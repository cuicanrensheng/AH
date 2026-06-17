package com.tv.live;

import android.content.Context;
import android.os.Build;
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

public class TVPlayerManager {
    private static final String TAG = "TVPlayerLog";
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    private String currentPlayUrl = "";

    // Surface就绪标记（解决重启黑屏核心）
    private boolean isSurfaceReady = false;
    // 待播放缓存（Surface没好时先存着）
    private String pendingPlayUrl = "";

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

    /**
     * 构造函数：优化缓冲配置 + 修复废弃Cookie API
     */
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        // 渲染器配置：开启解码降级，兼容更多设备
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // ✅ 优化缓冲：降低阈值，加快首帧出画面（解决黑屏等待久核心）
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 15000, 500, 1000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // ✅ 修复废弃API：适配高版本Android，避免Cookie异常导致网络慢
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.flush();
        } else {
            CookieSyncManager.createInstance(context);
            CookieSyncManager.getInstance().sync();
        }

        SettingsActivity.log(getLogTime() + " 播放器初始化完成");
    }

    /**
     * 绑定播放视图：新增Surface生命周期监听（解决重启黑屏核心）
     */
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setUseController(false);
        playerView.setPlayer(player);

        // 监听渲染Surface创建，确保画面能渲染
        playerView.getVideoSurfaceView().getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                isSurfaceReady = true;
                SettingsActivity.log(getLogTime() + " 渲染Surface已就绪");
                // Surface创建完成后，播放待缓存的地址
                if (!pendingPlayUrl.isEmpty()) {
                    String url = pendingPlayUrl;
                    pendingPlayUrl = "";
                    realPlay(url);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (player != null) {
                    player.setVideoSurfaceHolder(holder);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                isSurfaceReady = false;
            }
        });
    }

    /**
     * 切后台：仅暂停，不解绑Surface
     */
    public void onBackground() {
        try {
            if (player != null) {
                player.pause();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        SettingsActivity.log(getLogTime() + " 切换到后台");
    }

    /**
     * 切前台：优先恢复播放，不重新加载
     */
    public void onForeground() {
        try {
            if (player != null && playerView != null) {
                // 状态正常直接恢复播放
                if (player.getPlaybackState() != Player.STATE_IDLE
                        && player.getPlaybackState() != Player.STATE_ENDED) {
                    player.play();
                } else if (!currentPlayUrl.isEmpty()) {
                    // 空闲状态才重新加载
                    playUrl(currentPlayUrl);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (!currentPlayUrl.isEmpty()) {
                playUrl(currentPlayUrl);
            }
        }
        SettingsActivity.log(getLogTime() + " 切换到前台");
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
     * 播放入口：Surface就绪直接播，没就绪先缓存
     */
    public void playUrl(String url) {
        if (player == null || url == null || url.trim().isEmpty()) return;
        currentUrl = url.trim();
        currentPlayUrl = currentUrl;

        if (isSurfaceReady) {
            // 渲染层就绪，直接播放
            realPlay(url);
        } else {
            // 渲染层未就绪，先缓存等待
            pendingPlayUrl = url.trim();
            SettingsActivity.log(getLogTime() + " 渲染层未就绪，等待Surface创建后播放");
        }
    }

    /**
     * 真正的播放执行逻辑
     * ✅ 移除了自动错误重试，和你的PlayerStateListenerImpl逻辑完全一致
     */
    private void realPlay(String url) {
        try {
            String shortUrl = url.length() > 600 ? url.substring(0, 600) + "..." : url;
            SettingsActivity.log(getLogTime() + " 开始播放：" + shortUrl);

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
                    SettingsActivity.log(getLogTime() + " ❌ 播放错误：" + error.getMessage());
                    // ✅ 仅回调错误，不自动重试（和Listener逻辑一致）
                    if (listener != null) {
                        listener.onPlayError(error.getMessage());
                    }
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        updateWakeLock(true);
                        notifyLiveInfoUpdate();
                        showChannelAndAutoHide();
                        // 首帧就绪后强制刷新视图
                        if (playerView != null) {
                            playerView.postInvalidate();
                        }
                        SettingsActivity.log(getLogTime() + " ✅ 播放成功");
                        if (listener != null) listener.onPlayReady();
                    } else if (state == Player.STATE_BUFFERING) {
                        SettingsActivity.log(getLogTime() + " ⏳ 缓冲中...");
                        if (listener != null) listener.onBuffering();
                    } else if (state == Player.STATE_ENDED) {
                        SettingsActivity.log(getLogTime() + " 播放结束");
                        if (listener != null) listener.onPlayEnd();
                    } else if (state == Player.STATE_IDLE) {
                        if (listener != null) listener.onIdle();
                    } else {
                        updateWakeLock(false);
                    }
                }

                // 首帧渲染回调：确认画面显示
                @Override
                public void onRenderedFirstFrame() {
                    SettingsActivity.log(getLogTime() + " 🎬 首帧渲染完成");
                    if (playerView != null) {
                        playerView.requestLayout();
                        playerView.invalidate();
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "全局异常", e);
            SettingsActivity.log(getLogTime() + " ❌ 异常：" + e.getMessage());
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
     * 释放资源：清空所有状态，避免重启残留
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
            SettingsActivity.log(getLogTime() + " 播放器释放");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
