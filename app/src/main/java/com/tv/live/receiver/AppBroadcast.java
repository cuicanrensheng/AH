package com.tv.live.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import com.tv.live.AppConfig;
import com.tv.live.MainActivity;
import com.tv.live.UrlConfig;
import com.tv.live.util.LogUtils;

/**
 * 全局广播工厂类
 * 拆分MainActivity两个内部BroadcastReceiver
 * 统一生成：Exo控制器开关广播、刷新源&EPG广播
 */
public class AppBroadcast {

    /**
     * 获取播放器原生控制器显示/隐藏广播
     */
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

    /**
     * 获取刷新直播源+EPG配置广播
     * Action: com.tv.live.REFRESH_LIVE_AND_EPG
     */
    public static BroadcastReceiver getRefreshReceiver(MainActivity activity) {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                    activity.runOnUiThread(() -> {
                        // 重载本地配置
                        activity.settingsManager.reloadConfig();
                        AppConfig cfg = AppConfig.getInstance(activity);
                        // 读取自定义源覆盖全局地址
                        String customLive = cfg.getCustomLiveUrl();
                        String customEpg = cfg.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        // 重新加载源和EPG
                        activity.loadLiveAndEpg();
                        Toast.makeText(activity, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        };
    }
}
