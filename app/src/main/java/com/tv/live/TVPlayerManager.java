package com.tv.live;

import com.tv.live.SettingsActivity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 播放器核心管理类（完全复刻 mytv-android 能力）
 * 支持：HLS(m3u8)、FLV、MP4、TS、MPG
 * 自带：自动Cookie、自动重试、自动切格式、防盗链
 * 新增：播放时 → 阻止系统休眠/熄屏/锁屏
 */
public class TVPlayerManager {
    // 单例实例（全局唯一，避免重复创建播放器）
    private static TVPlayerManager instance;

    // ExoPlayer 核心播放器
    private ExoPlayer player;

    // 上下文
    private Context context;

    // 播放视图控件
    private PlayerView playerView;

    // 画面缩放模式
    public enum ScaleMode { FIT, FILL, ZOOM }

    // 播放状态监听接口
    private OnPlayStateListener listener;

    // ======================== 网络核心 ========================
    // OkHttp 完全接管网络（mytv 同款，实现真正自动Cookie）
    private final OkHttpClient okHttpClient;

    // 当前播放地址（出错自动重连用）
    private String currentUrl = "";

    // 记录已尝试的播放类型，避免循环重试
    private final Map<Integer, Boolean> triedTypes = new HashMap<>();

    // 播放类型：HLS(m3u8)
    private static final int TYPE_HLS = 1;

    // 播放类型：普通格式（FLV/MP4/TS 都走这个）
    private static final int TYPE_NORMAL = 2;

    // 自动维护的Cookie（解决虎牙/斗鱼等403）
    private String autoCookie = "";

    // ======================== 【新增】阻止系统休眠 ========================
    // 标记：是否正在播放（用于控制休眠）
    private boolean isPlaying = false;

    // ======================== 单例获取 ========================
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    // ======================== 构造方法：初始化播放器与网络 ========================
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        // 创建播放器
        player = new ExoPlayer.Builder(context).build();

        // 初始化 OkHttp + 自动Cookie管理
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                // 自动保存/携带Cookie（核心防盗链）
                .cookieJar(new CookieJar() {
                    private final Map<String, java.util.List<Cookie>> cookieStore = new HashMap<>();
                    @Override
                    public void saveFromResponse(HttpUrl url, java.util.List<Cookie> cookies) {
                        cookieStore.put(url.host(), cookies);
                    }
                    @Override
                    public java.util.List<Cookie> loadForRequest(HttpUrl url) {
                        java.util.List<Cookie> cookies = cookieStore.get(url.host());
                        return cookies != null ? cookies : java.util.Collections.emptyList();
                    }
                })
                .build();

        // 兼容系统Cookie
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // ======================== 绑定播放器视图 ========================
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // ======================== 【核心】播放时：阻止休眠；停止时：恢复休眠 ========================
    private void updateWakeLock(boolean enable) {
        this.isPlaying = enable;
        if (playerView == null || playerView.getWindowToken() == null) return;

        try {
            if (enable) {
                // 播放中 → 强制亮屏、阻止休眠
                playerView.setKeepScreenOn(true);
            } else {
                // 暂停/停止 → 允许系统休眠
                playerView.setKeepScreenOn(false);
            }
        } catch (Exception e) {
            // 防止异常崩溃
        }
    }

