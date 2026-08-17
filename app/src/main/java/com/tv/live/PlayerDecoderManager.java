package com.tv.live;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.media3.common.MediaCodecInfo;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressLint({"UnsafeOptInUsageError", "StaticFieldLeak"})
class PlayerDecoderManager {
    private static final String TAG = "PlayerDecoderManager";

    private final TVPlayerManager mgr;
    private final Context context;
    private final Handler mHandler;

    PlayerDecoderManager(TVPlayerManager mgr, Context context, Handler handler) {
        this.mgr = mgr;
        this.context = context;
        this.mHandler = handler;
    }

    void initPlayer() {
        long ti0 = System.currentTimeMillis();
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        long ti1 = System.currentTimeMillis();
        SoftwareFirstMediaCodecSelector codecSelector = new SoftwareFirstMediaCodecSelector(mgr.mDecoderMode);
        long ti2 = System.currentTimeMillis();
        renderersFactory.setMediaCodecSelector(codecSelector);
        renderersFactory.setEnableDecoderFallback(true);

        switch (mgr.mDecoderMode) {
            case TVPlayerManager.DECODER_MODE_SOFT:
                mgr.dLog("【解码器】软解模式");
                break;
            case TVPlayerManager.DECODER_MODE_HARD:
                mgr.dLog("【解码器】硬解模式");
                break;
            case TVPlayerManager.DECODER_MODE_AUTO:
            default:
                mgr.dLog("【解码器】自动模式");
                break;
        }
        long ti3 = System.currentTimeMillis();

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 45000, 800, 1500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        long ti4 = System.currentTimeMillis();

        mgr.trackSelector = new DefaultTrackSelector(context);
        long ti5 = System.currentTimeMillis();

        mgr.player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(mgr.trackSelector)
                .build();
        long ti6 = System.currentTimeMillis();

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "【启动计时】initPlayer 细粒度耗时(ms)："
                    + " DefaultRenderersFactory构造=" + (ti1 - ti0)
                    + " codecSelector构造=" + (ti2 - ti1)
                    + " codecSelector安装+switch=" + (ti3 - ti2)
                    + " DefaultLoadControl构造=" + (ti4 - ti3)
                    + " DefaultTrackSelector构造=" + (ti5 - ti4)
                    + " ExoPlayer.Builder.build=" + (ti6 - ti5)
                    + " 【总计】=" + (ti6 - ti0));
        }

        new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                long ts = System.currentTimeMillis();
                List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos("video/avc", false, false);
                int softCount = 0, hardCount = 0;
                for (MediaCodecInfo codec : h264Codecs) {
                    if (isSoftwareDecoder(codec)) softCount++;
                    else hardCount++;
                }
                long cost = System.currentTimeMillis() - ts;
                Log.i(TAG, "【解码器】枚举 H264 完成 异步耗时=" + cost + "ms，软解 " + softCount + " 个，硬解 " + hardCount + " 个");
                mgr.dLog("【解码器】枚举 H264 完成 异步耗时=" + cost + "ms，软解 " + softCount + " 个，硬解 " + hardCount + " 个");
            } catch (Exception ignored) {
            }
        }, "codec-enum").start();

        mgr.initPlayerListener();
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
    }

    static boolean isSoftwareDecoder(MediaCodecInfo codec) {
        if (codec == null) return false;
        String name = codec.name;
        if (name == null) return false;
        String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    private static final String[] UNSTABLE_HARDWARE_BLACKLIST_LOWER = new String[] {
            "c2.intel.goldfish.",
            "omx.google.android.",
            "c2.amlogic.avc.decoder.awesome",
    };

    static boolean isUnstableHardwareDecoder(String codecName) {
        if (codecName == null) return false;
        String lower = codecName.toLowerCase(Locale.ROOT);
        for (String prefix : UNSTABLE_HARDWARE_BLACKLIST_LOWER) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    static List<MediaCodecInfo> applyCodecPolicy(List<MediaCodecInfo> allCodecs, int mode) {
        if (allCodecs == null || allCodecs.isEmpty()) return allCodecs;

        List<MediaCodecInfo> afterBlacklist = new ArrayList<>();
        for (MediaCodecInfo codec : allCodecs) {
            if (codec == null) continue;
            if (isUnstableHardwareDecoder(codec.name)) continue;
            afterBlacklist.add(codec);
        }
        if (afterBlacklist.isEmpty()) {
            afterBlacklist = new ArrayList<>(allCodecs);
        }

        switch (mode) {
            case TVPlayerManager.DECODER_MODE_HARD: {
                List<MediaCodecInfo> hard = new ArrayList<>();
                for (MediaCodecInfo codec : afterBlacklist) {
                    if (!isSoftwareDecoder(codec)) hard.add(codec);
                }
                return hard.isEmpty() ? afterBlacklist : hard;
            }
            case TVPlayerManager.DECODER_MODE_SOFT: {
                List<MediaCodecInfo> soft = new ArrayList<>();
                List<MediaCodecInfo> hard = new ArrayList<>();
                for (MediaCodecInfo codec : afterBlacklist) {
                    if (isSoftwareDecoder(codec)) soft.add(codec);
                    else hard.add(codec);
                }
                soft.addAll(hard);
                return soft;
            }
            case TVPlayerManager.DECODER_MODE_AUTO:
            default:
                return afterBlacklist;
        }
    }

    private static class SoftwareFirstMediaCodecSelector implements MediaCodecSelector {
        private final int decoderMode;

        public SoftwareFirstMediaCodecSelector(int mode) {
            this.decoderMode = mode;
        }

        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> allCodecs = MediaCodecUtil.getDecoderInfos(mimeType, false, false);
            return applyCodecPolicy(allCodecs, decoderMode);
        }
    }

    void setDecoderMode(int mode) {
        if (mgr.mDecoderMode == mode) return;
        mgr.mDecoderMode = mode;
        mgr.dLog("手动切换解码器模式：" + mode);
        if (mgr.player != null) performDecoderSwitch();
    }

    private void performDecoderSwitch() {
        if (mgr.isSwitching) {
            Log.w(TAG, "正在解码器切换中，忽略当前请求");
            return;
        }
        mgr.isSwitching = true;
        long currentPosition = mgr.player != null ? mgr.player.getCurrentPosition() : 0;

        try {
            mHandler.removeCallbacks(mgr.stuckCheckRunnable);
            mHandler.removeCallbacks(mgr.retryRunnable);
            mHandler.removeCallbacks(mgr.hideChannelRunnable);
            if (mgr.player != null) {
                if (mgr.playerListener != null) {
                    mgr.player.removeListener(mgr.playerListener);
                    mgr.playerListener = null;
                }
                mgr.player.release();
                mgr.player = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放旧播放器异常", e);
        }

        initPlayer();
        final boolean hasUrl = !TextUtils.isEmpty(mgr.currentUrl);
        if (mgr.playerView != null) {
            mHandler.post(() -> {
                try {
                    if (mgr.playerView != null && mgr.player != null) {
                        mgr.playerView.setPlayer(mgr.player);
                    }
                } finally {
                    if (!hasUrl) mgr.isSwitching = false;
                }
            });
        }
        if (hasUrl) {
            mgr.retryCount = 0;
            mgr.isRetrying = false;

            if (mgr.mDecoderMode == TVPlayerManager.DECODER_MODE_SOFT) {
                android.widget.Toast.makeText(context, "已切换至 软解模式", android.widget.Toast.LENGTH_SHORT).show();
            } else if (mgr.mDecoderMode == TVPlayerManager.DECODER_MODE_HARD) {
                android.widget.Toast.makeText(context, "已切换至 硬解模式", android.widget.Toast.LENGTH_SHORT).show();
            } else {
                android.widget.Toast.makeText(context, "已切换至 自动模式", android.widget.Toast.LENGTH_SHORT).show();
            }

            mgr.playbackManager.playUrlInternal(mgr.currentUrl, currentPosition);
            mHandler.postDelayed(() -> { mgr.isSwitching = false; }, 30000);
        } else if (mgr.playerView == null) {
            mgr.isSwitching = false;
        }
    }

    int getDecoderMode() {
        return mgr.mDecoderMode;
    }

    void registerDecoderModeReceiver() {
        if (mgr.decoderReceiverRegistered) return;
        try {
            mgr.decoderModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String modeStr = sp.getString("decoder_mode", "auto");
                        int mode = TVPlayerManager.DECODER_MODE_AUTO;
                        if ("hard".equals(modeStr)) mode = TVPlayerManager.DECODER_MODE_HARD;
                        else if ("soft".equals(modeStr)) mode = TVPlayerManager.DECODER_MODE_SOFT;
                        setDecoderMode(mode);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
            ContextCompat.registerReceiver(context, mgr.decoderModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            mgr.decoderReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册解码器广播失败", e);
        }
    }

    void unregisterDecoderModeReceiver() {
        if (!mgr.decoderReceiverRegistered) return;
        try {
            if (mgr.decoderModeReceiver != null) {
                context.unregisterReceiver(mgr.decoderModeReceiver);
                mgr.decoderModeReceiver = null;
            }
            mgr.decoderReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "注销解码器广播失败", e);
        }
    }

    void switchRenderer(boolean useTexture) {
        if (mgr.player == null || mgr.playerView == null || context == null) return;
        if (mgr.mCurrentUseTexture != null && mgr.mCurrentUseTexture == useTexture) {
            if (mgr.playerView.getPlayer() != mgr.player) mgr.playerView.setPlayer(mgr.player);
            return;
        }
        ViewParent rawParent = mgr.playerView.getParent();
        if (!(rawParent instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) rawParent;

        mgr.isRenderingSwitching = true;
        mgr.bufferCount = 0;
        boolean wasPlaying = mgr.player.isPlaying();
        boolean useController = mgr.playerView.getUseController();
        ViewGroup.LayoutParams layoutParams = mgr.playerView.getLayoutParams();

        int index = parent.indexOfChild(mgr.playerView);
        int styleRes = useTexture ? R.style.PlayerView_Texture : R.style.PlayerView_Surface;
        ContextThemeWrapper themedContext = new ContextThemeWrapper(context, styleRes);
        PlayerView newPlayerView = new PlayerView(themedContext);
        newPlayerView.setLayoutParams(layoutParams);
        newPlayerView.setUseController(useController);
        newPlayerView.setKeepContentOnPlayerReset(true);

        int resizeMode;
        switch (mgr.mCurrentScaleMode) {
            case FILL:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
                break;
            case ZOOM:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
                break;
            case FIT:
            default:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
                break;
        }
        newPlayerView.setResizeMode(resizeMode);

        newPlayerView.setPlayer(mgr.player);
        parent.addView(newPlayerView, index + 1, layoutParams);

        if (mgr.onPlayerViewRecreatedListener != null) {
            mgr.onPlayerViewRecreatedListener.onPlayerViewRecreated(newPlayerView);
        }

        PlayerView oldPlayerView = mgr.playerView;
        mgr.playerView = newPlayerView;
        mgr.playerView.requestFocus();

        mHandler.postDelayed(() -> {
            oldPlayerView.setPlayer(null);
            parent.removeView(oldPlayerView);
            if (wasPlaying && mgr.player != null && !mgr.player.isPlaying()) {
                mgr.player.play();
            }
        }, 300);

        mgr.mCurrentUseTexture = useTexture;
        mgr.isRenderingSwitching = false;
    }

    void registerRendererModeReceiver() {
        if (mgr.rendererReceiverRegistered) return;
        try {
            mgr.rendererModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.RENDERER_TYPE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String mode = sp.getString("renderer_type", "surface");
                        if (mgr.playerView != null) switchRenderer("texture".equals(mode));
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.RENDERER_TYPE_CHANGED");
            ContextCompat.registerReceiver(context, mgr.rendererModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            mgr.rendererReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册渲染方式广播失败", e);
        }
    }

    void unregisterRendererModeReceiver() {
        if (!mgr.rendererReceiverRegistered) return;
        try {
            if (mgr.rendererModeReceiver != null) {
                context.unregisterReceiver(mgr.rendererModeReceiver);
                mgr.rendererModeReceiver = null;
            }
            mgr.rendererReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "注销渲染方式广播失败", e);
        }
    }

    void onForeground() {
        try {
            if (mgr.player != null && mgr.playerView != null) {
                if (mgr.surfaceReady) {
                    if (mgr.playerView.getPlayer() != mgr.player) {
                        mgr.playerView.setPlayer(mgr.player);
                    }
                    mgr.player.play();
                } else {
                    mgr.pendingBindPlayer = true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "切前台异常", e);
        }
    }

    void onBackground() {
        try {
            if (mgr.player != null) {
                mgr.player.pause();
            }
            if (mgr.playerView != null && mgr.surfaceReady) {
                mgr.pendingBindPlayer = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "切后台异常", e);
        }
    }

    void detachPlayerView() {
        try {
            if (mgr.playerView != null) {
                mgr.playerView.setPlayer(null);
                mgr.surfaceReady = false;
                mgr.pendingBindPlayer = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "解绑PlayerView异常", e);
        }
    }

    void attachPlayerView(PlayerView view) {
        mgr.playerView = view;

        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String rendererMode = sp.getString("renderer_type", "surface");
        boolean useTexture = "texture".equals(rendererMode);
        switchRenderer(useTexture);

        if (useTexture) {
            mgr.playerView.setPlayer(mgr.player);
            mgr.surfaceReady = true;
            mgr.pendingBindPlayer = false;
        } else {
            View videoSurfaceView = mgr.playerView.getVideoSurfaceView();
            if (videoSurfaceView instanceof android.view.SurfaceView) {
                android.view.SurfaceView surfaceView = (android.view.SurfaceView) videoSurfaceView;
                surfaceView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(android.view.SurfaceHolder holder) {
                        mgr.surfaceReady = true;
                        if (mgr.player != null && !mgr.player.isPlaying()) {
                            mgr.player.play();
                        }
                        mgr.pendingBindPlayer = false;
                        Log.d(TAG, "Surface创建成功，播放器持续播放");
                    }

                    @Override
                    public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {
                        Log.d(TAG, "Surface变化: " + width + "x" + height);
                    }

                    @Override
                    public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                        mgr.surfaceReady = false;
                        mgr.pendingBindPlayer = true;
                        Log.d(TAG, "Surface销毁，播放器保持运行不解绑");
                    }
                });
                android.view.Surface surface = surfaceView.getHolder().getSurface();
                if (surface != null && surface.isValid()) {
                    mgr.surfaceReady = true;
                    if (mgr.player != null && mgr.playerView.getPlayer() != mgr.player) {
                        mgr.playerView.setPlayer(mgr.player);
                    }
                    mgr.pendingBindPlayer = false;
                } else {
                    mgr.surfaceReady = false;
                    mgr.pendingBindPlayer = true;
                }
            } else {
                mgr.playerView.setPlayer(mgr.player);
                mgr.surfaceReady = true;
                mgr.pendingBindPlayer = false;
            }
        }

        mgr.playerView.setUseController(false);
    }

    void updateWakeLock(boolean enable) {
        mgr.isPlaying = enable;
        if (mgr.playerView != null) mgr.playerView.setKeepScreenOn(enable);
    }

    void setScaleMode(TVPlayerManager.ScaleMode mode) {
        try {
            if (mgr.playerView == null) return;
            mgr.mCurrentScaleMode = mode;
            switch (mode) {
                case FIT:
                    mgr.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    break;
                case FILL:
                    mgr.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                    break;
                case ZOOM:
                    mgr.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "设置缩放模式异常", e);
        }
    }

    void setSurface(android.view.Surface surface) {
        try {
            if (mgr.player != null) {
                mgr.player.setVideoSurface(surface);
                Log.d(TAG, "播放器已绑定 Surface");
            }
        } catch (Exception e) {
            Log.e(TAG, "绑定 Surface 失败", e);
        }
    }

    void release() {
        try {
            unregisterDecoderModeReceiver();
            unregisterRendererModeReceiver();
            if (mgr.player != null) {
                if (mgr.playerListener != null) {
                    mgr.player.removeListener(mgr.playerListener);
                    mgr.playerListener = null;
                }
                mgr.player.release();
                mgr.player = null;
            }
            if (mgr.playerView != null) {
                mgr.playerView.setPlayer(null);
                mgr.playerView.setVisibility(View.VISIBLE);
                mgr.playerView = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }
}