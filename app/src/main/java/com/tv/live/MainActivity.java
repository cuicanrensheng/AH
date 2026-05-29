package com.tv.live;
import android.util.Log;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public List<Channel> channelSourceList = new ArrayList<>();
    public int currentPlayIndex = 0;
    public int currentChannelIndex = 0;
    public boolean isPlayingPlayback = false;
    public int currentRatioIndex = 2;

    public ListView lvGroup;
    public ListView lvChannelList;
    public ListView lvDate;
    public ListView lvEpg;

    public TVPlayerManager mPlayerManager;
    private PlayerGestureHelper gestureHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;
        setContentView(R.layout.activity_main);

        PlayerView playerView = findViewById(R.id.player_view);
        lvGroup = findViewById(R.id.lv_group);
        lvChannelList = findViewById(R.id.lv_channel_list);
        lvDate = findViewById(R.id.lv_date);
        lvEpg = findViewById(R.id.lv_epg);

        // 手势
        gestureHelper = new PlayerGestureHelper(this, new PlayerGestureHelper.GestureCallback() {
            @Override public void onOk() { openChannelList(); }
            @Override public void onLongOk() { openSettings(); }
            @Override public void onMenu() { openSettings(); }
            @Override public void onPrevChannel() { playPrev(); }
            @Override public void onNextChannel() { playNext(); }
        });

        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        // 播放器初始化
        SharedPreferences sp = getSharedPreferences("play_config", Context.MODE_PRIVATE);
        currentRatioIndex = sp.getInt("play_ratio", 2);
        currentPlayIndex = sp.getInt("last_play_index", 0);

        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        switch (currentRatioIndex) {
            case 0: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT); break;
            case 1: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT); break;
            case 2: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM); break;
            case 3: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL); break;
        }

        mPlayerManager.setOnPlayStateListener(new TVPlayerManager.OnPlayStateListener() {
            @Override public void onIdle() {}
            @Override public void onBuffering() { Toast.makeText(MainActivity.this,"缓冲中...",Toast.LENGTH_SHORT).show(); }
            @Override public void onPlayReady() {}
            @Override public void onPlayEnd() { Toast.makeText(MainActivity.this,"播放结束，自动重试",Toast.LENGTH_SHORT).show(); }
            @Override public void onPlayError(String msg) { Toast.makeText(MainActivity.this,"播放异常："+msg,Toast.LENGTH_SHORT).show(); }
        });

        // 加载直播源和EPG
        loadLiveAndEpg();
        initGesture();
        initChannelList();
        initListViewClick();
    }

    // 加载直播源和EPG
    private void loadLiveAndEpg() {
    new Thread(() -> {
        try {
            Log.i("MainActivity", "加载直播源...");
            List<Channel> channels = PlaylistParser.parse(UrlConfig.LIVE_URL);
            runOnUiThread(() -> {
                Log.i("MainActivity", "解析到频道数：" + (channels == null ? "null" : channels.size()));
                if (channels != null && !channels.isEmpty()) {
                    channelSourceList.clear();
                    channelSourceList.addAll(channels);
                    Toast.makeText(this, "加载完成：" + channelSourceList.size() + "个频道", Toast.LENGTH_SHORT).show();
                    playChannel(0);
                } else {
                    Toast.makeText(this, "未获取到频道", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }).start();
}

    // 遥控器
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: playPrev(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: playNext(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: openChannelList(); return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_HELP: openSettings(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void playPrev() {
        if (channelSourceList.isEmpty()) return;
        int i = (currentPlayIndex - 1 + channelSourceList.size()) % channelSourceList.size();
        playChannel(i);
    }

    private void playNext() {
        if (channelSourceList.isEmpty()) return;
        int i = (currentPlayIndex + 1) % channelSourceList.size();
        playChannel(i);
    }

    private void openChannelList() {
        Toast.makeText(this,"频道列表",Toast.LENGTH_SHORT).show();
    }

    // ======================
    // 修复：打开设置（跳转）
    // ======================
    private void openSettings() {
        Toast.makeText(this,"设置页面",Toast.LENGTH_SHORT).show();
        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
    }

    // ======================
    // 修复：播放空指针保护
    // ======================
    public void playChannel(int index) {
        if (channelSourceList.isEmpty()) return;
        currentPlayIndex = index;
        currentChannelIndex = index;
        Channel ch = channelSourceList.get(index);

        if (TextUtils.isEmpty(ch.getPlayUrl())) {
            Toast.makeText(this,"播放地址为空",Toast.LENGTH_SHORT).show();
            return;
        }

        // 安全播放
        if (mPlayerManager != null) {
            mPlayerManager.playUrl(ch.getPlayUrl());
        }

        isPlayingPlayback = false;
        getSharedPreferences("play_config",0).edit().putInt("last_play_index",index).apply();
    }

    private void playEpgItem(Channel.EpgItem epg) {
        if (epg == null || TextUtils.isEmpty(epg.getReplayUrl())) {
            Toast.makeText(this,"暂无回看地址",Toast.LENGTH_SHORT).show();
            return;
        }
        if (mPlayerManager != null) {
            mPlayerManager.playUrl(epg.getReplayUrl());
        }
        isPlayingPlayback = true;
        Toast.makeText(this,"正在播放回看",Toast.LENGTH_SHORT).show();
    }

    private void setRatio(int index) {
        currentRatioIndex = index;
        switch (index) {
            case 0: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT); break;
            case 1: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT); break;
            case 2: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM); break;
            case 3: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL); break;
        }
        getSharedPreferences("play_config",0).edit().putInt("play_ratio",index).apply();
    }

    private void initListViewClick() {
        if (lvChannelList != null) {
            lvChannelList.setOnItemClickListener((p, v, pos, id) -> {
                Channel c = (Channel) p.getItemAtPosition(pos);
                int i = channelSourceList.indexOf(c);
                playChannel(i);
                lvChannelList.setSelection(pos);
            });
        }
        if (lvEpg != null) {
            lvEpg.setOnItemClickListener((p, v, pos, id) -> {
                Object o = p.getItemAtPosition(pos);
                if (o instanceof Channel.EpgItem) {
                    playEpgItem((Channel.EpgItem) o);
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (isPlayingPlayback) {
            isPlayingPlayback = false;
            playChannel(currentPlayIndex);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }
        mInstance = null;
    }

    private void initGesture() {}
    private void initChannelList() {}
    public void onReceiveConfig(String u1,String u2){}
}
