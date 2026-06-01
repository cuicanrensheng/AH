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
    private String autoCookie = "";
    private boolean isPlaying = false;

    // ====================== 播放信息结构 ======================
    public static class LiveInfo {
        public String quality;   // 画质等级：FHD/HD/SD
        public String audio;     // 音频格式：立体声/单声道
        public String bitrate;   // 实时码率：X.XMB/s
    }

    // ====================== 信息更新回调接口 ======================
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }
    private OnLiveInfoUpdateListener infoUpdateListener;

    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        // 硬解失败自动切软解（治黑屏）
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // 缓冲优化
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 5000, 10000)
                .build();

        // 自动识别数据源（核心）
        HttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0")
                .setDefaultRequestProperties(getHeaders());

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

    // 请求头 + Cookie
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

    // ====================== 自动识别播放信息 ======================
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        // 兜底默认值
        info.quality = "HD";
        info.audio = "立体声";
        info.bitrate = "0.0MB/s";

        // 播放就绪后，自动读取真实信息
        if (player != null && player.getPlaybackState() == Player.STATE_READY) {
            // 1. 自动识别画质（分辨率）
            if (player.getVideoFormat() != null) {
                int height = player.getVideoFormat().height;
                if (height >= 1080) {
                    info.quality = "FHD";
                } else if (height >= 720) {
                    info.quality = "HD";
                } else {
                    info.quality = "SD";
                }

                // 2. 自动计算实时码率
                long bitrate = player.getVideoFormat().bitrate;
                double mbps = bitrate / 1_000_000.0;
                info.bitrate = String.format("%.1fMB/s", mbps);
            }

            // 3. 自动识别音频声道
            if (player.getAudioFormat() != null) {
                int channels = player.getAudioFormat().channelCount;
                info.audio = (channels >= 2) ? "立体声" : "单声道";
            }
        }
        return info;
    }

    // ====================== 播放入口 ======================
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
                    case Player.STATE_BUFFERING:
                        SettingsActivity.log("⌛ 缓冲中");
                        break;
                    case Player.STATE_READY:
                        SettingsActivity.log("✅ 播放正常");
                        updateWakeLock(true);
                        // 播放就绪后，立刻通知界面刷新信息
                        notifyLiveInfoUpdate();
                        break;
                    case Player.STATE_IDLE:
                    case Player.STATE_ENDED:
                        updateWakeLock(false);
                        break;
                }
            }
        });

        MediaItem mediaItem = MediaItem.fromUri(url);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    // 通知界面刷新播放信息
    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                infoUpdateListener.onLiveInfoUpdate(getLiveInfo());
            });
        }
    }

    // 给界面设置监听
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    public void play(String url) {
        playUrl(url);
    }

    // 画面比例
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
