package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class TVPlayerManager {
    // 单例实例
    private static TVPlayerManager instance;

    // ExoPlayer 播放器核心
    private ExoPlayer player;

    // 上下文
    private Context context;

    // 播放视图
    private PlayerView playerView;

    // 画面缩放模式
    public enum ScaleMode { FIT, FILL, ZOOM }

    // 播放状态监听
    private OnPlayStateListener listener;

    // 当前播放地址
    private String currentUrl = "";

    // 是否正在播放
    private boolean isPlaying = false;

    // 当前频道号
    private int currentChannelNumber = 0;

    // 直播信息：画质、音频、码率、频道号
    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
    }

    // 直播信息更新监听
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    private OnLiveInfoUpdateListener infoUpdateListener;

    // 用于管理监听，避免重复添加
    private Player.Listener mInternalListener;

    // ==============================
    // 单例获取（全局唯一播放器）
    // ==============================
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    // ==============================
    // 构造方法：初始化播放器 + 兼容所有解码器
    // ==============================
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        // 初始化渲染器，开启解码失败自动降级（兼容所有机型）
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // ==============================
        // 【兼容补齐】方法1：设置支持的视频格式（旧版无此方法，空实现兼容）
        // ==============================
        setAllowedVideoMimeTypes(renderersFactory,
                "video/avc",    // H.264
                "video/hevc",   // H.265
                "video/mp4",    // MP4
                "video/x-vp9"   // VP9
        );

        // ==============================
        // 【兼容补齐】方法2：设置支持的音频格式（旧版无此方法，空实现兼容）
        // ==============================
        setAllowedAudioMimeTypes(renderersFactory,
                "audio/mp4a-latm", // AAC
                "audio/mpeg",      // MP3
                "audio/ac3"        // AC3
        );

        // 缓冲配置：超大缓冲，抗卡顿、抗网络波动
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(60000, 120000, 10000, 20000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        // 创建播放器
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // 初始化Cookie，支持需要登录的直播源
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // ==============================
    // 兼容补齐：允许的视频格式（低版本 Exo 无此方法，空实现兼容）
    // ==============================
    private void setAllowedVideoMimeTypes(DefaultRenderersFactory factory, String... types) {
        // 低版本 Exo 不支持此方法，保留结构用于兼容，不影响播放
    }

    // ==============================
    // 兼容补齐：允许的音频格式（低版本 Exo 无此方法，空实现兼容）
    // ==============================
    private void setAllowedAudioMimeTypes(DefaultRenderersFactory factory, String... types) {
        // 低版本 Exo 不支持此方法，保留结构用于兼容，不影响播放
    }

    // ==============================
    // 清空监听（避免重复）
    // ==============================
    private void clearListeners() {
        if (player != null && mInternalListener != null) {
            player.removeListener(mInternalListener);
        }
        mInternalListener = null;
    }

    // ==============================
    // 兼容补齐：setMediaSourceFactory（低版本不支持）
    // ==============================
    private void setMediaSourceFactory(DefaultMediaSourceFactory factory) {
        // 兼容占位，无逻辑
    }

    // ==============================
    // 绑定播放视图
    // ==============================
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // ==============================
    // 设置屏幕常亮
    // ==============================
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) playerView.setKeepScreenOn(enable);
    }

    // ==============================
    // 自动生成请求头（防盗链通用）
    // ==============================
    private Map<String, String> getAutoHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");

        // 自动提取 Referer，解决防盗链
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            headers.put("Referer", scheme + "://" + host);
        } catch (Exception ignored) {}

        // 自动携带Cookie
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null && !cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    // ==============================
    // 设置当前频道号
    // ==============================
    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    // ==============================
    // 获取实时播放信息
    // ==============================
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "SD";
        info.audio = "立体声";
        info.bitrate = "0.0Mbps";
        info.channelNum = currentChannelNumber;

        if (player != null && player.getPlaybackState() == Player.STATE_READY) {
            // 自动识别画质
            if (player.getVideoFormat() != null) {
                int h = player.getVideoFormat().height;
                if (h >= 1080) info.quality = "FHD";
                else if (h >= 720) info.quality = "HD";
                else info.quality = "SD";

                long b = player.getVideoFormat().bitrate;
                if (b > 0) info.bitrate = String.format("%.1fMbps", b / 1000000.0);
            }
            // 自动识别音频通道
            if (player.getAudioFormat() != null) {
                info.audio = player.getAudioFormat().channelCount >= 2 ? "立体声" : "单声道";
            }
        }
        return info;
    }

    // ==============================
    // 播放地址（核心方法）
    // ==============================
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;
        SettingsActivity.log("▶ 播放：" + url);

        // 清空旧监听
        clearListeners();

        // 获取自动生成的请求头
        Map<String, String> headers = getAutoHeaders(url);

        // 配置HTTP数据源
        HttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(60000);

        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);

        // 设置播放资源
        MediaItem mediaItem = MediaItem.fromUri(url);
        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem));

        // 添加播放监听
        mInternalListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                SettingsActivity.log("❌ 错误：" + error.getMessage());
                if (listener != null) listener.onPlayError(error.getMessage());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        if (listener != null) listener.onBuffering();
                        break;
                    case Player.STATE_READY:
                        updateWakeLock(true);
                        notifyLiveInfoUpdate();
                        if (listener != null) listener.onPlayReady();
                        break;
                    case Player.STATE_ENDED:
                    case Player.STATE_IDLE:
                        updateWakeLock(false);
                        break;
                }
            }
        };

        player.addListener(mInternalListener);
        player.prepare();
        player.play();
    }

    // ==============================
    // 通知更新直播信息
    // ==============================
    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    infoUpdateListener.onLiveInfoUpdate(getLiveInfo())
            );
        }
    }

    // ==============================
    // 设置信息更新监听
    // ==============================
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    // ==============================
    // 快捷播放
    // ==============================
    public void play(String url) { playUrl(url); }

    // ==============================
    // 设置画面比例
    // ==============================
    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case FILL: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case ZOOM: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
        }
    }

    // ==============================
    // 播放状态监听接口
    // ==============================
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    public void setOnPlayStateListener(OnPlayStateListener l) { listener = l; }

    // ==============================
    // 暂停
    // ==============================
    public void pause() {
        if (player != null) {
            player.pause();
            updateWakeLock(false);
        }
    }

    // ==============================
    // 恢复播放
    // ==============================
    public void resume() {
        if (player != null) {
            player.play();
            updateWakeLock(true);
        }
    }

    // ==============================
    // 释放资源
    // ==============================
    public void release() {
        updateWakeLock(false);
        if (player != null) player.release();
        instance = null;
    }
}
