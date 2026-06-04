package com.tv.live;
public class UrlConfig {
    //默认源常量（修复报错缺少字段）
    public static final String DEFAULT_LIVE = "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u";
    public static final String DEFAULT_EPG = "https://epg.catvod.com/epg.xml";

    //运行时可修改地址
    public static String LIVE_URL = DEFAULT_LIVE;
    public static String EPG_URL = DEFAULT_EPG;
}
