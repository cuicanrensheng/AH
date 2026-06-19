package com.tv.live.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用配置管理类（单例模式）
 *
 * 【功能说明】
 * 统一管理应用的所有设置项，使用 SharedPreferences 持久化存储。
 *
 * 【存储说明】
 * - app_settings：应用级设置（开关、URL 等）
 * - play_config：播放相关配置（上次播放的频道、屏幕比例等）
 *
 * 【新增内容】
 * 1. isEpgEnable() / setEpgEnable() - EPG 节目单开关
 * 2. isNumChannelEnable() / setNumChannelEnable() - 数字选台开关
 */
public class AppConfig {

    // 单例实例
    private static AppConfig instance;

    // 应用设置 SP（开关、URL 等）
    private final SharedPreferences appSp;
    // 播放配置 SP（上次播放位置、屏幕比例等）
    private final SharedPreferences playSp;

    /**
     * 私有构造函数（单例模式）
     *
     * @param context 上下文
     */
    private AppConfig(Context context) {
        appSp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        playSp = context.getSharedPreferences("play_config", Context.MODE_PRIVATE);
    }

    /**
     * 获取单例实例
     *
     * @param context 上下文
     * @return AppConfig 实例
     */
    public static AppConfig getInstance(Context context) {
        if (instance == null) {
            instance = new AppConfig(context.getApplicationContext());
        }
        return instance;
    }

    // ====================== 直播源/节目单 URL 相关 ======================

    /**
     * 获取自定义直播源地址
     *
     * @return 自定义直播源 URL，没有设置则返回 null
     */
    public String getCustomLiveUrl() {
        return appSp.getString("custom_live_url", null);
    }

    /**
     * 获取自定义 EPG 节目单地址
     *
     * @return 自定义 EPG URL，没有设置则返回 null
     */
    public String getCustomEpgUrl() {
        return appSp.getString("custom_epg_url", null);
    }

    /**
     * 设置自定义直播源和 EPG 地址
     *
     * @param liveUrl 直播源地址，为 null 则不修改
     * @param epgUrl  EPG 地址，为 null 则不修改
     */
    public void setCustomUrls(String liveUrl, String epgUrl) {
        SharedPreferences.Editor editor = appSp.edit();
        if (liveUrl != null) editor.putString("custom_live_url", liveUrl);
        if (epgUrl != null) editor.putString("custom_epg_url", epgUrl);
        editor.apply();
    }

    // ====================== 开关类设置 ======================

    /**
     * 获取换台反转开关状态
     *
     * @return true=反转（上键=下一台），false=正常
     */
    public boolean isChannelReverse() {
        return appSp.getBoolean("channel_reverse", false);
    }

    /**
     * 设置换台反转开关
     *
     * @param reverse true=反转，false=正常
     */
    public void setChannelReverse(boolean reverse) {
        appSp.edit().putBoolean("channel_reverse", reverse).apply();
    }

    // ====================================================================
    // ✅ 新增：EPG 节目单开关
    // ====================================================================
    /**
     * 获取 EPG 节目单开关状态
     *
     * 【作用】
     * 控制是否显示和加载 EPG 节目单。
     * 关闭后可以节省流量和内存，适合不需要节目单的场景。
     *
     * @return true=开启，false=关闭，默认开启
     */
    public boolean isEpgEnable() {
        return appSp.getBoolean("epg_enable", true);
    }

    /**
     * 设置 EPG 节目单开关
     *
     * @param enable true=开启，false=关闭
     */
    public void setEpgEnable(boolean enable) {
        appSp.edit().putBoolean("epg_enable", enable).apply();
    }

    // ====================================================================
    // ✅ 新增：数字选台开关
    // ====================================================================
    /**
     * 获取数字选台开关状态
     *
     * 【作用】
     * 控制是否响应遥控器的数字按键进行选台。
     * 关闭后数字按键不会触发选台，避免误触。
     *
     * @return true=开启，false=关闭，默认开启
     */
    public boolean isNumChannelEnable() {
        return appSp.getBoolean("num_channel_enable", true);
    }

    /**
     * 设置数字选台开关
     *
     * @param enable true=开启，false=关闭
     */
    public void setNumChannelEnable(boolean enable) {
        appSp.edit().putBoolean("num_channel_enable", enable).apply();
    }

    // ====================== 播放相关配置 ======================

    /**
     * 获取屏幕比例设置
     *
     * @return 屏幕比例字符串（原始/填充/全屏等）
     */
    public String getScreenRatio() {
        return appSp.getString("screen_ratio", "全屏");
    }

    /**
     * 设置屏幕比例
     *
     * @param ratio 屏幕比例字符串
     */
    public void setScreenRatio(String ratio) {
        appSp.edit().putString("screen_ratio", ratio).apply();
    }

    /**
     * 获取上次播放的频道索引
     *
     * 【作用】
     * 应用下次启动时自动跳转到上次观看的频道，
     * 提升用户体验，不用每次都重新选台。
     *
     * @return 上次播放的频道索引
     */
    public int getLastPlayIndex() {
        return playSp.getInt("last_play_index", 0);
    }

    /**
     * 设置上次播放的频道索引
     *
     * 每次切换频道时调用，保存当前观看位置。
     *
     * @param index 频道索引
     */
    public void setLastPlayIndex(int index) {
        playSp.edit().putInt("last_play_index", index).apply();
    }

    /**
     * 获取当前屏幕比例的索引（用于设置页面选中状态）
     *
     * @return 比例选项的索引
     */
    public int getCurrentRatioIndex() {
        return playSp.getInt("play_ratio", 2);
    }

    /**
     * 设置当前屏幕比例的索引
     *
     * @param index 比例选项的索引
     */
    public void setCurrentRatioIndex(int index) {
        playSp.edit().putInt("play_ratio", index).apply();
    }
}
