package com.tv.live.model;
import java.util.List;

public class Playlist {
    private long id;
    private String name;
    private List<Channel> channels;

    // 无参构造（必须加，否则报错）
    public Playlist() {}

    // 全参构造
    public Playlist(long id, String name, List<Channel> channels) {
        this.id = id;
        this.name = name;
        this.channels = channels;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Channel> getChannels() { return channels; }
    public void setChannels(List<Channel> channels) { this.channels = channels; }
}
