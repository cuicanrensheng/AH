package com.tv.live;

import java.util.List;

/**
 * 频道实体 + 节目单实体
 */
public class Channel {
    private String channelName;
    private String playUrl;
    private List<EpgItem> epgList;

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getPlayUrl() {
        return playUrl;
    }

    public void setPlayUrl(String playUrl) {
        this.playUrl = playUrl;
    }

    public List<EpgItem> getEpgList() {
        return epgList;
    }

    public void setEpgList(List<EpgItem> epgList) {
        this.epgList = epgList;
    }

    /**
     * 节目单子项
     */
    public static class EpgItem {
        private String title;
        private String startTime;
        private String endTime;
        private String replayUrl;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getStartTime() {
            return startTime;
        }

        public void setStartTime(String startTime) {
            this.startTime = startTime;
        }

        public String getEndTime() {
            return endTime;
        }

        public void setEndTime(String endTime) {
            this.endTime = endTime;
        }

        public String getReplayUrl() {
            return replayUrl;
        }

        public void setReplayUrl(String replayUrl) {
            this.replayUrl = replayUrl;
        }
    }
}
