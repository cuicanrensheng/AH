package com.tv.live;

public class Channel {
    private String name;
    private String playUrl;
    private String group;
    private String channelId;

    public Channel(String name, String playUrl, String group, String channelId) {
        this.name = name;
        this.playUrl = playUrl;
        this.group = group;
        this.channelId = channelId;
    }

    public String getName() { return name; }
    public String getPlayUrl() { return playUrl; }
    public String getGroup() { return group; }
    public String getChannelId() { return channelId; }

    public static class EpgItem {
        public String dayName;
        public String time;      // 开始时间
        public String endTime;   // ✅ 新增：结束时间
        public String title;
        public boolean isPlaying;

        // 原有构造方法（兼容老代码）
        public EpgItem(String dayName, String time, String title, boolean isPlaying) {
            this.dayName = dayName;
            this.time = time;
            this.title = title;
            this.isPlaying = isPlaying;
            this.endTime = null; // 默认可为空
        }

        // ✅ 新增：带结束时间的构造（真正使用的）
        public EpgItem(String dayName, String time, String endTime, String title, boolean isPlaying) {
            this.dayName = dayName;
            this.time = time;
            this.endTime = endTime;
            this.title = title;
            this.isPlaying = isPlaying;
        }

        public String getReplayUrl() {
            return null;
        }
    }
}
