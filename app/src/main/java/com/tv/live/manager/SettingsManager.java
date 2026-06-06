package com.tv.live.manager;

import android.content.Context;
import android.content.SharedPreferences;
import com.tv.live.util.LogUtils;

/**
 * APP本地配置管理器
 * 拆分自MainActivity loadSettings()方法
 * 统一管理SP配置读写：EPG开关、切台反转、数字选台、自动更新源四项配置
 */
public class SettingsManager {
    private final SharedPreferences sp;
    // EPG节目单总开关
    public boolean epg_enable;
    // 上下切台方向反转开关
    public boolean channel_reverse;
    // 遥控器数字选台开关
    public boolean number_channel_enable;
    // 自动更新直播源开关
    public boolean auto_update_source;

    /**
     * 构造：初始化SP对象并立刻加载配置
     * @param context 页面上下文
     */
    public SettingsManager(Context context) {
        sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        reloadConfig();
    }

    /**
     * 重新从本地SP读取所有配置，页面onResume/刷新源时调用
     */
    public void reloadConfig() {
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
        LogUtils.log("【设置】EPG开关：" + epg_enable);
        LogUtils.log("【设置】切台反转：" + channel_reverse);
    }
}
