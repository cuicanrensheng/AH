package com.tv.live;

public class Channel {
    private String name;
    private String playUrl;

    public Channel(String name, String playUrl) {
        this.name = name;
        this.playUrl = playUrl;
    }

    public String getName() {
        return name;
    }

    public String getPlayUrl() {
        return playUrl;
    }

    public static class EpgItem {
        public String day;
        public String time;
        public String title;
        public boolean isReplay;
        public String replayUrl;

        public EpgItem(String day, String time, String title, boolean isReplay) {
            this.day = day;
            this.time = time;
            this.title = title;
            this.isReplay = isReplay;
        }

        public String getReplayUrl() {
            return replayUrl;
        }

        public void setReplayUrl(String replayUrl) {
            this.replayUrl = replayUrl;
        }
    }
}
