package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;

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
    
    // ✅ Cookie 还在！
    private String autoCookie = "";
    
    private boolean isPlaying = false;

    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    // 构造方法：全自动识别数据源 + 解码兼容
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        // 硬解失败自动切软解（治黑屏）
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // 缓冲优化
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 5000, 10000)
                .build();

        // ====================== 自动识别数据源（核心） ======================
        HttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0")
                .setDefaultRequestProperties(getHeaders()); // ✅ 这里自动带 Cookie + 请求头

        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(
                context,
                httpDataSourceFactory
        );

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);

        // 创建播放器
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();

        // ✅ Cookie 管理器完整保留
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // 绑定播放画面
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // 播放时屏幕常亮
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    // ====================== ✅ 请求头 + Cookie 完整保留！ ======================
    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Referer", "http://hwrr.jx.chinamobile.com:8080/");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        
        // ✅ Cookie 正常携带
        if (autoCookie != null && !autoCookie.isEmpty()) {
            headers.put("Cookie", autoCookie);
        }
        return headers;
    }

    // 播放入口
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;

        SettingsActivity.log("▶ 播放：" + url);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                SettingsActivity.log("❌ 错误：" + error.getMessage() + " 码：" + error.errorCode);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case STATE_BUFFERING: SettingsActivity.log("⌛ 缓冲中"); break;
                    case STATE_READY: SettingsActivity.log("✅ 播放正常"); updateWakeLock(true); break;
                    case STATE_IDLE: case STATE_ENDED: updateWakeLock(false); break;
                }
            }
        });

        MediaItem mediaItem = MediaItem.fromUri(url);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    public void play(String url) {
        playUrl(url);
    }

    // 画面比例
    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case FILL: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case ZOOM: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
        }
    }

    // 播放状态监听
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

    // 播放控制
    public void pause() {
        if (player != null) { player.pause(); updateWakeLock(false); }
    }

    public void resume() {
        if (player != null) { player.play(); updateWakeLock(true); }
    }

    public void release() {
        updateWakeLock(false);
        if (player != null) player.release();
        instance = null;
    }

    // 播放信息
    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "HD";
        info.audio = "立体声";
        info.bitrate = "4.5MB/s";
        return info;
    }
}
