package com.tv.live;

public class UrlConfig {
    //默认内置直播源（源1固定地址）
    public static final String DEFAULT_LIVE = "https://gitee.com/qf_111/iptv/raw/master/playlist.m3u";
    //默认内置EPG（源1固定地址）
    public static final String DEFAULT_EPG = "https://epg.catvod.com/epg.xml";

    //全局生效地址，切换源动态赋值
    public static String LIVE_URL = DEFAULT_LIVE;
    public static String EPG_URL = DEFAULT_EPG;
}
