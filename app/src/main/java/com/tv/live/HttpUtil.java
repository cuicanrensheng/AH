package com.tv.live.util;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP工具类：直播地址重定向解析、携带防盗链请求头、Cookie缓存、自动多级跳转
 * 对外调用：HttpUtil.getFinalPlayUrl(原始播放地址) 返回最终真实直链
 */
public class HttpUtil {
    // 最大重定向次数，防止死循环
    private static final int MAX_REDIRECT = 10;
    // 域名Cookie缓存：key=域名 host, value=Cookie字符串
    private static final Map<String, String> DOMAIN_COOKIE_MAP = new HashMap<>();

    /**
     * 【核心方法】输入原始链接，自动递归解析301/302重定向，返回最终真实播放地址
     * @param srcUrl 原始中转/虎牙链接
     * @return 解析后最终有效播放地址
     */
    public static String getFinalPlayUrl(String srcUrl) {
        if (srcUrl == null || srcUrl.trim().isEmpty()) {
            return srcUrl;
        }
        String nowUrl = srcUrl.trim();
        int redirectTimes = 0;

        while (redirectTimes < MAX_REDIRECT) {
            HttpURLConnection conn = null;
            try {
                URL urlObj = new URL(nowUrl);
                conn = (HttpURLConnection) urlObj.openConnection();
                // 关闭系统自动重定向，手动处理跳转
                conn.setInstanceFollowRedirects(false);
                // 设置请求头（UA、Referer、Cookie，解决403）
                setBaseHeader(conn, urlObj);
                // 连接超时
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                // 保存本次域名返回的Set-Cookie，同域名后续请求复用
                saveDomainCookie(conn.getHeaderFields(), urlObj.getHost());

                // 3xx为重定向状态：301/302/307
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        break;
                    }
                    // 相对路径转绝对URL
                    URI baseUri = urlObj.toURI();
                    URI targetUri = baseUri.resolve(location);
                    nowUrl = targetUri.toString();
                    redirectTimes++;
                    continue;
                }
                // 非重定向，终止循环返回当前url
                break;

            } catch (Exception e) {
                // 异常直接返回当前已解析地址
                break;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return nowUrl;
    }

    /**
     * 统一配置请求头：区分虎牙/中转域名的Referer、UA，携带历史Cookie，解决403 Forbidden
     */
    private static void setBaseHeader(HttpURLConnection conn, URL urlObj) {
        String host = urlObj.getHost().toLowerCase();
        // 通用UA（浏览器标识，防拦截）
        String chromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        conn.setRequestProperty("User-Agent", chromeUA);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");

        // 携带该域名缓存的Cookie
        if (DOMAIN_COOKIE_MAP.containsKey(host)) {
            conn.setRequestProperty("Cookie", DOMAIN_COOKIE_MAP.get(host));
        }

        // 中转域名：jdshipin / zxyxndc 固定Referer
        if (host.contains("jdshipin") || host.contains("zxyxndc")) {
            conn.setRequestProperty("Referer", "http://cdn.jdshipin.com:8880");
        }
        // 虎牙域名：huya 固定Referer
        else if (host.contains("huya")) {
            conn.setRequestProperty("Referer", "https://www.huya.com");
        }
    }

    /**
     * 抓取响应头Set-Cookie，按域名缓存
     */
    private static void saveDomainCookie(Map<String, List<String>> headerMap, String host) {
        if (headerMap == null || host == null) return;
        List<String> setCookieList = headerMap.get("Set-Cookie");
        if (setCookieList == null || setCookieList.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (String cookieItem : setCookieList) {
            if (cookieItem.contains(";")) {
                sb.append(cookieItem.split(";")[0]).trim()).append("; ");
            } else {
                sb.append(cookieItem.trim()).append("; ");
            }
        }
        String finalCookie = sb.toString().trim();
        if (!finalCookie.isEmpty()) {
            DOMAIN_COOKIE_MAP.put(host, finalCookie);
        }
    }

    /**
     * 清空Cookie缓存（可选：设置页刷新源时调用）
     */
    public static void clearAllCookie() {
        DOMAIN_COOKIE_MAP.clear();
    }
}
