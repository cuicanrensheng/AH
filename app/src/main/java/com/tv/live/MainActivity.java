package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import com.tv.live.widget.*;

import java.util.List;

/**
 * 主界面：播放器 + 频道列表 + 节目单 + 设置 入口
 */
public class MainActivity extends AppCompatActivity {
    // 全局单例，方便其他类调用
    public static MainActivity mInstance;

    // 频道数据
    public List<Channel> channelSourceList;          // 全部频道列表
    public List<Channel> currentGroupChannelList;     // 当前分类下的频道
    public int currentPlayIndex = 0;                  // 当前正在播放的频道下标

    // 布局
    private View panel_layout;                        // 频道+节目单总面板
    private PlayerView playerView;                    // 播放器视图
    private View info_bar;                            // 顶部信息栏（频道名、码率等）

    // 各种显示控件
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name, tv_next_time_range;
    private TextView tv_channel_num;                  // 切台时显示的频道号

    // 管理器
    public TVPlayerManager mPlayerManager;            // 播放器管理
    private AppConfig appConfig;                     // 配置管理
    private ScreenRatioManager screenRatioManager;    // 画面比例
    private PanelManager panelManager;                // 面板管理
    private GestureManager gestureManager;            // 手势管理（滑动/点击/长按）
    private KeyEventManager keyEventManager;          // 遥控器按键管理
    private ChannelListManager channelListManager;    // 频道列表
    private GroupListManager groupListManager;        // 分类列表
    private DateListManager dateListManager;          // 日期列表（节目单）
    private EpgManagerWrapper epgManagerWrapper;      // 节目单管理
    private ChannelSwitchManager switchManager;       // 频道切换（上/下）

    // 节目单开关
    private boolean epgPanelOpen = false;
    private boolean epg_enable;                       // 是否启用节目单
    private boolean channel_reverse;                   // 是否反向切台
    private int currentSelectedDateIndex = 0;         // 当前选中的节目单日期

    // 信息栏自动隐藏
    private final Runnable hideInfoBar = () -> info_bar.setVisibility(View.GONE);

    // 切台防抖（300ms内不能连续切）
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;

    /**
     * 广播：刷新直播源 + 节目单
     */
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(() -> {
                    loadSettings();
                    String customLive = appConfig.getCustomLiveUrl();
                    String customEpg = appConfig.getCustomEpgUrl();
                    if (customLive != null) UrlConfig.LIVE_URL = customLive;
                    if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                    loadLiveAndEpg();
                    Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        // 强制横屏 + 全屏 + 常亮
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // 初始化控件
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);           // 关闭系统自带控制条

        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);
        info_bar = findViewById(R.id.info_bar);

        // 信息栏控件绑定
        tv_channel_name = findViewById(R.id.tv_channel_name);
        tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        tv_tag_audio = findViewById(R.id.tv_tag_audio);
        tv_bitrate = findViewById(R.id.tv_bitrate);
        tv_channel_num = findViewById(R.id.tv_channel_num);

        // 读取配置
        appConfig = AppConfig.getInstance(this);
        loadSettings();

        // 初始化各种列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();

        // 面板管理（频道 + 节目单）
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 日期切换 → 刷新节目单
        dateListManager.setOnDateSelectedListener(position -> {
            currentSelectedDateIndex = position;
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        // 显示/隐藏节目单
        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) {
                Toast.makeText(this, "节目单已关闭", Toast.LENGTH_SHORT).show();
                return;
            }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            if (epgPanelOpen && channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        // 分类切换
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            String groupName = groupListManager.getCurrentGroup(position);
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            if (!currentGroupChannelList.isEmpty()) {
                playChannel(channelSourceList.indexOf(currentGroupChannelList.get(0)));
            }
        });

        // 初始化播放器
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        // 直播信息更新（清晰度、音轨、码率）
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

        // 手势管理（手机滑动、点击、长按）
        gestureManager = new GestureManager(this);
        PlayerGestureHelper helper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            helper.handleTouch(event);
            return true;
        });

        // 遥控器按键管理
        keyEventManager = new KeyEventManager(this);

        // 频道切换管理
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        // 加载直播源 + 节目单
        loadLiveAndEpg();

        // 注册刷新广播
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));
    }

    /**
     * 读取设置：节目单开关、切台方向
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
    }

    /**
     * 加载直播源 + 节目单
     */
    public void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList = channels;
                currentGroupChannelList = channels;
                switchManager.setChannelList(channelSourceList);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
            }
        });

        // 加载节目单
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    /**
     * 播放指定下标的频道
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (TextUtils.isEmpty(ch.getPlayUrl())) return;

        // 播放地址
        mPlayerManager.playUrl(ch.getPlayUrl());

        // 显示频道号
        showChannelNum(index + 1);

        // 保存最后播放记录
        appConfig.setLastPlayIndex(index);

        // 刷新频道列表选中状态
        channelListManager.setChannels(channelSourceList, index);

        // 切台自动刷新节目单
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 显示顶部信息栏，4秒后自动隐藏
        info_bar.setVisibility(View.VISIBLE);
        info_bar.removeCallbacks(hideInfoBar);
        info_bar.postDelayed(hideInfoBar, 4000);
        tv_channel_name.setText(ch.getName());
    }

    /**
     * 播放上一个频道
     */
    public void playPrev() {
        if (System.currentTimeMillis() - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = System.currentTimeMillis();
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 播放下一个频道
     */
    public void playNext() {
        if (System.currentTimeMillis() - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = System.currentTimeMillis();
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 显示频道号，4秒后消失
     */
    private void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler().postDelayed(() -> tv_channel_num.setVisibility(View.GONE), 4000);
    }

    // ======================
    // 下面是 按键/手势 公共方法
    // ======================

    /**
     * 显示/隐藏频道面板
     */
    public void togglePanel() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
        } else {
            panel_layout.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 打开设置页面
     */
    public void openSettings() {
        Intent intent = new Intent(this, SettingActivity.class);
        startActivity(intent);
    }

    /**
     * 数字键选台（0-9）
     */
    public void selectChannelByNumber(int num) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int index = Math.max(0, Math.min(num, channelSourceList.size() - 1));
        playChannel(index);
    }

    /**
     * 遥控器按键分发（电视专用）
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (keyEventManager.dispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 返回键：先关面板，再退出
     */
    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPlayerManager.onBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        mPlayerManager.onForeground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(refreshReceiver);
        mPlayerManager.release();
    }
}
