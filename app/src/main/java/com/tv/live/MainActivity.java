package com.tv.live;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.model.Channel;
import com.tv.live.utils.PlaylistManager;
import com.tv.live.utils.WebServer;
import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer exoPlayer;
    private GestureDetector gestureDetector;
    private WebServer webServer;
    private int currentChannelIndex = 0;
    private boolean isFullscreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 绑定你刚创建的布局（含PlayerView）
        setContentView(R.layout.activity_main);

        // 初始化播放器
        playerView = findViewById(R.id.playerView);
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);

        // 延迟初始化其他耗时操作，不卡主界面
        new Handler().postDelayed(() -> {
            // 初始化数据管理器
            PlaylistManager.init(MainActivity.this);
            // 初始化手势识别
            initGestureDetector();
            // 启动后台Web服务
            initWebServer();
            // 加载并播放第一个频道
            loadFirstChannel();
        }, 300);
    }

    private void initGestureDetector() {
        gestureDetector = new GestureDetector(MainActivity.this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float deltaX = e2.getX() - e1.getX();
                float deltaY = e2.getY() - e1.getY();
                if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > 100) {
                    if (deltaX > 0) previousLine();
                    else nextLine();
                    return true;
                } else if (Math.abs(deltaY) > Math.abs(deltaX) && Math.abs(deltaY) > 100) {
                    if (deltaY > 0) previousChannel();
                    else nextChannel();
                    return true;
                }
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                toggleFavorite();
            }
        });

        gestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) { return false; }
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                toggleFullscreen();
                return true;
            }
            @Override
            public boolean onDoubleTapEvent(MotionEvent e) { return false; }
        });
    }

    private void initWebServer() {
        webServer = new WebServer(MainActivity.this);
        try {
            webServer.start(10481);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "后台服务已启动：http://本机IP:10481", Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "服务启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private void loadFirstChannel() {
        java.util.List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
        if (!channels.isEmpty()) {
            playChannel(channels.get(0));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestureDetector != null) {
            return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
        }
        return super.onTouchEvent(event);
    }

    // 遥控器按键处理
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                nextChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                previousChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                previousLine();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                nextLine();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_ENTER:
                toggleFullscreen();
                return true;
            case KeyEvent.KEYCODE_BACK:
                onBackPressed();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // 核心播放方法
    private void playChannel(Channel channel) {
        runOnUiThread(() -> {
            MediaItem mediaItem = MediaItem.fromUri(channel.getUrl());
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.play();
            Toast.makeText(MainActivity.this, "正在播放：" + channel.getName(), Toast.LENGTH_SHORT).show();
        });
    }

    // 频道切换方法
    private void nextChannel() {
        java.util.List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
        if (currentChannelIndex < channels.size() - 1) {
            currentChannelIndex++;
            playChannel(channels.get(currentChannelIndex));
        }
    }

    private void previousChannel() {
        java.util.List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
        if (currentChannelIndex > 0) {
            currentChannelIndex--;
            playChannel(channels.get(currentChannelIndex));
        }
    }

    private void nextLine() {
        Toast.makeText(this, "切换到下一条线路", Toast.LENGTH_SHORT).show();
    }

    private void previousLine() {
        Toast.makeText(this, "切换到上一条线路", Toast.LENGTH_SHORT).show();
    }

    private void toggleFavorite() {
        java.util.List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
        if (!channels.isEmpty()) {
            Channel channel = channels.get(currentChannelIndex);
            PlaylistManager.toggleFavorite(channel);
            Toast.makeText(this, channel.isFavorite() ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        if (isFullscreen) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放播放器资源
        if (exoPlayer != null) {
            exoPlayer.release();
        }
        // 关闭Web服务
        if (webServer != null) {
            webServer.stop();
        }
    }
}
