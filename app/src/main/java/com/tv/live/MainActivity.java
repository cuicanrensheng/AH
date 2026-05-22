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
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public int currentChannelIndex = 0;

    // 播放器核心
    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private View epgLayout;
    private TextView tvEpgInfo;
    private View menuLayout;

    // 配置
    private boolean isReverse;
    private boolean openEpg;

    // 频道列表
    public final List<Channel> channels = new ArrayList<>();
    private int curIndex = 0;

    private GestureDetector gestureDetector;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // 直播源
    private static final String LIVE_M3U = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
    private static final String EPG_URL = "http://epg.51zmt.top:8000/e.xml.gz";

    // 频道实体
    public static class Channel {
        public String name;
        public String url;

        public Channel(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mInstance = this;

        setFullscreen();
        initView();
        initPlayer();
        readConfig();
        initGesture();
        loadM3USource();
    }

    private void initView() {
        playerView = findViewById(R.id.player_view);
        epgLayout = findViewById(R.id.epg_layout);
        tvEpgInfo = findViewById(R.id.tv_epg_info);
        menuLayout = findViewById(R.id.menu_layout);

        findViewById(R.id.btn_aspect_ratio).setOnClickListener(v -> showAspectRatioDialog());
    }

    // ====================== 播放器初始化 ======================
    private void initPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        playerView.setUseController(false);
    }

    // ====================== 核心播放方法（修复黑屏、无画面） ======================
    private void play(int curIndex) {
        if (channels == null || channels.isEmpty()) return;
        if (curIndex < 0 || curIndex >= channels.size()) return;

        this.curIndex = curIndex;
        currentChannelIndex = curIndex;

        Channel channel = channels.get(curIndex);
        String url = channel.url;
        String name = channel.name;

        // 显示频道名
        if (tvEpgInfo != null) {
            tvEpgInfo.setText(name);
        }

        // 播放逻辑（解决黑屏、不播放）
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            MediaItem item = MediaItem.fromUri(url);
            exoPlayer.setMediaItem(item);
            exoPlayer.prepare();
            exoPlayer.play();
        }
    }

    // ====================== 切台（上/下） ======================
    private void preChannel() {
        if (isReverse) {
            nextChannel();
            return;
        }
        curIndex--;
        if (curIndex < 0) curIndex = channels.size() - 1;
        play(curIndex);
    }

    private void nextChannel() {
        if (isReverse) {
            preChannel();
            return;
        }
        curIndex++;
        if (curIndex >= channels.size()) curIndex = 0;
        play(curIndex);
    }

    // ====================== 加载M3U ======================
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
                    if (!channels.isEmpty()) play(0);
                    Toast.makeText(this, "加载完成：" + channels.size() + "个频道", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                mHandler.post(() -> Toast.makeText(this, "直播源加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ====================== 手势、菜单、全屏 ======================
    private void initGesture() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleMenu();
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

    private void toggleMenu() {
        menuLayout.setVisibility(menuLayout.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

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

    private void readConfig() {
        SharedPreferences sp = getSharedPreferences("setting", MODE_PRIVATE);
        isReverse = sp.getBoolean("reverse", false);
        openEpg = sp.getBoolean("epg", true);
        epgLayout.setVisibility(openEpg ? View.VISIBLE : View.GONE);
    }

    private void goSetting() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ====================== 触摸、遥控器 ======================
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

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
            case KeyEvent.KEYCODE_MENU:
                toggleMenu();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ====================== 画面比例 ======================
    private void showAspectRatioDialog() {
        String[] items = {"默认", "16:9", "4:3", "填充", "原始/裁剪"};
        new AlertDialog.Builder(this)
                .setTitle("画面比例")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                            break;
                        case 1:
                            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_16_9);
                            break;
                        case 2:
                            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_4_3);
                            break;
                        case 3:
                            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                            break;
                        case 4:
                            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                            break;
                    }
                }).show();
    }

    // ====================== 生命周期 ======================
    @Override
    protected void onResume() {
        super.onResume();
        readConfig();
        setFullscreen();
        if (exoPlayer != null) exoPlayer.play();
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
