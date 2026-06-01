package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class TVPlayerManager {
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    public enum ScaleMode { FIT, FILL, ZOOM }
    private OnPlayStateListener listener;
    private String currentUrl = "";
    private boolean isPlaying = false;
    private int currentChannelNumber = 0;

    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
    }

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }
    private OnLiveInfoUpdateListener infoUpdateListener;
    private Player.Listener mInternalListener;

    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }

    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 10000, 1000, 2000)
                .build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    private void clearListeners() {
        if (player != null && mInternalListener != null) {
            player.removeListener(mInternalListener);
        }
        mInternalListener = null;
    }

    private void setMediaSourceFactory(DefaultMediaSourceFactory factory) {
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    private Map<String, String> getAutoHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");

        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            headers.put("Referer", scheme + "://" + host);
        } catch (Exception ignored) {}

        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null && !cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "SD";
        info.audio = "立体声";
        info.bitrate = "0.0Mbps";
        info.channelNum = currentChannelNumber;

        if (player != null && player.getPlaybackState() == Player.STATE_READY) {
            if (player.getVideoFormat() != null) {
                int h = player.getVideoFormat().height;
                if (h >= 1080) info.quality = "FHD";
                else if (h >= 720) info.quality = "HD";

                long b = player.getVideoFormat().bitrate;
                if (b > 0) {
                    info.bitrate = String.format("%.1fMbps", b / 1000000.0);
                }
            }
            if (player.getAudioFormat() != null) {
                info.audio = player.getAudioFormat().channelCount >= 2 ? "立体声" : "单声道";
            }
        }
        return info;
    }

    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;

        clearListeners();

        Map<String, String> headers = getAutoHeaders(url);

        HttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000);

        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);

        MediaItem mediaItem = MediaItem.fromUri(url);
        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem));

        mInternalListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                if (listener != null) listener.onPlayError(error.getMessage());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        if (listener != null) listener.onBuffering();
                        break;
                    case Player.STATE_READY:
                        updateWakeLock(true);
                        if (listener != null) listener.onPlayReady();
                        break;
                    case Player.STATE_ENDED:
                    case Player.STATE_IDLE:
                        updateWakeLock(false);
                        break;
                }
            }
        };

        player.addListener(mInternalListener);
        player.prepare();
        player.play();
    }

    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    infoUpdateListener.onLiveInfoUpdate(getLiveInfo())
            );
        }
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    public void play(String url) {
        playUrl(url);
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
            updateWakeLock(false);
        }
    }

    public void resume() {
        if (player != null) {
            player.play();
            updateWakeLock(true);
        }
    }

    public void release() {
        updateWakeLock(false);
        if (player != null) player.release();
        instance = null;
    }
}
