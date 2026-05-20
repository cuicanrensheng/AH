package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private List<String> channelUrls = new ArrayList<>();
    private List<String> channelNames = new ArrayList<>();
    private int currentIndex = 0;
    private boolean reverseChannel = false;
    private boolean epgEnable = true;
    private int sourceIndex = 0;
    private EpgView epgView;
    private boolean epgLoaded = false;

    private final String[] LIVE_SOURCES = {
            "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u",
            "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u"
    };
    private final String EPG_URL = "http://epg.51zmt.top:8000/e.xml.gz";

    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences sp = getSharedPreferences("tv_setting", MODE_PRIVATE);
        reverseChannel = sp.getBoolean("reverse", false);
        epgEnable = sp.getBoolean("epg", true);
        sourceIndex = sp.getInt("source", 0);

        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView = findViewById(R.id.player_view);
        playerView.setPlayer(exoPlayer);

        loadPlaylist(LIVE_SOURCES[sourceIndex]);

        gestureDetector = new GestureDetector(this, new GestureListener());

        // EPG底部悬浮栏
        epgView = new EpgView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = android.view.Gravity.BOTTOM;
        ((FrameLayout)findViewById(android.R.id.content)).addView(epgView, lp);
        epgView.setVisibility(View.GONE);

        if(epgEnable){
            EpgManager.getInstance().loadEpg(this, new EpgManager.OnEpgLoadListener() {
                @Override
                public void onLoadSuccess() {
                    epgLoaded = true;
                    updateEpgDisplay();
                }
                @Override
                public void onLoadFail() {}
            });
        }
    }

    private void loadPlaylist(String url) {
        PlaylistParser parser = new PlaylistParser();
        parser.parseWithName(url, new PlaylistParser.CallbackWithName() {
            @Override
            public void onSuccess(List<String> urls, List<String> names) {
                channelUrls = urls;
                channelNames = names;
                playChannel(0);
            }
        });
    }

    private void playChannel(int index) {
        if(channelUrls.isEmpty()) return;
        currentIndex = index % channelUrls.size();
        MediaItem mediaItem = MediaItem.fromUri(channelUrls.get(currentIndex));
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();
        updateEpgDisplay();
    }

    private void prevChannel() {
        int idx = reverseChannel ? currentIndex + 1 : currentIndex -1;
        playChannel(idx);
    }
    private void nextChannel() {
        int idx = reverseChannel ? currentIndex -1 : currentIndex +1;
        playChannel(idx);
    }

    private void updateEpgDisplay() {
        if(!epgEnable || !epgLoaded) return;
        String name = channelNames.get(currentIndex);
        String info = EpgManager.getInstance().getEpgInfo(name);
        epgView.setEpgText("当前节目：" + info);
        epgView.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                prevChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                nextChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                startActivity(new Intent(this, SettingActivity.class));
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event);
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(exoPlayer != null) {
            exoPlayer.release();
        }
    }
}
