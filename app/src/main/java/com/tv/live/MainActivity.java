package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.PanelManager;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ListView lvGroup, lvChannelList, lvDate, lvEpg;
    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PanelManager panelManager;

    public static List<Channel> channelSourceList = new ArrayList<>();
    public static List<Channel> currentGroupChannelList = new ArrayList<>();

    private int currentPlayIndex = 0;
    private View panelLayout;

    private TVPlayerManager mPlayerManager;
    private final Handler handler = new Handler();

    private RefreshReceiver refreshReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        lvChannelList = findViewById(R.id.lv_channel);
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

        // ==============================
        // 分组点击 → 右侧只显示本组频道（完全隔离）
        // ==============================
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

        // ==============================
        // 频道点击 → 只播本组频道，永不串台
        // ==============================
        channelListManager.setOnChannelClickListener(position -> {
            if (currentGroupChannelList == null || position >= currentGroupChannelList.size()) return;
            Channel ch = currentGroupChannelList.get(position);
            playChannelByObj(ch);
            panelLayout.setVisibility(View.GONE);
        });
    }

    private void initPlayer() {
        mPlayerManager = new TVPlayerManager(this, findViewById(R.id.player_view));
        mPlayerManager.initPlayer();
    }

    // ==============================
    // 核心：安全播放（防错乱、防串台）
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

    public void loadLiveAndEpg() {
        LiveSourceLoader.load(source -> {
            channelSourceList = source;
            currentGroupChannelList.clear();
            currentGroupChannelList.addAll(source);

            runOnUiThread(() -> {
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(currentGroupChannelList, 0);
                if (!channelSourceList.isEmpty()) playChannel(0);
            });
        });
    }

    // ==============================
    // 网页刷新广播
    // ==============================
    private void registerRefreshReceiver() {
        refreshReceiver = new RefreshReceiver();
        IntentFilter filter = new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG");
        registerReceiver(refreshReceiver, filter);
    }

    private class RefreshReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadLiveAndEpg();
            Toast.makeText(MainActivity.this, "已刷新直播源", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (refreshReceiver != null) unregisterReceiver(refreshReceiver);
        if (mPlayerManager != null) mPlayerManager.release();
    }
}
