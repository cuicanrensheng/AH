package com.tv.live.model;

import java.util.List;

public class Playlist {
    private long id;
    private String name;
    private String url; // 加上这个字段，给 WebServer 用
    private List<Channel> channels;

    // 无参构造（你现在用的）
    public Playlist() {}

    // 全参构造（兼容所有情况）
    public Playlist(long id, String name, String url, List<Channel> channels) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.channels = channels;
    }

    // 兼容旧代码的构造方法
    public Playlist(long id, String name, List<Channel> channels) {
        this(id, name, "", channels);
    }

    // 加上 WebServer 需要的 getUrl() 方法
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Channel> getChannels() { return channels; }
    public void setChannels(List<Channel> channels) { this.channels = channels; }
}
