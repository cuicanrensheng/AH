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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 播放器管理类（单例模式）
 * 基于ExoPlayer封装，提供直播播放、状态监听、画质切换、Header设置等功能
 *
 * 【稳定版 - 基于老版本优化】
 * 1. 用回 DefaultHttpDataSource，保证稳定性（老版本能正常播放虎牙）
 * 2. 简化Header，只保留必要的，避免触发防盗链
 * 3. 智能识别平台：虎牙用虎牙Referer，斗鱼用斗鱼Referer
 * 4. 修复监听器重复添加的bug（只添加一次）
 * 5. 保留自动重定向支持
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

    // ✅ 播放状态监听器（成员变量，只添加一次，避免重复回调）
    private Player.Listener playerListener;

    /**
     * 直播信息实体类
     * 包含画质、音频、码率、频道号等信息
     */
    public static class LiveInfo {
        public String quality;   // 画质（HD/FHD等）
        public String audio;     // 音频类型（立体声/单声道等）
        public String bitrate;   // 码率
        public int channelNum;   // 频道号
    }

    /**
     * 直播信息更新监听器接口
     */
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    /**
     * 设置直播信息更新监听器
     * @param listener 监听器实例
     */
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    /**
     * 获取当前直播信息
     * @return LiveInfo 直播信息对象
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
     * @param num 频道号
     */
    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    /**
     * 通知直播信息更新
     * 在主线程回调监听器
     */
    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    infoUpdateListener.onLiveInfoUpdate(getLiveInfo()));
        }
    }

    /**
     * 绑定频道号显示TextView
     * @param textView 频道号显示控件
     */
    public void bindChannelText(TextView textView) {
        this.channelNumText = textView;
    }

    /**
     * 显示频道号并自动隐藏
     * 显示时长由CHANNEL_SHOW_DURATION控制
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
     * @param ctx 上下文
     * @return TVPlayerManager实例
     */
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    /**
     * 私有构造函数（单例模式）
     * 初始化ExoPlayer、渲染器、缓冲区、监听器、Cookie管理器等
     * @param ctx 上下文
     */
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        // 初始化渲染器工厂，启用解码器降级
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // 初始化缓冲区配置
        // 最小缓冲3秒，最大30秒，开始播放缓冲1.5秒，缓冲后重试3秒
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 1500, 500, 2000)
                .build();

        // 创建ExoPlayer实例
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // ✅ 初始化播放监听器（只创建一次，避免每次playUrl都addListener）
        initPlayerListener();

        // 初始化Cookie管理器，支持自动携带Cookie
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    /**
     * ✅ 初始化播放状态监听器
     * 只创建一次，避免每次playUrl都addListener导致重复回调
     */
    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
                // 回调错误状态给上层
                if (listener != null) {
                    listener.onPlayError(error.getMessage());
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    // 播放就绪：保持屏幕常亮、更新直播信息、显示频道号
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();
                    if (listener != null) listener.onPlayReady();
                } else if (state == Player.STATE_BUFFERING) {
                    // 缓冲中
                    if (listener != null) listener.onBuffering();
                } else if (state == Player.STATE_ENDED) {
                    // 播放结束
                    if (listener != null) listener.onPlayEnd();
                } else if (state == Player.STATE_IDLE) {
                    // 空闲状态
                    if (listener != null) listener.onIdle();
                } else {
                    // 其他状态：取消屏幕常亮
                    updateWakeLock(false);
                }
            }
        };
        // 只添加一次监听器
        player.addListener(playerListener);
    }

    /**
     * 切到前台：恢复播放
     */
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

    /**
     * 切到后台：暂停播放
     */
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
     * @param view PlayerView实例
     */
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
        playerView.setUseController(false); // 隐藏原生控制器
    }

    /**
     * 更新屏幕常亮状态
     * @param enable 是否保持常亮
     */
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    /**
     * 获取格式化的日志时间
     * @return 时间字符串，格式HH:mm:ss
     */
    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }

    /**
     * ✅ 生成请求Header（稳定版）
     *
     * 【设计思路】
     * 老版本就是简单的几个Header，能正常播放虎牙
     * 新版本加了太多Header（Origin、Host、Sec-Fetch-*等），反而触发防盗链
     * 所以改回简洁风格，只保留必要的
     *
     * 【智能识别】
     * - 虎牙地址 → Referer 用虎牙官网
     * - 斗鱼地址 → Referer 用斗鱼官网
     * - 其他地址 → 默认用虎牙Referer兜底
     *
     * @param url 播放地址
     * @return Header键值对Map
     */
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();

        // ===== 基础Header（和老版本保持一致，简洁稳定） =====
        // UA 用 ExoPlayer，不要用浏览器UA，反而不自然
        headers.put("User-Agent", "ExoPlayer");
        // 接受所有类型
        headers.put("Accept", "*/*");
        // 保持长连接
        headers.put("Connection", "keep-alive");
        // 支持ICY元数据（部分电台流需要）
        headers.put("Icy-MetaData", "1");

        // ===== 智能识别平台，设置对应的Referer =====
        boolean isHuya = url.contains("huya.com") || url.contains("huya.cn");
        boolean isDouyu = url.contains("douyu.com") || url.contains("douyucdn.cn");

        if (isHuya) {
            // 虎牙：用虎牙官网Referer
            headers.put("Referer", "https://www.huya.com/");
            Log.d(TAG, "虎牙直播，设置虎牙Referer");
        }
        else if (isDouyu) {
            // 斗鱼：用斗鱼官网Referer
            headers.put("Referer", "https://www.douyu.com/");
            Log.d(TAG, "斗鱼直播，设置斗鱼Referer");
        }
        else {
            // 其他地址：默认用虎牙Referer兜底
            headers.put("Referer", "https://www.huya.com/");
        }

        // ===== Cookie处理 =====
        // 自动携带WebView中保存的Cookie
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }

        return headers;
    }

    /**
     * 播放指定URL（play方法，兼容旧代码）
     * @param url 播放地址
     */
    public void play(String url) {
        playUrl(url);
    }

    /**
     * ✅ 播放指定URL（核心播放方法）
     *
     * 【说明】
     * 用回系统自带的 DefaultHttpDataSource，稳定可靠
     * 自动跟随重定向，Header全程生效
     *
     * @param url 播放地址
     */
    public void playUrl(String url) {
        try {
            // 参数校验
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();

            Log.d(TAG, "开始播放：" + currentUrl);

            // 停止当前播放，清空媒体项
            player.stop();
            player.clearMediaItems();

            // ===== 用系统自带的 DefaultHttpDataSource =====
            // 稳定可靠，老版本就是用的这个，能正常播放虎牙
            // setAllowCrossProtocolRedirects：支持跨协议重定向（http→https）
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(getHeaders(currentUrl))
                    .setConnectTimeoutMs(5000)
                    .setReadTimeoutMs(10000)
                    .setAllowCrossProtocolRedirects(true);

            // 创建媒体项
            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            com.google.android.exoplayer2.source.MediaSource mediaSource;

            // ===== 根据URL类型选择媒体源 =====
            if (currentUrl.toLowerCase().contains("m3u8")) {
                // HLS流（m3u8格式）
                Log.d(TAG, "流格式：HLS (m3u8)");
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                // 普通渐进式流（flv/mp4等）
                Log.d(TAG, "流格式：普通流 (Progressive)");
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            // 设置媒体源并开始播放
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();

            // ✅ 监听器已移到构造函数里只添加一次，这里不再重复add
            // 避免每次播放都累加监听器，导致出错时回调多次

        } catch (Exception e) {
            Log.e(TAG, "全局异常", e);
        }
    }

    /**
     * 设置屏幕缩放模式
     * @param mode 缩放模式（FIT自适应 / FILL拉伸 / ZOOM裁剪）
     */
    public void setScaleMode(ScaleMode mode) {
        try {
            if (playerView == null) return;
            switch (mode) {
                case FIT:
                    // 自适应：保持宽高比，完整显示
                    playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    break;
                case FILL:
                    // 拉伸：填满屏幕，可能变形
                    playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL);
                    break;
                case ZOOM:
                    // 裁剪：填满屏幕，保持宽高比，超出部分裁剪
                    playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "设置缩放模式异常", e);
        }
    }

    /**
     * 播放状态监听器接口
     */
    public interface OnPlayStateListener {
        void onIdle();        // 空闲状态
        void onBuffering();   // 缓冲中
        void onPlayReady();   // 播放就绪
        void onPlayEnd();     // 播放结束
        void onPlayError(String msg);  // 播放错误
    }

    /**
     * 设置播放状态监听器
     * @param l 监听器实例
     */
    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    /**
     * 暂停播放
     */
    public void pause() {
        try { if (player != null) player.pause(); } catch (Exception e) {
            Log.e(TAG, "暂停异常", e);
        }
    }

    /**
     * 恢复播放
     */
    public void resume() {
        try { if (player != null) player.play(); } catch (Exception e) {
            Log.e(TAG, "恢复异常", e);
        }
    }

    /**
     * 释放播放器资源
     * 页面销毁时调用，避免内存泄漏
     */
    public void release() {
        try {
            mHandler.removeCallbacks(hideChannelRunnable);
            updateWakeLock(false);
            if (player != null) {
                // 移除监听器
                if (playerListener != null) {
                    player.removeListener(playerListener);
                }
                // 释放播放器
                player.release();
                player = null;
            }
            instance = null;
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }
}
