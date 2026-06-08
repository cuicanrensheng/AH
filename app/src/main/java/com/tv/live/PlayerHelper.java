package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import java.net.HttpURLConnection;
import java.net.URL;

public class PlayerHelper {

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

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                for (int i = 0; i < MAX_REDIRECT_COUNT; i++) {
                    URL u = new URL(finalUrl[0]);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestMethod("GET");
                    conn.setInstanceFollowRedirects(false);

                    // ====================== 模拟浏览器 防盗链请求头 ======================
                    String host = u.getHost();
                    String protocol = u.getProtocol();
                    String referer = protocol + "://" + host;
                    String origin = protocol + "://" + host;

                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
                    conn.setRequestProperty("Referer", referer);
                    conn.setRequestProperty("Origin", origin);
                    conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
                    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                    conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
                    conn.setRequestProperty("Connection", "close");
                    conn.setRequestProperty("Sec-Fetch-Dest", "document");
                    conn.setRequestProperty("Sec-Fetch-Mode", "navigate");
                    conn.setRequestProperty("Sec-Fetch-Site", "none");
                    conn.setRequestProperty("Sec-Fetch-User", "?1");
                    conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
                    // ====================================================================

                    int code = conn.getResponseCode();

                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        if (loc != null) {
                            // 处理相对路径
                            if (loc.startsWith("/")) {
                                loc = protocol + "://" + host + loc;
                            }
                            finalUrl[0] = loc;
                        } else {
                            break;
                        }
                        conn.disconnect();
                        conn = null;
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!TextUtils.isEmpty(finalUrl[0])) {
                    mPlayerManager.playUrl(finalUrl[0]);
                }
            });
        }).start();
    }
}
