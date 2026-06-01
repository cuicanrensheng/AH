package com.tv.live;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;

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

public class TVPlayerManager {
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;

    public enum ScaleMode { FIT, FILL, ZOOM }
    private OnPlayStateListener listener;

    private final OkHttpClient okHttpClient;
    private String currentUrl = "";
    private final Map<Integer, Boolean> triedTypes = new HashMap<>();
    private static final int TYPE_HLS = 1;
    private static final int TYPE_NORMAL = 2;
    private String autoCookie = "";
    private boolean isPlaying = false;

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

        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
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

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ExoPlayer2");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        if (autoCookie != null && !autoCookie.isEmpty()) {
            headers.put("Cookie", autoCookie);
        }
        return headers;
    }

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
            } catch (Exception ignored) {}
        }).start();
    }

    public void play(String url) {
        playUrl(url);
    }

    // ====================== 【日志完整】播放入口 ======================
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;
        triedTypes.clear();
        refreshHuyaCookie();

        SettingsActivity.log("▶ 开始播放：" + url);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                // ====================== 【错误日志全输出】 ======================
                String errorMsg = "❌ 播放错误：" + error.getMessage() + "，错误码：" + error.errorCode;
                SettingsActivity.log(errorMsg);
                // 把完整异常也写进日志
                SettingsActivity.log("❌ 异常堆栈：" + android.util.Log.getStackTraceString(error));
                handleAutoRecover(error);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        SettingsActivity.log("⌛ 状态：缓冲中");
                        break;
                    case Player.STATE_READY:
                        SettingsActivity.log("✅ 状态：播放就绪");
                        updateWakeLock(true);
                        break;
                    case Player.STATE_IDLE:
                        SettingsActivity.log("⏹ 状态：空闲");
                        updateWakeLock(false);
                        break;
                    case Player.STATE_ENDED:
                        SettingsActivity.log("⏹ 状态：播放结束");
                        updateWakeLock(false);
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
                DataSource.Factory dataSourceFactory = new OkHttpDataSourceFactory(okHttpClient, getHeaders());
                int type = forceType != null ? forceType : TYPE_HLS;

                MediaSource mediaSource;
                if (type == TYPE_HLS) {
                    mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                }

                triedTypes.put(type, true);
                player.setMediaSource(mediaSource);
                player.prepare();
                player.play();
            } catch (Exception e) {
                // ====================== 【捕获所有异常】 ======================
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

    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "HD";
        info.audio = "立体声";
        info.bitrate = "4.5MB/s";
        return info;
    }

    private static class OkHttpDataSourceFactory implements DataSource.Factory {
        private final OkHttpClient client;
        private final Map<String, String> headers;

        public OkHttpDataSourceFactory(OkHttpClient client, Map<String, String> headers) {
            this.client = client;
            this.headers = headers;
        }

        @Override
        public DataSource createDataSource() {
            return new OkHttpDataSource(client, headers);
        }
    }

    private static class OkHttpDataSource extends BaseDataSource {
        private final OkHttpClient client;
        private final Map<String, String> headers;
        private Response response;
        private java.io.InputStream inputStream;
        private Uri uri;

        public OkHttpDataSource(OkHttpClient client, Map<String, String> headers) {
            super(true);
            this.client = client;
            this.headers = headers;
        }

        @Override
        public long open(DataSpec dataSpec) throws java.io.IOException {
            uri = dataSpec.uri;
            Request.Builder builder = new Request.Builder().url(dataSpec.uri.toString());
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    builder.addHeader(h.getKey(), h.getValue());
                }
            }
            response = client.newCall(builder.build()).execute();
            inputStream = response.body().byteStream();
            return response.body().contentLength();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            try {
                int read = inputStream.read(buffer, offset, length);
                if (read > 0) bytesTransferred(read);
                return read;
            } catch (Exception e) {
                SettingsActivity.log("❌ 读取流失败：" + e.getMessage());
                return -1;
            }
        }

        @Override
        public Uri getUri() {
            return uri;
        }

        @Override
        public void close() {
            try { if (response != null) response.close(); } catch (Exception ignored) {}
            try { if (inputStream != null) inputStream.close(); } catch (Exception ignored) {}
        }
    }
}
