package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.tv.live.manager.PanelManager;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // 实例供给外部类调用
    public static MainActivity mInstance;

    // 全局数据源
    public static List<Channel> channelSourceList = new ArrayList<>();
    public static List<Channel> currentGroupChannelList = new ArrayList<>();

    // 当前播放索引
    public int currentPlayIndex = 0;

    // 视图
    private ListView lvGroup, lvChannelList, lvDate, lvEpg;
    private View panelLayout;

    // 管理器
    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PanelManager panelManager;

    // 播放器
    private TVPlayerManager mPlayerManager;

    // 广播
    private RefreshReceiver refreshReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        // 全屏 + 亮屏 + 横屏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_main);

        initView();
        initPlayer();
        registerRefreshReceiver();
        loadLiveAndEpg();
    }

    private void initView() {
        lvGroup = findViewById(R.id.lv_group);
        
        // ======================
        // 【修复崩溃】正确控件ID：lv_channel_list
        // ======================
        lvChannelList = findViewById(R.id.lv_channel_list);

        lvDate = findViewById(R.id.lv_date);
        lvEpg = findViewById(R.id.lv_epg);
        panelLayout = findViewById(R.id.panel_layout);

        groupListManager = new GroupListManager(this, lvGroup);
        channelListManager = new ChannelListManager(this, lvChannelList);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        panelManager = new PanelManager(panelLayout, channelListManager, epgManagerWrapper);

        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(index -> {
            if (channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, index);
            }
        });

        // 分组切换
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.clearChoices();
            lvGroup.setItemChecked(position, true);
            String groupName = groupListManager.getCurrentGroup(position);

            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannels(currentGroupChannelList, 0);
        });

        // 频道点击
        channelListManager.setOnChannelClickListener(position -> {
            if (currentGroupChannelList == null || position >= currentGroupChannelList.size()) return;
            Channel ch = currentGroupChannelList.get(position);
            playChannelByObj(ch);
            panelLayout.setVisibility(View.GONE);
        });
    }

    // ==============================
    // 播放器初始化（已修复）
    // ==============================
    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        PlayerView playerView = findViewById(R.id.player_view);
        mPlayerManager.attachPlayerView(playerView);
    }

    // ==============================
    // 【遥控器 + 手势 必备4个方法】
    // ==============================

    /**
     * 开关频道面板
     */
    public void togglePanel() {
        if (panelLayout != null) {
            panelLayout.setVisibility(
                panelLayout.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
            );
        }
    }

    /**
     * 打开设置页面
     */
    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    /**
     * 上一频道
     */
    public void playPrev() {
        if (currentPlayIndex > 0) {
            playChannel(currentPlayIndex - 1);
        }
    }

    /**
     * 下一频道
     */
    public void playNext() {
        if (channelSourceList != null && currentPlayIndex < channelSourceList.size() - 1) {
            playChannel(currentPlayIndex + 1);
        }
    }

    // ==============================
    // 播放逻辑
    // ==============================
    private void playChannelByObj(Channel channel) {
        if (channel == null) return;
        for (int i = 0; i < channelSourceList.size(); i++) {
            Channel c = channelSourceList.get(i);
            if (c.getName().equals(channel.getName()) && c.getPlayUrl().equals(channel.getPlayUrl())) {
                playChannel(i);
                return;
            }
        }
    }

    public void playChannel(int index) {
        if (channelSourceList == null || index < 0 || index >= channelSourceList.size()) return;
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        mPlayerManager.playUrl(ch.getPlayUrl());
        channelListManager.setSelectedPosition(findInGroupList(ch));
        dateListManager.setSelectedPosition(0);
        epgManagerWrapper.refresh(ch, channelSourceList, 0);
    }

    private int findInGroupList(Channel ch) {
        for (int i = 0; i < currentGroupChannelList.size(); i++) {
            Channel c = currentGroupChannelList.get(i);
            if (c.getName().equals(ch.getName()) && c.getPlayUrl().equals(ch.getPlayUrl())) {
                return i;
            }
        }
        return 0;
    }

    // ==============================
    // 加载直播源
    // ==============================
    public void loadLiveAndEpg() {
        new Thread(() -> {
            try {
                SharedPreferences sp = getSharedPreferences("config", MODE_PRIVATE);
                String url = sp.getString("source_url", "");
                if (!url.isEmpty()) {
                    channelSourceList = PlaylistParser.parse(url);
                    currentGroupChannelList.clear();
                    currentGroupChannelList.addAll(channelSourceList);

                    runOnUiThread(() -> {
                        groupListManager.setGroups(channelSourceList);
                        channelListManager.setChannels(currentGroupChannelList, 0);
                        if (!channelSourceList.isEmpty()) playChannel(0);
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void registerRefreshReceiver() {
        refreshReceiver = new RefreshReceiver();
        IntentFilter filter = new IntentFilter("com.tv.live.REFRESH_LIVE_EPG");
        registerReceiver(refreshReceiver, filter);
    }

    private class RefreshReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadLiveAndEpg();
            Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (refreshReceiver != null) unregisterReceiver(refreshReceiver);
    }
}
