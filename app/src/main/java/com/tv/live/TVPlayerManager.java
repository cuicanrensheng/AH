package com.tv.live;

import android.content.Context;
import android.net.Uri;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.Util;
import java.util.HashMap;
import java.util.Map;

public class TVPlayerManager {
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private PlayerView playerView;
    private OnLiveInfoUpdateListener liveInfoUpdateListener;

    // 直播信息回调接口
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    public static class LiveInfo {
        public String quality = "未知";
        public String audio = "未知";
        public String bitrate = "未知";
    }

    private TVPlayerManager() {
        // 私有构造，单例模式
    }

    public static TVPlayerManager getInstance() {
        if (instance == null) {
            synchronized (TVPlayerManager.class) {
                if (instance == null) {
                    instance = new TVPlayerManager();
                }
            }
        }
        return instance;
    }

    /**
     * 绑定 PlayerView
     */
    public void attachPlayerView(PlayerView playerView) {
        this.playerView = playerView;
        Context context = playerView.getContext();
        if (player == null) {
            // 关键：创建带完整请求头和重定向支持的播放器
            createExoPlayer(context);
        }
        playerView.setPlayer(player);
    }

    /**
     * 创建 ExoPlayer，核心配置在这里
     */
    private void createExoPlayer(Context context) {
        // 1. 创建支持重定向和自定义请求头的 DataSource
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                // 和浏览器一致的 User-Agent，绕过反爬
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                // 关键：允许 HTTP -> HTTPS 跨协议重定向
                .setAllowCrossProtocolRedirects(true)
                // 关键：带上抓包中验证过的 Referer 等请求头
                .setDefaultRequestProperties(getHeaders());

        // 2. 创建 ExoPlayer
        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new com.google.android.exoplayer2.source.DefaultMediaSourceFactory(dataSourceFactory))
                .build();

        // 可选：添加播放状态监听
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                // 这里可以处理播放状态，如缓冲、播放完成等
            }
        });
    }

    /**
     * 返回和抓包中一致的请求头
     */
    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://cdn.jdshipin.com/");
        headers.put("Accept", "*/*");
        headers.put("Icy-MetaData", "1");
        headers.put("Accept-Encoding", "identity");
        return headers;
    }

    /**
     * 播放指定 URL 的直播流
     */
    public void playUrl(String url) {
        if (player == null) return;
        player.stop();
        player.clearMediaItems();

        // 创建媒体项并播放
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();

        // 模拟直播信息更新（你可以根据实际情况扩展）
        if (liveInfoUpdateListener != null) {
            LiveInfo info = new LiveInfo();
            info.quality = "高清";
            info.audio = "立体声";
            info.bitrate = "直播流";
            liveInfoUpdateListener.onLiveInfoUpdate(info);
        }
    }

    /**
     * 设置直播信息回调
     */
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.liveInfoUpdateListener = listener;
    }

    /**
     * 暂停
     */
    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    /**
     * 恢复播放
     */
    public void resume() {
        if (player != null) {
            player.play();
        }
    }

    /**
     * 切到后台时调用
     */
    public void onBackground() {
        pause();
    }

    /**
     * 切回前台时调用
     */
    public void onForeground() {
        resume();
    }

    /**
     * 释放播放器资源
     */
    public void release() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        playerView = null;
    }

    /**
     * 获取当前直播信息
     */
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "高清";
        info.audio = "立体声";
        info.bitrate = "直播流";
        return info;
    }
}
