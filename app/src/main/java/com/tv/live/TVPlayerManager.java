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
import java.util.HashMap;
import java.util.Map;
public class TVPlayerManager {
    private static TVPlayerManager instance;
    private static Context appContext;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    public enum ScaleMode { FIT, FILL, ZOOM }
    private OnPlayStateListener listener;
    private String currentUrl = "";
    private String autoCookie = "";
    private boolean isPlaying = false;
    private int currentChannelNumber = 0;
    // 播放信息（画质、音频、码率、频道号）
    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
    }
    // 实时刷新回调
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }
    private OnLiveInfoUpdateListener infoUpdateListener;

    public static void setAppContext(Context ctx){
        appContext = ctx.getApplicationContext();
    }

    //修复无参单例，不会返回null
    public static TVPlayerManager getInstance() {
        if(instance == null && appContext != null){
            instance = new TVPlayerManager(appContext);
        }
        return instance;
    }
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }
    // 构造：自动识别数据源 + 解码兼容（不黑屏）
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 5000, 10000)
                .build();
        // 自动识别 OkHttp / 系统 HTTP
        HttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0")
                .setDefaultRequestProperties(getHeaders());
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        // Cookie 完整保留
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) playerView.setKeepScreenOn(enable);
    }
    // 请求头 + Cookie 都在
    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Referer", "http://hwrr.jx.chinamobile.com:8080/");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        if (autoCookie != null && !autoCookie.isEmpty()) {
            headers.put("Cookie", autoCookie);
        }
        return headers;
    }
    // 设置频道号
    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }
    // 自动获取播放信息
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
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                } else if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    updateWakeLock(false);
                }
            }
        });
        MediaItem item = MediaItem.fromUri(url);
        player.setMediaItem(item);
        player.prepare();
        player.play();
    }
    // 通知界面刷新
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
    public void play(String url) { playUrl(url); }
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
        void onIdle(); void onBuffering(); void onPlayReady(); void onPlayEnd(); void onPlayError(String msg);
    }
    public void setOnPlayStateListener(OnPlayStateListener l) { listener = l; }
    public void onBackground(){}
    public void onForeground(){}
    // 播放控制
    public void pause() { if (player != null) { player.pause(); updateWakeLock(false); } }
    public void resume() { if (player != null) { player.play(); updateWakeLock(true); } }
    public void release() { updateWakeLock(false); if (player != null) player.release(); instance = null; }
}
