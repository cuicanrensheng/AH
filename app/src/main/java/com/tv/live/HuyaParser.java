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
            String nextUrl = "http://cdn.jdshipin.com:8880/huya.php?id=" + roomId;
            try {
                //最大4轮跳转，兼容当前3层重定向+后续新增跳转冗余
                for (int step = 0; step < 4; step++) {
                    URL urlObj = new URL(nextUrl);
                    conn = (HttpURLConnection) urlObj.openConnection();
                    //网络超时8秒，防止接口阻塞
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    //固定抓包原版请求头
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
                    conn.setRequestProperty("Referer", "http://cdn.jdshipin.com/");
                    conn.setRequestProperty("Icy-MetaData", "1");
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    //关闭系统自动跳转，手动抓取Location
                    conn.setInstanceFollowRedirects(false);

                    int code = conn.getResponseCode();
                    //301永久重定向 / 302临时重定向统一处理
                    if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP) {
                        nextUrl = conn.getHeaderField("Location");
                        conn.disconnect();
                    } else {
                        //非跳转状态码，终止循环
                        break;
                    }
                }
                //循环结束nextUrl = 最终CDN播放地址
                final String finalSource = nextUrl;
                new Handler(Looper.getMainLooper()).post(() -> listener.onSuccess(finalSource, 1));
            } catch (Exception e) {
                //异常回调，原链接兜底播放
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
