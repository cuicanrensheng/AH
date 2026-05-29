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

    // ↓↓↓ 就是缺了这个内部类，导致全部报红 ↓↓↓
    public static class EpgItem {
        private String day;
        private String time;
        private String title;
        private boolean isReplay;
        private String replayUrl;

        public EpgItem(String day, String time, String title, boolean isReplay) {
            this.day = day;
            this.time = time;
            this.title = title;
            this.isReplay = isReplay;
        }

        public String getDay() {
            return day;
        }

        public String getTime() {
            return time;
        }

        public String getTitle() {
            return title;
        }

        public boolean isReplay() {
            return isReplay;
        }

        public String getReplayUrl() {
            return replayUrl;
        }

        public void setReplayUrl(String replayUrl) {
            this.replayUrl = replayUrl;
        }
    }
}
