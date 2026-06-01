package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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

    // 频道号文本控件 + 延时隐藏(3秒)
    private TextView channelNumText;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // 显示时长 3000ms = 3秒
    private static final long CHANNEL_SHOW_DURATION = 3000L;

    // 补全外部调用所需类与接口
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

    // 绑定频道号TextView，外部调用此方法传入控件
    public void bindChannelText(TextView textView) {
        this.channelNumText = textView;
    }

    // 显示频道号，并3秒后自动隐藏
    private void showChannelAndAutoHide() {
        if (channelNumText == null) return;
        // 先移除之前的延时任务，避免叠加
        mHandler.removeCallbacks(hideChannelRunnable);
        // 显示
        channelNumText.setText("频道：" + currentChannelNumber);
        channelNumText.setVisibility(android.view.View.VISIBLE);
        // 3秒后隐藏
        mHandler.postDelayed(hideChannelRunnable, CHANNEL_SHOW_DURATION);
    }

    // 隐藏频道号任务
    private final Runnable hideChannelRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelNumText != null) {
                channelNumText.setVisibility(android.view.View.GONE);
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

        // ===================== 改动1：调小缓冲，加快起播 =====================
        // 单位：毫秒
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        800,    // 最小启动缓冲（改小，更快开播）
                        2000,   // 最大缓冲时长
                        500,    // 播放前最低缓冲
                        800     // 恢复播放最低缓冲
                )
                .build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // 初始化Cookie管理
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    // 核心请求头：固定UA为ExoPlayer + 自动Referer + 自动读取Cookie
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        // 固定 UA
        headers.put("User-Agent", "ExoPlayer");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");

        // 自动拼接 Referer
        try {
            URI uri = new URI(url);
            headers.put("Referer", uri.getScheme() + "://" + uri.getHost() + "/");
        } catch (Exception ignored) {}

        // 同步系统Cookie
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }
        return headers;
    }

    public void play(String url) {
        playUrl(url);
    }

    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;

        // 重置播放器状态
        player.stop();
        player.clearMediaItems();

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
        httpFactory.setDefaultRequestProperties(getHeaders(url));
        // 关键：允许301/302跨协议重定向
        httpFactory.setAllowCrossProtocolRedirects(true);

        // 直接播放原始链接，不做额外解析
        HlsMediaSource mediaSource = new HlsMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(url));

        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                android.util.Log.e("PlayerError", "播放异常：" + error.getMessage() + " 错误码：" + error.errorCode);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    // ===================== 改动2：播放就绪时显示频道号，3秒自动隐藏 =====================
                    showChannelAndAutoHide();
                } else {
                    updateWakeLock(false);
                }
            }
        });
    }

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
        // 释放时移除延时任务，防止内存泄漏
        mHandler.removeCallbacks(hideChannelRunnable);
        updateWakeLock(false);
        if (player != null) {
            player.release();
            player = null;
        }
        instance = null;
    }
}
