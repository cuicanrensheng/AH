package com.tv.live;

import com.tv.live.RedirectLoggingHttpDataSource;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.FrameLayout;
import android.widget.TextView;

// ====================================================================
// ✅ Media3 迁移：所有 import 从 com.google.android.exoplayer2 改成 androidx.media3
// ====================================================================
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 播放器管理类（单例模式）
 * 基于ExoPlayer封装，提供直播播放、状态监听、画质切换、Header设置等功能
 *
 * 【防卡优化版 + 切台优化版 + 真实数据版 + 失效提示版】
 * 1. 增大缓冲（从15秒→50秒），抗网络波动
 * 2. 检测播放卡住，自动重新加载
 * 3. 换回 DefaultHttpDataSource，稳定可靠
 * 4. 支持硬解码/软解码切换
 * 5. 切台保持最后一帧，避免黑屏
 * 6. 优化缓冲参数，更快出画
 * 7. 显示真实画质、音频、码率
 * 8. ✅ 2026-06-24 新增：播放界面直接显示失效提示
 *    - 播放失败时，在 PlayerView 中央显示"播放失败，请切换频道"
 *    - 播放成功时，自动隐藏错误提示
 *    - 不用 Toast，界面上直接显示更直观
 *    - 动态添加 TextView，不需要修改布局文件
 *
 * 【2026-06-23 Media3 迁移说明】
 * 从 ExoPlayer 2.x 升级到 Media3 1.10.1：
 * - 包名从 com.google.android.exoplayer2 改为 androidx.media3
 * - 公共API（Player, Format, MediaItem等）移到 common 包
 * - ExoPlayer 实现移到 exoplayer 包
 * - UI 组件移到 ui 包
 */
public class TVPlayerManager {
    private static final String TAG = "TVPlayerLog";
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;

    // 屏幕缩放模式枚举
    public enum ScaleMode { FIT, FILL, ZOOM }

    // 播放状态监听器
    private OnPlayStateListener listener;

    // 当前播放地址
    private String currentUrl = "";

    // 是否正在播放
    private boolean isPlaying = false;

    // 当前频道号
    private int currentChannelNumber = 0;

    // 频道号显示TextView
    private TextView channelNumText;

    // 主线程Handler，用于UI操作
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // 频道号显示时长（3秒）
    private static final long CHANNEL_SHOW_DURATION = 3000L;

    // 日志时间格式化
    private final SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 直播信息更新监听器
    private OnLiveInfoUpdateListener infoUpdateListener;

    // 播放状态监听器（成员变量，只添加一次）
    private Player.Listener playerListener;

    // ================================================
    // ✅ 防卡优化相关成员变量
    // ================================================
    // 是否使用软解码（默认硬解码，硬解码有问题再切软解码）
    private boolean useSoftwareDecoder = false;

    // 卡住检测：记录上次播放位置的时间
    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;

    // 卡住检测超时时间（5秒没动就算卡住了）
    private static final long STUCK_TIMEOUT = 5000;

    // 自动重试次数限制（防止无限重试）
    private int retryCount = 0;

    // ====================================================================
    // ✅ 2026-06-24 修改：重试次数改成 0，播放失败直接判定为失效
    // ====================================================================
    // 【为什么改成 0？】
    // 用户要求：直播源失效直接显示"播放失败，请切换频道"，
    // 不需要自动重试。播放失败一次就判定为失效，直接显示提示。
    //
    // 【原来的逻辑】
    // 自动重试 3 次，都失败才判定为失效。
    // 这样网络波动导致的临时失败不会误判，但用户等待时间长。
    //
    // 【现在的逻辑】
    // 播放失败直接判定为失效，立即在播放界面显示提示。
    // 响应更快，用户体验更直接。
    private static final int MAX_RETRY_COUNT = 0;

    // 卡住检测的Handler
    private final Handler stuckHandler = new Handler(Looper.getMainLooper());

    // 是否正在重试中
    private boolean isRetrying = false;

