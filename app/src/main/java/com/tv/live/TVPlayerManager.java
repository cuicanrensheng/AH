package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.TextView;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
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
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    private String currentPlayUrl = "";

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

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 30000, 1500, 3000)
                .build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // ========== 核心屏蔽系统"正在播放"弹窗（兼容低版本ExoPlayer）==========
        // 1. 配置音频属性，关闭自动音频焦点管理（切断系统识别播放的核心路径）
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_GAME)
                .setContentType(C.CONTENT_TYPE_MOVIE)
                .build();
        // 第二个参数false：不自动申请/释放音频焦点，系统无法通过音频焦点感知播放状态
        player.setAudioAttributes(audioAttributes, false);

        // 2. 禁用音频嘈杂自动暂停，减少与系统音频服务交互
        player.setHandleAudioBecomingNoisy(false);

        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // 切后台：暂停播放 + 解绑View
    public void onBackground() {
        try {
            if (player != null) {
                player.pause();
            }
            if (playerView != null) {
                playerView.setPlayer(null);
            }
        } catch (Exception e) {}
    }

    // 切前台：重新绑定 + 恢复播放
    public void onForeground() {
        try {
            if (player != null && playerView != null) {
                playerView.setPlayer(player);
                player.play();
            }
        } catch (Exception e) {
            if (!currentPlayUrl.isEmpty()) {
                playUrl(currentPlayUrl);
            }
        }
    }

    // 绑定View时 强制关闭控制器
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
        playerView.setUseController(false);
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

    // 请求头保持 ExoPlayer
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

    public void playUrl(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            currentPlayUrl = currentUrl;

            player.stop();
            player.clearMediaItems();

            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(getHeaders(currentUrl))
                    .setConnectTimeoutMs(5000)
                    .setReadTimeoutMs(10000)
                    .setAllowCrossProtocolRedirects(true);

            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            com.google.android.exoplayer2.source.MediaSource mediaSource;

            if (currentUrl.toLowerCase().contains("m3u8")) {
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
                        if (listener != null) listener.onPlayReady();
                    } else if (state == Player.STATE_BUFFERING) {
                        if (listener != null) listener.onBuffering();
                    } else if (state == Player.STATE_ENDED) {
                        if (listener != null) listener.onPlayEnd();
                    } else if (state == Player.STATE_IDLE) {
                        if (listener != null) listener.onIdle();
                    } else {
                        updateWakeLock(false);
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "全局异常", e);
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
        } catch (Exception e) {}
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

    public void release() {
        try {
            mHandler.removeCallbacks(hideChannelRunnable);
            updateWakeLock(false);
            if (player != null) {
                player.release();
                player = null;
            }
            instance = null;
        } catch (Exception e) {}
    }
}
