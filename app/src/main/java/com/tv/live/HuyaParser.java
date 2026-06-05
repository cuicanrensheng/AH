package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 虎牙直播源解析类，适配huya/jdshipin/zxyndc链接解析真实播放地址
 */
public class HuyaParser {
    private final OkHttpClient mHttpClient;

    //解析结果回调接口
    public interface OnParseResultListener {
        void onSuccess(String realPlayUrl, int sourceType);
        void onError(String errorMsg);
    }

    public HuyaParser() {
        mHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    /**
     * 开始异步解析直播间地址
     * @param roomId 直播间ID/原始链接
     * @param listener 解析回调
     */
    public void parse(String roomId, final OnParseResultListener listener) {
        new Thread(() -> {
            try {
                String realRoomId = extractRoomId(roomId);
                String apiUrl = "https://api.huya.com/m_pay/play/getPlayInfo?roomId=" + realRoomId;
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("User-Agent", "Mozilla/5.0 Android TV")
                        .build();
                mHttpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        runUiThread(() -> listener.onError("网络请求失败：" + e.getMessage()));
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        if (!response.isSuccessful()) {
                            runUiThread(() -> listener.onError("接口返回异常"));
                            return;
                        }
                        String resStr = response.body().string();

                        // 修复：JSON 异常捕获
                        try {
                            JSONObject json = new JSONObject(resStr);
                            String flvUrl = json.optString("dataUrl", "");
                            if (flvUrl.isEmpty()) {
                                runUiThread(() -> listener.onError("未获取到直播地址"));
                            } else {
                                runUiThread(() -> listener.onSuccess(flvUrl, 1));
                            }
                        } catch (JSONException e) {
                            runUiThread(() -> listener.onError("JSON解析失败"));
                        }
                    }
                });
            } catch (Exception e) {
                runUiThread(() -> listener.onError("解析异常：" + e.getMessage()));
            }
        }).start();
    }

    //正则从链接提取房间号
    private String extractRoomId(String url) {
        Pattern pattern = Pattern.compile("(\\d{5,12})");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return url;
    }

    //切主线程回调
    private void runUiThread(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }
}
