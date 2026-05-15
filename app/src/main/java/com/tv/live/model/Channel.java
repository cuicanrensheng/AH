package com.tv.live.model;
public class Channel {
    private int id;
    private String name;
    private String url;
    private boolean isFavorite;

    public Channel(int id, String name, String url, boolean isFavorite) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.isFavorite = isFavorite;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public boolean isFavorite() { return isFavorite; }
    public void setFavorite(boolean favorite) { isFavorite = favorite; }
}
