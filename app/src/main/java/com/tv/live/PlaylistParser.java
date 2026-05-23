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

    // 原有方法：返回 List<List<String>> （兼容旧代码）
    public static List<List<String>> parseFromUrl(String m3uUrl) throws IOException {
        List<List<String>> channelList = new ArrayList<>();
        URL url = new URL(m3uUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String line;
        List<String> currentChannelUrls = null;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTINF")) {
                currentChannelUrls = new ArrayList<>();
                channelList.add(currentChannelUrls);
            } else if (line.startsWith("http")
                    && !line.contains(".jpg")
                    && !line.contains(".png")
                    && !line.contains(".mp4")) {
                if (currentChannelUrls != null) {
                    currentChannelUrls.add(line);
                }
            }
        }
        reader.close();
        connection.disconnect();
        Log.d(TAG, "解析完成，频道总数：" + channelList.size());
        return channelList;
    }

    // 【新增】解析真实频道名称 + 多线路（适配你 MainActivity.Channel）
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
        List<String> currentUrls = null;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            // 读取 #EXTINF 后的频道名称
            if (line.startsWith("#EXTINF")) {
                int commaIndex = line.lastIndexOf(',');
                if (commaIndex != -1) {
                    currentName = line.substring(commaIndex + 1).trim();
                }
                // 新建频道线路列表
                currentUrls = new ArrayList<>();
            }
            // 读取直播地址
            else if (line.startsWith("http")
                    && !line.contains(".jpg")
                    && !line.contains(".png")
                    && !line.contains(".mp4")) {
                if (currentUrls != null) {
                    currentUrls.add(line);
                }
            }
            // 遇到下一个频道，保存上一个
            else if (line.startsWith("#EXTINF") && currentUrls != null && !currentUrls.isEmpty()) {
                channelList.add(new MainActivity.Channel(currentName, new ArrayList<>(currentUrls)));
                currentUrls.clear();
            }
        }
        // 保存最后一个频道
        if (currentUrls != null && !currentUrls.isEmpty()) {
            channelList.add(new MainActivity.Channel(currentName, new ArrayList<>(currentUrls)));
        }

        reader.close();
        connection.disconnect();
        Log.d(TAG, "解析完成，真实名称频道总数：" + channelList.size());
        return channelList;
    }
}
