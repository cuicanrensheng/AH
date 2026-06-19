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
 *
 * 【三播放器双向预加载版】
 * 三个播放器同时工作：
 * - 主播放器（mainPlayer）：正在播放，有声音，显示
 * - 预加载播放器（preloadPlayerNext）：提前缓冲下一个台，静音，隐藏
 * - 预加载播放器（preloadPlayerPrev）：提前缓冲上一个台，静音，隐藏
 *
 * 切台时直接交换主播放器和对应方向的预加载播放器，0 毫秒黑屏，完全无缝。
 * 上下两个方向都预加载，不管按上还是按下都是无缝的。
 *
 * 【切换原理】
 * 按下键时：
 * - 原来的下一个预加载 → 新的主播放器（显示，有声音）
 * - 原来的主播放器 → 新的上一个预加载（隐藏，静音）
 * - 原来的上一个预加载 → 释放，重新预加载新的下一个
 *
 * 按上键时：
 * - 原来的上一个预加载 → 新的主播放器（显示，有声音）
 * - 原来的主播放器 → 新的下一个预加载（隐藏，静音）
 * - 原来的下一个预加载 → 释放，重新预加载新的上一个
 *
 * 这样每次切换后，都有一个方向的预加载可以直接复用（不用重新缓冲），
 * 另一个方向需要重新预加载。
 */
public class TVPlayerManager {

    private static final String TAG = "TVPlayerLog";
    private static TVPlayerManager instance;

    // ====================== 三播放器相关 ======================
    /** 主播放器（正在播放，有声音，显示） */
    private ExoPlayer mainPlayer;
    /** 预加载播放器 - 下一个频道（静音，隐藏） */
    private ExoPlayer preloadPlayerNext;
    /** 预加载播放器 - 上一个频道（静音，隐藏） */
    private ExoPlayer preloadPlayerPrev;

    /** 主播放器视图 */
    private PlayerView mainPlayerView;
    /** 预加载播放器视图 - 下一个 */
    private PlayerView preloadPlayerViewNext;
    /** 预加载播放器视图 - 上一个 */
    private PlayerView preloadPlayerViewPrev;

    /** 当前预加载的下一个地址 */
    private String preloadedUrlNext = "";
    /** 当前预加载的上一个地址 */
    private String preloadedUrlPrev = "";

    /** 下一个预加载是否就绪 */
    private boolean isPreloadReadyNext = false;
    /** 上一个预加载是否就绪 */
    private boolean isPreloadReadyPrev = false;

    // ====================== 其他成员变量 ======================
    private Context context;
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
    private Player.Listener mainPlayerListener;
    private Player.Listener preloadNextListener;
    private Player.Listener preloadPrevListener;

    // ====================== 防卡优化相关 ======================
    private boolean useSoftwareDecoder = false;
    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;
    private static final long STUCK_TIMEOUT = 5000;
    private int retryCount = 0;
    private static final int MAX_RETRY_COUNT = 3;
    private final Handler stuckHandler = new Handler(Looper.getMainLooper());
    private boolean isRetrying = false;

