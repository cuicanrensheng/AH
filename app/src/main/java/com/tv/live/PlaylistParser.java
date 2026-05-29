package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {
   public static List<Channel> parse(String m3uUrl) throws Exception {
        List<MainActivity.Channel> list = new ArrayList<>();
        HttpURLConnection conn = (HttpURLConnection) new URL(m3uUrl).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("GET");
        conn.connect();

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));

        String line;
        String group = "默认";
        String name = "";
        List<String> urls = new ArrayList<>();

        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 处理 #EXTINF 行，提取频道名和分组
            if (line.startsWith("#EXTINF")) {
                // 先把上一个频道保存
                if (!urls.isEmpty()) {
                    list.add(new MainActivity.Channel(name, group, new ArrayList<>(urls)));
                    urls.clear();
                }

                // 提取 group-title
                if (line.contains("group-title=\"")) {
                    int s = line.indexOf("group-title=\"") + 13;
                    int e = line.indexOf("\"", s);
                    if (e > s) group = line.substring(s, e);
                }

                // 提取频道名称（逗号后的部分）
                int comma = line.lastIndexOf(',');
                if (comma != -1) {
                    name = line.substring(comma + 1).trim();
                } else {
                    name = "未知频道";
                }
            }
            // 处理直播地址行
            else if (line.startsWith("http") || line.startsWith("rtmp") || line.startsWith("rtsp")) {
                urls.add(line);
            }
        }

        // 保存最后一个频道
        if (!urls.isEmpty()) {
            list.add(new MainActivity.Channel(name, group, urls));
        }

        br.close();
        conn.disconnect();
        return list;
    }
}
