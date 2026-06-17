package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.TextView;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ================================================
 * TV直播播放器管理类
 * 功能：
 * 1. 封装ExoPlayer播放器核心逻辑
 * 2. 支持HTTP/HTTPS/HLS直播流播放
 * 3. 自动处理防盗链（Referer/Cookie/User-Agent）
 * 4. 完整的播放状态日志系统
 * 5. 画面缩放模式管理（适配/填充/裁剪）
 * 6. 前后台切换自动暂停/恢复
 * ================================================
 */
public class TVPlayerManager {
    private static final String TAG = "TVPlayerLog";
    private static TVPlayerManager instance;          // 单例实例
    private ExoPlayer player;                          // ExoPlayer核心播放器
    private Context context;                           // 上下文
    private PlayerView playerView;                      // 播放器视图
    private String currentPlayUrl = "";                 // 当前播放地址

    /**
     * 画面缩放模式
     * FIT:  等比例适配，黑边填充
     * FILL: 拉伸填充全屏
     * ZOOM: 等比例裁剪铺满全屏
     */
    public enum ScaleMode { FIT, FILL, ZOOM }

    private OnPlayStateListener listener;              // 播放状态回调
    private String currentUrl = "";                     // 当前URL
    private boolean isPlaying = false;                  // 是否正在播放
    private int currentChannelNumber = 0;               // 当前频道号

    private TextView channelNumText;                    // 频道号显示控件
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_SHOW_DURATION = 3000L;  // 频道号显示时长3秒
    private final SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private OnLiveInfoUpdateListener infoUpdateListener;  // 直播信息更新回调

    /**
     * 直播流信息结构体
     */
    public static class LiveInfo {
        public String quality;    // 画质（HD/SD/FHD）
        public String audio;      // 音频格式
        public String bitrate;    // 码率
        public int channelNum;    // 频道号
    }

