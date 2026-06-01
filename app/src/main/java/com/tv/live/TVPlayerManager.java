package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.TextView;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

// 你自己的 FFmpeg 解码器（aar 已自带）
import app/libs/lib-decoder-ffmpeg-release.aar;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class TVPlayerManager {
    private static final String TAG = "TVPlayerLog";
    private static TVPlayerManager instance;

    // ======================
    // 双播放器核心
    // ======================
    private ExoPlayer exoPlayer;               // 主播放器
    private FFmpegDecoder ffmpegDecoder;       // 软解兜底
    private boolean isUsingFFmpeg = false;     // 是否正在用 FFmpeg

    private Context context;
    private PlayerView playerView;
    private String currentUrl;

    // 画面比例
    public enum ScaleMode { FIT, FILL, ZOOM }

    // ======================
    // 外部必须的监听接口（修复编译错误）
    // ======================
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
    }

    private OnPlayStateListener stateListener;
    private OnLiveInfoUpdateListener infoListener;

    // 频道号
    private TextView channelNumText;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_HIDE_DELAY = 3000;
    private static final long PLAY_TIMEOUT = 3000;

    // 日志时间
    private SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // ======================
    // 单例
    // ======================
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    // ======================
    // 初始化：Exo + FFmpeg
    // ======================
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        initExoPlayer();
        initFFmpegDecoder();
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // ======================
    // 初始化 ExoPlayer
    // ======================
    private void initExoPlayer() {
        DefaultRenderersFactory factory = new DefaultRenderersFactory(context);
        factory.setEnableDecoderFallback(true);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 20000, 2500, 5000)
                .build();

        exoPlayer = new ExoPlayer.Builder(context)
                .setRenderersFactory(factory)
                .setLoadControl(loadControl)
                .build();
    }

    // ======================
    // 初始化 FFmpeg（你的 aar）
    // ======================
    private void initFFmpegDecoder() {
        try {
            ffmpegDecoder = new FFmpegDecoder();
        } catch (Exception e) {
            Log.e(TAG, "FFmpeg 初始化失败", e);
        }
    }

    // ======================
    // 绑定视图
    // ======================
    public void attachPlayerView(PlayerView view) {
        this.playerView = view;
        playerView.setPlayer(exoPlayer);
    }

    // ======================
    // 对外播放：自动切换
    // ======================
    public void play(String url) {
        if (url == null || url.isEmpty()) return;
        currentUrl = url;
        isUsingFFmpeg = false;

        stopAll();
        startExoPlayer(url);

        // 3秒没出画面 → 自动切 FFmpeg
        mHandler.postDelayed(playTimeoutRunnable, PLAY_TIMEOUT);
    }

    // ======================
    // ExoPlayer 播放
    // ======================
    private void startExoPlayer(String url) {
        try {
            DefaultHttpDataSource.Factory dsFactory = new DefaultHttpDataSource.Factory();
            dsFactory.setDefaultRequestProperties(getHuyaHeaders());
            dsFactory.setAllowCrossProtocolRedirects(true);

            HlsMediaSource mediaSource = new HlsMediaSource.Factory(dsFactory)
                    .createMediaSource(MediaItem.fromUri(url));

            exoPlayer.setMediaSource(mediaSource);
            exoPlayer.prepare();
            exoPlayer.play();

            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        mHandler.removeCallbacks(playTimeoutRunnable);
                        keepScreenOn(true);
                        showChannel();
                        if (stateListener != null) stateListener.onPlayReady();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException e) {
                    Log.e(TAG, "Exo 播放失败 → 自动切 FFmpeg");
                    switchToFFmpeg();
                }
            });

        } catch (Exception e) {
            switchToFFmpeg();
        }
    }

    // ======================
    // 自动切换 FFmpeg（核心）
    // ======================
    private void switchToFFmpeg() {
        if (isUsingFFmpeg || ffmpegDecoder == null) return;

        mHandler.removeCallbacks(playTimeoutRunnable);
        isUsingFFmpeg = true;

        try {
            // 停止 Exo
            exoPlayer.stop();
            playerView.setPlayer(null);

            // 你的 FFmpeg 播放（aar 自带方法）
            ffmpegDecoder.setDisplay(playerView.getHolder());
            ffmpegDecoder.playUrl(currentUrl);

            Log.i(TAG, "✅ 已切换 FFmpeg 软解播放");
            keepScreenOn(true);
            showChannel();
            if (stateListener != null) stateListener.onPlayReady();

        } catch (Exception e) {
            Log.e(TAG, "FFmpeg 播放失败", e);
            if (stateListener != null) stateListener.onPlayError(e.getMessage());
        }
    }

    // ======================
    // 超时自动切兜底
    // ======================
    private final Runnable playTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (exoPlayer.getPlaybackState() != Player.STATE_READY) {
                Log.e(TAG, "播放超时 → 自动切 FFmpeg");
                switchToFFmpeg();
            }
        }
    };

    // ======================
    // 虎牙专用请求头（防盗链）
    // ======================
    private Map<String, String> getHuyaHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.huya.com/");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        return headers;
    }

    // ======================
    // 停止全部
    // ======================
    public void stopAll() {
        mHandler.removeCallbacks(playTimeoutRunnable);
        keepScreenOn(false);

        if (exoPlayer != null) exoPlayer.stop();
        if (ffmpegDecoder != null) ffmpegDecoder.stop();
    }

    // ======================
    // 释放
    // ======================
    public void release() {
        stopAll();
        mHandler.removeCallbacksAndMessages(null);

        if (exoPlayer != null) exoPlayer.release();
        if (ffmpegDecoder != null) ffmpegDecoder.release();

        instance = null;
    }

    // ======================
    // 画面比例
    // ======================
    public void setScaleMode(ScaleMode mode) {
        if (playerView == null || isUsingFFmpeg) return;

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

    // ======================
    // 外部监听设置
    // ======================
    public void setOnPlayStateListener(OnPlayStateListener listener) {
        this.stateListener = listener;
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoListener = listener;
    }

    // ======================
    // 频道号显示
    // ======================
    public void bindChannelText(TextView textView) {
        this.channelNumText = textView;
    }

    public void setCurrentChannelNumber(int num) {
        // 可自行扩展
    }

    private void showChannel() {
        if (channelNumText == null) return;
        channelNumText.setVisibility(TextView.VISIBLE);
        mHandler.removeCallbacks(hideChannelRunnable);
        mHandler.postDelayed(hideChannelRunnable, CHANNEL_HIDE_DELAY);
    }

    private Runnable hideChannelRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelNumText != null) {
                channelNumText.setVisibility(TextView.GONE);
            }
        }
    };

    private void keepScreenOn(boolean enable) {
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }
}
