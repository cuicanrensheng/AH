package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.TextView;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
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
 * 电视播放器管理类（适配你项目路径 + FFmpeg兜底）
 * 单例模式，负责ExoPlayer的初始化、播放、切换比例、异常处理
 */
public class TVPlayerManager {
    // 日志TAG
    private static final String TAG = "TVPlayerLog";

    // 单例实例
    private static TVPlayerManager instance;

    // 主播放器：ExoPlayer（优先用，性能高）
    private ExoPlayer exoPlayer;

    // 备用播放器：FFmpeg软解码器（ExoPlayer失败时兜底，解决虎牙流黑屏/兼容问题）
    private FFmpegDecoder ffmpegDecoder;

    // 上下文
    private Context context;

    // 播放视图
    private PlayerView playerView;

    // 画面比例模式：自适应、拉伸填充、裁剪全屏
    public enum ScaleMode { FIT, FILL, ZOOM }

    // 播放状态监听
    private OnPlayStateListener listener;

    // 当前播放地址
    private String currentUrl = "";

    // 是否正在播放
    private boolean isPlaying = false;

    // 当前频道号
    private int currentChannelNumber = 0;

    // 频道号显示控件
    private TextView channelNumText;

    // 主线程Handler，用于延迟隐藏频道号、播放超时检测
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // 频道号显示时长：3秒
    private static final long CHANNEL_SHOW_DURATION = 3000L;

    // 播放超时检测：3秒（判断ExoPlayer是否黑屏/失败）
    private static final long PLAY_TIMEOUT = 3000L;

    // 日志时间格式化
    private final SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 直播信息更新监听
    private OnLiveInfoUpdateListener infoUpdateListener;

    /**
     * 直播信息实体
     */
    public static class LiveInfo {
        public String quality;    // 画质
        public String audio;      // 音频
        public String bitrate;    // 码率
        public int channelNum;    // 频道号
    }

    /**
     * 直播信息回调接口
     */
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    /**
     * 设置直播信息监听
     */
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    /**
     * 获取当前直播信息
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
     * 通知更新直播信息
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
     * 显示频道号，并在3秒后自动隐藏
     */
    private void showChannelAndAutoHide() {
        if (channelNumText == null) return;

        mHandler.removeCallbacks(hideChannelRunnable);
        channelNumText.setText("频道：" + currentChannelNumber);
        channelNumText.setVisibility(android.view.View.VISIBLE);
        mHandler.postDelayed(hideChannelRunnable, CHANNEL_SHOW_DURATION);
    }

