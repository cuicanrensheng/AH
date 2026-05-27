package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {
    public static List<MainActivity.Channel> parse(String m3uUrl) throws Exception {
        List<MainActivity.Channel> list = new ArrayList<>();
        HttpURLConnection conn = (HttpURLConnection) new URL(m3uUrl).openConnection();
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));

        String line;
        String group = "默认";
        String name = "";
        List<String> urls = new ArrayList<>();

        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTINF")) {
                if (line.contains("group-title=\"")) {
                    int s = line.indexOf("group-title=\"") + 13;
                    int e = line.indexOf("\"", s);
                    if (e > s) group = line.substring(s, e);
                }
                if (!urls.isEmpty()) {
                    list.add(new MainActivity.Channel(name, group, new ArrayList<>(urls)));
                    urls.clear();
                }
                int comma = line.lastIndexOf(',');
                if (comma != -1) name = line.substring(comma + 1).trim();
            } else if (line.startsWith("http")) {
                urls.add(line);
            }
        }
        if (!urls.isEmpty()) {
            list.add(new MainActivity.Channel(name, group, urls));
        }
        br.close();
        conn.disconnect();
        return list;
    }
}
