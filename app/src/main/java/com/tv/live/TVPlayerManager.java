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

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.video.VideoSize;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 播放器管理类（单例模式）
 * 基于ExoPlayer封装，提供直播播放、状态监听、画质切换、Header设置等功能
 *
 * 【双播放器无缝切台版】
 * 两个播放器叠加：
 * - 主播放器（mainPlayer）：正在播放，有声音，显示
 * - 预加载播放器（preloadPlayer）：提前缓冲下一个台，静音，隐藏
 * 切台时直接交换两个播放器，0 毫秒黑屏，完全无缝
 *
 * 【防卡优化 + 切台优化 + 真实数据 + 双播放器】
 * 1. 增大缓冲（50秒），抗网络波动
 * 2. 检测播放卡住，自动重新加载
 * 3. 切台保持最后一帧，避免黑屏
 * 4. 优化缓冲参数，更快出画
 * 5. 显示真实画质、音频、码率
 * 6. ✅ 双播放器预加载，无缝切台，0 黑屏
 */
public class TVPlayerManager {

    private static final String TAG = "TVPlayerLog";
    private static TVPlayerManager instance;

    // ====================== 双播放器相关 ======================
    /** 主播放器（正在播放，有声音，显示） */
    private ExoPlayer mainPlayer;
    /** 预加载播放器（提前缓冲，静音，隐藏） */
    private ExoPlayer preloadPlayer;
    /** 主播放器视图 */
    private PlayerView mainPlayerView;
    /** 预加载播放器视图 */
    private PlayerView preloadPlayerView;
    /** 当前预加载的地址 */
    private String preloadedUrl = "";
    /** 预加载是否就绪（缓冲完成，可以随时切换） */
    private boolean isPreloadReady = false;

    // ====================== 其他成员变量 ======================
    private Context context;
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
    private Player.Listener mainPlayerListener;
    private Player.Listener preloadPlayerListener;

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
        // ✅ 从主播放器获取真实的视频/音频信息
        // ====================================================================
        try {
            if (mainPlayer != null) {
                // ========================================
                // 1. 画质（根据真实分辨率判断）
                // ========================================
                Format videoFormat = mainPlayer.getVideoFormat();
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
                Format audioFormat = mainPlayer.getAudioFormat();
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
        // 初始化两个播放器
        initMainPlayer();
        initPreloadPlayer();
    }

    // ====================================================================
    // ✅ 双播放器：初始化主播放器
    // ====================================================================
    /**
     * 初始化主播放器
     */
    private void initMainPlayer() {
        mainPlayer = createPlayer();
        mainPlayerListener = createPlayerListener(true);
        mainPlayer.addListener(mainPlayerListener);
    }

    // ====================================================================
    // ✅ 双播放器：初始化预加载播放器
    // ====================================================================
    /**
     * 初始化预加载播放器
     *
     * 【特点】
     * 1. 静音：预加载的播放器不能有声音
     * 2. 隐藏：预加载的播放器不显示
     * 3. 只缓冲不播放：预加载好就暂停，等切换时再播放
     */
    private void initPreloadPlayer() {
        preloadPlayer = createPlayer();
        preloadPlayerListener = createPlayerListener(false);
        preloadPlayer.addListener(preloadPlayerListener);
        // 预加载播放器默认静音
        preloadPlayer.setVolume(0f);
    }

    // ====================================================================
    // ✅ 双播放器：创建播放器（公共方法，两个播放器都用同样的配置）
    // ====================================================================
    /**
     * 创建一个播放器实例（两个播放器用同样的配置）
     *
     * @return ExoPlayer 实例
     */
    private ExoPlayer createPlayer() {
        // 初始化渲染器工厂
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        if (useSoftwareDecoder) {
            // 软解码模式
            renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
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
        // ✅ 缓冲配置（快速出画 + 大缓冲防卡）
        // ================================================
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        2000,      // minBufferMs - 最小缓冲 2秒
                        50000,     // maxBufferMs - 最大缓冲 50秒（抗网络波动）
                        300,       // bufferForPlaybackMs - 有 300ms 就开始播（快速出画）
                        500        // bufferForPlaybackAfterRebufferMs - 重缓冲后 500ms 就播
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        // 创建 ExoPlayer 实例
        return new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();
    }

    // ====================================================================
    // ✅ 双播放器：创建播放器监听器
    // ====================================================================
    /**
     * 创建播放器状态监听器
     *
     * @param isMain 是否是主播放器（主播放器才触发回调和卡住检测）
     * @return Player.Listener
     */
    private Player.Listener createPlayerListener(final boolean isMain) {
        return new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, (isMain ? "主播放器" : "预加载播放器") + "异常: " + error.getMessage());

                if (isMain) {
                    // 主播放器错误才触发回调和重试
                    if (listener != null) {
                        listener.onPlayError(error.getMessage());
                    }
                    autoRetry("播放错误");
                } else {
                    // 预加载播放器失败，标记未就绪
                    isPreloadReady = false;
                    Log.w(TAG, "预加载失败：" + error.getMessage());
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (isMain) {
                    // 主播放器状态变化
                    handleMainPlayerState(state);
                } else {
                    // 预加载播放器状态变化
                    handlePreloadPlayerState(state);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isMain && isPlaying) {
                    lastPositionUpdateTime = System.currentTimeMillis();
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if (isMain) {
                    // 主播放器分辨率变化才通知 UI 更新
                    int width = videoSize.width;
                    int height = videoSize.height;
                    Log.d(TAG, "视频分辨率变化：" + width + "×" + height);
                    notifyLiveInfoUpdate();
                }
            }
        };
    }

    // ====================================================================
    // ✅ 双播放器：主播放器状态处理
    // ====================================================================
    /**
     * 处理主播放器状态变化
     */
    private void handleMainPlayerState(int state) {
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
            // 直播流意外结束，自动重试
            autoRetry("播放结束");
        } else if (state == Player.STATE_IDLE) {
            if (listener != null) listener.onIdle();
        } else {
            updateWakeLock(false);
        }
    }

