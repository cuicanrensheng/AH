package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ================================================
 * 播放器管理类（黑屏修复版）
 * 修复点：
 * 1. 开启硬件加速，优化解码器优先级，解决渲染黑屏
 * 2. 调小初始缓冲阈值，加快首帧出图速度
 * 3. 优化前后台切换逻辑，修复切回黑屏
 * 4. 增加首帧渲染监听，便于定位问题
 * ================================================
 */
public class TVPlayerManager {
    private static final String TAG = "TVPlayerLog";
    private static TVPlayerManager instance;
    private ExoPlayer player;                  // ExoPlayer核心实例
    private Context context;                   // 应用上下文
    private PlayerView playerView;             // 播放渲染视图
    private String currentPlayUrl = "";        // 当前播放地址（用于失败重试）

    /**
     * 画面缩放模式枚举
     * FIT: 等比例适配，保留黑边
     * FILL: 拉伸填充全屏
     * ZOOM: 等比例裁剪铺满
     */
    public enum ScaleMode { FIT, FILL, ZOOM }

    private OnPlayStateListener listener;              // 播放状态回调
    private String currentUrl = "";                    // 当前URL副本
    private boolean isPlaying = false;                 // 播放状态标记
    private int currentChannelNumber = 0;              // 当前频道号

    private TextView channelNumText;                   // 频道号显示控件
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_SHOW_DURATION = 3000L; // 频道号显示时长
    private final SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private OnLiveInfoUpdateListener infoUpdateListener; // 直播信息更新回调

    /**
     * 直播流信息结构体
     */
    public static class LiveInfo {
        public String quality;    // 画质
        public String audio;      // 音频格式
        public String bitrate;    // 码率
        public int channelNum;    // 频道号
    }

