package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.URL;

public class PlayerHelper {

    private static final String TAG = "PlayerHelper";
    private static final int MAX_REDIRECT_COUNT = 10;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 8000;

    public interface PlayerStateListener {
        void setCurrentChannelName(String name);
    }

    public static void playWithRedirect(
            String originalUrl,
            PlayerStateListener listener,
            TVPlayerManager mPlayerManager
    ) {
        final String[] finalUrl = {originalUrl};

        Log.d(TAG, "========================================");
        Log.d(TAG, "开始播放解析：" + originalUrl);

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                for (int i = 0; i < MAX_REDIRECT_COUNT; i++) {
                    Log.d(TAG, "第 " + (i + 1) + " 次解析链接：" + finalUrl[0]);

                    URL u = new URL(finalUrl[0]);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestMethod("GET");
                    conn.setInstanceFollowRedirects(false);

                    int code = conn.getResponseCode();
                    Log.d(TAG, "服务器响应码：" + code);

                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        Log.d(TAG, "检测到重定向 -> " + loc);

                        if (loc != null) {
                            finalUrl[0] = loc;
                            Log.d(TAG, "更新最终播放地址：" + finalUrl[0]);
                        } else {
                            Log.e(TAG, "重定向地址为空，停止解析");
                            break;
                        }

                        conn.disconnect();
                        conn = null;
                    } else {
                        Log.d(TAG, "非重定向地址，停止解析");
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "解析发生异常：" + e.getMessage());
                e.printStackTrace();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }

            Log.d(TAG, "最终播放地址：" + finalUrl[0]);
            Log.d(TAG, "========================================");

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!TextUtils.isEmpty(finalUrl[0])) {
                    mPlayerManager.playUrl(finalUrl[0]);
                }
            });
        }).start();
    }
}
