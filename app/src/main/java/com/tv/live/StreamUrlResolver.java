package com.tv.live;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamUrlResolver {
    private static final String TAG = "StreamResolver";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8";
    private static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9";
    private static final String CACHE_CONTROL = "no-cache";

    // 正则预编译，提高解析效率
    private static final Pattern M3U8_PATTERN = Pattern.compile("(https?://[^\\s\"<>]+\\.m3u8)");

    /**
     * 解析流地址
     * ⚠️ 注意：此方法涉及网络请求，必须从后台线程调用，严禁在主线程执行！
     */
    public static String resolve(String url) {
        if (url == null || url.isEmpty()) return url;
        // 已经是直链，不需要解析
        if (url.endsWith(".m3u8") || url.endsWith(".ts") || url.endsWith(".mp4")) return url;
        // 识别需要处理的动态接口
        if (url.contains(".php") || url.contains("?id=") || url.contains(".asp")) {
            return parse(url);
        }
        return url;
    }

    private static String parse(String url) {
        HttpURLConnection conn = null;
        BufferedReader br = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            // 🟢 将超时从 15 秒缩短至 10 秒，减少卡顿时间
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(false);

            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", ACCEPT);
            conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);
            conn.setRequestProperty("Cache-Control", CACHE_CONTROL);

            // 🟢 安全截取域名（修复 indexOf 越界问题）
            int slashIndex = url.indexOf("/", 8);
            String domain = url;
            if (slashIndex > 0) {
                domain = url.substring(0, slashIndex);
            }
            conn.setRequestProperty("Referer", domain);
            conn.setRequestProperty("Origin", domain);

            int code = conn.getResponseCode();
            if (code == 301 || code == 302) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && loc.startsWith("http")) {
                    Log.d(TAG, "跳转解析成功: " + loc);
                    return loc;
                }
            }

            // 🟢 核心优化：流式读取，逐行正则匹配，避免大文件 OOM
            br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                Matcher m = M3U8_PATTERN.matcher(line);
                if (m.find()) {
                    String real = m.group(1);
                    Log.d(TAG, "流式匹配解析成功: " + real);
                    return real;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "解析失败", e);
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
        return url;
    }
}
