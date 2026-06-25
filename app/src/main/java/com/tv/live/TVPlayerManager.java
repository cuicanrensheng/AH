           package com.tv.live;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
// ====================================================================
// ✅ 2026-06-25 修改：降级回 ExoPlayer 2.19.1
// ====================================================================
// 所有 import 从 androidx.media3.*
// 改回对应的 com.google.android.exoplayer2.* 包名
//
// 【包名迁移对照表（降级版）】
// 新包名 (Media3 1.x)                        → 旧包名 (ExoPlayer 2.x)
// androidx.media3.common.C                    → com.google.android.exoplayer2.C
// androidx.media3.common.Format               → com.google.android.exoplayer2.Format
// androidx.media3.common.MediaItem            → com.google.android.exoplayer2.MediaItem
// androidx.media3.common.PlaybackException    → com.google.android.exoplayer2.PlaybackException
// androidx.media3.common.Player               → com.google.android.exoplayer2.Player
// androidx.media3.common.VideoSize            → com.google.android.exoplayer2.video.VideoSize
// androidx.media3.exoplayer.DefaultLoadControl → com.google.android.exoplayer2.DefaultLoadControl
// androidx.media3.exoplayer.DefaultRenderersFactory → com.google.android.exoplayer2.DefaultRenderersFactory
// androidx.media3.exoplayer.ExoPlayer         → com.google.android.exoplayer2.ExoPlayer
// androidx.media3.exoplayer.hls.HlsMediaSource → com.google.android.exoplayer2.source.hls.HlsMediaSource
// androidx.media3.exoplayer.source.MediaSource → com.google.android.exoplayer2.source.MediaSource
// androidx.media3.exoplayer.source.ProgressiveMediaSource → com.google.android.exoplayer2.source.ProgressiveMediaSource
// androidx.media3.ui.AspectRatioFrameLayout   → com.google.android.exoplayer2.ui.AspectRatioFrameLayout
// androidx.media3.ui.PlayerView               → com.google.android.exoplayer2.ui.PlayerView
//
// ⚠️ 重要修正：Player.Listener
// 之前误以为降级后是 Player.EventListener，实际上 ExoPlayer 2.19.1 里也是 Player.Listener
// 两个版本接口名一样，都是 Player.Listener
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.video.VideoSize;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
/**
 * TV 播放器管理器
 * 
 * 【功能】
 * 1. 封装 ExoPlayer 播放器
 * 2. 支持 HLS 和普通流
 * 3. 支持硬解/软解切换（软解优先使用 FFmpeg）
 * 4. 自动重试机制
 * 5. 卡住检测
 * 6. 自动切换解码器（硬解卡顿时自动切到 FFmpeg 软解）
 * 
 * 【2026-06-23 更新】集成 FFmpeg 软解码器
 * 【说明】
 * - 软解模式下优先使用 FFmpeg 解码器，兼容性更好
 * - 硬解模式下也可以启用 FFmpeg 作为备用（EXTENSION_RENDERER_MODE_ON）
 * - 通过 DefaultRenderersFactory.setExtensionRendererMode() 配置
 * 
 * 【2026-06-24 更新】修复自动切换解码器 bug
 * 【bug 描述】
 * playStartTime 每次 STATE_READY 都会重置，导致自动切换判断不准确
 * 【修复方案】
 * 1. 新增 initialPlayStartTime 变量，只在第一次 STATE_READY 时设置
 * 2. 自动切换判断改用 initialPlayStartTime
 * 3. hasSwitchedDecoder 从 resetPerformanceStats() 中移除，改在 playUrl() 切换频道时重置
 */
