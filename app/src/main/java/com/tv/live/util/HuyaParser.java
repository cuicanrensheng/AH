package com.tv.live.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.tv.live.jsparser.JsLayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Iterator;
import java.util.Locale;

import okhttp3.Response;

public class HuyaParser {
    private static final Handler mMainHandler = new Handler(Looper.getMainLooper());
    
    private static final ConcurrentHashMap<Integer, CacheItem> SOURCE_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_VALID_MS = 110 * 1000;

    // 🟢 虎牙官方 API 接口
    private static final String API_MOBILE_ROOM = "https://m.huya.com/%d";
    private static final String API_PC_ROOM = "https://www.huya.com/%d";
    private static final String API_STREAM_INFO = "https://www.huya.com/cache.php?m=LiveList&do=getLivePlayInfo&roomId=%d";
    private static final String API_LIVE_INFO = "https://live-api.huya.com/moment/getLiveInfo?roomId=%d";

    public interface OnParseResultListener {
        void onSuccess(String hlsUrl, String flvUrl, boolean isTogetherWatch);
        void onFailed(String errorMsg);
    }

    private static class CacheItem {
        String hls;
        String flv;
        boolean isTogether;
        long expireTime;
        CacheItem(String h, String f, boolean t, long exp) {
            hls = h;
            flv = f;
            isTogether = t;
            expireTime = exp;
        }
    }

    public static void parse(int roomId, OnParseResultListener listener) {
        Log.d("HuyaParser", "开始解析房间：" + roomId);
        if (roomId <= 0) {
            mMainHandler.post(() -> listener.onFailed("房间号不合法"));
            return;
        }
        long now = System.currentTimeMillis();
        CacheItem cache = SOURCE_CACHE.get(roomId);
        if (cache != null && now < cache.expireTime) {
            Log.d("HuyaParser", "使用缓存：hls=" + cache.hls);
            mMainHandler.post(() -> listener.onSuccess(cache.hls, cache.flv, cache.isTogether));
            return;
        }
        Log.d("HuyaParser", "缓存未命中，开始获取播放地址");
        fetchPlayUrl(roomId, listener);
    }

