package com.tv.live;

import android.content.Context;
import android.net.Uri;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 电视直播播放器管理器
 * 单例模式，全局控制 ExoPlayer 播放、暂停、缩放、释放
 */
public class TVPlayerManager {

    // 单例实例
    private static TVPlayerManager instance;

    // ExoPlayer 核心播放器
    private ExoPlayer player;

    // 播放视图控件
    private PlayerView playerView;

    // 直播信息回调
    private OnLiveInfoUpdateListener liveInfoUpdateListener;

    // 播放状态回调
    private OnPlayStateListener playStateListener;

    /**
     * 画面缩放模式
     * FIT：适应屏幕
     * FILL：填充屏幕
     * ZOOM：裁剪铺满
     */
    public enum ScaleMode {
        FIT, FILL, ZOOM
    }

    /**
     * 直播信息更新接口
     */
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    /**
     * 播放状态监听接口
     */
    public interface OnPlayStateListener {
        void onPlayStarted();    // 开始播放
        void onPlayPaused();     // 暂停
        void onPlayCompleted();  // 播放完成
        void onPlayError(Exception e); // 出错
        void onBuffering();      // 缓冲中
        void onPlaying();        // 播放中
    }

    /**
     * 直播信息实体：清晰度、音频、码率
     */
    public static class LiveInfo {
        public String quality = "未知";
        public String audio = "未知";
        public String bitrate = "未知";
    }

    // 私有构造（单例）
    private TVPlayerManager() {}

    /**
     * 获取单例
     */
    public static TVPlayerManager getInstance() {
        if (instance == null) {
            synchronized (TVPlayerManager.class) {
                if (instance == null)
                    instance = new TVPlayerManager();
            }
        }
        return instance;
    }

    /**
     * 设置播放状态监听
     */
    public void setOnPlayStateListener(OnPlayStateListener listener) {
        this.playStateListener = listener;
    }

    /**
     * 绑定播放器视图
     */
    public void attachPlayerView(PlayerView playerView) {
        this.playerView = playerView;
        Context context = playerView.getContext();
        if (player == null)
            createExoPlayer(context);
        playerView.setPlayer(player);
    }

    /**
     * 创建 ExoPlayer 实例，配置 HTTP 数据源
     */
    private void createExoPlayer(Context context) {
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0")
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(getHeaders());

        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new com.google.android.exoplayer2.source.DefaultMediaSourceFactory(dataSourceFactory))
                .build();
    }

    /**
     * 配置直播请求头（防盗链用）
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
     * 播放地址
     */
    public void playUrl(String url) {
        if (player == null) return;
        player.stop();
        player.clearMediaItems();
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    /**
     * 设置直播信息监听
     */
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.liveInfoUpdateListener = listener;
    }

    /**
     * 暂停
     */
    public void pause() {
        if (player != null) player.pause();
    }

    /**
     * 继续播放
     */
    public void resume() {
        if (player != null) player.play();
    }

    /**
     * 进入后台
     */
    public void onBackground() {
        pause();
    }

    /**
     * 回到前台
     */
    public void onForeground() {
        resume();
    }

    /**
     * 释放播放器，防止内存泄漏
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
     * 获取当前直播信息（默认写死，可根据实际修改）
     */
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "高清";
        info.audio = "立体声";
        info.bitrate = "直播流";
        return info;
    }

    /**
     * 设置画面缩放模式（已修复新版 ExoPlayer 常量）
     */
    public void setScaleMode(ScaleMode scaleMode) {
        if (playerView != null) {
            playerView.setResizeMode(
                    scaleMode == ScaleMode.FIT ? AspectRatioFrameLayout.RESIZE_MODE_FIT :
                    scaleMode == ScaleMode.FILL ? AspectRatioFrameLayout.RESIZE_MODE_FILL :
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            );
        }
    }
}
