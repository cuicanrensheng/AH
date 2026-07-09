package com.tv.live;

import java.util.ArrayList;
import java.util.List;

public class Channel {
    private String name;
    // 主播放地址
    private String mainPlayUrl;
    // 备用播放地址列表
    private List<String> backupUrls;
    private String group;
    private String channelId;

    public Channel(String name, String mainPlayUrl, String group, String channelId) {
        this.name = name;
        this.mainPlayUrl = mainPlayUrl;
        this.group = group;
        this.channelId = channelId;
        this.backupUrls = new ArrayList<>();
    }

    // 添加备用源，自动去重
    public void addBackupUrl(String url) {
        if (url != null && !backupUrls.contains(url)) {
            backupUrls.add(url);
        }
    }

    // ====== 兼容旧项目原有代码，保留getPlayUrl()，返回主源地址 ======
    public String getPlayUrl() {
        return mainPlayUrl;
    }

    // 新接口：获取主播放地址
    public String getMainPlayUrl() {
        return mainPlayUrl;
    }

    // 获取全部备用源列表
    public List<String> getBackupUrls() {
        return backupUrls;
    }

    public String getName() {
        return name;
    }

    public String getGroup() {
        return group;
    }

    // 🟢【新增】设置分组，用于解析时动态更新分组名
    public void setGroup(String group) {
        this.group = group;
    }

    public String getChannelId() {
        return channelId;
    }

    public static class EpgItem {
        public String dayName;
        public String time;
        public String title;
        public boolean isPlaying;

        public EpgItem(String dayName, String time, String title, boolean isPlaying) {
            this.dayName = dayName;
            this.time = time;
            this.title = title;
            this.isPlaying = isPlaying;
        }

        public String getReplayUrl() {
            return null;
        }
    }
}