    /**
     * 直播信息更新接口
     */
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    /**
     * 设置直播信息更新监听器
     */
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    /**
     * 获取当前直播流信息
     */
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "HD";
        info.audio = "立体声";
        info.bitrate = "4.5MB/s";
        info.channelNum = currentChannelNumber;
        return info;
    }

    /**
     * 设置当前频道号
     */
    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    /**
     * 通知UI更新直播信息
     */
    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    infoUpdateListener.onLiveInfoUpdate(getLiveInfo()));
        }
    }

    /**
     * 绑定频道号显示控件
     */
    public void bindChannelText(TextView textView) {
        this.channelNumText = textView;
    }

    /**
     * 显示频道号并自动隐藏
     */
    private void showChannelAndAutoHide() {
        if (channelNumText == null) return;
        mHandler.removeCallbacks(hideChannelRunnable);
        channelNumText.setText("频道：" + currentChannelNumber);
        channelNumText.setVisibility(View.VISIBLE);
        mHandler.postDelayed(hideChannelRunnable, CHANNEL_SHOW_DURATION);
    }

    /**
     * 隐藏频道号任务
     */
    private final Runnable hideChannelRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelNumText != null) {
                channelNumText.setVisibility(View.GONE);
            }
        }
    };

    /**
     * 获取播放器单例
     */
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    /**
     * ================================================
     * 构造函数：初始化播放器核心配置（黑屏修复重点）
     * ================================================
     */
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        // 1. 渲染器工厂：开启硬件加速，优先硬解，失败自动降级软解
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);
        renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);

        // 2. 缓冲控制：减小初始缓冲，加快首帧出图，解决长时间黑屏
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 30000, 500, 1000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        // 3. 创建ExoPlayer实例
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // 4. Cookie管理器初始化（用于防盗链）
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);

        MainActivity.log(getLogTime() + " 播放器初始化完成（黑屏优化版）");
    }

    /**
     * 切换到后台：暂停播放 + 解绑渲染视图
     */
    public void onBackground() {
        try {
            if (player != null) {
                player.pause();
            }
            if (playerView != null) {
                playerView.setPlayer(null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        MainActivity.log(getLogTime() + " 切换到后台");
    }

    /**
     * ================================================
     * 切换到前台：修复切回黑屏问题
     * 修复点：先解绑再重新绑定，强制刷新视图，状态异常则重连
     * ================================================
     */
    public void onForeground() {
        try {
            if (playerView != null && player != null) {
                // 先解绑再重绑，强制刷新渲染Surface
                playerView.setPlayer(null);
                playerView.setPlayer(player);
                playerView.requestLayout();
            }

            if (player != null) {
                // 播放器空闲状态说明已释放，重新加载URL
                if (player.getPlaybackState() == Player.STATE_IDLE && !currentPlayUrl.isEmpty()) {
                    playUrl(currentPlayUrl);
                } else {
                    player.play();
                }
            }
        } catch (Exception e) {
            // 恢复失败则完整重连
            e.printStackTrace();
            MainActivity.log(getLogTime() + " 前台恢复异常，尝试重连");
            if (!currentPlayUrl.isEmpty()) {
                playUrl(currentPlayUrl);
            }
        }
        MainActivity.log(getLogTime() + " 切换到前台");
    }

    /**
     * 绑定播放视图，强制关闭原生控制器
     */
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
        playerView.setUseController(false);
    }

    /**
     * 更新屏幕常亮状态
     */
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    /**
     * 获取日志时间戳
     */
    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }

    /**
     * 构建请求头（防盗链）
     */
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

    /**
     * 兼容播放方法
     */
    public void play(String url) {
        playUrl(url);
    }

    /**
     * ================================================
     * 核心播放方法（增加首帧监听）
     * ================================================
     */
    public void playUrl(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            currentPlayUrl = currentUrl;

            String shortUrl = currentUrl.length() > 50 ? currentUrl.substring(0, 50) + "..." : currentUrl;
            MainActivity.log(getLogTime() + " 开始播放：" + shortUrl);

            // 停止上一路播放，清空资源
            player.stop();
            player.clearMediaItems();

            // HTTP数据源工厂：配置超时、防盗链、跨协议重定向
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(getHeaders(currentUrl))
                    .setConnectTimeoutMs(5000)
                    .setReadTimeoutMs(10000)
                    .setAllowCrossProtocolRedirects(true);

            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            com.google.android.exoplayer2.source.MediaSource mediaSource;

            // 自动区分HLS流和普通流
            if (currentUrl.toLowerCase().contains("m3u8")) {
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();

            // 播放状态监听
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "播放异常: " + error.getMessage());
                    MainActivity.log(getLogTime() + " ❌ 播放错误：" + error.getMessage());
                    if (listener != null) listener.onPlayError(error.getMessage());

                    // 播放失败1秒后自动重试
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        playUrl(currentUrl);
                    }, 1000);
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        // 播放就绪：亮屏、更新信息、显示频道号
                        updateWakeLock(true);
                        notifyLiveInfoUpdate();
                        showChannelAndAutoHide();
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

                /**
                 * 首帧渲染回调：画面真正显示出来时触发（黑屏排查关键）
                 */
                @Override
                public void onRenderedFirstFrame() {
                    MainActivity.log(getLogTime() + " 🎬 首帧渲染完成，画面已显示");
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "全局异常", e);
            MainActivity.log(getLogTime() + " ❌ 异常：" + e.getMessage());
        }
    }

    /**
     * 设置画面缩放模式
     */
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

    /**
     * 播放状态回调接口
     */
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    /**
     * 设置播放状态监听器
     */
    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    /**
     * 暂停播放
     */
    public void pause() {
        try { if (player != null) player.pause(); } catch (Exception e) {}
    }

    /**
     * 恢复播放
     */
    public void resume() {
        try { if (player != null) player.play(); } catch (Exception e) {}
    }

    /**
     * 释放播放器资源
     */
    public void release() {
        try {
            mHandler.removeCallbacks(hideChannelRunnable);
            updateWakeLock(false);
            if (player != null) {
                player.release();
                player = null;
            }
            instance = null;
            MainActivity.log(getLogTime() + " 播放器释放");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
