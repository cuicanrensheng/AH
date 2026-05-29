package com.tv.live;
import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import java.util.HashMap;
import java.util.Map;

public class TVPlayerManager {
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 15000;
    private static final long RETRY_DELAY = 3000;
    private static TVPlayerManager mInstance;
    private final Context mAppContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private ExoPlayer mExoPlayer;
    private PlayerView mPlayerView;
    private String mCurrentPlayUrl = "";

    public enum ScaleMode {
        FIT,
        ZOOM,
        FILL
    }

    public enum CastState {
        IDLE,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    private CastState mCastState = CastState.IDLE;
    private boolean mIsBackgroundPlay = false;
    private float mCurrentSpeed = 1.0f;
    private OnPlayStateListener mPlayStateListener;

    private TVPlayerManager(Context context) {
        this.mAppContext = context.getApplicationContext();
        initPlayer();
    }

    public static TVPlayerManager getInstance(Context context) {
        if (mInstance == null) {
            synchronized (TVPlayerManager.class) {
                if (mInstance == null) {
                    mInstance = new TVPlayerManager(context);
                }
            }
        }
        return mInstance;
    }

    private void initPlayer() {
        mExoPlayer = new ExoPlayer.Builder(mAppContext)
                .setRenderersFactory(new DefaultRenderersFactory(mAppContext))
                .build();
        mExoPlayer.setPlayWhenReady(true);
        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (mPlayStateListener == null) return;
                switch (state) {
                    case Player.STATE_IDLE:
                        mPlayStateListener.onIdle();
                        break;
                    case Player.STATE_BUFFERING:
                        mPlayStateListener.onBuffering();
                        break;
                    case Player.STATE_READY:
                        mPlayStateListener.onPlayReady();
                        break;
                    case Player.STATE_ENDED:
                        mPlayStateListener.onPlayEnd();
                        autoRetry();
                        break;
                }
            }

            @Override
            public void onPlayerError(@NonNull com.google.android.exoplayer2.PlaybackException error) {
                if (mPlayStateListener != null) {
                    mPlayStateListener.onPlayError(error.getMessage());
                }
                autoRetry();
            }
        });
    }

    public void attachPlayerView(PlayerView playerView) {
        this.mPlayerView = playerView;
        if (mPlayerView != null && mExoPlayer != null) {
            mPlayerView.setPlayer(mExoPlayer);
            mPlayerView.setUseController(false);
        }
    }

    public void detachPlayerView() {
        if (mPlayerView != null) {
            mPlayerView.setPlayer(null);
            mPlayerView = null;
        }
    }

    // 关键修复：支持 PHP 接口 + 防盗链请求头
public void playUrl(String url) {
    if (mExoPlayer == null) return;
    if (url == null || url.isEmpty()) return;

    // 1. 后台线程解析 PHP 接口，拿到真实 m3u8 地址
    new Thread(() -> {
        final String realUrl = resolveStreamUrl(url);

        // 切回主线程播放
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                MediaItem mediaItem = new MediaItem.Builder()
                        .setUri(Uri.parse(realUrl))
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build();

                DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                        .setConnectTimeoutMs(CONNECT_TIMEOUT)
                        .setReadTimeoutMs(READ_TIMEOUT)
                        .setUserAgent("Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
                        .setDefaultRequestProperties(getHuyaHeaders());

                MediaSource mediaSource = new HlsMediaSource.Factory(factory)
                        .createMediaSource(mediaItem);

                mExoPlayer.setMediaSource(mediaSource);
                mExoPlayer.prepare();
                mExoPlayer.play();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }).start();
}

// 解析 PHP 接口，返回真实流地址
private String resolveStreamUrl(String url) {
    if (url == null || url.isEmpty()) return url;

    // 标准 m3u8/ts 链接直接返回
    if (url.endsWith(".m3u8") || url.endsWith(".ts")) {
        return url;
    }

    // PHP 接口解析
    if (url.contains(".php") || url.contains("?id=")) {
        HttpURLConnection conn = null;
        BufferedReader br = null;
        try {
            URL targetUrl = new URL(url);
            conn = (HttpURLConnection) targetUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);

            // 防盗链请求头
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
            conn.setRequestProperty("Referer", url.substring(0, url.indexOf("/", 8)));
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

            int responseCode = conn.getResponseCode();

            // 处理 301/302 跳转
            if (responseCode == 301 || responseCode == 302) {
                String location = conn.getHeaderField("Location");
                if (location != null && location.startsWith("http")) {
                    return location;
                }
            }

            // 处理接口返回的文本内容，提取 m3u8 地址
            br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            String content = sb.toString();

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(https?://.*?\\.m3u8)");
            java.util.regex.Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }
    return url;
}

// 虎牙防盗链请求头（示例，可根据实际接口调整）
private Map<String, String> getHuyaHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("Referer", "http://cdn.jdshipin.com:8880/");
    headers.put("Origin", "http://cdn.jdshipin.com:8880");
    return headers;
}
    
    private Map<String, String> getHuyaHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 Chrome/114.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.huya.com/");
        headers.put("Origin", "https://www.huya.com");
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    public void pause() {
        if (mExoPlayer != null) mExoPlayer.pause();
    }

    public void resume() {
        if (mExoPlayer != null) mExoPlayer.play();
    }

    public void stop() {
        if (mExoPlayer != null) mExoPlayer.stop();
    }

    private void autoRetry() {
        if (mExoPlayer == null) return;
        mMainHandler.postDelayed(() -> playUrl(mCurrentPlayUrl), RETRY_DELAY);
    }

    public void setScaleMode(ScaleMode mode) {
        if (mPlayerView == null) return;
        switch (mode) {
            case FIT:
                mPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case ZOOM:
                mPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
            case FILL:
                mPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
        }
    }

    public void setPlaySpeed(float speed) {
        mCurrentSpeed = speed;
        if (mExoPlayer != null) {
            mExoPlayer.setPlaybackParameters(new PlaybackParameters(speed));
        }
    }

    public void setVolume(float volume) {
        if (mExoPlayer != null) {
            float vol = Math.max(0f, Math.min(1f, volume));
            mExoPlayer.setVolume(vol);
        }
    }

    public void setBackgroundPlay(boolean enable) {
        mIsBackgroundPlay = enable;
        if (mExoPlayer != null) {
            mExoPlayer.setPlayWhenReady(enable);
        }
    }

    public void enterPiP(Activity activity) {}
    public void exitPiP(Activity activity) {}
    public void startCast(String deviceId) {}
    public void stopCast() {}

    public void setOnPlayStateListener(OnPlayStateListener listener) {
        this.mPlayStateListener = listener;
    }

    public void release() {
        detachPlayerView();
        mMainHandler.removeCallbacksAndMessages(null);
        if (mExoPlayer != null) {
            mExoPlayer.release();
            mExoPlayer = null;
        }
        mInstance = null;
    }

    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String errorMsg);
    }
}
