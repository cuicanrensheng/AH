package com.tv.live.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tv.live.model.Channel;
import com.tv.live.model.Playlist;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistManager {
    private static final String SP_NAME = "IPTV_DATA";
    private static final String KEY_PLAYLISTS = "playlists";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_CURRENT_PLAYLIST = "current_playlist";
    private static Context context;
    private static final Gson gson = new Gson();

    // 你指定的直播源地址
    private static final String M3U_URL = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";

    public static void init(Context ctx) {
        context = ctx.getApplicationContext();
        // 启动时自动加载直播源
        loadRemotePlaylist();
    }

    // 从网络加载M3U并自动创建默认播放列表
    private static void loadRemotePlaylist() {
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

                // 保存为默认播放列表
                if (!channelList.isEmpty()) {
                    Playlist playlist = new Playlist();
                    playlist.setId(1000);
                    playlist.setName("默认频道");
                    playlist.setChannels(channelList);

                    List<Playlist> playlists = getAllPlaylists();
                    boolean hasDefault = false;
                    for (Playlist p : playlists) {
                        if (p.getId() == 1000) {
                            hasDefault = true;
                            break;
                        }
                    }
                    if (!hasDefault) {
                        addPlaylist(playlist);
                    }

                    // 设置为当前播放列表
                    SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                    sp.edit().putLong(KEY_CURRENT_PLAYLIST, 1000).apply();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // 以下是你原来的所有方法，完全保留不动
    public static List<Playlist> getAllPlaylists() {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_PLAYLISTS, "[]");
        Type type = new TypeToken<List<Playlist>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public static void addPlaylist(Playlist playlist) {
        List<Playlist> playlists = new ArrayList<>(getAllPlaylists());
        playlists.add(playlist);
        savePlaylists(playlists);
    }

    public static void deletePlaylist(long id) {
        List<Playlist> playlists = new ArrayList<>(getAllPlaylists());
        playlists.removeIf(p -> p.getId() == id);
        savePlaylists(playlists);
    }

    private static void savePlaylists(List<Playlist> playlists) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_PLAYLISTS, gson.toJson(playlists)).apply();
    }

    public static List<Channel> getCurrentPlaylistChannels() {
        List<Playlist> playlists = getAllPlaylists();
        long currentId = getCurrentPlaylistId();
        for (Playlist p : playlists) {
            if (p.getId() == currentId) {
                return p.getChannels();
            }
        }
        return new ArrayList<>();
    }

    private static long getCurrentPlaylistId() {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        return sp.getLong(KEY_CURRENT_PLAYLIST, -1);
    }

    public static void toggleFavorite(Channel channel) {
        List<Channel> favorites = new ArrayList<>(getFavorites());
        boolean isFavorite = false;
        for (Channel c : favorites) {
            if (c.getId() == channel.getId()) {
                favorites.remove(c);
                isFavorite = true;
                break;
            }
        }
        if (!isFavorite) {
            favorites.add(new Channel(channel.getId(), channel.getName(), channel.getUrl(), true));
        }
        saveFavorites(favorites);
    }

    public static List<Channel> getFavorites() {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_FAVORITES, "[]");
        Type type = new TypeToken<List<Channel>>() {}.getType();
        return gson.fromJson(json, type);
    }

    private static void saveFavorites(List<Channel> favorites) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_FAVORITES, gson.toJson(favorites)).apply();
    }

    public static String exportAllData() {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("playlists", getAllPlaylists());
        data.put("favorites", getFavorites());
        return gson.toJson(data);
    }
}