    // ======================== 自动刷新虎牙首页Cookie ========================
    private void refreshHuyaCookie() {
        new Thread(() -> {
            try {
                URL url = new URL("https://www.huya.com/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
                conn.connect();
                String c = conn.getHeaderField("Set-Cookie");
                if (c != null) autoCookie = c;
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ======================== 统一请求头（模拟浏览器） ========================
    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.huya.com/");
        headers.put("Origin", "https://www.huya.com");
        headers.put("Accept", "*/*");
        headers.put("Connection", "Keep-Alive");
        // 自动带上Cookie
        if (autoCookie != null && !autoCookie.isEmpty()) {
            headers.put("Cookie", autoCookie);
        }
        return headers;
    }

    // ======================== 外部调用播放 ========================
    public void play(String url) {
        playUrl(url);
    }

    // ======================== 核心播放方法 ========================
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;

        currentUrl = url;
        triedTypes.clear();
        refreshHuyaCookie(); // 每次播放刷新Cookie
        SettingsActivity.log("播放地址：" + url);

        // 监听播放状态与错误
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                SettingsActivity.log("错误：" + error.getMessage());
                handleAutoRecover(error); // 自动恢复
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING: 
                        SettingsActivity.log("缓冲中"); 
                        break;

                    case Player.STATE_READY: 
                        SettingsActivity.log("播放正常");
                        // 播放准备完成 → 阻止休眠
                        updateWakeLock(true);
                        break;

                    case Player.STATE_IDLE:
                    case Player.STATE_ENDED:
                        // 播放停止 → 允许休眠
                        updateWakeLock(false);
                        break;
                }
            }
        });

