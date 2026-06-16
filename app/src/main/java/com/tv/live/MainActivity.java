package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;

import com.tv.live.config.AppConfig;
import com.tv.live.manager.GestureManager;
import com.tv.live.manager.ScreenRatioManager;
import com.tv.live.manager.KeyEventManager;
import com.tv.live.manager.ChannelSwitchManager;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;

/**
 * 直播应用主界面
 * 核心能力：
 * 1. ExoPlayer 直播播放 + 自定义信息栏
 * 2. 频道分组 + 频道列表 + EPG节目单 侧边面板
 * 3. 手势滑动切台 + 遥控器按键切台
 * 4. 直播源/EPG 加载、刷新、自定义配置
 * 修复点：
 * - 彻底屏蔽 ExoPlayer 原生控制器，杜绝滑动唤起
 * - 关闭所有原生状态弹窗（缓冲、错误等）
 * - 频道切换支持分组内循环 + 冷却防抖
 * - 触摸事件完全拦截，原生逻辑无触发入口
 * - 低版本 ExoPlayer 编译兼容保护
 */
public class MainActivity extends AppCompatActivity {
    // 全局单例，供其他模块调用主界面方法
    public static MainActivity mInstance;

    // ====================== 频道数据 ======================
    public List<Channel> channelSourceList = new ArrayList<>();      // 全量频道数据源
    public List<Channel> currentGroupChannelList = new ArrayList<>();// 当前选中分组的频道列表
    public int currentPlayIndex = 0;                                 // 当前播放频道的全局索引
    private String nowSelectGroup = "";                              // 当前选中的分组名称

    // ====================== 视图与核心组件 ======================
    private View panel_layout;               // 侧边频道/EPG面板根布局
    public TVPlayerManager mPlayerManager;   // 播放器核心管理器（播放、暂停、释放等）
    private PlayerView playerView;           // ExoPlayer 播放渲染视图
    private AppConfig appConfig;             // 应用配置持久化管理
    private ScreenRatioManager screenRatioManager; // 画面比例适配管理

    // ====================== 业务管理器 ======================
    private LivePanelManager.PanelManager panelManager;               // 面板显隐控制
    private GestureManager gestureManager;                            // 手势交互管理（滑动切台、调音量）
    private KeyEventManager keyEventManager;                          // 遥控器按键事件分发
    private LivePanelManager.ChannelListManager channelListManager;   // 频道列表UI适配
    private LivePanelManager.GroupListManager groupListManager;       // 分组列表UI适配
    private LivePanelManager.DateListManager dateListManager;         // EPG日期列表UI适配
    private LivePanelManager.EpgManagerWrapper epgManagerWrapper;     // EPG节目单数据+UI包装
    private PlayerStateListenerImpl playerStateListener;              // 播放器状态回调监听
    private ChannelSwitchManager switchManager;                       // 全局频道切换逻辑（上/下一台）

    // ====================== 状态标志位 ======================
    private boolean epgPanelOpen = false;       // EPG节目单子面板是否展开
    private boolean isControllerVisible = false;// 原生控制器显隐状态（默认关闭，兼容旧广播）
    private boolean epg_enable;                 // 设置项：是否启用节目单
    private boolean channel_reverse;            // 设置项：频道切换方向是否反向
    private boolean number_channel_enable;      // 设置项：是否显示频道数字角标
    private boolean auto_update_source;         // 设置项：是否自动更新直播源
    private int currentSelectedDateIndex = 0;   // EPG当前选中的日期索引
    private SharedPreferences sp;               // 本地配置存储

    // ====================== 自定义播放信息栏 ======================
    private View info_bar;
    private TextView tv_channel_name;      // 频道名称
    private TextView tv_tag_fhd;           // 画质标签（HD/FHD等）
    private TextView tv_tag_audio;         // 音频类型标签
    private TextView tv_bitrate;           // 实时码率
    private TextView tv_current_program_name;  // 当前节目名称
    private TextView tv_current_time_range;    // 当前节目时间范围
    private TextView tv_remaining_time;    // 节目剩余时长
    private TextView tv_next_program_name; // 下一节目名称
    public TextView tv_next_time_range;    // 下一节目时间范围
    private ProgressBar progress_program;  // 节目播放进度条
    private TextView tv_channel_num;       // 频道数字角标

    // ====================== 常量配置 ======================
    private static final int MAX_REDIRECT_COUNT = 10;    // 播放地址最大重定向跟随次数
    private static final int CONNECT_TIMEOUT = 8000;     // 网络连接超时（毫秒）
    private static final int READ_TIMEOUT = 8000;        // 网络读取超时（毫秒）
    private static final String DEF_UA = "ExoPlayer";    // 默认请求 User-Agent
    private static final String DEF_REFER = "https://www.huya.com/"; // 默认 Referer 请求头
    private static final long CHANNEL_COOLDOWN = 300;    // 切台冷却时间（防快速连点）

