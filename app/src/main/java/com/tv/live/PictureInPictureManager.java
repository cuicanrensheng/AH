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

/**
 * 画中画(PIP)核心管理器
 */
public class PictureInPictureManager {

    private static PictureInPictureManager instance;

    private final Context appContext;
    private boolean pipEnabled = false;
    private boolean isInPipMode = false;
    private boolean isPipEntering = false;
    private boolean onStopCalled = false;
    private boolean isReturnFromBackgroundPip = false;
    
    // 🟢 主线程 Handler，用于延迟检测
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

    // ====================================================================
    // 基础状态方法
    // ====================================================================
    public boolean isPipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
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

    // ====================================================================
    // 是否应该进入画中画
    // ====================================================================
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

    // ====================================================================
    // 构建默认画中画参数（16:9）
    // ====================================================================
    public PictureInPictureParams buildDefaultPipParams() {
        if (!isPipSupported()) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                builder.setAspectRatio(new Rational(16, 9));
                return builder.build();
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return null;
    }

    // ====================================================================
    // 便捷进入画中画方法
    // ====================================================================
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

    // ====================================================================
    // 进入画中画（底层方法）
    // ====================================================================
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

    // ====================================================================
    // onPause 处理
    // ====================================================================
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

    // ====================================================================
    // 画中画模式变化回调（新增后台返回检测）
    // ====================================================================
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

    // ====================================================================
    // 退出画中画处理
    // ====================================================================
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
            // 🟢 优化：确保 activity 非空且前台恢复
            if (isReturnFromBackgroundPip && activity != null && !activity.isFinishing()) {
                restoreGestureAndChannelSwitch(activity);
            }
        }
        onStopCalled = false;
        isReturnFromBackgroundPip = false;
    }

    // ====================================================================
    // 处理进入画中画
    // ====================================================================
    public void handleEnterPip(Activity activity,
                               ChannelPanelController channelPanelController,
                               InfoDisplayManager infoDisplayManager,
                               TVPlayerManager playerManager,
                               PlayerView playerView) {
        try {
            hideAllUi(channelPanelController, infoDisplayManager);
            if (activity != null) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            resumePlayback(playerManager);
        } catch (Exception ignored) {}
    }

    // ====================================================================
    // 🟢【核心优化】处理退出画中画的 UI 恢复（精简重绘，防止任务堆积）
    // ====================================================================
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
                // 🟢【优化1】用单次 requestLayout 替代多重重绘
                playerView.post(() -> {
                    try {
                        playerView.requestLayout();
                    } catch (Exception ignored) {}
                });

                // 🟢【优化2】合并为单次延迟，避免连续两个 Handler 堆积
                playerView.postDelayed(() -> {
                    try {
                        playerView.requestLayout();
                        keepPlaying(playerManager, playerView, channelSourceList, currentPlayIndex);
                        
                        // 🟢【优化3】将 200ms 和 300ms 合并为 300ms 一次性恢复
                        mainHandler.postDelayed(() -> {
                            if (activity != null && !activity.isFinishing()) {
                                restoreGestureAndChannelSwitch(activity);
                            }
                        }, 100); // 最终 100ms 后恢复交互，总延迟约 400ms，平衡且不再堆积
                        
                    } catch (Exception ignored) {}
                }, 300); // 原 200 改为 300，留足渲染时间
            }

            if (infoDisplayManager != null && channelSourceList != null 
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel currChannel = channelSourceList.get(currentPlayIndex);
                TVPlayerManager.LiveInfo liveInfo = (playerManager != null) ? playerManager.getLiveInfo() : null;
                infoDisplayManager.showInfoBar(currChannel, liveInfo);
                infoDisplayManager.showChannelNum(currentPlayIndex + 1);
            }

            if (activity != null) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            resumePlayback(playerManager);

        } catch (Exception ignored) {}
    }

    // ====================================================================
    // 恢复手势和切台功能
    // ====================================================================
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

    // ====================================================================
    // 播放状态和频道信息更新
    // ====================================================================
    public void updatePlayState(boolean isPlaying) {}
    public void updateChannelInfo(int num, String name, String bitrate) {}

    // ====================================================================
    // 隐藏画中画模式下的所有 UI
    // ====================================================================
    public void hideAllUi(ChannelPanelController channelPanelController,
                          InfoDisplayManager infoDisplayManager) {
        try {
            if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                channelPanelController.hidePanel();
            }
            if (infoDisplayManager != null) {
                infoDisplayManager.hideInfoBar();
                infoDisplayManager.hideChannelNum();
            }
        } catch (Exception ignored) {}
    }

    // ====================================================================
    // 画中画模式下保持播放（三重保险）
    // ====================================================================
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

    // ====================================================================
    // 恢复播放（简单版）
    // ====================================================================
    public void resumePlayback(TVPlayerManager playerManager) {
        try {
            if (playerManager != null) playerManager.resume();
        } catch (Exception ignored) {}
    }

    // ====================================================================
    // 🟢【修复5】彻底解决内存泄漏：释放资源时清空所有 Handler 任务
    // ====================================================================
    public void release() {
        mainHandler.removeCallbacksAndMessages(null); // 关键修复：清除所有延迟任务！
        listener = null;
        interactionRestoreListener = null;
        isInPipMode = false;
        isPipEntering = false;
        onStopCalled = false;
        isReturnFromBackgroundPip = false;
    }
}
