package com.tv.live;
import android.widget.Toast;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

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
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.tv.live.util.NetUtil;
import com.tv.live.exception.RedirectFailedException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import okhttp3.Headers;

public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";
    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_HARD = 1;
    public static final int DECODER_MODE_SOFT = 2;
    // 🔴【新增】FFmpeg 扩展模式常量
    public static final int DECODER_MODE_FFMPEG = 3;
    
    private static final int MAX_RETRY_COUNT = 2;
    private static final long STUCK_TIMEOUT = 20000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;

    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";

    private static TVPlayerManager instance;
    private Context context;
    private ExoPlayer player;
    private PlayerView playerView;
    private Player.Listener playerListener;
    private String currentUrl;
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

    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;
    private Runnable stuckCheckRunnable;

    private Handler mHandler;
    private Runnable hideChannelRunnable;

    private OnPlayStateListener listener;
    private OnSourceFailedListener sourceFailedListener;
    private OnLiveInfoUpdateListener liveInfoUpdateListener;
    private boolean isPlaying = false;
    private SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private BroadcastReceiver decoderModeReceiver;
    private boolean decoderReceiverRegistered = false;
    private BroadcastReceiver rendererModeReceiver;
    private boolean rendererReceiverRegistered = false;

    private OnPlayerViewRecreatedListener onPlayerViewRecreatedListener;
    private boolean isRenderingSwitching = false;

    private final Map<String, String> reusableHeaderMap = new HashMap<>();

    private DefaultTrackSelector trackSelector;

    private ScaleMode mCurrentScaleMode = ScaleMode.FILL;

    // 清晰度相关
    private List<Variant> variantList = new ArrayList<>();
    private boolean isParsingMasterPlaylist = false;

    // 🔴【新增】用于控制日志开关的 SharedPreferences
    private SharedPreferences sp;

    // 清晰度实体类
    public static class Variant {
        public String url;
        public int bandwidth;
        public int width;
        public int height;
        public String resolutionLabel; // 如 "720p", "1080p"

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
    }

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
        mHandler = new Handler(Looper.getMainLooper());

        hideChannelRunnable = () -> hideChannelNum();

        stuckCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (player == null || !player.isPlaying()) {
                    mHandler.postDelayed(this, 2000);
                    return;
                }
                try {
                    long currentPosition = player.getCurrentPosition();
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
                mHandler.postDelayed(this, 2000);
            }
        };
        initPlayer();
    }
    
    // 🔴【修改】将日志同时写入 LogCollector，实现弹窗查看
    private void dLog(String msg) {
        if (sp.getBoolean("log_enable", false)) {
            Log.d(TAG, msg);
            com.tv.live.util.LogCollector.getInstance().addLog(TAG, msg);
        }
    }
    
    private void initPlayer() {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        SoftwareFirstMediaCodecSelector codecSelector = new SoftwareFirstMediaCodecSelector(mDecoderMode);
        renderersFactory.setMediaCodecSelector(codecSelector);

        switch (mDecoderMode) {
            case DECODER_MODE_SOFT:
                dLog("【解码器】软解模式");
                break;
            case DECODER_MODE_HARD:
                dLog("【解码器】硬解模式");
                break;
            case DECODER_MODE_FFMPEG:
                dLog("【解码器】FFmpeg 软解扩展模式");
                renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
                break;
            case DECODER_MODE_AUTO:
            default:
                dLog("【解码器】自动模式");
                break;
        }

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 45000, 800, 1500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        trackSelector = new DefaultTrackSelector(context);

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .build();

        try {
            List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos("video/avc", false, false);
            int softCount = 0, hardCount = 0;
            for (MediaCodecInfo codec : h264Codecs) {
                if (isSoftwareDecoder(codec)) softCount++;
                else hardCount++;
            }
            dLog("【解码器】软解 " + softCount + " 个，硬解 " + hardCount + " 个");
        } catch (Exception ignored) {
        }

        initPlayerListener();
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    private static boolean isSoftwareDecoder(MediaCodecInfo codec) {
        if (codec == null) return false;
        String name = codec.name;
        if (name == null) return false;
        String lowerName = name.toLowerCase();
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    private void initPlayerListener() {
        if (playerListener != null) return;
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
                
                // 🔴【新增】检测 FFmpeg 解码失败的情况
                if (mDecoderMode == DECODER_MODE_FFMPEG) {
                    String errMsg = error.getMessage();
                    if (errMsg != null && (errMsg.contains("DecoderInitializationException") 
                            || errMsg.contains("FFmpeg") 
                            || errMsg.contains("soft decoder"))) {
                        Toast.makeText(context, "FFmpeg 解码不支持该视频格式，已自动切换回系统解码", Toast.LENGTH_LONG).show();
                        dLog("【FFmpeg 提示】解码失败，回退系统解码：\n" + errMsg);
                    }
                }

                Throwable rootCause = error.getCause();
                boolean isRedirectError = false;
                while (rootCause != null) {
                    if (rootCause instanceof RedirectFailedException) {
                        isRedirectError = true;
                        break;
                    }
                    rootCause = rootCause.getCause();
                }
                if (listener != null) listener.onPlayError(error.getMessage());

                if (!isRedirectError) {
                    boolean switched = trySwitchBackup();
                    if (switched) return;
                }

                if (sourceFailedListener != null) {
                    sourceFailedListener.onSourceFailed();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();
                    if (listener != null) listener.onPlayReady();
                    retryCount = 0;
                    isRetrying = false;
                    startStuckDetection();
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
                    autoRetry("播放结束");
                } else if (state == Player.STATE_IDLE) {
                    if (listener != null) listener.onIdle();
                    updateWakeLock(false);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
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
        if (reason.contains("RedirectFailedException") || reason.contains("重定向")) {
            return;
        }
        if (isRetrying) return;
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限：" + MAX_RETRY_COUNT + "，判定为失效源");
            if (sourceFailedListener != null) {
                mHandler.post(() -> sourceFailedListener.onSourceFailed());
            }
            return;
        }
        isRetrying = true;
        retryCount++;
        Log.w(TAG, "自动重试（第" + retryCount + "次），原因：" + reason);
        retryRunnable = () -> {
            isRetrying = false;
            if (!TextUtils.isEmpty(currentUrl)) {
                playUrlInternal(currentUrl);
            }
            retryRunnable = null;
        };
        mHandler.postDelayed(retryRunnable, 3000);
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
        boolean wasPlaying = player != null && player.isPlaying();

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
        if (playerView != null) {
            mHandler.post(() -> {
                if (playerView != null && player != null) {
                    playerView.setPlayer(player);
                }
            });
        }
        if (!TextUtils.isEmpty(currentUrl)) {
            retryCount = 0;
            isRetrying = false;
            
            // 🔴【新增】切换成功提示
            if (mDecoderMode == DECODER_MODE_FFMPEG) {
                Toast.makeText(context, "已切换至 FFmpeg 软解扩展模式", Toast.LENGTH_SHORT).show();
            } else if (mDecoderMode == DECODER_MODE_SOFT) {
                Toast.makeText(context, "已切换至 软解模式", Toast.LENGTH_SHORT).show();
            } else if (mDecoderMode == DECODER_MODE_HARD) {
                Toast.makeText(context, "已切换至 硬解模式", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "已切换至 自动模式", Toast.LENGTH_SHORT).show();
            }

            playUrlInternal(currentUrl, currentPosition);
        }
        isSwitching = false;
    }

    public int getDecoderMode() {
        return mDecoderMode;
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
                        else if ("ffmpeg".equals(modeStr)) mode = DECODER_MODE_FFMPEG;
                        setDecoderMode(mode);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
            context.registerReceiver(decoderModeReceiver, filter);
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
        if (playerView == null || context == null) return;
        FrameLayout parent = (FrameLayout) playerView.getParent();
        if (parent == null) return;

        View blackMask = new View(context);
        blackMask.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams maskParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        parent.addView(blackMask, maskParams);
        blackMask.bringToFront();

        isRenderingSwitching = true;
        bufferCount = 0;
        long currentPosition = player.getCurrentPosition();
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

        parent.addView(newPlayerView, index, layoutParams);
        playerView.setPlayer(null);
        parent.removeView(playerView);
        playerView = newPlayerView;

        if (currentPosition > 0) player.seekTo(currentPosition);
        if (wasPlaying) {
            mHandler.postDelayed(() -> {
                if (player != null && !player.isPlaying()) player.play();
            }, 200);
        }

        if (onPlayerViewRecreatedListener != null) {
            onPlayerViewRecreatedListener.onPlayerViewRecreated(newPlayerView);
        }
        playerView.requestFocus();

        playerView.postDelayed(() -> {
            blackMask.animate().alpha(0f).setDuration(250).withEndAction(() -> parent.removeView(blackMask)).start();
        }, 100);

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
            context.registerReceiver(rendererModeReceiver, filter);
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
            if (player != null) player.pause();
        } catch (Exception e) {
            Log.e(TAG, "切后台异常", e);
        }
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String rendererMode = sp.getString("renderer_type", "surface");
        switchRenderer("texture".equals(rendererMode));
        playerView.setPlayer(player);
        playerView.setUseController(false);
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

    private void playUrlInternal(String url) {
        playUrlInternal(url, 0);
    }

    private void playUrlInternal(String url, long initialSeekPosition) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;

            String playUrl = url.trim();
            if (currentChannel != null) {
                SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                int lineIndex = sp.getInt("channel_line_index", 0);
                if (lineIndex == 0) {
                    playUrl = currentChannel.getMainPlayUrl();
                } else {
                    List<String> backups = currentChannel.getBackupUrls();
                    int backupIndex = lineIndex - 1;
                    if (backupIndex >= 0 && backupIndex < backups.size()) {
                        playUrl = backups.get(backupIndex);
                    } else {
                        playUrl = currentChannel.getMainPlayUrl();
                        Log.w(TAG, "线路索引越界，已自动切回主源");
                    }
                }
                currentUrl = playUrl;
                dLog("切换线路后播放：" + currentUrl);
            } else {
                currentUrl = playUrl;
            }

            // 异步解析直播源（主播放列表）
            if (currentUrl.toLowerCase().contains("m3u8")) {
                fetchAndParseMasterPlaylist(currentUrl);
            } else {
                variantList.clear();
            }

            RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
            Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(currentUrl);
            reusableHeaderMap.clear();
            for (String name : globalHeaders.names()) {
                reusableHeaderMap.put(name, globalHeaders.get(name));
            }

            SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
            if (sendCookie) {
                String cookies = CookieManager.getInstance().getCookie(currentUrl);
                if (cookies != null) reusableHeaderMap.put("Cookie", cookies);
            }

            httpFactory.setDefaultRequestProperties(reusableHeaderMap);
            httpFactory.setChannelName(currentChannelName);
            httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                    .setAllowCrossDomainRedirects(sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true))
                    .setAllowCrossProtocolRedirects(sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true))
                    .setFollowRedirectsWithHeaders(sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true))
                    .setIgnoreSslErrorRedirect(sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false))
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(10000);

            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            MediaSource mediaSource;
            if (currentUrl.toLowerCase().contains("m3u8")) {
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            player.setMediaSource(mediaSource, true);
            player.prepare();
            if (initialSeekPosition > 0) player.seekTo(initialSeekPosition);
            player.play();
            startStuckDetection();

        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            if (e instanceof RedirectFailedException) {
                if (listener != null) listener.onPlayError("源跳转失败：" + e.getMessage());
                return;
            }
            autoRetry("播放异常：" + e.getMessage());
        }
    }

    // ********** 解析主播放列表，获取多码率子流 **********
    private void fetchAndParseMasterPlaylist(String masterUrl) {
        if (isParsingMasterPlaylist) return;
        isParsingMasterPlaylist = true;
        new Thread(() -> {
            try {
                dLog("开始解析主播放列表: " + masterUrl);
                URL url = new URL(masterUrl);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
                String cookies = CookieManager.getInstance().getCookie(masterUrl);
                if (cookies != null) connection.setRequestProperty("Cookie", cookies);

                InputStream is = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                reader.close();
                connection.disconnect();

                String playlist = content.toString();
                dLog("播放列表内容长度: " + playlist.length());
                parseMasterPlaylist(playlist, masterUrl);
            } catch (Exception e) {
                Log.e(TAG, "解析主播放列表失败: ", e);
                variantList.clear();
            } finally {
                isParsingMasterPlaylist = false;
            }
        }).start();
    }

    private void parseMasterPlaylist(String playlist, String baseUrl) {
        List<Variant> list = new ArrayList<>();
        Pattern streamPattern = Pattern.compile(
            "#EXT-X-STREAM-INF:[^\\r\\n]*BANDWIDTH=(\\d+)(?:[^\\r\\n]*RESOLUTION=(\\d+x\\d+))?[^\\r\\n]*"
        );
        dLog("播放列表内容（截取前500字符）：\n" + playlist.substring(0, Math.min(playlist.length(), 500)));

        String[] lines = playlist.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher matcher = streamPattern.matcher(line);
            if (matcher.find()) {
                int bandwidth = Integer.parseInt(matcher.group(1));
                String resolutionStr = matcher.group(2);
                int width = 0, height = 0;

                if (resolutionStr != null && !resolutionStr.isEmpty()) {
                    String[] wh = resolutionStr.split("x");
                    width = Integer.parseInt(wh[0]);
                    height = Integer.parseInt(wh[1]);
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
        }
        list.sort((a, b) -> Integer.compare(a.height, b.height));
        this.variantList = list;
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
        for (Variant v : variantList) {
            if (!resolutions.contains(v.resolutionLabel)) {
                resolutions.add(v.resolutionLabel);
            }
        }
        return resolutions;
    }

    public void switchToResolution(int targetHeight) {
        if (variantList.isEmpty()) {
            Log.w(TAG, "无多码率信息，无法切换清晰度");
            return;
        }
        Variant selected = null;
        for (Variant v : variantList) {
            if (v.height >= targetHeight) {
                selected = v;
                break;
            }
        }
        if (selected == null) {
            selected = variantList.get(variantList.size() - 1);
        }
        dLog("切换清晰度到：" + selected.resolutionLabel + "，URL=" + selected.url);
        playUrlInternal(selected.url);
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
                    info.format = videoFormat.sampleMimeType;
                    if (videoFormat.bitrate > 0)
                        info.bitrate = String.format(Locale.getDefault(), "%.1f Mbps", videoFormat.bitrate / 1000000f);
                }
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    info.audio = audioFormat.sampleMimeType;
                    if (audioFormat.sampleRate > 0) info.audio += " " + (audioFormat.sampleRate / 1000) + "kHz";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取直播信息异常", e);
        }
        return info;
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
            if (player != null) player.play();
        } catch (Exception ignored) {}
    }

    public void release() {
        try {
            stopStuckDetection();
            cancelRetry();
            mHandler.removeCallbacksAndMessages(null);
            updateWakeLock(false);
            unregisterDecoderModeReceiver();
            unregisterRendererModeReceiver();
            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                    playerListener = null;
                }
                player.release();
                player = null;
            }
            instance = null;
            if (playerView != null) {
                playerView.setPlayer(null);
                playerView = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }

    private static class SoftwareFirstMediaCodecSelector implements MediaCodecSelector {
        private final int decoderMode;

        public SoftwareFirstMediaCodecSelector(int mode) {
            this.decoderMode = mode;
        }

        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> allCodecs = MediaCodecUtil.getDecoderInfos(mimeType, false, false);
            if (allCodecs == null || allCodecs.isEmpty()) return allCodecs;
            switch (decoderMode) {
                case DECODER_MODE_HARD:
                    List<MediaCodecInfo> hardCodecs = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        if (!isSoftwareDecoder(codec)) hardCodecs.add(codec);
                    }
                    return hardCodecs;
                case DECODER_MODE_SOFT:
                    List<MediaCodecInfo> softCodecs = new ArrayList<>();
                    List<MediaCodecInfo> hardCodecs2 = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        if (isSoftwareDecoder(codec)) softCodecs.add(codec);
                        else hardCodecs2.add(codec);
                    }
                    softCodecs.addAll(hardCodecs2);
                    return softCodecs;
                case DECODER_MODE_AUTO:
                default:
                    return allCodecs;
            }
        }
    }
}
