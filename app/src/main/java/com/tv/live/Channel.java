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
