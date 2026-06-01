package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

// 👇 全部是最新 Media3 包
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.ui.PlayerView;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DataSource;

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
    private boolean isPlaying = false;
    private int currentChannelNumber = 0;
    private Player.Listener mInternalListener;

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

    // ==========================
    // 单例
    // ==========================
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    // ==========================
    // 最新版初始化（无任何停顿）
    // ==========================
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        // 自动解码兼容，不卡顿
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // 极速缓冲，不停顿
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        5000,   // 最小缓冲
                        10000,  // 最大缓冲
                        1000,   // 开播缓冲
                        2000    // 重新缓冲
                )
                .build();

        // 创建最新播放器
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();
    }

    // ==========================
    // 绑定视图
    // ==========================
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // ==========================
    // 屏幕常亮
    // ==========================
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    // ==========================
    // 完整请求头，不断流
    // ==========================
    private Map<String, String> getAutoHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
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

    // ==========================
    // 播放核心（秒开、不停顿）
    // ==========================
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;

        // 清空监听
        if (mInternalListener != null) {
            player.removeListener(mInternalListener);
        }

        // 配置网络
        Map<String, String> headers = getAutoHeaders(url);
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000);

        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);
        MediaItem mediaItem = MediaItem.fromUri(url);

        // 最新版播放方式
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
        updateWakeLock(true);

        // 监听
        mInternalListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                if (listener != null) listener.onPlayError(error.getMessage());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        if (listener != null) listener.onBuffering();
                        break;
                    case Player.STATE_READY:
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
    }

    // ==========================
    // 基础方法
    // ==========================
    public void play(String url) { playUrl(url); }
    public void setCurrentChannelNumber(int num) { currentChannelNumber = num; }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "SD";
        info.audio = "立体声";
        info.bitrate = "0.0Mbps";
        info.channelNum = currentChannelNumber;
        return info;
    }

    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(PlayerView.RESIZE_MODE_FIT; break;
            case FILL: playerView.setResizeMode(PlayerView.RESIZE_MODE_FILL; break;
            case ZOOM: playerView.setResizeMode(PlayerView.RESIZE_MODE_ZOOM; break;
        }
    }

    public interface OnPlayStateListener {
        void onIdle(); void onBuffering(); void onPlayReady(); void onPlayEnd(); void onPlayError(String msg);
    }
    public void setOnPlayStateListener(OnPlayStateListener l) { listener = l; }
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener l) { infoUpdateListener = l; }

    public void pause() { if (player != null) player.pause(); }
    public void resume() { if (player != null) player.play(); }
    public void release() { if (player != null) player.release(); instance = null; }
}
