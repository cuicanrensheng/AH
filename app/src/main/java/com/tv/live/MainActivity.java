package com.tv.live;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.model.Channel;
import com.tv.live.utils.PlaylistManager;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer exoPlayer;
    private int currentChannelIndex = 0;
    public static MainActivity instance;

    private View touchCover;
    private long lastTapTime = 0;
    private float downY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;

        // 电视盒子必须获取焦点，遥控器才有用
        findViewById(android.R.id.content).requestFocus();

        playerView = findViewById(R.id.playerView);
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);

        // 给最外层布局加触摸监听，解决点击没反应
        FrameLayout root = findViewById(R.id.rootLayout);
        root.setOnTouchListener((v, event) -> {
            handleTouch(event);
            return true;
        });

        new Handler().postDelayed(() -> {
            PlaylistManager.init(MainActivity.this);
            loadFirstChannel();
        }, 300);
    }

    // 统一触摸处理：手机端完美响应
    private void handleTouch(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                long now = System.currentTimeMillis();
                // 双击打开设置
                if (now - lastTapTime < 300) {
                    openSettingsPage();
                }
                lastTapTime = now;
                break;

            case MotionEvent.ACTION_UP:
                float dy = event.getY() - downY;
                // 滑动切换频道
                if (Math.abs(dy) > 80) {
                    if (dy < 0) nextChannel();
                    else previousChannel();
                } else {
                    // 单击暂停/播放
                    if (exoPlayer.isPlaying()) exoPlayer.pause();
                    else exoPlayer.play();
                }
                break;
        }
    }

    private void loadFirstChannel() {
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            runOnUiThread(() -> {
                List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
                if (channels != null && !channels.isEmpty()) {
                    currentChannelIndex = 0;
                    playChannel(channels.get(0));
                } else {
                    Toast.makeText(MainActivity.this, "直播源加载失败", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    public void playChannel(Channel channel) {
        runOnUiThread(() -> {
            if (channel == null || channel.getUrl() == null || channel.getUrl().isEmpty()) {
                Toast.makeText(this, "无效地址", Toast.LENGTH_SHORT).show();
                return;
            }
            MediaItem mediaItem = MediaItem.fromUri(channel.getUrl());
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.play();
            Toast.makeText(this, "正在播放：" + channel.getName(), Toast.LENGTH_SHORT).show();
        });
    }

    private void nextChannel() {
        List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
        if (currentChannelIndex < channels.size() - 1) {
            currentChannelIndex++;
            playChannel(channels.get(currentChannelIndex));
        }
    }

    private void previousChannel() {
        List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
        if (currentChannelIndex > 0) {
            currentChannelIndex--;
            playChannel(channels.get(currentChannelIndex));
        }
    }

    private void openSettingsPage() {
        startActivity(new android.content.Intent(this, SettingsActivity.class));
    }

    // 遥控器完美可用
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                previousChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                nextChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (exoPlayer.isPlaying()) exoPlayer.pause();
                else exoPlayer.play();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_HELP:
                openSettingsPage();
                return true;
            case KeyEvent.KEYCODE_0: case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3: case KeyEvent.KEYCODE_4: case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6: case KeyEvent.KEYCODE_7: case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                int num = keyCode - KeyEvent.KEYCODE_0;
                List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
                if (num >= 0 && num < channels.size()) {
                    currentChannelIndex = num;
                    playChannel(channels.get(num));
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                onBackPressed();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exoPlayer != null) exoPlayer.release();
    }

    public void openChannelList(View v) {
        openSettingsPage();
    }
}
