package com.tv.live;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

import androidx.core.content.ContextCompat;

import com.tv.live.util.HuyaSDKParser;
import com.tv.live.exception.RedirectFailedException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SuppressLint({"UnsafeOptInUsageError", "StaticFieldLeak"})
public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";

    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_HARD = 1;
    public static final int DECODER_MODE_SOFT = 2;
    public static final int DECODER_MODE_FFMPEG = 3;

    public static final String HUYA_ENGINE_EXO_PLAYER  = "exo_player";
    public static final String HUYA_ENGINE_SDK_PLAYER  = "sdk_player";
    public static final String HUYA_ENGINE_AUTO        = "auto";

    private static final String KEY_HUYA_PLAYER_ENGINE = "huya_player_engine";
    private static final String KEY_HUYA_DECODER_SYNC  = "huya_decoder_sync";

    static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";

    static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";

    private static volatile TVPlayerManager instance;
    final Context context;

    // Shared state - package-private for sub-managers
    ExoPlayer player;
    PlayerView playerView;
    Player.Listener playerListener;
    String currentUrl;
    com.tv.live.util.SourceHealthChecker healthChecker;
    private int currentChannelNumber = 0;
    private TextView channelNumberTextView;
    String currentChannelName = "";
    int mDecoderMode = DECODER_MODE_AUTO;

    boolean isSwitching = false;

    Channel currentChannel;
    int backupRetryIndex = -1;

    long initialPlayStartTime = 0;
    int bufferCount = 0;
    long totalStallTime = 0;
    boolean isStalled = false;
    long lastStallStartTime = 0;
    int retryCount = 0;
    boolean isRetrying = false;
    Runnable retryRunnable;
    long lastSourceFailedTime = 0;

    Runnable stuckCheckRunnable;

    Handler mHandler;
    Runnable hideChannelRunnable;

    int mHuyaRoomId = -1;
    int mCurrentHuyaLineIndex = 0;

    OnPlayStateListener listener;
    OnSourceFailedListener sourceFailedListener;
    OnLiveInfoUpdateListener liveInfoUpdateListener;
    boolean isPlaying = false;

    BroadcastReceiver decoderModeReceiver;
    boolean decoderReceiverRegistered = false;
    BroadcastReceiver rendererModeReceiver;
    boolean rendererReceiverRegistered = false;

    OnPlayerViewRecreatedListener onPlayerViewRecreatedListener;
    boolean isRenderingSwitching = false;

    final Map<String, String> reusableHeaderMap = new HashMap<>();
    Map<String, String> mPendingPlaybackHeaders = null;

    DefaultTrackSelector trackSelector;

    ScaleMode mCurrentScaleMode = ScaleMode.FILL;
    Boolean mCurrentUseTexture = null;
    boolean surfaceReady = false;
    boolean pendingBindPlayer = false;

    final Object variantListLock = new Object();
    volatile List<Variant> variantList = new ArrayList<>();
    volatile boolean isParsingMasterPlaylist = false;

    SharedPreferences sp;
    String currentResolutionLabel = "自适应";

    // Sub-managers
    final PlayerDecoderManager decoderManager;
    final PlayerPlaybackManager playbackManager;
    final PlayerRetryManager retryManager;
    final PlayerQualityManager qualityManager;

    // HTTP data source factory (shared instance)
    RedirectLoggingHttpDataSource.Factory httpFactory;

    // ==================== Inner Classes ====================

    public static class Variant {
        public String url;
        public int bandwidth;
        public int width;
        public int height;
        public String resolutionLabel;
        public String huyaBitRateDisplayName;
        public int huyaLineIndex = -1;
        public int huyaBitRate = -1;

        Variant(String url, int bandwidth, int width, int height) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.width = width;
            this.height = height;
            if (height >= 2160) resolutionLabel = "4K (2160p)";
            else if (height >= 1080) resolutionLabel = "1080p";
            else if (height >= 720) resolutionLabel = "720p";
            else if (height > 0) resolutionLabel = height + "p";
            else resolutionLabel = "自适应";
        }

        public static Variant fromHuyaStreamInfo(HuyaSDKParser.HuyaStreamInfo s) {
            Variant v = new Variant(
                    s.getPlayUrl(),
                    s.bitRate * 1000,
                    0, 0
            );
            String url = s.getPlayUrl();
            if (!android.text.TextUtils.isEmpty(url)) {
                v.height = PlayerQualityManager.inferHeightFromUrl(url, s.bitRate);
                v.resolutionLabel = PlayerQualityManager.inferResolutionLabelFromUrl(url, s.bitRate);
            } else {
                v.height = s.bitRate >= 4000 ? 1080 : s.bitRate >= 2000 ? 720 : 360;
                v.resolutionLabel = v.height + "p";
            }
            if (!android.text.TextUtils.isEmpty(s.bitRateDisplayName)) {
                v.huyaBitRateDisplayName = s.bitRateDisplayName;
            }
            v.huyaLineIndex = s.lineIndex;
            v.huyaBitRate = s.bitRate;
            return v;
        }

        public String getDisplayLabel() {
            return resolutionLabel != null ? resolutionLabel :
                    (!android.text.TextUtils.isEmpty(huyaBitRateDisplayName) ? huyaBitRateDisplayName : "未知");
        }
    }

    public static class LiveInfo {
        public String resolution = "未知";
        public String bitrate = "0";
        public String audio = "未知";
        public String format = "未知";
    }

    public interface OnPlayerViewRecreatedListener {
        void onPlayerViewRecreated(PlayerView newPlayerView);
    }

    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    public interface OnSourceFailedListener {
        void onSourceFailed();
    }

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    public enum ScaleMode {FIT, FILL, ZOOM}

    // ==================== Singleton ====================

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

        // Initialize shared HTTP factory
        httpFactory = new RedirectLoggingHttpDataSource.Factory();

        // Initialize sub-managers
        decoderManager = new PlayerDecoderManager(this, context, mHandler);
        playbackManager = new PlayerPlaybackManager(this, context);
        retryManager = new PlayerRetryManager(this);
        qualityManager = new PlayerQualityManager(this);

        hideChannelRunnable = () -> hideChannelNum();

        stuckCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (player == null || !player.isPlaying()) {
                    // Use PlayerRetryManager's logic
                    retryManager.internalStuckCheck();
                    return;
                }
                try {
                    long currentPosition = player.getCurrentPosition();
                    long now = System.currentTimeMillis();
                    long lastPos = getLastPosition();
                    long lastUpdate = getLastPositionUpdateTime();
                    if (currentPosition != lastPos) {
                        setLastPosition(currentPosition);
                        setLastPositionUpdateTime(now);
                    } else {
                        if (now - lastUpdate > PlayerRetryManager.STUCK_TIMEOUT) {
                            Log.w(TAG, "检测到播放卡住，自动重试...");
                            retryManager.autoRetry("播放卡住");
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "卡住检测异常", e);
                }
                mHandler.postDelayed(this, 2000);
            }
        };

        decoderManager.initPlayer();
    }

    // ==================== Internal helpers for sub-managers ====================

    private long lastPosition = 0;
    private long lastPositionUpdateTime = 0;

    long getLastPosition() { return lastPosition; }
    void setLastPosition(long v) { lastPosition = v; }
    long getLastPositionUpdateTime() { return lastPositionUpdateTime; }
    void setLastPositionUpdateTime(long v) { lastPositionUpdateTime = v; }

    void dLog(String msg) {
        if (sp.getBoolean("log_enable", false)) {
            Log.d(TAG, msg);
            com.tv.live.util.LogCollector.getInstance().addLog(TAG, msg);
        }
    }

    void resetPerformanceStats() {
        bufferCount = 0;
        totalStallTime = 0;
        isStalled = false;
        lastStallStartTime = 0;
    }

    void initPlayerListener() {
        if (playerListener != null) return;
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
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

                if (isRedirectError) {
                    if (listener != null) {
                        listener.onPlayError(error.getMessage());
                    }
                    return;
                }

                boolean isNetworkError = retryManager.isNetworkError(error);
                if (isNetworkError && retryManager.isNetworkUnavailable()) {
                    Log.w(TAG, "网络不可用，暂不切台，等待网络恢复后重试...");
                    retryManager.autoRetry("网络不可用", error);
                } else {
                    retryManager.autoRetry(isNetworkError ? "网络异常" : "播放异常", error);
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
                    decoderManager.updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();
                    isSwitching = false;
                    if (listener != null) listener.onPlayReady();
                    retryCount = 0;
                    isRetrying = false;
                    retryManager.startStuckDetection();
                    if (healthChecker != null && !android.text.TextUtils.isEmpty(currentUrl)) {
                        healthChecker.markSuccess(currentUrl);
                    }
                    if (initialPlayStartTime == 0) {
                        initialPlayStartTime = System.currentTimeMillis();
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (listener != null) listener.onBuffering();
                    lastPositionUpdateTime = System.currentTimeMillis();
                    bufferCount++;
                    if (!isStalled) {
                        isStalled = true;
                        lastStallStartTime = System.currentTimeMillis();
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (listener != null) listener.onPlayEnd();
                    retryManager.autoRetry("播放结束");
                } else if (state == Player.STATE_IDLE) {
                    isSwitching = false;
                    if (listener != null) listener.onIdle();
                    decoderManager.updateWakeLock(false);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (playing) {
                    lastPositionUpdateTime = System.currentTimeMillis();
                    if (isStalled) {
                        isStalled = false;
                        long stallDuration = System.currentTimeMillis() - lastStallStartTime;
                        totalStallTime += stallDuration;
                        dLog("【性能】卡顿结束，时长：" + stallDuration + "ms");
                    }
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                dLog("视频分辨率变化：" + videoSize.width + "×" + videoSize.height);
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }

    private void notifyLiveInfoUpdate() {
        if (liveInfoUpdateListener != null) liveInfoUpdateListener.onLiveInfoUpdate(getLiveInfo());
    }

    private void showChannelAndAutoHide() {
        if (channelNumberTextView != null && currentChannelNumber > 0) {
            channelNumberTextView.setText(String.valueOf(currentChannelNumber));
            channelNumberTextView.setVisibility(android.view.View.VISIBLE);
            mHandler.removeCallbacks(hideChannelRunnable);
            mHandler.postDelayed(hideChannelRunnable, 3000);
        }
    }

    private void hideChannelNum() {
        if (channelNumberTextView != null) channelNumberTextView.setVisibility(android.view.View.GONE);
    }

    // ==================== Public API (Delegating) ====================

    public static boolean isSoftwareDecoder(MediaCodecInfo codec) {
        return PlayerDecoderManager.isSoftwareDecoder(codec);
    }

    public static boolean isUnstableHardwareDecoder(String codecName) {
        return PlayerDecoderManager.isUnstableHardwareDecoder(codecName);
    }

    public static List<MediaCodecInfo> applyCodecPolicy(List<MediaCodecInfo> allCodecs, int mode) {
        return PlayerDecoderManager.applyCodecPolicy(allCodecs, mode);
    }

    public void playUrl(String url) {
        playbackManager.playUrl(url);
    }

    public void playUrl(String url, String channelName) {
        playbackManager.playUrl(url, channelName);
    }

    public void playUrl(String url, String channelName, Channel channel) {
        playbackManager.playUrl(url, channelName, channel);
    }

    public Channel getCurrentChannel() {
        return currentChannel;
    }

    public boolean isHuyaSource(String url) {
        return playbackManager.isHuyaSource(url);
    }

    public void setDecoderMode(int mode) {
        decoderManager.setDecoderMode(mode);
    }

    public int getDecoderMode() {
        return decoderManager.getDecoderMode();
    }

    public void registerDecoderModeReceiver() {
        decoderManager.registerDecoderModeReceiver();
    }

    public void unregisterDecoderModeReceiver() {
        decoderManager.unregisterDecoderModeReceiver();
    }

    public void registerRendererModeReceiver() {
        decoderManager.registerRendererModeReceiver();
    }

    public void unregisterRendererModeReceiver() {
        decoderManager.unregisterRendererModeReceiver();
    }

    public void onForeground() {
        decoderManager.onForeground();
    }

    public void onBackground() {
        decoderManager.onBackground();
    }

    public void detachPlayerView() {
        decoderManager.detachPlayerView();
    }

    public void attachPlayerView(PlayerView view) {
        decoderManager.attachPlayerView(view);
    }

    public void setScaleMode(ScaleMode mode) {
        decoderManager.setScaleMode(mode);
    }

    public void setSurface(android.view.Surface surface) {
        decoderManager.setSurface(surface);
    }

    public List<String> getAvailableResolutions() {
        return qualityManager.getAvailableResolutions();
    }

    public List<String> getAvailableLines() {
        return qualityManager.getAvailableLines();
    }

    public void switchToResolution(int targetHeight, String... matchLabelOpt) {
        qualityManager.switchToResolution(targetHeight, matchLabelOpt);
    }

    @Deprecated
    public void switchToResolution(int targetHeight) {
        qualityManager.switchToResolution(targetHeight, (String) null);
    }

    public void switchToHuyaLine(int lineIndex) {
        qualityManager.switchToHuyaLine(lineIndex);
    }

    public String getCurrentResolutionLabel() {
        return currentResolutionLabel;
    }

    public void setOnPlayerViewRecreatedListener(OnPlayerViewRecreatedListener listener) {
        this.onPlayerViewRecreatedListener = listener;
    }

    public void setOnPlayStateListener(OnPlayStateListener l) {
        this.listener = l;
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.liveInfoUpdateListener = listener;
    }

    public void setOnSourceFailedListener(OnSourceFailedListener listener) {
        this.sourceFailedListener = listener;
    }

    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    public void bindChannelText(TextView textView) {
        this.channelNumberTextView = textView;
    }

    public void onReceiveConfig(String liveUrl, String epgUrl) {
        currentChannelNumber = 0;
    }

    public void pause() {
        try {
            if (player != null) player.pause();
        } catch (Exception ignored) {}
    }

    public void resume() {
        try {
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
                        info.bitrate = String.format(Locale.ROOT, "%.1f Mbps", videoFormat.bitrate / 1000000f);
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
        if (android.text.TextUtils.isEmpty(mimeType)) return "未知";
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

    public void release() {
        try {
            retryManager.stopStuckDetection();
            retryManager.cancelRetry();
            mHandler.removeCallbacksAndMessages(null);
            decoderManager.updateWakeLock(false);
            decoderManager.release();
            instance = null;
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }
}