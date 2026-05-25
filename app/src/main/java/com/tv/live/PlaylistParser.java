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
                    ch.epgList = new ArrayList<>(); // 空列表，EpgManager自动填充真实节目单
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
            ch.epgList = new ArrayList<>();
            channelList.add(ch);
        }

        reader.close();
        connection.disconnect();
        return channelList;
    }
}
