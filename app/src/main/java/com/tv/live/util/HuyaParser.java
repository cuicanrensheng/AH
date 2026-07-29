package com.tv.live.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

import okhttp3.Response;

public class HuyaParser {
    private static final Handler mMainHandler = new Handler(Looper.getMainLooper());
    
    private static final ConcurrentHashMap<Integer, CacheItem> SOURCE_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_VALID_MS = 110 * 1000;

    private static final String API_MOBILE_ROOM = "https://m.huya.com/%d";
    private static final String API_PC_ROOM = "https://www.huya.com/%d";
    private static final String API_STREAM_INFO = "https://www.huya.com/cache.php?m=LiveList&do=getLivePlayInfo&roomId=%d";
    private static final String API_CDN_TMP_LIST = "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList?iGid=2135&iTmpId=2067&iPageNo=1&iPageSize=50";

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
        Thread thread = new Thread(() -> {
            String hlsUrl = "";
            String flvUrl = "";

            try {
                Log.d("HuyaParser", "尝试从PC网页获取播放地址");
                String pcHtml = fetchHtml(API_PC_ROOM, roomId);
                if (!TextUtils.isEmpty(pcHtml)) {
                    String result = extractUrlFromHtml(pcHtml);
                    if (!TextUtils.isEmpty(result)) {
                        if (result.endsWith(".m3u8")) {
                            hlsUrl = result;
                        } else {
                            flvUrl = result;
                        }
                        Log.d("HuyaParser", "从PC网页获取到地址：" + result);
                    }
                }

                if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                    Log.d("HuyaParser", "尝试从移动端网页获取播放地址");
                    String mobileHtml = fetchHtml(API_MOBILE_ROOM, roomId);
                    if (!TextUtils.isEmpty(mobileHtml)) {
                        String result = extractUrlFromHtml(mobileHtml);
                        if (!TextUtils.isEmpty(result)) {
                            if (result.endsWith(".m3u8")) {
                                hlsUrl = result;
                            } else {
                                flvUrl = result;
                            }
                            Log.d("HuyaParser", "从移动端网页获取到地址：" + result);
                        }
                    }
                }

                if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                    Log.d("HuyaParser", "尝试从StreamInfo API获取播放地址");
                    String result = fetchFromStreamInfoAPI(roomId);
                    if (!TextUtils.isEmpty(result)) {
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
                mMainHandler.post(() -> listener.onFailed("未获取到播放地址"));
            }
        });
        thread.start();
    }

    private static String fetchHtml(String urlPattern, int roomId) {
        try {
            String url = String.format(urlPattern, roomId);
            
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            headers.put("Connection", "keep-alive");
            headers.put("Referer", "https://www.huya.com/");
            
            Response response = NetUtil.getInstance().syncGetWithHeaders(url, headers);
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

    private static String fetchFromStreamInfoAPI(int roomId) {
        try {
            String url = String.format(Locale.ROOT, API_STREAM_INFO, roomId);
            
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            headers.put("Connection", "keep-alive");
            headers.put("Referer", "https://www.huya.com/" + roomId);
            headers.put("Origin", "https://www.huya.com");
            
            Response response = NetUtil.getInstance().syncGetWithHeaders(url, headers);
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

    public static void release() {
        SOURCE_CACHE.clear();
    }
}