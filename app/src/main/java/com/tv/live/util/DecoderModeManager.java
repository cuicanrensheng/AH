package com.tv.live.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.tv.live.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解码器模式管理器
 * 负责硬解/软解/自动模式切换、渲染器(Surface/Texture)切换、
 * 解码器广播接收器、Codec策略过滤等
 */
public class DecoderModeManager {
    private static final String TAG = "DecoderModeManager";

    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_HARD = 1;
    public static final int DECODER_MODE_SOFT = 2;

    private static final String[] UNSTABLE_HARDWARE_BLACKLIST_LOWER = new String[] {
            "c2.intel.goldfish.",
            "omx.google.android.",
            "c2.amlogic.avc.decoder.awesome",
    };

    public interface PlayerViewRecreatedCallback {
        void onPlayerViewRecreated(PlayerView newPlayerView);
    }

    private Context context;
    private ExoPlayer player;
    private PlayerView playerView;
    private android.os.Handler mHandler;
    private Player.Listener playerListener;
    private PlayerViewRecreatedCallback recreateCallback;

    private int mDecoderMode = DECODER_MODE_AUTO;
    private boolean isSwitching = false;

    private BroadcastReceiver decoderModeReceiver;
    private boolean decoderReceiverRegistered = false;
    private BroadcastReceiver rendererModeReceiver;
    private boolean rendererReceiverRegistered = false;

    private Boolean mCurrentUseTexture = null;
    private boolean isRenderingSwitching = false;

    private Runnable stuckCheckRunnable;
    private Runnable retryRunnable;
    private Runnable hideChannelRunnable;

    private CharSequence currentUrl;
    private String currentChannelName = "";

    private SharedPreferences sp;
    private final Object scaleModeLock = new Object();
    private int mCurrentScaleMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;

    public DecoderModeManager(Context context, android.os.Handler handler) {
        this.context = context;
        this.mHandler = handler;
        this.sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
    }

    public void setPlayer(ExoPlayer player) {
        this.player = player;
    }

    public ExoPlayer getPlayer() {
        return player;
    }

    public void setPlayerView(PlayerView playerView) {
        this.playerView = playerView;
    }

    public PlayerView getPlayerView() {
        return playerView;
    }

    public void setPlayerListener(Player.Listener listener) {
        this.playerListener = listener;
    }

    public void setRecreateCallback(PlayerViewRecreatedCallback callback) {
        this.recreateCallback = callback;
    }

    public void setCurrentUrl(CharSequence url) {
        this.currentUrl = url;
    }

    public void setCurrentChannelName(String name) {
        this.currentChannelName = name;
    }

    public void setStuckCheckRunnable(Runnable r) {
        this.stuckCheckRunnable = r;
    }

    public void setRetryRunnable(Runnable r) {
        this.retryRunnable = r;
    }

    public void setHideChannelRunnable(Runnable r) {
        this.hideChannelRunnable = r;
    }

    public void setScaleMode(int mode) {
        synchronized (scaleModeLock) {
            mCurrentScaleMode = mode;
        }
    }

    public int getDecoderMode() {
        return mDecoderMode;
    }

    public boolean isSwitching() {
        return isSwitching;
    }

    public void setDecoderMode(int mode) {
        if (mDecoderMode == mode) return;
        mDecoderMode = mode;
        LogBridge.d(TAG, "手动切换解码器模式：" + mode);
        if (player != null) performDecoderSwitch();
    }

