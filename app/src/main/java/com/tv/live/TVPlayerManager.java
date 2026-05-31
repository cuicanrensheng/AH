package com.tv.live;
import com.tv.live.SettingsActivity;
import android.content.Context;
import android.net.Uri;
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
    // 单例实例
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    public enum ScaleMode { FIT, FILL, ZOOM }
    private OnPlayStateListener listener;

    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        player = new ExoPlayer.Builder(context).build();
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    // ==============================
    // Gitee / giteehb 专用请求头
    // ==============================
    private Map<String, String> getGiteeHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "MTV");
        headers.put("Accept", "*/*");
        headers.put("Connection", "Keep-Alive");
        headers.put("Accept-Encoding", "gzip");
        return headers;
    }

    
    public void play(String url) {
        playUrl(url);
    }
    public void playUrl(String url) {
    if (player == null || url == null || url.isEmpty()) return;

    // 第一处日志：请求信息
    SettingsActivity.log("播放器请求地址：" + url);
    SettingsActivity.log("播放器 User-Agent：MTV");

    new Thread(() -> {
        new Handler(context.getMainLooper()).post(() -> {
            try {
                DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
                factory.setUserAgent("MTV");
                factory.setDefaultRequestProperties(getGiteeHeaders());
                factory.setAllowCrossProtocolRedirects(true);

                MediaItem mediaItem = MediaItem.fromUri(url);
                HlsMediaSource source = new HlsMediaSource.Factory(factory).createMediaSource(mediaItem);
                player.setMediaSource(source);
                player.prepare();
                player.play();

                // 第二处日志：播放状态监听（关键！）
                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlayerError(PlaybackException error) {
                        // 捕获播放器错误
                        SettingsActivity.log("❌ 播放器错误：" + error.getMessage());
                        SettingsActivity.log("❌ 错误码：" + error.errorCode);
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                        switch (state) {
                            case Player.STATE_IDLE:
                                SettingsActivity.log("⏸️ 播放器状态：空闲");
                                break;
                            case Player.STATE_BUFFERING:
                                SettingsActivity.log("⏳ 播放器状态：缓冲中");
                                break;
                            case Player.STATE_READY:
                                SettingsActivity.log("✅ 播放器状态：已就绪，开始播放");
                                break;
                            case Player.STATE_ENDED:
                                SettingsActivity.log("🛑 播放器状态：播放结束");
                                break;
                        }
                    }
                });

                SettingsActivity.log("播放器已开始播放");
            } catch (Exception e) {
                // 第三处日志：捕获异常
                SettingsActivity.log("❌ 播放异常：" + e.getMessage());
                e.printStackTrace();
            }
        });
    }).start();
}

    private String resolveStreamUrl(String url) {
        return url;
    }

    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case FILL: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case ZOOM: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
        }
    }

    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void resume() {
        if (player != null) {
            player.play();
        }
    }

    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        instance = null;
    }

    public long getBitrate() {
        try { return player.getVideoFormat().bitrate; }
        catch (Exception e) { return 0; }
    }

    public String getBitrateStr() {
        long b = getBitrate();
        return b <= 0 ? "4.5MB/s" : String.format("%.1fMB/s", b / 1000000f);
    }

    public String getQuality() {
        try {
            int h = player.getVideoFormat().height;
            if (h >= 1080) return "FHD";
            else if (h >= 720) return "HD";
            else return "SD";
        } catch (Exception e) { return "FHD"; }
    }

    public String getAudio() {
        try {
            int ch = player.getAudioFormat().channelCount;
            return ch >= 2 ? "立体声" : "单声道";
        } catch (Exception e) { return "立体声"; }
    }

    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = getQuality();
        info.audio = getAudio();
        info.bitrate = getBitrateStr();
        return info;
    }

    public static class M3u {
        public static class Channel {
            public String tvg;
            public String name;
            public String url;
            public Channel(String tvg, String name, String url) {
                this.tvg = tvg;
                this.name = name;
                this.url = url;
            }
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
            public String start;
            public String stop;
            public String title;
            public Program(String s, String e, String t) {
                start = s;
                stop = e;
                title = t;
            }
        }
        public static ArrayList<Program> parse(String xml, String tvg) {
            ArrayList<Program> list = new ArrayList<>();
            return list;
        }
    }

    public static class PlayInfo {
        public String channel;
        public String tvg;
        public String nowTitle;
        public String nowTime;
        public String nextTitle;
        public String nextTime;
        public int progress;
        public int remain;
    }

    public interface OnPlayInfoListener {
        void onSuccess(PlayInfo info);
    }

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

    public interface OnChannelListener {
        void onSuccess(ArrayList<M3u.Channel> list);
    }

    public void loadChannelList(OnChannelListener listener) {}
}
