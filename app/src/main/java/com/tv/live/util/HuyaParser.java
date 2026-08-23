package com.tv.live.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HuyaParser {

    public interface OnParseResultListener {
        void onSuccess(String hlsUrl, String flvUrl, boolean isTogetherWatch);
        void onFailed(String errorMsg);
    }

    private static class StreamCache {
        String hls;
        String flv;
        long timestamp;

        StreamCache(String hls, String flv) {
            this.hls = hls;
            this.flv = flv;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid() {
            return System.currentTimeMillis() - timestamp < 60_000L;
        }
    }

    private static final ConcurrentHashMap<Long, StreamCache> sCache = new ConcurrentHashMap<>();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final OkHttpClient sClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    public static void parse(final int roomId, final OnParseResultListener listener) {
        if (listener == null) {
            return;
        }
        final long roomLong = roomId;
        Log.d("HuyaParser", "开始解析房间：" + roomId);

        StreamCache cached = sCache.get(roomLong);
        if (cached != null && cached.isValid()) {
            Log.d("HuyaParser", "使用缓存：hls=" + cacheSafe(cached.hls));
            postSuccess(listener, cached.hls, cached.flv);
            return;
        }
        Log.d("HuyaParser", "缓存未命中，开始获取播放地址");

        Runnable worker = new Runnable() {
            @Override
            public void run() {
                try {
                    String hls = null;
                    String flv = null;

                    // 方案1：StreamInfo API (mp.huya.com) - 首选，已验证可用
                    try {
                        Log.d("HuyaParser", "尝试从StreamInfo API获取播放地址");
                        String streamInfoResult = fetchFromStreamInfoAPI(roomLong);
                        Log.d("HuyaParser", "从StreamInfoAPI获取到地址：" + (streamInfoResult == null ? "null" : streamInfoResult.length() + "字符"));
                        if (!TextUtils.isEmpty(streamInfoResult)) {
                            if (streamInfoResult.contains(".m3u8")) {
                                hls = streamInfoResult;
                            } else if (streamInfoResult.contains(".flv")) {
                                flv = streamInfoResult;
                            }
                        }
                        if (!TextUtils.isEmpty(hls) || !TextUtils.isEmpty(flv)) {
                            putCache(roomLong, hls, flv);
                            postSuccess(listener, hls, flv);
                            return;
                        }
                    } catch (Throwable t) {
                        Log.d("HuyaParser", "StreamInfoAPI 异常: " + t.getMessage());
                    }

                    // 方案2：移动端网页解析（主方案）
                    try {
                        Log.d("HuyaParser", "尝试从移动端网页获取播放地址");
                        String mHtml = fetchHtml("https://m.huya.com/" + roomId);
                        if (!TextUtils.isEmpty(mHtml)) {
                            String result = extractUrlFromHtml(mHtml, true);
                            if (!TextUtils.isEmpty(result)) {
                                Log.d("HuyaParser", "从移动端网页获取到地址：" + head(result, 60));
                                if (result.contains(".m3u8")) {
                                    hls = result;
                                } else if (result.contains(".flv")) {
                                    flv = result;
                                }
                            }
                        }
                        if (!TextUtils.isEmpty(hls) || !TextUtils.isEmpty(flv)) {
                            putCache(roomLong, hls, flv);
                            postSuccess(listener, hls, flv);
                            return;
                        }
                    } catch (Throwable t) {
                        Log.d("HuyaParser", "移动端网页解析异常：" + t.getMessage());
                    }

                    // 方案3：PC网页解析
                    try {
                        Log.d("HuyaParser", "尝试从PC网页获取播放地址");
                        String pcHtml = fetchHtml("https://www.huya.com/" + roomId);
                        if (!TextUtils.isEmpty(pcHtml)) {
                            String result = extractUrlFromHtml(pcHtml, false);
                            if (!TextUtils.isEmpty(result)) {
                                Log.d("HuyaParser", "从PC网页获取到地址：" + head(result, 60));
                                if (result.contains(".m3u8")) {
                                    hls = result;
                                } else if (result.contains(".flv")) {
                                    flv = result;
                                }
                            }
                        }
                        if (!TextUtils.isEmpty(hls) || !TextUtils.isEmpty(flv)) {
                            putCache(roomLong, hls, flv);
                            postSuccess(listener, hls, flv);
                            return;
                        }
                    } catch (Throwable t) {
                        Log.d("HuyaParser", "PC网页解析异常：" + t.getMessage());
                    }

                    // 方案4: 通过 StreamInfo API 备用
                    try {
                        Log.d("HuyaParser", "尝试从StreamInfo API获取播放地址");
                        String streamInfoResult = fetchFromStreamInfoAPI(roomLong);
                        if (!TextUtils.isEmpty(streamInfoResult)) {
                            if (streamInfoResult.contains(".m3u8")) {
                                hls = streamInfoResult;
                            } else if (streamInfoResult.contains(".flv")) {
                                flv = streamInfoResult;
                            }
                        }
                        if (!TextUtils.isEmpty(hls) || !TextUtils.isEmpty(flv)) {
                            putCache(roomLong, hls, flv);
                            postSuccess(listener, hls, flv);
                            return;
                        }
                    } catch (Throwable t) {
                        Log.d("HuyaParser", "StreamInfo API异常：" + t.getMessage());
                    }

                    postFailed(listener, "解析失败，无可用播放地址（4种方案均未返回有效URL）");
                } catch (Throwable t) {
                    Log.d("HuyaParser", "获取播放地址异常：" + t.getMessage());
                    postFailed(listener, "解析异常：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                }
            }
        };
        Thread t = new Thread(worker, "HuyaParser-" + roomId);
        t.start();
    }

    private static String buildFromLineObj(JSONObject line) {
        if (line == null) {
            return null;
        }
        String v = line.optString("sFlvUrl");
        if (TextUtils.isEmpty(v)) {
            v = line.optString("sHlsUrl");
        }
        String sName = line.optString("sStreamName");
        String suffix = line.optString("sFlvUrlSuffix");
        if (TextUtils.isEmpty(suffix)) {
            suffix = line.optString("sHlsUrlSuffix");
        }
        String antiCode = line.optString("sFlvAntiCode");
        if (TextUtils.isEmpty(antiCode)) {
            antiCode = line.optString("sHlsAntiCode");
        }
        if (TextUtils.isEmpty(v) || TextUtils.isEmpty(sName)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(v).append("/").append(sName);
        if (!TextUtils.isEmpty(suffix)) {
            sb.append(".").append(suffix);
        }
        if (!TextUtils.isEmpty(antiCode)) {
            sb.append(antiCode.contains("?") ? "&" : "?").append(antiCode);
        }
        return sb.toString();
    }

    // ================= 方案 2/3：HTML 抓取 =================

    private static String fetchHtml(String url) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .build();
        Response resp = sClient.newCall(req).execute();
        if (resp == null || !resp.isSuccessful() || resp.body() == null) {
            if (resp != null) {
                Log.d("HuyaParser", "fetchHtml请求失败，状态码：" + resp.code());
            }
            return null;
        }
        return resp.body().string();
    }

    // ================= 方案 4：StreamInfo API =================

    private static String fetchFromStreamInfoAPI(long roomId) throws Exception {
        String url = "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=" + roomId
                + "&_=" + System.currentTimeMillis();
        Request req = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .build();
        Response resp = sClient.newCall(req).execute();
        if (resp == null || !resp.isSuccessful() || resp.body() == null) {
            if (resp != null) {
                Log.d("HuyaParser", "fetchFromStreamInfoAPI请求失败，状态码：" + resp.code());
            }
            return null;
        }
        String jsonStr = resp.body().string();
        Log.d("HuyaParser", "StreamInfoAPI返回长度：" + jsonStr.length());
        if (TextUtils.isEmpty(jsonStr)) {
            return null;
        }
        Log.d("HuyaParser", "StreamInfoAPI前500字符：" + head(jsonStr, 500));
        try {
            JSONObject json = new JSONObject(jsonStr);
            Log.d("HuyaParser", "StreamInfoAPI JSON keys: " + json.keys());
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                Log.d("HuyaParser", "data keys: " + data.keys());
                JSONObject live = data.optJSONObject("liveData");
                if (live != null) {
                    JSONArray streamList = live.optJSONArray("stream");
                    if (streamList == null) {
                        streamList = live.optJSONArray("hybridStream");
                    }
                    if (streamList == null) {
                        streamList = data.optJSONArray("gameLiveInfo");
                    }
                    if (streamList != null && streamList.length() > 0) {
                        String built = buildFromLineObj(streamList.optJSONObject(0));
                        if (!TextUtils.isEmpty(built)) {
                            return built;
                        }
                    }
                    JSONArray iList = live.optJSONArray("gameStreamInfoList");
                    if (iList != null && iList.length() > 0) {
                        String built = buildFromLineObj(iList.optJSONObject(0));
                        if (!TextUtils.isEmpty(built)) {
                            return built;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.d("HuyaParser", "解析StreamInfoAPI失败：" + t.getMessage());
        }
        return extractUrlFromJsonString(jsonStr);
    }

    // ================= 工具方法 =================

    private static String extractUrlFromJsonString(String json) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            List<String> candidates = new ArrayList<>();
            String[] urlMarkers = new String[]{
                    "\"sFlvUrl\":\"", "\"sHlsUrl\":\"",
                    "https://", "http://"
            };
            for (String marker : urlMarkers) {
                int idx = 0;
                while ((idx = json.indexOf(marker, idx)) != -1) {
                    int start = marker.startsWith("http") ? idx : idx + marker.length();
                    int end = json.indexOf('"', start);
                    int end2 = json.indexOf('\'', start);
                    int end3 = json.indexOf(',', start);
                    int realEnd = Integer.MAX_VALUE;
                    if (end > 0) {
                        realEnd = end;
                    }
                    if (end2 > 0 && end2 < realEnd) {
                        realEnd = end2;
                    }
                    if (end3 > 0 && end3 < realEnd) {
                        realEnd = end3;
                    }
                    if (realEnd == Integer.MAX_VALUE) {
                        realEnd = Math.min(start + 800, json.length());
                    }
                    if (realEnd > start + 20) {
                        String candidate = json.substring(start, realEnd);
                        candidate = candidate.replace("\\/", "/")
                                .replace("\\u0026", "&")
                                .replace("\\n", "")
                                .replace("\\\"", "\"");
                        if (candidate.startsWith("http")
                                && (candidate.contains(".m3u8") || candidate.contains(".flv"))
                                && candidate.contains("huya")) {
                            candidates.add(candidate);
                        }
                    }
                    idx = start + 1;
                }
                if (!candidates.isEmpty()) {
                    break;
                }
            }
            if (!candidates.isEmpty()) {
                for (String c : candidates) {
                    if (c.contains(".m3u8")) {
                        return c;
                    }
                }
                return candidates.get(0);
            }
        } catch (Throwable t) {
            Log.d("HuyaParser", "extractUrlFromJsonString异常：" + t.getMessage());
        }
        return null;
    }

    private static String extractUrlFromHtml(String html, boolean isMobile) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        try {
            String[] markers = new String[]{
                    "var streamData =",
                    "\"gameLiveInfo\":",
                    "\"HY_GAME_LIVE_DATA\":",
                    "gameStreamInfoList",
                    "hyPlayerConfig ="
            };
            for (String m : markers) {
                int idx = html.indexOf(m);
                if (idx < 0) {
                    continue;
                }
                int start = idx + m.length();
                int len = Math.min(start + 5000, html.length());
                String chunk = html.substring(start, len);
                String extracted = extractUrlFromJsonString(chunk);
                if (!TextUtils.isEmpty(extracted)) {
                    return extracted;
                }
            }
            String[] protocols = new String[]{"https://", "http://"};
            for (String p : protocols) {
                int idx = 0;
                while ((idx = html.indexOf(p, idx)) != -1) {
                    int e1 = html.indexOf('"', idx);
                    int e2 = html.indexOf('\'', idx);
                    int e3 = html.indexOf('<', idx);
                    int e4 = html.indexOf('&', idx);
                    int end = Integer.MAX_VALUE;
                    if (e1 > 0 && e1 < end) {
                        end = e1;
                    }
                    if (e2 > 0 && e2 < end) {
                        end = e2;
                    }
                    if (e3 > 0 && e3 < end) {
                        end = e3;
                    }
                    if (e4 > 0 && e4 < end) {
                        end = e4;
                    }
                    if (end == Integer.MAX_VALUE) {
                        end = Math.min(idx + 800, html.length());
                    }
                    if (end - idx > 30) {
                        String cand = html.substring(idx, end);
                        cand = cand.replace("&amp;", "&").replace("\\/", "/");
                        if ((cand.contains(".m3u8") || cand.contains(".flv")) && cand.contains("huya")) {
                            return cand;
                        }
                    }
                    idx = idx + 1;
                }
            }
        } catch (Throwable t) {
            Log.d("HuyaParser", "extractUrlFromHtml异常：" + t.getMessage());
        }
        return null;
    }

    // ================= 辅助方法 =================

    private static void putCache(long roomId, String hls, String flv) {
        sCache.put(roomId, new StreamCache(hls, flv));
    }

    private static String cacheSafe(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() < 80 ? s : s.substring(0, 80) + "...";
    }

    private static String head(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() < max ? s : s.substring(0, max) + "...";
    }

    private static void postSuccess(final OnParseResultListener listener, final String hls, final String flv) {
        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onSuccess(hls, flv, true);
                } catch (Throwable ignore) {
                }
            }
        });
    }

    private static void postFailed(final OnParseResultListener listener, final String errorMsg) {
        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onFailed(errorMsg);
                } catch (Throwable ignore) {
                }
            }
        });
    }
}
