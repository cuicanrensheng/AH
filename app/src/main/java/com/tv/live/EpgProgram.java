package com.tv.live;
public class EpgProgram {
    private String time;
    private String title;
    public EpgProgram(String time, String title) {
        this.time = time;
        this.title = title;
    }
    public String getTime() { return time; }
    public String getTitle() { return title; }
}
