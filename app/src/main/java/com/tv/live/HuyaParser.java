package com.tv.live;

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HuyaParser {
    private static final String TAG = "HuyaParser";

    public static String getHuyaRealUrl(String input) throws IOException {
        String roomId;
        Pattern numPattern = Pattern.compile("(\\d{5,})");
        Matcher numMatcher = numPattern.matcher(input);
        if (numMatcher.find()) {
            roomId = numMatcher.group(1);
        } else {
            throw new IOException("未识别虎牙房间号");
        }

        URL url = new URL("https://m.huya.com/" + roomId);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        String line;
        String realM3u8 = null;
        Pattern urlPattern = Pattern.compile("https?://[^\"']+\\.m3u8");

        while ((line = br.readLine()) != null) {
            if (line.contains("hls") && line.contains(".m3u8")) {
                Matcher urlMatcher = urlPattern.matcher(line);
                if (urlMatcher.find()) {
                    realM3u8 = urlMatcher.group();
                    break;
                }
            }
        }

        br.close();
        conn.disconnect();

        if (realM3u8 == null) {
            throw new IOException("主播未开播或获取源失败");
        }
        return realM3u8;
    }
}
