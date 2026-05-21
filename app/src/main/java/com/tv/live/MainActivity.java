package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // 单例实例
    public static MainActivity mInstance;
    // 当前播放频道下标，用于列表定位
    public int currentChannelIndex = 0;

    // 频道实体类
    public static class Channel {
        public String name;
        public String url;

        public Channel(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

     // 切换画面延时参数
    private static final long SWITCH_DELAY_TIME = 2000;
    private boolean isSwitching = false;
    
    // 直播源 & EPG地址
    private static final String LIVE_M3U = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
    private static final String EPG_URL = "http://epg.51zmt.top:8000/e.xml.gz";

    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private View epgLayout;
    private TextView tvEpgInfo;

    // 设置项
    private boolean isReverse;
    private boolean openEpg;

    // 频道集合
    public final List<Channel> channels = new ArrayList<>();
    private int curIndex = 0;

    private GestureDetector gestureDetector;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mInstance = this;
        // 开启全屏隐藏状态栏导航栏
        setFullscreen();

        initView();
        initPlayer();
        readConfig();
        initGesture();
        loadM3USource();
    }
    public void play(int index) {
    if (index < 0 || index >= channels.size() || isSwitching) {
        return;
    }
        
    // 同一个频道不重复切换
    if (index == currentChannelIndex) {
        return;
    }

    isSwitching = true;
    curIndex = index;
    currentChannelIndex = index;

    // 定格上一个频道画面，不立即黑屏
    exoPlayer.setPlayWhenReady(false);

    // 延迟2秒后再加载新频道
    mHandler.postDelayed(() -> {
        String url = channels.get(index).url;
        String name = channels.get(index).name;

        MediaItem item = MediaItem.fromUri(url);
        exoPlayer.setMediaItem(item);
        exoPlayer.prepare();
        exoPlayer.play();

        tvEpgInfo.setText("正在播放：" + name + "\nEPG数据源：" + EPG_URL);
        isSwitching = false;
    }, SWITCH_DELAY_TIME);
}


    // 全屏沉浸式隐藏状态栏
    private void setFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    private void initView() {
        playerView = findViewById(R.id.player_view);
        epgLayout = findViewById(R.id.epg_layout);
        tvEpgInfo = findViewById(R.id.tv_epg_info);
    }

    private void initPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        playerView.setUseController(false);
    }

    // 读取设置
    private void readConfig() {
        SharedPreferences sp = getSharedPreferences("setting", MODE_PRIVATE);
        isReverse = sp.getBoolean("reverse", false);
        openEpg = sp.getBoolean("epg", true);
        epgLayout.setVisibility(openEpg ? View.VISIBLE : View.GONE);
    }

    // 网络解析M3U
    private void loadM3USource() {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(LIVE_M3U).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                String nameTemp = null;
                channels.clear();

                while ((line = br.readLine()) != null) {
                    if (line.startsWith("#EXTINF")) {
                        int pos = line.lastIndexOf(",");
                        if (pos > 0) nameTemp = line.substring(pos + 1);
                    } else if (line.startsWith("http")) {
                        if (nameTemp != null) {
                            channels.add(new Channel(nameTemp, line));
                            nameTemp = null;
                        }
                    }
                }
                br.close();
                conn.disconnect();

                mHandler.post(() -> {
                    if (!channels.isEmpty()) play(curIndex);
                    Toast.makeText(this, "加载完成：" + channels.size() + "个频道", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mHandler.post(() -> Toast.makeText(this, "直播源加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // 播放频道，同步更新当前下标
    public void play(int index) {
    if (index < 0 || index >= channels.size()) return;
    // 同步更新播放下标
    curIndex = index;
    currentChannelIndex = index;

    String url = channels.get(index).url;
    String name = channels.get(index).name;

    MediaItem item = MediaItem.fromUri(url);
    exoPlayer.setMediaItem(item);
    exoPlayer.prepare();
    exoPlayer.play();

    tvEpgInfo.setText("正在播放：" + name + "\nEPG数据源：" + EPG_URL);
}


    // 上一频道
    private void preChannel() {
        if (isReverse) {
            nextChannel();
            return;
        }
        curIndex--;
        if (curIndex < 0) curIndex = channels.size() - 1;
        play(curIndex);
    }

    // 下一频道
    private void nextChannel() {
        if (isReverse) {
            preChannel();
            return;
        }
        curIndex++;
        if (curIndex >= channels.size()) curIndex = 0;
        play(curIndex);
    }

    // 弹出频道列表
    private void showChannelList() {
        if (channels.isEmpty()) return;
        String[] names = new String[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            names[i] = channels.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("频道列表")
                .setItems(names, (d, w) -> play(w))
                .show();
    }

    // 打开设置
    private void goSetting() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // 手势初始化
    private void initGesture() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showChannelList();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                goSetting();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > 80) {
                    if (dy < 0) nextChannel();
                    else preChannel();
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

    // 遥控器按键
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                preChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                nextChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showChannelList();
                return true;
            case KeyEvent.KEYCODE_MENU:
                goSetting();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        readConfig();
        // 返回前台重新恢复全屏
        setFullscreen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
        mInstance = null;
    }
}
