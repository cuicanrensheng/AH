package com.tv.live;
import android.content.Context;
import android.net.Uri;
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
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import java.net.HttpURLConnection;
import java.net.URI;
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
    private String currentUrl = "";
    private final Map<Integer, Boolean> triedTypes = new HashMap<>();
    private static final int TYPE_HLS = 1;
    private static final int TYPE_NORMAL = 2;
    private String autoCookie = "";
    private boolean isPlaying = false;

    private int currentChannelNumber = 0;
    private OnLiveInfoUpdateListener infoUpdateListener;

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
                .setBufferDurationsMs(15000, 30000, 5000, 10000)
                .build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
    }

    private void updateWakeLock(boolean enable) {
        this.isPlaying = enable;
        if (playerView == null) return;
        try {
            playerView.setKeepScreenOn(enable);
        } catch (Exception e) {}
    }

    // ✅ 自动识别数据源 + 动态请求头
    private Map<String, String> getAutoHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");

        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            headers.put("Referer", scheme + "://" + host);
        } catch (Exception ignored) {
            headers.put("Referer", "");
        }

        String webCookie = CookieManager.getInstance().getCookie(url);
        if (webCookie != null && !webCookie.isEmpty()) {
            headers.put("Cookie", webCookie);
        } else if (autoCookie != null && !autoCookie.isEmpty()) {
            headers.put("Cookie", autoCookie);
        }
        return headers;
    }

    private void refreshHuyaCookie() {
        new Thread(() -> {
            try {
                URL url = new URL("https://www.huya.com/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
                conn.connect();
                String c = conn.getHeaderField("Set-Cookie");
                if (c != null) autoCookie = c;
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }

    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
    }

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "HD";
        info.audio = "立体声";
        info.bitrate = "4.5MB/s";
        info.channelNum = currentChannelNumber;
        return info;
    }

    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                infoUpdateListener.onLiveInfoUpdate(getLiveInfo());
            });
        }
    }

    public void play(String url) {
        playUrl(url);
    }

    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;
        triedTypes.clear();
        refreshHuyaCookie();
        SettingsActivity.log("▶ 开始播放：" + url);

        // 强制重置播放器，解决黑屏/卡死
        player.stop();
        player.clearMediaItems();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                String errorMsg = "❌ 播放错误：" + error.getMessage() + "，错误码：" + error.errorCode;
                SettingsActivity.log(errorMsg);
                SettingsActivity.log("❌ 异常堆栈：" + android.util.Log.getStackTraceString(error));
                SettingsActivity.log("❌ 当前地址：" + currentUrl);
                handleAutoRecover(error);
                if (listener != null) listener.onPlayError(errorMsg);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        SettingsActivity.log("⌛ 状态：缓冲中");
                        if (listener != null) listener.onBuffering();
                        break;
                    case Player.STATE_READY:
                        SettingsActivity.log("✅ 状态：播放就绪");
                        updateWakeLock(true);
                        notifyLiveInfoUpdate();
                        if (listener != null) listener.onPlayReady();
                        break;
                    case Player.STATE_IDLE:
                        SettingsActivity.log("⏹ 状态：空闲");
                        updateWakeLock(false);
                        if (listener != null) listener.onIdle();
                        break;
                    case Player.STATE_ENDED:
                        SettingsActivity.log("⏹ 状态：播放结束");
                        updateWakeLock(false);
                        if (listener != null) listener.onPlayEnd();
                        break;
                }
            }
        });

        startPlay(url, null);
    }

    private void handleAutoRecover(PlaybackException e) {
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            SettingsActivity.log("🔄 自动修复：直播窗口过期，跳到最新");
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        if (!triedTypes.containsKey(TYPE_HLS)) {
            SettingsActivity.log("🔄 自动重试：切换 HLS 模式");
            startPlay(currentUrl, TYPE_HLS);
        } else {
            SettingsActivity.log("❌ 自动重试失败：所有模式都试过了");
        }
    }

    private void startPlay(String url, Integer forceType) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // ✅ 改用 ExoPlayer 原生 DefaultHttpDataSource，自动识别协议
                DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
                httpFactory.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
                httpFactory.setDefaultRequestProperties(getAutoHeaders(url));

                int type = forceType != null ? forceType : TYPE_HLS;
                MediaSource mediaSource;

                if (type == TYPE_HLS) {
                    mediaSource = new HlsMediaSource.Factory(httpFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(httpFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                }

                triedTypes.put(type, true);
                player.setMediaSource(mediaSource);
                player.prepare();
                player.play();
            } catch (Exception e) {
                SettingsActivity.log("❌ 播放异常：" + e.getMessage());
                SettingsActivity.log("❌ 异常堆栈：" + android.util.Log.getStackTraceString(e));
            }
        });
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
        if (player != null) {
            player.release();
            player = null;
        }
        instance = null;
    }
}
