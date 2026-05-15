package com.tv.live.utils;

import android.content.Context;
import com.tv.live.model.Channel;
import com.tv.live.model.Playlist;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class M3uLoader {
    private static final String M3U_URL = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";

    public static void loadDefaultPlaylist(Context context) {
        new Thread(() -> {
            try {
                URL url = new URL(M3U_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                String channelName = "";
                List<Channel> channelList = new ArrayList<>();
                int id = 1;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#EXTINF")) {
                        int comma = line.lastIndexOf(",");
                        if (comma != -1) {
                            channelName = line.substring(comma + 1).trim();
                        }
                    } else if (line.startsWith("http")) {
                        channelList.add(new Channel(id++, channelName, line.trim(), false));
                    }
                }
                reader.close();
                conn.disconnect();

                if (!channelList.isEmpty()) {
                    PlaylistManager.init(context);
                    boolean hasDefault = false;
                    for (Playlist p : PlaylistManager.getAllPlaylists()) {
                        if (p.getId() == 1000) {
                            hasDefault = true;
                            break;
                        }
                    }
                    if (!hasDefault) {
                        Playlist playlist = new Playlist();
                        playlist.setId(1000);
                        playlist.setName("默认频道");
                        playlist.setChannels(channelList);
                        PlaylistManager.addPlaylist(playlist);
                    }

                    context.getSharedPreferences("IPTV_DATA", Context.MODE_PRIVATE)
                            .edit().putLong("current_playlist", 1000).apply();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
