package com.tv.live.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Headers;

/**
 * 虎牙纯原生解析器（不依赖 SDK / JsLayer / WebView）
 *
 * 多源回退：PC 网页 → 移动端网页 → LiveAPI → StreamInfoAPI
 *
 * 返回：hlsUrl + flvUrl + 配套请求头 Map（用于 Media3 拉流时注入 UA/Referer/Cookie，
 *       避免 302 跳转到 CDN 节点时清空鉴权头导致的 403）
 */
public class HuyaPureParser {

    private static final String TAG = "HuyaPureParser";

    // 签名字段 wsSecret/wsTime 时效一般 60-90 秒，这里保守设为 60 秒
    // 临近过期（剩余 < TTL_WINDOW_MS）会自动重新解析，避免复用过期签名
    private static final long CACHE_VALID_MS = 60 * 1000L;
    private static final long TTL_WINDOW_MS   = 10 * 1000L;

    private static final ConcurrentHashMap<Integer, CacheItem> SOURCE_CACHE = new ConcurrentHashMap<>();

    private static final String PC_URL     = "https://www.huya.com/%d";
    private static final String MOBILE_URL = "https://m.huya.com/%d";
    private static final String LIVE_API   = "https://live-api.huya.com/moment/getLiveInfo?roomId=%d";
    private static final String STREAM_API = "https://www.huya.com/cache.php?m=LiveList&do=getLivePlayInfo&roomId=%d";

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    // =====================================================================
    // 浏览器级完整请求头（解析 PC 网页用 + 播放器拉流复用）
    // 注意：每一个 ts 分片请求都必须携带相同的 UA/Referer，虎牙 CDN 会校验 Referer 来源
    // =====================================================================
    static final Map<String, String> BROWSER_HEADERS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("User-Agent",      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        m.put("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        m.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        m.put("Connection",      "keep-alive");
        m.put("Cache-Control",   "max-age=0");
        m.put("Sec-Ch-Ua",       "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"");
        m.put("Sec-Ch-Ua-Mobile","?0");
        m.put("Sec-Ch-Ua-Platform", "\"Windows\"");
        m.put("Sec-Fetch-Dest",  "document");
        m.put("Sec-Fetch-Mode",  "navigate");
        m.put("Sec-Fetch-Site",  "same-origin");
        m.put("Sec-Fetch-User",  "?1");
        m.put("Upgrade-Insecure-Requests", "1");
        BROWSER_HEADERS = Collections.unmodifiableMap(m);
    }

    // 关闭自动重定向 —— 302 时 HttpURLConnection/OkHttp 默认会丢弃 UA/Referer
    // 我们自己做递归跳转，全程保留 headers
    private static final OkHttpClient sClient;
    static {
        sClient = new OkHttpClient.Builder()
                .connectTimeout(8000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(10000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .followRedirects(false)        // 关键：禁止自动重定向
                .followSslRedirects(false)
                .cookieJar(new okhttp3.CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        try {
                            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                            for (Cookie cookie : cookies) {
                                // 使用 RFC6265 字符串存储（name=value; path=/; domain=...; secure; httponly）
                                cm.setCookie(url.toString(), cookie.toString());
                            }
                            cm.flush();
                        } catch (Throwable t) {
                            Log.v(TAG, "saveCookie 忽略: " + t.getMessage());
                        }
                    }

                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                        String cookieStr = cm.getCookie(url.toString());
                        List<Cookie> result = new ArrayList<>();
                        if (cookieStr == null || cookieStr.isEmpty()) return result;
                        for (String pair : cookieStr.split(";\\s*")) {
                            int eq = pair.indexOf('=');
                            if (eq <= 0) continue;
                            String name  = pair.substring(0, eq).trim();
                            String value = pair.substring(eq + 1).trim();
                            try {
                                result.add(new Cookie.Builder()
                                        .name(name).value(value)
                                        .domain(url.host())
                                        .path("/")
                                        .build());
                            } catch (Throwable ignored) {}
                        }
                        return result;
                    }
                })
                .build();
    }

    // =====================================================================
    // 对外接口
    // =====================================================================

    /**
     * 解析成功时返回 hlsUrl / flvUrl 以及建议播放器拉流使用的请求头集合
     * headers 包含：User-Agent / Referer / Origin / Accept / Cookie 等
     */
    public interface OnParseResultListener {
        void onSuccess(String hlsUrl, String flvUrl, Map<String, String> headers);
        void onFailed(String errorMsg);
    }

    private static class CacheItem {
        final String hls, flv;
        final Map<String, String> headers;
        final long expireAtMs;

        CacheItem(String h, String f, Map<String, String> hdrs, long expireAt) {
            hls = h; flv = f; headers = hdrs; expireAtMs = expireAt;
        }
    }

    public static void parse(int roomId, OnParseResultListener listener) {
        Log.d(TAG, "开始解析房间: " + roomId);

        if (roomId <= 0) {
            postFailed(listener, "房间号不合法");
            return;
        }

        CacheItem cache = SOURCE_CACHE.get(roomId);
        long now = System.currentTimeMillis();
        if (cache != null) {
            long ttl = cache.expireAtMs - now;
            if (ttl > TTL_WINDOW_MS) {
                Log.d(TAG, "命中缓存(剩余" + (ttl / 1000) + "s), hls=" + (cache.hls != null));
                postSuccess(listener, cache.hls, cache.flv, cache.headers);
                return;
            } else {
                Log.d(TAG, "缓存临近过期(剩余" + (ttl / 1000) + "s), 自动刷新");
                SOURCE_CACHE.remove(roomId);
            }
        }

        new Thread(() -> {
            try {
                ParseResult result = doParse(roomId);
                if (result != null && (!TextUtils.isEmpty(result.hls) || !TextUtils.isEmpty(result.flv))) {
                    long expireAt = System.currentTimeMillis() + CACHE_VALID_MS;
                    // 构建复用给播放器的 headers
                    Map<String, String> hdrs = buildPlaybackHeaders(result.hls, result.flv);
                    SOURCE_CACHE.put(roomId, new CacheItem(result.hls, result.flv, hdrs, expireAt));
                    postSuccess(listener, result.hls, result.flv, hdrs);
                } else {
                    postFailed(listener, "未获取到有效播放地址");
                }
            } catch (Exception e) {
                Log.e(TAG, "解析异常: " + e.getMessage(), e);
                postFailed(listener, "解析异常: " + e.getMessage());
            }
        }, "HuyaPureParser").start();
    }

    // =====================================================================
    // 核心解析
    // =====================================================================

    private static class ParseResult {
        final String hls, flv;
        ParseResult(String h, String f) { hls = h; flv = f; }
    }

    private static ParseResult doParse(int roomId) {
        Log.d(TAG, "1. 尝试 PC 网页解析...");
        String[] r = parsePcPage(roomId);
        if (r != null && isValidResult(r)) {
            Log.d(TAG, "PC 网页解析成功");
            return new ParseResult(r[0], r[1]);
        }

        Log.d(TAG, "2. 尝试移动端网页解析...");
        r = parseMobilePage(roomId);
        if (r != null && isValidResult(r)) {
            Log.d(TAG, "移动端解析成功");
            return new ParseResult(r[0], r[1]);
        }

        Log.d(TAG, "3. 尝试 LiveAPI 解析...");
        r = parseLiveApi(roomId);
        if (r != null && isValidResult(r)) {
            Log.d(TAG, "LiveAPI 解析成功");
            return new ParseResult(r[0], r[1]);
        }

        Log.d(TAG, "4. 尝试 StreamInfoAPI 解析...");
        r = parseStreamInfoApi(roomId);
        if (r != null && isValidResult(r)) {
            Log.d(TAG, "StreamInfoAPI 解析成功");
            return new ParseResult(r[0], r[1]);
        }

        Log.w(TAG, "所有解析源均失败");
        return null;
    }

    // ===== PC 网页解析 =====
    private static String[] parsePcPage(int roomId) {
        try {
            String html = fetchHtmlManualRedirect(String.format(PC_URL, roomId), true, 0);
            if (TextUtils.isEmpty(html)) {
                Log.d(TAG, "PC 页面为空");
                return null;
            }
            return extractFromHtml(html);
        } catch (Exception e) {
            Log.d(TAG, "PC 页面解析异常: " + e.getMessage());
            return null;
        }
    }

    // ===== 移动端网页解析 =====
    private static String[] parseMobilePage(int roomId) {
        try {
            String html = fetchHtmlManualRedirect(String.format(MOBILE_URL, roomId), false, 0);
            if (TextUtils.isEmpty(html)) return null;
            return extractFromHtml(html);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== LiveAPI =====
    private static String[] parseLiveApi(int roomId) {
        try {
            String url  = String.format(LIVE_API, roomId);
            String json = fetchTextManualRedirect(url, true, 0);
            if (TextUtils.isEmpty(json)) return null;

            JSONObject root = new JSONObject(json);
            if (root.optInt("code", -1) != 0) return null;

            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;

            String hls = "", flv = "";
            String[] paths = { "stream", "gameLiveInfo.liveStreamInfo", "liveData.tLiveInfo.tLiveStreamInfo" };
            for (String path : paths) {
                String[] ext = extractFromJsonPath(data, path);
                if (!TextUtils.isEmpty(ext[0]) && TextUtils.isEmpty(hls)) hls = ext[0];
                if (!TextUtils.isEmpty(ext[1]) && TextUtils.isEmpty(flv)) flv = ext[1];
                if (!TextUtils.isEmpty(hls) && !TextUtils.isEmpty(flv)) break;
            }
            return new String[]{hls, flv};
        } catch (Exception e) {
            return null;
        }
    }

    // ===== StreamInfoAPI =====
    private static String[] parseStreamInfoApi(int roomId) {
        try {
            String url  = String.format(STREAM_API, roomId);
            String json = fetchTextManualRedirect(url, true, 0);
            if (TextUtils.isEmpty(json)) return null;

            if (json.contains("<!DOCTYPE")) return extractFromHtml(json);

            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;

            String hls = "", flv = "";
            JSONObject stream = data.optJSONObject("stream");
            if (stream != null) {
                hls = stream.optString("hls", "");
                flv = stream.optString("flv", "");
                if (TextUtils.isEmpty(hls)) hls = stream.optString("sHlsUrl", "");
                if (TextUtils.isEmpty(flv)) flv = stream.optString("sFlvUrl", "");
                String ha = stream.optString("sHlsAntiCode", "");
                String fa = stream.optString("sFlvAntiCode", "");
                hls = appendAnti(hls, ha);
                flv = appendAnti(flv, fa);
            }
            if (TextUtils.isEmpty(hls) && TextUtils.isEmpty(flv)) {
                JSONObject glInfo = data.optJSONObject("gameLiveInfo");
                if (glInfo != null) {
                    JSONObject lsi = glInfo.optJSONObject("liveStreamInfo");
                    if (lsi != null) {
                        hls = lsi.optString("sHlsUrl", "");
                        flv = lsi.optString("sFlvUrl", "");
                        String ha = lsi.optString("sHlsAntiCode", "");
                        String fa = lsi.optString("sFlvAntiCode", "");
                        hls = appendAnti(hls, ha);
                        flv = appendAnti(flv, fa);
                    }
                }
            }
            return new String[]{hls, flv};
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    // 反序列化提取
    // =====================================================================

    private static String[] extractFromJsonPath(JSONObject obj, String path) {
        try {
            String[] parts = path.split("\\.");
            JSONObject cur = obj;
            for (String part : parts) {
                if (cur == null) break;
                if (part.equals("stream")) {
                    JSONObject s = cur.optJSONObject(part);
                    if (s != null) {
                        String hls  = s.optString("sHlsUrl", "");
                        String flv  = s.optString("sFlvUrl", "");
                        String ha   = s.optString("sHlsAntiCode", "");
                        String fa   = s.optString("sFlvAntiCode", "");
                        return new String[]{appendAnti(hls, ha), appendAnti(flv, fa)};
                    }
                    break;
                } else {
                    cur = cur.optJSONObject(part);
                }
            }
            if (cur != null) {
                String hls  = cur.optString("sHlsUrl", "");
                String flv  = cur.optString("sFlvUrl", "");
                String ha   = cur.optString("sHlsAntiCode", "");
                String fa   = cur.optString("sFlvAntiCode", "");
                JSONArray streams = cur.optJSONArray("vMultiStreamInfo");
                if (streams != null && streams.length() > 0) {
                    JSONObject it = streams.getJSONObject(0);
                    String h2  = it.optString("sHlsUrl", "");
                    String f2  = it.optString("sFlvUrl", "");
                    String h2a = it.optString("sHlsAntiCode", "");
                    String f2a = it.optString("sFlvAntiCode", "");
                    if (TextUtils.isEmpty(hls) && !TextUtils.isEmpty(h2)) hls = h2;
                    if (TextUtils.isEmpty(flv) && !TextUtils.isEmpty(f2)) flv = f2;
                    if (TextUtils.isEmpty(ha)  && !TextUtils.isEmpty(h2a)) ha = h2a;
                    if (TextUtils.isEmpty(fa)  && !TextUtils.isEmpty(f2a)) fa = f2a;
                }
                return new String[]{appendAnti(hls, ha), appendAnti(flv, fa)};
            }
        } catch (Exception ignored) {}
        return new String[]{null, null};
    }

    // =====================================================================
    // 🔴【核心】虎牙 antiCode 重新签名算法（从 APK SdkLiveService.getSAntiCode() 逆向移植）
    //
    // 原始 antiCode 格式: fm=xxx&wsSecret=xxx&wsTime=xxx
    // APK 内部会重新计算签名，CDN 校验的是重新签名后的 antiCode
    // 这是 403 的根本原因 — 直接拼接原始 antiCode 会被 CDN 拒绝
    // =====================================================================

    /**
     * 从播放 URL 中提取 streamName（路径最后一段，不含查询参数）
     * 例如: https://al.hls.huya.com/src?xxx → src
     */
    private static String extractStreamName(String url) {
        try {
            int pathEnd = url.indexOf('?');
            String path = pathEnd > 0 ? url.substring(0, pathEnd) : url;
            int lastSlash = path.lastIndexOf('/');
            return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        } catch (Exception e) {
            return "src";
        }
    }

    /**
     * Base64 解码（对应 APK 中的 decodeData() 方法）
     * 本质: Base64.decode(str.getBytes("UTF-8"))
     */
    private static String decodeData(String encoded) {
        try {
            byte[] decoded = android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE);
            return new String(decoded, "UTF-8");
        } catch (Exception e) {
            try {
                byte[] decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                return new String(decoded, "UTF-8");
            } catch (Exception e2) {
                return encoded;
            }
        }
    }

    /**
     * 重新生成 antiCode（对应 APK 中 SdkLiveService.getSAntiCode()）
     *
     * 关键：wsSecret 和 wsTime 是十六进制字符串（不需要 Base64 解码）
     *       fm 参数需要 Base64 解码，解码后是模板字符串如: DWq8BcJ3h6DJt6TQY_$0_$1_$2_$3
     *       其中 $0-$3 是占位符，需要替换为实际值
     *
     * @param rawAntiCode 原始 antiCode (fm=xxx&wsSecret=xxx&wsTime=xxx)
     * @param streamUrl  基础流 URL (用于提取 streamName)
     * @return 重新签名后的 antiCode
     */
    private static String getSAntiCode(String rawAntiCode, String streamUrl) {
        if (TextUtils.isEmpty(rawAntiCode)) return rawAntiCode;

        try {
            // 1. 解析原始 antiCode 参数
            Map<String, String> params = new HashMap<>();
            for (String part : rawAntiCode.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    String k = part.substring(0, eq);
                    String v = part.substring(eq + 1);
                    params.put(k, v);
                }
            }

            String fm = params.containsKey("fm") ? params.get("fm") : "";
            String wsSecret = params.containsKey("wsSecret") ? params.get("wsSecret") : "";
            String wsTime = params.containsKey("wsTime") ? params.get("wsTime") : "";

            if (TextUtils.isEmpty(wsSecret) || TextUtils.isEmpty(wsTime)) {
                return rawAntiCode;
            }

            // 2. wsSecret 和 wsTime 是十六进制字符串，直接使用（不解码）
            Log.d(TAG, "getSAntiCode 原始: fm=" + fm + " wsSecret=" + wsSecret + " wsTime=" + wsTime);

            // 3. 提取 streamName
            String streamName = extractStreamName(streamUrl);

            // 4. URL 解码 fm，然后 Base64 解码获取模板
            //    fm 解码后形如: DWq8BcJ3h6DJt6TQY_$0_$1_$2_$3
            String fmTemplate = "";
            try {
                String fmUrlDecoded = java.net.URLDecoder.decode(fm, "UTF-8");
                byte[] fmDecoded = android.util.Base64.decode(fmUrlDecoded, android.util.Base64.URL_SAFE);
                fmTemplate = new String(fmDecoded, "UTF-8");
                Log.d(TAG, "getSAntiCode fm模板: " + fmTemplate);
            } catch (Exception e) {
                Log.d(TAG, "getSAntiCode fm解码失败，使用原始 fm: " + e.getMessage());
                fmTemplate = fm;
            }

            // 5. 替换模板中的 $0 $1 $2 $3 占位符
            //    $0 = seqid (即 fm 的值，原始的未解码 fm)
            //    $1 = wsTime
            //    $2 = streamName
            //    $3 = uuid (uid，使用 "0")
            String uid = "0";
            String seqid = fm; // 注意：这里用原始 fm 值作为 seqid
            String signStr = fmTemplate
                    .replace("$0", seqid)
                    .replace("$1", wsTime)
                    .replace("$2", streamName)
                    .replace("$3", uid);

            Log.d(TAG, "getSAntiCode 签名原文: " + signStr);

            // 6. MD5 哈希
            String sign = md5(signStr);
            Log.d(TAG, "getSAntiCode MD5: " + sign);

            // 7. 组装新 antiCode
            return "wsSecret=" + sign + "&wsTime=" + wsTime + "&u=" + uid + "&seqid=" + fm;
        } catch (Exception e) {
            Log.d(TAG, "getSAntiCode 异常，使用原始 antiCode: " + e.getMessage());
            return rawAntiCode;
        }
    }

    /**
     * MD5 哈希（对应 APK 中 AppUtils.md5()）
     */
    private static String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String appendAnti(String base, String anti) {
        if (TextUtils.isEmpty(base) || TextUtils.isEmpty(anti)) return base;
        // 🔴【修复】先用原始 antiCode 拼接（PC 网页解析到的 antiCode 本身可能就是有效的）
        // 先确保 Cookie/Referer 风控通过，antiCode 重签作为备选方案
        if (base.contains("?")) {
            int q = base.indexOf("?");
            return base.substring(0, q) + "?" + anti;
        }
        return base + "?" + anti;
    }

    // ===== 从 HTML 中抽取（hyPlayerConfig + __INITIAL_STATE__ 双通道）=====
    private static String[] extractFromHtml(String html) {
        String[] r = extractFromHyPlayerConfig(html);
        if (r != null && (r[0] != null || r[1] != null)) return r;

        r = extractFromInitialState(html);
        if (r != null && (r[0] != null || r[1] != null)) return r;

        // 最后兜底：全文正则
        String hls = "", flv = "";
        Pattern hUrlP  = Pattern.compile("sHlsUrl[^,}]*:\\s*[\"']([^\"']+)[\"']");
        Pattern hAntiP = Pattern.compile("sHlsAntiCode[^,}]*:\\s*[\"']([^\"']+)[\"']");
        Matcher hm  = hUrlP.matcher(html);
        Matcher ham = hAntiP.matcher(html);
        if (hm.find()) {
            hls = hm.group(1);
            if (ham.find()) hls = appendAnti(hls, ham.group(1));
        }
        Pattern fUrlP  = Pattern.compile("sFlvUrl[^,}]*:\\s*[\"']([^\"']+)[\"']");
        Pattern fAntiP = Pattern.compile("sFlvAntiCode[^,}]*:\\s*[\"']([^\"']+)[\"']");
        Matcher fm  = fUrlP.matcher(html);
        Matcher fam = fAntiP.matcher(html);
        if (fm.find()) {
            flv = fm.group(1);
            if (fam.find()) flv = appendAnti(flv, fam.group(1));
        }
        if (TextUtils.isEmpty(hls)) {
            Pattern m3u8P = Pattern.compile("https?://[^\"'\\s,]+\\.m3u8[^\"'\\s,]*");
            Matcher mp = m3u8P.matcher(html);
            if (mp.find()) hls = mp.group(0);
        }
        if (TextUtils.isEmpty(flv)) {
            Pattern flvP = Pattern.compile("https?://[^\"'\\s,]+\\.flv[^\"'\\s,]*");
            Matcher fp = flvP.matcher(html);
            if (fp.find()) flv = fp.group(0);
        }
        // 统一 HTTPS
        if (!TextUtils.isEmpty(hls) && hls.startsWith("http://")) hls = "https://" + hls.substring(7);
        if (!TextUtils.isEmpty(flv) && flv.startsWith("http://")) flv = "https://" + flv.substring(7);
        return new String[]{hls, flv};
    }

    private static String[] extractFromHyPlayerConfig(String html) {
        try {
            Pattern p = Pattern.compile("hyPlayerConfig\\s*=\\s*(\\{.*?\\})\\s*[;\n]", Pattern.DOTALL);
            Matcher m = p.matcher(html);
            if (!m.find()) return null;
            String configJson = m.group(1);
            Log.d(TAG, "hyPlayerConfig 长度: " + configJson.length());

            String hls = null, flv = null;
            Pattern lsiPattern = Pattern.compile("\"liveStreamInfo\"\\s*:\\s*(\\{[^}]+\\})", Pattern.DOTALL);
            Matcher lsiMatcher = lsiPattern.matcher(configJson);
            if (lsiMatcher.find()) {
                String lsiJson = lsiMatcher.group(1);
                hls = extractStringField(lsiJson, "sHlsUrl");
                String ha = extractStringField(lsiJson, "sHlsAntiCode");
                flv = extractStringField(lsiJson, "sFlvUrl");
                String fa = extractStringField(lsiJson, "sFlvAntiCode");
                hls = appendAnti(hls, ha);
                flv = appendAnti(flv, fa);
                if (!TextUtils.isEmpty(hls) || !TextUtils.isEmpty(flv)) {
                    if (!TextUtils.isEmpty(hls) && hls.startsWith("http://")) hls = "https://" + hls.substring(7);
                    if (!TextUtils.isEmpty(flv) && flv.startsWith("http://")) flv = "https://" + flv.substring(7);
                    Log.d(TAG, "hyPlayerConfig 提取成功(方法1): hls=" + (hls != null) + " flv=" + (flv != null));
                    return new String[]{hls, flv};
                }
            }

            hls = extractStringField(configJson, "sHlsUrl");
            String ha = extractStringField(configJson, "sHlsAntiCode");
            flv = extractStringField(configJson, "sFlvUrl");
            String fa = extractStringField(configJson, "sFlvAntiCode");
            // 🔴【调试】打印原始 antiCode，检查是否缺少 seqid/uuid 等参数
            Log.d(TAG, "原始 sHlsUrl=" + hls);
            Log.d(TAG, "原始 sHlsAntiCode=" + ha);
            Log.d(TAG, "原始 sFlvUrl=" + flv);
            Log.d(TAG, "原始 sFlvAntiCode=" + fa);
            hls = appendAnti(hls, ha);
            flv = appendAnti(flv, fa);
            if (!TextUtils.isEmpty(hls) && hls.startsWith("http://")) hls = "https://" + hls.substring(7);
            if (!TextUtils.isEmpty(flv) && flv.startsWith("http://")) flv = "https://" + flv.substring(7);

            Log.d(TAG, "hyPlayerConfig 提取成功(方法2): hls=" + (hls != null) + " flv=" + (flv != null));
            return new String[]{hls, flv};
        } catch (Exception e) {
            Log.d(TAG, "extractFromHyPlayerConfig 异常: " + e.getMessage());
            return null;
        }
    }

    private static String[] extractFromInitialState(String html) {
        try {
            Pattern p = Pattern.compile("window\\.__INITIAL_STATE__\\s*=\\s*(\\{.*?\\})\\s*</script>", Pattern.DOTALL);
            Matcher m = p.matcher(html);
            if (!m.find()) return null;
            String jsonStr = m.group(1);
            JSONObject root = new JSONObject(jsonStr);
            // 常见路径：roomInfo.stream / roomInfo.gameLiveInfo.liveStreamInfo
            String[][] paths = {
                    {"roomInfo", "stream"},
                    {"roomInfo", "gameLiveInfo", "liveStreamInfo"},
                    {"liveData", "tLiveInfo", "tLiveStreamInfo"}
            };
            for (String[] parts : paths) {
                JSONObject cur = root;
                for (int i = 0; i < parts.length && cur != null; i++) {
                    cur = cur.optJSONObject(parts[i]);
                }
                if (cur == null) continue;
                String hls = cur.optString("sHlsUrl", "");
                String flv = cur.optString("sFlvUrl", "");
                String ha  = cur.optString("sHlsAntiCode", "");
                String fa  = cur.optString("sFlvAntiCode", "");
                hls = appendAnti(hls, ha);
                flv = appendAnti(flv, fa);
                if (!TextUtils.isEmpty(hls) || !TextUtils.isEmpty(flv)) {
                    if (!TextUtils.isEmpty(hls) && hls.startsWith("http://")) hls = "https://" + hls.substring(7);
                    if (!TextUtils.isEmpty(flv) && flv.startsWith("http://")) flv = "https://" + flv.substring(7);
                    return new String[]{hls, flv};
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // =====================================================================
    // 手动处理 302 重定向 —— 关键修复：避免丢失 UA / Referer
    // =====================================================================

    private static final int MAX_REDIRECTS = 5;

    private static String fetchHtmlManualRedirect(String url, boolean isHuya, int depth) {
        if (depth > MAX_REDIRECTS) {
            Log.d(TAG, "fetchHtml 重定向次数超限");
            return "";
        }
        try {
            Request.Builder rb = new Request.Builder().url(url).get();
            addBrowserHeaders(rb, isHuya, url);
            try (Response resp = sClient.newCall(rb.build()).execute()) {
                int code = resp.code();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = resp.header("Location");
                    if (TextUtils.isEmpty(loc)) return "";
                    String next = resolveRedirect(url, loc);
                    Log.d(TAG, "fetchHtml 302 → " + next.substring(0, Math.min(80, next.length())));
                    return fetchHtmlManualRedirect(next, isHuya, depth + 1);
                }
                if (!resp.isSuccessful() || resp.body() == null) {
                    Log.d(TAG, "fetchHtml 失败: code=" + code);
                    return "";
                }
                return resp.body().string();
            }
        } catch (Exception e) {
            Log.d(TAG, "fetchHtml 异常: " + e.getMessage());
            return "";
        }
    }

    private static String fetchTextManualRedirect(String url, boolean isHuya, int depth) {
        if (depth > MAX_REDIRECTS) return "";
        try {
            Request.Builder rb = new Request.Builder().url(url).get();
            addBrowserHeaders(rb, isHuya, url);
            try (Response resp = sClient.newCall(rb.build()).execute()) {
                int code = resp.code();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = resp.header("Location");
                    if (TextUtils.isEmpty(loc)) return "";
                    String next = resolveRedirect(url, loc);
                    return fetchTextManualRedirect(next, isHuya, depth + 1);
                }
                if (!resp.isSuccessful() || resp.body() == null) return "";
                return resp.body().string();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String resolveRedirect(String baseUrl, String location) {
        try {
            HttpUrl base = HttpUrl.parse(baseUrl);
            if (base == null) return location;
            HttpUrl next = base.resolve(location);
            return next != null ? next.toString() : location;
        } catch (Exception e) {
            return location;
        }
    }

    private static void addBrowserHeaders(Request.Builder rb, boolean isHuya, String requestUrl) {
        for (Map.Entry<String, String> e : BROWSER_HEADERS.entrySet()) {
            // 浏览器级 headers (UA/Accept/... ) 全量注入
            rb.header(e.getKey(), e.getValue());
        }
        if (isHuya) {
            rb.header("Referer", "https://www.huya.com/");
            rb.header("Origin",  "https://www.huya.com");
        }
        // 读取并携带已有的 WebView cookies（风控 cookie：__yamid_new / __yasmid / ...）
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String cookieStr = cm.getCookie(requestUrl);
            if (cookieStr != null && !cookieStr.isEmpty()) {
                rb.header("Cookie", cookieStr);
            }
        } catch (Throwable ignored) {}
    }

    // =====================================================================
    // 播放器复用请求头构建
    // 关键点：Media3 的每个 m3u8 主列表 + m3u8 子列表 + ts 分片请求，
    // 都必须携带相同的 UA/Referer/Origin/Cookie，CDN 对 Referer 有严格校验
    // =====================================================================
    static Map<String, String> buildPlaybackHeaders(String hlsUrl, String flvUrl) {
        Map<String, String> m = new LinkedHashMap<>();
        // 与解析 PC 网页时相同的 UA
        m.put("User-Agent", BROWSER_HEADERS.get("User-Agent"));
        m.put("Accept",          "*/*");
        m.put("Accept-Language", BROWSER_HEADERS.get("Accept-Language"));
        m.put("Connection",      "keep-alive");
        // 虎牙专属 Referer / Origin（所有 CDN 节点：tx.hls.huya.com / al.hls.huya.com ... 都校验来源）
        m.put("Referer", "https://www.huya.com/");
        m.put("Origin",  "https://www.huya.com");
        // 避免部分 CDN 拒绝 gzip 解压失败
        m.put("Accept-Encoding", "identity");

        // cookies：从 WebView CookieManager 合并进 headers 字段，
        //   这样 HttpDataSource（即使没有自动读取 cookies）也能一并携带
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String anyUrl = !TextUtils.isEmpty(hlsUrl) ? hlsUrl
                         : (!TextUtils.isEmpty(flvUrl) ? flvUrl : "https://www.huya.com/");
            String c = cm.getCookie(anyUrl);
            String d = cm.getCookie("https://www.huya.com/");
            StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(c)) sb.append(c);
            if (!TextUtils.isEmpty(d)) {
                if (sb.length() > 0 && !sb.toString().endsWith("; ")) sb.append("; ");
                sb.append(d);
            }
            if (sb.length() > 0) {
                m.put("Cookie", sb.toString());
            }
        } catch (Throwable ignored) {}
        return m;
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private static String extractStringField(String json, String fieldName) {
        try {
            Pattern p = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"");
            Matcher m = p.matcher(json);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isValidResult(String[] r) {
        if (r == null) return false;
        return !TextUtils.isEmpty(r[0]) || !TextUtils.isEmpty(r[1]);
    }

    private static void postSuccess(final OnParseResultListener l,
                                    final String hls, final String flv, final Map<String, String> hdrs) {
        if (l == null) return;
        sHandler.post(() -> l.onSuccess(hls, flv, hdrs));
    }

    private static void postFailed(final OnParseResultListener l, final String msg) {
        if (l == null) return;
        sHandler.post(() -> l.onFailed(msg));
    }
}
