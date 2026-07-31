package com.tv.live;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.media3.ui.PlayerView;

import com.tv.live.manager.ChannelPanelController;
import com.tv.live.manager.DisplayManager;
import com.tv.live.manager.InfoDisplayManager;

import java.util.List;

public class PictureInPictureManager {

    private static PictureInPictureManager instance;

    private final Context appContext;
    private boolean pipEnabled = false;
    private boolean isInPipMode = false;
    private boolean isPipEntering = false;
    private boolean onStopCalled = false;
    private boolean isReturnFromBackgroundPip = false;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OnPipListener listener;
    private OnPipInteractionRestoreListener interactionRestoreListener;

    public static PictureInPictureManager getInstance(Context context) {
        if (instance == null) {
            instance = new PictureInPictureManager(context.getApplicationContext());
        }
        return instance;
    }

    private PictureInPictureManager(Context context) {
        this.appContext = context;
    }

    public interface OnPipListener {
        void onPipModeChanged(boolean inPip);
    }

    public interface OnPipInteractionRestoreListener {
        void onRestoreGesture();
        void onRestoreChannelSwitch();
        void onRestoreLandscapeUi();
    }

    public boolean isPipSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            android.content.pm.PackageManager pm = appContext.getPackageManager();
            if (pm.hasSystemFeature("android.software.leanback")) {
                return pm.hasSystemFeature("android.software.picture_in_picture");
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void setPipEnabled(boolean enabled) {
        this.pipEnabled = enabled;
    }

    public boolean isPipEnabled() {
        return pipEnabled;
    }

    public boolean isInPipMode() {
        return isInPipMode;
    }

    public boolean isPipEntering() {
        return isPipEntering;
    }

    public void setPipEntering(boolean entering) {
        this.isPipEntering = entering;
    }

    public void setStopCalled(boolean stopCalled) {
        this.onStopCalled = stopCalled;
    }

    public boolean isStopCalled() {
        return onStopCalled;
    }

    public void setListener(OnPipListener listener) {
        this.listener = listener;
    }

    public void setInteractionRestoreListener(OnPipInteractionRestoreListener listener) {
        this.interactionRestoreListener = listener;
    }

    public void setReturnFromBackgroundPip(boolean isReturn) {
        this.isReturnFromBackgroundPip = isReturn;
    }

    public boolean shouldEnterPip(boolean isExternalPlayer) {
        if (!isPipSupported()) return false;
        if (!pipEnabled) return false;
        if (isInPipMode || isPipEntering) return false;
        if (isExternalPlayer) return false;
        return true;
    }

    public boolean shouldEnterPip() {
        return shouldEnterPip(false);
    }

    public PictureInPictureParams buildDefaultPipParams() {
        if (!isPipSupported()) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                builder.setAspectRatio(new Rational(16, 9));
                return builder.build();
            }
        } catch (Exception e) {
        }
        return null;
    }

    public boolean enterPip(Activity activity, TVPlayerManager playerManager, boolean mainSwitch) {
        if (activity == null) return false;
        if (!shouldEnterPip()) return false;
        return enterPipInternal(activity, playerManager);
    }

    public boolean enterPip(Activity activity, TVPlayerManager playerManager) {
        return enterPip(activity, playerManager, pipEnabled);
    }

    private boolean enterPipInternal(Activity activity, TVPlayerManager playerManager) {
        try {
            if (playerManager != null) {
                updatePlayState(true);
            }
            PictureInPictureParams params = buildDefaultPipParams();
            return enterPictureInPicture(activity, params);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean enterPictureInPicture(Activity activity, PictureInPictureParams params) {
        if (!isPipSupported() || !pipEnabled || activity == null || activity.isFinishing()) {
            return false;
        }
        try {
            isPipEntering = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.enterPictureInPictureMode(params);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            isPipEntering = false;
        }
        return false;
    }

    public void handleOnPause(Runnable resumeAction, Runnable pauseAction) {
        if (!isPipSupported()) {
            if (pauseAction != null) pauseAction.run();
            return;
        }
        if (isInPipMode || isPipEntering) {
            if (resumeAction != null) {
                try { resumeAction.run(); } catch (Exception ignored) {}
            }
        } else {
            if (pauseAction != null) {
                try { pauseAction.run(); } catch (Exception ignored) {}
            }
        }
    }

    public void handleOnPause(Runnable resumeAction) {
        handleOnPause(resumeAction, null);
    }

    public void onPipModeChanged(Activity activity, boolean isInPip) {
        this.isInPipMode = isInPip;
        this.isPipEntering = false;
        if (!isInPip) {
            setReturnFromBackgroundPip(true);
        }
        if (listener != null) {
            try {
                listener.onPipModeChanged(isInPip);
            } catch (Exception ignored) {}
        }
    }

    public void handleExitPip(Runnable releaseAction) {
        handleExitPip(null, releaseAction);
    }

    public void handleExitPip(Activity activity, Runnable releaseAction) {
        if (!isPipSupported()) return;
        if (onStopCalled) {
            if (releaseAction != null) {
                try { releaseAction.run(); } catch (Exception ignored) {}
            }
        } else {
            if (isReturnFromBackgroundPip && activity != null && !activity.isFinishing()) {
                restoreGestureAndChannelSwitch(activity);
            }
        }
        onStopCalled = false;
        isReturnFromBackgroundPip = false;
    }

    public void handleEnterPip(Activity activity,
                               ChannelPanelController channelPanelController,
                               InfoDisplayManager infoDisplayManager,
                               TVPlayerManager playerManager,
                               PlayerView playerView) {
        try {
            hideAllUi(channelPanelController, infoDisplayManager);
            
            if (playerView != null) {
                playerView.setUseController(false);
            }

            if (activity != null) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            resumePlayback(playerManager);
        } catch (Exception ignored) {}
    }

    public void handleExitPipRestore(Activity activity,
                                     DisplayManager displayManager,
                                     PlayerView playerView,
                                     TVPlayerManager playerManager,
                                     List<Channel> channelSourceList,
                                     int currentPlayIndex,
                                     InfoDisplayManager infoDisplayManager) {
        try {
            if (displayManager != null) {
                displayManager.reapplyFullScreen();
            }

            if (playerView != null) {
                playerView.post(() -> {
                    try {
                        playerView.requestLayout();
                    } catch (Exception ignored) {}
                });

                playerView.postDelayed(() -> {
                    try {
                        playerView.requestLayout();
                        keepPlaying(playerManager, playerView, channelSourceList, currentPlayIndex);
                        
                        mainHandler.postDelayed(() -> {
                            if (activity != null && !activity.isFinishing()) {
                                restoreGestureAndChannelSwitch(activity);
                            }
                        }, 100);
                        
                    } catch (Exception ignored) {}
                }, 300);
            }

            if (infoDisplayManager != null && channelSourceList != null 
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel currChannel = channelSourceList.get(currentPlayIndex);
                TVPlayerManager.LiveInfo liveInfo = (playerManager != null) ? playerManager.getLiveInfo() : null;
                infoDisplayManager.showInfoBar(currChannel, liveInfo);
            }

            if (activity != null) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            resumePlayback(playerManager);

        } catch (Exception ignored) {}
    }

    private void restoreGestureAndChannelSwitch(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            
            if (interactionRestoreListener != null) {
                interactionRestoreListener.onRestoreLandscapeUi();
                interactionRestoreListener.onRestoreGesture();
                interactionRestoreListener.onRestoreChannelSwitch();
            }
            
            if (activity.getWindow() != null) {
                activity.getWindow().getDecorView().setFocusable(true);
                activity.getWindow().getDecorView().setFocusableInTouchMode(true);
                activity.getWindow().getDecorView().requestFocus();
            }
        } catch (Exception ignored) {}
    }

    public void updatePlayState(boolean isPlaying) {}
    public void updateChannelInfo(int num, String name, String bitrate) {}

    public void hideAllUi(ChannelPanelController channelPanelController,
                          InfoDisplayManager infoDisplayManager) {
        try {
            if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                channelPanelController.hidePanel();
            }
            if (infoDisplayManager != null) {
                infoDisplayManager.hideInfoBar();
            }
        } catch (Exception ignored) {}
    }

    public void keepPlaying(TVPlayerManager playerManager,
                            PlayerView playerView,
                            List<Channel> channelSourceList,
                            int currentPlayIndex) {
        try {
            if (playerManager != null) {
                playerManager.resume();
                if (playerView != null) {
                    playerManager.attachPlayerView(playerView);
                    playerManager.resume();
                }
            }
        } catch (Exception e) {
            try {
                if (channelSourceList != null && currentPlayIndex >= 0 
                        && currentPlayIndex < channelSourceList.size()) {
                    Channel channel = channelSourceList.get(currentPlayIndex);
                    if (channel != null && channel.getPlayUrl() != null) {
                        playerManager.playUrl(channel.getPlayUrl());
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    public void resumePlayback(TVPlayerManager playerManager) {
        try {
            if (playerManager != null) playerManager.resume();
        } catch (Exception ignored) {}
    }

    public void release() {
        mainHandler.removeCallbacksAndMessages(null);
        listener = null;
        interactionRestoreListener = null;
        isInPipMode = false;
        isPipEntering = false;
        onStopCalled = false;
        isReturnFromBackgroundPip = false;
    }
}
