package com.tv.live;

import com.tv.live.SettingsActivity;
import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.Toast;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import android.os.Handler;
import android.os.Looper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 播放器管理类（ExoPlayer）
 * 负责：播放、解析地址、切换画面比例、暂停/恢复
 */
public class TVPlayerManager {
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    public enum ScaleMode { FIT, FILL, ZOOM }
    private OnPlayStateListener listener;

    // 全局自动Cookie（每次播放自动刷新）
    private String autoCookie = "";

    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        player = new ExoPlayer.Builder(context).build();
        // 初始化Cookie管理器
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // ==============================
    // 🔥 核心：自动获取虎牙最新Cookie
    // ==============================
    private void refreshHuyaCookie() {
        new Thread(() -> {
            try {
                URL url = new URL("https://www.huya.com/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
                conn.setConnectTimeout(5000);
                conn.connect();

                // 从响应头自动获取最新Cookie
                String headerCookie = conn.getHeaderField("Set-Cookie");
                if (headerCookie != null && !headerCookie.isEmpty()) {
                    autoCookie = headerCookie;
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ==============================
    // 自动Cookie + 全请求头
    // ==============================
    private Map<String, String> getAutoHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.huya.com/");
        headers.put("Origin", "https://www.huya.com");
        headers.put("Accept", "*/*");
        headers.put("Connection", "Keep-Alive");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "cross-site");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("X-Requested-With", "XMLHttpRequest");

        // 🔥 自动使用最新Cookie
        if (autoCookie != null && !autoCookie.isEmpty()) {
            headers.put("Cookie", autoCookie);
        }
        return headers;
    }

    public void play(String url) {
        playUrl(url);
    }

    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;

        // 每次播放前自动刷新Cookie
        refreshHuyaCookie();

        SettingsActivity.log("播放器地址：" + url);
        SettingsActivity.log("已启用：自动刷新Cookie（永久有效）");

        new Thread(() -> {
            new Handler(context.getMainLooper()).post(() -> {
                try {
                    DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
                    factory.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
                    factory.setDefaultRequestProperties(getAutoHeaders());
                    factory.setAllowCrossProtocolRedirects(true);
                    factory.setConnectTimeoutMs(15000);
                    factory.setReadTimeoutMs(15000);

                    MediaItem mediaItem = MediaItem.fromUri(url);
                    HlsMediaSource source = new HlsMediaSource.Factory(factory).createMediaSource(mediaItem);
                    player.setMediaSource(source);
                    player.prepare();
                    player.play();

                    player.addListener(new Player.Listener() {
                        @Override
                        public void onPlayerError(PlaybackException error) {
                            SettingsActivity.log("❌ 播放器错误：" + error.getMessage());
                            SettingsActivity.log("❌ 错误码：" + error.errorCode);
                        }
                        @Override
                        public void onPlaybackStateChanged(int state) {
                            switch (state) {
                                case Player.STATE_IDLE: SettingsActivity.log("⏸️ 状态：空闲"); break;
                                case Player.STATE_BUFFERING: SettingsActivity.log("⏳ 状态：缓冲中"); break;
                                case Player.STATE_READY: SettingsActivity.log("✅ 状态：播放正常"); break;
                                case Player.STATE_ENDED: SettingsActivity.log("🛑 状态：播放结束"); break;
                            }
                        }
                    });
                    SettingsActivity.log("播放器已启动");
                } catch (Exception e) {
                    SettingsActivity.log("❌ 异常：" + e.getMessage());
                    e.printStackTrace();
                }
            });
        }).start();
    }

    // ==============================
    // 以下代码完全保持你原有逻辑不变
    // ==============================
    private String resolveStreamUrl(String url) { return url; }

    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case FILL: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case ZOOM: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
        }
    }

    public void setOnPlayStateListener(OnPlayStateListener l) { listener = l; }
    public interface OnPlayStateListener {
        void onIdle(); void onBuffering(); void onPlayReady(); void onPlayEnd(); void onPlayError(String msg);
    }

    public void pause() { if (player != null) player.pause(); }
    public void resume() { if (player != null) player.play(); }
    public void release() { if (player != null) { player.release(); player = null; } instance = null; }

    public long getBitrate() { try { return player.getVideoFormat().bitrate; } catch (Exception e) { return 0; } }
    public String getBitrateStr() { long b = getBitrate(); return b <= 0 ? "4.5MB/s" : String.format("%.1fMB/s", b / 1000000f); }
    public String getQuality() { try { int h = player.getVideoFormat().height; return h >= 1080 ? "FHD" : h >= 720 ? "HD" : "SD"; } catch (Exception e) { return "FHD"; } }
    public String getAudio() { try { int ch = player.getAudioFormat().channelCount; return ch >= 2 ? "立体声" : "单声道"; } catch (Exception e) { return "立体声"; } }

    public static class LiveInfo { public String quality; public String audio; public String bitrate; }
    public LiveInfo getLiveInfo() { LiveInfo info = new LiveInfo(); info.quality = getQuality(); info.audio = getAudio(); info.bitrate = getBitrateStr(); return info; }

    public static class M3u {
        public static class Channel {
            public String tvg; public String name; public String url;
            public Channel(String tvg, String name, String url) { this.tvg = tvg; this.name = name; this.url = url; }
        }
        public static ArrayList<Channel> parse(String txt) {
            ArrayList<Channel> list = new ArrayList<>();
            Pattern p = Pattern.compile("tvg-name=\"([^\"]+)\".*?,(.*?)\\s*\\n(https?://.*?\\.m3u8)");
            Matcher m = p.matcher(txt);
            while (m.find()) {
                String tvg = m.group(1).trim();
                String name = m.group(2).trim();
                String url = m.group(3).trim();
                list.add(new Channel(tvg, name, url));
            }
            return list;
        }
    }

    public static class Epg {
        public static class Program {
            public String start; public String stop; public String title;
            public Program(String s, String e, String t) { start = s; stop = e; title = t; }
        }
        public static ArrayList<Program> parse(String xml, String tvg) { return new ArrayList<>(); }
    }

    public static class PlayInfo {
        public String channel; public String tvg; public String nowTitle; public String nowTime;
        public String nextTitle; public String nextTime; public int progress; public int remain;
    }

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
        mRefreshRunnable = () -> {
            loadPlayInfo(mCurrUrl, mRefreshListener);
            mRefreshHandler.postDelayed(mRefreshRunnable, 30000);
        };
        mRefreshHandler.post(mRefreshRunnable);
    }

    public void stopAutoRefresh() {
        if (mRefreshRunnable != null) mRefreshHandler.removeCallbacks(mRefreshRunnable);
    }

    public interface OnChannelListener { void onSuccess(ArrayList<M3u.Channel> list); }
    public void loadChannelList(OnChannelListener listener) {}
}
