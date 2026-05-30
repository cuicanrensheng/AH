package com.tv.live;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private View panel_layout;
    private TextView btn_show_epg;
    private NanoHTTPD nanoHTTPD;
    public TVPlayerManager mPlayerManager;
    private PlayerGestureHelper gestureHelper;
    private SharedPreferences sp;
    private Button btnToggleController;
    private boolean isControllerVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        mInstance = this;
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        String customLive = sp.getString("custom_live_url", null);
        String customEpg = sp.getString("custom_epg_url", null);
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        PlayerView playerView = findViewById(R.id.player_view);
        panel_layout = findViewById(R.id.panel_layout);
        lvGroup = findViewById(R.id.lv_group);
        lvChannelList = findViewById(R.id.lv_channel_list);
        lvDate = findViewById(R.id.lv_date);
        lvEpg = findViewById(R.id.lv_epg);
        btn_show_epg = findViewById(R.id.btn_show_epg);
        btnToggleController = findViewById(R.id.btn_toggle_controller);

        lvGroup.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>()));
        lvGroup.setOnItemClickListener((p, v, pos, id) -> {
            String selectedGroup = (String) p.getItemAtPosition(pos);
            List<Channel> filteredChannels = new ArrayList<>();
            for (Channel channel : channelSourceList) {
                if (selectedGroup.equals(channel.getGroup())) {
                    filteredChannels.add(channel);
                }
            }
            List<String> channelNames = new ArrayList<>();
            for (Channel c : filteredChannels) {
                channelNames.add(c.getName());
            }
            lvChannelList.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, channelNames));
        });

        playerView.setUseController(isControllerVisible);
        btnToggleController.setOnClickListener(v -> {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
            Toast.makeText(this, isControllerVisible ? "已显示控制条" : "已隐藏控制条", Toast.LENGTH_SHORT).show();
        });

        playerView.setOnClickListener(v -> togglePanel());
        btn_show_epg.setOnClickListener(v -> {
            lvEpg.post(() -> lvEpg.smoothScrollToPosition(0));
        });
        initDateList();

        gestureHelper = new PlayerGestureHelper(this, new PlayerGestureHelper.GestureCallback() {
            @Override public void onOk() { togglePanel(); }
            @Override public void onLongOk() { openSettings(); }
            @Override public void onMenu() { openSettings(); }
            @Override public void onNextChannel() { playPrev(); }
            @Override public void onPrevChannel() { playNext(); }
        });
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        SharedPreferences spPlay = getSharedPreferences("play_config", Context.MODE_PRIVATE);
        currentRatioIndex = spPlay.getInt("play_ratio", 2);
        currentPlayIndex = spPlay.getInt("last_play_index", 0);

        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        applyScreenRatioFromSettings();

        mPlayerManager.setOnPlayStateListener(new TVPlayerManager.OnPlayStateListener() {
            @Override public void onIdle() {}
            @Override public void onBuffering() {
                try {
                    if (!channelSourceList.isEmpty()) {
                        String name = channelSourceList.get(currentPlayIndex).getName();
                        Toast.makeText(MainActivity.this, "正在播放：" + name, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {}
            }
            @Override public void onPlayReady() {}
            @Override public void onPlayEnd() { Toast.makeText(MainActivity.this,"播放结束，自动重试",Toast.LENGTH_SHORT).show(); }
            @Override public void onPlayError(String msg) { Toast.makeText(MainActivity.this,"播放异常："+msg,Toast.LENGTH_SHORT).show(); }
        });

        loadLiveAndEpg();
        initListViewClick();

        try {
            nanoHTTPD = new NanoHTTPD(10481);
            nanoHTTPD.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initDateList() {
        List<String> dates = new ArrayList<>();
        dates.add("今天");
        dates.add("周一");
        dates.add("周二");
        dates.add("周三");
        dates.add("周四");
        dates.add("周五");
        dates.add("周六");
        dates.add("周日");
        lvDate.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, dates));
    }

    private void togglePanel() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
        } else {
            panel_layout.setVisibility(View.VISIBLE);
            refreshChannelList();
            refreshCurrentEpg();
        }
    }

    private void refreshChannelList() {
        if (channelSourceList.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) names.add(c.getName());
        lvChannelList.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        lvChannelList.setSelection(currentPlayIndex);
    }

    private void refreshCurrentEpg() {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            runOnUiThread(() -> {
                lvEpg.setAdapter(new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1,
                        Collections.singletonList("暂无节目单")));
            });
            return;
        }

        new Thread(() -> {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(currentChannel.getName());
            List<String> data = new ArrayList<>();

            if (epgList != null && !epgList.isEmpty()) {
                for (Channel.EpgItem item : new ArrayList<>(epgList)) {
                    data.add(item.dayName + " " + item.time + " " + item.title);
                }
            } else {
                data.add("暂无节目单");
            }

            runOnUiThread(() -> {
                lvEpg.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, data));
            });
        }).start();
    }

    private void applyScreenRatioFromSettings() {
        String ratio = sp.getString("screen_ratio", "全屏");
        switch (ratio) {
            case "原始": mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT); break;
            case "填充": mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL); break;
            default: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM); break;
        }
    }

    public void loadLiveAndEpg() {
        new Thread(() -> {
            try {
                List<Channel> channels = PlaylistParser.parse(UrlConfig.LIVE_URL);
                runOnUiThread(() -> {
                    if (channels != null && !channels.isEmpty()) {
                        channelSourceList.clear();
                        channelSourceList.addAll(channels);
                        Toast.makeText(MainActivity.this, "直播源加载完成：" + channelSourceList.size() + "个频道", Toast.LENGTH_SHORT).show();

                        Set<String> groupSet = new HashSet<>();
                        for (Channel c : channelSourceList) {
                            groupSet.add(c.getGroup());
                        }
                        List<String> groupList = new ArrayList<>(groupSet);
                        lvGroup.setAdapter(new android.widget.ArrayAdapter<>(MainActivity.this,
                                android.R.layout.simple_list_item_1, groupList));

                        playChannel(currentPlayIndex);
                    }
                });

                EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
                EpgManager.getInstance().loadEpg(() -> {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "EPG节目单加载完成", Toast.LENGTH_SHORT).show();
                        refreshCurrentEpg();
                    });
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean reverse = sp.getBoolean("channel_reverse", false);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (reverse) playNext(); else playPrev(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (reverse) playPrev(); else playNext(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: togglePanel(); return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_HELP: openSettings(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void playPrev() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIndex = (currentPlayIndex - 1 + channelSourceList.size()) % channelSourceList.size();
        playChannel(newIndex);
    }

    private void playNext() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIndex = (currentPlayIndex + 1) % channelSourceList.size();
        playChannel(newIndex);
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void playEpgItem(Channel.EpgItem epg) {
        Toast.makeText(this,"暂无回看",Toast.LENGTH_SHORT).show();
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        currentChannelIndex = index;

        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            Toast.makeText(this, "播放地址无效", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mPlayerManager != null) {
            mPlayerManager.play(ch.getPlayUrl());
        }

        getSharedPreferences("play_config", MODE_PRIVATE)
                .edit()
                .putInt("last_play_index", index)
                .apply();

        refreshChannelList();
        refreshCurrentEpg();
    }

    private void initListViewClick() {
        if (lvChannelList != null) {
            lvChannelList.setOnItemClickListener((p, v, pos, id) -> {
                playChannel(pos);
                togglePanel();
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

    public void onReceiveConfig(String liveUrl, String epgUrl) {
        SharedPreferences.Editor edit = sp.edit();
        if (liveUrl != null && !liveUrl.isEmpty()) {
            UrlConfig.LIVE_URL = liveUrl;
            edit.putString("custom_live_url", liveUrl);
        }
        if (epgUrl != null && !epgUrl.isEmpty()) {
            UrlConfig.EPG_URL = epgUrl;
            edit.putString("custom_epg_url", epgUrl);
        }
        edit.apply();
        runOnUiThread(() -> {
            Toast.makeText(this, "配置已保存，重新加载中…", Toast.LENGTH_LONG).show();
            loadLiveAndEpg();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyScreenRatioFromSettings();
    }

    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
            return;
        }
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
        if (nanoHTTPD != null) nanoHTTPD.stop();
        if (mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }
}
