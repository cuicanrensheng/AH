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
    // 单例实例（全局唯一播放器）
    private static TVPlayerManager instance;

    // ExoPlayer 核心对象
    private ExoPlayer player;

    // 上下文
    private Context context;

    // 播放视图
    private PlayerView playerView;

    // 画面缩放模式
    public enum ScaleMode { FIT, FILL, ZOOM }

    // 播放状态回调
    private OnPlayStateListener listener;

    // 当前播放地址
    private String currentUrl = "";

    // 是否正在播放
    private boolean isPlaying = false;

    // 当前频道号
    private int currentChannelNumber = 0;

    // 直播信息实体：清晰度、音频、码率、频道号
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

    // 内部监听器（避免重复添加）
    private Player.Listener mInternalListener;

    // ==============================
    // 获取单例（全局复用）
    // ==============================
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    // ==============================
    // 构造方法：初始化播放器（最稳定原版）
    // 【重要】这里删除了所有会导致卡顿的兼容代码
    // ==============================
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        // 渲染工厂：只开自动解码降级，不做多余限制
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // 缓冲配置：流畅不卡、不拖慢启动
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 5000, 10000)
                .build();

        // 创建播放器
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // 初始化 Cookie（支持需要登录的源）
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // ==============================
    // 清空监听器（防止重复）
    // ==============================
    private void clearListeners() {
        if (player != null && mInternalListener != null) {
            player.removeListener(mInternalListener);
        }
        mInternalListener = null;
    }

    // ==============================
    // 兼容占位：旧版不支持，空实现
    // ==============================
    private void setMediaSourceFactory(DefaultMediaSourceFactory factory) {}

    // ==============================
    // 绑定播放视图
    // ==============================
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // ==============================
    // 屏幕常亮
    // ==============================
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    // ==============================
    // 自动生成请求头（防盗链必备）
    // 自动 Referer + UA + Cookie
    // ==============================
    private Map<String, String> getAutoHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");

        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            headers.put("Referer", scheme + "://" + host);
        } catch (Exception ignored) {}

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
            if (player.getVideoFormat() != null) {
                int h = player.getVideoFormat().height;
                if (h >= 1080) info.quality = "FHD";
                else if (h >= 720) info.quality = "HD";
                else info.quality = "SD";

                long b = player.getVideoFormat().bitrate;
                if (b > 0) info.bitrate = String.format("%.1fMbps", b / 1000000.0);
            }
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

        clearListeners();

        Map<String, String> headers = getAutoHeaders(url);

        HttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(20000);

        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);

        MediaItem mediaItem = MediaItem.fromUri(url);
        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem));

        mInternalListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                SettingsActivity.log("❌ 播放错误：" + error.getMessage());
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
    // 设置信息监听
    // ==============================
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    // ==============================
    // 快捷播放
    // ==============================
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

    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

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
