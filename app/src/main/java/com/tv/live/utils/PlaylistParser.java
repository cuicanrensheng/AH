package com.tv.live.utils;

import android.util.Log;
import com.tv.live.model.Channel;
import com.tv.live.model.Playlist;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {
    private static final String TAG = "PlaylistParser";

    public static Playlist parseM3u(String content, String playlistName) {
        List<Channel> channels = new ArrayList<>();
        String[] lines = content.split("\\r?\\n");
        String currentName = "";
        for (String line : lines) {
            if (line.startsWith("#EXTINF:")) {
                String[] parts = line.split(",", 2);
                currentName = parts.length > 1 ? parts[1] : "未知频道";
            } else if (!line.startsWith("#") && !line.trim().isEmpty()) {
                channels.add(new Channel(System.currentTimeMillis(), currentName, line.trim()));
                currentName = "";
            }
        }
        return new Playlist(System.currentTimeMillis(), playlistName, "", channels);
    }

    public static Playlist parseTvBox(String jsonStr, String playlistName) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            JSONArray list = json.getJSONArray("list");
            List<Channel> channels = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                String name = item.getString("name");
                String url = item.getString("url");
                channels.add(new Channel(System.currentTimeMillis(), name, url));
            }
            return new Playlist(System.currentTimeMillis(), playlistName, "", channels);
        } catch (Exception e) {
            Log.e(TAG, "TVBox解析失败: " + e.getMessage());
            return null;
        }
    }

    public static Playlist parseXml(String xmlContent, String playlistName) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xmlContent));
            List<Channel> channels = new ArrayList<>();
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.getName().equals("channel")) {
                    String name = parser.getAttributeValue(null, "name");
                    String url = parser.getAttributeValue(null, "url");
                    if (name != null && url != null) {
                        channels.add(new Channel(System.currentTimeMillis(), name, url));
                    }
                }
                eventType = parser.next();
            }
            return new Playlist(System.currentTimeMillis(), playlistName, "", channels);
        } catch (Exception e) {
            Log.e(TAG, "XML解析失败: " + e.getMessage());
            return null;
        }
    }

    public static void parseFromUrl(String url, String playlistName, Callback callback) {
        new Thread(() -> {
            try {
                String content = new URL(url).openStream().toString();
                Playlist playlist;
                if (url.endsWith(".m3u") || url.endsWith(".m3u8")) {
                    playlist = parseM3u(content, playlistName);
                } else if (url.endsWith(".json")) {
                    playlist = parseTvBox(content, playlistName);
                } else if (url.endsWith(".xml")) {
                    playlist = parseXml(content, playlistName);
                } else {
                    playlist = parseM3u(content, playlistName);
                }
                callback.onResult(playlist);
            } catch (Exception e) {
                Log.e(TAG, "从URL解析失败: " + e.getMessage());
                callback.onResult(null);
            }
        }).start();
    }

    public interface Callback {
        void onResult(Playlist playlist);
    }
}
