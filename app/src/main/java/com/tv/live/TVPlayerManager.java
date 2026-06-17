package com.tv.live.manager;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.tv.live.SettingsActivity;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * 播放器管理类（单例）
 * 基于ExoPlayer封装，支持自动跟随重定向
 *
 * 【日志说明】
 * - 播放开始、缓冲、就绪、错误等关键节点都会打日志
 * - 可以通过SettingsActivity的日志查看功能查看
 * - 重定向过程由ExoPlayer内部自动处理，可通过抓包工具查看详情
 */
public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private OnPlayStateListener listener;
    private OnLiveInfoUpdateListener liveInfoListener;
    private String currentUrl = "";
    private Player.Listener playerListener;

    // 直播信息
    public static class LiveInfo {
        public String quality = "高清";
        public String audio = "立体声";
        public String bitrate = "0 Mbps";
    }
    private LiveInfo liveInfo = new LiveInfo();

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

    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        player = new ExoPlayer.Builder(context).build();
        // 初始化播放器监听器（只添加一次）
        initPlayerListener();
        log("【播放器】初始化完成");
    }

    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            synchronized (TVPlayerManager.class) {
                if (instance == null) {
                    instance = new TVPlayerManager(ctx);
                }
            }
        }
        return instance;
    }

    /**
     * 初始化播放器监听器
     * 只在构造函数中调用一次，避免重复添加
     */
    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                String errMsg = error.getMessage() != null ? error.getMessage() : "未知错误";
                Log.e(TAG, "播放异常: " + errMsg);
                log("【播放器】❌ 播放错误：" + errMsg);
                if (listener != null) {
                    listener.onPlayError(errMsg);
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_IDLE:
                        log("【播放器】状态：空闲（IDLE）");
                        if (listener != null) listener.onIdle();
                        break;
                    case Player.STATE_BUFFERING:
                        log("【播放器】状态：缓冲中（BUFFERING）");
                        if (listener != null) listener.onBuffering();
                        break;
                    case Player.STATE_READY:
                        log("【播放器】状态：播放就绪（READY）");
                        if (listener != null) listener.onPlayReady();
                        // 更新直播信息
                        updateLiveInfo();
                        break;
                    case Player.STATE_ENDED:
                        log("【播放器】状态：播放结束（ENDED）");
                        if (listener != null) listener.onPlayEnd();
                        break;
                }
            }
        };
        player.addListener(playerListener);
    }

    /**
     * 附加播放器视图
     * @param playerView 播放器视图
     */
    public void attachPlayerView(PlayerView playerView) {
        if (playerView != null && player != null) {
            playerView.setPlayer(player);
            log("【播放器】已绑定PlayerView");
        }
    }

    /**
     * 播放指定URL
     * ExoPlayer会自动跟随重定向（已设置setAllowCrossProtocolRedirects(true)）
     *
     * @param url 播放地址
     */
    public void playUrl(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) {
                log("【播放器】参数错误，无法播放");
                return;
            }

            currentUrl = url.trim();
            log("========================================");
            log("【播放器】开始播放");
            log("【播放器】地址：" + currentUrl);
            log("【播放器】自动跟随重定向：已开启");
            log("========================================");

            // 停止当前播放
            player.stop();
            player.clearMediaItems();

            // 构建HTTP数据源工厂，设置Header和重定向支持
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(getHeaders(currentUrl))
                    .setConnectTimeoutMs(5000)
                    .setReadTimeoutMs(10000)
                    .setAllowCrossProtocolRedirects(true); // 支持跨协议重定向

            // 根据URL类型创建不同的MediaSource
            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            Object mediaSource;

            if (currentUrl.toLowerCase().contains("m3u8")) {
                // HLS流
                log("【播放器】流格式：HLS (m3u8)");
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                // 普通流（flv、mp4等）
                log("【播放器】流格式：普通流 (Progressive)");
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            // 设置媒体源并准备播放
            player.setMediaSource((com.google.android.exoplayer2.source.MediaSource) mediaSource);
            player.prepare();
            player.play();

            log("【播放器】已提交播放请求，等待缓冲...");

        } catch (Exception e) {
            Log.e(TAG, "全局异常", e);
            log("【播放器】❌ 播放异常：" + e.getMessage());
        }
    }

    /**
     * 获取请求Header
     * 智能识别虎牙/斗鱼，设置专属Referer和Origin
     * 全套增强Header，模拟真实浏览器请求
     *
     * @param url 播放地址
     * @return Header映射
     */
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();

        // ===== 基础通用Header =====
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Connection", "keep-alive");
        headers.put("Icy-MetaData", "1");
        headers.put("Sec-Fetch-Dest", "video");
        headers.put("Sec-Fetch-Mode", "no-cors");
        headers.put("Sec-Fetch-Site", "cross-site");
        headers.put("Pragma", "no-cache");
        headers.put("Cache-Control", "no-cache");

        // ===== 智能识别平台，设置专属Header =====
        boolean isHuya = url.contains("huya.com") || url.contains("huya.cn");
        boolean isDouyu = url.contains("douyu.com") || url.contains("douyucdn.cn");

        if (isHuya) {
            // ✅ 虎牙专属：全套Header
            headers.put("Referer", "https://www.huya.com/");
            headers.put("Origin", "https://www.huya.com");
            headers.put("Host", getHostFromUrl(url));
            log("【播放器】虎牙直播，已设置全套专属Header");
        }
        else if (isDouyu) {
            // ✅ 斗鱼专属：全套Header
            headers.put("Referer", "https://www.douyu.com/");
            headers.put("Origin", "https://www.douyu.com");
            headers.put("Host", getHostFromUrl(url));
            log("【播放器】斗鱼直播，已设置全套专属Header");
        }
        else {
            // 通用地址：自动提取Referer
            try {
                URI uri = new URI(url);
                headers.put("Referer", uri.getScheme() + "://" + uri.getHost() + "/");
            } catch (Exception e) {
                headers.put("Referer", "https://www.huya.com/");
            }
        }

        // ===== Cookie处理 =====
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }

        return headers;
    }

    /**
     * 从URL中提取Host
     * @param url 完整URL
     * @return Host域名
     */
    private String getHostFromUrl(String url) {
        try {
            URI uri = new URI(url);
            return uri.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 更新直播信息
     * 从播放器中获取码率、画质等信息
     */
    private void updateLiveInfo() {
        // 这里可以从ExoPlayer的TrackSelector中获取更详细的信息
        // 简化处理，直接回调当前信息
        if (liveInfoListener != null) {
            liveInfoListener.onLiveInfoUpdate(liveInfo);
        }
    }

    /**
     * 获取当前直播信息
     * @return 直播信息对象
     */
    public LiveInfo getLiveInfo() {
        return liveInfo;
    }

    /**
     * 设置播放状态监听器
     * @param l 监听器
     */
    public void setOnPlayStateListener(OnPlayStateListener l) {
        this.listener = l;
    }

    /**
     * 设置直播信息更新监听器
     * @param l 监听器
     */
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener l) {
        this.liveInfoListener = l;
    }

    /**
     * 切到后台时暂停
     */
    public void onBackground() {
        if (player != null) {
            player.setPlayWhenReady(false);
            log("【播放器】切到后台，暂停播放");
        }
    }

    /**
     * 切到前台时恢复
     */
    public void onForeground() {
        if (player != null) {
            player.setPlayWhenReady(true);
            log("【播放器】回到前台，恢复播放");
        }
    }

    /**
     * 释放播放器资源
     */
    public void release() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
            log("【播放器】已释放资源");
        }
    }

    /**
     * 获取当前播放地址
     * @return 当前播放地址
     */
    public String getCurrentUrl() {
        return currentUrl;
    }

    /**
     * 添加日志
     * 同时输出到Logcat和SettingsActivity的日志系统
     * @param msg 日志内容
     */
    private void log(String msg) {
        Log.d(TAG, msg);
        SettingsActivity.log(msg);
    }
}
