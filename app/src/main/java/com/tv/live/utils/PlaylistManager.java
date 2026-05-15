package com.tv.live.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tv.live.model.Channel;
import com.tv.live.model.Playlist;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class PlaylistManager {
    private static final String SP_NAME = "IPTV_DATA";
    private static final String KEY_PLAYLISTS = "playlists";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_CURRENT_PLAYLIST = "current_playlist";
    private static Context context;
    private static final Gson gson = new Gson();

    public static void init(Context ctx) {
        context = ctx.getApplicationContext();
    }

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
