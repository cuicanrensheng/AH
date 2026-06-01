package com.tv.live;

import com.tv.live.SettingsActivity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
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

    // ====================== 【修复黑屏】构造方法 ======================
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();

        // ✅ 修复1：开启解码器自动回退（硬解失败 → 软解）
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true); // 这行是救黑屏的命

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 5000, 10000)
                .build();

        // ✅ 修复2：用安全渲染工厂创建播放器
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

    // ====================== 【修复黑屏】绑定Surface ======================
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);

        // ✅ 修复3：强制绑定Surface，保证画面能渲染
        if (playerView.getSurfaceView() != null) {
            player.setVideoSurface(playerView.getSurfaceView().getHolder().getSurface());
        }
    }

    // ====================== 控制亮屏 ======================
    private void updateWakeLock(boolean enable) {
        this.isPlaying = enable;
        if (playerView == null) return;
        try { playerView.setKeepScreenOn(enable); } catch (Exception e) {}
    }

    // ====================== 请求头（适配IPTV） ======================
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

    private void refreshHuyaCookie() { /* 保留 */ }

    // ====================== 播放 ======================
    public void play(String url) { playUrl(url); }
    public void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;
        triedTypes.clear();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) { handleAutoRecover(error); }
            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_READY: updateWakeLock(true); break;
                    case Player.STATE_IDLE: case Player.STATE_ENDED: updateWakeLock(false); break;
                }
            }
        });
        startPlay(url, null);
    }

    private void handleAutoRecover(PlaybackException e) {
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        if (!triedTypes.containsKey(TYPE_HLS)) {
            startPlay(currentUrl, TYPE_HLS);
        }
    }

    private void startPlay(String url, Integer forceType) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                DataSource.Factory dataSourceFactory = new OkHttpDataSourceFactory(okHttpClient, getHeaders());
                int type = forceType != null ? forceType : TYPE_HLS;

                MediaSource mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(url));

                triedTypes.put(type, true);
                player.setMediaSource(mediaSource);
                player.prepare();
                player.play();
            } catch (Exception e) {
                SettingsActivity.log("播放异常：" + e.getMessage());
            }
        });
    }

    // ====================== 基础方法（不动） ======================
    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case FILL: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case ZOOM: playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
        }
    }

    public interface OnPlayStateListener {
        void onIdle(); void onBuffering(); void onPlayReady(); void onPlayEnd(); void onPlayError(String msg);
    }
    public void setOnPlayStateListener(OnPlayStateListener l) { listener = l; }
    public void pause() { if(player!=null){player.pause();updateWakeLock(false);}}
    public void resume() { if(player!=null){player.play();updateWakeLock(true);}}
    public void release() { updateWakeLock(false);if(player!=null){player.release();player=null;}instance=null; }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.quality = "HD"; info.audio = "立体声"; info.bitrate = "4.5MB/s";
        return info;
    }
    public static class LiveInfo { public String quality; public String audio; public String bitrate; }

    // ====================== OkHttp 数据源（不动） ======================
    private static class OkHttpDataSourceFactory implements DataSource.Factory {
        private final OkHttpClient client;
        private final Map<String, String> headers;
        public OkHttpDataSourceFactory(OkHttpClient client, Map<String, String> headers) {
            this.client = client; this.headers = headers;
        }
        @Override public DataSource createDataSource() { return new OkHttpDataSource(client, headers); }
    }
    private static class OkHttpDataSource extends BaseDataSource {
        private final OkHttpClient client;
        private final Map<String, String> headers;
        private Response response;
        private java.io.InputStream inputStream;
        private Uri uri;

        public OkHttpDataSource(OkHttpClient client, Map<String, String> headers) {
            super(true); this.client = client; this.headers = headers;
        }
        @Override
        public long open(DataSpec dataSpec) throws java.io.IOException {
            uri = dataSpec.uri;
            Request.Builder builder = new Request.Builder().url(dataSpec.uri.toString());
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) builder.addHeader(h.getKey(), h.getValue());
            response = client.newCall(builder.build()).execute();
            inputStream = response.body().byteStream();
            return response.body().contentLength();
        }
        @Override public int read(byte[] buffer, int offset, int length) {
            try { int r=inputStream.read(buffer,offset,length);if(r>0)bytesTransferred(r);return r;
            } catch (Exception e) { return -1; }
        }
        @Override public Uri getUri() { return uri; }
        @Override public void close() {
            try{if(response!=null)response.close();}catch(Exception ignored){}
            try{if(inputStream!=null)inputStream.close();}catch(Exception ignored){}
        }
    }
}
