package com.tv.live.model;

import java.util.ArrayList;
import java.util.List;

public class Playlist {
    private long id;
    private String name;
    private String url;
    private List<Channel> channels;

    // 无参构造
    public Playlist() {}

    // 4 参数全参构造
    public Playlist(long id, String name, String url, List<Channel> channels) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.channels = channels;
    }

    // 兼容旧代码：3 参数（给 WebServer 用）
    public Playlist(long id, String name, String url) {
        this(id, name, url, new ArrayList<>());
    }

    // 兼容旧代码：3 参数（给 PlaylistParser 用）
    public Playlist(long id, String name, List<Channel> channels) {
        this(id, name, "", channels);
    }

    // Getter 和 Setter
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public List<Channel> getChannels() { return channels; }
    public void setChannels(List<Channel> channels) { this.channels = channels; }
}
