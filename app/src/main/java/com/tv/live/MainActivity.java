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
import java.util.ArrayList;
import java.util.List;

/**
 * 电视直播主页面
 * 功能：播放器、频道切换、EPG节目单、分组、手势/按键控制
 * 已修复：
 * 1. 手势点击冲突（日期列表可点击）
 * 2. 频道列表点击失效
 * 3. 按键与触摸互不干扰
 */
public class MainActivity extends AppCompatActivity {

    // 当前页面单例，供外部类调用
    public static MainActivity mInstance;

    // 频道数据
    public List<Channel> channelSourceList;       // 全部频道总列表
    public List<Channel> currentGroupChannelList; // 当前分组显示的频道列表
    public int currentPlayIndex = 0;               // 当前正在播放的频道索引

    // 界面控件
    private View panel_layout;        // 左侧面板（分组+频道+节目单）
    private PlayerView playerView;     // 播放器视图
    private View info_bar;            // 顶部信息栏

    // 顶部信息文字
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_channel_num;  // 切台时显示的频道数字

    // 功能管理器
    public TVPlayerManager mPlayerManager;         // 播放器管理
    private AppConfig appConfig;                   // 配置管理
    private PanelManager panelManager;             // 面板管理
    private GestureManager gestureManager;         // 手势控制
    private KeyEventManager keyEventManager;       // 遥控器按键
    private ChannelListManager channelListManager; // 频道列表
    private GroupListManager groupListManager;     // 分组列表
    private DateListManager dateListManager;       // 日期列表（EPG）
    private EpgManagerWrapper epgManagerWrapper;   // 节目单管理
    private ChannelSwitchManager switchManager;    // 频道上下切台

    // EPG 节目单相关状态
    private boolean epgPanelOpen = false;  // 节目单面板是否展开
    private boolean epg_enable;            // 节目单功能开关
    private boolean channel_reverse;       // 切台方向反转
    private int currentSelectedDateIndex = 0; // 当前选中的日期索引

    // 自动隐藏顶部信息栏
    private final Runnable hideInfoBar = () -> info_bar.setVisibility(View.GONE);

    // 切台防抖（防止快速连续按）
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;

