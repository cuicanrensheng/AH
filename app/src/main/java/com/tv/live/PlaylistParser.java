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

    // 解析网络M3U文件，返回 频道-多线路 结构
    public static List<List<String>> parseFromUrl(String m3uUrl) throws IOException {
        List<List<String>> channelList = new ArrayList<>();
        URL url = new URL(m3uUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        // 模拟浏览器UA，防止被防盗链拦截
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String line;
        List<String> currentChannelUrls = null;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            // 遇到频道标记，新建一个频道
            if (line.startsWith("#EXTINF")) {
                currentChannelUrls = new ArrayList<>();
                channelList.add(currentChannelUrls);
            }
            // 抓取所有http直播地址，排除图片、静态文件
            else if (line.startsWith("http")
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
}
