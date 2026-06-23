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
    // 分隔符
    // ====================================================================
    private static final String SEPARATOR = "|||";
    private static final String SEPARATOR_REGEX = "\\|\\|\\|";
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
    // 收藏频道相关
    // ====================================================================
    private static final String KEY_FAVORITE_CHANNELS = "favorite_channels";
    /**
     * 获取收藏的频道列表
     */
    public List<String> getFavoriteChannels() {
        String saved = appSp.getString(KEY_FAVORITE_CHANNELS, "");
        List<String> list = new ArrayList<>();
        if (saved.isEmpty()) return list;
        String[] names = saved.split(SEPARATOR_REGEX);
        for (String name : names) {
            if (!name.isEmpty()) {
                list.add(name);
            }
        }
        return list;
    }
    /**
     * 添加收藏频道
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
     */
    public void removeFavorite(String channelName) {
        List<String> favorites = getFavoriteChannels();
        favorites.remove(channelName);
        saveFavorites(favorites);
    }
    /**
     * 判断频道是否已收藏
     */
    public boolean isFavorite(String channelName) {
        return getFavoriteChannels().contains(channelName);
    }
    /**
     * 切换收藏状态
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
            if (i > 0) sb.append(SEPARATOR);
            sb.append(favorites.get(i));
        }
        appSp.edit().putString(KEY_FAVORITE_CHANNELS, sb.toString()).apply();
    }
    // ====================================================================
    // 最近观看相关
    // ====================================================================
    private static final String KEY_RECENT_CHANNELS = "recent_channels";
    private static final int MAX_RECENT_COUNT = 20; // 最多保存 20 个，超过则清空
    /**
     * 获取最近观看的频道列表
     *
     * @return 最近观看的频道名列表（最新的在最前面）
     */
    public List<String> getRecentChannels() {
        String saved = appSp.getString(KEY_RECENT_CHANNELS, "");
        List<String> list = new ArrayList<>();
        if (saved.isEmpty()) return list;
        String[] names = saved.split(SEPARATOR_REGEX);
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
     * 【2026-06-22 修改：超过20个时自动清空全部，继续记录新频道】
     * 【说明】
     * 1. 如果当前记录数 >= 20，先清空全部记录
     * 2. 如果频道已经在列表里，先移除旧的
     * 3. 新的加到最前面
     */
    public void addRecentChannel(String channelName) {
        List<String> recent = getRecentChannels();
        
        // ✅ 新增：超过20个时，先清空全部记录
        if (recent.size() >= MAX_RECENT_COUNT) {
            recent.clear();
        }
        
        // 先移除旧的（如果存在）
        recent.remove(channelName);
        // 加到最前面
        recent.add(0, channelName);
        
        saveRecent(recent);
    }
    /**
     * 保存最近观看列表到 SharedPreferences
     */
    private void saveRecent(List<String> recent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) sb.append(SEPARATOR);
            sb.append(recent.get(i));
        }
        appSp.edit().putString(KEY_RECENT_CHANNELS, sb.toString()).apply();
    }
}
