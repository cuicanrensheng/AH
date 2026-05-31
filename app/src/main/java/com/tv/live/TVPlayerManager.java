package com.tv.live;
import android.content.Context;
import android.net.Uri;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import java.util.HashMap;
import java.util.Map;

public class TVPlayerManager {
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;

    // ====================== 修复丢失的接口 ======================
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

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

    public void play(String url) {
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
        factory.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        factory.setDefaultRequestProperties(getCommonHeaders());
        factory.setAllowCrossProtocolRedirects(true);

        MediaItem mediaItem = MediaItem.fromUri(url);
        HlsMediaSource source = new HlsMediaSource.Factory(factory).createMediaSource(mediaItem);
        player.setMediaSource(source);
        player.prepare();
        player.play();
    }

    private Map<String, String> getCommonHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.huya.com/");
        headers.put("Origin", "https://www.huya.com");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        return headers;
    }

    public void setOnPlayStateListener(OnPlayStateListener listener) {
        this.listener = listener;
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public void resume() {
        if (player != null) player.play();
    }

    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        instance = null;
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

    public String getBitrateStr() {
        try {
            long b = player.getVideoFormat().bitrate;
            return b <= 0 ? "4.5MB/s" : String.format("%.1fMB/s", b / 1000000f);
        } catch (Exception e) {
            return "4.5MB/s";
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
}
