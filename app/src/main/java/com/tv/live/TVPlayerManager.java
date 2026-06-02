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
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    public enum ScaleMode { FIT, FILL, ZOOM }
    private OnPlayStateListener listener;
    private String currentUrl = "";
    private String autoCookie = "";
    private boolean isPlaying = false;
    private int currentChannelNumber = 0;

    // 播放信息实体
    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
    }
    // 播放信息实时回调接口
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }
    private OnLiveInfoUpdateListener infoUpdateListener;

    // 播放器状态回调（和PlayerStateListenerImpl严格匹配5个方法）
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    // 单例获取（带上下文，完整版标准写法）
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    // 构造方法：缓冲、解码容错、请求头初始化全部保留
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 5000, 10000)
                .build();
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
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // 绑定播放器画面控件
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // 屏幕常亮开关
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    // 请求头配置（含Cookie携带）
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

    // 设置当前频道序号
    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    // 实时获取分辨率、音频、码率信息
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "HD";
        info.audio = "立体声";
        info.bitrate = "0.0MB/s";
        info.channelNum = currentChannelNumber;
        if (player != null && player.getPlaybackState() == Player.STATE_READY) {
            if (player.getVideoFormat() != null) {
                int h = player.getVideoFormat().height;
                if (h >= 1080) {
                    info.quality = "FHD";
                } else if (h >= 720) {
                    info.quality = "HD";
                } else {
                    info.quality = "SD";
                }
                long b = player.getVideoFormat().bitrate;
                info.bitrate = String.format("%.1fMB/s", b / 1000000.0);
            }
            if (player.getAudioFormat() != null) {
                info.audio = player.getAudioFormat().channelCount >= 2 ? "立体声" : "单声道";
            }
        }
        return info;
    }

    // 核心播放方法：Exo状态转发（修复回调失效关键，括号全部补全）
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) {
            return;
        }
        currentUrl = url;
        SettingsActivity.log("▶ 播放：" + url);
        // Exo原生监听转发到自定义回调
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                SettingsActivity.log("❌ 错误：" + error.getMessage() + " 码：" + error.errorCode);
                if(listener != null) {
                    listener.onPlayError(error.getMessage());
                }
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                if(listener == null) {
                    return;
                }
                switch (state) {
                    case Player.STATE_IDLE:
                        listener.onIdle();
                        updateWakeLock(false);
                        break;
                    case Player.STATE_BUFFERING:
                        listener.onBuffering();
                        break;
                    case Player.STATE_READY:
                        listener.onPlayReady();
                        updateWakeLock(true);
                        notifyLiveInfoUpdate();
                        break;
                    case Player.STATE_ENDED:
                        listener.onPlayEnd();
                        updateWakeLock(false);
                        break;
                }
            }
        });
        MediaItem item = MediaItem.fromUri(url);
        player.setMediaItem(item);
        player.prepare();
        player.play();
    }

    // 推送播放信息到主线程UI
    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                infoUpdateListener.onLiveInfoUpdate(getLiveInfo());
            });
        }
    }

    // 绑定播放信息监听
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    // 播放别名
    public void play(String url) {
        playUrl(url);
    }

    // 画面缩放切换
    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) {
            return;
        }
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

    // 绑定播放状态回调
    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    // 暂停
    public void pause() {
        if (player != null) {
            player.pause();
            updateWakeLock(false);
        }
    }

    // 继续播放
    public void resume() {
        if (player != null) {
            player.play();
            updateWakeLock(true);
        }
    }

    // 释放播放器资源
    public void release() {
        updateWakeLock(false);
        if (player != null) {
            player.release();
        }
        instance = null;
    }
}
