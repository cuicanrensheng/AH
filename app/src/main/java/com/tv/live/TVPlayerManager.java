package com.tv.live;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

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

/**
 * 完整版播放器
 * 支持：虎牙直播、虎牙一起看(hs.hls.huya.com + wsSecret/wsTime/seqid/ctype/fs)、自动重试、画面比例、倍速、后台播放
 */
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

    // 画面缩放模式
    public enum ScaleMode {
        FIT,
        ZOOM,
        FILL
    }

    // 投屏状态（预留兼容）
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

    // 单例
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

    /**
     * 初始化播放器 + 硬解码
     */
    private void initPlayer() {
        mExoPlayer = new ExoPlayer.Builder(mAppContext)
                .setRenderersFactory(Util.buildRenderersFactory(mAppContext, true))
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

    /**
     * 绑定播放视图
     */
    public void attachPlayerView(PlayerView playerView) {
        this.mPlayerView = playerView;
        mPlayerView.setPlayer(mExoPlayer);
        mPlayerView.setUseController(false);
    }

    /**
     * 解绑视图
     */
    public void detachPlayerView() {
        if (mPlayerView != null) {
            mPlayerView.setPlayer(null);
            mPlayerView = null;
        }
    }

    /**
     * 核心播放方法：强制支持虎牙一起看全参数 m3u8
     */
    public void playUrl(String url) {
        if (url == null || url.isEmpty()) return;
        mCurrentPlayUrl = url;

        // 强制指定 MIME 为 m3u8，解决格式识别失败
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();

        // 虎牙专用请求头，防403
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(CONNECT_TIMEOUT)
                .setReadTimeoutMs(READ_TIMEOUT)
                .setUserAgent("Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 Chrome/114.0.0.0 Safari/537.36")
                .setDefaultRequestProperties(getHuyaHeaders());

        // 强制使用 HLS 解析器
        MediaSource mediaSource = new HlsMediaSource.Factory(factory)
                .createMediaSource(mediaItem);

        mExoPlayer.setMediaSource(mediaSource);
        mExoPlayer.prepare();
        mExoPlayer.play();
    }

    /**
     * 虎牙通用请求头（适配一起看/普通直播）
     */
    private Map<String, String> getHuyaHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 Chrome/114.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.huya.com/");
        headers.put("Origin", "https://www.huya.com");
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    // 播放控制
    public void pause() {
        if (mExoPlayer != null) mExoPlayer.pause();
    }

    public void resume() {
        if (mExoPlayer != null) mExoPlayer.play();
    }

    public void stop() {
        if (mExoPlayer != null) mExoPlayer.stop();
    }

    /**
     * 自动断线重试
     */
    private void autoRetry() {
        mMainHandler.postDelayed(() -> playUrl(mCurrentPlayUrl), RETRY_DELAY);
    }

    // 画面比例
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

    // 倍速播放
    public void setPlaySpeed(float speed) {
        mCurrentSpeed = speed;
        if (mExoPlayer != null) {
            mExoPlayer.setPlaybackParameters(new PlaybackParameters(speed));
        }
    }

    // 音量
    public void setVolume(float volume) {
        if (mExoPlayer != null) {
            float vol = Math.max(0f, Math.min(1f, volume));
            mExoPlayer.setVolume(vol);
        }
    }

    // 后台播放开关
    public void setBackgroundPlay(boolean enable) {
        mIsBackgroundPlay = enable;
        if (mExoPlayer != null) {
            mExoPlayer.setPlayWhenReady(enable);
        }
    }

    // 画中画（预留接口）
    public void enterPiP(Activity activity) {}
    public void exitPiP(Activity activity) {}

    // 投屏（预留接口）
    public void startCast(String deviceId) {}
    public void stopCast() {}

    // 状态监听
    public void setOnPlayStateListener(OnPlayStateListener listener) {
        this.mPlayStateListener = listener;
    }

    /**
     * 释放全部资源
     */
    public void release() {
        detachPlayerView();
        mMainHandler.removeCallbacksAndMessages(null);
        if (mExoPlayer != null) {
            mExoPlayer.release();
            mExoPlayer = null;
        }
        mInstance = null;
    }

    // 播放状态回调
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String errorMsg);
    }
}
