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
                    ch.epgList = generateSampleEpg(ch.name);
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
            ch.epgList = generateSampleEpg(ch.name);
            channelList.add(ch);
        }

        reader.close();
        connection.disconnect();
        return channelList;
    }

    private static List<MainActivity.Channel.EpgItem> generateSampleEpg(String channelName) {
        List<MainActivity.Channel.EpgItem> list = new ArrayList<>();

        MainActivity.Channel.EpgItem e1 = new MainActivity.Channel.EpgItem();
        e1.time = "12:00–12:30";
        e1.title = "往期节目 · 回放";
        e1.playUrl = "";
        e1.isNow = false;
        list.add(e1);

        MainActivity.Channel.EpgItem e2 = new MainActivity.Channel.EpgItem();
        e2.time = "12:30–13:05";
        e2.title = channelName + " 正在直播";
        e2.playUrl = "";
        e2.isNow = true;
        list.add(e2);

        MainActivity.Channel.EpgItem e3 = new MainActivity.Channel.EpgItem();
        e3.time = "13:05–13:57";
        e3.title = "金牌调解";
        e3.playUrl = "";
        e3.isNow = false;
        list.add(e3);

        MainActivity.Channel.EpgItem e4 = new MainActivity.Channel.EpgItem();
        e4.time = "14:00–15:00";
        e4.title = "午后剧场";
        e4.playUrl = "";
        e4.isNow = false;
        list.add(e4);

        return list;
    }
}