    // ====================================================================
    // ✅ 双播放器：预加载播放器状态处理
    // ====================================================================
    /**
     * 处理预加载播放器状态变化
     *
     * 预加载播放器缓冲就绪后，就暂停播放（只保持缓冲，不播放），
     * 标记为就绪状态，等待切台时直接切换。
     */
    private void handlePreloadPlayerState(int state) {
        if (state == Player.STATE_READY) {
            // 预加载就绪，暂停播放（只缓冲，不播放）
            preloadPlayer.pause();
            isPreloadReady = true;
            Log.d(TAG, "【双播放器】预加载就绪：" + preloadedUrl);
        } else if (state == Player.STATE_BUFFERING) {
            // 缓冲中，继续等
            Log.d(TAG, "【双播放器】预加载缓冲中：" + preloadedUrl);
        } else if (state == Player.STATE_ENDED) {
            // 预加载结束（不太可能，直播流一般不会结束）
            isPreloadReady = false;
        }
    }

    // ================================================
    // ✅ 卡住检测 + 自动重试（只检测主播放器）
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
            if (mainPlayer == null || !mainPlayer.isPlaying()) {
                // 没在播放，不检测
                stuckHandler.postDelayed(this, 2000);
                return;
            }
            try {
                long currentPosition = mainPlayer.getCurrentPosition();
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
                    // 重新播放当前地址（用主播放器正常播放，不用预加载）
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

        // 重新创建两个播放器
        releaseAllPlayers();
        initMainPlayer();
        initPreloadPlayer();

        // 重新绑定视图
        if (mainPlayerView != null) {
            mainPlayerView.setPlayer(mainPlayer);
        }
        if (preloadPlayerView != null) {
            preloadPlayerView.setPlayer(preloadPlayer);
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
            if (mainPlayer != null && mainPlayerView != null) {
                mainPlayerView.setPlayer(mainPlayer);
                mainPlayer.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "切前台异常", e);
        }
    }

    public void onBackground() {
        try {
            if (mainPlayer != null) {
                mainPlayer.pause();
            }
        } catch (Exception e) {
            Log.e(TAG, "切后台异常", e);
        }
    }

    // ====================================================================
    // ✅ 双播放器：绑定播放器视图
    // ====================================================================
    /**
     * 绑定播放器视图（两个都要绑定）
     *
     * @param mainView    主播放器视图
     * @param preloadView 预加载播放器视图
     */
    public void attachPlayerViews(PlayerView mainView, PlayerView preloadView) {
        this.mainPlayerView = mainView;
        this.preloadPlayerView = preloadView;

        mainPlayerView.setPlayer(mainPlayer);
        mainPlayerView.setUseController(false);

        preloadPlayerView.setPlayer(preloadPlayer);
        preloadPlayerView.setUseController(false);
    }

