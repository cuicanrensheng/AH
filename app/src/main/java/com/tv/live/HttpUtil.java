package com.tv.live;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 直播专用网络请求工具，适配TVPlayerManager调用
 * 补齐：public static final String UA + public static String get(String url)
 */
public class HttpUtil {
    // 修复报错1：补充全局UA常量，TVPlayerManager.factory.setUserAgent(HttpUtil.UA)使用
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    // 单例OkHttp客户端（直播通用，超时10秒，适配m3u8/epg/源地址请求）
    private static final OkHttpClient OK_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true) // 自动301/302重定向（直播源必备）
            .build();

    // 修复报错2/3/4：补齐 TVPlayerManager 调用的 get(String url) 无参重载
    public static String get(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", UA) // 默认携带UA防盗链
                .build();
        try (Response response = OK_CLIENT.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
            return "";
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    // 可选扩展：带自定义请求头get（后续扩展用，不影响现有编译）
    public static String get(String url, okhttp3.Headers headers) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) builder.headers(headers);
        else builder.header("User-Agent", UA);
        try (Response resp = OK_CLIENT.newCall(builder.build().execute())) {
            return resp.isSuccessful() && resp.body() != null ? resp.body().string() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
