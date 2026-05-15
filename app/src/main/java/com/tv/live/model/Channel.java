package com.tv.live.model;

public class Channel {
    private int id;
    private String name;
    private String url;
    private boolean isFavorite;

    // 全参构造（你现在用的）
    public Channel(int id, String name, String url, boolean isFavorite) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.isFavorite = isFavorite;
    }

    // 兼容旧代码的构造方法（给 PlaylistParser 用）
    public Channel(long id, String name, String url) {
        this((int) id, name, url, false);
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public boolean isFavorite() { return isFavorite; }
    public void setFavorite(boolean favorite) { isFavorite = favorite; }
}
