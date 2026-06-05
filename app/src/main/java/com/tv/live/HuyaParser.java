package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import java.net.HttpURLConnection;
import java.net.URL;

public class HuyaParser {
    public interface OnParseResultListener {
        void onSuccess(String playUrl, int type);
        void onError(String msg);
    }

    public void parse(String roomId, OnParseResultListener listener) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String api = "http://cdn.jdshipin.com:8880/huya.php?id=" + roomId;
                URL url = new URL(api);
                conn = (HttpURLConnection) url.openConnection();
                //超时设置
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                //请求头
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
                conn.setRequestProperty("Referer", "http://cdn.jdshipin.com/");
                conn.setRequestProperty("Icy-MetaData", "1");
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setInstanceFollowRedirects(false);

                int code = conn.getResponseCode();
                if (code == 302) {
                    String realUrl = conn.getHeaderField("Location");
                    new Handler(Looper.getMainLooper()).post(() -> listener.onSuccess(realUrl, 1));
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> listener.onError("状态码：" + code));
                }
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
