package com.tv.live;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {
    private static final String TAG = "PlaylistParser";

    public static List<Channel> parse(String urlStr) {
        List<Channel> list = new ArrayList<>();
        HttpURLConnection conn = null;
        BufferedReader br = null;

        try {
            Log.i(TAG, "开始解析：" + urlStr);
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();

            // 加上超时，避免一直卡着
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.connect();

            int code = conn.getResponseCode();
            Log.i(TAG, "HTTP 响应码：" + code);
            if (code != 200) {
                Log.e(TAG, "请求失败，code=" + code);
                return list;
            }

            br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            String name = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF:")) {
                    int idx = line.lastIndexOf(",");
                    if (idx != -1) {
                        name = line.substring(idx + 1).trim();
                        Log.i(TAG, "频道：" + name);
                    }
                } else if (line.startsWith("http")) {
                    Log.i(TAG, "地址：" + line);
                    if (name != null) {
                        list.add(new Channel(name, line));
                        name = null;
                    }
                }
            }

            Log.i(TAG, "解析完成，频道总数：" + list.size());

        } catch (Exception e) {
            Log.e(TAG, "解析异常", e);
        } finally {
            try {
                if (br != null) br.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
        return list;
    }
}
