package com.tv.live;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {
    private static final String TAG = "M3UParser";

    public static List<MainActivity.Channel> parseWithRealName(String m3uUrl) throws IOException {
        List<MainActivity.Channel> channelList = new ArrayList<>();
        URL url = new URL(m3uUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String line;
        String currentName = "未知频道";
        List<String> currentUrls = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTINF")) {
                if (!currentUrls.isEmpty()) {
                    MainActivity.Channel ch = new MainActivity.Channel(currentName, new ArrayList<>(currentUrls));
                    ch.epg = generateMockEpg(currentName); // ← 自动生成节目单
                    channelList.add(ch);
                    currentUrls.clear();
                }
                int comma = line.lastIndexOf(',');
                if (comma != -1) {
                    currentName = line.substring(comma + 1).trim();
                }
            } else if (line.startsWith("http") && !line.contains(".jpg") && !line.contains(".png") && !line.contains(".mp4")) {
                currentUrls.add(line);
            }
        }

        if (!currentUrls.isEmpty()) {
            MainActivity.Channel ch = new MainActivity.Channel(currentName, new ArrayList<>(currentUrls));
            ch.epg = generateMockEpg(currentName);
            channelList.add(ch);
        }

        reader.close();
        connection.disconnect();
        return channelList;
    }

    // ==============================================
    // 自动生成节目单（当前直播+预告+回放）
    // ==============================================
    private static String generateMockEpg(String channelName) {
        return "正在播放：直播\n" +
               "20:00 黄金剧场\n" +
               "21:30 晚间新闻\n" +
               "22:10 纪录片\n" +
               "回看 19:00 新闻联播\n" +
               "回看 18:30 地方新闻";
    }
}
