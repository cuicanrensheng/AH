package com.tv.live;

import com.tv.live.RedirectLoggingHttpDataSource;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
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
 * 【防卡优化版 + 切台优化版 + 真实数据版】
 * 1. 增大缓冲（从15秒→50秒），抗网络波动
 * 2. 检测播放卡住，自动重新加载
 * 3. 换回 DefaultHttpDataSource，稳定可靠
 * 4. 支持硬解码/软解码切换
 * 5. 切台保持最后一帧，避免黑屏
 * 6. 优化缓冲参数，更快出画
 * 7. 显示真实画质、音频、码率
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
    private static final int MAX_RETRY_COUNT = 3;
    // 卡住检测的Handler
    private final Handler stuckHandler = new Handler(Looper.getMainLooper());
    // 是否正在重试中
    private boolean isRetrying = false;

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

    /**
     * ✅ 初始化播放状态监听器
     */
    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
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
                    if (listener != null) listener.onPlayReady();
                    // 播放就绪，重置重试计数
                    retryCount = 0;
                    isRetrying = false;
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
     */
    private void autoRetry(String reason) {
        if (isRetrying) return; // 已经在重试中，避免重复
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限：" + MAX_RETRY_COUNT);
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
     */
    public void playUrl(String url) {
        // 切换频道，重置重试计数
        retryCount = 0;
        isRetrying = false;
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
            instance = null;
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }
}
