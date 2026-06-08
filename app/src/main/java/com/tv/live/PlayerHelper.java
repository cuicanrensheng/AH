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

    // 日志回调 → 写给界面弹窗
    public interface LogCallback {
        void onLog(String log);
    }

    public interface PlayerStateListener {
        void setCurrentChannelName(String name);
    }

    // 增加 LogCallback 参数
    public static void playWithRedirect(
            String originalUrl,
            PlayerStateListener listener,
            TVPlayerManager mPlayerManager,
            LogCallback logCallback
    ) {
        final String[] finalUrl = {originalUrl};

        // 同时输出到 Logcat 和 界面
        log("========================================", logCallback);
        log("开始播放解析：" + originalUrl, logCallback);

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                for (int i = 0; i < MAX_REDIRECT_COUNT; i++) {
                    log("第 " + (i + 1) + " 次解析链接：" + finalUrl[0], logCallback);

                    URL u = new URL(finalUrl[0]);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestMethod("GET");
                    conn.setInstanceFollowRedirects(false);

                    int code = conn.getResponseCode();
                    log("服务器响应码：" + code, logCallback);

                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        log("检测到重定向 → " + loc, logCallback);

                        if (loc != null) {
                            finalUrl[0] = loc;
                            log("更新最终播放地址：" + finalUrl[0], logCallback);
                        } else {
                            log("重定向地址为空，停止解析", logCallback);
                            break;
                        }

                        conn.disconnect();
                        conn = null;
                    } else {
                        log("非重定向地址，停止解析", logCallback);
                        break;
                    }
                }
            } catch (Exception e) {
                log("解析异常：" + e.getMessage(), logCallback);
                e.printStackTrace();
            } finally {
                if (conn != null) conn.disconnect();
            }

            log("最终播放地址：" + finalUrl[0], logCallback);
            log("========================================", logCallback);

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!TextUtils.isEmpty(finalUrl[0])) {
                    mPlayerManager.playUrl(finalUrl[0]);
                }
            });
        }).start();
    }

    // 统一输出：Logcat + 界面日志
    private static void log(String msg, LogCallback callback) {
        Log.d(TAG, msg);
        if (callback != null) {
            callback.onLog(msg);
        }
    }
}
