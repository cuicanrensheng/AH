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

/**
 * 播放器管理类（ExoPlayer）
 * 负责：播放、解析地址、切换画面比例、暂停/恢复
 */
public class TVPlayerManager {
    // 单例实例
    private static TVPlayerManager instance;
    // ExoPlayer 核心播放器
    private ExoPlayer player;
    // 上下文
    private Context context;
    // 播放视图
    private PlayerView playerView;
    // 画面缩放模式
    public enum ScaleMode { FIT, FILL, ZOOM }
    // 播放状态回调
    private OnPlayStateListener listener;

    /**
     * 获取单例（全局唯一）
     */
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    /**
     * 私有构造：初始化 ExoPlayer
     */
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        player = new ExoPlayer.Builder(context).build();
    }

    /**
     * 绑定播放视图
     */
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    /**
     * 播放地址（支持 m3u8 / PHP 接口解析）
     */
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        new Thread(() -> {
            // 解析真实播放地址
            String realUrl = resolveStreamUrl(url);
            new android.os.Handler(context.getMainLooper()).post(() -> {
                try {
                    // 配置请求头（防盗链）
                    DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
                    factory.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
                    factory.setDefaultRequestProperties(getCommonHeaders());
                    
                    // 构建媒体项
                    MediaItem mediaItem = MediaItem.fromUri(realUrl);
                    HlsMediaSource source = new HlsMediaSource.Factory(factory).createMediaSource(mediaItem);
                    
                    // 准备并播放
                    player.setMediaSource(source);
                    player.prepare();
                    player.play();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }).start();
    }
    
    /**
     * 简化播放方法
     */
    public void play(String url) {
        playUrl(url);
    }

    /**
     * 解析 PHP 接口，提取真实 m3u8 地址
     */
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
            
            // 处理 302 重定向
            int code = conn.getResponseCode();
            if (code == 301 || code == 302) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && loc.startsWith("http")) {
                    conn.disconnect();
                    return loc;
                }
            }
            
            // 读取页面内容，正则匹配 m3u8
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

    /**
     * 通用请求头（解决防盗链）
     */
    private Map<String, String> getCommonHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://cdn.jdshipin.com/");
        headers.put("Origin", "http://cdn.jdshipin.com");
        return headers;
    }

    /**
     * 设置画面比例：适应 / 填充 / 拉伸
     */
    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case FILL: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case ZOOM: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
        }
    }

    /**
     * 设置播放状态监听
     */
    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    /**
     * 播放状态回调接口
     */
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    // ==================== 新增：仅用于后台暂停 / 前台恢复 ====================
    /**
     * 暂停播放（按 Home 时调用）
     */
    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    /**
     * 恢复播放（返回 APP 时调用）
     */
    public void resume() {
        if (player != null) {
            player.play();
        }
    }

    /**
     * 释放播放器资源
     */
    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        instance = null;
    }
}
