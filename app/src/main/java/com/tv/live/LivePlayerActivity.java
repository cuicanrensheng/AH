package com.tv.live;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class LivePlayerActivity extends AppCompatActivity {

    private PlayerView playerView;
    private int currentChannel = 1;
    private String currentPlayUrl = "";

    public static List<ChannelItem> channelList = new ArrayList<>();
    private HashMap<Integer, String> channelMap = new HashMap<>();

    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;
    private float touchStartY = 0;
    private static final float SLIDE_THRESHOLD = 80;

    public static class ChannelItem {
        public int number;
        public String url;

        public ChannelItem(int number, String url) {
            this.number = number;
            this.url = url;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_player);
        playerView = findViewById(R.id.player_view);

        buildChannelMap();

        currentChannel = getIntent().getIntExtra("channel_num", 1);
        currentPlayUrl = getChannelUrl(currentChannel);

        TVPlayerManager manager = TVPlayerManager.getInstance(this);
        manager.attachPlayerView(playerView);
        manager.setCurrentChannelNumber(currentChannel);
        manager.play(currentPlayUrl);
    }

    private void buildChannelMap() {
        channelMap.clear();
        for (ChannelItem item : channelList) {
            channelMap.put(item.number, item.url);
        }
    }

    private String getChannelUrl(int channelNumber) {
        if (channelMap.containsKey(channelNumber)) {
            return channelMap.get(channelNumber);
        }
        currentChannel = 1;
        return channelMap.getOrDefault(1, "");
    }

    @Override
    protected void onResume() {
        super.onResume();
        TVPlayerManager.getInstance(this).onForeground();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TVPlayerManager.getInstance(this).onBackground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TVPlayerManager.getInstance(this).release();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            lastChannelChangeTime = now;
            changeChannel(false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            lastChannelChangeTime = now;
            changeChannel(true);
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
                float offset = event.getY() - touchStartY;
                long now = System.currentTimeMillis();

                if (Math.abs(offset) > SLIDE_THRESHOLD && now - lastChannelChangeTime >= CHANNEL_COOLDOWN) {
                    lastChannelChangeTime = now;
                    if (offset < 0) {
                        changeChannel(true);
                    } else {
                        changeChannel(false);
                    }
                }
                break;
        }
        return true;
    }

    private void changeChannel(boolean next) {
        if (next) {
            currentChannel++;
        } else {
            currentChannel--;
            if (currentChannel < 1) {
                currentChannel = 1;
                return;
            }
        }

        currentPlayUrl = getChannelUrl(currentChannel);

        TVPlayerManager manager = TVPlayerManager.getInstance(this);
        manager.setCurrentChannelNumber(currentChannel);
        manager.play(currentPlayUrl);
    }
}
