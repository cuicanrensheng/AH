package com.tv.live.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import com.tv.live.MainActivity;
import com.tv.live.UrlConfig;
import com.tv.live.config.AppConfig; // 修复这里
import com.tv.live.util.LogUtils;

public class AppBroadcast {

    public static BroadcastReceiver getToggleControllerReceiver(MainActivity activity) {
        return new BroadcastReceiver() {
            private boolean isControllerVisible = false;
            @Override
            public void onReceive(Context context, Intent intent) {
                isControllerVisible = !isControllerVisible;
                activity.playerView.setUseController(isControllerVisible);
            }
        };
    }

    public static BroadcastReceiver getRefreshReceiver(MainActivity activity) {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                    activity.runOnUiThread(() -> {
                        activity.settingsManager.reloadConfig();
                        AppConfig cfg = AppConfig.getInstance(activity);
                        String customLive = cfg.getCustomLiveUrl();
                        String customEpg = cfg.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        activity.loadLiveAndEpg();
                        Toast.makeText(activity, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        };
    }
}