    private static void fetchPlayUrl(final int roomId, final OnParseResultListener listener) {
        Log.d("HuyaParser", "尝试JS解析方案, isInit=" + JsLayer.isInit());

        // 🟢 超时保护：JS解析中HTTP请求可能卡死，10秒后强制回退到Java方案
        final long startTs = System.currentTimeMillis();
        final boolean[] jsDone = {false};
        final Runnable timeoutRunnable = () -> {
            if (!jsDone[0]) {
                Log.d("HuyaParser", "JS解析超时(" + (System.currentTimeMillis() - startTs) + "ms)，回退到Java方案");
                jsDone[0] = true;
                fetchPlayUrlJava(roomId, listener);
            }
        };
        mMainHandler.postDelayed(timeoutRunnable, 10000);

        new Thread(() -> {
            try {
                String parseJs = JsLayer.assetFileToString("js/huya_parse.js");
                if (TextUtils.isEmpty(parseJs)) {
                    Log.d("HuyaParser", "huya_parse.js 加载为空，回退到Java方案");
                    mMainHandler.removeCallbacks(timeoutRunnable);
                    fetchPlayUrlJava(roomId, listener);
                    return;
                }
                final String callJs = parseJs + "\nparseHuya('https://www.huya.com/" + roomId + "');";
                Log.d("HuyaParser", "JS解析执行中，房间：" + roomId);
                JsLayer.evaluate(callJs, new JsLayer.JsCallback() {
                    @Override
                    public void onResult(String result) {
                        if (jsDone[0]) return;
                        jsDone[0] = true;
                        mMainHandler.removeCallbacks(timeoutRunnable);
                        Log.d("HuyaParser", "JS返回结果：" + result);
                        try {
                            JSONObject json = new JSONObject(result);
                            if (json.optBoolean("success")) {
                                JSONArray data = json.optJSONArray("data");
                                if (data != null && data.length() > 0) {
                                    String hlsUrl = "";
                                    String flvUrl = "";
                                    for (int i = 0; i < data.length(); i++) {
                                        JSONObject stream = data.getJSONObject(i);
                                        String url = stream.optString("url", "");
                                        String quality = stream.optString("quality", "");
                                        if (url.endsWith(".m3u8") || "hls".equals(quality)) {
                                            hlsUrl = url;
                                        } else if (url.endsWith(".flv") || "flv".equals(quality)) {
                                            flvUrl = url;
                                        }
                                    }
                                    if (!TextUtils.isEmpty(hlsUrl) || !TextUtils.isEmpty(flvUrl)) {
                                        Log.d("HuyaParser", "JS解析成功：hls=" + hlsUrl + " flv=" + flvUrl);
                                        long expire = System.currentTimeMillis() + CACHE_VALID_MS;
                                        SOURCE_CACHE.put(roomId, new CacheItem(hlsUrl, flvUrl, true, expire));
                                        final String finalHls = hlsUrl;
                                        final String finalFlv = flvUrl;
                                        mMainHandler.post(() -> listener.onSuccess(finalHls, finalFlv, true));
                                        return;
                                    }
                                }
                            } else {
                                String err = json.optString("error", "未知错误");
                                Log.d("HuyaParser", "JS解析失败：" + err + "，回退到Java方案");
                            }
                            fetchPlayUrlJava(roomId, listener);
                        } catch (Exception e) {
                            Log.d("HuyaParser", "JS结果解析异常：" + e.getMessage() + "，回退到Java方案");
                            fetchPlayUrlJava(roomId, listener);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (jsDone[0]) return;
                        jsDone[0] = true;
                        mMainHandler.removeCallbacks(timeoutRunnable);
                        Log.d("HuyaParser", "JS执行错误：" + error + "，回退到Java方案");
                        fetchPlayUrlJava(roomId, listener);
                    }
                });
            } catch (Exception e) {
                if (jsDone[0]) return;
                jsDone[0] = true;
                mMainHandler.removeCallbacks(timeoutRunnable);
                Log.d("HuyaParser", "JS解析初始化异常：" + e.getMessage() + "，回退到Java方案");
                fetchPlayUrlJava(roomId, listener);
            }
        }).start();
    }

    private static void fetchPlayUrlJava(final int roomId, final OnParseResultListener listener) {
        Thread thread = new Thread(() -> {
            String hlsUrl = "";
            String flvUrl = "";

            try {
                // 1. 优先使用 live-api.huya.com 接口
                Log.d("HuyaParser", "尝试从LiveAPI获取播放地址");
                String liveApiResult = fetchFromLiveApi(roomId);
                if (!TextUtils.isEmpty(liveApiResult) && isValidStreamUrl(liveApiResult)) {
                    if (liveApiResult.endsWith(".m3u8")) {
                        hlsUrl = liveApiResult;
                    } else {
                        flvUrl = liveApiResult;
                    }
                    Log.d("HuyaParser", "从LiveAPI获取到地址：" + liveApiResult);
                }

                // 2. 尝试PC网页
                if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                    Log.d("HuyaParser", "尝试从PC网页获取播放地址");
                    String pcHtml = fetchHtml(API_PC_ROOM, roomId);
                    if (!TextUtils.isEmpty(pcHtml)) {
                        String result = extractUrlFromHtml(pcHtml);
                        if (!TextUtils.isEmpty(result) && isValidStreamUrl(result)) {
                            if (result.endsWith(".m3u8")) {
                                hlsUrl = result;
                            } else {
                                flvUrl = result;
                            }
                            Log.d("HuyaParser", "从PC网页获取到地址：" + result);
                        } else if (!TextUtils.isEmpty(result)) {
                            Log.d("HuyaParser", "PC网页解析到无效地址，忽略：" + result);
                        }
                    }
                }

                // 3. 尝试移动端网页
                if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                    Log.d("HuyaParser", "尝试从移动端网页获取播放地址");
                    String mobileHtml = fetchHtml(API_MOBILE_ROOM, roomId);
                    if (!TextUtils.isEmpty(mobileHtml)) {
                        String result = extractUrlFromHtml(mobileHtml);
                        if (!TextUtils.isEmpty(result) && isValidStreamUrl(result)) {
                            if (result.endsWith(".m3u8")) {
                                hlsUrl = result;
                            } else {
                                flvUrl = result;
                            }
                            Log.d("HuyaParser", "从移动端网页获取到地址：" + result);
                        }
                    }
                }

                // 4. 最后尝试 StreamInfo API (cache.php)
                if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                    Log.d("HuyaParser", "尝试从StreamInfo API获取播放地址");
                    String result = fetchFromStreamInfoAPI(roomId);
                    if (!TextUtils.isEmpty(result) && isValidStreamUrl(result)) {
                        if (result.endsWith(".m3u8")) {
                            hlsUrl = result;
                        } else {
                            flvUrl = result;
                        }
                        Log.d("HuyaParser", "从StreamInfoAPI获取到地址：" + result);
                    }
                }

            } catch (Exception e) {
                Log.d("HuyaParser", "获取播放地址异常：" + e.getMessage());
                e.printStackTrace();
            }

            final String finalHlsUrl = hlsUrl;
            final String finalFlvUrl = flvUrl;
            if (!TextUtils.isEmpty(hlsUrl) || !TextUtils.isEmpty(flvUrl)) {
                long expire = System.currentTimeMillis() + CACHE_VALID_MS;
                SOURCE_CACHE.put(roomId, new CacheItem(hlsUrl, flvUrl, true, expire));
                mMainHandler.post(() -> listener.onSuccess(finalHlsUrl, finalFlvUrl, true));
            } else {
                mMainHandler.post(() -> listener.onFailed("未获取到有效播放地址"));
            }
        });
        thread.start();
    }

    /**
     * 从 live-api.huya.com/moment/getLiveInfo 获取播放地址
     * 这是最可靠的方式，直接返回JSON格式的流信息
     */
    private static String fetchFromLiveApi(int roomId) {
        try {
            String url = String.format(Locale.ROOT, API_LIVE_INFO, roomId);
            Log.d("HuyaParser", "LiveAPI URL: " + url);
            Response response = NetUtil.getInstance().syncGet(url);
            if (!response.isSuccessful() || response.body() == null) {
                Log.d("HuyaParser", "LiveAPI请求失败: " + response.code());
                return "";
            }

            String jsonStr = response.body().string();
            Log.d("HuyaParser", "LiveAPI响应长度: " + jsonStr.length());

            JSONObject json = new JSONObject(jsonStr);
            if (json.optInt("code", -1) != 0) {
                Log.d("HuyaParser", "LiveAPI返回code: " + json.optInt("code"));
                return "";
            }

            JSONObject data = json.optJSONObject("data");
            if (data == null) return "";

            // 解析 stream 对象
            JSONObject stream = data.optJSONObject("stream");
            if (stream != null) {
                Iterator<String> keys = stream.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject streamItem = stream.optJSONObject(key);
                    if (streamItem != null) {
                        String hls = streamItem.optString("sHlsUrl", "");
                        if (!TextUtils.isEmpty(hls)) return hls;
                        String flv = streamItem.optString("sFlvUrl", "");
                        if (!TextUtils.isEmpty(flv)) return flv;
                    }
                }
            }

            // 解析 gameLiveInfo -> liveStreamInfo
            JSONObject gameLiveInfo = data.optJSONObject("gameLiveInfo");
            if (gameLiveInfo != null) {
                JSONObject liveStreamInfo = gameLiveInfo.optJSONObject("liveStreamInfo");
                if (liveStreamInfo != null) {
                    String hls = liveStreamInfo.optString("sHlsUrl", "");
                    if (!TextUtils.isEmpty(hls)) return hls;
                    String flv = liveStreamInfo.optString("sFlvUrl", "");
                    if (!TextUtils.isEmpty(flv)) return flv;
                }
            }

            // 解析 liveData -> tLiveInfo -> tLiveStreamInfo -> vMultiStreamInfo
            JSONObject liveData = data.optJSONObject("liveData");
            if (liveData != null) {
                JSONObject tLiveInfo = liveData.optJSONObject("tLiveInfo");
                if (tLiveInfo != null) {
                    JSONObject tLiveStreamInfo = tLiveInfo.optJSONObject("tLiveStreamInfo");
                    if (tLiveStreamInfo != null) {
                        JSONArray streams = tLiveStreamInfo.optJSONArray("vMultiStreamInfo");
                        if (streams != null) {
                            for (int i = 0; i < streams.length(); i++) {
                                JSONObject item = streams.getJSONObject(i);
                                String hls = item.optString("sHlsUrl", "");
                                if (!TextUtils.isEmpty(hls)) return hls;
                                String flv = item.optString("sFlvUrl", "");
                                if (!TextUtils.isEmpty(flv)) return flv;
                            }
                        }
                    }
                }
            }

            // 兜底：在整个JSON中搜索URL
            return extractUrlFromJsonString(jsonStr);
        } catch (Exception e) {
            Log.d("HuyaParser", "LiveAPI异常: " + e.getMessage());
            return "";
        }
    }

    /**
     * 🟢【核心修改 1】获取网页 HTML，完全依赖 NetUtil 自动生成请求头（允许重定向）
     */
    private static String fetchHtml(String urlPattern, int roomId) {
        try {
            String url = String.format(urlPattern, roomId);
            // 使用 syncGet，NetUtil 内部会自动添加虎牙相关的 UA 和 Referer
            Response response = NetUtil.getInstance().syncGet(url);
            if (!response.isSuccessful() || response.body() == null) {
                Log.d("HuyaParser", "fetchHtml请求失败，状态码：" + response.code());
                return "";
            }
            return response.body().string();
        } catch (IOException e) {
            Log.d("HuyaParser", "fetchHtml异常：" + e.getMessage());
        }
        return "";
    }

    /**
     * 🟢【核心修改 2】调用 API 接口，移除硬编码 Header，依赖 NetUtil 自动生成；同时强制不跟随重定向
     */
    private static String fetchFromStreamInfoAPI(int roomId) {
        try {
            String url = String.format(Locale.ROOT, API_STREAM_INFO, roomId);
            // 调用 syncGetNoRedirect，NetUtil 内部会自动生成 UA 和 Header，且禁止跟随 302 跳转
            Response response = NetUtil.getInstance().syncGetNoRedirect(url);
            if (!response.isSuccessful() || response.body() == null) {
                Log.d("HuyaParser", "fetchFromStreamInfoAPI请求失败，状态码：" + response.code());
                return "";
            }
            
            String jsonStr = response.body().string();
            Log.d("HuyaParser", "StreamInfoAPI返回长度：" + jsonStr.length());
            Log.d("HuyaParser", "StreamInfoAPI前500字符：" + (jsonStr.length() > 500 ? jsonStr.substring(0, 500) : jsonStr));

            if (jsonStr.contains("<!DOCTYPE")) {
                return extractUrlFromHtml(jsonStr);
            }
            
            try {
                JSONObject json = new JSONObject(jsonStr);
                Log.d("HuyaParser", "StreamInfoAPI JSON keys: " + json.keys());
                
                if (json.has("data")) {
                    JSONObject data = json.getJSONObject("data");
                    Log.d("HuyaParser", "data keys: " + data.keys());
                    
                    if (data.has("stream")) {
                        JSONObject stream = data.getJSONObject("stream");
                        if (stream.has("hls")) {
                            return stream.getString("hls");
                        }
                        if (stream.has("flv")) {
                            return stream.getString("flv");
                        }
                    }
                    if (data.has("gameLiveInfo")) {
                        JSONObject liveInfo = data.getJSONObject("gameLiveInfo");
                        if (liveInfo.has("liveStreamInfo")) {
                            JSONObject streamInfo = liveInfo.getJSONObject("liveStreamInfo");
                            if (streamInfo.has("sHlsUrl")) {
                                return streamInfo.getString("sHlsUrl");
                            }
                            if (streamInfo.has("sFlvUrl")) {
                                return streamInfo.getString("sFlvUrl");
                            }
                        }
                    }
                    if (data.has("liveData")) {
                        JSONObject liveData = data.getJSONObject("liveData");
                        if (liveData.has("tLiveInfo")) {
                            JSONObject liveInfo = liveData.getJSONObject("tLiveInfo");
                            if (liveInfo.has("tLiveStreamInfo")) {
                                JSONObject streamInfo = liveInfo.getJSONObject("tLiveStreamInfo");
                                if (streamInfo.has("vMultiStreamInfo")) {
                                    JSONArray streams = streamInfo.getJSONArray("vMultiStreamInfo");
                                    for (int i = 0; i < streams.length(); i++) {
                                        JSONObject stream = streams.getJSONObject(i);
                                        if (stream.has("sHlsUrl")) {
                                            String hlsUrl = stream.getString("sHlsUrl");
                                            if (!TextUtils.isEmpty(hlsUrl)) return hlsUrl;
                                        }
                                        if (stream.has("sFlvUrl")) {
                                            String flvUrl = stream.getString("sFlvUrl");
                                            if (!TextUtils.isEmpty(flvUrl)) return flvUrl;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.d("HuyaParser", "解析StreamInfoAPI失败：" + e.getMessage());
                String urlFromJson = extractUrlFromJsonString(jsonStr);
                if (!TextUtils.isEmpty(urlFromJson)) {
                    return urlFromJson;
                }
            }
            
        } catch (IOException e) {
            Log.d("HuyaParser", "fetchFromStreamInfoAPI异常：" + e.getMessage());
        }
        return "";
    }

    // ================== 以下为正则提取工具方法，保持不变 ==================

    private static String extractUrlFromJsonString(String jsonStr) {
        try {
            Pattern m3u8Pattern = Pattern.compile("https?://[^\"'\\s,]+\\.m3u8[^\"'\\s,]*");
            Matcher matcher = m3u8Pattern.matcher(jsonStr);
            if (matcher.find()) {
                String url = matcher.group(0);
                try {
                    url = URLDecoder.decode(url, "UTF-8");
                } catch (Exception ignored) {}
                return url;
            }
            
            Pattern flvPattern = Pattern.compile("https?://[^\"'\\s,]+\\.flv[^\"'\\s,]*");
            matcher = flvPattern.matcher(jsonStr);
            if (matcher.find()) {
                String url = matcher.group(0);
                try {
                    url = URLDecoder.decode(url, "UTF-8");
                } catch (Exception ignored) {}
                return url;
            }
        } catch (Exception e) {
            Log.d("HuyaParser", "extractUrlFromJsonString异常：" + e.getMessage());
        }
        return "";
    }

    private static String extractUrlFromHtml(String html) {
        try {
            // 1. 尝试从 window.__INITIAL_STATE__ 提取
            Pattern initialStatePattern = Pattern.compile("window\\.__INITIAL_STATE__\\s*=\\s*(\\{.*?\\})\\s*;", Pattern.DOTALL);
            Matcher stateMatcher = initialStatePattern.matcher(html);
            if (stateMatcher.find()) {
                try {
                    JSONObject state = new JSONObject(stateMatcher.group(1));
                    if (state.has("roomInfo")) {
                        JSONObject roomInfo = state.getJSONObject("roomInfo");
                        if (roomInfo.has("tLiveInfo")) {
                            JSONObject liveInfo = roomInfo.getJSONObject("tLiveInfo");
                            if (liveInfo.has("tLiveStreamInfo")) {
                                JSONObject streamInfo = liveInfo.getJSONObject("tLiveStreamInfo");
                                if (streamInfo.has("vMultiStreamInfo")) {
                                    JSONArray streams = streamInfo.getJSONArray("vMultiStreamInfo");
                                    for (int i = 0; i < streams.length(); i++) {
                                        JSONObject item = streams.getJSONObject(i);
                                        String hls = item.optString("sHlsUrl", "");
                                        if (!TextUtils.isEmpty(hls)) return hls;
                                        String flv = item.optString("sFlvUrl", "");
                                        if (!TextUtils.isEmpty(flv)) return flv;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 2. 尝试从 hyPlayerConfig 提取
            Pattern configPattern = Pattern.compile("hyPlayerConfig\\s*=\\s*(\\{.*?\\})\\s*;", Pattern.DOTALL);
            Matcher configMatcher = configPattern.matcher(html);
            if (configMatcher.find()) {
                String configStr = configMatcher.group(1);
                String urlFromConfig = extractUrlFromJsonString(configStr);
                if (!TextUtils.isEmpty(urlFromConfig)) return urlFromConfig;
            }

            Pattern sHlsUrlPattern = Pattern.compile("sHlsUrl[^,]*:\\s*[\"']([^\"']+)[\"']");
            Matcher matcher = sHlsUrlPattern.matcher(html);
            if (matcher.find()) {
                String hlsUrl = matcher.group(1);
                Pattern sHlsAntiPattern = Pattern.compile("sHlsAntiCode[^,]*:\\s*[\"']([^\"']+)[\"']");
                Matcher antiMatcher = sHlsAntiPattern.matcher(html);
                if (antiMatcher.find()) {
                    String antiCode = antiMatcher.group(1);
                    hlsUrl = hlsUrl + "?" + antiCode;
                }
                return hlsUrl;
            }

            Pattern sFlvUrlPattern = Pattern.compile("sFlvUrl[^,]*:\\s*[\"']([^\"']+)[\"']");
            matcher = sFlvUrlPattern.matcher(html);
            if (matcher.find()) {
                String flvUrl = matcher.group(1);
                Pattern sFlvAntiPattern = Pattern.compile("sFlvAntiCode[^,]*:\\s*[\"']([^\"']+)[\"']");
                Matcher antiMatcher = sFlvAntiPattern.matcher(html);
                if (antiMatcher.find()) {
                    String antiCode = antiMatcher.group(1);
                    flvUrl = flvUrl + "?" + antiCode;
                }
                return flvUrl;
            }

            Pattern hlsPattern = Pattern.compile("hls\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']");
            matcher = hlsPattern.matcher(html);
            if (matcher.find()) {
                return matcher.group(1);
            }

            Pattern httpM3u8Pattern = Pattern.compile("https?://[^\"'\\s,]+\\.m3u8[^\"'\\s,]*");
            matcher = httpM3u8Pattern.matcher(html);
            if (matcher.find()) {
                String url = matcher.group(0);
                try {
                    url = URLDecoder.decode(url, "UTF-8");
                } catch (Exception ignored) {}
                return url;
            }

            Pattern httpFlvPattern = Pattern.compile("https?://[^\"'\\s,]+\\.flv[^\"'\\s,]*");
            matcher = httpFlvPattern.matcher(html);
            if (matcher.find()) {
                String url = matcher.group(0);
                try {
                    url = URLDecoder.decode(url, "UTF-8");
                } catch (Exception ignored) {}
                return url;
            }
            
            Pattern streamDataPattern = Pattern.compile("stream.*?data.*?gameLiveInfo.*?liveStreamInfo.*?sHlsUrl[^\"']*[\"']([^\"']+)[\"']", Pattern.DOTALL);
            matcher = streamDataPattern.matcher(html);
            if (matcher.find()) {
                return matcher.group(1);
            }
            
            Pattern hlsHttpPattern = Pattern.compile("\"https?://[^\"]+\\.m3u8[^\"]*\"");
            matcher = hlsHttpPattern.matcher(html);
            if (matcher.find()) {
                String url = matcher.group(0);
                url = url.substring(1, url.length() - 1);
                try {
                    url = URLDecoder.decode(url, "UTF-8");
                } catch (Exception ignored) {}
                return url;
            }

        } catch (Exception e) {
            Log.d("HuyaParser", "extractUrlFromHtml异常：" + e.getMessage());
        }
        return "";
    }

    public static void clearCache() {
        SOURCE_CACHE.clear();
    }

    /**
     * 验证URL是否为有效的流地址
     * 过滤掉太短的、不完整的或明显无效的URL
     */
    private static boolean isValidStreamUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        // URL必须以http/https开头
        if (!url.startsWith("http")) return false;
        // URL长度至少30个字符（有效的流地址通常很长）
        if (url.length() < 30) return false;
        // 必须包含至少一个路径分隔符
        if (!url.contains("/")) return false;
        // 排除明显无效的模式
        if (url.contains("src/") && !url.contains(".m3u8") && !url.contains(".flv")) return false;
        return true;
    }

    public static void release() {
        SOURCE_CACHE.clear();
    }
}
