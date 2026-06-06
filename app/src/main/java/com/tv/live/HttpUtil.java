package com.tv.live.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HttpUtil {
    // 补齐缺失UA常量（解决factory.setUserAgent(HttpUtil.UA)报错）
    public static final String UA = "Mozilla/5.0 (Linux; Android; TV) AppleWebKit/537.36 Chrome/114.0.0.0 Safari/537.36";

    // 补齐get(String)静态方法（解决4处HttpUtil.get(url)找不到方法）
    public static String get(String urlStr) {
        HttpURLConnection conn = null;
        InputStream is = null;
        BufferedReader br = null;
        StringBuilder sb = new StringBuilder();
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            // 使用上面UA
            conn.setRequestProperty("User-Agent", UA);

            if (conn.getResponseCode() == 200) {
                is = conn.getInputStream();
                br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (br != null) br.close();
                if (is != null) is.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    // MainActivity用到的getFinalPlayUrl方法（之前遗漏一并补上，防止后续报错）
    public static String getFinalPlayUrl(String sourceUrl) {
        return get(sourceUrl);
    }
}
