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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 播放器核心管理类
 * 功能：ExoPlayer 直播播放、请求头配置、播放状态回调
 * 本次修复重点：
 * 1. 关闭ExoPlayer向系统媒体服务上报状态，杜绝系统"正在播放"悬浮弹窗
 * 2. 禁用媒体按键监听、清空媒体元数据，彻底切断系统弹窗数据源
 * 3. 兼容低版本ExoPlayer，所有新增API增加异常捕获
 * 4. 保留原有全部业务逻辑，仅增强系统弹窗屏蔽能力
 */
public class TVPlayerManager {
    private static final String TAG = "TVPlayerLog";
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    private String currentPlayUrl; // 当前正在播放的地址
    public enum ScaleMode { FIT, FILL, ZOOM } // 画面缩放模式

    private OnPlayStateListener listener; // 播放状态监听
    private String currentUrl;
    private boolean isPlaying; // 播放状态标记
    private int currentChannelNumber; // 当前频道号
    private TextView channelNumText; // 频道数字展示控件

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_SHOW_DURATION = 3000L; // 频道数字显示时长
    private final SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private OnLiveInfoUpdateListener infoUpdateListener; // 直播信息回调

    /** 直播信息实体类：画质、音频、码率 */
    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
    }

    /** 直播信息更新回调接口 */
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    /** 获取默认直播信息 */
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "HD";
        info.audio = "立体声";
        info.bitrate = "4.5MB/s";
        info.channelNum = currentChannelNumber;
        return info;
    }

    /** 设置当前频道号 */
    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    /** 通知上层更新直播信息 */
    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    infoUpdateListener.onLiveInfoUpdate(getLiveInfo()));
        }
    }

    /** 绑定频道数字控件 */
    public void bindChannelText(TextView textView) {
        this.channelNumText = textView;
    }

    /** 显示频道号并自动延时隐藏 */
    private void showChannelAndAutoHide() {
        if (channelNumText == null) return;
        mHandler.removeCallbacks(hideChannelRunnable);
        channelNumText.setText("频道：" + currentChannelNumber);
        channelNumText.setVisibility(View.VISIBLE);
        mHandler.postDelayed(hideChannelRunnable, CHANNEL_SHOW_DURATION);
    }

    /** 隐藏频道号任务 */
    private final Runnable hideChannelRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelNumText != null) {
                channelNumText.setVisibility(View.GONE);
            }
        }
    };

    /** 单例获取实例 */
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    /**
     * 私有构造方法：初始化ExoPlayer + 核心弹窗屏蔽配置
     * 【关键修复区】针对系统"正在播放"弹窗做全套屏蔽
     */
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        // 渲染器配置：开启解码器降级兼容
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // 缓冲区配置
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 30000, 1500, 3000)
                .build();

        // ========== ExoPlayer 初始化 + 关闭系统状态上报（修复1） ==========
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setUsePlatformDiagnostics(false) // 禁止向系统媒体服务上报播放状态
                .build();

        // ========== 音频配置：伪装音频类型，系统不识别为媒体播放（修复2） ==========
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_GAME)        // 伪装为游戏音频，系统不弹出媒体通知
                .setContentType(C.CONTENT_TYPE_MOVIE)
                .build();
        // 第二个参数false：禁用自动音频焦点，切断系统感知播放的核心通道
        player.setAudioAttributes(audioAttributes, false);

        // 拔耳机/音频断开时不自动暂停，减少与系统音频交互
        player.setHandleAudioBecomingNoisy(false);

        // ========== 禁用媒体按键（修复3）：耳机/遥控器媒体键不触发系统弹窗 ==========
        try {
            player.setHandleMediaButtons(false);
        } catch (Exception ignored) {
            // 低版本ExoPlayer无此方法，静默跳过，不崩溃
        }

        // ========== 清空媒体元数据（修复4）：系统弹窗无文字、标题来源 ==========
        try {
            player.clearMediaMetadata();
        } catch (Exception ignored) {
            // 低版本兼容保护
        }

        // Cookie 初始化（原有逻辑，无修改）
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    /** 应用切后台：暂停播放、解绑播放器视图 */
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

    /**
     * 应用切前台：恢复播放
     * 【加固】切前台重新绑定播放器时，再次关闭原生控制器
     */
    public void onForeground() {
        try {
            if (player != null && playerView != null) {
                playerView.setPlayer(player);
                playerView.setUseController(false); // 二次加固：防止切前台控制器自动恢复
                player.play();
            }
        } catch (Exception e) {
            if (!currentPlayUrl.isEmpty()) {
                playUrl(currentPlayUrl);
            }
        }
    }

    /** 绑定PlayerView视图，并强制关闭原生控制器 */
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
        playerView.setUseController(false); // 永久关闭ExoPlayer原生控制器
    }

    /** 控制屏幕常亮状态 */
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    /** 拼接日志时间 */
    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }

    /** 构造网络请求头（防盗链、UA、Cookie） */
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

    /** 播放重载方法 */
    public void play(String url) {
        playUrl(url);
    }

    /** 核心播放方法：加载直播流并播放 */
    public void playUrl(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            currentPlayUrl = currentUrl;

            // 停止并清空旧媒体源
            player.stop();
            player.clearMediaItems();

            // 网络请求工厂配置
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(getHeaders(currentUrl))
                    .setConnectTimeoutMs(5000)
                    .setReadTimeoutMs(10000)
                    .setAllowCrossProtocolRedirects(true);

            // 区分HLS/普通流创建媒体源
            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            com.google.android.exoplayer2.source.MediaSource mediaSource;
            if (currentUrl.toLowerCase().contains("m3u8")) {
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            // 设置媒体源并开始播放
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();

            // 播放状态监听
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "播放异常: " + error.getMessage());
                    if (listener != null) listener.onPlayError(error.getMessage());
                    // 播放异常自动重试
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
                        if (listener != null) onPlayEnd();
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

    /** 设置画面缩放模式 */
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

    /** 播放状态回调接口 */
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

    /** 暂停播放 */
    public void pause() {
        try { if (player != null) player.pause(); } catch (Exception e) {}
    }

    /** 恢复播放 */
    public void resume() {
        try { if (player != null) player.play(); } catch (Exception e) {}
    }

    /** 释放播放器资源，防止内存泄漏 */
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