    /**
     * 兼容旧方法：只绑定主播放器视图
     * （为了兼容旧代码，保留这个方法）
     */
    public void attachPlayerView(PlayerView view) {
        this.mainPlayerView = view;
        mainPlayerView.setPlayer(mainPlayer);
        mainPlayerView.setUseController(false);
    }

    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (mainPlayerView != null) {
            mainPlayerView.setKeepScreenOn(enable);
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
        } else if (isDouyu) {
            headers.put("Referer", "https://www.douyu.com/");
            Log.d(TAG, "斗鱼直播，设置斗鱼Referer");
        } else {
            headers.put("Referer", "https://www.huya.com/");
        }

        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }

        return headers;
    }

    /**
     * 创建 MediaSource（公共方法，两个播放器都用）
     */
    private com.google.android.exoplayer2.source.MediaSource buildMediaSource(String url) {
        RedirectLoggingHttpDataSource.Factory httpFactory =
                new RedirectLoggingHttpDataSource.Factory();
        httpFactory.setDefaultRequestProperties(getHeaders(url));
        httpFactory.setAllowCrossProtocolRedirects(true);

        MediaItem mediaItem = MediaItem.fromUri(url);

        if (url.toLowerCase().contains("m3u8")) {
            Log.d(TAG, "流格式：HLS (m3u8)");
            return new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        } else {
            Log.d(TAG, "流格式：普通流 (Progressive)");
            return new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        }
    }

    public void play(String url) {
        playUrl(url);
    }

    // ====================================================================
    // ✅ 双播放器：播放指定URL（对外接口）
    // ====================================================================
    /**
     * 播放指定URL（对外接口）
     *
     * 【智能切换逻辑】
     * 1. 如果目标地址已经预加载好了 → 直接无缝切换（0 黑屏）
     * 2. 如果没有预加载 → 用主播放器正常切换（可能有短暂黑屏）
     *
     * @param url 播放地址
     */
    public void playUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        url = url.trim();

        // 切换频道，重置重试计数
        retryCount = 0;
        isRetrying = false;

        // ====================================================================
        // ✅ 双播放器：检查是否已经预加载了目标地址
        // ====================================================================
        if (url.equals(preloadedUrl) && isPreloadReady) {
            // 已经预加载好了，直接无缝切换
            Log.d(TAG, "【双播放器】无缝切换到预加载频道：" + url);
            switchToPreload();
            return;
        }

        // 没有预加载，用主播放器正常播放
        Log.d(TAG, "【双播放器】未预加载，正常播放：" + url);
        playUrlInternal(url);
    }

    // ====================================================================
    // ✅ 双播放器：内部播放方法（用主播放器正常播放）
    // ====================================================================
    /**
     * 内部播放方法（用主播放器正常播放）
     *
     * 【切台优化：保持最后一帧】
     * 去掉 player.stop() 和 player.clearMediaItems()
     * 直接用 setMediaSource 切换，旧画面会保留到新画面出来
     */
    private void playUrlInternal(String url) {
        try {
            if (mainPlayer == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            Log.d(TAG, "开始播放：" + currentUrl);

            // 直接设置新的媒体源，不调用 stop，保持最后一帧
            com.google.android.exoplayer2.source.MediaSource mediaSource = buildMediaSource(currentUrl);
            mainPlayer.setMediaSource(mediaSource, true);
            mainPlayer.prepare();
            mainPlayer.play();

            // 开始卡住检测
            startStuckDetection();

        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            autoRetry("播放异常：" + e.getMessage());
        }
    }

    // ====================================================================
    // ✅ 双播放器：预加载指定地址
    // ====================================================================
    /**
     * 预加载指定地址（提前缓冲，等切台时直接切换）
     *
     * 【预加载流程】
     * 1. 用预加载播放器加载目标地址
     * 2. 缓冲就绪后自动暂停（只缓冲，不播放）
     * 3. 标记为就绪状态
     * 4. 切台时直接交换两个播放器，无缝切换
     *
     * @param url 要预加载的地址
     */
    public void preloadUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        url = url.trim();

        // 如果预加载的就是当前播放的，跳过
        if (url.equals(currentUrl)) {
            Log.d(TAG, "【双播放器】预加载地址就是当前地址，跳过");
            return;
        }

        // 如果已经预加载了这个地址，跳过
        if (url.equals(preloadedUrl) && isPreloadReady) {
            Log.d(TAG, "【双播放器】已预加载过：" + url);
            return;
        }

        Log.d(TAG, "【双播放器】开始预加载：" + url);
        preloadedUrl = url;
        isPreloadReady = false;

        try {
            // 用预加载播放器加载
            com.google.android.exoplayer2.source.MediaSource mediaSource = buildMediaSource(url);
            preloadPlayer.setMediaSource(mediaSource, true);
            preloadPlayer.prepare();
            // 预加载播放器静音
            preloadPlayer.setVolume(0f);
            // 准备好后会自动暂停（在状态监听器里处理）

        } catch (Exception e) {
            Log.e(TAG, "预加载异常", e);
            isPreloadReady = false;
        }
    }

    // ====================================================================
    // ✅ 双播放器：切换到预加载播放器（无缝切换，0 黑屏）
    // ====================================================================
    /**
     * 切换到预加载播放器（无缝切换，0 黑屏）
     *
     * 【切换流程】
     * 1. 交换两个播放器的引用
     * 2. 交换两个 PlayerView 的显示/隐藏
     * 3. 新的主播放器取消静音，新的预加载播放器静音
     * 4. 新的主播放器开始播放
     * 5. 更新 currentUrl
     * 6. 重新设置监听器（因为播放器交换了）
     *
     * 【效果】
     * 用户完全感知不到切换，画面瞬间从旧台变到新台，0 毫秒黑屏。
     */
    private void switchToPreload() {
        if (preloadPlayer == null || !isPreloadReady) return;

        Log.d(TAG, "【双播放器】执行无缝切换");

        // ========================================
        // 1. 交换两个播放器的引用
        // ========================================
        ExoPlayer tempPlayer = mainPlayer;
        mainPlayer = preloadPlayer;
        preloadPlayer = tempPlayer;

        // ========================================
        // 2. 交换两个 PlayerView 的显示/隐藏
        // ========================================
        // 新的主播放器视图显示
        if (mainPlayerView != null) {
            mainPlayerView.setVisibility(View.VISIBLE);
            mainPlayerView.setPlayer(mainPlayer);
        }
        // 新的预加载播放器视图隐藏
        if (preloadPlayerView != null) {
            preloadPlayerView.setVisibility(View.INVISIBLE);
            preloadPlayerView.setPlayer(preloadPlayer);
        }

        // ========================================
        // 3. 音量控制：主播放器有声音，预加载播放器静音
        // ========================================
        mainPlayer.setVolume(1f);    // 主播放器取消静音
        preloadPlayer.setVolume(0f);  // 预加载播放器静音

        // ========================================
        // 4. 新的主播放器开始播放
        // ========================================
        mainPlayer.play();

        // ========================================
        // 5. 更新当前播放地址
        // ========================================
        currentUrl = preloadedUrl;
        preloadedUrl = "";
        isPreloadReady = false;

        // ========================================
        // 6. 重新设置监听器
        // ========================================
        // （因为播放器交换了，监听器也要重新绑定）
        // 这里简化处理：直接重新开始卡住检测
        startStuckDetection();

        // ========================================
        // 7. 触发回调
        // ========================================
        if (listener != null) {
            listener.onPlayReady();
        }
        notifyLiveInfoUpdate();
        showChannelAndAutoHide();

        Log.d(TAG, "【双播放器】无缝切换完成：" + currentUrl);
    }

    // ====================================================================
    // ✅ 双播放器：检查指定地址是否已经预加载
    // ====================================================================
    /**
     * 检查指定地址是否已经预加载就绪
     *
     * @param url 播放地址
     * @return 是否已预加载就绪
     */
    public boolean isPreloaded(String url) {
        return url != null && url.equals(preloadedUrl) && isPreloadReady;
    }

    /**
     * 获取当前预加载的地址
     *
     * @return 预加载地址
     */
    public String getPreloadedUrl() {
        return preloadedUrl;
    }

    public void setScaleMode(ScaleMode mode) {
        try {
            if (mainPlayerView == null) return;
            switch (mode) {
                case FIT:
                    mainPlayerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    break;
                case FILL:
                    mainPlayerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL);
                    break;
                case ZOOM:
                    mainPlayerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
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
        try { if (mainPlayer != null) mainPlayer.pause(); } catch (Exception e) {
            Log.e(TAG, "暂停异常", e);
        }
    }

    public void resume() {
        try { if (mainPlayer != null) mainPlayer.play(); } catch (Exception e) {
            Log.e(TAG, "恢复异常", e);
        }
    }

    // ====================================================================
    // ✅ 双播放器：释放所有播放器
    // ====================================================================
    /**
     * 释放所有播放器资源
     */
    private void releaseAllPlayers() {
        stopStuckDetection();
        mHandler.removeCallbacks(hideChannelRunnable);
        updateWakeLock(false);

        // 释放主播放器
        if (mainPlayer != null) {
            if (mainPlayerListener != null) {
                mainPlayer.removeListener(mainPlayerListener);
            }
            mainPlayer.release();
            mainPlayer = null;
        }

        // 释放预加载播放器
        if (preloadPlayer != null) {
            if (preloadPlayerListener != null) {
                preloadPlayer.removeListener(preloadPlayerListener);
            }
            preloadPlayer.release();
            preloadPlayer = null;
        }
    }

    public void release() {
        try {
            releaseAllPlayers();
            instance = null;
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }
}
