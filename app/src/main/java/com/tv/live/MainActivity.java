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
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // ==================== 修复项 4 ====================
    // 给外部类（ChannelListActivity）提供实例引用
    public static MainActivity mInstance;

    // 全局数据源
    public static List<Channel> channelSourceList = new ArrayList<>();
    public static List<Channel> currentGroupChannelList = new ArrayList<>();

    // 当前正在播放的频道索引
    public int currentPlayIndex = 0;

    // 视图控件
    private ListView lvGroup, lvChannelList, lvDate, lvEpg;
    private View panelLayout;

    // 管理器
    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;

    // 播放器
    private TVPlayerManager mPlayerManager;

    // 广播接收器
    private RefreshReceiver refreshReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 绑定当前实例（修复 mInstance 不存在）
        mInstance = this;

        // 全屏 + 保持亮屏 + 横屏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_main);

        // 初始化布局
        initView();
        // 初始化播放器
        initPlayer();
        // 注册刷新广播
        registerRefreshReceiver();
        // 加载直播源
        loadLiveAndEpg();
    }

    /**
     * 初始化布局控件与各列表管理器
     */
    private void initView() {
        lvGroup = findViewById(R.id.lv_group);
        lvChannelList = findViewById(R.id.lv_channel);
        lvDate = findViewById(R.id.lv_date);
        lvEpg = findViewById(R.id.lv_epg);
        panelLayout = findViewById(R.id.panel_layout);

        // 初始化分组、频道、日期、节目单管理器
        groupListManager = new GroupListManager(this, lvGroup);
        channelListManager = new ChannelListManager(this, lvChannelList);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);

        // 初始化日期栏
        dateListManager.initDate();
        // 日期切换 -> 刷新节目单
        dateListManager.setOnDateSelectedListener(index -> {
            if (channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, index);
            }
        });

        // 分组点击 -> 只显示当前分组频道
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.clearChoices();
            lvGroup.setItemChecked(position, true);
            String groupName = groupListManager.getCurrentGroup(position);

            // 清空并重新添加当前分组下的频道
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            // 更新频道列表
            channelListManager.setChannels(currentGroupChannelList, 0);
        });

        // 频道点击 -> 播放对应频道
        channelListManager.setOnChannelClickListener(position -> {
            if (currentGroupChannelList == null || position >= currentGroupChannelList.size()) return;
            Channel ch = currentGroupChannelList.get(position);
            playChannelByObj(ch);
            // 播放后自动隐藏面板
            panelLayout.setVisibility(View.GONE);
        });
    }

    /**
     * ==================== 修复项 2 + 5 ====================
     * 初始化播放器
     * 1. 修复 TVPlayerManager 构造方法错误
     * 2. 删除不存在的 initPlayer() 方法
     */
    private void initPlayer() {
        // 正确构造（只传 Context）
        mPlayerManager = new TVPlayerManager(this);
        // 错误方法已删除：mPlayerManager.initPlayer();
    }

    /**
     * ==================== 修复项 1 ====================
     * 切换频道面板的显示与隐藏
     * 被调用：KeyEventManager / GestureManager
     */
    public void togglePanel() {
        if (panelLayout != null) {
            if (panelLayout.getVisibility() == View.VISIBLE) {
                // 隐藏面板
                panelLayout.setVisibility(View.GONE);
            } else {
                // 显示面板
                panelLayout.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * ==================== 修复项 1 ====================
     * 打开设置页面
     * 被调用：KeyEventManager / GestureManager
     */
    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    /**
     * ==================== 修复项 1 ====================
     * 播放上一个频道
     * 被调用：KeyEventManager / GestureManager
     */
    public void playPrev() {
        if (currentPlayIndex > 0) {
            playChannel(currentPlayIndex - 1);
        }
    }

    /**
     * ==================== 修复项 1 ====================
     * 播放下一个频道
     * 被调用：KeyEventManager / GestureManager
     */
    public void playNext() {
        if (channelSourceList != null && currentPlayIndex < channelSourceList.size() - 1) {
            playChannel(currentPlayIndex + 1);
        }
    }

    /**
     * 根据频道对象播放（防串台）
     */
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

    /**
     * 根据索引播放频道
     */
    public void playChannel(int index) {
        if (channelSourceList == null || index < 0 || index >= channelSourceList.size()) return;
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        // 播放地址
        mPlayerManager.playUrl(ch.getPlayUrl());
        // 同步选中状态
        channelListManager.setSelectedPosition(findInGroupList(ch));
        dateListManager.setSelectedPosition(0);
        // 刷新节目单
        epgManagerWrapper.refresh(ch, channelSourceList, 0);
    }

    /**
     * 查找当前频道在分组列表中的位置
     */
    private int findInGroupList(Channel ch) {
        for (int i = 0; i < currentGroupChannelList.size(); i++) {
            Channel c = currentGroupChannelList.get(i);
            if (c.getName().equals(ch.getName()) && c.getPlayUrl().equals(ch.getPlayUrl())) {
                return i;
            }
        }
        return 0;
    }

    /**
     * ==================== 修复项 3 ====================
     * 加载直播源（替换报错的 LiveSourceLoader）
     * 直接使用 PlaylistParser 解析
     */
    public void loadLiveAndEpg() {
        new Thread(() -> {
            try {
                SharedPreferences sp = getSharedPreferences("config", MODE_PRIVATE);
                String url = sp.getString("source_url", "");
                if (!url.isEmpty()) {
                    // 解析直播源
                    channelSourceList = PlaylistParser.parse(url);
                    // 默认显示全部频道
                    currentGroupChannelList.clear();
                    currentGroupChannelList.addAll(channelSourceList);

                    // 更新UI
                    runOnUiThread(() -> {
                        groupListManager.setGroups(channelSourceList);
                        channelListManager.setChannels(currentGroupChannelList, 0);
                        if (!channelSourceList.isEmpty()) {
                            playChannel(0);
                        }
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "直播源加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /**
     * 注册刷新广播
     */
    private void registerRefreshReceiver() {
        refreshReceiver = new RefreshReceiver();
        IntentFilter filter = new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG");
        registerReceiver(refreshReceiver, filter);
    }

    /**
     * 刷新广播接收器
     */
    private class RefreshReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadLiveAndEpg();
            Toast.makeText(MainActivity.this, "直播源已刷新", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (refreshReceiver != null) {
            unregisterReceiver(refreshReceiver);
        }
    }
}
