package com.tv.live;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class HuyaParser {

    public interface OnParseResultListener {
        void onSuccess(String playUrl, int type);
        void onError(String msg);
    }

    public void parse(String roomId, OnParseResultListener listener) {
        new Thread(() -> {
            try {
                String api = "https://mp-api.huya.com/mobile/room/playInfo?roomId=" + roomId;
                HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                InputStream in = conn.getInputStream();
                String json = new Scanner(in, "UTF-8").useDelimiter("\\A").next();
                in.close();
                conn.disconnect();

                JSONObject obj = new JSONObject(json);
                String url = obj.getJSONObject("data").getString("url");

                new Handler(Looper.getMainLooper()).post(() -> {
                    listener.onSuccess(url, 1);
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    listener.onError(e.getMessage());
                });
            }
        }).start();
    }
}
