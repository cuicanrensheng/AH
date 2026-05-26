package com.tv.live;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {
    public static List<MainActivity.Channel> parseWithRealName(String m3uUrl) throws Exception {
        List<MainActivity.Channel> channelList = new ArrayList<>();
        HttpURLConnection conn = (HttpURLConnection) new URL(m3uUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent","Mozilla/5.0");
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));

        String line;
        String currentName = "未知频道";
        String currentGroup = "默认";
        List<String> currentUrls = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if(line.startsWith("#EXTINF")){
                // 自动读取分组 group‑title
                if(line.contains("group‑title=\"")){
                    int s = line.indexOf("group‑title=\"") + 13;
                    int e = line.indexOf("\"", s);
                    if(e > s) currentGroup = line.substring(s,e);
                }
                // 保存上一个频道
                if(!currentUrls.isEmpty()){
                    channelList.add(new MainActivity.Channel(currentName, currentGroup, new ArrayList<>(currentUrls)));
                    currentUrls.clear();
                }
                // 读取频道名称
                int comma = line.lastIndexOf(',');
                if(comma != -1) currentName = line.substring(comma+1).trim();
            }else if(line.startsWith("http") && !line.contains(".jpg") && !line.contains(".png")){
                currentUrls.add(line);
            }
        }
        // 最后一个频道
        if(!currentUrls.isEmpty()){
            channelList.add(new MainActivity.Channel(currentName, currentGroup, new ArrayList<>(currentUrls)));
        }
        reader.close();
        conn.disconnect();
        return channelList;
    }
}
