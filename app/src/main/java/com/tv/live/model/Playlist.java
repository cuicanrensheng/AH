package com.tv.live.model;

import java.util.ArrayList;
import java.util.List;

public class Playlist {
    private long id;
    private String name;
    private String url;
    private List<Channel> channels;

    public Playlist(long id, String name, String url) {
        this(id, name, url, new ArrayList<>());
    }

    public Playlist(long id, String name, String url, List<Channel> channels) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.channels = channels;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public List<Channel> getChannels() { return channels; }
}
