package com.tv.live.util;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.tv.live.TVPlayerManager;

import java.lang.ref.WeakReference;

/**
 * 生命周期辅助类
 * 封装 Activity 生命周期相关的资源清理逻辑
 */
public class LifecycleHelper {
    private final WeakReference<Activity> activityRef;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View touchListenerSource;
    private Dialog settingsDialog;
    private Dialog exitMenuDialog;
    private Releaseable infoDisplayManager;
    private Releaseable displayManager;
    private Releaseable channelPanelController;
    private Releaseable appCoreManager;
    private Releaseable pipManager;
    private Releaseable playerControlManager;
    private Releaseable playerManager;
    private BroadcastReceiver unlockReceiver;

    private boolean isDestroyed = false;

    public interface Releaseable {
        void release();
    }

    public interface GestureHelperProvider {
        void updateGestureHelper(Object helper);
    }

    public LifecycleHelper(Activity activity) {
        this.activityRef = new WeakReference<>(activity);
    }

    public void setTouchListenerSource(View source) {
        this.touchListenerSource = source;
    }

    public void setSettingsDialog(Dialog dialog) {
        this.settingsDialog = dialog;
    }

    public void setExitMenuDialog(Dialog dialog) {
        this.exitMenuDialog = dialog;
    }

    public void setInfoDisplayManager(Releaseable manager) {
        this.infoDisplayManager = manager;
    }

    public void setDisplayManager(Releaseable manager) {
        this.displayManager = manager;
    }

    public void setChannelPanelController(Releaseable controller) {
        this.channelPanelController = controller;
    }

    public void setAppCoreManager(Releaseable manager) {
        this.appCoreManager = manager;
    }

    public void setPipManager(Releaseable manager) {
        this.pipManager = manager;
    }

    public void setPlayerControlManager(Releaseable manager) {
        this.playerControlManager = manager;
    }

    public void setPlayerManager(Releaseable manager) {
        this.playerManager = manager;
    }

    public void setUnlockReceiver(BroadcastReceiver receiver) {
        this.unlockReceiver = receiver;
    }

    public void clearHandlerCallbacks() {
        mainHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 释放所有资源
     */
    public void releaseAll() {
        if (isDestroyed) return;
        isDestroyed = true;

        mainHandler.removeCallbacksAndMessages(null);

        if (settingsDialog != null) {
            try {
                if (settingsDialog.isShowing()) {
                    settingsDialog.dismiss();
                }
            } catch (Exception ignored) {}
            settingsDialog = null;
        }

        if (exitMenuDialog != null) {
            try {
                if (exitMenuDialog.isShowing()) {
                    exitMenuDialog.dismiss();
                }
            } catch (Exception ignored) {}
            exitMenuDialog = null;
        }

        releaseManager(infoDisplayManager);
        infoDisplayManager = null;

        releaseManager(displayManager);
        displayManager = null;

        releaseManager(channelPanelController);
        channelPanelController = null;

        releaseManager(appCoreManager);
        appCoreManager = null;

        releaseManager(pipManager);
        pipManager = null;

        releaseManager(playerControlManager);
        playerControlManager = null;

        releaseManager(playerManager);
        playerManager = null;

        TVPlayerManager.shutdownThreadPool();

        Activity activity = activityRef.get();
        if (activity != null && unlockReceiver != null) {
            try {
                activity.unregisterReceiver(unlockReceiver);
            } catch (Exception ignored) {}
            unlockReceiver = null;
        }
    }

    private void releaseManager(Releaseable manager) {
        if (manager != null) {
            try {
                manager.release();
            } catch (Exception e) {
                android.util.Log.e("LifecycleHelper", "释放资源失败: " + e.getMessage());
            }
        }
    }

    public boolean isDestroyed() {
        return isDestroyed;
    }
}