    // ====================================================================
    // ✅ 2026-06-24 新增：是否已经触发了频道失效提示
    // ====================================================================
    /**
     * 标记当前频道是否已经触发过失效提示
     * 防止同一个频道多次失败时重复触发回调
     * 
     * 【为什么需要这个标记？】
     * 虽然 MAX_RETRY_COUNT = 0，第一次失败就会判定为失效，
     * 但播放过程中可能会多次触发 onPlayerError（比如网络反复波动），
     * 用这个标记确保只触发一次失效回调。
     * 
     * 【什么时候重置？】
     * 切换频道时（playUrl 方法中）会重置这个标记，
     * 确保每个频道独立判断是否失效。
     */
    private boolean hasReportedInvalid = false;

    // ====================================================================
    // ✅ 2026-06-24 新增：错误提示 TextView
    // ====================================================================
    /**
     * 播放界面上显示的错误提示文字
     * 动态添加到 PlayerView 中，不需要修改布局文件
     * 
     * 【为什么不用 PlayerView.setErrorMessage？】
     * 因为 Media3 的 PlayerView 没有这个方法，编译会报错。
     * 所以我们自己动态创建一个 TextView，添加到 PlayerView 上。
     * 
     * 【显示位置】
     * 显示在 PlayerView 的正中央，和系统默认的错误提示位置一样。
     * 
     * 【样式】
     * - 白色文字
     * - 加粗
     * - 16sp 字号
     * - 半透明黑色背景（可选，这里先不加背景，只显示文字）
     */
    private TextView errorMessageView;

    /**
     * 直播信息实体类
     * 所有数据都从播放器实时获取，不再写死
     */
    public static class LiveInfo {
        public String quality;      // 画质（HD/FHD/SD）
        public String audio;        // 音频信息
        public String bitrate;      // 码率
        public int channelNum;      // 频道号
        public int videoWidth;      // 视频宽度（真实分辨率）
        public int videoHeight;     // 视频高度（真实分辨率）
    }

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.channelNum = currentChannelNumber;
        
