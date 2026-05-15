package com.tv.live;
import com.tv.live.model.Channel;
import com.tv.live.model.Playlist;
import com.tv.live.utils.PlaylistManager;
import com.tv.live.utils.WebServer;

import android.os.Bundle;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.tv.live.utils.PlaylistManager;
import com.tv.live.utils.WebServer;

public class MainActivity extends AppCompatActivity {
    private GestureDetector gestureDetector;
    private WebServer webServer;
    private int currentChannelIndex = 0;
    private boolean isFullscreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化数据管理器
        PlaylistManager.init(this);

        // 初始化手势识别
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
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

        // 启动后台Web服务
        webServer = new WebServer(this);
        try {
            webServer.start();
            Toast.makeText(this, "后台服务已启动：http://本机IP:10481", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "服务启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
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

    // 核心功能方法
    private void nextChannel() {
        java.util.List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
        if (currentChannelIndex < channels.size() - 1) {
            currentChannelIndex++;
            playChannel(channels.get(currentChannelIndex));
            Toast.makeText(this, "下一台：" + channels.get(currentChannelIndex).getName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void previousChannel() {
        java.util.List<Channel> channels = PlaylistManager.getCurrentPlaylistChannels();
        if (currentChannelIndex > 0) {
            currentChannelIndex--;
            playChannel(channels.get(currentChannelIndex));
            Toast.makeText(this, "上一台：" + channels.get(currentChannelIndex).getName(), Toast.LENGTH_SHORT).show();
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

    private void playChannel(Channel channel) {
        // 此处替换为你的播放器播放逻辑
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webServer.stop();
    }
}
