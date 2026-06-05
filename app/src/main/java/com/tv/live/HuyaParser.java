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
            String currUrl = "http://cdn.jdshipin.com:8880/huya.php?id=" + roomId;
            try {
                //最多2轮重定向（302→301），避免死循环
                for(int i = 0; i < 2; i++){
                    URL url = new URL(currUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    //网络超时
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    //固定抓包请求头
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
                    conn.setRequestProperty("Referer", "http://cdn.jdshipin.com/");
                    conn.setRequestProperty("Icy-MetaData", "1");
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setInstanceFollowRedirects(false);

                    int code = conn.getResponseCode();
                    //兼容301永久重定向、302临时重定向
                    if(code == 301 || code == 302){
                        currUrl = conn.getHeaderField("Location");
                        conn.disconnect();
                    }else{
                        break;
                    }
                }
                //循环结束currUrl即为最终真实播放地址
                final String finalPlay = currUrl;
                new Handler(Looper.getMainLooper()).post(() -> listener.onSuccess(finalPlay, 1));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
