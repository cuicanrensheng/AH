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
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    public enum ScaleMode { FIT, FILL, ZOOM }
    private OnPlayStateListener listener;
    private String currentUrl = "";
    private boolean isPlaying = false;
    private int currentChannelNumber = 0;

    // 频道号控件 + 3秒自动隐藏
    private TextView channelNumText;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_SHOW_DURATION = 3000L;

    // 日志时间格式
    private final SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 外部回调接口
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

    // 绑定频道号TextView
    public void bindChannelText(TextView textView) {
        this.channelNumText = textView;
    }

    // 3秒后隐藏频道号
    private void showChannelAndAutoHide() {
        if (channelNumText == null) return;
        mHandler.removeCallbacks(hideChannelRunnable);
        channelNumText.setText("频道：" + currentChannelNumber);
        channelNumText.setVisibility(android.view.View.VISIBLE);
        mHandler.postDelayed(hideChannelRunnable, CHANNEL_SHOW_DURATION);
    }

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

        // 缓冲配置：最小缓冲1秒，起播缓冲0.8秒
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        1000,
                        2000,
                        800,
                        800
                )
                .build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // 初始化Cookie
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

    // 构造日志时间前缀
    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }

    // 请求头：固定UA + 自动Referer + 自动Cookie
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ExoPlayer");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");

        try {
            URI uri = new URI(url);
            headers.put("Referer", uri.getScheme() + "://" + uri.getHost() + "/");
        } catch (Exception e) {
            Log.e(TAG, getLogTime() + " Referer 生成异常", e);
        }

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
        // 全局捕获异常，防止APP崩溃
        try {
            if (player == null || url == null || url.trim().isEmpty()) {
                Log.e(TAG, getLogTime() + " 播放失败：URL为空 或 播放器未初始化");
                return;
            }
            currentUrl = url.trim();
            Log.i(TAG, getLogTime() + " 开始播放，地址：" + currentUrl);

            // 重置播放器
            player.stop();
            player.clearMediaItems();

            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true); // 允许301/302重定向

            // 构建HLS源
            HlsMediaSource mediaSource = new HlsMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(currentUrl));

            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();

            // 播放器状态监听（兼容低版本ExoPlayer）
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    // 兼容旧版：移除 type 与 TYPE 常量，保留完整异常信息+堆栈
                    Log.e(TAG, getLogTime() + " ========== 播放异常/解析失败 ==========");
                    Log.e(TAG, getLogTime() + " 错误信息：" + error.getMessage());
                    // 打印完整异常堆栈，定位崩溃代码
                    Log.e(TAG, getLogTime() + " 异常堆栈：", error);
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    Log.d(TAG, getLogTime() + " 播放状态码：" + state);
                    if (state == Player.STATE_READY) {
                        Log.i(TAG, getLogTime() + " 播放就绪，正常播放");
                        updateWakeLock(true);
                        notifyLiveInfoUpdate();
                        showChannelAndAutoHide();
                    } else {
                        updateWakeLock(false);
                    }
                }
            });

        } catch (Exception e) {
            // 外层全局异常：防止初始化/构建源阶段直接APP崩溃
            Log.e(TAG, getLogTime() + " ========== 播放器全局崩溃异常 ==========", e);
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
            Log.e(TAG, getLogTime() + " 画面缩放设置异常", e);
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
        try {
            if (player != null) {
                player.pause();
                updateWakeLock(false);
            }
        } catch (Exception e) {
            Log.e(TAG, getLogTime() + " 暂停异常", e);
        }
    }

    public void resume() {
        try {
            if (player != null) {
                player.play();
                updateWakeLock(true);
            }
        } catch (Exception e) {
            Log.e(TAG, getLogTime() + " 恢复播放异常", e);
        }
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
            Log.i(TAG, getLogTime() + " 播放器已释放");
        } catch (Exception e) {
            Log.e(TAG, getLogTime() + " 释放播放器异常", e);
        }
    }
}
