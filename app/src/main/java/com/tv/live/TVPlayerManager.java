package com.tv.live;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.widget.Toast;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Surface;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.CookieManager;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import androidx.core.content.ContextCompat;

import com.tv.live.util.NetUtil;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.Variant;
import com.tv.live.util.DecoderModeManager;
import com.tv.live.util.VariantManager;
import com.tv.live.util.HuyaStreamPlayer;
import com.tv.live.util.AppCacheInspector;
import com.tv.live.exception.RedirectFailedException;
import com.tv.live.BuildConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import okhttp3.Headers;

@SuppressLint({"UnsafeOptInUsageError", "StaticFieldLeak"})
public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";
    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_HARD = 1;
    public static final int DECODER_MODE_SOFT = 2;
    
    private static final int MAX_RETRY_COUNT = 2;
    private static final int MAX_RETRY_COUNT_NETWORK = 5;
    private static final long STUCK_TIMEOUT = 20000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    private static final long SOURCE_FAILED_COOLDOWN_MS = 30000;
    private static final long MIN_STALL_WARN_THRESHOLD = 2000;

    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";

    private static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";

    private static volatile TVPlayerManager instance;
    private Context context;
    private ExoPlayer player;
    private PlayerView playerView;
    private Player.Listener playerListener;
    private String currentUrl;
    private com.tv.live.util.SourceHealthChecker healthChecker;
    private int currentChannelNumber = 0;
    private TextView channelNumberTextView;
    private String currentChannelName = "";
    private int mDecoderMode = DECODER_MODE_AUTO;

    private boolean isSwitching = false;

    private Channel currentChannel;
    private int backupRetryIndex = -1;

    private long initialPlayStartTime = 0;
    private int bufferCount = 0;
    private long totalStallTime = 0;
    private boolean isStalled = false;
    private long lastStallStartTime = 0;
    private int retryCount = 0;
    private boolean isRetrying = false;
    private Runnable retryRunnable;
    private long lastSourceFailedTime = 0;

    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;
    private Runnable stuckCheckRunnable;

    private Handler mHandler;
    private Runnable hideChannelRunnable;

    // 🟢 虎牙 SDK 容器（仅用于隐藏，不再使用 SDK 原生播放器）
    private FrameLayout mSdkPlayerContainer;
    private Activity mActivity;
    private int mHuyaRoomId = -1; // 当前虎牙房间号（用于重试时重新解析）
    private int mCurrentHuyaLineIndex = 0; // 当前虎牙线路索引

    private OnPlayStateListener listener;
    private OnSourceFailedListener sourceFailedListener;
    private OnLiveInfoUpdateListener liveInfoUpdateListener;
    private boolean isPlaying = false;

    private BroadcastReceiver decoderModeReceiver;
    private boolean decoderReceiverRegistered = false;
    private BroadcastReceiver rendererModeReceiver;
    private boolean rendererReceiverRegistered = false;

    private OnPlayerViewRecreatedListener onPlayerViewRecreatedListener;
    private boolean isRenderingSwitching = false;

    private final Map<String, String> reusableHeaderMap = new HashMap<>();
    // 由 SDK 解析返回的专用播放请求头
    // 在 doPlay 构建 reusableHeaderMap 时优先覆盖，避免 CDN 302 后鉴权头丢失
    private Map<String, String> mPendingPlaybackHeaders = null;

    private DefaultTrackSelector trackSelector;

    private ScaleMode mCurrentScaleMode = ScaleMode.FILL;

    private Boolean mCurrentUseTexture = null;

    private final Object variantListLock = new Object();
    private volatile List<Variant> variantList = new ArrayList<>();
    private volatile boolean isParsingMasterPlaylist = false;

    private SharedPreferences sp;
    private String currentResolutionLabel = "自适应";

    private static ExecutorService sPlaylistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TVPlayer-PlaylistParser");
        t.setDaemon(true);
        return t;
    });

    // 子模块管理器
    private DecoderModeManager decoderModeManager;
    private VariantManager variantManager;
    private HuyaStreamPlayer huyaStreamPlayer;

    public interface OnPlayerViewRecreatedListener {
        void onPlayerViewRecreated(PlayerView newPlayerView);
    }

    public void setOnPlayerViewRecreatedListener(OnPlayerViewRecreatedListener listener) {
        this.onPlayerViewRecreatedListener = listener;
    }

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
        this.sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        this.healthChecker = new com.tv.live.util.SourceHealthChecker(context);
        mHandler = new Handler(Looper.getMainLooper());

        String savedMode = sp.getString("decoder_mode", "auto");
        switch (savedMode) {
            case "hard":   mDecoderMode = DECODER_MODE_HARD;   break;
            case "soft":   mDecoderMode = DECODER_MODE_SOFT;   break;
            default:       mDecoderMode = DECODER_MODE_AUTO;   break;
        }
        Log.i(TAG, "📺 启动时读取解码器设置: decoder_mode=" + savedMode + " → mDecoderMode=" + mDecoderMode);

        hideChannelRunnable = () -> hideChannelNum();

        stuckCheckRunnable = new Runnable() {
            @Override
            public void run() {
                // 🔧 修复卡顿：用播放器实际状态判断是否真正卡住，而非仅看 position 是否变化。
                // 直播流的 getCurrentPosition() 可能长时间不增长（无 seek 时间轴），
                // 原逻辑会在正常播放时误判"卡住"从而全量重试，放大卡顿。
                if (player == null) {
                    lastPosition = 0;
                    lastPositionUpdateTime = System.currentTimeMillis();
                    mHandler.postDelayed(this, 2000);
                    return;
                }
                try {
                    int state = player.getPlaybackState();
                    if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                        // 播放器已空闲/结束且未恢复，判定为异常，触发重试
                        Log.w(TAG, "检测到播放器异常状态(state=" + state + ")，自动重试...");
                        autoRetry("播放卡住");
                        return;
                    }
                    if (state == Player.STATE_BUFFERING) {
                        // 持续处于缓冲状态超过阈值，判定为卡住（网络/解码问题）
                        long now = System.currentTimeMillis();
                        if (lastPositionUpdateTime == 0) {
                            lastPositionUpdateTime = now;
                        }
                        if (now - lastPositionUpdateTime > STUCK_TIMEOUT) {
                            Log.w(TAG, "检测到长时间缓冲(" + STUCK_TIMEOUT + "ms)，自动重试...");
                            autoRetry("播放卡住");
                            return;
                        }
                    } else {
                        // STATE_READY：正常播放中，更新缓冲起始时间为0，避免误判
                        lastPositionUpdateTime = 0;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "卡住检测异常", e);
                }
                mHandler.postDelayed(this, 2000);
            }
        };

        // 初始化子模块管理器
        decoderModeManager = new DecoderModeManager(context, mHandler);
        variantManager = new VariantManager(new VariantManager.PlaybackCallback() {
            @Override
            public void playUrl(String url) {
                TVPlayerManager.this.playUrlInternal(url);
            }
            @Override
            public Channel getCurrentChannel() {
                return TVPlayerManager.this.getCurrentChannel();
            }
            @Override
            public void dLog(String msg) {
                TVPlayerManager.this.dLog(msg);
            }
        });
        huyaStreamPlayer = new HuyaStreamPlayer(new HuyaStreamPlayer.StreamPlaybackCallback() {
            @Override
            public void onPlayError(String msg) {
                if (listener != null) listener.onPlayError(msg);
            }
            @Override
            public void onSourceFailed() {
                if (sourceFailedListener != null) sourceFailedListener.onSourceFailed();
            }
            @Override
            public void dLog(String msg) {
                TVPlayerManager.this.dLog(msg);
            }
            @Override public void onPlaySuccess() {}
            @Override public void onSeekTo(long positionMs) { if (player != null) player.seekTo(positionMs); }
            @Override public void onPlay() { if (player != null) player.play(); }
            @Override public void onPrepare() { if (player != null) player.prepare(); }
            @Override public void onStuckDetectionStart() { startStuckDetection(); }
            @Override public Context getContext() { return context; }
            @Override public ExoPlayer getPlayer() { return player; }
            @Override public PlayerView getPlayerView() { return playerView; }
            @Override public FrameLayout getSdkPlayerContainer() { return mSdkPlayerContainer; }
            @Override public android.os.Handler getHandler() { return mHandler; }
            @Override public ExecutorService getPlaylistExecutor() { return ensurePlaylistExecutor(); }
            @Override public SharedPreferences getSharedPrefs() { return sp; }
            @Override public String getCurrentChannelName() { return currentChannelName; }
            @Override public Channel getCurrentChannel() { return TVPlayerManager.this.getCurrentChannel(); }
            @Override public void setCurrentUrl(String url) { currentUrl = url; }
            @Override public void setHuyaRoomId(int roomId) { mHuyaRoomId = roomId; }
            @Override public int getHuyaRoomId() { return mHuyaRoomId; }
            @Override public void setPendingHeaders(Map<String, String> headers) { mPendingPlaybackHeaders = headers; }
            @Override public Map<String, String> getPendingHeaders() { return mPendingPlaybackHeaders; }
            @Override public void setReusableHeaderMap(Map<String, String> map) { /* shared */ }
            @Override public Map<String, String> getReusableHeaderMap() { return reusableHeaderMap; }
            @Override public void setCurrentResolutionLabel(String label) { currentResolutionLabel = label; }
            @Override public void ensurePlayerBoundToView() { TVPlayerManager.this.ensurePlayerBoundToView(); }
            @Override public void setMediaSourceAndPrepare(MediaSource source, long seekPosition) {
                if (player != null) {
                    player.setMediaSource(source, true);
                    player.prepare();
                    if (seekPosition > 0) player.seekTo(seekPosition);
                    player.play();
                }
            }
        }, variantManager);

        initPlayer();
    }
    
    private void dLog(String msg) {
        if (sp.getBoolean("log_enable", false)) {
            Log.d(TAG, msg);
            com.tv.live.util.LogCollector.getInstance().addLog(TAG, msg);
        }
    }

    private void logPlayback(String msg) {
        Log.i(TAG, "[PLAY] " + msg);
        com.tv.live.util.LogCollector.getInstance().playback(TAG, msg);
    }

    private void logError(String msg) {
        Log.e(TAG, msg);
        com.tv.live.util.LogCollector.getInstance().error(TAG, msg);
    }

    private void logWarn(String msg) {
        Log.w(TAG, msg);
        com.tv.live.util.LogCollector.getInstance().warn(TAG, msg);
    }

    private void logNetwork(String msg) {
        if (BuildConfig.IS_DEBUG) {
            Log.i(TAG, "[NET] " + msg);
            com.tv.live.util.LogCollector.getInstance().network(TAG, msg);
        }
    }
    
    private void initPlayer() {
        com.tv.live.util.DeviceCapabilities.ensureDetected(context);

        // 🎯 解码器模式 → 是否优先软解（硬解不足时的降级策略）
        // 方案 1：全 MediaCodec，靠 fallback + MediaCodecSelector 切换
        boolean preferSoftware;
        switch (mDecoderMode) {
            case DECODER_MODE_SOFT:
                dLog("【解码器】软解优先模式");
                preferSoftware = true;
                break;
            case DECODER_MODE_HARD:
                dLog("【解码器】硬解优先模式");
                preferSoftware = false;
                break;
            case DECODER_MODE_AUTO:
            default:
                // 取消老电视芯片强制软解：AUTO 模式默认硬解优先（fallback 自动补位）
                preferSoftware = false;
                break;
        }

        DefaultRenderersFactory factory = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(createSmartSelector(preferSoftware))
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

        dLog("【解码器】RenderersFactory = DefaultRenderersFactory"
                + ", preferSoftware=" + preferSoftware
                + ", fallback=true");

        DefaultLoadControl optimizedLoadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    3000,
                    50000,
                    1000,
                    2000
                )
                .build();

        trackSelector = new DefaultTrackSelector(context);

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(factory)
                .setLoadControl(optimizedLoadControl)
                .setTrackSelector(trackSelector)
                .build();

        try {
            List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos("video/avc", false, false);
            int softCount = 0, hardCount = 0;
            for (MediaCodecInfo codec : h264Codecs) {
                if (isSoftwareDecoder(codec)) softCount++;
                else hardCount++;
            }
            dLog("【解码器】软解 " + softCount + " 个，硬解 " + hardCount + " 个"
                    + " → 选中模式: " + (preferSoftware ? "软解优先" : "硬解优先"));
        } catch (Exception ignored) {
        }

        initPlayerListener();
        CookieManager.getInstance().setAcceptCookie(true);
    }

    static boolean isSoftwareDecoder(MediaCodecInfo codec) {
        if (codec == null) return false;
        String name = codec.name;
        if (name == null) return false;
        String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    private static boolean isHlsUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String path = uri.getPath();
            if (TextUtils.isEmpty(path)) return false;
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) return true;
            // 虎牙HLS CDN: *.hls.huya.com/src?... (无m3u8扩展名，但返回HLS流)
            String host = uri.getHost();
            if (host != null && host.contains(".hls.huya.com")) return true;
            return false;
        } catch (Exception e) {
            String lower = url.toLowerCase(Locale.ROOT);
            int q = lower.indexOf('?');
            String beforeQuery = q >= 0 ? lower.substring(0, q) : lower;
            if (beforeQuery.contains(".m3u8") || beforeQuery.contains(".m3u")) return true;
            // 虎牙HLS CDN域名检测
            if (lower.contains(".hls.huya.com")) return true;
            return false;
        }
    }

    private void initPlayerListener() {
        if (playerListener != null) return;
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                logError("播放异常: " + error.getMessage());
                isSwitching = false;

                Throwable rootCause = error.getCause();
                boolean isRedirectError = false;
                int depth = 0;
                while (rootCause != null && depth < 20) {
                    if (rootCause instanceof RedirectFailedException) {
                        isRedirectError = true;
                        break;
                    }
                    rootCause = rootCause.getCause();
                    depth++;
                }

                // 🔧【核心修复】黑屏Bug：识别 Surface 失效异常（IllegalArgumentException
                //    + MediaCodec.native_setSurface/Surface 相关），不应直接走网络错误切台，
                //    而应触发 Surface 重建流程，让 ExoPlayer 重新绑定到新 Surface。
                //
                // 复现链路（来自电视日志 13:28:30 时刻）：
                //  1) 用户按返回键退出应用
                //  2) Coocaa 电视系统强制 disconnect 视频输出（vsyncbridge: Wrapper_disconnect IN）
                //  3) ExoPlayer 在后台尝试 setOutputSurface 但 surface 已失效
                //  4) 抛出 java.lang.IllegalArgumentException at MediaCodec.native_setSurface
                //  5) 原代码直接 autoRetry("播放异常") → 切到下一个频道 → 同样失败 → 黑屏
                //
                // 修复策略：检测到 Surface 失效时，清空 player 并触发 onForeground 重建流程
                if (isSurfaceLostError(error)) {
                    logWarn("检测到 Surface 失效（MediaCodec/Surface 相关），触发重建流程");
                    handleSurfaceLost();
                    if (listener != null) {
                        listener.onPlayError("Surface 失效，正在重建");
                    }
                    return;
                }

                if (isRedirectError) {
                    logError("重定向错误: " + error.getMessage());
                    if (listener != null) {
                        listener.onPlayError(error.getMessage());
                    }
                    return;
                }

                boolean isNetworkError = isNetworkError(error);
                if (isNetworkError && isNetworkUnavailable()) {
                    logWarn("网络不可用，暂不切台，等待网络恢复后重试...");
                    autoRetry("网络不可用", error);
                } else {
                    autoRetry(isNetworkError ? "网络异常" : "播放异常", error);
                }

                if (!isNetworkError && !isRedirectError) {
                    if (healthChecker != null && currentChannel != null) {
                        healthChecker.markFailed(currentUrl, currentChannel);
                    }
                }

                if (listener != null) {
                    listener.onPlayError(error.getMessage());
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    logPlayback("播放就绪 (STATE_READY)");
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();
                    isSwitching = false;
                    if (listener != null) listener.onPlayReady();
                    retryCount = 0;
                    isRetrying = false;
                    startStuckDetection();
                    if (healthChecker != null && !TextUtils.isEmpty(currentUrl)) {
                        healthChecker.markSuccess(currentUrl);
                    }
                    if (initialPlayStartTime == 0) {
                        initialPlayStartTime = System.currentTimeMillis();
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    logPlayback("缓冲中 (STATE_BUFFERING)");
                    if (listener != null) listener.onBuffering();
                    lastPositionUpdateTime = System.currentTimeMillis();
                    bufferCount++;
                    if (!isStalled) {
                        isStalled = true;
                        lastStallStartTime = System.currentTimeMillis();
                    }
                } else if (state == Player.STATE_ENDED) {
                    logPlayback("播放结束 (STATE_ENDED)");
                    if (listener != null) listener.onPlayEnd();
                    autoRetry("播放结束");
                } else if (state == Player.STATE_IDLE) {
                    logPlayback("空闲状态 (STATE_IDLE)");
                    isSwitching = false;
                    if (listener != null) listener.onIdle();
                    updateWakeLock(false);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    logPlayback("开始播放");
                    lastPositionUpdateTime = System.currentTimeMillis();
                    if (isStalled) {
                        isStalled = false;
                        long stallDuration = System.currentTimeMillis() - lastStallStartTime;
                        totalStallTime += stallDuration;
                        if (stallDuration >= MIN_STALL_WARN_THRESHOLD) {
                            logWarn("严重卡顿结束，时长：" + stallDuration + "ms");
                        } else {
                            logPlayback("短暂缓冲恢复，时长：" + stallDuration + "ms");
                        }
                    }
                } else {
                    logPlayback("暂停播放");
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                logPlayback("视频分辨率变化：" + videoSize.width + "×" + videoSize.height);
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }

    // 🔴【核心修复】在 trySwitchBackup 里拦截虎牙房间号死循环
    private boolean trySwitchBackup() {
        if (currentChannel == null || currentChannel.getBackupUrls().isEmpty()) {
            return false;
        }
        if (backupRetryIndex < 0) {
            backupRetryIndex = 0;
        } else {
            backupRetryIndex++;
        }
        List<String> backups = currentChannel.getBackupUrls();
        if (backupRetryIndex >= backups.size()) {
            backupRetryIndex = -1;
            return false;
        }
        String backupUrl = backups.get(backupRetryIndex);
        
        // 🔴 如果备用源也是虎牙房间号，直接跳过，递归尝试下一个！
        if (isHuyaRoomUrl(backupUrl)) {
            Log.w(TAG, "备用源是虎牙房间号，跳过！尝试下一个...");
            return trySwitchBackup();
        }
        
        dLog("尝试切换到备用源：" + backupUrl);
        playUrlInternal(backupUrl);
        return true;
    }

    private void startStuckDetection() {
        mHandler.removeCallbacks(stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        mHandler.postDelayed(stuckCheckRunnable, 2000);
    }

    private void stopStuckDetection() {
        mHandler.removeCallbacks(stuckCheckRunnable);
    }

    private void cancelRetry() {
        if (retryRunnable != null) {
            mHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        isRetrying = false;
    }

    private void autoRetry(String reason) {
        autoRetry(reason, null);
    }

    private void autoRetry(String reason, Throwable cause) {
        if (cause != null) {
            Throwable t = cause;
            int depth = 0;
            while (t != null && depth < 20) {
                if (t instanceof RedirectFailedException) return;
                t = t.getCause();
                depth++;
            }
        }
        if (cause == null && reason != null && reason.contains("RedirectFailedException")) {
            return;
        }
        if (isRetrying) return;

        boolean isNetworkError = isNetworkError(cause) || (reason != null && (reason.contains("网络") || reason.contains("卡住")));
        int maxRetry = isNetworkError ? MAX_RETRY_COUNT_NETWORK : MAX_RETRY_COUNT;

        if (retryCount >= maxRetry) {
            logWarn("重试次数已达上限：" + maxRetry + "（" + (isNetworkError ? "网络错误" : "源错误") + "），判定为失效源");

            if (!isNetworkError || !isNetworkUnavailable()) {
                boolean backupSwitched = trySwitchBackup();
                if (!backupSwitched) {
                    long now = System.currentTimeMillis();
                    if (now - lastSourceFailedTime < SOURCE_FAILED_COOLDOWN_MS) {
                        logWarn("切台冷却中（" + (SOURCE_FAILED_COOLDOWN_MS / 1000) + "s），跳过本次自动切台");
                        return;
                    }
                    lastSourceFailedTime = now;
                    if (sourceFailedListener != null) {
                        mHandler.post(() -> sourceFailedListener.onSourceFailed());
                    }
                }
            } else {
                logWarn("网络不可用，不切台不切源，保持当前频道等待网络恢复");
            }
            return;
        }
        isRetrying = true;
        retryCount++;
        long delayMs = isNetworkError ? (2000L * (1L << (retryCount - 1))) : 3000L;
        logWarn("自动重试（第" + retryCount + "/" + maxRetry + "次），延迟" + delayMs + "ms，原因：" + reason);
        retryRunnable = () -> {
            isRetrying = false;
            if (!TextUtils.isEmpty(currentUrl)) {
                // 如果是虎牙流 URL，重新触发完整解析（获取新签名+headers）
                if (currentUrl.contains(".huya.com") && mHuyaRoomId > 0) {
                    Log.d(TAG, "【虎牙】重试：重新触发解析获取新签名, roomId=" + mHuyaRoomId);
                    playHuyaStream(mHuyaRoomId, 0);
                } else {
                    playUrlInternal(currentUrl);
                }
            }
            retryRunnable = null;
        };
        mHandler.postDelayed(retryRunnable, delayMs);
    }

    /**
     * 检测错误是否为 Surface 失效类（电视系统强制 disconnect 视频输出后常见）
     * 特征：java.lang.IllegalArgumentException，且涉及 MediaCodec.setSurface 或 Surface 相关 native 方法
     */
    private boolean isSurfaceLostError(Throwable throwable) {
        if (throwable == null) return false;
        Throwable t = throwable;
        int depth = 0;
        while (t != null && depth < 20) {
            if (t instanceof IllegalArgumentException) {
                // 匹配 MediaCodec.setOutputSurface / native_setSurface 调用栈
                for (StackTraceElement elem : t.getStackTrace()) {
                    String cls = elem.getClassName();
                    String method = elem.getMethodName();
                    if ((cls.contains("MediaCodec") && method.contains("Surface"))
                            || (cls.contains("SynchronousMediaCodecAdapter"))
                            || (cls.contains("MediaCodecVideoRenderer"))) {
                        return true;
                    }
                }
            }
            t = t.getCause();
            depth++;
        }
        return false;
    }

    /**
     * Surface 失效处理：清空 ExoPlayer 的 surface 绑定，触发 playerView 重建 surface，
     * 然后重新绑定播放器。这是修复"按返回退出再重开应用黑屏"的关键：
     * - 不走 autoRetry 切台（切台无法解决 surface 失效问题）
     * - 直接重置 surface 状态，让 onForeground 的 retryResumeAfterSurface 重新生效
     */
    private void handleSurfaceLost() {
        try {
            Log.w(TAG, "handleSurfaceLost: 开始处理 Surface 失效");
            // 1. 解除 player 与 view 的绑定，让 PlayerView 重新创建 surface
            if (playerView != null) {
                try {
                    playerView.setPlayer(null);
                } catch (Exception e) {
                    Log.w(TAG, "setPlayer(null) 异常: " + e.getMessage());
                }
            }
            // 2. 重置 surface 状态
            surfaceReady = false;
            surfaceCallbackBound = false;
            pendingBindPlayer = true;

            // 3. 主动触发 PlayerView 重建 surface（invalidate + requestLayout）
            if (playerView != null) {
                try {
                    playerView.post(() -> {
                        try {
                            playerView.setVisibility(View.VISIBLE);
                            playerView.requestLayout();
                            playerView.invalidate();
                        } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
            }

            // 4. 延迟重新绑定并播放（等待新 surface 创建）
            if (mHandler != null) {
                mHandler.postDelayed(() -> {
                    try {
                        if (player == null || playerView == null) return;
                        // 重新注册 SurfaceHolder.Callback（surfaceCallbackBound 已被重置为 false）
                        bindSurfaceCallback(playerView);
                        // 重新绑定 player 到 view
                        if (playerView.getPlayer() != player) {
                            playerView.setPlayer(player);
                        }
                        // 重置 player 并重放当前 URL（从最新状态恢复）
                        if (!TextUtils.isEmpty(currentUrl)) {
                            Log.d(TAG, "Surface 重建完成，重新播放: " + currentUrl);
                            // 注意：playUrlInternal 会重新 prepare+play，无需手动 reset
                            playUrlInternal(currentUrl);
                        }
                        pendingBindPlayer = false;
                    } catch (Exception e) {
                        Log.e(TAG, "Surface 重建后恢复播放异常: " + e.getMessage(), e);
                    }
                }, 500); // 给 Surface 重建留 500ms
            }
        } catch (Exception e) {
            Log.e(TAG, "handleSurfaceLost 异常", e);
        }
    }

    private boolean isNetworkError(Throwable throwable) {
        if (throwable == null) return false;
        Throwable t = throwable;
        int depth = 0;
        while (t != null && depth < 20) {
            String className = t.getClass().getName();
            String msg = t.getMessage();
            if (msg == null) msg = "";
            if (className.contains("SocketTimeoutException")
                    || className.contains("ConnectException")
                    || className.contains("UnknownHostException")
                    || className.contains("NetworkOnMainThreadException")
                    || msg.contains("timeout")
                    || msg.contains("timed out")
                    || msg.contains("Connection")
                    || msg.contains("connection")
                    || msg.contains("unreachable")
                    || msg.contains("ECONNRESET")
                    || msg.contains("ECONNREFUSED")) {
                return true;
            }
            t = t.getCause();
            depth++;
        }
        return false;
    }

    private boolean isNetworkUnavailable() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info == null || !info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    public void setDecoderMode(int mode) {
        if (mDecoderMode == mode) return;
        mDecoderMode = mode;
        dLog("手动切换解码器模式：" + mode);
        if (player != null) performDecoderSwitch();
    }

    private void performDecoderSwitch() {
        if (isSwitching) {
            Log.w(TAG, "正在解码器切换中，忽略当前请求");
            return;
        }
        isSwitching = true;
        long currentPosition = player != null ? player.getCurrentPosition() : 0;

        try {
            mHandler.removeCallbacks(stuckCheckRunnable);
            mHandler.removeCallbacks(retryRunnable);
            mHandler.removeCallbacks(hideChannelRunnable);
            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                    playerListener = null;
                }
                player.release();
                player = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放旧播放器异常", e);
        }

        initPlayer();
        final boolean hasUrl = !TextUtils.isEmpty(currentUrl);
        if (playerView != null) {
            mHandler.post(() -> {
                try {
                    if (playerView != null && player != null) {
                        playerView.setPlayer(player);
                    }
                } finally {
                    if (!hasUrl) isSwitching = false;
                }
            });
        }
        if (hasUrl) {
            retryCount = 0;
            isRetrying = false;

            if (mDecoderMode == DECODER_MODE_SOFT) {
                Toast.makeText(context, "已切换至 软解模式", Toast.LENGTH_SHORT).show();
            } else if (mDecoderMode == DECODER_MODE_HARD) {
                Toast.makeText(context, "已切换至 硬解模式", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "已切换至 自动模式", Toast.LENGTH_SHORT).show();
            }

            playUrlInternal(currentUrl, currentPosition);
            mHandler.postDelayed(() -> { isSwitching = false; }, 30000);
        } else if (playerView == null) {
            isSwitching = false;
        }
    }

    public int getDecoderMode() {
        return mDecoderMode;
    }

    /**
     * 🧠 智能 MediaCodecSelector —— 整合黑名单过滤 + 硬解/软解优先级
     *
     * 1. 排除已知有缺陷的硬解器（DeviceCapabilities 里的黑名单）
     * 2. preferSoftware=true 时把系统软解器排到最前
     * 3. 硬解器内部按 CTS 认证优先排列
     * 4. fallback 自动补位
     */
    private MediaCodecSelector createSmartSelector(boolean preferSoftware) {
        return new MediaCodecSelector() {
            @Override
            public java.util.List<MediaCodecInfo> getDecoderInfos(
                    String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
                    throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {

                java.util.List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);

                // 1. 过滤黑名单
                java.util.List<String> blacklist = new java.util.ArrayList<>();
                blacklist.add("omx.ms.");
                blacklist.add("c2.mstar.");
                blacklist.add("c2.amlogic.avc.decoder.awesome");
                blacklist.add("omx.hisi.video.decoder");

                java.util.List<MediaCodecInfo> filtered = new java.util.ArrayList<>();
                for (MediaCodecInfo info : all) {
                    String name = info.name.toLowerCase(java.util.Locale.ROOT);
                    boolean blocked = false;
                    for (String prefix : blacklist) {
                        if (name.startsWith(prefix)) { blocked = true; break; }
                    }
                    if (!blocked) filtered.add(info);
                }

                if (filtered.isEmpty()) filtered.addAll(all);

                // 2. 软解优先：把系统软解器排到最前
                if (preferSoftware) {
                    java.util.List<MediaCodecInfo> soft = new java.util.ArrayList<>();
                    java.util.List<MediaCodecInfo> hard = new java.util.ArrayList<>();
                    for (MediaCodecInfo info : filtered) {
                        if (isSoftwareDecoder(info)) soft.add(info);
                        else hard.add(info);
                    }
                    soft.addAll(hard);
                    return soft;
                }
                return filtered;
            }
        };
    }

    public void registerDecoderModeReceiver() {
        if (decoderReceiverRegistered) return;
        try {
            decoderModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String modeStr = sp.getString("decoder_mode", "auto");
                        int mode = DECODER_MODE_AUTO;
                        if ("hard".equals(modeStr)) mode = DECODER_MODE_HARD;
                        else if ("soft".equals(modeStr)) mode = DECODER_MODE_SOFT;
                        setDecoderMode(mode);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
            ContextCompat.registerReceiver(context, decoderModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            decoderReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册解码器广播失败", e);
        }
    }

    public void unregisterDecoderModeReceiver() {
        if (!decoderReceiverRegistered) return;
        try {
            if (decoderModeReceiver != null) {
                context.unregisterReceiver(decoderModeReceiver);
                decoderModeReceiver = null;
            }
            decoderReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "注销解码器广播失败", e);
        }
    }

    private void switchRenderer(boolean useTexture) {
        if (player == null || playerView == null || context == null) return;
        if (mCurrentUseTexture != null && mCurrentUseTexture == useTexture) {
            if (playerView.getPlayer() != player) playerView.setPlayer(player);
            return;
        }
        ViewParent rawParent = playerView.getParent();
        if (!(rawParent instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) rawParent;

        isRenderingSwitching = true;
        bufferCount = 0;
        boolean wasPlaying = player.isPlaying();
        boolean useController = playerView.getUseController();
        ViewGroup.LayoutParams layoutParams = playerView.getLayoutParams();

        int index = parent.indexOfChild(playerView);
        int styleRes = useTexture ? R.style.PlayerView_Texture : R.style.PlayerView_Surface;
        ContextThemeWrapper themedContext = new ContextThemeWrapper(context, styleRes);
        PlayerView newPlayerView = new PlayerView(themedContext);
        newPlayerView.setLayoutParams(layoutParams);
        newPlayerView.setUseController(useController);
        newPlayerView.setKeepContentOnPlayerReset(true);

        int resizeMode;
        switch (mCurrentScaleMode) {
            case FILL:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
                break;
            case ZOOM:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
                break;
            case FIT:
            default:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
                break;
        }
        newPlayerView.setResizeMode(resizeMode);

        newPlayerView.setPlayer(player);
        parent.addView(newPlayerView, index + 1, layoutParams);

        // 🔧 修复黑屏：addView 后立即为新 PlayerView 注册 SurfaceHolder.Callback，
        // 避免新 SurfaceView 在回调注册前就完成 surfaceCreated 分发导致 surfaceReady 永久 false。
        if (!useTexture) {
            surfaceCallbackBound = false;
            bindSurfaceCallback(newPlayerView);
        }

        if (onPlayerViewRecreatedListener != null) {
            onPlayerViewRecreatedListener.onPlayerViewRecreated(newPlayerView);
        }

        PlayerView oldPlayerView = playerView;
        playerView = newPlayerView;
        playerView.requestFocus();

        mHandler.postDelayed(() -> {
            oldPlayerView.setPlayer(null);
            parent.removeView(oldPlayerView);
            if (wasPlaying && player != null && !player.isPlaying()) {
                player.play();
            }
        }, 300);

        mCurrentUseTexture = useTexture;
        isRenderingSwitching = false;
    }

    public void registerRendererModeReceiver() {
        if (rendererReceiverRegistered) return;
        try {
            rendererModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.RENDERER_TYPE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String mode = sp.getString("renderer_type", "surface");
                        if (playerView != null) switchRenderer("texture".equals(mode));
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.RENDERER_TYPE_CHANGED");
            ContextCompat.registerReceiver(context, rendererModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            rendererReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册渲染方式广播失败", e);
        }
    }

    public void unregisterRendererModeReceiver() {
        if (!rendererReceiverRegistered) return;
        try {
            if (rendererModeReceiver != null) {
                context.unregisterReceiver(rendererModeReceiver);
                rendererModeReceiver = null;
            }
            rendererReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "注销渲染方式广播失败", e);
        }
    }

    private boolean surfaceReady = false;
    private boolean pendingBindPlayer = false;
    // 🔧 修复黑屏：标记当前 playerView 的 SurfaceHolder.Callback 是否已注册，
    // 避免 switchRenderer 重建视图后回调注册竞态丢失导致 surfaceReady 永久 false
    private boolean surfaceCallbackBound = false;

    public void onForeground() {
        // ExoPlayer
        try {
            if (player != null && playerView != null) {
                if (surfaceReady) {
                    if (playerView.getPlayer() != player) {
                        playerView.setPlayer(player);
                    }
                    player.play();
                } else {
                    pendingBindPlayer = true;
                    // 长时间后台后 Surface 可能已被系统回收，主动诱使重建并延迟重试恢复播放
                    try {
                        playerView.post(() -> {
                            playerView.setVisibility(View.VISIBLE);
                            playerView.requestLayout();
                            playerView.invalidate();
                        });
                    } catch (Exception ignored) {}

                    // 🔧 修复：切前台时如果 Surface 未就绪，先强制绑定播放器到视图，
                    // 确保 PlayerView 内部 Surface 重建后能自动恢复渲染，避免黑屏。
                    // 部分高版本安卓（10-16）Surface 重建时机晚于 onResume，
                    // 如果不提前绑定，surfaceCreated 回调可能不会触发。
                    try {
                        if (playerView.getPlayer() != player) {
                            playerView.setPlayer(player);
                        }
                        player.play();
                    } catch (Exception ignored) {}

                    retryResumeAfterSurface();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "切前台异常", e);
        }
    }

    // 延迟重试：等待 Surface 重建，就绪后恢复播放，避免回前台黑屏
    // 🔧 增强：最多重试10次（3秒），避免无限循环；每次重试都尝试强制绑定+播放
    private void retryResumeAfterSurface() {
        try {
            if (mHandler == null) return;
            final int[] retryCount = {0};
            final int maxRetries = 10;
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        retryCount[0]++;
                        if (player != null && playerView != null) {
                            if (surfaceReady) {
                                if (playerView.getPlayer() != player) {
                                    playerView.setPlayer(player);
                                }
                                if (!player.isPlaying()) {
                                    player.play();
                                }
                                pendingBindPlayer = false;
                                Log.d(TAG, "Surface已重建，播放已恢复（重试第" + retryCount[0] + "次）");
                                return;
                            }
                            // 🔧 每次重试都尝试强制绑定+播放，避免 Surface 回调丢失
                            try {
                                if (playerView.getPlayer() != player) {
                                    playerView.setPlayer(player);
                                }
                                if (!player.isPlaying()) {
                                    player.play();
                                }
                                playerView.setVisibility(View.VISIBLE);
                                playerView.requestLayout();
                            } catch (Exception ignored) {}
                            // 尚未就绪，继续重试（最多 maxRetries 次）
                            if (pendingBindPlayer && retryCount[0] < maxRetries) {
                                mHandler.postDelayed(this, 300);
                            } else if (retryCount[0] >= maxRetries) {
                                Log.w(TAG, "Surface恢复重试已达上限(" + maxRetries + "次)，停止重试");
                                pendingBindPlayer = false;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "恢复播放重试异常", e);
                    }
                }
            }, 300);
        } catch (Exception e) {
            Log.e(TAG, "启动恢复重试异常", e);
        }
    }

    public void onBackground() {
        // ExoPlayer
        try {
            if (player != null) {
                // 🔧 修复：切后台时不直接暂停，而是标记待恢复状态。
                // 在高版本安卓（10-16）上，onPause → Surface销毁 → Surface重建 → onResume
                // 的流程中，如果 onBackground 暂停了播放器且 Surface 销毁了，
                // 回前台时 onForeground 需要等待 Surface 重建才能恢复，容易黑屏。
                // 改为保持 playWhenReady=true，让播放器在 Surface 重建后自动恢复渲染。
                // 实际的后台暂停由系统 Surface 销毁自动停止渲染来实现，
                // 不需要我们主动 pause。
                player.pause();
            }
            if (playerView != null && surfaceReady) {
                pendingBindPlayer = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "切后台异常", e);
        }
    }

    /**
     * 确保播放器已绑定到 PlayerView，防止黑屏
     * 在 setMediaSource/prepare/play 前调用
     */
    private void ensurePlayerBoundToView() {
        try {
            if (player != null && playerView != null) {
                if (playerView.getPlayer() != player) {
                    playerView.setPlayer(player);
                    Log.d(TAG, "播放器已绑定到视图");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "绑定播放器到视图失败", e);
        }
    }

    public void detachPlayerView() {
        try {
            if (playerView != null) {
                playerView.setPlayer(null);
                surfaceReady = false;
                pendingBindPlayer = true;
                surfaceCallbackBound = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "解绑PlayerView异常", e);
        }
    }

    /**
     * 为指定 PlayerView 的 SurfaceView 注册 SurfaceHolder.Callback。
     * 修复黑屏：确保在 addView 后立即注册回调（而非依赖外部滞后补注册），
     * 否则新 SurfaceView 可能在回调注册前就完成 surfaceCreated 分发，
     * 导致 surfaceReady 永久 false → 播放器有源但不渲染 → 黑屏。
     */
    private void bindSurfaceCallback(final PlayerView view) {
        if (view == null) return;
        if (surfaceCallbackBound) return; // 已注册，避免重复
        View videoSurfaceView = view.getVideoSurfaceView();
        if (videoSurfaceView instanceof android.view.SurfaceView) {
            android.view.SurfaceView surfaceView = (android.view.SurfaceView) videoSurfaceView;
            surfaceView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(android.view.SurfaceHolder holder) {
                    surfaceReady = true;
                    if (player != null) {
                        if (view.getPlayer() != player) {
                            view.setPlayer(player);
                        }
                        if (!player.isPlaying()) {
                            player.play();
                        }
                    }
                    pendingBindPlayer = false;
                    Log.d(TAG, "Surface创建成功，播放器已绑定视图并持续播放");
                }

                @Override
                public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {
                    Log.d(TAG, "Surface变化: " + width + "x" + height);
                }

                @Override
                public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                    surfaceReady = false;
                    pendingBindPlayer = true;
                    Log.d(TAG, "Surface销毁，播放器保持运行不解绑");
                    // 🔧 Surface 销毁时不要暂停播放器，保持 playWhenReady=true，
                    // 这样 Surface 重建后播放器会自动恢复渲染，避免黑屏。
                }
            });
            surfaceCallbackBound = true;
        }
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;

        // 保存 Activity 引用用于 SDK 播放器
        if (view.getContext() instanceof Activity) {
            mActivity = (Activity) view.getContext();
        }
        // 查找 SDK 播放器容器
        if (mActivity != null) {
            FrameLayout container = mActivity.findViewById(R.id.sdk_player_container);
            if (container != null) {
                mSdkPlayerContainer = container;
            }
        }

        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String rendererMode = sp.getString("renderer_type", "surface");
        boolean useTexture = "texture".equals(rendererMode);
        switchRenderer(useTexture);

        if (useTexture) {
            playerView.setPlayer(player);
            surfaceReady = true;
            pendingBindPlayer = false;
        } else {
            // 🔧 修复黑屏：走 bindSurfaceCallback 注册 SurfaceHolder.Callback。
            // 若 switchRenderer 重建视图时已注册（surfaceCallbackBound=true）则跳过，避免重复。
            bindSurfaceCallback(playerView);
            View videoSurfaceView = playerView.getVideoSurfaceView();
            if (videoSurfaceView instanceof android.view.SurfaceView) {
                android.view.SurfaceView surfaceView = (android.view.SurfaceView) videoSurfaceView;
                android.view.Surface surface = surfaceView.getHolder().getSurface();
                if (surface != null && surface.isValid()) {
                    surfaceReady = true;
                    if (player != null && playerView.getPlayer() != player) {
                        playerView.setPlayer(player);
                    }
                    pendingBindPlayer = false;
                } else {
                    surfaceReady = false;
                    pendingBindPlayer = true;
                }
            } else {
                playerView.setPlayer(player);
                surfaceReady = true;
                pendingBindPlayer = false;
            }
        }

        playerView.setUseController(false);
    }

    /**
     * 获取 SDK 播放器容器（供外部绑定手势/触摸监听）
     * 当切换到 SDK 播放器时，原 PlayerView 会被遮挡，
     * 外部需要把触摸监听也绑到 SDK 容器上，否则手势面板无法唤起。
     */
    public FrameLayout getSdkPlayerContainer() {
        return mSdkPlayerContainer;
    }

    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) playerView.setKeepScreenOn(enable);
    }

    public void playUrl(String url) {
        playUrl(url, null, null);
    }

    public void playUrl(String url, String channelName) {
        playUrl(url, channelName, null);
    }

    public void playUrl(String url, String channelName, Channel channel) {
        if (!TextUtils.isEmpty(channelName)) this.currentChannelName = channelName;
        this.currentChannel = channel;
        this.backupRetryIndex = -1;
        if (channel != null && TextUtils.isEmpty(this.currentChannelName)) {
            this.currentChannelName = channel.getName();
        }
        cancelRetry();
        retryCount = 0;
        isRetrying = false;
        initialPlayStartTime = 0;
        resetPerformanceStats();
        playUrlInternal(url, 0);
    }

    public Channel getCurrentChannel() {
        return currentChannel;
    }

    public interface OnSourceFailedListener {
        void onSourceFailed();
    }

    public void setOnSourceFailedListener(OnSourceFailedListener listener) {
        this.sourceFailedListener = listener;
    }

    private void resetPerformanceStats() {
        bufferCount = 0;
        totalStallTime = 0;
        isStalled = false;
        lastStallStartTime = 0;
    }

    // ================================================================
    // 🔧 核心修改：虎牙房间号检测与解析
    // ================================================================
    private boolean isHuyaRoomUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) return false;
            if (!host.contains("huya.com") && !host.contains("huya.cn")) return false;
            String path = uri.getPath();
            if (TextUtils.isEmpty(path)) return false;
            String roomIdStr = path.replace("/", "").trim();
            return roomIdStr.matches("\\d+");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isHuyaProtocolUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.startsWith("huya://room/");
    }

    private void playUrlInternal(String url) {
        playUrlInternal(url, 0);
    }

    private void playUrlInternal(String url, long initialSeekPosition) {
        // 🔧【缓存治理】每次起播前，后台快速巡检一次 ExoPlayer 临时分片目录的水位
        try { AppCacheInspector.onBeforePlayback(context); } catch (Throwable ignored) {}

        if (isHuyaProtocolUrl(url)) {
            String roomIdStr = url.replace("huya://room/", "").trim();
            int roomId;
            try {
                roomId = Integer.parseInt(roomIdStr);
            } catch (NumberFormatException e) {
                autoRetry("虎牙房间号格式错误: " + url);
                return;
            }
            // 优先使用 SDK 解析
            playHuyaStream(roomId, initialSeekPosition);
            return;
        }
        if (isHuyaRoomUrl(url)) {
            String roomIdStr = url.replaceAll(".*/(\\d+).*", "$1");
            int roomId;
            try {
                roomId = Integer.parseInt(roomIdStr);
            } catch (NumberFormatException e) {
                autoRetry("虎牙房间号格式错误: " + url);
                return;
            }
            // 优先使用 SDK 解析
            playHuyaStream(roomId, initialSeekPosition);
            return;
        }
        doPlay(url, initialSeekPosition);
    }

    /**
     * 虎牙流播放统一入口
     * 使用 SDK 解析获取线路+清晰度+URL，全部交给 ExoPlayer 播放。
     * SDK 不可用/解析失败时直接提示错误。
     *
     * 🟢【并行加载优化】：在 AppCoreManager 加载直播源时，已与直播源显示并行地
     * 在后台调用 HuyaSDKParser.preloadRooms() 预解析虎牙房间，命中缓存时 100~200ms
     * 内即可进入播放（之前=点频道后才实时解析，需 3s~30s 等待）。
     */
    private void playHuyaStream(int roomId, long initialSeekPosition) {
        mHuyaRoomId = roomId;
        final long parseStartTs = System.currentTimeMillis();

        // 隐藏 SDK 容器，确保 ExoPlayer 可见
        if (mSdkPlayerContainer != null) {
            mSdkPlayerContainer.setVisibility(View.GONE);
        }
        if (playerView != null) {
            playerView.setVisibility(View.VISIBLE);
        }

        // 🔴【关键】切频道前先清掉上一个虎牙房间的 variantList/backupUrls 缓存，
        // 避免前一个频道的清晰度/线路列表残留到新房间。
        synchronized (variantListLock) {
            variantList.clear();
        }
        if (currentChannel != null) {
            try {
                currentChannel.clearBackupUrls();
            } catch (Exception ignored) {}
        }

        // 仅使用 SDK 解析完整线路+码率信息
        if (!HuyaSDKParser.isSDKAvailable()) {
            Log.e(TAG, "【虎牙】SDK 不可用, roomId=" + roomId);
            mHandler.post(() -> {
                Toast.makeText(context, "虎牙 SDK 不可用，无法解析直播源", Toast.LENGTH_SHORT).show();
                if (sourceFailedListener != null) {
                    sourceFailedListener.onSourceFailed();
                }
            });
            return;
        }

        // 🟢【性能诊断】：入口先检查是否命中缓存（parseFull 内部也会做，但这里打印
        //   用户点击→开始播放的耗时感知日志，方便定位首帧速度）
        HuyaSDKParser.CachedStreams cached = HuyaSDKParser.getCachedStreams(roomId);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            long ageSec = (System.currentTimeMillis() - cached.timestamp) / 1000;
            Log.i(TAG, "🚀【虎牙并行加载】命中预解析缓存！房间=" + roomId
                    + "，缓存年龄=" + ageSec + "s，流数=" + cached.streams.size()
                    + "，即将瞬时启动播放（跳过实时SDK等待）");
        } else {
            Log.w(TAG, "⚠️【虎牙并行加载】未命中预解析缓存（房间=" + roomId
                    + "），退化为实时解析，等待时间约 3~30s。"
                    + " 通常是：该房间在预解析前30名之外 / 直播源刚加载完用户就立刻点击 / 缓存已过期（>60s）");
        }

        Log.d(TAG, "【虎牙】使用 SDK 全量解析, roomId=" + roomId);
        HuyaSDKParser.parseFull(roomId, new HuyaSDKParser.OnSDKFullResultListener() {
            @Override
            public void onSuccess(HuyaSDKParser.HuyaStreamInfo defaultStream,
                                  java.util.List<HuyaSDKParser.HuyaStreamInfo> allStreams,
                                  java.util.List<String> lines) {
                long costMs = System.currentTimeMillis() - parseStartTs;
                HuyaSDKParser.CachedStreams cs = HuyaSDKParser.getCachedStreams(roomId);
                boolean fromPreload = (cs != null && cs.streams == allStreams)
                        || (costMs < 300);  // 缓存命中几乎瞬时返回，<300ms 基本是命中
                Log.i(TAG, "⚡【虎牙解析耗时】" + costMs + "ms, roomId=" + roomId
                        + "，缓存命中=" + fromPreload
                        + "，流数=" + (allStreams != null ? allStreams.size() : 0));
                // 🟢【新增】使用数组包装器，允许在轮询逻辑中修改 defaultStream 引用
                final HuyaSDKParser.HuyaStreamInfo[] streamHolder = {defaultStream};

                // ================================================================
                // 🟢【电视适配】根据设备能力（软解/老电视 → 优先 720p）选择默认流
                //   - 在线路轮询之前应用，确保默认流的码率已适配设备
                //   - 后续 lineIndex 轮询只在适配设备后的码率基础上切换线路
                // ================================================================
                if (defaultStream != null && allStreams != null && !allStreams.isEmpty()) {
                    // 找到 defaultStream 所在线路的所有码率流
                    java.util.List<HuyaSDKParser.HuyaStreamInfo> sameLineStreams = new java.util.ArrayList<>();
                    for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                        if (s != null && s.lineIndex == defaultStream.lineIndex
                                && !TextUtils.isEmpty(s.getPlayUrl())) {
                            sameLineStreams.add(s);
                        }
                    }
                    HuyaSDKParser.HuyaStreamInfo deviceBest =
                            HuyaSDKParser.selectBestStreamForDevice(sameLineStreams);
                    if (deviceBest != null && deviceBest != defaultStream) {
                        Log.i(TAG, "【电视适配】默认流已调整: " + defaultStream.bitRateDisplayName
                                + " → " + deviceBest.bitRateDisplayName
                                + " (适配目标高度="
                                + (com.tv.live.util.DeviceCapabilities.isTv() ? 720 : "自动") + "p)");
                        streamHolder[0] = deviceBest;
                    }
                }

                // ================================================================
                // 🟢【新增】线路轮询逻辑：多次进入同一直播间时自动切换线路
                // ================================================================
                if (streamHolder[0] == null || TextUtils.isEmpty(streamHolder[0].getPlayUrl())) {
                    Log.e(TAG, "【虎牙】SDK 解析返回空默认地址");
                    mHandler.post(() -> {
                        Toast.makeText(context, "虎牙 SDK 解析失败：返回空地址", Toast.LENGTH_SHORT).show();
                        if (sourceFailedListener != null) {
                            sourceFailedListener.onSourceFailed();
                        }
                    });
                    return;
                }

                if (allStreams != null && allStreams.size() > 1) {
                    java.util.Set<Integer> lineIndexSet = new java.util.TreeSet<>();
                    for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                        if (!TextUtils.isEmpty(s.getPlayUrl())) {
                            lineIndexSet.add(s.lineIndex);
                        }
                    }
                    java.util.List<Integer> uniqueLineIndices = new java.util.ArrayList<>(lineIndexSet);

                    if (uniqueLineIndices.size() > 1) {
                        String linePrefKey = "huya_line_poll_" + roomId;
                        int lastLineIdx = sp.getInt(linePrefKey, uniqueLineIndices.get(0));

                        int currentPos = -1;
                        for (int i = 0; i < uniqueLineIndices.size(); i++) {
                            if (uniqueLineIndices.get(i) == lastLineIdx) {
                                currentPos = i;
                                break;
                            }
                        }
                        if (currentPos == -1) currentPos = 0;

                        int nextPos = (currentPos + 1) % uniqueLineIndices.size();
                        int targetLineIndex = uniqueLineIndices.get(nextPos);

                        if (targetLineIndex != streamHolder[0].lineIndex) {
                            Log.d(TAG, "【虎牙】线路轮询：切换到线路 " + targetLineIndex);
                            HuyaSDKParser.HuyaStreamInfo targetStream = null;
                            for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                                if (s.lineIndex == targetLineIndex && !TextUtils.isEmpty(s.getPlayUrl())) {
                                    if (s.isDefaultBitrate) {
                                        targetStream = s;
                                        break;
                                    }
                                    if (targetStream == null || s.bitRate > targetStream.bitRate) {
                                        targetStream = s;
                                    }
                                }
                            }
                            if (targetStream != null) {
                                streamHolder[0] = targetStream;
                            }
                        }
                        sp.edit().putInt(linePrefKey, streamHolder[0].lineIndex).apply();
                    }
                }
                // ================================================================
                // 【新增结束】
                // ================================================================

                // 步骤1：填充 variantList（清晰度选择 UI 用）
                // - 存储所有线路的所有码率（Variant 带 huyaLineIndex 区分）
                // - 当前线路的 variant 按码率降序排列，当前 URL 排第一
                // ================================================================
                java.util.List<Variant> allVariants = new java.util.ArrayList<>();
                if (allStreams != null) {
                    for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                        if (!TextUtils.isEmpty(s.getPlayUrl())) {
                            allVariants.add(Variant.fromHuyaStreamInfo(s));
                        }
                    }
                }
                final String defaultUrl = streamHolder[0].getPlayUrl();
                mCurrentHuyaLineIndex = streamHolder[0].lineIndex;

                // 按线路分组 + 各线路按码率降序
                java.util.Map<Integer, java.util.List<Variant>> lineGroups = new java.util.TreeMap<>();
                for (Variant v : allVariants) {
                    java.util.List<Variant> group = lineGroups.get(v.huyaLineIndex);
                    if (group == null) {
                        group = new java.util.ArrayList<>();
                        lineGroups.put(v.huyaLineIndex, group);
                    }
                    group.add(v);
                }
                for (java.util.List<Variant> group : lineGroups.values()) {
                    Collections.sort(group, (a, b) -> Integer.compare(b.bandwidth, a.bandwidth));
                }

                // 当前线路的 URL 排第一
                java.util.List<Variant> currentLineVariants = lineGroups.get(mCurrentHuyaLineIndex);
                if (currentLineVariants != null) {
                    int defIdx = -1;
                    for (int i = 0; i < currentLineVariants.size(); i++) {
                        if (defaultUrl.equals(currentLineVariants.get(i).url)) { defIdx = i; break; }
                    }
                    if (defIdx > 0) {
                        Variant defV = currentLineVariants.remove(defIdx);
                        currentLineVariants.add(0, defV);
                    }
                }

                synchronized (variantListLock) {
                    variantList.clear();
                    variantList.addAll(allVariants);
                }
                currentResolutionLabel = currentLineVariants != null && !currentLineVariants.isEmpty()
                        ? currentLineVariants.get(0).getDisplayLabel() : "";
                int totalVariantCount = 0;
                for (java.util.List<Variant> g : lineGroups.values()) totalVariantCount += g.size();
                Log.d(TAG, "【虎牙】variantList 填充: 共 " + totalVariantCount + " 个清晰度，分布在 " + lineGroups.size() + " 条线路");
                for (java.util.Map.Entry<Integer, java.util.List<Variant>> entry : lineGroups.entrySet()) {
                    StringBuilder sb = new StringBuilder("  线路").append(entry.getKey()).append(": ");
                    for (Variant v : entry.getValue()) {
                        sb.append(v.getDisplayLabel()).append(" ");
                    }
                    Log.d(TAG, sb.toString());
                }

                // ================================================================
                // 步骤2：填充 backupUrls（扁平化所有线路×码率组合）
                // - 与反编译版一致：将所有非主URL的变体都加入备源
                // - 线路对话框显示: "主源", "源1", "源2", ...
                // ================================================================
                if (currentChannel != null) {
                    java.util.List<String> backups = currentChannel.getBackupUrls();
                    if (backups == null) { backups = new java.util.ArrayList<>(); }
                    else backups.clear();

                    currentChannel.setMainPlayUrl(defaultUrl);

                    // 扁平化：收集所有变体的URL（去重），排除主URL
                    java.util.Set<String> seenUrls = new java.util.HashSet<>();
                    if (allVariants != null) {
                        for (Variant v : allVariants) {
                            if (v.url != null && !seenUrls.contains(v.url)) {
                                seenUrls.add(v.url);
                                if (!v.url.equals(defaultUrl) && !backups.contains(v.url)) {
                                    backups.add(v.url);
                                }
                            }
                        }
                    }

                    Log.d(TAG, "【虎牙】扁平化线路: 主源 + " + backups.size() + " 个备源 (总变体数=" + allVariants.size() + ")");
                }

                Log.d(TAG, "【虎牙】SDK 全量解析成功, 默认流=" + defaultUrl.substring(0, Math.min(80, defaultUrl.length())));

                mPendingPlaybackHeaders = null;
                mHandler.post(() -> doPlay(defaultUrl, initialSeekPosition));
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "【虎牙】SDK 全量解析失败: " + error);
                mHandler.post(() -> {
                    Toast.makeText(context, "虎牙 SDK 解析失败: " + error, Toast.LENGTH_SHORT).show();
                    if (sourceFailedListener != null) {
                        sourceFailedListener.onSourceFailed();
                    }
                });
            }
        });
    }

    // ================================================================
    // 实际播放逻辑（从原 playUrlInternal 提取）
    // ================================================================
    private void doPlay(String url, long initialSeekPosition) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;

            String playUrl = url.trim();

            // 🟢【核心修复】检测是否是 m3u8 或 flv 这种可以直接播放的流地址
            // 虎牙 FLV URL 格式: https://al.flv.huya.com/src?wsSecret=... 不以 .flv 结尾，需要额外检测
            String lowerUrl = playUrl.toLowerCase(Locale.ROOT);
            boolean isRealStream = isHlsUrl(playUrl)
                    || lowerUrl.endsWith(".flv")
                    || lowerUrl.contains(".flv.huya.com/")
                    || lowerUrl.contains(".hls.huya.com/")
                    || (lowerUrl.startsWith("http") && (lowerUrl.contains("/src?ws") || lowerUrl.contains("&wssecret=")));
            Log.d(TAG, "doPlay: url=" + playUrl.substring(0, Math.min(100, playUrl.length())) + " isHls=" + isHlsUrl(playUrl) + " isRealStream=" + isRealStream);

            // 计算最终播放 URL（处理线路切换）
            String finalUrl;
            if (currentChannel != null && !isRealStream) {
                SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                String channelKey = currentChannel.getChannelId();
                if (TextUtils.isEmpty(channelKey)) {
                    channelKey = currentChannel.getName();
                }
                String prefKey = "channel_line_index_" + channelKey;
                int lineIndex = sp.getInt(prefKey, 0);
                if (lineIndex == 0 && sp.contains(KEY_CHANNEL_LINE_INDEX)) {
                    lineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
                }

                if (lineIndex == 0) {
                    finalUrl = currentChannel.getMainPlayUrl();
                } else {
                    List<String> backups = currentChannel.getBackupUrls();
                    int backupIndex = lineIndex - 1;
                    if (backupIndex >= 0 && backupIndex < backups.size()) {
                        finalUrl = backups.get(backupIndex);
                    } else {
                        finalUrl = currentChannel.getMainPlayUrl();
                        Log.w(TAG, "线路索引越界，已自动切回主源");
                    }
                }
                dLog("切换线路后播放：" + finalUrl);
            } else {
                finalUrl = playUrl;
                dLog("直接播放地址：" + finalUrl);
            }
            currentUrl = finalUrl;

            // 🟢【分离式架构】正常源与虎牙源完全独立：解析方法/重定向/防盗链 各走各的
            if (isHuyaSource(finalUrl)) {
                doPlayHuya(finalUrl, initialSeekPosition);
            } else {
                doPlayNormal(finalUrl, initialSeekPosition);
            }

        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            if (e instanceof RedirectFailedException) {
                if (listener != null) listener.onPlayError("源跳转失败：" + e.getMessage());
                return;
            }
            autoRetry("播放异常：" + e.getMessage(), e);
        }
    }

    /**
     * 判断是否为虎牙源（URL 特征或频道 ID 特征）
     */
    public boolean isHuyaSource(String url) {
        if (url != null && (url.contains(".huya.com/") || url.contains("huya.com/src"))) {
            return true;
        }
        if (currentChannel != null) {
            String cid = currentChannel.getChannelId();
            if (cid != null && (cid.startsWith("huya_") || cid.startsWith("hy_"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 🟢【路径N】正常直播源播放：独立 UA / 独立重定向 / 无防盗链头
     * - 普通 UA（Linux Android）
     * - 标准 HttpURLConnection 重定向
     * - 不注入 Referer/Origin/Cookie
     */
    private void doPlayNormal(String url, long initialSeekPosition) {
        Log.d(TAG, "【普通源】开始播放: " + url.substring(0, Math.min(80, url.length())));

        if (isHlsUrl(url)) {
            fetchAndParseMasterPlaylistNormal(url);
            Log.i(TAG, "🔴 [DEBUG-1] fetchAndParseMasterPlaylistNormal 返回！继续执行");
        } else {
            synchronized (variantListLock) { variantList.clear(); }
            Log.i(TAG, "🔴 [DEBUG-1] 非 HLS，variantList 已清空");
        }

        Log.i(TAG, "🔴 [DEBUG-2] 准备创建 SharedPreferences 和 httpFactory...");
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean debugEnabled = sp.getBoolean("debug_log_enable", false);
        Log.i(TAG, "🔴 [DEBUG-3] sp ready, debugEnabled=" + debugEnabled);

        RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
        httpFactory.setDebugLogEnabled(debugEnabled);

        // 普通 Headers：仅默认 UA + Accept
        Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
        reusableHeaderMap.clear();
        for (String name : globalHeaders.names()) {
            reusableHeaderMap.put(name, globalHeaders.get(name));
        }
        dLog("【普通源】使用默认 headers " + reusableHeaderMap.size() + " 项");

        httpFactory.setDefaultRequestProperties(reusableHeaderMap);
        httpFactory.setChannelName(currentChannelName);
        httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                .setAllowCrossDomainRedirects(sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true))
                .setAllowCrossProtocolRedirects(sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true))
                .setFollowRedirectsWithHeaders(sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true))
                .setIgnoreSslErrorRedirect(sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false))
                .setConnectTimeoutMs(5000)
                .setReadTimeoutMs(8000);

        MediaItem mediaItem = MediaItem.fromUri(url);
        MediaSource mediaSource;
        if (isHlsUrl(url)) {
            mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        } else {
            mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        }

        ensurePlayerBoundToView();
        Log.i(TAG, "🔴 [DEBUG-doPlayNormal] setMediaSource 前！player state=" + player.getPlaybackState());
        player.setMediaSource(mediaSource, true);
        Log.i(TAG, "🔴 [DEBUG-doPlayNormal] prepare 前！state=" + player.getPlaybackState());
        player.prepare();
        Log.i(TAG, "🔴 [DEBUG-doPlayNormal] prepare 后！state=" + player.getPlaybackState());
        if (initialSeekPosition > 0) player.seekTo(initialSeekPosition);
        Log.i(TAG, "🔴 [DEBUG-doPlayNormal] play() 前！state=" + player.getPlaybackState());
        player.play();
        Log.i(TAG, "🔴 [DEBUG-doPlayNormal] play() 后！state=" + player.getPlaybackState());
        startStuckDetection();
    }

    /**
     * 🔴【路径H】虎牙源播放：完全独立的 UA / 重定向 / 防盗链
     * - 浏览器 UA
     * - Referer/Origin 强制注入
     * - 解析器专用 headers（含 Cookie/签名）优先覆盖
     * - 跨域重定向保留鉴权头（避免 302 后 403）
     * - Cookie 与 WebView 同步
     */
    private void doPlayHuya(String url, long initialSeekPosition) {
        Log.d(TAG, "【虎牙源】开始播放: " + url.substring(0, Math.min(80, url.length())));

        // 🔴【修复】不再清空 variantList！variantList 已在 playHuyaStream 中填充好，
        // 包含所有线路×码率的清晰度选项，此处直接保留供 UI 使用。
        // 之前的 variantList.clear() 会导致清晰度选择对话框为空。

        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean debugEnabled = sp.getBoolean("debug_log_enable", false);

        RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
        httpFactory.setDebugLogEnabled(debugEnabled);

        // 虎牙专属 headers：浏览器 UA + Referer + Origin
        Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
        reusableHeaderMap.clear();
        for (String name : globalHeaders.names()) {
            reusableHeaderMap.put(name, globalHeaders.get(name));
        }
        reusableHeaderMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        // 🔴【关键】Referer 必须是具体房间页 URL，主页 URL 会被 CDN 403
        String huyaReferer = mHuyaRoomId > 0 ? "https://www.huya.com/" + mHuyaRoomId : "https://www.huya.com/";
        reusableHeaderMap.put("Referer", huyaReferer);
        // 🔴【移除 Origin】CDN 不期望非浏览器请求携带 Origin 头，可能导致 403
        reusableHeaderMap.put("Accept", "*/*");
        reusableHeaderMap.put("Accept-Language", "zh-CN,zh;q=0.9");
        reusableHeaderMap.put("Accept-Encoding", "identity");
        reusableHeaderMap.put("Connection", "keep-alive");
        dLog("【虎牙源】已启用浏览器UA+Referer(" + huyaReferer + ") 防盗链头");

        // 🔴【关键】SDK 返回的解析器专用头优先覆盖
        // 这套 headers 的 UA/Referer/Origin/Cookie 与解析 PC 网页时完全一致
        if (mPendingPlaybackHeaders != null && !mPendingPlaybackHeaders.isEmpty()) {
            int cnt = 0;
            for (Map.Entry<String, String> e : mPendingPlaybackHeaders.entrySet()) {
                // 🔴【跳过 Origin/Referer】Origin 触发 CDN 拦截；Referer 用房间页 URL 覆盖
                if ("Origin".equalsIgnoreCase(e.getKey())) continue;
                if ("Referer".equalsIgnoreCase(e.getKey())) continue;
                reusableHeaderMap.put(e.getKey(), e.getValue());
                cnt++;
                // 🔴【调试】打印每个 header 的名称和值前50字符
                Log.d(TAG, "  Header[" + e.getKey() + "] = " + e.getValue().substring(0, Math.min(50, e.getValue().length())));
            }
            Log.d(TAG, "【虎牙源】解析器专用Headers注入 " + cnt + " 项(含Cookie="
                    + (mPendingPlaybackHeaders.containsKey("Cookie") ? "是" : "否") + ")");
        } else {
            Log.d(TAG, "【虎牙源】mPendingPlaybackHeaders 为空，走默认虎牙 headers");
        }
        mPendingPlaybackHeaders = null;

        // Cookie 与 WebView 同步（仅当解析器未提供 Cookie 时）
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        if (sendCookie && !reusableHeaderMap.containsKey("Cookie")) {
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) reusableHeaderMap.put("Cookie", cookies);
        }

        httpFactory.setDefaultRequestProperties(reusableHeaderMap);
        httpFactory.setChannelName(currentChannelName);
        // 虎牙源强制启用跨域+跨协议+保留头重定向（防盗链要求）
        httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                .setAllowCrossDomainRedirects(true)
                .setAllowCrossProtocolRedirects(true)
                .setFollowRedirectsWithHeaders(true)
                .setIgnoreSslErrorRedirect(sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false))
                .setConnectTimeoutMs(5000)
                .setReadTimeoutMs(8000);

        MediaItem mediaItem = MediaItem.fromUri(url);
        MediaSource mediaSource;
        if (isHlsUrl(url)) {
            mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        } else {
            mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        }

        ensurePlayerBoundToView();
        player.setMediaSource(mediaSource, true);
        player.prepare();
        if (initialSeekPosition > 0) player.seekTo(initialSeekPosition);
        player.play();
        startStuckDetection();
    }

    /**
     * 🟢【路径N】普通源主播放列表解析：普通 UA，标准重定向
     */
    private void fetchAndParseMasterPlaylistNormal(String masterUrl) {
        if (isParsingMasterPlaylist) return;
        isParsingMasterPlaylist = true;
        ensurePlaylistExecutor().execute(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                Log.d(TAG, "【普通源】解析主播放列表: " + masterUrl.substring(0, Math.min(100, masterUrl.length())));

                java.net.URL url = new java.net.URL(masterUrl);
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);

                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
                connection.setRequestProperty("Accept", "*/*");

                int code = connection.getResponseCode();
                Log.d(TAG, "【普通源】主播放列表响应码: " + code);

                if (code == java.net.HttpURLConnection.HTTP_OK) {
                    StringBuilder content = new StringBuilder();
                    try (java.io.InputStream is = connection.getInputStream();
                         java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    String playlist = content.toString();
                    Log.d(TAG, "【普通源】主播放列表长度: " + playlist.length());
                    parseMasterPlaylist(playlist, masterUrl);
                } else if (code == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                        || code == java.net.HttpURLConnection.HTTP_MOVED_PERM) {
                    String newUrl = connection.getHeaderField("Location");
                    Log.d(TAG, "【普通源】重定向到: " + newUrl);
                    isParsingMasterPlaylist = false;
                    if (newUrl != null) {
                        fetchAndParseMasterPlaylistNormal(newUrl);
                        return;
                    }
                } else {
                    Log.e(TAG, "【普通源】主播放列表请求失败: code=" + code);
                    synchronized (variantListLock) { variantList.clear(); }
                }
            } catch (Exception e) {
                Log.e(TAG, "【普通源】解析主播放列表失败: ", e);
                synchronized (variantListLock) { variantList.clear(); }
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                isParsingMasterPlaylist = false;
            }
        });
    }

    /**
     * 🔴【路径H】虎牙源主播放列表解析：浏览器 UA + 防盗链头 + Cookie 同步
     * 独立解析方法，与普通源完全分离
     */
    private void fetchAndParseMasterPlaylistHuya(String masterUrl) {
        if (isParsingMasterPlaylist) return;
        isParsingMasterPlaylist = true;
        ensurePlaylistExecutor().execute(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                Log.d(TAG, "【虎牙源】解析主播放列表: " + masterUrl.substring(0, Math.min(100, masterUrl.length())));

                java.net.URL url = new java.net.URL(masterUrl);
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                // 虎牙源手动处理重定向以保留鉴权头
                connection.setInstanceFollowRedirects(false);

                // 虎牙专属防盗链头
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
                connection.setRequestProperty("Referer", "https://www.huya.com/");
                connection.setRequestProperty("Origin", "https://www.huya.com");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Connection", "keep-alive");

                // 优先使用解析器专用 headers（含签名 Cookie）
                if (mPendingPlaybackHeaders != null && !mPendingPlaybackHeaders.isEmpty()) {
                    for (Map.Entry<String, String> e : mPendingPlaybackHeaders.entrySet()) {
                        connection.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                // Cookie 与 WebView 同步
                String cookies = android.webkit.CookieManager.getInstance().getCookie(masterUrl);
                if (cookies != null && !cookies.isEmpty()) {
                    connection.setRequestProperty("Cookie", cookies);
                    Log.d(TAG, "【虎牙源】发送 Cookie: " + cookies.substring(0, Math.min(80, cookies.length())));
                }

                int code = connection.getResponseCode();
                Log.d(TAG, "【虎牙源】主播放列表响应码: " + code);

                if (code == java.net.HttpURLConnection.HTTP_OK) {
                    StringBuilder content = new StringBuilder();
                    try (java.io.InputStream is = connection.getInputStream();
                         java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    String playlist = content.toString();
                    Log.d(TAG, "【虎牙源】主播放列表长度: " + playlist.length());
                    parseMasterPlaylist(playlist, masterUrl);
                } else if (code == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                        || code == java.net.HttpURLConnection.HTTP_MOVED_PERM) {
                    // 手动处理重定向，保留鉴权头（关键防盗链要求）
                    String newUrl = connection.getHeaderField("Location");
                    Log.d(TAG, "【虎牙源】手动重定向到: " + newUrl);
                    isParsingMasterPlaylist = false;
                    if (newUrl != null) {
                        fetchAndParseMasterPlaylistHuya(newUrl);
                        return;
                    }
                } else {
                    // 读取错误响应体
                    try (java.io.InputStream es = connection.getErrorStream()) {
                        if (es != null) {
                            StringBuilder err = new StringBuilder();
                            byte[] buf = new byte[1024];
                            int len;
                            while ((len = es.read(buf)) != -1) {
                                err.append(new String(buf, 0, len));
                            }
                            if (err.length() > 0) {
                                Log.e(TAG, "【虎牙源】错误响应体: " + err.substring(0, Math.min(200, err.length())));
                            }
                        }
                    }
                    Log.e(TAG, "【虎牙源】主播放列表请求失败: code=" + code);
                    synchronized (variantListLock) { variantList.clear(); }
                }
            } catch (Exception e) {
                Log.e(TAG, "【虎牙源】解析主播放列表失败: ", e);
                synchronized (variantListLock) { variantList.clear(); }
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                isParsingMasterPlaylist = false;
            }
        });
    }

    private void parseMasterPlaylist(String playlist, String baseUrl) {
        List<Variant> list = new ArrayList<>();
        Pattern streamInfPattern = Pattern.compile("^#EXT-X-STREAM-INF:", Pattern.CASE_INSENSITIVE);
        Pattern bandwidthPattern = Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);
        Pattern resolutionPattern = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);
        dLog("播放列表内容（截取前500字符）：\n" + playlist.substring(0, Math.min(playlist.length(), 500)));

        String[] lines = playlist.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!streamInfPattern.matcher(line).find()) continue;

            Matcher bwMatcher = bandwidthPattern.matcher(line);
            if (!bwMatcher.find()) continue;
            int bandwidth = Integer.parseInt(bwMatcher.group(1));

            int width = 0, height = 0;
            String resolutionStr = null;
            Matcher resMatcher = resolutionPattern.matcher(line);
            if (resMatcher.find()) {
                width = Integer.parseInt(resMatcher.group(1));
                height = Integer.parseInt(resMatcher.group(2));
                resolutionStr = width + "x" + height;
            }

            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (!next.isEmpty() && !next.startsWith("#")) {
                    uri = next;
                    break;
                }
            }
            if (uri != null) {
                if (!uri.startsWith("http")) {
                    uri = resolveUrl(baseUrl, uri);
                }
                list.add(new Variant(uri, bandwidth, width, height));
                dLog("解析到清晰度: " + (height > 0 ? resolutionStr : "自适应") + " -> " + uri);
            }
        }
        Collections.sort(list, (a, b) -> Integer.compare(a.height, b.height));
        synchronized (variantListLock) { this.variantList = list; }
        if (!list.isEmpty()) {
            dLog("解析到 " + list.size() + " 个清晰度");
        } else {
            Log.w(TAG, "未解析到任何清晰度流，可能是直播源本身不支持多码率或网络被拦截");
        }
    }

    private String resolveUrl(String base, String relative) {
        try {
            URL baseUrl = new URL(base);
            URL resolved = new URL(baseUrl, relative);
            return resolved.toString();
        } catch (Exception e) {
            return relative;
        }
    }

    public List<String> getAvailableResolutions() {
        List<String> resolutions = new ArrayList<>();
        synchronized (variantListLock) {
            for (Variant v : variantList) {
                String label = v.getDisplayLabel();
                if (!resolutions.contains(label)) {
                    resolutions.add(label);
                }
            }
        }
        return resolutions;
    }

    /**
     * 获取可用线路列表（用于线路切换 UI）
     */
    public List<String> getAvailableLines() {
        List<String> lines = new ArrayList<>();
        Channel ch = getCurrentChannel();
        if (ch == null) return lines;

        lines.add("主源");
        List<String> backups = ch.getBackupUrls();
        if (backups != null) {
            for (int i = 0; i < backups.size(); i++) {
                lines.add("源" + (i + 1));
            }
        }
        return lines;
    }

    /**
     * 切换到指定线路（按线路索引）
     * @param lineIndex 0=主线路, 1+ = 备用线路索引
     */
    public void switchToHuyaLine(int lineIndex) {
        Channel ch = getCurrentChannel();
        if (ch == null) return;

        if (lineIndex < 0) lineIndex = 0;

        if (lineIndex == 0) {
            String url = ch.getMainPlayUrl();
            if (!TextUtils.isEmpty(url)) {
                ch.setCurrentLineIndex(0);
                playUrlInternal(url);
            }
        } else {
            List<String> backups = ch.getBackupUrls();
            int backupIdx = lineIndex - 1;
            if (backups != null && backupIdx >= 0 && backupIdx < backups.size()) {
                ch.setCurrentLineIndex(lineIndex);
                playUrlInternal(backups.get(backupIdx));
            }
        }
    }

    /**
     * 根据 URL 查找对应的 huyaLineIndex
     */
    private int findLineIndexByUrl(String url) {
        synchronized (variantListLock) {
            for (Variant v : variantList) {
                if (url.equals(v.url)) return v.huyaLineIndex;
            }
        }
        return -1;
    }

    /**
     * 重建 variantList：将指定线路的 variant 排在前面
     */
    private void rebuildVariantListForLine(int lineIndex) {
        synchronized (variantListLock) {
            List<Variant> currentLine = new ArrayList<>();
            List<Variant> otherLines = new ArrayList<>();
            for (Variant v : variantList) {
                if (v.huyaLineIndex == lineIndex) {
                    currentLine.add(v);
                } else {
                    otherLines.add(v);
                }
            }
            Collections.sort(currentLine, (a, b) -> Integer.compare(b.bandwidth, a.bandwidth));
            variantList.clear();
            variantList.addAll(currentLine);
            variantList.addAll(otherLines);

            if (!currentLine.isEmpty()) {
                currentResolutionLabel = currentLine.get(0).getDisplayLabel();
            }
        }
        Log.d(TAG, "【虎牙】切换到线路 " + lineIndex + ", 当前线路清晰度: " + getAvailableResolutions());
    }

    /**
     * 切换清晰度：
     * - 与反编译版一致：在所有变体中查找（不限线路）
     * - 优先按标签名精确匹配（"1080p高清"、"720p"等URL模式标签）
     * - 匹配不上再按目标高度兜底
     */
    public void switchToResolution(int targetHeight, String... matchLabelOpt) {
        List<Variant> snapshot;
        synchronized (variantListLock) {
            snapshot = new ArrayList<>(variantList);
        }
        if (snapshot.isEmpty()) {
            Log.w(TAG, "无多码率信息，无法切换清晰度");
            return;
        }
        String matchLabel = (matchLabelOpt != null && matchLabelOpt.length > 0) ? matchLabelOpt[0] : null;
        Variant selected = null;

        // 1) 按显示名精确匹配（所有变体中查找）
        if (!TextUtils.isEmpty(matchLabel)) {
            for (Variant v : snapshot) {
                if (matchLabel.equals(v.getDisplayLabel())
                        || matchLabel.equals(v.resolutionLabel)) {
                    selected = v;
                    break;
                }
            }
        }

        // 2) 按高度兜底
        if (selected == null && targetHeight > 0) {
            for (Variant v : snapshot) {
                if (v.height >= targetHeight) {
                    selected = v;
                    break;
                }
            }
        }

        if (selected == null) {
            selected = snapshot.get(0);
        }
        // 切换到当前清晰度时保持默认线路的 Variant（避免重新 doPlay）
        currentResolutionLabel = selected.getDisplayLabel();
        dLog("切换清晰度到：" + selected.getDisplayLabel() + "，URL=" + (selected.url != null ? selected.url.substring(0, Math.min(60, selected.url.length())) : "(空)"));
        playUrlInternal(selected.url);
    }

    /** 兼容旧版（仅按高度） */
    @Deprecated
    public void switchToResolution(int targetHeight) {
        switchToResolution(targetHeight, (String) null);
    }

    public enum ScaleMode {FIT, FILL, ZOOM}

    public void setScaleMode(ScaleMode mode) {
        try {
            if (playerView == null) return;
            this.mCurrentScaleMode = mode;
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

    public void setSurface(Surface surface) {
        try {
            if (player != null) {
                player.setVideoSurface(surface);
                Log.d(TAG, "播放器已绑定 Surface");
            }
        } catch (Exception e) {
            Log.e(TAG, "绑定 Surface 失败", e);
        }
    }

    public String getCurrentResolutionLabel() {
        return currentResolutionLabel;
    }

    public void setCurrentChannelNumber(int num) {
        currentChannelNumber = num;
    }

    public void bindChannelText(TextView textView) {
        channelNumberTextView = textView;
    }

    private void showChannelAndAutoHide() {
        if (channelNumberTextView != null && currentChannelNumber > 0) {
            channelNumberTextView.setText(String.valueOf(currentChannelNumber));
            channelNumberTextView.setVisibility(View.VISIBLE);
            mHandler.removeCallbacks(hideChannelRunnable);
            mHandler.postDelayed(hideChannelRunnable, CHANNEL_NUM_HIDE_DELAY);
        }
    }

    private void hideChannelNum() {
        if (channelNumberTextView != null) channelNumberTextView.setVisibility(View.GONE);
    }

    public static class LiveInfo {
        public String resolution = "未知";
        public String bitrate = "0";
        public String audio = "未知";
        public String format = "未知";
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        try {
            if (player != null) {
                Format videoFormat = player.getVideoFormat();
                if (videoFormat != null) {
                    int width = videoFormat.width, height = videoFormat.height;
                    if (width > 0 && height > 0) info.resolution = width + "×" + height;
                    info.format = friendlyMime(videoFormat.sampleMimeType);
                    if (videoFormat.bitrate > 0)
                        info.bitrate = String.format(Locale.getDefault(), "%.1f Mbps", videoFormat.bitrate / 1000000f);
                }
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    info.audio = friendlyMime(audioFormat.sampleMimeType);
                    if (audioFormat.sampleRate > 0) info.audio += " " + (audioFormat.sampleRate / 1000) + "kHz";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取直播信息异常", e);
        }
        return info;
    }

    private static String friendlyMime(String mimeType) {
        if (TextUtils.isEmpty(mimeType)) return "未知";
        String m = mimeType.toLowerCase(Locale.ROOT);
        if (m.contains("avc") || m.contains("h264") || m.endsWith("/264")) return "H.264";
        if (m.contains("hevc") || m.contains("h265")) return "H.265 (HEVC)";
        if (m.contains("av1")) return "AV1";
        if (m.contains("vp9")) return "VP9";
        if (m.contains("vp8")) return "VP8";
        if (m.contains("mpeg2") || m.contains("mp2v")) return "MPEG-2";
        if (m.contains("mpeg4") || m.contains("mp4v")) return "MPEG-4";
        if (m.contains("wmv")) return "WMV";
        if (m.contains("mp4a") || m.contains("aac") || m.contains("mpeg4-generic")) return "AAC";
        if (m.contains("ac3")) return "AC-3";
        if (m.contains("eac3") || m.contains("ec3")) return "E-AC-3 (Dolby Digital Plus)";
        if (m.contains("ac4")) return "AC-4";
        if (m.contains("opus")) return "Opus";
        if (m.contains("vorbis")) return "Vorbis";
        if (m.contains("flac")) return "FLAC";
        if (m.contains("g711") || m.contains("alaw") || m.contains("ulaw")) return "G.711";
        if (m.contains("pcm")) return "PCM";
        if (m.contains("wma")) return "WMA";
        if (m.contains("mp3") || m.endsWith("/mpeg") && m.startsWith("audio/")) return "MP3";
        return mimeType;
    }

    private void notifyLiveInfoUpdate() {
        if (liveInfoUpdateListener != null) liveInfoUpdateListener.onLiveInfoUpdate(getLiveInfo());
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

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        liveInfoUpdateListener = listener;
    }

    public void pause() {
        try {
            if (player != null) player.pause();
        } catch (Exception ignored) {}
    }

    public void resume() {
        try {
            ensurePlayerBoundToView();
            if (player != null) player.play();
        } catch (Exception ignored) {}
    }

    public void togglePlayWhenReady() {
        try {
            if (player == null) return;
            if (player.getPlaybackState() == androidx.media3.common.Player.STATE_IDLE
                    || player.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED) {
                return;
            }
            player.setPlayWhenReady(!player.getPlayWhenReady());
        } catch (Exception ignored) {}
    }

    public boolean isPlaying() {
        try {
            return player != null && player.getPlayWhenReady()
                    && player.getPlaybackState() != androidx.media3.common.Player.STATE_IDLE
                    && player.getPlaybackState() != androidx.media3.common.Player.STATE_ENDED;
        } catch (Exception e) {
            return false;
        }
    }

    public long getCurrentPosition() {
        try {
            return player != null ? player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public long getDuration() {
        try {
            return player != null ? player.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public void seekTo(long positionMs) {
        try {
            if (player != null) player.seekTo(positionMs);
        } catch (Exception ignored) {}
    }

    public com.tv.live.util.SourceHealthChecker getHealthChecker() {
        return healthChecker;
    }

    public void release() {
        // 🔧【核心修复】黑屏Bug：原版 try-catch 把整个清理逻辑包在一起，
        //    一旦 trackSelector.release() 抛 IllegalArgumentException
        //    （Media3 1.7.1 + Android 14/15 SpatializerWrapperV32 已知问题），
        //    后续的 instance = null 不会执行 → TVPlayerManager 单例残留半释放状态 →
        //    重新打开应用时 getInstance() 返回旧实例，player/playerView/trackSelector
        //    已为 null，无法正常初始化 → 黑屏。
        //
        // 修复策略：
        //  1) instance = null 必须放在 finally，确保任何异常路径下都会清空单例引用
        //  2) 每个资源清理步骤独立 try-catch，避免一个失败影响其他
        try {
            stopStuckDetection();
            cancelRetry();
            if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
            updateWakeLock(false);
            try { unregisterDecoderModeReceiver(); } catch (Exception e) {
                Log.w(TAG, "注销解码器广播异常: " + e.getMessage());
            }
            try { unregisterRendererModeReceiver(); } catch (Exception e) {
                Log.w(TAG, "注销渲染方式广播异常: " + e.getMessage());
            }

            // 清理子模块（每个独立 try-catch）
            if (decoderModeManager != null) {
                try { decoderModeManager.release(); } catch (Exception e) {
                    Log.w(TAG, "decoderModeManager.release 异常: " + e.getMessage());
                }
                decoderModeManager = null;
            }
            if (variantManager != null) {
                try { variantManager.release(); } catch (Exception e) {
                    Log.w(TAG, "variantManager.release 异常: " + e.getMessage());
                }
                variantManager = null;
            }
            if (huyaStreamPlayer != null) {
                try { huyaStreamPlayer.release(); } catch (Exception e) {
                    Log.w(TAG, "huyaStreamPlayer.release 异常: " + e.getMessage());
                }
                huyaStreamPlayer = null;
            }

            // 清理所有监听器（无副作用，直接置 null）
            onPlayerViewRecreatedListener = null;
            sourceFailedListener = null;
            liveInfoUpdateListener = null;
            listener = null;

            // 清理Activity引用和SDK容器
            mActivity = null;
            if (mSdkPlayerContainer != null) {
                try {
                    mSdkPlayerContainer.removeAllViews();
                    mSdkPlayerContainer.setVisibility(View.GONE);
                } catch (Exception ignored) {}
                mSdkPlayerContainer = null;
            }

            // 清理播放器（独立 try-catch 包裹 player.release）
            if (player != null) {
                try {
                    if (playerListener != null) {
                        try { player.removeListener(playerListener); }
                        catch (Exception e) { Log.w(TAG, "removeListener 异常: " + e.getMessage()); }
                        playerListener = null;
                    }
                    player.release();
                } catch (Exception e) {
                    Log.w(TAG, "player.release 异常: " + e.getMessage());
                }
                player = null;
            }
            if (playerView != null) {
                try {
                    playerView.setPlayer(null);
                    playerView.setVisibility(View.VISIBLE);
                } catch (Exception ignored) {}
                playerView = null;
            }

            // 清理TrackSelector（重点修复：独立 try-catch 包裹，避免 IllegalArgumentException
            // 破坏后续清理，导致 instance = null 不执行）
            if (trackSelector != null) {
                try {
                    trackSelector.release();
                } catch (Exception e) {
                    // Media3 1.7.1 + Android 14/15 的 SpatializerWrapperV32 Bug，
                    // 移除未注册的监听器抛 IllegalArgumentException。这里吞掉即可，
                    // 不影响后续清理流程（instance = null 在 finally 保证执行）。
                    Log.w(TAG, "trackSelector.release 异常（已吞掉，不影响清理）: " + e.getMessage());
                }
                trackSelector = null;
            }

            // 清理健康检查器
            if (healthChecker != null) {
                try { healthChecker.release(); }
                catch (Exception e) { Log.w(TAG, "healthChecker.release 异常: " + e.getMessage()); }
                healthChecker = null;
            }

            // 清空集合
            synchronized (variantListLock) {
                variantList.clear();
            }
            reusableHeaderMap.clear();

            // 清理UI组件
            channelNumberTextView = null;
            currentChannel = null;
            currentChannelName = "";
            currentUrl = null;
            mHuyaRoomId = -1;

            // 清理Context引用
            context = null;
            sp = null;
        } catch (Exception e) {
            // 顶层兜底：捕获任何漏网的异常，确保 finally 仍能执行
            Log.e(TAG, "释放异常（顶层兜底）", e);
        } finally {
            // 🔧【核心修复】必须放在 finally，确保即使中途异常，
            //    TVPlayerManager 单例引用也会被清空，下次 getInstance() 会重建。
            //    这是修复"按返回退出再重开应用黑屏"的关键。
            instance = null;
        }
    }

    /**
     * 关闭静态线程池（应用真正退出时调用）。
     * 注意：只在本方法被显式调用时才真正关闭；Activity onDestroy 不会调用它，
     * 因为进程可能未死，重开应用后仍需线程池解析播放列表。
     */
    public static void shutdownThreadPool() {
        if (sPlaylistExecutor != null && !sPlaylistExecutor.isShutdown()) {
            sPlaylistExecutor.shutdownNow();
        }
    }

    /**
     * 确保静态线程池存活；若已被意外关闭则重建，避免重开应用后
     * 播放器无法解析播放列表导致黑屏。
     */
    private static ExecutorService ensurePlaylistExecutor() {
        if (sPlaylistExecutor == null || sPlaylistExecutor.isShutdown()) {
            synchronized (TVPlayerManager.class) {
                if (sPlaylistExecutor == null || sPlaylistExecutor.isShutdown()) {
                    sPlaylistExecutor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "TVPlayer-PlaylistParser");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return sPlaylistExecutor;
    }
}
