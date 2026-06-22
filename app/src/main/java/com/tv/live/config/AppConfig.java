package com.tv.live.config;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    private static AppConfig instance;
    private final SharedPreferences appSp;
    private final SharedPreferences playSp;

    // ====================================================================
    // ✅ 2026-06-21 新增：收藏和最近观看的分隔符
    //
    // 【重要说明】
    // SEPARATOR 用于保存时拼接字符串
    // SEPARATOR_REGEX 用于 split 时分割（因为 | 在正则里是特殊字符，必须转义）
    // ====================================================================
    private static final String SEPARATOR = "|||";
    private static final String SEPARATOR_REGEX = "\\|\\|\\|"; // ✅ 转义后的分隔符，用于 split

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

    // ====================================================================
    // ✅ 2026-06-21 新增：收藏频道相关
    // ====================================================================
    private static final String KEY_FAVORITE_CHANNELS = "favorite_channels";

    /**
     * 获取收藏的频道列表
     *
     * @return 收藏的频道名列表
     */
    public List<String> getFavoriteChannels() {
        String saved = appSp.getString(KEY_FAVORITE_CHANNELS, "");
        List<String> list = new ArrayList<>();
        if (saved.isEmpty()) return list;
        String[] names = saved.split(SEPARATOR_REGEX); // ✅ 改成转义后的分隔符
        for (String name : names) {
            if (!name.isEmpty()) {
                list.add(name);
            }
        }
        return list;
    }

    /**
     * 添加收藏频道
     *
     * @param channelName 频道名
     */
    public void addFavorite(String channelName) {
        List<String> favorites = getFavoriteChannels();
        if (!favorites.contains(channelName)) {
            favorites.add(channelName);
            saveFavorites(favorites);
        }
    }

    /**
     * 取消收藏频道
     *
     * @param channelName 频道名
     */
    public void removeFavorite(String channelName) {
        List<String> favorites = getFavoriteChannels();
        favorites.remove(channelName);
        saveFavorites(favorites);
    }

    /**
     * 判断频道是否已收藏
     *
     * @param channelName 频道名
     * @return true=已收藏
     */
    public boolean isFavorite(String channelName) {
        return getFavoriteChannels().contains(channelName);
    }

    /**
     * 切换收藏状态（已收藏则取消，未收藏则添加）
     *
     * @param channelName 频道名
     * @return 操作后的状态（true=已收藏，false=已取消）
     */
    public boolean toggleFavorite(String channelName) {
        if (isFavorite(channelName)) {
            removeFavorite(channelName);
            return false;
        } else {
            addFavorite(channelName);
            return true;
        }
    }

    /**
     * 保存收藏列表到 SharedPreferences
     */
    private void saveFavorites(List<String> favorites) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < favorites.size(); i++) {
            if (i > 0) sb.append(SEPARATOR); // 保存用原来的分隔符，不用转义
            sb.append(favorites.get(i));
        }
        appSp.edit().putString(KEY_FAVORITE_CHANNELS, sb.toString()).apply();
    }

    // ====================================================================
    // ✅ 2026-06-21 新增：最近观看相关
    // ====================================================================
    private static final String KEY_RECENT_CHANNELS = "recent_channels";
    private static final int MAX_RECENT_COUNT = 10; // 最多保存 10 个

    /**
     * 获取最近观看的频道列表
     *
     * @return 最近观看的频道名列表（最新的在最前面）
     */
    public List<String> getRecentChannels() {
        String saved = appSp.getString(KEY_RECENT_CHANNELS, "");
        List<String> list = new ArrayList<>();
        if (saved.isEmpty()) return list;
        String[] names = saved.split(SEPARATOR_REGEX); // ✅ 改成转义后的分隔符
        for (String name : names) {
            if (!name.isEmpty()) {
                list.add(name);
            }
        }
        return list;
    }

    /**
     * 添加到最近观看
     *
     * @param channelName 频道名
     *
     * 【说明】
     * 1. 如果已经在列表里，先移除旧的
     * 2. 新的加到最前面
     * 3. 最多保留 10 个
     */
    public void addRecentChannel(String channelName) {
        List<String> recent = getRecentChannels();
        // 先移除旧的（如果存在）
        recent.remove(channelName);
        // 加到最前面
        recent.add(0, channelName);
        // 最多保留 10 个
        while (recent.size() > MAX_RECENT_COUNT) {
            recent.remove(recent.size() - 1);
        }
        saveRecent(recent);
    }

    /**
     * 保存最近观看列表到 SharedPreferences
     */
    private void saveRecent(List<String> recent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) sb.append(SEPARATOR); // 保存用原来的分隔符，不用转义
            sb.append(recent.get(i));
        }
        appSp.edit().putString(KEY_RECENT_CHANNELS, sb.toString()).apply();
    }
        // ====================================================================
    // ✅ 画中画开关（统一管理）
    // ====================================================================
    private static final String KEY_PIP_ENABLE = "pip_enable";
    
    /**
     * 获取画中画开关状态
     *
     * @return true=开启，false=关闭（默认关闭）
     */
    public boolean isPipEnabled() {
        return appSp.getBoolean(KEY_PIP_ENABLE, false);
    }
    
    /**
     * 设置画中画开关状态
     *
     * @param enabled true=开启，false=关闭
     */
    public void setPipEnabled(boolean enabled) {
        appSp.edit().putBoolean(KEY_PIP_ENABLE, enabled).apply();
    }
}