    public void performDecoderSwitch() {
        if (isSwitching) {
            LogBridge.w(TAG, "正在解码器切换中，忽略当前请求");
            return;
        }
        isSwitching = true;
        long currentPosition = player != null ? player.getCurrentPosition() : 0;

        try {
            mHandler.removeCallbacks(stuckCheckRunnable);
            mHandler.removeCallbacks(retryRunnable);
            mHandler.removeCallbacks(hideChannelRunnable);
            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                    playerListener = null;
                }
                player.release();
                player = null;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "释放旧播放器异常", e);
        }

        if (playerView != null) {
            mHandler.post(() -> {
                try {
                    if (playerView != null && player != null) {
                        playerView.setPlayer(player);
                    }
                } finally {
                    if (!TextUtils.isEmpty(currentUrl)) isSwitching = false;
                }
            });
        }

        if (!TextUtils.isEmpty(currentUrl)) {
            if (mDecoderMode == DECODER_MODE_SOFT) {
                Toast.makeText(context, "已切换至 软解模式", Toast.LENGTH_SHORT).show();
            } else if (mDecoderMode == DECODER_MODE_HARD) {
                Toast.makeText(context, "已切换至 硬解模式", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "已切换至 自动模式", Toast.LENGTH_SHORT).show();
            }
            mHandler.postDelayed(() -> { isSwitching = false; }, 30000);
        }
    }

    public void registerDecoderModeReceiver() {
        if (decoderReceiverRegistered) return;
        try {
            decoderModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String modeStr = sp.getString("decoder_mode", "auto");
                        int mode = DECODER_MODE_AUTO;
                        if ("hard".equals(modeStr)) mode = DECODER_MODE_HARD;
                        else if ("soft".equals(modeStr)) mode = DECODER_MODE_SOFT;
                        setDecoderMode(mode);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
            ContextCompat.registerReceiver(context, decoderModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            decoderReceiverRegistered = true;
        } catch (Exception e) {
            LogBridge.e(TAG, "注册解码器广播失败", e);
        }
    }

    public void unregisterDecoderModeReceiver() {
        if (!decoderReceiverRegistered) return;
        try {
            if (decoderModeReceiver != null) {
                context.unregisterReceiver(decoderModeReceiver);
                decoderModeReceiver = null;
            }
            decoderReceiverRegistered = false;
        } catch (Exception e) {
            LogBridge.e(TAG, "注销解码器广播失败", e);
        }
    }

    public void switchRenderer(boolean useTexture) {
        if (player == null || playerView == null || context == null) return;
        if (mCurrentUseTexture != null && mCurrentUseTexture == useTexture) {
            if (playerView.getPlayer() != player) playerView.setPlayer(player);
            return;
        }
        ViewParent rawParent = playerView.getParent();
        if (!(rawParent instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) rawParent;

        isRenderingSwitching = true;
        boolean wasPlaying = player.isPlaying();
        boolean useController = playerView.getUseController();
        ViewGroup.LayoutParams layoutParams = playerView.getLayoutParams();

        int index = parent.indexOfChild(playerView);
        int styleRes = useTexture ? R.style.PlayerView_Texture : R.style.PlayerView_Surface;
        ContextThemeWrapper themedContext = new ContextThemeWrapper(context, styleRes);
        PlayerView newPlayerView = new PlayerView(themedContext);
        newPlayerView.setLayoutParams(layoutParams);
        newPlayerView.setUseController(useController);
        newPlayerView.setKeepContentOnPlayerReset(true);

        int resizeMode;
        synchronized (scaleModeLock) {
            resizeMode = mCurrentScaleMode;
        }
        newPlayerView.setResizeMode(resizeMode);

        newPlayerView.setPlayer(player);
        parent.addView(newPlayerView, index + 1, layoutParams);

        if (recreateCallback != null) {
            recreateCallback.onPlayerViewRecreated(newPlayerView);
        }

        PlayerView oldPlayerView = playerView;
        playerView = newPlayerView;
        playerView.requestFocus();

        mHandler.postDelayed(() -> {
            oldPlayerView.setPlayer(null);
            parent.removeView(oldPlayerView);
            if (wasPlaying && player != null && !player.isPlaying()) {
                player.play();
            }
        }, 300);

        mCurrentUseTexture = useTexture;
        isRenderingSwitching = false;
    }

    public void registerRendererModeReceiver() {
        if (rendererReceiverRegistered) return;
        try {
            rendererModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.RENDERER_TYPE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String mode = sp.getString("renderer_type", "surface");
                        if (playerView != null) switchRenderer("texture".equals(mode));
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.RENDERER_TYPE_CHANGED");
            ContextCompat.registerReceiver(context, rendererModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            rendererReceiverRegistered = true;
        } catch (Exception e) {
            LogBridge.e(TAG, "注册渲染方式广播失败", e);
        }
    }

    public void unregisterRendererModeReceiver() {
        if (!rendererReceiverRegistered) return;
        try {
            if (rendererModeReceiver != null) {
                context.unregisterReceiver(rendererModeReceiver);
                rendererModeReceiver = null;
            }
            rendererReceiverRegistered = false;
        } catch (Exception e) {
            LogBridge.e(TAG, "注销渲染方式广播失败", e);
        }
    }

    public void release() {
        unregisterDecoderModeReceiver();
        unregisterRendererModeReceiver();
        if (mHandler != null) {
            mHandler.removeCallbacks(stuckCheckRunnable);
            mHandler.removeCallbacks(retryRunnable);
            mHandler.removeCallbacks(hideChannelRunnable);
        }
        stuckCheckRunnable = null;
        retryRunnable = null;
        hideChannelRunnable = null;
        playerListener = null;
        recreateCallback = null;
        isSwitching = false;
        isRenderingSwitching = false;
        mCurrentUseTexture = null;
        currentUrl = null;
        currentChannelName = null;
        sp = null;
        playerView = null;
        player = null;
        mHandler = null;
        context = null;
    }

    public static boolean isSoftwareDecoder(MediaCodecInfo codec) {
        if (codec == null) return false;
        String name = codec.name;
        if (name == null) return false;
        String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    public static boolean isUnstableHardwareDecoder(String codecName) {
        if (codecName == null) return false;
        String lower = codecName.toLowerCase(Locale.ROOT);
        for (String prefix : UNSTABLE_HARDWARE_BLACKLIST_LOWER) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    public static List<MediaCodecInfo> applyCodecPolicy(List<MediaCodecInfo> allCodecs, int mode) {
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
            case DECODER_MODE_HARD: {
                List<MediaCodecInfo> hard = new ArrayList<>();
                for (MediaCodecInfo codec : afterBlacklist) {
                    if (!isSoftwareDecoder(codec)) hard.add(codec);
                }
                return hard.isEmpty() ? afterBlacklist : hard;
            }
            case DECODER_MODE_SOFT: {
                List<MediaCodecInfo> soft = new ArrayList<>();
                List<MediaCodecInfo> hard = new ArrayList<>();
                for (MediaCodecInfo codec : afterBlacklist) {
                    if (isSoftwareDecoder(codec)) soft.add(codec);
                    else hard.add(codec);
                }
                soft.addAll(hard);
                return soft;
            }
            case DECODER_MODE_AUTO:
            default:
                return afterBlacklist;
        }
    }

    public static class SoftwareFirstMediaCodecSelector implements MediaCodecSelector {
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
}