        // ====================================================================
        // ✅ 从播放器获取真实的视频/音频信息
        // ====================================================================
        try {
            if (player != null) {
                // ========================================
                // 1. 画质（根据真实分辨率判断）
                // ========================================
                Format videoFormat = player.getVideoFormat();
                if (videoFormat != null && videoFormat.width != Format.NO_VALUE) {
                    info.videoWidth = videoFormat.width;
                    info.videoHeight = videoFormat.height;
                    
                    // 根据分辨率判断画质等级
                    if (videoFormat.width >= 1920 || videoFormat.height >= 1080) {
                        info.quality = "FHD";  // 全高清
                    } else if (videoFormat.width >= 1280 || videoFormat.height >= 720) {
                        info.quality = "HD";   // 高清
                    } else {
                        info.quality = "SD";   // 标清
                    }
                    
                    // ========================================
                    // 2. 码率（从视频格式获取，单位 MB/s）
                    // ========================================
                    if (videoFormat.bitrate != Format.NO_VALUE && videoFormat.bitrate > 0) {
                        // bitrate 是比特每秒(bps)，转换成兆字节每秒(MB/s)
                        double bitrateMBs = videoFormat.bitrate / 8.0 / 1024.0 / 1024.0;
                        info.bitrate = String.format("%.1fMB/s", bitrateMBs);
                    } else {
                        info.bitrate = "—";
                    }
                } else {
                    info.quality = "—";
                    info.bitrate = "—";
                    info.videoWidth = 0;
                    info.videoHeight = 0;
                }
                
                // ========================================
                // 3. 音频（根据真实声道数判断）
                // ========================================
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    int channels = audioFormat.channelCount;
                    if (channels == 1) {
                        info.audio = "单声道";
                    } else if (channels == 2) {
                        info.audio = "立体声";
                    } else if (channels >= 6) {
                        info.audio = "5.1";  // 6声道及以上显示 5.1
                    } else {
                        info.audio = channels + "声道";
                    }
                } else {
                    info.audio = "—";
                }
            } else {
                info.quality = "—";
                info.audio = "—";
                info.bitrate = "—";
            }
        } catch (Exception e) {
            Log.e(TAG, "获取播放信息失败", e);
            info.quality = "—";
            info.audio = "—";
            info.bitrate = "—";
        }
        
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
        initPlayer();
    }

    /**
     * ✅ 初始化播放器
     * 单独抽出来，方便重试时重新创建
     */
    private void initPlayer() {
        // 初始化渲染器工厂
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);

        if (useSoftwareDecoder) {
            // 软解码模式：只使用软件解码器
            renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
            // 设置优先使用软件解码器
            try {
                renderersFactory.setEnableDecoderFallback(true);
            } catch (Exception e) {
                Log.e(TAG, "设置软解码失败", e);
            }
        } else {
            // 硬解码模式：启用解码器降级
            renderersFactory.setEnableDecoderFallback(true);
        }

        // ================================================
        // ✅ 优化1：缓冲配置（快速出画 + 大缓冲防卡）
        // ================================================
        /**
         * 【参数说明】
         *
         * minBufferMs：最小缓冲，低于这个值就继续加载
         * maxBufferMs：最大缓冲，超过这个值就停止加载
         * bufferForPlaybackMs：开始播放所需的最小缓冲量
         * bufferForPlaybackAfterRebufferMs：重缓冲后开始播放所需的最小缓冲量
         *
         * 【优化思路】
         * - 把 bufferForPlaybackMs 从 1000ms 改成 300ms
         *   意思是：只要有 300ms 的数据，就开始播放
         *   这样首帧出来得更快，用户等待时间更短
         *
         * - maxBufferMs 保持 50000ms（50秒）
         *   大缓冲可以抵抗网络波动，防止卡顿
         *
         * - 这是"快速出画 + 稳定播放"的平衡方案
         *
         * 【注意】
         * minBufferMs 必须 >= bufferForPlaybackAfterRebufferMs
         * 否则 ExoPlayer 会崩溃
         */
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        2000,      // minBufferMs - 最小缓冲 2秒
                        50000,     // maxBufferMs - 最大缓冲 50秒（抗网络波动）
                        300,       // bufferForPlaybackMs - 有 300ms 就开始播（快速出画）
                        500        // bufferForPlaybackAfterRebufferMs - 重缓冲后 500ms 就播
                )
                .setPrioritizeTimeOverSizeThresholds(true) // 优先保证时间缓冲
                .build();

        // 创建ExoPlayer实例
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // 初始化播放监听器
        initPlayerListener();

        // 初始化Cookie管理器
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // ====================================================================
    // ✅ 2026-06-24 新增：初始化错误提示 TextView
    // ====================================================================
    /**
     * 创建错误提示 TextView，并添加到 PlayerView 中
     * 
     * 【为什么要动态创建？】
     * 1. 不需要修改布局文件，侵入性小
     * 2. 可以统一管理，不需要每个 Activity 都加一遍
     * 3. PlayerView 继承自 FrameLayout，可以直接 addView
     * 
     * 【显示样式】
     * - 白色文字
     * - 加粗
     * - 16sp 字号
     * - 居中显示
     * - 默认隐藏
     */
    private void initErrorView() {
        if (playerView == null) return;
        if (errorMessageView != null) return; // 已经初始化过了

        try {
            errorMessageView = new TextView(context);
            errorMessageView.setText("播放失败，请切换频道");
            errorMessageView.setTextColor(Color.WHITE);
            errorMessageView.setTextSize(16); // 16sp
            errorMessageView.setTypeface(Typeface.DEFAULT_BOLD); // 加粗
            errorMessageView.setGravity(Gravity.CENTER);
            errorMessageView.setVisibility(View.GONE); // 默认隐藏

            // 设置布局参数：居中显示，宽度和高度都是包裹内容
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
            );

            // 添加到 PlayerView 中
            playerView.addView(errorMessageView, params);

            Log.d(TAG, "【错误提示】已初始化错误提示 TextView");

        } catch (Exception e) {
            Log.e(TAG, "初始化错误提示失败", e);
            errorMessageView = null;
        }
    }

    /**
     * ✅ 显示错误提示
     * @param message 错误提示文字
     */
    private void showErrorMessage(String message) {
        if (errorMessageView == null) {
            initErrorView(); // 还没初始化，先初始化
        }

        if (errorMessageView != null) {
            try {
                errorMessageView.setText(message);
                errorMessageView.setVisibility(View.VISIBLE);
                Log.d(TAG, "【错误提示】显示：" + message);
            } catch (Exception e) {
                Log.e(TAG, "显示错误提示失败", e);
            }
        }
    }

    /**
     * ✅ 隐藏错误提示
     */
    private void hideErrorMessage() {
        if (errorMessageView != null) {
            try {
                errorMessageView.setVisibility(View.GONE);
                Log.d(TAG, "【错误提示】隐藏");
            } catch (Exception e) {
                Log.e(TAG, "隐藏错误提示失败", e);
            }
        }
    }

    /**
     * ✅ 初始化播放状态监听器
     */
    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());

                // ====================================================================
                // ✅ 2026-06-24 修改：播放界面直接显示错误提示
                // ====================================================================
                // 【为什么不用 PlayerView.setErrorMessage？】
                // 因为 Media3 的 PlayerView 没有这个方法，编译会报错。
                // 所以我们自己动态创建了一个 TextView，添加到 PlayerView 上。
                //
                // 【显示位置】
                // 显示在 PlayerView 的正中央。
                //
                // 【显示文字】
                // "播放失败，请切换频道"
                // 既说明了问题（播放失败），又告诉用户怎么做（请切换频道）。
                showErrorMessage("播放失败，请切换频道");

                if (listener != null) {
                    listener.onPlayError(error.getMessage());
                }

                // ✅ 播放错误时自动重试
                autoRetry("播放错误");
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();

                    // ====================================================================
                    // ✅ 2026-06-24 新增：播放成功，隐藏错误提示
                    // ====================================================================
                    // 播放成功了，把错误提示隐藏掉。
                    // 这样切到有效频道时，错误提示会自动消失。
                    //
                    // 【为什么要在这里隐藏？】
                    // STATE_READY 表示播放器已经准备好，可以开始播放了。
                    // 这时候说明频道是有效的，应该把错误提示隐藏掉。
                    hideErrorMessage();

                    if (listener != null) listener.onPlayReady();

                    // 播放就绪，重置重试计数
                    retryCount = 0;
                    isRetrying = false;

                    // ====================================================================
                    // ✅ 2026-06-24 新增：播放成功，重置失效提示标记
                    // ====================================================================
                    // 播放成功了，说明频道是有效的，
                    // 下次再失败时可以重新触发失效提示。
                    hasReportedInvalid = false;

                    // 开始卡住检测
                    startStuckDetection();

                } else if (state == Player.STATE_BUFFERING) {
                    if (listener != null) listener.onBuffering();

                    // 缓冲中也重置卡住检测
                    lastPositionUpdateTime = System.currentTimeMillis();

                } else if (state == Player.STATE_ENDED) {
                    if (listener != null) listener.onPlayEnd();

                    // ✅ 直播流意外结束，自动重试
                    autoRetry("播放结束");

                } else if (state == Player.STATE_IDLE) {
                    if (listener != null) listener.onIdle();
                } else {
                    updateWakeLock(false);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                // 播放状态变化时更新卡住检测
                if (isPlaying) {
                    lastPositionUpdateTime = System.currentTimeMillis();
                }
            }

            // ====================================================================
            // ✅ 视频分辨率变化时触发（新版本 ExoPlayer 签名）
            // ====================================================================
            /**
             * 为什么需要这个？
             * 有些直播流刚开始时分辨率还没确定，
             * 等视频解码器初始化完成后，才会回调真实的分辨率。
             * 这时候我们需要更新一下信息栏的画质标签。
             */
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                int width = videoSize.width;
                int height = videoSize.height;
                Log.d(TAG, "视频分辨率变化：" + width + "×" + height);

                // 分辨率变化时，通知 UI 更新
                notifyLiveInfoUpdate();
            }
        };

        player.addListener(playerListener);
    }

    // ================================================
    // ✅ 优化2：卡住检测 + 自动重试
    // ================================================
    /**
     * 开始卡住检测
     * 每隔2秒检查一次播放位置，如果长时间没动，说明卡住了
     */
    private void startStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        stuckHandler.postDelayed(stuckCheckRunnable, 2000);
    }

    /**
     * 停止卡住检测
     */
    private void stopStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
    }

    /**
     * 卡住检测Runnable
     */
    private final Runnable stuckCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (player == null || !player.isPlaying()) {
                // 没在播放，不检测
                stuckHandler.postDelayed(this, 2000);
                return;
            }

            try {
                long currentPosition = player.getCurrentPosition();
                long now = System.currentTimeMillis();

                if (currentPosition != lastPosition) {
                    // 播放位置在动，正常
                    lastPosition = currentPosition;
                    lastPositionUpdateTime = now;
                } else {
                    // 播放位置没动，检查超时
                    if (now - lastPositionUpdateTime > STUCK_TIMEOUT) {
                        // 卡住了，自动重试
                        Log.w(TAG, "检测到播放卡住，自动重试...");
                        autoRetry("播放卡住");
                        return; // 重试后不再继续检测
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "卡住检测异常", e);
            }

            // 继续下一次检测
            stuckHandler.postDelayed(this, 2000);
        }
    };

    /**
     * ✅ 自动重试
     * @param reason 重试原因（用于日志）
     * 
     * 【2026-06-24 修改：增加频道失效回调】
     * 【修改说明】
     * 当重试次数达到上限后，判定为"频道失效"，
     * 通过 onChannelInvalid() 回调通知外部。
     * 
     * 【现在的重试次数是 0】
     * 所以第一次失败就会达到上限，直接判定为失效，
     * 立即触发 onChannelInvalid 回调。
     * 
     * 【为什么需要 hasReportedInvalid 标记？】
     * 防止同一个频道多次失败时重复触发回调。
     * 虽然 MAX_RETRY_COUNT = 0，但播放过程中可能会多次触发错误，
     * 用这个标记确保只触发一次失效回调。
     */
    private void autoRetry(String reason) {
        if (isRetrying) return; // 已经在重试中，避免重复

        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限：" + MAX_RETRY_COUNT + "，判定为频道失效");

            // ====================================================================
            // ✅ 2026-06-24 新增：频道失效回调
            // ====================================================================
            // 重试次数达到上限，说明这个直播源真的有问题，
            // 通知外部（Listener）频道失效了。
            // 
            // 【hasReportedInvalid 的作用】
            // 防止同一个频道多次失败时重复触发回调。
            // 只有第一次达到上限时才触发一次。
            // 
            // 【注意】
            // 错误提示已经直接显示在 PlayerView 上了，
            // 这个回调主要是给外部做其他处理用的（比如统计、上报等）。
            if (!hasReportedInvalid && listener != null) {
                hasReportedInvalid = true;
                listener.onChannelInvalid("播放失败，请切换频道");
                Log.d(TAG, "【失效提示】已触发频道失效回调");
            }

            isRetrying = false;
            return;
        }

        isRetrying = true;
        retryCount++;
        Log.w(TAG, "自动重试（第" + retryCount + "次），原因：" + reason);

        // 延迟1秒后重新加载
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!TextUtils.isEmpty(currentUrl)) {
                    // 重新播放当前地址
                    playUrlInternal(currentUrl);
                }
            }
        }, 1000);
    }

    /**
     * 切换软解码/硬解码
     * @param useSoftware true=软解码，false=硬解码
     */
    public void setSoftwareDecoder(boolean useSoftware) {
        if (useSoftwareDecoder == useSoftware) return;

        useSoftwareDecoder = useSoftware;
        Log.d(TAG, "切换解码器：" + (useSoftware ? "软解码" : "硬解码"));

        // 重新创建播放器
        if (player != null) {
            try {
                stopStuckDetection();
                if (playerListener != null) {
                    player.removeListener(playerListener);
                }
                player.release();
                player = null;
            } catch (Exception e) {
                Log.e(TAG, "释放播放器异常", e);
            }
        }

        initPlayer();

        if (playerView != null) {
            playerView.setPlayer(player);
        }

        // 重新播放当前地址
        if (!TextUtils.isEmpty(currentUrl)) {
            retryCount = 0;
            isRetrying = false;
            playUrlInternal(currentUrl);
        }
    }

    public void onForeground() {
        try {
            if (player != null && playerView != null) {
                playerView.setPlayer(player);
                player.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "切前台异常", e);
        }
    }

    public void onBackground() {
        try {
            if (player != null) {
                player.pause();
            }
        } catch (Exception e) {
            Log.e(TAG, "切后台异常", e);
        }
    }

    /**
     * 绑定播放器视图
     * 
     * 【2026-06-24 修改：绑定视图时初始化错误提示】
     * 【修改说明】
     * 绑定 PlayerView 时，同时初始化错误提示 TextView，
     * 并添加到 PlayerView 中。
     */
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
        playerView.setUseController(false);

        // ====================================================================
        // ✅ 2026-06-24 新增：绑定视图时初始化错误提示
        // ====================================================================
        // 绑定 PlayerView 后，初始化错误提示 TextView，
        // 并添加到 PlayerView 中。
        // 这样播放失败时就能直接在界面上显示提示了。
        initErrorView();
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

        boolean isHuya = url.contains("huya.com") || url.contains("huya.cn");
        boolean isDouyu = url.contains("douyu.com") || url.contains("douyucdn.cn");

        if (isHuya) {
            headers.put("Referer", "https://www.huya.com/");
            Log.d(TAG, "虎牙直播，设置虎牙Referer");
        }
        else if (isDouyu) {
            headers.put("Referer", "https://www.douyu.com/");
            Log.d(TAG, "斗鱼直播，设置斗鱼Referer");
        }
        else {
            headers.put("Referer", "https://www.huya.com/");
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

    /**
     * 播放指定URL（对外接口）
     * 切换频道时调用，重置重试计数
     * 
     * 【2026-06-24 修改：切台时重置失效提示标记】
     * 【修改说明】
     * 切换到新频道时，重置 hasReportedInvalid 标记，
     * 确保每个频道独立判断是否失效。
     * 
     * 【为什么需要重置？】
     * 如果上一个频道失效了，已经触发过失效回调了，
     * 切换到新频道后，新频道也可能失效，需要重新判断。
     * 所以每次切台都要重置这个标记。
     */
    public void playUrl(String url) {
        // 切换频道，重置重试计数
        retryCount = 0;
        isRetrying = false;

        // ====================================================================
        // ✅ 2026-06-24 新增：切台时重置失效提示标记
        // ====================================================================
        // 新频道，重新开始判断是否失效
        hasReportedInvalid = false;
        Log.d(TAG, "【切台】切换到新频道，重置重试计数和失效标记");

        playUrlInternal(url);
    }

    /**
     * ✅ 内部播放方法
     *
     * 【优化3】换回 DefaultHttpDataSource
     * 自定义的 RedirectLoggingHttpDataSource 可能有bug，先换回官方的稳定版
     * 如果需要看重定向日志，可以再切回去
     *
     * 【优化4】切台保持最后一帧
     * 去掉 player.stop() 和 player.clearMediaItems()
     * 直接用 setMediaSource 切换，旧画面会保留到新画面出来
     * 这样就完全避免了切台黑屏的问题
     */
    private void playUrlInternal(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;

            currentUrl = url.trim();
            Log.d(TAG, "开始播放：" + currentUrl);

            // ====================================================================
            // ✅ 关键修改：去掉 player.stop() 和 player.clearMediaItems()
            // ====================================================================
            /**
             * 【为什么去掉 stop() 就能保持最后一帧？】
             *
             * 调用 player.stop() 会立刻清空渲染器的画面，导致黑屏。
             * 直接调用 setMediaSource() + prepare()，旧画面会保留到新画面渲染出来。
             *
             * 用户看到的效果：旧画面静止不动 → 新画面突然出现
             * 而不是：黑屏 → 新画面出现
             *
             * 这样就完全避免了切台黑屏的问题。
             *
             * 【为什么去掉 clearMediaItems()？】
             * setMediaSource(mediaSource, true) 会自动替换所有媒体源，
             * 不需要先 clear 再 set。
             *
             * 【第二个参数 true 是什么意思？】
             * setMediaSource(mediaSource, resetPosition = true)
             * true = 重置播放位置到开头（直播流必须用 true）
             * false = 保持当前播放位置（点播连播时用 false）
             */
            // player.stop();          // ✅ 注释掉，保持最后一帧
            // player.clearMediaItems(); // ✅ 注释掉，保持最后一帧

            // ===== 创建数据源（带重定向日志版） =====
            // 每一重定向都会打印详细日志，方便调试直播源
            RedirectLoggingHttpDataSource.Factory httpFactory =
                    new RedirectLoggingHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true);

            MediaItem mediaItem = MediaItem.fromUri(currentUrl);

            // ====================================================================
            // ✅ Media3 迁移：MediaSource 从 exoplayer.source 包改为 androidx.media3.exoplayer.source
            // ====================================================================
            MediaSource mediaSource;
            if (currentUrl.toLowerCase().contains("m3u8")) {
                Log.d(TAG, "流格式：HLS (m3u8)");
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                Log.d(TAG, "流格式：普通流 (Progressive)");
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            // ====================================================================
            // ✅ 关键修改：直接设置新的媒体源，第二个参数 true = 重置到开头
            // ====================================================================
            player.setMediaSource(mediaSource, true);
            player.prepare();
            player.play();

            // 开始卡住检测
            startStuckDetection();

        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            autoRetry("播放异常：" + e.getMessage());
        }
    }

    public void setScaleMode(ScaleMode mode) {
        try {
            if (playerView == null) return;

            // ====================================================================
            // ✅ Media3 迁移：AspectRatioFrameLayout 从 exoplayer2.ui 改为 media3.ui
            // ====================================================================
            switch (mode) {
                case FIT:
                    playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    break;
                case FILL:
                    playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL);
                    break;
                case ZOOM:
                    playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "设置缩放模式异常", e);
        }
    }

    // ====================================================================
    // ✅ 播放状态监听器
    // ====================================================================
    /**
     * 【2026-06-24 新增：onChannelInvalid 回调】
     * 
     * 【回调时机】
     * 切换频道后，自动重试次数达到上限，判定为"频道失效"时触发。
     * 
     * 【现在重试次数是 0】
     * 所以播放失败一次就会触发这个回调。
     * 
     * 【注意】
     * 错误提示已经直接显示在 PlayerView 上了，
     * 这个回调主要是给外部做其他处理用的（比如统计、上报等）。
     * 外部不需要再弹 Toast，因为播放界面已经直接显示了。
     */
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);

        // ====================================================================
        // ✅ 2026-06-24 新增：频道失效回调
        // ====================================================================
        /**
         * 频道失效时触发（重试次数达到上限）
         * 
         * @param msg 失效原因描述，可直接显示给用户
         */
        void onChannelInvalid(String msg);
    }

    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    public void pause() {
        try { if (player != null) player.pause(); } catch (Exception e) {
            Log.e(TAG, "暂停异常", e);
        }
    }

    public void resume() {
        try { if (player != null) player.play(); } catch (Exception e) {
            Log.e(TAG, "恢复异常", e);
        }
    }

    public void release() {
        try {
            stopStuckDetection();
            mHandler.removeCallbacks(hideChannelRunnable);
            updateWakeLock(false);

            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                }
                player.release();
                player = null;
            }

            // ====================================================================
            // ✅ 2026-06-24 新增：释放时清理错误提示 View
            // ====================================================================
            // 避免内存泄漏
            if (errorMessageView != null && playerView != null) {
                try {
                    playerView.removeView(errorMessageView);
                } catch (Exception e) {
                    Log.e(TAG, "移除错误提示失败", e);
                }
                errorMessageView = null;
            }

            instance = null;
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }
}
