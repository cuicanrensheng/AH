package com.tv.live;
import android.content.Context;
import android.net.Uri;
import android.widget.Toast;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
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

public class TVPlayerManager {
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

    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        new Thread(() -> {
            String realUrl = resolveStreamUrl(url);
            new Handler(context.getMainLooper()).post(() -> {
                try {
                    DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
                    factory.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
                    factory.setDefaultRequestProperties(getCommonHeaders());
                    factory.setAllowCrossProtocolRedirects(true);
                    MediaItem mediaItem = MediaItem.fromUri(realUrl);
                    HlsMediaSource source = new HlsMediaSource.Factory(factory).createMediaSource(mediaItem);
                    player.setMediaSource(source);
                    player.prepare();
                    player.play();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }).start();
    }

    public void play(String url) {
        playUrl(url);
    }

    private String resolveStreamUrl(String url) {
        if (url.endsWith(".m3u8") || url.endsWith(".ts")) return url;
        try {
            URL u = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Referer", "https://www.huya.com/");
            int code = conn.getResponseCode();
            if (code == 301 || code == 302) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && loc.startsWith("http")) {
                    conn.disconnect();
                    return loc;
                }
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();
            Pattern pattern = Pattern.compile("(https?://[^\\s\"']+\\.m3u8)");
            Matcher matcher = pattern.matcher(sb.toString());
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return url;
    }

    private Map<String, String> getCommonHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.huya.com/");
        headers.put("Origin", "https://www.huya.com");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        return headers;
    }

    public void setScaleMode(ScaleMode mode) {
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
        try {
            return player.getVideoFormat().bitrate;
        } catch (Exception e) {
            return 0;
        }
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
        } catch (Exception e) {
            return "FHD";
        }
    }

    public String getAudio() {
        try {
            int ch = player.getAudioFormat().channelCount;
            return ch >= 2 ? "立体声" : "单声道";
        } catch (Exception e) {
            return "立体声";
        }
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

    public void loadPlayInfo(String url, OnPlayInfoListener listener) {
    }

    public interface OnPlayInfoListener {
        void onSuccess(PlayInfo info);
    }

    public static class PlayInfo {
    }

    public static class M3u {
    }

    public static class Epg {
    }
}
