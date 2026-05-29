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
        private String startTime;
        private String stopTime;
        private String title;

        public EpgItem(String startTime, String stopTime, String title) {
            this.startTime = startTime;
            this.stopTime = stopTime;
            this.title = title;
        }

        public String getDisplayTime() {
            if (startTime == null || startTime.length() < 12) return "";
            return startTime.substring(8, 10) + ":" + startTime.substring(10, 12);
        }

        public String getTitle() { return title; }
    }
}
