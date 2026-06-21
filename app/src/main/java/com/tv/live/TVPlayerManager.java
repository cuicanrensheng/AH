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
 * 【防卡优化版 + 切台优化版 + 真实数据版 + 多平台增强版】
 * 1. 增大缓冲（从15秒→50秒），抗网络波动
 * 2. 检测播放卡住，自动重新加载
 * 3. 支持硬解码/软解码切换
 * 4. 切台保持最后一帧，避免黑屏
 * 5. 显示真实画质、音频、码率
 * 6. ✅ 2026-06-21 新增：浏览器 UA + 多平台自动识别 + 智能 Referer
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
    // 防卡优化相关成员变量
    // ================================================
    private boolean useSoftwareDecoder = false;
    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;
    private static final long STUCK_TIMEOUT = 5000;
    private int retryCount = 0;
    private static final int MAX_RETRY_COUNT = 3;
    private final Handler stuckHandler = new Handler(Looper.getMainLooper());
    private boolean isRetrying = false;

    // ====================================================================
    // ✅ 2026-06-21 新增：浏览器 User-Agent（模拟 Chrome 浏览器）
    // ====================================================================
    private static final String BROWSER_USER_AGENT = 
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // ====================================================================
    // ✅ 2026-06-21 新增：各平台 Referer 配置
    // ====================================================================
    private static final String REFERER_HUYA = "https://www.huya.com/";
    private static final String REFERER_DOUYU = "https://www.douyu.com/";
    private static final String REFERER_DOUYIN = "https://live.douyin.com/";
    private static final String REFERER_BILIBILI = "https://live.bilibili.com/";
    private static final String REFERER_KUAISHOU = "https://live.kuaishou.com/";
    private static final String REFERER_IQIYI = "https://www.iqiyi.com/";
    private static final String REFERER_TENCENT = "https://v.qq.com/";
    private static final String REFERER_YOUKU = "https://www.youku.com/";
    private static final String REFERER_MIGU = "https://www.miguvideo.com/";
    private static final String REFERER_CCTV = "https://tv.cctv.com/";

    /**
     * 直播信息实体类
     */
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
            if (player != null) {
                Format videoFormat = player.getVideoFormat();
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
                    info.videoWidth = 0;
                    info.videoHeight = 0;
                }
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    int channels = audioFormat.channelCount;
                    if (channels == 1) {
                        info.audio = "单声道";
                    } else if (channels == 2) {
                        info.audio = "立体声";
                    } else if (channels >= 6) {
                        info.audio = "5.1";
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

    private void initPlayer() {
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

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        initPlayerListener();

        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
                Log.e(TAG, "错误类型: " + error.errorCode);
                if (error.getCause() != null) {
                    Log.e(TAG, "根本原因: " + error.getCause().getMessage());
                }
                if (listener != null) {
                    listener.onPlayError(error.getMessage());
                }
                autoRetry("播放错误");
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

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    lastPositionUpdateTime = System.currentTimeMillis();
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                int width = videoSize.width;
                int height = videoSize.height;
                Log.d(TAG, "视频分辨率变化：" + width + "×" + height);
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }

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
            if (player == null || !player.isPlaying()) {
                stuckHandler.postDelayed(this, 2000);
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

    // ====================================================================
    // ✅ 2026-06-21 重写：多平台自动识别 + 智能请求头
    //
    // 【支持的平台】
    // 1. 虎牙直播
    // 2. 斗鱼直播
    // 3. 抖音直播
    // 4. B站直播
    // 5. 快手直播
    // 6. 爱奇艺
    // 7. 腾讯视频
    // 8. 优酷
    // 9. 咪咕视频
    // 10. CCTV央视
    // 11. 其他普通源（默认）
    // ====================================================================
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();

        // ✅ 1. 统一用浏览器 User-Agent（最重要！）
        headers.put("User-Agent", BROWSER_USER_AGENT);

        // ✅ 2. 标准浏览器请求头
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Connection", "keep-alive");
        headers.put("Icy-MetaData", "1");

        // ✅ 3. 多平台自动识别，设置对应 Referer
        String platform = detectPlatform(url);
        String referer = getRefererForPlatform(platform);
        
        if (referer != null) {
            headers.put("Referer", referer);
            Log.d(TAG, "识别到平台：" + platform + "，设置 Referer");
        } else {
            Log.d(TAG, "普通直播源，不设置 Referer");
        }

        // ✅ 4. 部分平台需要特殊的 Origin 头
        String origin = getOriginForPlatform(platform);
        if (origin != null) {
            headers.put("Origin", origin);
        }

        // 5. Cookie
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }

        return headers;
    }

    // ====================================================================
    // ✅ 平台识别：根据 URL 判断是哪个平台
    // ====================================================================
    private String detectPlatform(String url) {
        if (url == null) return "unknown";
        String lowerUrl = url.toLowerCase();

        // 虎牙
        if (lowerUrl.contains("huya.com") || lowerUrl.contains("huya.cn")) {
            return "huya";
        }
        // 斗鱼
        if (lowerUrl.contains("douyu.com") || lowerUrl.contains("douyucdn.cn")) {
            return "douyu";
        }
        // 抖音
        if (lowerUrl.contains("douyin.com") || lowerUrl.contains("douyincdn.com") 
                || lowerUrl.contains("bytecdn.cn") || lowerUrl.contains("toutiao.com")) {
            return "douyin";
        }
        // B站
        if (lowerUrl.contains("bilibili.com") || lowerUrl.contains("bilivideo.com")
                || lowerUrl.contains("hdslb.com")) {
            return "bilibili";
        }
        // 快手
        if (lowerUrl.contains("kuaishou.com") || lowerUrl.contains("kslive.com")
                || lowerUrl.contains("ksyun.com")) {
            return "kuaishou";
        }
        // 爱奇艺
        if (lowerUrl.contains("iqiyi.com") || lowerUrl.contains("qiyi.com")
                || lowerUrl.contains("iqiyipic.com")) {
            return "iqiyi";
        }
        // 腾讯视频
        if (lowerUrl.contains("qq.com") || lowerUrl.contains("video.qq.com")
                || lowerUrl.contains("tencentvideo.com")) {
            return "tencent";
        }
        // 优酷
        if (lowerUrl.contains("youku.com") || lowerUrl.contains("ykimg.com")) {
            return "youku";
        }
        // 咪咕视频
        if (lowerUrl.contains("miguvideo.com") || lowerUrl.contains("migu.cn")) {
            return "migu";
        }
        // CCTV央视
        if (lowerUrl.contains("cctv.com") || lowerUrl.contains("cctv.cn")
                || lowerUrl.contains("cntv.cn")) {
            return "cctv";
        }

        // 其他普通源
        return "normal";
    }

    // ====================================================================
    // ✅ 根据平台获取对应的 Referer
    // ====================================================================
    private String getRefererForPlatform(String platform) {
        switch (platform) {
            case "huya":
                return REFERER_HUYA;
            case "douyu":
                return REFERER_DOUYU;
            case "douyin":
                return REFERER_DOUYIN;
            case "bilibili":
                return REFERER_BILIBILI;
            case "kuaishou":
                return REFERER_KUAISHOU;
            case "iqiyi":
                return REFERER_IQIYI;
            case "tencent":
                return REFERER_TENCENT;
            case "youku":
                return REFERER_YOUKU;
            case "migu":
                return REFERER_MIGU;
            case "cctv":
                return REFERER_CCTV;
            default:
                // 普通源不设置 Referer，避免加错了反效果
                return null;
        }
    }

    // ====================================================================
    // ✅ 根据平台获取对应的 Origin（部分平台需要）
    // ====================================================================
    private String getOriginForPlatform(String platform) {
        switch (platform) {
            case "douyin":
                return "https://live.douyin.com";
            case "bilibili":
                return "https://live.bilibili.com";
            case "kuaishou":
                return "https://live.kuaishou.com";
            default:
                return null;
        }
    }

    public void play(String url) {
        playUrl(url);
    }

    public void playUrl(String url) {
        retryCount = 0;
        isRetrying = false;
        playUrlInternal(url);
    }

    /**
     * 内部播放方法
     * 
     * 【注意】Factory 保持原来的用法，不调用不存在的方法
     */
    private void playUrlInternal(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            Log.d(TAG, "开始播放：" + currentUrl);

            // ===== 创建数据源（带重定向日志版） =====
            // 保持原来的用法，只设置 headers 和允许跨协议重定向
            RedirectLoggingHttpDataSource.Factory httpFactory =
                    new RedirectLoggingHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true);

            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            com.google.android.exoplayer2.source.MediaSource mediaSource;

            if (currentUrl.toLowerCase().contains("m3u8")) {
                Log.d(TAG, "流格式：HLS (m3u8)");
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                Log.d(TAG, "流格式：普通流 (Progressive)");
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            player.setMediaSource(mediaSource, true);
            player.prepare();
            player.play();

            startStuckDetection();
        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            autoRetry("播放异常：" + e.getMessage());
        }
    }

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
