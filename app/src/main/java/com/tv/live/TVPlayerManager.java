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
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    public enum ScaleMode { FIT, FILL, ZOOM }
    private OnPlayStateListener listener;
    private String currentUrl = "";
    private String autoCookie = "";
    private boolean isPlaying = false;
    private int currentChannelNumber = 0;

    // 直播信息实体
    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
    }

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }
    private OnLiveInfoUpdateListener infoUpdateListener;

    // 用于管理监听器，防止叠加
    private Player.Listener mInternalListener;

    // ==============================
    // 单例获取
    // ==============================
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    // ==============================
    // 初始化播放器（稳定版）
    // ==============================
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // 缓冲配置：兼顾流畅和启动速度
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 30000, 2000, 5000)
                .build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // ==============================
    // 绑定播放视图
    // ==============================
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // ==============================
    // 屏幕常亮控制
    // ==============================
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) playerView.setKeepScreenOn(enable);
    }

    // ==============================
    // 【核心修复】动态请求头（自动适配 Referer，解决 2004 错误）
    // ==============================
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");

        // 自动提取当前播放地址的域名作为 Referer
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            headers.put("Referer", scheme + "://" + host + "/");
        } catch (Exception ignored) {
            // 解析失败时，用空 Referer
            headers.put("Referer", "");
        }

        // 保留 Cookie 支持
        if (autoCookie != null && !autoCookie.isEmpty()) {
            headers.put("Cookie", autoCookie);
        }
        return headers;
    }

    // ==============================
    // 设置频道号
    // ==============================
    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    // ==============================
    // 获取实时播放信息
    // ==============================
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "HD";
        info.audio = "立体声";
        info.bitrate = "0.0MB/s";
        info.channelNum = currentChannelNumber;

        if (player != null && player.getPlaybackState() == Player.STATE_READY) {
            if (player.getVideoFormat() != null) {
                int h = player.getVideoFormat().height;
                if (h >= 1080) info.quality = "FHD";
                else if (h >= 720) info.quality = "HD";
                else info.quality = "SD";

                long b = player.getVideoFormat().bitrate;
                info.bitrate = String.format("%.1fMB/s", b / 1000000.0);
            }
            if (player.getAudioFormat() != null) {
                info.audio = player.getAudioFormat().channelCount >= 2 ? "立体声" : "单声道";
            }
        }
        return info;
    }

    // ==============================
    // 【核心修复】清空监听器，防止叠加
    // ==============================
    private void clearListeners() {
        if (player != null && mInternalListener != null) {
            player.removeListener(mInternalListener);
        }
        mInternalListener = null;
    }

    // ==============================
    // 【核心修复】切换直播源专用方法
    // ==============================
    public void switchStream(String newUrl) {
        if (player == null || newUrl == null || newUrl.isEmpty()) return;

        // 1. 强制停止并清空上一个流
        player.stop();
        player.clearMediaItems();

        // 2. 清空旧监听器
        clearListeners();

        // 3. 播放新地址
        playUrl(newUrl);
    }

    // ==============================
    // 播放核心方法
    // ==============================
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;
        SettingsActivity.log("▶ 播放：" + url);

        // 清空旧监听器
        clearListeners();

        // 动态配置当前地址的请求头
        Map<String, String> headers = getHeaders(url);
        HttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000);

        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);
        player.setMediaSourceFactory(mediaSourceFactory);

        // 添加新的监听器
        mInternalListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                // 打印详细错误信息，方便排查
                SettingsActivity.log("❌ 错误：" + error.getMessage() + " 码：" + error.errorCode);
                if (listener != null) listener.onPlayError(error.getMessage());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    if (listener != null) listener.onPlayReady();
                } else if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    updateWakeLock(false);
                } else if (state == Player.STATE_BUFFERING) {
                    if (listener != null) listener.onBuffering();
                }
            }
        };

        player.addListener(mInternalListener);

        // 设置并播放
        MediaItem item = MediaItem.fromUri(url);
        player.setMediaItem(item);
        player.prepare();
        player.play();
    }

    // ==============================
    // 通知更新直播信息
    // ==============================
    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                infoUpdateListener.onLiveInfoUpdate(getLiveInfo());
            });
        }
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    public void play(String url) {
        playUrl(url);
    }

    // ==============================
    // 设置画面比例
    // ==============================
    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT:
                playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case FILL:
                playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
            case ZOOM:
                playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
        }
    }

    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    public void pause() {
        if (player != null) {
            player.pause();
            updateWakeLock(false);
        }
    }

    public void resume() {
        if (player != null) {
            player.play();
            updateWakeLock(true);
        }
    }

    public void release() {
        updateWakeLock(false);
        if (player != null) player.release();
        instance = null;
    }
}
