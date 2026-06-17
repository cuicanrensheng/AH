package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.TextView;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ================================================
 * 播放器管理类（彻底修复重启黑屏版）
 * 修复点：
 * 1. 监听Surface创建，渲染层就绪后再播放，解决时序差黑屏
 * 2. 优化单例释放逻辑，避免Activity重建时状态残留
 * 3. 首帧渲染强制刷新视图，解决部分设备渲染滞后
 * 4. 前后台切换时Surface重建自动恢复
 * ================================================
 */
public class TVPlayerManager {
    private static final String TAG = "TVPlayerLog";
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    private String currentPlayUrl = "";
    private boolean isSurfaceReady = false; // Surface是否就绪标记
    private String pendingPlayUrl = "";     // 待播放的URL（Surface没好时先缓存）

    public enum ScaleMode { FIT, FILL, ZOOM }

    private OnPlayStateListener listener;
    private String currentUrl = "";
    private boolean isPlaying = false;
    private int currentChannelNumber = 0;

    private TextView channelNumText;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_SHOW_DURATION = 3000L;
    private final SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private OnLiveInfoUpdateListener infoUpdateListener;

    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
    }

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "HD";
        info.audio = "立体声";
        info.bitrate = "4.5MB/s";
        info.channelNum = currentChannelNumber;
        return info;
    }

    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    infoUpdateListener.onLiveInfoUpdate(getLiveInfo()));
        }
    }

    public void bindChannelText(TextView textView) {
        this.channelNumText = textView;
    }

    private void showChannelAndAutoHide() {
        if (channelNumText == null) return;
        mHandler.removeCallbacks(hideChannelRunnable);
        channelNumText.setText("频道：" + currentChannelNumber);
        channelNumText.setVisibility(View.VISIBLE);
        mHandler.postDelayed(hideChannelRunnable, CHANNEL_SHOW_DURATION);
    }

    private final Runnable hideChannelRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelNumText != null) {
                channelNumText.setVisibility(View.GONE);
            }
        }
    };

    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);
        renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);

        // 优化缓冲：降低首帧等待时间
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 30000, 500, 1000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);

        MainActivity.log(getLogTime() + " 播放器初始化完成（重启黑屏优化版）");
    }

    /**
     * ================================================
     * 绑定播放视图（核心修复：监听Surface创建）
     * ================================================
     */
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setUseController(false);
        playerView.setPlayer(player);

        // 监听Surface生命周期，解决重启黑屏
        playerView.getVideoSurfaceView().getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                isSurfaceReady = true;
                MainActivity.log(getLogTime() + " 渲染Surface已创建");
                // Surface就绪后，如果有待播放的URL，立刻开始播放
                if (!pendingPlayUrl.isEmpty()) {
                    String url = pendingPlayUrl;
                    pendingPlayUrl = "";
                    realPlay(url);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // Surface尺寸变化，强制刷新播放器
                if (player != null) {
                    player.setVideoSurfaceHolder(holder);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                isSurfaceReady = false;
                MainActivity.log(getLogTime() + " 渲染Surface已销毁");
            }
        });
    }

    public void onBackground() {
        try {
            if (player != null) {
                player.pause();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        MainActivity.log(getLogTime() + " 切换到后台");
    }

    /**
     * ================================================
     * 切前台修复：Surface重建后自动恢复
     * ================================================
     */
    public void onForeground() {
        try {
            if (playerView != null && player != null) {
                playerView.setPlayer(player);
            }
            if (player != null) {
                if (player.getPlaybackState() == Player.STATE_IDLE && !currentPlayUrl.isEmpty()) {
                    playUrl(currentPlayUrl);
                } else {
                    player.play();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            MainActivity.log(getLogTime() + " 前台恢复异常，尝试重连");
            if (!currentPlayUrl.isEmpty()) {
                playUrl(currentPlayUrl);
            }
        }
        MainActivity.log(getLogTime() + " 切换到前台");
    }

    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }

    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ExoPlayer");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        headers.put("Icy-MetaData", "1");
        headers.put("Referer", "https://www.huya.com/");

        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }
        return headers;
    }

    public void play(String url) {
        playUrl(url);
    }

    /**
     * ================================================
     * 播放入口：Surface就绪直接播，没就绪先缓存
     * ================================================
     */
    public void playUrl(String url) {
        if (player == null || url == null || url.trim().isEmpty()) return;
        currentUrl = url.trim();
        currentPlayUrl = currentUrl;

        // Surface已经就绪，直接播放
        if (isSurfaceReady) {
            realPlay(url);
        } else {
            // Surface还没创建好，先缓存URL，等Surface就绪后自动播放
            pendingPlayUrl = url.trim();
            MainActivity.log(getLogTime() + " Surface未就绪，缓存播放地址等待渲染层初始化");
        }
    }

    /**
     * 真正的播放执行逻辑
     */
    private void realPlay(String url) {
        try {
            String shortUrl = url.length() > 50 ? url.substring(0, 50) + "..." : url;
            MainActivity.log(getLogTime() + " 开始播放：" + shortUrl);

            player.stop();
            player.clearMediaItems();

            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(getHeaders(url))
                    .setConnectTimeoutMs(5000)
                    .setReadTimeoutMs(10000)
                    .setAllowCrossProtocolRedirects(true);

            MediaItem mediaItem = MediaItem.fromUri(url);
            com.google.android.exoplayer2.source.MediaSource mediaSource;

            if (url.toLowerCase().contains("m3u8")) {
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();

            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "播放异常: " + error.getMessage());
                    MainActivity.log(getLogTime() + " ❌ 播放错误：" + error.getMessage());
                    if (listener != null) listener.onPlayError(error.getMessage());

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        playUrl(currentUrl);
                    }, 1000);
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        updateWakeLock(true);
                        notifyLiveInfoUpdate();
                        showChannelAndAutoHide();
                        // 首帧就绪后强制刷新视图，解决部分设备渲染卡顿
                        if (playerView != null) {
                            playerView.postInvalidate();
                        }
                        MainActivity.log(getLogTime() + " ✅ 播放就绪");
                        if (listener != null) listener.onPlayReady();
                    } else if (state == Player.STATE_BUFFERING) {
                        MainActivity.log(getLogTime() + " ⏳ 缓冲中...");
                        if (listener != null) listener.onBuffering();
                    } else if (state == Player.STATE_ENDED) {
                        MainActivity.log(getLogTime() + " 播放结束");
                        if (listener != null) listener.onPlayEnd();
                    } else if (state == Player.STATE_IDLE) {
                        if (listener != null) listener.onIdle();
                    } else {
                        updateWakeLock(false);
                    }
                }

                @Override
                public void onRenderedFirstFrame() {
                    MainActivity.log(getLogTime() + " 🎬 首帧渲染完成，画面已显示");
                    // 首帧渲染后，强制刷新View布局
                    if (playerView != null) {
                        playerView.requestLayout();
                        playerView.invalidate();
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "全局异常", e);
            MainActivity.log(getLogTime() + " ❌ 异常：" + e.getMessage());
        }
    }

    public void setScaleMode(ScaleMode mode) {
        try {
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
        } catch (Exception e) {
            e.printStackTrace();
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
        try { if (player != null) player.pause(); } catch (Exception e) {}
    }

    public void resume() {
        try { if (player != null) player.play(); } catch (Exception e) {}
    }

    /**
     * 释放资源：彻底清空状态，避免重启残留
     */
    public void release() {
        try {
            mHandler.removeCallbacks(hideChannelRunnable);
            updateWakeLock(false);
            pendingPlayUrl = "";
            isSurfaceReady = false;
            if (player != null) {
                player.release();
                player = null;
            }
            instance = null;
            MainActivity.log(getLogTime() + " 播放器已彻底释放");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
