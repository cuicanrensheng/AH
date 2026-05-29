package com.tv.live;

/**
 * 直播源 + EPG 统一配置文件
 * 支持 .gz 自动解压
 */
public class UrlConfig {

    //===================== 直播源链接 =====================
    public static final String LIVE_URL = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";

    //===================== EPG 链接（支持 .gz 解压） =====================
    public static final String EPG_URL = "https://e.erw.cc/all.xml.gz";

}
