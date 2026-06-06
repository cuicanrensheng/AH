package com.tv.live.util;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 播放地址重定向解析工具类
 * 拆分自MainActivity.playChannel内部网络请求代码
 * 功能：递归解析301/302重定向链接、提取虎牙房间ID、统一请求头配置
 */
public class RedirectUrlUtil {
    // 最大重定向递归次数，防止死循环跳转
    private static final int MAX_REDIRECT_COUNT = 10;
    // 链接连接超时时间
    private static final int CONNECT_TIMEOUT = 8000;
    // 数据读取超时时间
    private static final int READ_TIMEOUT = 8000;
    // 请求UA标识
    private static final String DEF_UA = "ExoPlayer";
    // 默认Referer防盗链
    private static final String DEF_REFER = "https://www.huya.com/";

    /**
     * 输入原始播放地址，返回经过重定向解析后的真实播放链接
     * @param originalUrl 原始源地址
     * @return 解析完成最终直链
     */
    public static String getRealPlayUrl(String originalUrl) {
        HttpURLConnection conn = null;
        String finalUrl = originalUrl;
        try {
            // 循环遍历重定向
            for (int step = 0; step < MAX_REDIRECT_COUNT; step++) {
                URL urlObj = new URL(finalUrl);
                conn = (HttpURLConnection) urlObj.openConnection();
                // 配置连接参数
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", DEF_UA);
                conn.setRequestProperty("Referer", DEF_REFER);
                conn.setRequestProperty("Origin", "https://www.huya.com");
                conn.setRequestProperty("Icy-MetaData", "1");
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                // 关闭自动跟随重定向，手动捕获Location
                conn.setInstanceFollowRedirects(false);

                int code = conn.getResponseCode();
                // 捕获301/302跳转
                if (code == 301 || code == 302) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null) finalUrl = loc;
                    LogUtils.log("【重定向" + (step + 1) + "次】→ " + finalUrl);
                    conn.disconnect();
                    conn = null;
                } else {
                    // 非跳转链接，结束循环
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            LogUtils.log("【解析失败】使用原始地址播放");
        } finally {
            // 释放连接
            if (conn != null) conn.disconnect();
        }
        return finalUrl;
    }

    /**
     * 从虎牙链接中截取房间数字ID（备用工具）
     * @param url 原始播放地址
     * @return 纯数字房间ID，解析失败返回0
     */
    public static int extractRoomId(String url) {
        try {
            if (url.contains("id=")) {
                return Integer.parseInt(url.split("id=")[1].replaceAll("[^0-9]", ""));
            }
            if (url.contains("huya.com/")) {
                return Integer.parseInt(url.split("huya.com/")[1].replaceAll("[^0-9]", ""));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }
}
