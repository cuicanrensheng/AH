package com.tv.live.config;

import android.content.Context;
import android.content.SharedPreferences;

public class AppConfig {
    private static AppConfig instance;
    private final SharedPreferences appSp;
    private final SharedPreferences playSp;

    private AppConfig(Context context) {
        appSp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        playSp = context.getSharedPreferences("play_config", Context.MODE_PRIVATE);
    }

    public static AppConfig getInstance(Context context) {
        if (instance == null) {
            instance = new AppConfig(context.getApplicationContext());
        }
        return instance;
    }

    // 直播源/节目单URL
    public String getCustomLiveUrl() {
        return appSp.getString("custom_live_url", null);
    }

    public String getCustomEpgUrl() {
        return appSp.getString("custom_epg_url", null);
    }

    public void setCustomUrls(String liveUrl, String epgUrl) {
        SharedPreferences.Editor editor = appSp.edit();
        if (liveUrl != null) editor.putString("custom_live_url", liveUrl);
        if (epgUrl != null) editor.putString("custom_epg_url", epgUrl);
        editor.apply();
    }

    // 频道切换方向
    public boolean isChannelReverse() {
        return appSp.getBoolean("channel_reverse", false);
    }

    // 屏幕比例
    public String getScreenRatio() {
        return appSp.getString("screen_ratio", "全屏");
    }

    // 上次播放的频道索引
    public int getLastPlayIndex() {
        return playSp.getInt("last_play_index", 0);
    }

    public void setLastPlayIndex(int index) {
        playSp.edit().putInt("last_play_index", index).apply();
    }

    public int getCurrentRatioIndex() {
        return playSp.getInt("play_ratio", 2);
    }
}
