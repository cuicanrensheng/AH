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

                    int code = conn.getResponseCode();

                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        if (loc != null) {
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
