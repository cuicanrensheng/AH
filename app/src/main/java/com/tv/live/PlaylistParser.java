package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {
    public static List<Channel> parse(String url) {
        List<Channel> list = new ArrayList<>();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.connect();

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            String name = null;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF:")) {
                    int idx = line.lastIndexOf(",");
                    if (idx != -1) name = line.substring(idx + 1).trim();
                } else if (line.startsWith("http")) {
                    if (name != null) list.add(new Channel(name, line));
                }
            }
            br.close();
        } catch (Exception e) { e.printStackTrace(); }
        return list;
    }
}