    /**
     * 直播信息更新监听接口
     */
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    /**
     * 设置直播信息更新监听器
     */
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    /**
     * 获取当前直播流信息
     */
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "HD";
        info.audio = "立体声";
        info.bitrate = "4.5MB/s";
        info.channelNum = currentChannelNumber;
        return info;
    }

    /**
     * 设置当前频道号
     */
    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    /**
     * 通知UI更新直播信息
     */
    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    infoUpdateListener.onLiveInfoUpdate(getLiveInfo()));
        }
    }

    /**
     * 绑定频道号显示TextView
     */
    public void bindChannelText(TextView textView) {
        this.channelNumText = textView;
    }

    /**
     * 显示频道号并自动隐藏
     */
    private void showChannelAndAutoHide() {
        if (channelNumText == null) return;
        mHandler.removeCallbacks(hideChannelRunnable);
        channelNumText.setText("频道：" + currentChannelNumber);
        channelNumText.setVisibility(View.VISIBLE);
        mHandler.postDelayed(hideChannelRunnable, CHANNEL_SHOW_DURATION);
    }

    /**
     * 隐藏频道号的Runnable
     */
    private final Runnable hideChannelRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelNumText != null) {
                channelNumText.setVisibility(View.GONE);
            }
        }
    };

    /**
     * 获取播放器单例
     */
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    /**
     * ================================================
     * 构造函数：初始化ExoPlayer
     * ================================================
     */
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        
        // 1. 渲染器工厂：支持硬解码降级
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // 2. 缓冲控制：3秒启动缓冲，30秒最大缓冲
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 30000, 1500, 3000)
                .build();

        // 3. 创建ExoPlayer实例
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // 4. 初始化Cookie管理器（用于防盗链）
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
        
        SettingsActivity.log(getLogTime() + " 播放器初始化完成");
    }

    /**
     * 切换到后台：暂停播放
     */
    public void onBackground() {
        try {
            if (player != null) {
                player.pause();
            }
            if (playerView != null) {
                playerView.setPlayer(null);
            }
        } catch (Exception e) {}
        SettingsActivity.log(getLogTime() + " 切换到后台");
    }

    /**
     * 切换到前台：恢复播放
     */
    public void onForeground() {
        try {
            if (player != null && playerView != null) {
                playerView.setPlayer(player);
                player.play();
            }
        } catch (Exception e) {
            // 恢复失败，重新播放
            if (!currentPlayUrl.isEmpty()) {
                playUrl(currentPlayUrl);
            }
        }
        SettingsActivity.log(getLogTime() + " 切换到前台");
    }

    /**
     * 绑定播放器视图
     */
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
        playerView.setUseController(false);  // 禁用原生控制器
    }

    /**
     * 更新屏幕常亮状态
     */
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    /**
     * 获取日志时间戳
     */
    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }

    /**
     * ================================================
     * 获取防盗链请求头
     * 关键：Referer/User-Agent/Cookie 绕过403防盗链
     * ================================================
     */
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ExoPlayer");              // 模拟播放器UA
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        headers.put("Icy-MetaData", "1");
        headers.put("Referer", "https://www.huya.com/");      // 虎牙防盗链Referer

        // 自动带上Cookie（关键！）
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }
        return headers;
    }

    /**
     * 播放URL（兼容方法）
     */
    public void play(String url) {
        playUrl(url);
    }

    /**
     * ================================================
     * 核心播放方法
     * 1. 停止上一个播放
     * 2. 创建媒体源（自动区分HLS/普通流）
     * 3. 设置防盗链请求头
     * 4. 开始播放并监听状态
     * ================================================
     */
    public void playUrl(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            currentPlayUrl = currentUrl;

            // 日志：截断过长URL
            String shortUrl = currentUrl.length() > 600 ? currentUrl.substring(0, 600) + "..." : currentUrl;
            SettingsActivity.log(getLogTime() + " 开始播放：" + shortUrl);

            // 停止并清空上一个媒体
            player.stop();
            player.clearMediaItems();

            // HTTP数据源工厂：配置超时和防盗链
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(getHeaders(currentUrl))
                    .setConnectTimeoutMs(5000)      // 连接超时5秒
                    .setReadTimeoutMs(10000)        // 读取超时10秒
                    .setAllowCrossProtocolRedirects(true);  // 允许跨协议重定向

            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            com.google.android.exoplayer2.source.MediaSource mediaSource;

            // 自动区分：m3u8用HLS源，其他用普通流
            if (currentUrl.toLowerCase().contains("m3u8")) {
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            // 开始播放
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();

            // ================================================
            // 播放状态监听
            // ================================================
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "播放异常: " + error.getMessage());
                    SettingsActivity.log(getLogTime() + " ❌ 播放错误：" + error.getMessage());
                    if (listener != null) listener.onPlayError(error.getMessage());

                    // 播放错误1秒后自动重试
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        playUrl(currentUrl);
                    }, 1000);
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        // 播放成功：亮屏 + 更新信息 + 显示频道号
                        updateWakeLock(true);
                        notifyLiveInfoUpdate();
                        showChannelAndAutoHide();
                        SettingsActivity.log(getLogTime() + " ✅ 播放成功");
                        if (listener != null) listener.onPlayReady();
                    } else if (state == Player.STATE_BUFFERING) {
                        // 缓冲中
                        SettingsActivity.log(getLogTime() + " ⏳ 缓冲中...");
                        if (listener != null) listener.onBuffering();
                    } else if (state == Player.STATE_ENDED) {
                        // 播放结束
                        SettingsActivity.log(getLogTime() + " 播放结束");
                        if (listener != null) listener.onPlayEnd();
                    } else if (state == Player.STATE_IDLE) {
                        // 空闲状态
                        if (listener != null) listener.onIdle();
                    } else {
                        // 其他状态：熄屏
                        updateWakeLock(false);
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "全局异常", e);
            SettingsActivity.log(getLogTime() + " ❌ 异常：" + e.getMessage());
        }
    }

    /**
     * 设置画面缩放模式
     */
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

    /**
     * 播放状态回调接口
     */
    public interface OnPlayStateListener {
        void onIdle();        // 空闲
        void onBuffering();   // 缓冲中
        void onPlayReady();   // 播放成功
        void onPlayEnd();     // 播放结束
        void onPlayError(String msg);  // 播放错误
    }

    /**
     * 设置播放状态监听器
     */
    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    /**
     * 暂停播放
     */
    public void pause() {
        try { if (player != null) player.pause(); } catch (Exception e) {}
    }

    /**
     * 恢复播放
     */
    public void resume() {
        try { if (player != null) player.play(); } catch (Exception e) {}
    }

    /**
     * 释放播放器资源
     */
    public void release() {
        try {
            mHandler.removeCallbacks(hideChannelRunnable);
            updateWakeLock(false);
            if (player != null) {
                player.release();
                player = null;
            }
            instance = null;
            SettingsActivity.log(getLogTime() + " 播放器释放");
        } catch (Exception e) {}
    }
}
