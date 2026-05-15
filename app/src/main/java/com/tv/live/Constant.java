package com.tv.live;

public class Constant {
    // 网页后台端口
    public static final int WEB_PORT = 10481;

    // 默认订阅源（支持IPv6）
    public static final String DEFAULT_M3U_URL = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";

    // 缓存配置Key
    public static final String KEY_SUBSCRIBE_LIST = "subscribe_list";
    public static final String KEY_EPG_LIST = "epg_list";
    public static final String KEY_PLAYABLE_DOMAIN = "playable_domain";
    public static final String KEY_COLLECT_LIST = "collect_list";

    // 设置项
    public static final String KEY_REVERSE_CHANGE = "reverse_change";
    public static final String KEY_AUTO_NEXT_LINE = "auto_next_line";
    public static final String KEY_CACHE_TIME = "cache_time";
}
