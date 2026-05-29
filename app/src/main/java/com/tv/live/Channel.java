package com.tv.live;

import java.util.List;

public class Channel {
    public String name;
    public String group;
    public String playUrl;
    public List<EpgItem> epgList;

    // 构造方法
    public Channel(String name, String group, String playUrl) {
        this.name = name;
        this.group = group;
        this.playUrl = playUrl;
    }

    public Channel(String name, String group, List<String> urls) {
        this.name = name;
        this.group = group;
        if (urls != null && !urls.isEmpty()) this.playUrl = urls.get(0);
    }

    public String getPlayUrl() { return playUrl; }
    public String getName() { return name; }
    public String getGroup() { return group; }
    public List<EpgItem> getEpgList() { return epgList; }
    public void setEpgList(List<EpgItem> epgList) { this.epgList = epgList; }

    // EpgItem 修复全部错误
    public static class EpgItem {
        public String title;
        public String time;
        public String replayUrl;
        public boolean isNow;

        public EpgItem() {}

        public EpgItem(String title, String time, String replayUrl, boolean isNow) {
            this.title = title;
            this.time = time;
            this.replayUrl = replayUrl;
            this.isNow = isNow;
        }

        public String getTitle() { return title; }
        public String getTime() { return time; }
        public String getReplayUrl() { return replayUrl; }
        public boolean isNow() { return isNow; }
    }
}