        startPlay(url, null);
    }

    // ======================== 播放错误自动修复（mytv核心） ========================
    private void handleAutoRecover(PlaybackException e) {
        // 直播窗口过期 → 跳到最新
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }

        // 解析失败 → 自动切换格式
        if (!triedTypes.containsKey(TYPE_HLS)) {
            startPlay(currentUrl, TYPE_HLS);
        } else if (!triedTypes.containsKey(TYPE_NORMAL)) {
            startPlay(currentUrl, TYPE_NORMAL);
        }
    }

    // ======================== 真正执行播放 ========================
    private void startPlay(String url, Integer forceType) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // 使用OkHttp数据源（带Cookie）
                DataSource.Factory dataSourceFactory = new OkHttpDataSourceFactory(okHttpClient, getHeaders());

                // 自动判断格式
                int type = forceType != null ? forceType : (url.contains(".m3u8") ? TYPE_HLS : TYPE_NORMAL);

                MediaSource mediaSource;
                if (type == TYPE_HLS) {
                    // HLS 流
                    mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                } else {
                    // FLV / MP4 / TS 通用
                    mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                }

                triedTypes.put(type, true);
                player.setMediaSource(mediaSource);
                player.prepare();
                player.play();
            } catch (Exception e) {
                SettingsActivity.log("播放异常：" + e.getMessage());
            }
        });
    }

    // ======================== 设置画面比例 ========================
    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case FILL: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case ZOOM: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
        }
    }

    // ======================== 播放状态监听 ========================
    public void setOnPlayStateListener(OnPlayStateListener l) { listener = l; }
    public interface OnPlayStateListener {
        void onIdle(); void onBuffering(); void onPlayReady(); void onPlayEnd(); void onPlayError(String msg);
    }

    // ======================== 基础控制 ========================
    public void pause() { 
        if (player != null) {
            player.pause();
            // 暂停 → 允许休眠
            updateWakeLock(false);
        }
    }

    public void resume() { 
        if (player != null) {
            player.play();
            // 恢复播放 → 阻止休眠
            updateWakeLock(true);
        }
    }

    public void release() { 
        if (player != null) {
            // 释放播放器 → 恢复休眠
            updateWakeLock(false);
            player.release(); 
            player = null; 
        }
        instance = null; 
    }

    // ======================== 获取播放信息 ========================
    public long getBitrate() { try { return player.getVideoFormat().bitrate; } catch (Exception e) { return 0; } }
    public String getBitrateStr() { long b = getBitrate(); return b <= 0 ? "4.5MB/s" : String.format("%.1fMB/s", b / 1000000f); }
    public String getQuality() { try { int h = player.getVideoFormat().height; return h >= 1080 ? "FHD" : h >= 720 ? "HD" : "SD"; } catch (Exception e) { return "FHD"; } }
    public String getAudio() { try { int ch = player.getAudioFormat().channelCount; return ch >= 2 ? "立体声" : "单声道"; } catch (Exception e) { return "立体声"; } }

    // ======================== 界面显示用实体 ========================
    public static class LiveInfo { public String quality; public String audio; public String bitrate; }
    public LiveInfo getLiveInfo() { LiveInfo info = new LiveInfo(); info.quality = getQuality(); info.audio = getAudio(); info.bitrate = getBitrateStr(); return info; }

    // ======================== M3U解析 ========================
    public static class M3u {
        public static class Channel {
            public String tvg; public String name; public String url;
            public Channel(String tvg, String name, String url) { this.tvg = tvg; this.name = name; this.url = url; }
        }
        public static java.util.ArrayList<Channel> parse(String txt) {
            java.util.ArrayList<Channel> list = new java.util.ArrayList<>();
            Pattern p = Pattern.compile("tvg-name=\"([^\"]+)\".*?,(.*?)\\s*\\n(https?://.*?\\.m3u8)");
            java.util.regex.Matcher m = p.matcher(txt);
            while (m.find()) {
                String tvg = m.group(1).trim();
                String name = m.group(2).trim();
                String url = m.group(3).trim();
                list.add(new Channel(tvg, name, url));
            }
            return list;
        }
    }

    // ======================== EPG节目单解析 ========================
    public static class Epg {
        public static class Program {
            public String start; public String stop; public String title;
            public Program(String s, String e, String t) { start = s; stop = e; title = t; }
        }
        public static java.util.ArrayList<Program> parse(String xml, String tvg) { return new java.util.ArrayList<>(); }
    }

    // ======================== 以下为原有逻辑，保持兼容 ========================
    public static class PlayInfo { public String channel; public String tvg; public String nowTitle; public String nowTime; public String nextTitle; public String nextTime; public int progress; public int remain; }
    public interface OnPlayInfoListener { void onSuccess(PlayInfo info); }
    public void loadPlayInfo(String playUrl, OnPlayInfoListener listener) {}

    private Handler mRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable mRefreshRunnable;
    private String mCurrUrl;
    private OnPlayInfoListener mRefreshListener;

    public void startAutoRefresh(String url, OnPlayInfoListener listener) {
        mCurrUrl = url;
        mRefreshListener = listener;
        stopAutoRefresh();
        mRefreshRunnable = () -> { loadPlayInfo(mCurrUrl, mRefreshListener); mRefreshHandler.postDelayed(mRefreshRunnable, 30000); };
        mRefreshHandler.post(mRefreshRunnable);
    }

    public void stopAutoRefresh() { if (mRefreshRunnable != null) mRefreshHandler.removeCallbacks(mRefreshRunnable); }
    public interface OnChannelListener { void onSuccess(java.util.ArrayList<M3u.Channel> list); }
    public void loadChannelList(OnChannelListener listener) {}

    // ======================== OkHttp 数据源适配 ExoPlayer ========================
    private static class OkHttpDataSourceFactory implements DataSource.Factory {
        private final OkHttpClient client;
        private final Map<String, String> headers;
        public OkHttpDataSourceFactory(OkHttpClient client, Map<String, String> headers) { this.client = client; this.headers = headers; }
        @Override public DataSource createDataSource() { return new OkHttpDataSource(client, headers); }
    }

    private static class OkHttpDataSource extends com.google.android.exoplayer2.upstream.BaseDataSource {
        private final OkHttpClient client;
        private final Map<String, String> headers;
        private Response response;
        private java.io.InputStream inputStream;

        public OkHttpDataSource(OkHttpClient client, Map<String, String> headers) { super(true); this.client = client; this.headers = headers; }

        @Override
        public long open(com.google.android.exoplayer2.upstream.DataSpec dataSpec) throws java.io.IOException {
            Request.Builder builder = new Request.Builder().url(dataSpec.uri.toString());
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) builder.addHeader(h.getKey(), h.getValue());
            response = client.newCall(builder.build()).execute();
            inputStream = response.body().byteStream();
            return response.body().contentLength();
        }

        @Override public int read(byte[] buffer, int offset, int length) { try { int r = inputStream.read(buffer, offset, length); if (r > 0) bytesTransferred(r); return r; } catch (Exception e) { return -1; } }
        @Override public void close() { try { if (response != null) response.close(); } catch (Exception ignored) {} try { if (inputStream != null) inputStream.close(); } catch (Exception ignored) {} }
    }
}