    /**
     * 隐藏频道号的任务
     */
    private final Runnable hideChannelRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelNumText != null) {
                channelNumText.setVisibility(android.view.View.GONE);
            }
        }
    };

    /**
     * 获取单例
     */
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    /**
     * 私有构造（单例）
     * 初始化ExoPlayer、FFmpeg解码器、缓冲策略、Cookie
     */
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        // 1. 初始化ExoPlayer主播放器（开启解码器自动降级兼容）
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        // 【原版默认缓冲配置，已恢复】
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        5000,    // 最小缓冲 5秒
                        20000,   // 最大缓冲 20秒
                        2500,    // 起播缓冲 2.5秒
                        5000     // 回放缓冲 5秒
                )
                .build();

        exoPlayer = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // 2. 初始化FFmpeg软解码器（备用方案，适配你项目里的lib-decoder-ffmpeg-release.aar）
        ffmpegDecoder = new FFmpegDecoder(context);

        // 初始化Cookie支持（适配虎牙防盗链）
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    /**
     * 绑定PlayerView（给ExoPlayer和FFmpeg共用）
     */
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        exoPlayer.setPlayerView(view); // 给ExoPlayer绑定视图
    }

    /**
     * 设置屏幕常亮
     */
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    /**
     * 获取带时间的日志前缀
     */
    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }

    /**
     * 构建播放请求头：适配虎牙防盗链的UA、Referer、Cookie
     */
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        // 虎牙必须带浏览器UA，避免被拦截
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        // 虎牙必须带Referer，否则拉不到流
        headers.put("Referer", "https://www.huya.com/");

        try {
            URI uri = new URI(url);
            headers.put("Referer", uri.getScheme() + "://" + uri.getHost() + "/");
        } catch (Exception e) {
            Log.e(TAG, getLogTime() + " Referer 生成异常", e);
        }

        // 自动携带Cookie
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }
        return headers;
    }

    /**
     * 对外播放接口（自动双播放器兜底，直接调用即可）
     */
    public void play(String url) {
        playWithFallback(url);
    }

    /**
     * 核心：带FFmpeg兜底的播放逻辑
     * 1. 先尝试ExoPlayer播放（性能高）
     * 2. 3秒后如果失败/黑屏，自动切换到FFmpeg软解码（兼容虎牙流）
     */
    private void playWithFallback(String url) {
        try {
            // 空值判断
            if (url == null || url.trim().isEmpty()) {
                Log.e(TAG, getLogTime() + " 播放失败：URL为空");
                return;
            }

            currentUrl = url.trim();
            Log.i(TAG, getLogTime() + " 尝试用ExoPlayer播放虎牙流，地址：" + currentUrl);

            // 先释放之前的播放器
            releaseAllPlayers();

            // 重置ExoPlayer
            exoPlayer.stop();
            exoPlayer.clearMediaItems();

            // 构建带虎牙请求头的HTTP数据源
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true);

            // 构建HLS媒体源
            HlsMediaSource mediaSource = new HlsMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(currentUrl));

            exoPlayer.setMediaSource(mediaSource);
            exoPlayer.prepare();
            exoPlayer.play();

            // 启动超时检测：3秒后检查ExoPlayer状态
            mHandler.postDelayed(playTimeoutRunnable, PLAY_TIMEOUT);

            // 监听ExoPlayer播放状态与错误
            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, getLogTime() + " ExoPlayer播放失败，错误：" + error.getMessage());
                    // 取消超时检测，直接切换到FFmpeg
                    mHandler.removeCallbacks(playTimeoutRunnable);
                    switchToFFmpeg();
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    Log.d(TAG, getLogTime() + " ExoPlayer状态：" + state);
                    if (state == Player.STATE_READY) {
                        // 播放就绪，取消超时检测
                        mHandler.removeCallbacks(playTimeoutRunnable);
                        Log.i(TAG, getLogTime() + " ExoPlayer播放就绪，正常播放");
                        updateWakeLock(true);
                        notifyLiveInfoUpdate();
                        showChannelAndAutoHide();
                    } else if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                        // 播放结束/空闲，更新状态
                        updateWakeLock(false);
                    }
                }
            });

        } catch (Exception e) {
            // ExoPlayer全局异常，直接切换到FFmpeg
            Log.e(TAG, getLogTime() + " ExoPlayer全局异常，切换到FFmpeg", e);
            switchToFFmpeg();
        }
    }

    /**
     * 切换到FFmpeg软解码播放（适配你项目里的lib-decoder-ffmpeg-release.aar）
     */
    private void switchToFFmpeg() {
        try {
            Log.i(TAG, getLogTime() + " 切换到FFmpeg软解码播放虎牙流：" + currentUrl);
            // 先释放ExoPlayer
            releaseExoPlayer();
            // 启动FFmpeg播放，渲染到绑定的PlayerView上
            ffmpegDecoder.play(currentUrl, playerView);
            updateWakeLock(true);
            notifyLiveInfoUpdate();
            showChannelAndAutoHide();
        } catch (Exception e) {
            Log.e(TAG, getLogTime() + " FFmpeg播放也失败：" + e.getMessage(), e);
        }
    }

    /**
     * 播放超时任务：ExoPlayer3秒没就绪，直接切换到FFmpeg
     */
    private final Runnable playTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (exoPlayer.getPlaybackState() != Player.STATE_READY || exoPlayer.hasError()) {
                Log.e(TAG, getLogTime() + " ExoPlayer播放超时，切换到FFmpeg");
                switchToFFmpeg();
            }
        }
    };

    /**
     * 设置画面显示比例（只对ExoPlayer生效，FFmpeg会自动适配）
     * FIT：自适应（原始比例）
     * FILL：拉伸填充全屏
     * ZOOM：等比例裁剪全屏
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
        } catch (Exception e) {
            Log.e(TAG, getLogTime() + " 画面缩放设置异常", e);
        }
    }

    /**
     * 播放状态回调接口
     */
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    /**
     * 设置播放状态监听
     */
    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    /**
     * 暂停播放
     */
    public void pause() {
        try {
            if (exoPlayer != null && exoPlayer.isPlaying()) {
                exoPlayer.pause();
            }
            if (ffmpegDecoder != null) {
                ffmpegDecoder.pause();
            }
            updateWakeLock(false);
        } catch (Exception e) {
            Log.e(TAG, getLogTime() + " 暂停异常", e);
        }
    }

    /**
     * 恢复播放
     */
    public void resume() {
        try {
            if (exoPlayer != null && !exoPlayer.isPlaying()) {
                exoPlayer.play();
            }
            if (ffmpegDecoder != null) {
                ffmpegDecoder.resume();
            }
            updateWakeLock(true);
        } catch (Exception e) {
            Log.e(TAG, getLogTime() + " 恢复播放异常", e);
        }
    }

    /**
     * 释放ExoPlayer资源
     */
    private void releaseExoPlayer() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            exoPlayer.release();
        }
    }

    /**
     * 释放所有播放器资源（ExoPlayer + FFmpeg）
     */
    public void release() {
        try {
            mHandler.removeCallbacks(hideChannelRunnable);
            mHandler.removeCallbacks(playTimeoutRunnable);
            updateWakeLock(false);

            // 释放ExoPlayer
            releaseExoPlayer();

            // 释放FFmpeg解码器
            if (ffmpegDecoder != null) {
                ffmpegDecoder.release();
            }

            instance = null;
            Log.i(TAG, getLogTime() + " 所有播放器已释放");
        } catch (Exception e) {
            Log.e(TAG, getLogTime() + " 释放播放器异常", e);
        }
    }

    /**
     * 释放所有播放器（内部工具方法）
     */
    private void releaseAllPlayers() {
        releaseExoPlayer();
        if (ffmpegDecoder != null) {
            ffmpegDecoder.stop();
        }
    }
}
