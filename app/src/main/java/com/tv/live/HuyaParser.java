package com.tv.live.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 虎牙直播间 + 一起看影视 源解析工具
 * 自动签名 wsSecret / wsTime / seqid，自动区分普通直播 & 一起看房间
 */
public class HuyaParser {
    private static final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private static final OkHttpClient mClient = new OkHttpClient();
    private static final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private static final String API_ROOM_INFO = "https://www.huya.com/cache.mini-global-%d.json";
    private static final String API_PLAY_URL = "https://api.huya.com/m_push/%d";

    public interface OnParseResultListener {
        void onSuccess(String hlsUrl, String flvUrl, boolean isTogetherWatch);
        void onFailed(String errorMsg);
    }

    /**
     * 入口：根据房间号解析播放源
     */
    public static void parse(int roomId, OnParseResultListener listener) {
        if (roomId <= 0) {
            listener.onFailed("房间号不合法");
            return;
        }
        mExecutor.execute(() -> getRoomInfo(roomId, listener));
    }

    private static void getRoomInfo(int roomId, OnParseResultListener listener) {
        String url = String.format(API_ROOM_INFO, roomId);
        Map<String, String> headers = getBaseHeaders();

        Request request = new Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.of(headers))
                .get()
                .build();

        try (Response response = mClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                postFailed(listener, "请求房间信息失败");
                return;
            }
            String resStr = response.body().string();
            JSONObject json = new JSONObject(resStr);

            boolean isTogetherWatch = json.optInt("isVideoRoom", 0) == 1;
            String streamName = json.optString("stream", "");
            String uid = json.optString("uid", "");

            if (TextUtils.isEmpty(streamName) || TextUtils.isEmpty(uid)) {
                postFailed(listener, "房间未开播或无流信息");
                return;
            }

            long wsTime = System.currentTimeMillis() / 1000;
            String wsSecret = calcSecret(uid, streamName, wsTime);
            getPlaySource(roomId, streamName, wsTime, wsSecret, isTogetherWatch, listener);

        } catch (IOException | JSONException e) {
            e.printStackTrace();
            postFailed(listener, "解析数据异常：" + e.getMessage());
        }
    }

    private static void getPlaySource(int roomId, String streamName, long wsTime, String wsSecret,
                                      boolean isTogetherWatch, OnParseResultListener listener) {
        StringBuilder apiUrl = new StringBuilder(String.format(API_PLAY_URL, roomId));
        apiUrl.append("?m=8&do=hd&uid=").append(streamName)
                .append("&wsSecret=").append(wsSecret)
                .append("&wsTime=").append(wsTime)
                .append("&fm=57&ver=2108191723&tx=").append(System.currentTimeMillis());

        if (isTogetherWatch) {
            apiUrl.append("&seqid=").append(System.currentTimeMillis());
        }

        Map<String, String> headers = getBaseHeaders();
        Request request = new Request.Builder()
                .url(apiUrl.toString())
                .headers(okhttp3.Headers.of(headers))
                .get()
                .build();

        try (Response response = mClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                postFailed(listener, "获取播放源失败");
                return;
            }
            String resStr = response.body().string();
            JSONObject json = new JSONObject(resStr);
            JSONArray streamArray = json.optJSONArray("data");
            if (streamArray == null || streamArray.length() == 0) {
                postFailed(listener, "暂无可用播放流");
                return;
            }

            String hlsUrl = "";
            String flvUrl = "";
            for (int i = 0; i < streamArray.length(); i++) {
                JSONObject item = streamArray.getJSONObject(i);
                String url = item.optString("url", "");
                if (TextUtils.isEmpty(url)) continue;

                if (url.contains(".m3u8")) {
                    hlsUrl = url;
                } else if (url.contains(".flv")) {
                    flvUrl = url;
                }
            }

            postSuccess(listener, hlsUrl, flvUrl, isTogetherWatch);

        } catch (IOException | JSONException e) {
            e.printStackTrace();
            postFailed(listener, "播放源解析异常：" + e.getMessage());
        }
    }

    /**
     * 计算 wsSecret MD5 签名
     */
    private static String calcSecret(String uid, String stream, long time) {
        String raw = uid + stream + time + "97b64242aa187a74";
        return md5(raw).toLowerCase();
    }

    private static String md5(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(str.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                int val = b & 0xff;
                if (val < 16) sb.append("0");
                sb.append(Integer.toHexString(val));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static Map<String, String> getBaseHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 Chrome/114.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.huya.com/");
        headers.put("Origin", "https://www.huya.com");
        return headers;
    }

    private static void postSuccess(OnParseResultListener listener, String hls, String flv, boolean isTogether) {
        mMainHandler.post(() -> listener.onSuccess(hls, flv, isTogether));
    }

    private static void postFailed(OnParseResultListener listener, String msg) {
        mMainHandler.post(() -> listener.onFailed(msg));
    }

    /**
     * 释放线程池（Activity onDestroy 调用）
     */
    public static void release() {
        mExecutor.shutdownNow();
    }
}