    // 信息栏自动隐藏任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    private long lastChannelChangeTime = 0; // 上次切台时间戳（冷却防抖用）
    public static List<String> logList = new ArrayList<>(); // 全局运行日志（最多100条）

    /**
     * 全局日志添加方法
     * 新日志插入头部，超出100条自动移除最旧的
     */
    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
    }

    // ====================== 广播接收器 ======================
    /**
     * 切换原生控制器显隐的广播（兼容旧逻辑，默认控制器已关闭）
     */
    private final BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    /**
     * 刷新直播源与EPG的广播
     * 接收外部指令重新加载配置和数据
     */
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(() -> {
                    loadSettings();
                    // 应用自定义直播源/EPG地址
                    String customLive = appConfig.getCustomLiveUrl();
                    String customEpg = appConfig.getCustomEpgUrl();
                    if (customLive != null) UrlConfig.LIVE_URL = customLive;
                    if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                    loadLiveAndEpg();
                    Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        // ========== 窗口基础配置 ==========
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); // 强制横屏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN); // 全屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // 播放时保持屏幕常亮
        // 沉浸式粘性模式：隐藏状态栏+导航栏，触摸不常驻显示
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        setContentView(R.layout.activity_main);

        // ========== 基础初始化 ==========
        tv_channel_num = findViewById(R.id.tv_channel_num);
        initInfoBar();
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 加载用户自定义的直播源/EPG地址，覆盖默认配置
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        // ========== 播放器视图 + 原生UI全面屏蔽（编译安全版） ==========
        playerView = findViewById(R.id.player_view);

        // ========== 第一层：所有版本100%支持的核心屏蔽 ==========
        // 核心1：彻底关闭原生控制器，移除可见性回调
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        // 核心2：禁用所有交互属性，从入口阻断控制器唤起
        playerView.setClickable(false);
        playerView.setLongClickable(false);
        playerView.setFocusable(false);
        playerView.setFocusableInTouchMode(false);

        // 核心3：关闭所有原生状态弹窗
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); // 关闭缓冲加载转圈
        playerView.setErrorMessageProvider(null);                    // 关闭原生错误提示
        // 隐藏所有原生控制按钮
        playerView.setShowRewindButton(false);
        playerView.setShowFastForwardButton(false);
        playerView.setShowPreviousButton(false);
        playerView.setShowNextButton(false);
        // 切台/播放器重置时保留最后一帧，避免黑屏闪烁和状态文字
        playerView.setKeepContentOnPlayerReset(true);

        // ========== 第二层：低版本兼容增强屏蔽（有就生效，没有也不报错） ==========
        try { playerView.setControllerAutoShow(false); } catch (Exception ignored) {}
        try { playerView.setControllerHideOnTouch(false); } catch (Exception ignored) {}
        try { playerView.setShowPlayButton(false); } catch (Exception ignored) {}
        try { playerView.setShowSubtitleButton(false); } catch (Exception ignored) {}

        // ========== 面板与列表控件初始化 ==========
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // EPG展开按钮点击：切换日期列表和节目单显隐
        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) {
                Toast.makeText(MainActivity.this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
                return;
            }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            // 展开时刷新当前频道的节目单
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                currentSelectedDateIndex = dateListManager.getSelectedPosition();
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // 日期列表初始化 + 选中监听
        dateListManager = new LivePanelManager.DateListManager(this, lvDate);
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, pos);
            }
        });

        // 频道列表初始化 + 点击播放
        channelListManager = new LivePanelManager.ChannelListManager(this, lvChannelList);
        channelListManager.setOnChannelClickListener(filterPos -> {
            if (filterPos >= 0 && filterPos < currentGroupChannelList.size()) {
                Channel target = currentGroupChannelList.get(filterPos);
                int global = channelSourceList.indexOf(target);
                if (global != -1) {
                    playChannel(global);
                    togglePanel();
                }
            }
        });

        // 分组列表初始化 + 切换监听
        // 修复：监听统一由管理器分发，避免内部覆盖导致列表不更新
        groupListManager = new LivePanelManager.GroupListManager(this, lvGroup);
        groupListManager.setOnGroupChangeListener(groupName -> {
            if (TextUtils.isEmpty(groupName)) return;
            int position = groupListManager.getSelectedPos();
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            nowSelectGroup = groupName;
            // 过滤当前分组的频道
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (nowSelectGroup.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            // 更新频道列表显示
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
        });

        // EPG包装器 + 面板管理器初始化
        epgManagerWrapper = new LivePanelManager.EpgManagerWrapper(this, lvEpg);
        panelManager = new LivePanelManager.PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // ========== 播放器初始化 ==========
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        // 播放状态监听
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        // 直播信息更新监听（画质、音频、码率）
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

        // 应用画面比例配置
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // ========== 自定义手势 + 触摸事件完全拦截 ==========
        gestureManager = new GestureManager(this);
        PlayerGestureHelper gestureHelper = gestureManager.create();

        // 核心：触摸事件全部由自定义手势消费，彻底阻断原生逻辑
        playerView.setOnTouchListener((v, event) -> {
            // 侧边面板弹出时透传事件，保证内部列表可正常点击
            if (panel_layout.getVisibility() == View.VISIBLE) {
                return false;
            }
            // 自定义手势处理滑动切台、调音量等
            gestureHelper.handleTouch(event);
            // 返回true：完全消费事件，事件不会向下传递到PlayerView原生逻辑
            // 从事件流层面彻底杜绝滑动/点击唤起原生控制器的可能
            return true;
        });

        // 按键事件管理初始化
        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        // 恢复上次播放的频道索引
        currentPlayIndex = appConfig.getLastPlayIndex();
        // 启动加载直播源和EPG
        loadLiveAndEpg();
    }

    /**
     * 初始化自定义信息栏所有控件
     */
    private void initInfoBar() {
        info_bar = findViewById(R.id.info_bar);
        tv_channel_name = findViewById(R.id.tv_channel_name);
        tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        tv_tag_audio = findViewById(R.id.tv_tag_audio);
        tv_bitrate = findViewById(R.id.tv_bitrate);
        tv_current_program_name = findViewById(R.id.tv_current_program_name);
        tv_current_time_range = findViewById(R.id.tv_current_time_range);
        progress_program = findViewById(R.id.progress_program);
        tv_remaining_time = findViewById(R.id.tv_remaining_time);
        tv_next_program_name = findViewById(R.id.tv_next_program_name);
        tv_next_time_range = findViewById(R.id.tv_next_time_range);
    }

    /**
     * 从本地SharedPreferences加载用户设置
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
    }

    /**
     * 返回键逻辑：面板打开时先关闭面板，否则退出应用
     */
    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
            playerView.requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 加载直播源列表 + EPG节目单数据
     * 直播源加载完成后自动播放上次选中的频道
     */
    public void loadLiveAndEpg() {
        // 异步加载直播源
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                // 同步数据到全局切台管理器
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);

                // 恢复上次选中的分组，无记录则默认选中第一个
                if (!TextUtils.isEmpty(nowSelectGroup)) {
                    currentGroupChannelList.clear();
                    for (Channel ch : channelSourceList) {
                        if (ch.getGroup().equals(nowSelectGroup)) {
                            currentGroupChannelList.add(ch);
                        }
                    }
                    channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                } else {
                    List<String> groups = groupListManager.getGroupList();
                    if (groups != null && groups.size() > 0) {
                        nowSelectGroup = groups.get(0);
                        currentGroupChannelList.clear();
                        for (Channel ch : channelSourceList) {
                            if (ch.getGroup().equals(nowSelectGroup)) {
                                currentGroupChannelList.add(ch);
                            }
                        }
                        channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                    } else {
                        // 无分组时直接显示全部频道
                        channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    }
                }
                // 开始播放当前频道
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        // 异步加载EPG节目单
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    /**
     * 切换到上一个频道
     * 支持分组内循环、反向切换、冷却防抖
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        // 冷却时间内忽略重复请求，防止快速连点
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        // 有选中分组时，在分组内循环切换
        if (!TextUtils.isEmpty(nowSelectGroup) && !currentGroupChannelList.isEmpty()) {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            int groupIndex = currentGroupChannelList.indexOf(currentChannel);
            // 兜底：当前频道不在分组内时，从第一个开始
            if (groupIndex == -1) groupIndex = 0;

            int newGroupIndex;
            if (channel_reverse) {
                // 反向模式：上一台 = 索引+1
                newGroupIndex = groupIndex + 1;
                if (newGroupIndex >= currentGroupChannelList.size()) {
                    newGroupIndex = 0;
                }
            } else {
                // 正常模式：上一台 = 索引-1
                newGroupIndex = groupIndex - 1;
                if (newGroupIndex < 0) {
                    newGroupIndex = currentGroupChannelList.size() - 1;
                }
            }

            // 分组索引映射回全局索引
            Channel targetChannel = currentGroupChannelList.get(newGroupIndex);
            int globalIndex = channelSourceList.indexOf(targetChannel);
            if (globalIndex != -1) {
                switchManager.setCurrentIndex(globalIndex);
                playChannel(globalIndex);
            }
            return;
        }

        // 无分组时走全局切换逻辑
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 切换到下一个频道
     * 支持分组内循环、反向切换、冷却防抖
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        // 有选中分组时，在分组内循环切换
        if (!TextUtils.isEmpty(nowSelectGroup) && !currentGroupChannelList.isEmpty()) {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            int groupIndex = currentGroupChannelList.indexOf(currentChannel);
            if (groupIndex == -1) groupIndex = 0;

            int newGroupIndex;
            if (channel_reverse) {
                // 反向模式：下一台 = 索引-1
                newGroupIndex = groupIndex - 1;
                if (newGroupIndex < 0) {
                    newGroupIndex = currentGroupChannelList.size() - 1;
                }
            } else {
                // 正常模式：下一台 = 索引+1
                newGroupIndex = groupIndex + 1;
                if (newGroupIndex >= currentGroupChannelList.size()) {
                    newGroupIndex = 0;
                }
            }

            Channel targetChannel = currentGroupChannelList.get(newGroupIndex);
            int globalIndex = channelSourceList.indexOf(targetChannel);
            if (globalIndex != -1) {
                switchManager.setCurrentIndex(globalIndex);
                playChannel(globalIndex);
            }
            return;
        }

        // 无分组时走全局切换逻辑
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 播放指定全局索引的频道
     * 包含：边界校验、信息栏更新、EPG刷新、地址重定向处理
     */
    public void playChannel(int index) {
        // 空数据校验
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        // 索引边界保护，防止数组越界
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) return;

        // 更新状态监听的频道名称
        playerStateListener.setCurrentChannelName(ch.getName());
        // 显示频道数字角标
        showChannelNum(index + 1);
        // 持久化保存当前播放位置
        appConfig.setLastPlayIndex(index);

        // 更新频道列表选中状态
        if (!TextUtils.isEmpty(nowSelectGroup)) {
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, index);
        } else {
            channelListManager.setChannels(channelSourceList, index);
        }

        // 刷新EPG节目单
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 更新并显示自定义信息栏，2秒后自动隐藏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 2000);
            tv_channel_name.setText(ch.getName());
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
        }

        // 子线程处理播放地址重定向，避免阻塞主线程
        final String originalUrl = ch.getPlayUrl();
        new Thread(() -> {
            java.net.HttpURLConnection conn = null;
            String finalUrl = originalUrl;
            try {
                // 最多跟随10次301/302重定向
                for (int step = 0; step < MAX_REDIRECT_COUNT; step++) {
                    java.net.URL urlObj = new java.net.URL(finalUrl);
                    conn = (java.net.HttpURLConnection) urlObj.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", DEF_UA);
                    conn.setRequestProperty("Refer", DEF_REFER);
                    conn.setInstanceFollowRedirects(false); // 手动处理重定向
                    int code = conn.getResponseCode();
                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        if (loc != null) finalUrl = loc;
                        conn.disconnect();
                        conn = null;
                    } else break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (conn != null) conn.disconnect();
            }

            final String realPlayUrl = TextUtils.isEmpty(finalUrl) ? originalUrl : finalUrl;
            // 切回主线程执行播放
            new Handler(Looper.getMainLooper()).post(() -> {
                mPlayerManager.playUrl(realPlayUrl);
            });
        }).start();
    }

    /**
     * 显示频道数字角标，3秒后自动隐藏
     */
    public void showChannelNum(int num) {
        if (!number_channel_enable) return;
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            tv_channel_num.setVisibility(View.GONE);
        }, 3000);
    }

    /**
     * 切换侧边频道面板的显示/隐藏
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    /**
     * 打开设置页面
     */
    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    /**
     * 接收外部传入的自定义直播源/EPG地址并重新加载
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appConfig.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        runOnUiThread(this::loadLiveAndEpg);
    }

    /**
     * 遥控器按键事件分发
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    // ====================== 生命周期方法 ======================
    @Override
    protected void onPause() {
        super.onPause();
        // 切后台：暂停播放、解绑播放器视图
        if (mPlayerManager != null) mPlayerManager.onBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 切前台：重新加载设置、应用画面比例、恢复播放
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null) mPlayerManager.onForeground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 安全注销广播、释放播放器、清空单例
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        if (mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }

    /**
     * 预留外部播放入口
     */
    public void playUrl(String url) {
    }
}
