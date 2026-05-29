package com.tv.live;

public class Channel {
    private String name;
    private String playUrl;

    public Channel(String name, String playUrl) {
        this.name = name;
        this.playUrl = playUrl;
    }

    public String getPlayUrl() {
        return playUrl;
    }
}