    // ====================== 直播信息实体类 ======================
    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
        public int videoWidth;
        public int videoHeight;
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
        try {
            if (mainPlayer != null) {
                Format videoFormat = mainPlayer.getVideoFormat();
                if (videoFormat != null && videoFormat.width != Format.NO_VALUE) {
                    info.videoWidth = videoFormat.width;
                    info.videoHeight = videoFormat.height;
                    if (videoFormat.width >= 1920 || videoFormat.height >= 1080) {
                        info.quality = "FHD";
                    } else if (videoFormat.width >= 1280 || videoFormat.height >= 720) {
                        info.quality = "HD";
                    } else {
                        info.quality = "SD";
                    }
                    if (videoFormat.bitrate != Format.NO_VALUE && videoFormat.bitrate > 0) {
                        double bitrateMBs = videoFormat.bitrate / 8.0 / 1024.0 / 1024.0;
                        info.bitrate = String.format("%.1fMB/s", bitrateMBs);
                    } else {
                        info.bitrate = "—";
                    }
                } else {
                    info.quality = "—";
                    info.bitrate = "—";
                }
                Format audioFormat = mainPlayer.getAudioFormat();
                if (audioFormat != null) {
                    int channels = audioFormat.channelCount;
                    if (channels == 1) info.audio = "单声道";
                    else if (channels == 2) info.audio = "立体声";
                    else if (channels >= 6) info.audio = "5.1";
                    else info.audio = channels + "声道";
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
        // 初始化三个播放器
        initMainPlayer();
        initPreloadPlayerNext();
        initPreloadPlayerPrev();
    }

    // ====================================================================
    // 初始化三个播放器
    // ====================================================================
    private void initMainPlayer() {
        mainPlayer = createPlayer();
        mainPlayerListener = createPlayerListener(true, "主播放器");
        mainPlayer.addListener(mainPlayerListener);
    }

    private void initPreloadPlayerNext() {
        preloadPlayerNext = createPlayer();
        preloadNextListener = createPlayerListener(false, "预加载-下一个");
        preloadPlayerNext.addListener(preloadNextListener);
        preloadPlayerNext.setVolume(0f);  // 静音
    }

    private void initPreloadPlayerPrev() {
        preloadPlayerPrev = createPlayer();
        preloadPrevListener = createPlayerListener(false, "预加载-上一个");
        preloadPlayerPrev.addListener(preloadPrevListener);
        preloadPlayerPrev.setVolume(0f);  // 静音
    }

    // ====================================================================
    // 创建播放器（公共方法，三个播放器用同样的配置）
    // ====================================================================
    private ExoPlayer createPlayer() {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        if (useSoftwareDecoder) {
            renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
            try {
                renderersFactory.setEnableDecoderFallback(true);
            } catch (Exception e) {
                Log.e(TAG, "设置软解码失败", e);
            }
        } else {
            renderersFactory.setEnableDecoderFallback(true);
        }

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 50000, 300, 500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        return new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();
    }

    // ====================================================================
    // 创建播放器监听器
    // ====================================================================
    private Player.Listener createPlayerListener(final boolean isMain, final String tag) {
        return new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, tag + "异常: " + error.getMessage());
                if (isMain) {
                    if (listener != null) listener.onPlayError(error.getMessage());
                    autoRetry("播放错误");
                } else {
                    // 预加载失败，标记未就绪
                    if (tag.contains("下一个")) {
                        isPreloadReadyNext = false;
                    } else {
                        isPreloadReadyPrev = false;
                    }
                    Log.w(TAG, tag + "失败：" + error.getMessage());
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (isMain) {
                    handleMainPlayerState(state);
                } else {
                    handlePreloadPlayerState(state, tag);
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
                    notifyLiveInfoUpdate();
                }
            }
        };
    }

    // ====================================================================
    // 状态处理
    // ====================================================================
    private void handleMainPlayerState(int state) {
        if (state == Player.STATE_READY) {
            updateWakeLock(true);
            notifyLiveInfoUpdate();
            showChannelAndAutoHide();
            if (listener != null) listener.onPlayReady();
            retryCount = 0;
            isRetrying = false;
            startStuckDetection();
        } else if (state == Player.STATE_BUFFERING) {
            if (listener != null) listener.onBuffering();
            lastPositionUpdateTime = System.currentTimeMillis();
        } else if (state == Player.STATE_ENDED) {
            if (listener != null) listener.onPlayEnd();
            autoRetry("播放结束");
        } else if (state == Player.STATE_IDLE) {
            if (listener != null) listener.onIdle();
        } else {
            updateWakeLock(false);
        }
    }

    private void handlePreloadPlayerState(int state, String tag) {
        if (state == Player.STATE_READY) {
            // 预加载就绪，暂停播放（只缓冲，不播放）
            if (tag.contains("下一个")) {
                preloadPlayerNext.pause();
                isPreloadReadyNext = true;
                Log.d(TAG, "【三播放器】下一个预加载就绪：" + preloadedUrlNext);
            } else {
                preloadPlayerPrev.pause();
                isPreloadReadyPrev = true;
                Log.d(TAG, "【三播放器】上一个预加载就绪：" + preloadedUrlPrev);
            }
        } else if (state == Player.STATE_BUFFERING) {
            Log.d(TAG, "【三播放器】" + tag + "缓冲中");
        }
    }

