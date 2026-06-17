package com.tv.live;

import android.content.Context;
import android.net.Uri;
import android.widget.Toast;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class TVPlayerManager {
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;

    public enum ScaleMode { FIT, FILL, ZOOM }

    private OnPlayStateListener listener;

    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        player = new ExoPlayer.Builder(context).build();
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // ====================== 支持 PHP 解析 + 防盗链（修复版） ======================
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;

        new Thread(() -> {
            String realUrl = resolveStreamUrl(url);
            new android.os.Handler(context.getMainLooper()).post(() -> {
                try {
                    DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
                    factory.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
                    factory.setDefaultRequestProperties(getCommonHeaders());

                    MediaItem mediaItem = MediaItem.fromUri(realUrl);
                    HlsMediaSource source = new HlsMediaSource.Factory(factory).createMediaSource(mediaItem);

                    player.setMediaSource(source);
                    player.prepare();
                    player.play();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }).start();
    }
    
    public void play(String url) {
    playUrl(url);
}

    // 解析 PHP 接口获取真实地址
    private String resolveStreamUrl(String url) {
        if (url.endsWith(".m3u8") || url.endsWith(".ts")) return url;

        try {
            URL u = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);

            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Referer", url.substring(0, url.indexOf("/", 8)));

            int code = conn.getResponseCode();
            if (code == 301 || code == 302) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && loc.startsWith("http")) {
                    conn.disconnect();
                    return loc;
                }
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(https?://[^\\s\"']+\\.m3u8)");
            java.util.regex.Matcher matcher = pattern.matcher(sb.toString());
            if (matcher.find()) {
                return matcher.group(1);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return url;
    }

    // 公共请求头（防防盗链）
    private Map<String, String> getCommonHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://cdn.jdshipin.com/");
        headers.put("Origin", "http://cdn.jdshipin.com");
        return headers;
    }

    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case FILL: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case ZOOM: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
        }
    }

    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        instance = null;
    }
}
