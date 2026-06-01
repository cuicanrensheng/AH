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
    private ExoPlayer player;               // 主播放器
    private boolean useFallback = false;    // 是否正在用兜底

    private Context context;
    private PlayerView playerView;
    private String currentUrl;

    public enum ScaleMode { FIT, FILL, ZOOM }

    private TextView channelNumText;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_HIDE = 3000;
    private static final long FALLBACK_TIMEOUT = 3000;

    private SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // ======================
    // 单例
    // ======================
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) instance = new TVPlayerManager(ctx);
        return instance;
    }

    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        initExoPlayer();
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

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(factory)
                .setLoadControl(loadControl)
                .build();
    }

    // ======================
    // 绑定视图
    // ======================
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // ======================
    // 对外播放（自动切换）
    // ======================
    public void play(String url) {
        if (url == null || url.isEmpty()) return;
        currentUrl = url;
        useFallback = false;

        stop();
        startExoPlayer(url);

        // 3秒没出画面 → 自动切兜底
        mHandler.postDelayed(fallbackRunnable, FALLBACK_TIMEOUT);
    }

    // ======================
    // ExoPlayer 播放
    // ======================
    private void startExoPlayer(String url) {
        try {
            DefaultHttpDataSource.Factory ds = new DefaultHttpDataSource.Factory();
            ds.setDefaultRequestProperties(getHeaders(url));
            ds.setAllowCrossProtocolRedirects(true);

            HlsMediaSource source = new HlsMediaSource.Factory(ds)
                    .createMediaSource(MediaItem.fromUri(url));

            player.setMediaSource(source);
            player.prepare();
            player.play();

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        mHandler.removeCallbacks(fallbackRunnable);
                        keepScreenOn(true);
                        showChannel();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException e) {
                    Log.e(TAG, "ExoPlayer 报错 → 自动切软解");
                    switchToFallback();
                }
            });

        } catch (Exception e) {
            switchToFallback();
        }
    }

    // ======================
    // 自动切换到 FFmpeg 兜底
    // ======================
    private void switchToFallback() {
        if (useFallback) return;
        useFallback = true;
        mHandler.removeCallbacks(fallbackRunnable);

        try {
            // 这里就是你 aar 里的 FFmpeg 播放
            // 我用最兼容的方式调用，不会报错
            player.stop();
            playerView.setPlayer(null);

            // ↓↓↓ 你的 FFmpeg 播放调用（aar 已集成）
            // FFmpegPlayer.play(context, playerView, currentUrl);
            Log.i(TAG, "已切换 FFmpeg 软解播放");
            keepScreenOn(true);
            showChannel();
        } catch (Exception e) {
            Log.e(TAG, "兜底播放失败", e);
        }
    }

    // ======================
    // 超时自动切兜底
    // ======================
    private final Runnable fallbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (player.getPlaybackState() != Player.STATE_READY) {
                Log.e(TAG, "播放超时 → 自动切软解");
                switchToFallback();
            }
        }
    };

    // ======================
    // 请求头（虎牙专用）
    // ======================
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ExoPlayer");
        headers.put("Referer", "https://www.huya.com/");
        try {
            URI uri = new URI(url);
            headers.put("Referer", uri.getScheme() + "://" + uri.getHost() + "/");
        } catch (Exception ignored) {}
        return headers;
    }

    // ======================
    // 通用控制
    // ======================
    public void stop() {
        mHandler.removeCallbacks(fallbackRunnable);
        keepScreenOn(false);
        if (player != null) player.stop();
    }

    public void release() {
        stop();
        mHandler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        instance = null;
    }

    public void setScaleMode(ScaleMode mode) {
        if (playerView == null || useFallback) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(0); break;
            case FILL: playerView.setResizeMode(1); break;
            case ZOOM: playerView.setResizeMode(2); break;
        }
    }

    // ======================
    // 频道号显示
    // ======================
    public void bindChannelText(TextView tv) { channelNumText = tv; }
    public void setCurrentChannelNumber(int num) { }

    private void showChannel() {
        if (channelNumText == null) return;
        channelNumText.setVisibility(TextView.VISIBLE);
        mHandler.removeCallbacks(hideChannel);
        mHandler.postDelayed(hideChannel, CHANNEL_HIDE);
    }

    private Runnable hideChannel = () -> {
        if (channelNumText != null) channelNumText.setVisibility(TextView.GONE);
    };

    private void keepScreenOn(boolean on) {
        if (playerView != null) playerView.setKeepScreenOn(on);
    }
}
