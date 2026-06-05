package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import java.net.HttpURLConnection;
import java.net.URL;

public class HuyaParser {
    //解析结果回调接口
    public interface OnParseResultListener {
        void onSuccess(String playUrl, int type);
        void onError(String msg);
    }

    /**
     * 虎牙源解析入口
     * @param roomId 纯数字房间ID
     * @param listener 播放地址回调
     */
    public void parse(String roomId, OnParseResultListener listener) {
        //子线程执行网络请求，禁止主线程网络
        new Thread(() -> {
            HttpURLConnection conn = null;
            //初始代理解析地址
            String nextUrl = "http://cdn.jdshipin.com:8880/huya.php?id=" + roomId;
            try {
                //限制最多2次重定向（302→301），规避死循环
                for (int step = 0; step < 2; step++) {
                    URL urlObj = new URL(nextUrl);
                    conn = (HttpURLConnection) urlObj.openConnection();
                    //读写超时8秒，防止接口阻塞卡死
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    //严格沿用抓包固定请求头
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
                    conn.setRequestProperty("Referer", "http://cdn.jdshipin.com/");
                    conn.setRequestProperty("Icy-MetaData", "1");
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    //关闭系统自动跟随跳转，手动抓取Location
                    conn.setInstanceFollowRedirects(false);

                    int respCode = conn.getResponseCode();
                    //301永久重定向 / 302临时重定向 统一处理
                    if (respCode == HttpURLConnection.HTTP_MOVED_PERM || respCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                        nextUrl = conn.getHeaderField("Location");
                        conn.disconnect();
                    } else {
                        //非跳转链接，终止循环
                        break;
                    }
                }
                //循环结束nextUrl即为最终FLV源，切主线程回调播放
                final String finalSource = nextUrl;
                new Handler(Looper.getMainLooper()).post(() -> listener.onSuccess(finalSource, 1));
            } catch (Exception e) {
                //异常回调，触发原链接兜底播放
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e.getMessage()));
            } finally {
                //释放连接资源
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
