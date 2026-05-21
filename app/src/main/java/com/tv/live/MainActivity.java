package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.gesture.GestureDetectorCompat;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    public static final String URL_SOURCE_1 = "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u";
    public static final String URL_SOURCE_2 = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
    public static final String EPG_URL = "http://epg.51zmt.top:8000/e.xml.gz";

    private PlayerView playerView;
    private ExoPlayer exoPlayer;
    private final List<Channel> channels = new ArrayList<>();
    private int currentPos = 0;
    private boolean reverse;
    private boolean epgEnable;
    private int sourceType;

    private GestureDetectorCompat gesture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        playerView = findViewById(R.id.exo_player_view);

        loadConfig();
        initPlayer();
        gesture = new GestureDetectorCompat(this, new GestureListener());
        loadList();
    }

    private void loadConfig() {
        SharedPreferences sp = getSharedPreferences("setting", MODE_PRIVATE);
        reverse = sp.getBoolean("reverse", false);
        epgEnable = sp.getBoolean("epg", true);
        sourceType = sp.getInt("source", 0);
    }

    private void initPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        exoPlayer.setPlayWhenReady(true);
    }

    private void loadList() {
        new Thread(() -> {
            try {
                String url = sourceType == 0 ? URL_SOURCE_1 : URL_SOURCE_2;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                String name = null;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("#EXTINF")) {
                        name = line.replaceAll(".*,", "").trim();
                    } else if (line.startsWith("http") && name != null) {
                        channels.add(new Channel(name, line.trim()));
                        name = null;
                    }
                }
                br.close();
                conn.disconnect();

                if (reverse) Collections.reverse(channels);

                runOnUiThread(() -> {
                    if (!channels.isEmpty()) play(0);
                    else Toast.makeText(this, "无频道", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void play(int pos) {
        if (channels.isEmpty()) return;
        currentPos = pos;
        Channel c = channels.get(pos);
        MediaItem item = MediaItem.fromUri(Uri.parse(c.url));
        exoPlayer.setMediaItem(item);
        exoPlayer.prepare();
        exoPlayer.play();
        Toast.makeText(this, c.name, Toast.LENGTH_SHORT).show();
    }

    private void next() {
        if (channels.isEmpty()) return;
        currentPos = (currentPos + 1) % channels.size();
        play(currentPos);
    }

    private void prev() {
        if (channels.isEmpty()) return;
        currentPos = (currentPos - 1 + channels.size()) % channels.size();
        play(currentPos);
    }

    // ========== 电视遥控器 ==========
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) prev();
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) next();
        else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
            startActivity(new Intent(this, ChannelListActivity.class));
        else if (keyCode == KeyEvent.KEYCODE_HELP || keyCode == KeyEvent.KEYCODE_MENU)
            startActivity(new Intent(this, SettingsActivity.class));
        return super.onKeyDown(keyCode, event);
    }

    // ========== 手机触摸 ==========
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gesture.onTouchEvent(event);
        return true;
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            startActivity(new Intent(MainActivity.this, ChannelListActivity.class));
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
            if (e2.getY() < e1.getY() - 100) next();
            else if (e2.getY() > e1.getY() + 100) prev();
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exoPlayer != null) exoPlayer.release();
    }

    public static class Channel {
        public String name;
        public String url;
        public Channel(String n, String u) { name = n; url = u; }
    }
}
