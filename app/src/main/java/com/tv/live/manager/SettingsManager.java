package com.tv.live.manager;

import android.content.Context;
import android.content.SharedPreferences;
import com.tv.live.util.LogUtils;

public class SettingsManager {
    private static SettingsManager instance;
    private final SharedPreferences sp;

    public boolean epg_enable;
    public boolean channel_reverse;
    public boolean number_channel_enable;
    public boolean auto_update_source;

    // 公开构造方法
    public SettingsManager(Context context) {
        sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        reloadConfig();
    }

    public static SettingsManager getInstance(Context context) {
        if (instance == null) {
            instance = new SettingsManager(context);
        }
        return instance;
    }

    public void reloadConfig() {
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
        LogUtils.log("【设置】EPG开关：" + epg_enable);
        LogUtils.log("【设置】切台反转：" + channel_reverse);
    }
}
