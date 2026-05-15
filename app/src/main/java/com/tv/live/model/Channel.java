package com.tv.live.model;

public class Channel {
    private long id;
    private String name;
    private String url;
    private boolean isFavorite;

    public Channel(long id, String name, String url) {
        this(id, name, url, false);
    }

    public Channel(long id, String name, String url, boolean isFavorite) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.isFavorite = isFavorite;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public boolean isFavorite() { return isFavorite; }
}
