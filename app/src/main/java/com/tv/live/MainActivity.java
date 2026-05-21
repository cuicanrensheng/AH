package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // 你的真实直播源 + EPG
    private static final String M3U_URL = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
    private static final String EPG_URL = "http://epg.51zmt.top:8000/e.xml.gz";

    // 设置
    private boolean isReverse;
    private boolean enableEpg;
    private int sourceIndex;

    // 频道数据（自动从m3u读取）
    private final List<String> channelNames = new ArrayList<>();
    private final List<String> channelUrls = new ArrayList<>();
    private int currentIndex = 0;

    private GestureDetector gestureDetector;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 读取设置
        loadSettings();

        // 初始化手势
        initGesture();

        // 加载真实直播源
        loadM3uLiveSource(M3U_URL);
    }

    // 读取设置（反转、EPG、源序号）
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("setting", MODE_PRIVATE);
        isReverse = sp.getBoolean("reverse", false);
        enableEpg = sp.getBoolean("epg", true);
        sourceIndex = sp.getInt("source", 0);
    }

    // 加载网络 M3U 直播源（你真实的源）
    private void loadM3uLiveSource(String url) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));

                String line;
                String name = null;
                channelNames.clear();
                channelUrls.clear();

                while ((line = br.readLine()) != null) {
                    if (line.startsWith("#EXTINF")) {
                        int last = line.lastIndexOf(",");
                        if (last > 0) name = line.substring(last + 1);
                    } else if (line.startsWith("http")) {
                        if (name != null) {
                            channelNames.add(name);
                            channelUrls.add(line);
                            name = null;
                        }
                    }
                }
                br.close();
                conn.disconnect();

                handler.post(() -> {
                    if (!channelNames.isEmpty()) playChannel(0);
                    Toast.makeText(this, "加载完成：" + channelNames.size() + "个频道", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this, "加载源失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // 播放指定频道
    private void playChannel(int index) {
        if (index < 0 || index >= channelUrls.size()) return;
        currentIndex = index;

        String url = channelUrls.get(index);
        String name = channelNames.get(index);

        // 这里替换成你的播放器播放
        // player.setUrl(url);
        // player.start();

        Toast.makeText(this, "正在播放：" + name, Toast.LENGTH_SHORT).show();
    }

    // 上一频道（带反转）
    private void prevChannel() {
        if (isReverse) {
            nextChannel();
            return;
        }
        currentIndex--;
        if (currentIndex < 0) currentIndex = channelUrls.size() - 1;
        playChannel(currentIndex);
    }

    // 下一频道（带反转）
    private void nextChannel() {
        if (isReverse) {
            prevChannel();
            return;
        }
        currentIndex++;
        if (currentIndex >= channelUrls.size()) currentIndex = 0;
        playChannel(currentIndex);
    }

    // 显示频道列表（直接读取源里的真实频道）
    private void showChannelList() {
        if (channelNames.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("频道列表")
                .setItems(channelNames.toArray(new String[0]), (dialog, which) -> playChannel(which))
                .show();
    }

    // 打开设置
    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ========== 电视遥控器 ==========
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
            case KeyEvent.KEYCODE_ENTER:
                showChannelList();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_HELP:
                openSettings();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ========== 手机手势 ==========
    private void initGesture() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showChannelList();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                openSettings();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > 80) {
                    if (dy < 0) nextChannel();
                    else prevChannel();
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    // 返回时刷新设置
    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
    }
}
