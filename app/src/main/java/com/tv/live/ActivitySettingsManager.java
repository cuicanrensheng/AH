package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.tv.live.config.AppConfig;
import com.tv.live.manager.ChannelPanelController;
import com.tv.live.manager.PictureInPictureManager;
import com.tv.live.manager.PlayerControlManager;
import com.tv.live.manager.ScreenRatioManager;

public class ActivitySettingsManager {
    private static final String TAG = "ActivitySettingsManager";
    private static final String ACTION_UNLOCK_SETTINGS = "com.tv.live.UNLOCK_SETTINGS";

    private final MainActivity activity;
    private final SharedPreferences sp;
    private final AppConfig appConfig;

    private SettingsDialog settingsDialog;
    private volatile boolean isOpeningSettings = false;
    private long settingsCloseTime = 0;
    private long lastSettingsOpenTime = 0;

    private boolean channelReverse = false;
    private boolean numberChannelEnable = true;
    private boolean pipEnable = false;

    private final android.content.BroadcastReceiver unlockReceiver;

    public ActivitySettingsManager(MainActivity activity, SharedPreferences sp, AppConfig appConfig) {
        this.activity = activity;
        this.sp = sp;
        this.appConfig = appConfig;

        this.unlockReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_UNLOCK_SETTINGS.equals(intent.getAction())) {
                    isOpeningSettings = false;
                    settingsCloseTime = System.currentTimeMillis();
                    Log.d(TAG, "📡 收到解锁广播，isOpeningSettings 已重置");
                }
            }
        };
    }

    public void registerReceiver() {
        ContextCompat.registerReceiver(
                activity,
                unlockReceiver,
                new IntentFilter(ACTION_UNLOCK_SETTINGS),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    public void unregisterReceiver() {
        try {
            activity.unregisterReceiver(unlockReceiver);
        } catch (Exception e) {
            Log.e(TAG, "unregisterReceiver 失败", e);
        }
    }

    public void loadSettings() {
        boolean epgEnable = sp.getBoolean("epg_enable", true);
        channelReverse = sp.getBoolean("channel_reverse", false);
        numberChannelEnable = sp.getBoolean("number_channel_enable", true);
        boolean autoUpdateSource = sp.getBoolean("auto_update_source", true);
        pipEnable = sp.getBoolean("pip_enable", false);

        String decoderMode = sp.getString("decoder_mode", "auto");
        int mode = TVPlayerManager.DECODER_MODE_AUTO;
        if ("hard".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_HARD;
        } else if ("soft".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_SOFT;
        }

        TVPlayerManager playerManager = activity.getPlayerManager();
        if (playerManager != null) playerManager.setDecoderMode(mode);

        ChannelPanelController channelPanelController = activity.getChannelPanelController();
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epgEnable);
            channelPanelController.setReverse(channelReverse);
        }

        com.tv.live.manager.PictureInPictureManager pipManager = activity.getPipManager();
        if (pipManager != null) pipManager.setPipEnabled(pipEnable);
    }

    public void openSettings() {
        try {
            long now = System.currentTimeMillis();

            if (isOpeningSettings) {
                if (now - lastSettingsOpenTime > 5000) {
                    Log.d(TAG, "强制解锁 isOpeningSettings（超过 5 秒）");
                    isOpeningSettings = false;
                } else {
                    Log.d(TAG, "isOpeningSettings 为 true，被拦截（距离上次尝试不到 5 秒）");
                    return;
                }
            }

            if (activity.isInCatchUpMode()) return;

            lastSettingsOpenTime = now;
            isOpeningSettings = true;

            try {
                ChannelPanelController channelPanelController = activity.getChannelPanelController();
                if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                    channelPanelController.hidePanel();
                }
            } catch (Exception e) {
                Log.e(TAG, "hidePanel 失败", e);
            }

            try {
                PlayerControlManager playerControlManager = activity.getPlayerControlManager();
                if (playerControlManager != null) {
                    playerControlManager.onOpenSettings();
                }
            } catch (Exception e) {
                Log.e(TAG, "onOpenSettings 失败", e);
            }

            try {
                settingsDialog = new SettingsDialog(activity);
                settingsDialog.show();
                Log.d(TAG, "SettingsDialog 显示成功");
            } catch (Exception e) {
                Log.e(TAG, "显示 SettingsDialog 失败", e);
                isOpeningSettings = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "打开设置失败", e);
            isOpeningSettings = false;
        }
    }

    public void refreshSettings() {
        loadSettings();

        ScreenRatioManager screenRatioManager = activity.getScreenRatioManager();
        if (screenRatioManager != null) {
            screenRatioManager.apply();
        }

        com.tv.live.manager.PictureInPictureManager pipManager = activity.getPipManager();
        if (pipManager != null) {
            pipManager.setPipEnabled(pipEnable);
        }

        ChannelPanelController channelPanelController = activity.getChannelPanelController();
        if (channelPanelController != null) {
            channelPanelController.setReverse(channelReverse);
        }

        Log.d(TAG, "设置已主动刷新，无需切后台");
    }

    public boolean isSettingsDialogShowing() {
        return settingsDialog != null && settingsDialog.isShowing();
    }

    public void dismissSettingsDialog() {
        if (settingsDialog != null) {
            try {
                if (settingsDialog.isShowing()) {
                    settingsDialog.dismiss();
                }
                settingsDialog = null;
            } catch (Exception e) {
                Log.e(TAG, "dismissSettingsDialog 异常", e);
                settingsDialog = null;
            }
        }
    }

    public boolean isOpeningSettings() {
        return isOpeningSettings;
    }

    public void setOpeningSettings(boolean opening) {
        this.isOpeningSettings = opening;
    }

    public boolean isChannelReverse() {
        return channelReverse;
    }

    public boolean isNumberChannelEnabled() {
        return numberChannelEnable;
    }

    public boolean isPipEnabled() {
        return pipEnable;
    }

    public boolean isExitDialogEnabled() {
        return sp != null && sp.getBoolean("exit_dialog_enable", false);
    }

    public boolean hasRecentSettingsClose() {
        return settingsCloseTime > 0 && System.currentTimeMillis() - settingsCloseTime < 1000;
    }

    public void cleanup() {
        dismissSettingsDialog();
        unregisterReceiver();
    }
}