    // 广播：刷新直播源 + EPG
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(() -> {
                    // 重新加载配置
                    loadSettings();
                    applyScreenRatio();

                    // 使用自定义源地址
                    String customLive = appConfig.getCustomLiveUrl();
                    String customEpg = appConfig.getCustomEpgUrl();
                    if (customLive != null) UrlConfig.LIVE_URL = customLive;
                    if (customEpg != null) UrlConfig.EPG_URL = customEpg;

                    // 刷新数据
                    loadLiveAndEpg();
                    Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 单例
        mInstance = this;

        // 强制横屏 + 全屏 + 不休眠
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 加载布局
        setContentView(R.layout.activity_main);

        // 绑定控件
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false); // 关闭系统自带控制栏

        panel_layout = findViewById(R.id.panel_layout);
        info_bar = findViewById(R.id.info_bar);

        // 列表控件
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 文字控件
        tv_channel_name = findViewById(R.id.tv_channel_name);
        tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        tv_tag_audio = findViewById(R.id.tv_tag_audio);
        tv_bitrate = findViewById(R.id.tv_bitrate);
        tv_channel_num = findViewById(R.id.tv_channel_num);

        // 初始化配置
        appConfig = AppConfig.getInstance(this);
        loadSettings();

        // 初始化各个列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate(); // 初始化日期（今天、明天、后天...）
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // ===================== 频道列表点击（修复：按名称匹配，永不失效） =====================
        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            if (currentGroupChannelList == null || position >= currentGroupChannelList.size()) return;

            // 获取点击的频道
            Channel clickCh = currentGroupChannelList.get(position);

            // 循环匹配频道名称，解决 indexOf 失效问题
            int realIdx = -1;
            for (int i = 0; i < channelSourceList.size(); i++) {
                if (channelSourceList.get(i).getName().equals(clickCh.getName())) {
                    realIdx = i;
                    break;
                }
            }

            // 找到就播放并关闭面板
            if (realIdx != -1) {
                playChannel(realIdx);
                panel_layout.setVisibility(View.GONE);
            }
        });

        // ===================== 日期选择（切换节目单日期） =====================
        dateListManager.setOnDateSelectedListener(position -> {
            currentSelectedDateIndex = position;
            if (channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(
                        channelSourceList.get(currentPlayIndex),
                        channelSourceList,
                        currentSelectedDateIndex
                );
            }
        });

        // ===================== 展开/收起 EPG 节目单 =====================
        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) {
                Toast.makeText(this, "节目单已关闭", Toast.LENGTH_SHORT).show();
                return;
            }

            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);

            // 展开时刷新当前频道节目单
            if (epgPanelOpen && channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(
                        channelSourceList.get(currentPlayIndex),
                        channelSourceList,
                        currentSelectedDateIndex
                );
            }
        });

        // ===================== 分组点击：切换频道分组 =====================
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            String groupName = groupListManager.getCurrentGroup(position);

            // 筛选当前分组频道
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }

            // 刷新列表
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);

            // 自动播放当前分组第一个频道（修复匹配）
            if (!currentGroupChannelList.isEmpty()) {
                Channel first = currentGroupChannelList.get(0);
                int findIdx = 0;
                for (int i = 0; i < channelSourceList.size(); i++) {
                    if (channelSourceList.get(i).getName().equals(first.getName())) {
                        findIdx = i;
                        break;
                    }
                }
                playChannel(findIdx);
            }
        });

        // ===================== 初始化播放器 =====================
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        // 实时更新清晰度、音轨、码率
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

        // ===================== 手势管理（已修复：不拦截点击） =====================
        gestureManager = new GestureManager(this);
        PlayerGestureHelper helper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            helper.handleTouch(event);
            return true;
        });

        // 按键、切台管理初始化
        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex(); // 读取上次播放频道

        // 加载直播源 + EPG
        loadLiveAndEpg();

        // 注册刷新广播
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // 设置画面比例
        applyScreenRatio();
    }

    /**
     * 读取用户设置：EPG开关、切台反转
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
    }

    /**
     * 设置画面显示模式：填充、全屏、原始
     */
    private void applyScreenRatio() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        String ratio = sp.getString("screen_ratio", "填充");

        switch (ratio) {
            case "全屏":
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM);
                break;
            case "原始":
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT);
                break;
            case "填充":
            default:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
        }

        if (playerView != null) playerView.requestLayout();
    }

    /**
     * 加载直播源列表 + 加载EPG节目单
     */
    public void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList = channels;

                // 初始化当前分组列表
                if (currentGroupChannelList == null) currentGroupChannelList = new ArrayList<>();
                currentGroupChannelList.clear();
                currentGroupChannelList.addAll(channels);

                // 设置切台管理器
                switchManager.setChannelList(channelSourceList);

                // 刷新分组、频道列表
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);

                // 播放频道
                playChannel(currentPlayIndex);

                // 加载节目单
                EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
                    if (channelSourceList != null && !channelSourceList.isEmpty()) {
                        epgManagerWrapper.refresh(
                                channelSourceList.get(currentPlayIndex),
                                channelSourceList,
                                currentSelectedDateIndex
                        );
                    }
                }));
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
            }
        });
    }
    public void playChannel(int index) {
    if (channelSourceList == null || channelSourceList.isEmpty()) return;

    index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
    currentPlayIndex = index;

    Channel ch = channelSourceList.get(index);
    if (TextUtils.isEmpty(ch.getPlayUrl())) return;

    // 调用带界面日志的版本
    PlayerHelper.playWithRedirect(ch.getPlayUrl(), new PlayerHelper.PlayerStateListener() {
        @Override
        public void setCurrentChannelName(String name) {}
    }, mPlayerManager, new PlayerHelper.LogCallback() {
        @Override
        public void onLog(String log) {
            // 这里就是输出到你电视界面的解析日志
            addParseLog(log);
        }
    });

    showChannelNum(index + 1);
    appConfig.setLastPlayIndex(index);
    channelListManager.setChannels(channelSourceList, index);
    epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

    info_bar.setVisibility(View.VISIBLE);
    info_bar.removeCallbacks(hideInfoBar);
    info_bar.postDelayed(hideInfoBar, 2000);
    tv_channel_name.setText(ch.getName());
}

    /**
     * 上一个频道
     */
    public void playPrev() {
        if (System.currentTimeMillis() - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = System.currentTimeMillis();

        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 下一个频道
     */
    public void playNext() {
        if (System.currentTimeMillis() - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = System.currentTimeMillis();

        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 显示频道数字，3秒后消失
     */
    private void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler().postDelayed(() -> tv_channel_num.setVisibility(View.GONE), 3000);
    }

    /**
     * 打开/关闭左侧面板
     */
    public void togglePanel() {
        panel_layout.setVisibility(panel_layout.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /**
     * 打开设置页面
     */
    public void openSettings() {
        try {
            startActivity(new Intent(this, SettingsActivity.class));
        } catch (Exception e) {
            Toast.makeText(this, "设置无法打开", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 遥控器按键统一分发
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (keyEventManager.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /**
     * 返回键：优先关闭面板
     */
    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE)
            panel_layout.setVisibility(View.GONE);
        else
            super.onBackPressed();
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
        applyScreenRatio();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(refreshReceiver);
        mPlayerManager.release();
    }
}