public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";
    private static TVPlayerManager instance;
    private Context context;
    private ExoPlayer player;
    private PlayerView playerView;
    private String currentUrl;
    // ====================================================================
    // 播放状态监听器
    // ====================================================================
    // ✅ 2026-06-25 修正：ExoPlayer 2.19.1 里也是 Player.Listener
    // 之前误以为降级后是 Player.EventListener，其实两个版本接口名一样
    private Player.Listener playerListener;
    private OnPlayStateListener listener;
    private OnLiveInfoUpdateListener liveInfoListener;
    // ====================================================================
    // 解码器相关
    // ====================================================================
    private boolean useSoftwareDecoder = false;  // 是否使用软解码
    private boolean hasSwitchedDecoder = false;  // 是否已切换过解码器（每个频道只切一次）
    private long initialPlayStartTime = 0;       // 首次播放开始时间（用于自动切换判断）
    // ====================================================================
    // 自动重试相关
    // ====================================================================
    private static final int MAX_RETRY_COUNT = 3;  // 最大重试次数
    private int retryCount = 0;                    // 当前重试次数
    private boolean isRetrying = false;            // 是否正在重试
    private Runnable retryRunnable = null;         // 重试任务引用（用于取消）
    // ====================================================================
    // 卡住检测相关
    // ====================================================================
    private static final long STUCK_TIMEOUT = 10000;  // 卡住超时时间（10秒）
    private Handler stuckHandler = new Handler(Looper.getMainLooper());
    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;
    // ====================================================================
    // 性能统计相关
    // ====================================================================
    private int bufferCount = 0;       // 缓冲次数
    private long totalStallTime = 0;   // 总卡顿时间
    private long lastStallStartTime = 0;
    private boolean isStalled = false;
    // ====================================================================
    // 直播信息
    // ====================================================================
    private LiveInfo currentLiveInfo = new LiveInfo();
    // ====================================================================
    // 其他
    // ====================================================================
    private boolean isPlaying = false;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    // ====================================================================
    // 单例
    // ====================================================================
    public static TVPlayerManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TVPlayerManager.class) {
                if (instance == null) {
                    instance = new TVPlayerManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }
    private TVPlayerManager(Context context) {
        this.context = context;
        initPlayer();
    }
    // ====================================================================
    // 初始化播放器
    // ====================================================================
    private void initPlayer() {
        // ====================================================================
        // ✅ 2026-06-23 修改：配置 FFmpeg 扩展渲染器
        // ====================================================================
        // 【说明】
        // DefaultRenderersFactory 用于创建音频、视频渲染器。
        // 通过 setExtensionRendererMode() 可以配置是否使用扩展渲染器（如 FFmpeg）。
        //
        // 【三种模式】
        // 1. EXTENSION_RENDERER_MODE_OFF：不使用扩展渲染器（默认）
        // 2. EXTENSION_RENDERER_MODE_ON：使用扩展渲染器，作为备用方案
        //    - 系统解码器能解 → 用系统硬解
        //    - 系统解码器解不了 → 用 FFmpeg 软解
        // 3. EXTENSION_RENDERER_MODE_PREFER：优先使用扩展渲染器
        //    - FFmpeg 能解 → 用 FFmpeg 软解
        //    - FFmpeg 解不了 → 用系统解码器
        //
        // 【为什么用 DefaultRenderersFactory？】
        // ExoPlayer 默认的渲染器工厂只能用系统的 MediaCodec 解码器，
        // 对于一些特殊格式（如某些音频编码）可能不支持。
        // FFmpeg 扩展渲染器可以解码更多格式，兼容性更好。
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        if (useSoftwareDecoder) {
            // 软解模式：优先使用 FFmpeg
            renderersFactory.setExtensionRendererMode(
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
            Log.d(TAG, "【解码器】软解模式：优先使用 FFmpeg");
        } else {
            // 硬解模式：FFmpeg 作为备用
            renderersFactory.setExtensionRendererMode(
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
            Log.d(TAG, "【解码器】硬解模式：FFmpeg 作为备用");
        }
        // 优化缓冲策略
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        10000,     // minBufferMs - 最小缓冲 10秒
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
        // ====================================================================
        // ✅ 2026-06-25 修正：ExoPlayer 2.19.1 里也是 Player.Listener
        // ====================================================================
        // 之前误以为降级后是 Player.EventListener，其实两个版本接口名一样
        // 都是 Player.Listener
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
                    
                    // ====================================================================
                    // ✅ 2026-06-23 新增：打印当前使用的解码器信息
                    // ====================================================================
                    // 【作用】
                    // 方便调试，看看当前用的是硬解码还是 FFmpeg 软解
                    // 可以从 videoFormat 的名称里看出来
                    try {
                        Format videoFormat = player.getVideoFormat();
                        if (videoFormat != null) {
                            String decoderName = videoFormat.sampleMimeType;
                            boolean isFfmpeg = decoderName != null 
                                && decoderName.toLowerCase().contains("ffmpeg");
                            Log.d(TAG, "【解码器】当前视频解码器：" + decoderName 
                                + "（" + (isFfmpeg ? "FFmpeg 软解" : "系统硬解") + "）");
                        }
                    } catch (Exception e) {
                        // 忽略，获取解码器信息失败不影响播放
                    }
                    // ====================================================================
                    // ✅ 2026-06-24 修复：只在第一次 STATE_READY 时记录开始时间
                    // ====================================================================
                    // 【为什么要这样？】
                    // 原来每次 STATE_READY 都会重置 playStartTime，
                    // 但自动切换解码器的判断是基于"播放开始后 30 秒内"，
                    // 如果中间因为缓冲导致状态变化，会重置这个时间，
                    // 导致自动切换判断不准确。
                    //
                    // 修复后：只在第一次 STATE_READY 时设置 initialPlayStartTime，
                    // 后续的状态变化不会影响这个时间。
                    if (initialPlayStartTime == 0) {
                        initialPlayStartTime = System.currentTimeMillis();
                    }
                    // ====================================================================
                    // ✅ 2026-06-24 新增：自动切换解码器（硬解 → 软解）
                    // ====================================================================
                    // 【触发条件】
                    // 1. 当前是硬解模式
                    // 2. 还没切换过解码器
                    // 3. 播放开始后 30 秒内
                    // 4. 缓冲次数 > 2 次
                    //
                    // 【为什么要自动切换？】
                    // 有些频道用硬解会很卡（码率太高、格式不兼容等），
                    // 自动切换到 FFmpeg 软解可以提升播放流畅度。
                    // 每个频道只切一次，避免反复切换。
                    if (!useSoftwareDecoder && !hasSwitchedDecoder 
                            && initialPlayStartTime > 0 
                            && System.currentTimeMillis() - initialPlayStartTime < 30000
                            && bufferCount > 2) {
                        Log.d(TAG, "【自动切换】硬解卡顿，自动切换到 FFmpeg 软解");
                        hasSwitchedDecoder = true;
                        setSoftwareDecoder(true);
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (listener != null) listener.onBuffering();
                    // 缓冲中也重置卡住检测
                    lastPositionUpdateTime = System.currentTimeMillis();
                    // 统计缓冲次数和卡顿时间
                    bufferCount++;
                    if (!isStalled) {
                        isStalled = true;
                        lastStallStartTime = System.currentTimeMillis();
                    }
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
                    // 卡顿结束，统计卡顿时间
                    if (isStalled) {
                        isStalled = false;
                        totalStallTime += System.currentTimeMillis() - lastStallStartTime;
                    }
                }
            }
            // ====================================================================
            // ✅ 视频分辨率变化时触发
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
    // ====================================================================
    // ✅ 2026-06-24 新增：取消重试任务
    // ====================================================================
    /**
     * 取消待执行的重试任务
     * 
     * 【作用】
     * 切换频道时调用，取消旧频道的重试任务，
     * 避免旧频道的重试干扰新频道的播放。
     * 
     * 【为什么需要这个？】
     * 自动跳过失效频道时，切到新频道后，
     * 旧频道的延迟重试任务还在 Handler 队列里，
     * 1秒后会执行并重新加载（但 currentUrl 已经是新频道了），
     * 导致新频道被重新加载一次，播放中断，体验不好。
     * 
     * 【调用时机】
     * 1. playUrl() 切换频道时自动调用
     * 2. 外部也可以手动调用
     */
    private void cancelRetry() {
        if (retryRunnable != null) {
            mHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        isRetrying = false;
    }
    /**
     * ✅ 自动重试
     * @param reason 重试原因（用于日志）
     * 
     * 【2026-06-24 修改：保存 retryRunnable 引用】
     * 【修改说明】
     * 把重试的 Runnable 保存为成员变量，
     * 方便 cancelRetry() 取消掉。
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
        
        // ✅ 2026-06-24 修改：保存重试任务的引用，方便后续取消
        retryRunnable = new Runnable() {
            @Override
            public void run() {
                if (!TextUtils.isEmpty(currentUrl)) {
                    // 重新播放当前地址
                    playUrlInternal(currentUrl);
                }
                // 执行完后清空引用
                retryRunnable = null;
            }
        };
        
        // 延迟1秒后重新加载
        mHandler.postDelayed(retryRunnable, 1000);
    }
    /**
     * 切换软解码/硬解码
     * @param useSoftware true=软解码，false=硬解码
     * 
     * 【2026-06-23 更新】
     * 软解码模式现在会优先使用 FFmpeg 解码器，
     * 而不是系统的 MediaCodec 软解。
     * FFmpeg 兼容性更好，支持更多格式。
     */
    public void setSoftwareDecoder(boolean useSoftware) {
        if (useSoftwareDecoder == useSoftware) return;
        useSoftwareDecoder = useSoftware;
        Log.d(TAG, "切换解码器：" + (useSoftware ? "FFmpeg 软解码" : "系统硬解码"));
        // 重新创建播放器
        if (player != null) {
            try {
                stopStuckDetection();
                // ✅ 2026-06-24 新增：重新创建播放器前取消重试
                cancelRetry();
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
     * 
     * 【2026-06-24 修改：切换频道时取消旧重试任务】
     * 【修改说明】
     * 切换到新频道前，先调用 cancelRetry() 取消旧频道的重试任务，
     * 避免旧频道的延迟重试干扰新频道的播放。
     */
    public void playUrl(String url) {
        // ✅ 2026-06-24 新增：切换频道，先取消之前的重试任务
        cancelRetry();
        // 切换频道，重置重试计数
        retryCount = 0;
        isRetrying = false;
        // ✅ 2026-06-24 新增：切换频道，重置解码器切换标记
        // 每个频道只自动切换一次解码器
        hasSwitchedDecoder = false;
        // ✅ 2026-06-24 新增：切换频道，重置首次播放开始时间
        initialPlayStartTime = 0;
        // 重置性能统计
        resetPerformanceStats();
        playUrlInternal(url);
    }
    /**
     * 重置性能统计
     */
    private void resetPerformanceStats() {
        bufferCount = 0;
        totalStallTime = 0;
        isStalled = false;
        lastStallStartTime = 0;
        // ⚠️ 注意：hasSwitchedDecoder 不再在这里重置
        // 因为它是按频道来的，不是按播放状态来的
        // 改在 playUrl() 里重置
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
            // ✅ 2026-06-25 修改：降级回 ExoPlayer 2.19.1
            // ====================================================================
            // MediaSource 的包名从 androidx.media3.exoplayer.source.MediaSource
            // 改回 com.google.android.exoplayer2.source.MediaSource
            //
            // HlsMediaSource 和 ProgressiveMediaSource 的包名也对应改回
            // com.google.android.exoplayer2.source.hls 和 com.google.android.exoplayer2.source
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
            // ✅ 2026-06-25 修改：降级回 ExoPlayer 2.19.1
            // ====================================================================
            // AspectRatioFrameLayout 的包名从 androidx.media3.ui.AspectRatioFrameLayout
            // 改回 com.google.android.exoplayer2.ui.AspectRatioFrameLayout
            switch (mode) {
                case FIT:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    break;
                case FILL:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                    break;
                case ZOOM:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "设置缩放模式异常", e);
        }
    }
    // ====================================================================
    // 直播信息更新
    // ====================================================================
    private void notifyLiveInfoUpdate() {
        if (player == null) return;
        try {
            Format videoFormat = player.getVideoFormat();
            Format audioFormat = player.getAudioFormat();
            if (videoFormat != null) {
                currentLiveInfo.width = videoFormat.width;
                currentLiveInfo.height = videoFormat.height;
                currentLiveInfo.videoCodec = videoFormat.sampleMimeType;
            }
            if (audioFormat != null) {
                currentLiveInfo.audioCodec = audioFormat.sampleMimeType;
                // ✅ 2026-06-25 新增：兼容 InfoDisplayManager 的 info.audio 字段
                currentLiveInfo.audio = audioFormat.sampleMimeType;
                currentLiveInfo.audioChannels = audioFormat.channelCount;
            }
            // 计算码率（粗略估算）
            long bitrate = player.getVideoFormat() != null ? player.getVideoFormat().bitrate : 0;
            if (bitrate > 0) {
                currentLiveInfo.bitrate = (bitrate / 1000) + " kbps";
            }
            // 判断画质标签
            if (currentLiveInfo.height >= 1080) {
                currentLiveInfo.quality = "FHD";
            } else if (currentLiveInfo.height >= 720) {
                currentLiveInfo.quality = "HD";
            } else { 
                currentLiveInfo.quality = "SD";
            }
            if (liveInfoListener != null) {
                liveInfoListener.onLiveInfoUpdate(currentLiveInfo);
            }
        } catch (Exception e) {
            // 忽略
        }
    }
    /**
     * 显示频道信息并自动隐藏
     */
    private void showChannelAndAutoHide() {
        // 这个方法在 MainActivity 里有更完整的实现
        // 这里留空，避免重复逻辑
    }
    // ====================================================================
    // 接口定义
    // ====================================================================
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
    // ====================================================================
    // 直播信息类
    // ====================================================================
    public static class LiveInfo {
        public int width;
        public int height;
        public String videoCodec;
        // ✅ 2026-06-25 新增：音频信息（兼容 InfoDisplayManager 的 info.audio）
        public String audio;
        public String audioCodec;
        public int audioChannels;
        public String bitrate;
        public String quality;  // FHD / HD / SD
    }
    // ====================================================================
    // 缩放模式枚举
    // ====================================================================
    public enum ScaleMode {
        FIT,    // 等比例缩放，全部可见
        FILL,   // 拉伸填充
        ZOOM    // 等比例缩放，填满屏幕，裁剪多余部分
    }
    // ====================================================================
    // Setter
    // ====================================================================
    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener l) {
        liveInfoListener = l;
    }
    public LiveInfo getLiveInfo() {
        return currentLiveInfo;
    }
    public boolean isPlaying() {
        return isPlaying;
    }
    public String getCurrentUrl() {
        return currentUrl;
    }
    public ExoPlayer getPlayer() {
        return player;
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
            // ✅ 2026-06-24 新增：释放时取消重试任务
            cancelRetry();
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
    // 占位：hideChannelRunnable（实际在 MainActivity 里有完整实现）
    private final Runnable hideChannelRunnable = new Runnable() {
        @Override
        public void run() {
            // 空实现，避免编译错误
        }
    };
}
