package com.tv.live;

import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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
    private GestureDetector gestureDetector;
    private int currentChannelIndex = 0;

    public static MainActivity instance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;

        // 初始化播放器
        playerView = findViewById(R.id.playerView);
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);

        // 初始化手势识别（触屏操作核心）
        initGestureDetector();

        // 延迟初始化其他耗时操作，不卡主界面
        new Handler().postDelayed(() -> {
            // 初始化数据管理器
            PlaylistManager.init(MainActivity.this);
            // 加载并播放第一个频道
            loadFirstChannel();
        }, 300);
    }

    // 初始化手势识别（实现所有触屏操作）
    private void initGestureDetector() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            // 1. 上下滑动：切换频道
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float deltaX = e2.getX() - e1.getX();
                float deltaY = e2.getY() - e1.getY();

                // 上下滑动：频道切换
                if (Math.abs(deltaY) > Math.abs(deltaX) && Math.abs(deltaY) > 100) {
                    if (deltaY < 0) {
                        // 上滑：下一个频道
                        nextChannel();
                    } else {
                        // 下滑：上一个频道
                        previousChannel();
                    }
                    return true;
                }
                return super.onFling(e1, e2, velocityX, velocityY);
            }

            // 2. 单击屏幕：OK键（确认/暂停播放）
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (exoPlayer != null) {
                    if (exoPlayer.isPlaying()) {
                        exoPlayer.pause();
                    } else {
                        exoPlayer.play();
                    }
                }
                return true;
            }

            // 3. 双击屏幕：菜单/帮助键（打开设置页）
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                openSettingsPage();
                return true;
            }

            // 4. 长按屏幕：长按OK键（打开设置页）
            @Override
            public void onLongPress(MotionEvent e) {
                openSettingsPage();
            }
        });
    }

    // 加载并播放第一个频道
    private void loadFirstChannel() {
        new Thread(() -> {
            try {
                Thread.sleep(1000); // 给直播源解析留1秒时间
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            runOnUiThread(() -> {
                List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
                if (channels != null && !channels.isEmpty()) {
                    playChannel(channels.get(0));
                } else {
                    Toast.makeText(MainActivity.this, "直播源加载失败，请检查网络", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    // 核心播放方法
    public void playChannel(Channel channel) {
        runOnUiThread(() -> {
            if (channel == null || channel.getUrl() == null || channel.getUrl().isEmpty()) {
                Toast.makeText(MainActivity.this, "无效的直播地址", Toast.LENGTH_SHORT).show();
                return;
            }

            MediaItem mediaItem = MediaItem.fromUri(channel.getUrl());
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.play();
            Toast.makeText(MainActivity.this, "正在播放：" + channel.getName(), Toast.LENGTH_SHORT).show();
        });
    }

    // 下一个频道
    private void nextChannel() {
        List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
        if (currentChannelIndex < channels.size() - 1) {
            currentChannelIndex++;
            playChannel(channels.get(currentChannelIndex));
        }
    }

    // 上一个频道
    private void previousChannel() {
        List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
        if (currentChannelIndex > 0) {
            currentChannelIndex--;
            playChannel(channels.get(currentChannelIndex));
        }
    }

    // 打开设置页面
    private void openSettingsPage() {
        startActivity(new android.content.Intent(this, SettingsActivity.class));
    }

    // 触摸事件分发
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    // 遥控器按键处理（实现所有遥控器操作）
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            // 上下方向键：切换频道
            case KeyEvent.KEYCODE_DPAD_UP:
                previousChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                nextChannel();
                return true;

            // OK键：确认/暂停播放
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (exoPlayer != null) {
                    if (exoPlayer.isPlaying()) {
                        exoPlayer.pause();
                    } else {
                        exoPlayer.play();
                    }
                }
                return true;

            // 菜单/帮助键：打开设置页
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_HELP:
                openSettingsPage();
                return true;

            // 数字键：直接切换频道
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                int num = keyCode - KeyEvent.KEYCODE_0;
                List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
                if (num >= 0 && num < channels.size()) {
                    currentChannelIndex = num;
                    playChannel(channels.get(num));
                }
                return true;

            // 返回键
            case KeyEvent.KEYCODE_BACK:
                onBackPressed();
                return true;

            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放播放器资源
        if (exoPlayer != null) {
            exoPlayer.release();
        }
    }

    // 点击屏幕打开设置（备用）
    public void openChannelList(View v) {
        openSettingsPage();
    }
}
