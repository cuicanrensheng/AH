package com.tv.live;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class StreamUrlResolver {
    private static final String TAG = "StreamResolver";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8";
    private static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9";
    private static final String CACHE_CONTROL = "no-cache";

    public static String resolve(String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.endsWith(".m3u8") || url.endsWith(".ts") || url.endsWith(".mp4")) return url;
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
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);

            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", ACCEPT);
            conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);
            conn.setRequestProperty("Cache-Control", CACHE_CONTROL);

            String domain = url.substring(0, url.indexOf("/", 8));
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

            br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String body = sb.toString();

            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(https?://[^\\s\"<>]+\\.m3u8)");
            java.util.regex.Matcher m = p.matcher(body);
            if (m.find()) {
                String real = m.group(1);
                Log.d(TAG, "文本解析成功: " + real);
                return real;
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
        return url;
    }
}