    // ====================================================================
    // 卡住检测 + 自动重试
    // ====================================================================
    private void startStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        stuckHandler.postDelayed(stuckCheckRunnable, 2000);
    }

    private void stopStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
    }

    private final Runnable stuckCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (mainPlayer == null || !mainPlayer.isPlaying()) {
                stuckHandler.postDelayed(this, 2000);
                return;
            }
            try {
                long currentPosition = mainPlayer.getCurrentPosition();
                long now = System.currentTimeMillis();
                if (currentPosition != lastPosition) {
                    lastPosition = currentPosition;
                    lastPositionUpdateTime = now;
                } else {
                    if (now - lastPositionUpdateTime > STUCK_TIMEOUT) {
                        Log.w(TAG, "检测到播放卡住，自动重试...");
                        autoRetry("播放卡住");
                        return;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "卡住检测异常", e);
            }
            stuckHandler.postDelayed(this, 2000);
        }
    };

    private void autoRetry(String reason) {
        if (isRetrying) return;
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限：" + MAX_RETRY_COUNT);
            return;
        }
        isRetrying = true;
        retryCount++;
        Log.w(TAG, "自动重试（第" + retryCount + "次），原因：" + reason);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!TextUtils.isEmpty(currentUrl)) {
                    playUrlInternal(currentUrl);
                }
            }
        }, 1000);
    }

    public void setSoftwareDecoder(boolean useSoftware) {
        if (useSoftwareDecoder == useSoftware) return;
        useSoftwareDecoder = useSoftware;
        Log.d(TAG, "切换解码器：" + (useSoftware ? "软解码" : "硬解码"));
        releaseAllPlayers();
        initMainPlayer();
        initPreloadPlayerNext();
        initPreloadPlayerPrev();
        if (mainPlayerView != null) mainPlayerView.setPlayer(mainPlayer);
        if (preloadPlayerViewNext != null) preloadPlayerViewNext.setPlayer(preloadPlayerNext);
        if (preloadPlayerViewPrev != null) preloadPlayerViewPrev.setPlayer(preloadPlayerPrev);
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
    // ✅ 三播放器：绑定三个播放器视图
    // ====================================================================
    /**
     * 绑定三个播放器视图
     *
     * @param mainView          主播放器视图
     * @param preloadNextView   下一个预加载视图
     * @param preloadPrevView   上一个预加载视图
     */
    public void attachPlayerViews(PlayerView mainView, PlayerView preloadNextView, PlayerView preloadPrevView) {
        this.mainPlayerView = mainView;
        this.preloadPlayerViewNext = preloadNextView;
        this.preloadPlayerViewPrev = preloadPrevView;

        mainPlayerView.setPlayer(mainPlayer);
        mainPlayerView.setUseController(false);

        preloadPlayerViewNext.setPlayer(preloadPlayerNext);
        preloadPlayerViewNext.setUseController(false);

        preloadPlayerViewPrev.setPlayer(preloadPlayerPrev);
        preloadPlayerViewPrev.setUseController(false);
    }

    /**
     * 兼容旧方法：只绑定主播放器视图
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
        } else if (isDouyu) {
            headers.put("Referer", "https://www.douyu.com/");
        } else {
            headers.put("Referer", "https://www.huya.com/");
        }
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }
        return headers;
    }

    private com.google.android.exoplayer2.source.MediaSource buildMediaSource(String url) {
        RedirectLoggingHttpDataSource.Factory httpFactory =
                new RedirectLoggingHttpDataSource.Factory();
        httpFactory.setDefaultRequestProperties(getHeaders(url));
        httpFactory.setAllowCrossProtocolRedirects(true);
        MediaItem mediaItem = MediaItem.fromUri(url);
        if (url.toLowerCase().contains("m3u8")) {
            return new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        } else {
            return new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        }
    }

    public void play(String url) {
        playUrl(url);
    }

    // ====================================================================
    // ✅ 三播放器：播放指定URL（智能判断是否已预加载）
    // ====================================================================
    /**
     * 播放指定URL
     *
     * 【智能切换逻辑】
     * 1. 如果是下一个频道且已预加载 → 无缝切换到下一个预加载
     * 2. 如果是上一个频道且已预加载 → 无缝切换到上一个预加载
     * 3. 否则 → 用主播放器正常播放
     */
    public void playUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        url = url.trim();

        retryCount = 0;
        isRetrying = false;

        // 检查下一个预加载
        if (url.equals(preloadedUrlNext) && isPreloadReadyNext) {
            Log.d(TAG, "【三播放器】无缝切换到下一个预加载：" + url);
            switchToNextPreload();
            return;
        }

        // 检查上一个预加载
        if (url.equals(preloadedUrlPrev) && isPreloadReadyPrev) {
            Log.d(TAG, "【三播放器】无缝切换到上一个预加载：" + url);
            switchToPrevPreload();
            return;
        }

        // 都没预加载，正常播放
        Log.d(TAG, "【三播放器】未预加载，正常播放：" + url);
        playUrlInternal(url);
    }

    // ====================================================================
    // 内部播放方法（用主播放器正常播放）
    // ====================================================================
    private void playUrlInternal(String url) {
        try {
            if (mainPlayer == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            Log.d(TAG, "开始播放：" + currentUrl);

            com.google.android.exoplayer2.source.MediaSource mediaSource = buildMediaSource(currentUrl);
            mainPlayer.setMediaSource(mediaSource, true);
            mainPlayer.prepare();
            mainPlayer.play();

            startStuckDetection();
        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            autoRetry("播放异常：" + e.getMessage());
        }
    }

    // ====================================================================
    // ✅ 三播放器：预加载下一个频道
    // ====================================================================
    /**
     * 预加载下一个频道
     *
     * @param url 下一个频道的播放地址
     */
    public void preloadNextUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        url = url.trim();

        if (url.equals(currentUrl)) {
            Log.d(TAG, "【三播放器】下一个预加载地址就是当前地址，跳过");
            return;
        }
        if (url.equals(preloadedUrlNext) && isPreloadReadyNext) {
            Log.d(TAG, "【三播放器】下一个已预加载过：" + url);
            return;
        }

        Log.d(TAG, "【三播放器】开始预加载下一个：" + url);
        preloadedUrlNext = url;
        isPreloadReadyNext = false;

        try {
            com.google.android.exoplayer2.source.MediaSource mediaSource = buildMediaSource(url);
            preloadPlayerNext.setMediaSource(mediaSource, true);
            preloadPlayerNext.prepare();
            preloadPlayerNext.setVolume(0f);
        } catch (Exception e) {
            Log.e(TAG, "下一个预加载异常", e);
            isPreloadReadyNext = false;
        }
    }

    // ====================================================================
    // ✅ 三播放器：预加载上一个频道
    // ====================================================================
    /**
     * 预加载上一个频道
     *
     * @param url 上一个频道的播放地址
     */
    public void preloadPrevUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        url = url.trim();

        if (url.equals(currentUrl)) {
            Log.d(TAG, "【三播放器】上一个预加载地址就是当前地址，跳过");
            return;
        }
        if (url.equals(preloadedUrlPrev) && isPreloadReadyPrev) {
            Log.d(TAG, "【三播放器】上一个已预加载过：" + url);
            return;
        }

        Log.d(TAG, "【三播放器】开始预加载上一个：" + url);
        preloadedUrlPrev = url;
        isPreloadReadyPrev = false;

        try {
            com.google.android.exoplayer2.source.MediaSource mediaSource = buildMediaSource(url);
            preloadPlayerPrev.setMediaSource(mediaSource, true);
            preloadPlayerPrev.prepare();
            preloadPlayerPrev.setVolume(0f);
        } catch (Exception e) {
            Log.e(TAG, "上一个预加载异常", e);
            isPreloadReadyPrev = false;
        }
    }

    // ====================================================================
    // ✅ 三播放器：切换到下一个预加载（无缝切换）
    // ====================================================================
    /**
     * 切换到下一个预加载播放器（无缝切换）
     *
     * 【切换后角色变化】
     * - 新的主播放器：原来的下一个预加载
     * - 新的下一个预加载：需要重新预加载新的下一个
     * - 新的上一个预加载：原来的主播放器（不用重新加载，直接复用）
     */
    private void switchToNextPreload() {
        if (preloadPlayerNext == null || !isPreloadReadyNext) return;

        Log.d(TAG, "【三播放器】执行无缝切换（下一个）");

        // 1. 保存原来的主播放器
        ExoPlayer tempMain = mainPlayer;
        PlayerView tempMainView = mainPlayerView;
        String tempMainUrl = currentUrl;

        // 2. 下一个预加载变成新的主播放器
        mainPlayer = preloadPlayerNext;
        mainPlayerView = preloadPlayerViewNext;
        currentUrl = preloadedUrlNext;

        // 3. 原来的主播放器变成新的上一个预加载
        preloadPlayerPrev = tempMain;
        preloadPlayerViewPrev = tempMainView;
        preloadedUrlPrev = tempMainUrl;
        isPreloadReadyPrev = true;  // 原来的主播放器肯定是就绪的

        // 4. 下一个预加载标记为未就绪（需要重新预加载）
        isPreloadReadyNext = false;
        preloadedUrlNext = "";

        // 5. 更新视图显示
        if (mainPlayerView != null) {
            mainPlayerView.setVisibility(View.VISIBLE);
            mainPlayerView.setPlayer(mainPlayer);
        }
        if (preloadPlayerViewPrev != null) {
            preloadPlayerViewPrev.setVisibility(View.INVISIBLE);
            preloadPlayerViewPrev.setPlayer(preloadPlayerPrev);
            preloadPlayerPrev.setVolume(0f);  // 静音
            preloadPlayerPrev.pause();        // 暂停，只缓冲
        }

        // 6. 新的主播放器开始播放，取消静音
        mainPlayer.setVolume(1f);
        mainPlayer.play();

        // 7. 重新开始卡住检测
        startStuckDetection();

        // 8. 触发回调
        if (listener != null) listener.onPlayReady();
        notifyLiveInfoUpdate();
        showChannelAndAutoHide();

        Log.d(TAG, "【三播放器】下一个无缝切换完成：" + currentUrl);
    }

    // ====================================================================
    // ✅ 三播放器：切换到上一个预加载（无缝切换）
    // ====================================================================
    /**
     * 切换到上一个预加载播放器（无缝切换）
     *
     * 【切换后角色变化】
     * - 新的主播放器：原来的上一个预加载
     * - 新的上一个预加载：需要重新预加载新的上一个
     * - 新的下一个预加载：原来的主播放器（不用重新加载，直接复用）
     */
    private void switchToPrevPreload() {
        if (preloadPlayerPrev == null || !isPreloadReadyPrev) return;

        Log.d(TAG, "【三播放器】执行无缝切换（上一个）");

        // 1. 保存原来的主播放器
        ExoPlayer tempMain = mainPlayer;
        PlayerView tempMainView = mainPlayerView;
        String tempMainUrl = currentUrl;

        // 2. 上一个预加载变成新的主播放器
        mainPlayer = preloadPlayerPrev;
        mainPlayerView = preloadPlayerViewPrev;
        currentUrl = preloadedUrlPrev;

        // 3. 原来的主播放器变成新的下一个预加载
        preloadPlayerNext = tempMain;
        preloadPlayerViewNext = tempMainView;
        preloadedUrlNext = tempMainUrl;
        isPreloadReadyNext = true;  // 原来的主播放器肯定是就绪的

        // 4. 上一个预加载标记为未就绪（需要重新预加载）
        isPreloadReadyPrev = false;
        preloadedUrlPrev = "";

        // 5. 更新视图显示
        if (mainPlayerView != null) {
            mainPlayerView.setVisibility(View.VISIBLE);
            mainPlayerView.setPlayer(mainPlayer);
        }
        if (preloadPlayerViewNext != null) {
            preloadPlayerViewNext.setVisibility(View.INVISIBLE);
            preloadPlayerViewNext.setPlayer(preloadPlayerNext);
            preloadPlayerNext.setVolume(0f);  // 静音
            preloadPlayerNext.pause();        // 暂停，只缓冲
        }

        // 6. 新的主播放器开始播放，取消静音
        mainPlayer.setVolume(1f);
        mainPlayer.play();

        // 7. 重新开始卡住检测
        startStuckDetection();

        // 8. 触发回调
        if (listener != null) listener.onPlayReady();
        notifyLiveInfoUpdate();
        showChannelAndAutoHide();

        Log.d(TAG, "【三播放器】上一个无缝切换完成：" + currentUrl);
    }

    // ====================================================================
    // 检查是否已预加载
    // ====================================================================
    public boolean isNextPreloaded(String url) {
        return url != null && url.equals(preloadedUrlNext) && isPreloadReadyNext;
    }

    public boolean isPrevPreloaded(String url) {
        return url != null && url.equals(preloadedUrlPrev) && isPreloadReadyPrev;
    }

    /**
     * 兼容旧方法：检查是否已预加载（默认检查下一个）
     */
    public boolean isPreloaded(String url) {
        return isNextPreloaded(url);
    }

    public String getPreloadedUrlNext() {
        return preloadedUrlNext;
    }

    public String getPreloadedUrlPrev() {
        return preloadedUrlPrev;
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
    // 释放所有播放器
    // ====================================================================
    private void releaseAllPlayers() {
        stopStuckDetection();
        mHandler.removeCallbacks(hideChannelRunnable);
        updateWakeLock(false);

        if (mainPlayer != null) {
            if (mainPlayerListener != null) mainPlayer.removeListener(mainPlayerListener);
            mainPlayer.release();
            mainPlayer = null;
        }
        if (preloadPlayerNext != null) {
            if (preloadNextListener != null) preloadPlayerNext.removeListener(preloadNextListener);
            preloadPlayerNext.release();
            preloadPlayerNext = null;
        }
        if (preloadPlayerPrev != null) {
            if (preloadPrevListener != null) preloadPlayerPrev.removeListener(preloadPrevListener);
            preloadPlayerPrev.release();
            preloadPlayerPrev = null;
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
