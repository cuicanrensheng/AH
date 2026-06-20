package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.*;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播主页面 Activity
 *
 * 【核心职责】
 * 1. 页面生命周期管理
 * 2. 各 Manager 的初始化和协调
 * 3. 按键事件分发
 * 4. 播放器视图绑定
 *
 * 【防花屏说明】
 * 1. PlayerView 设置 keep_content_on_player_reset="true"，暂停时保持最后一帧
 * 2. 退到后台时显示黑色占位图，盖住 SurfaceView，防止花屏
 * 3. 回到前台后延迟 100ms 隐藏占位图，等 Surface 准备好
 *
 * 【兼容层说明】
 * 为了兼容其他类（GestureManager、KeyEventManager、ChannelListActivity 等）
 * 对旧的方法和变量的调用，保留了以下兼容接口：
 * - 方法：togglePanel()、playPrev()、playNext()、playChannel(int)
 * - 变量：channelSourceList、currentPlayIndex
 * 这些接口内部都委托给对应的 Manager，外部调用方式不变。
 *
 * 【2026-06-19 优化：两个完整面板切换 + 焦点管理】
 * 原左右面板切换模式（只有日期+EPG）改为两个完整面板切换：
 * - 左侧面板：分组列表 + 频道列表 + 节目单按钮（默认显示）
 * - 右侧面板：返回按钮 + 频道列表 + 日期 + EPG（默认隐藏）
 * - 两个面板都有频道列表，切换时选中状态保持同步
 * - 节目单页面也能直接切换频道，不用切回去
 *
 * 【按键分发说明】
 * 按键事件按以下优先级分发：
 * 1. 数字选台（ChannelNumberManager）- 数字键
 * 2. 频道面板（ChannelPanelController）- 左右键、OK键（面板打开时）
 * 3. 方向键切台（handleDirectionKey）- 上下键（面板关闭时）
 * 4. 按键事件管理（KeyEventManager）- 其他按键
 *
 * 【2026-06-20 修改：打开设置页面时不显示占位图】
 * 【问题原因】
 * 打开设置页面时，MainActivity 会走 onPause()，然后显示黑色占位图。
 * 但设置页面是透明主题，背景透过来就会看到黑色占位图，看不到播放画面。
 *
 * 【解决方案】
 * 新增 isOpeningSettings 标志位：
 * - 打开设置前设为 true → onPause 时不显示占位图
 * - onResume 时重置为 false → 下次退到后台还是会显示占位图
 *
 * 【效果】
 * - 按 Home 键退到后台 → 显示占位图，防花屏 ✅
 * - 打开设置页面 → 不显示占位图，能看到播放画面 ✅
 */
public class MainActivity extends AppCompatActivity {

    // ====================== 单例 ======================
    /** Activity 单例，供其他类访问 */
    public static MainActivity mInstance;

    // ====================================================================
    // ✅ 兼容层：保留旧的 public 变量，供其他类直接访问
    // ====================================================================
    /**
     * 所有频道数据源列表（全部频道，未筛选）
     * 【兼容说明】内部数据来自 appCoreManager，外部访问方式不变
     */
    public List<Channel> channelSourceList = new ArrayList<>();

    /**
     * 当前正在播放的频道索引（全局索引，对应 channelSourceList）
     * 【兼容说明】内部数据来自 channelPanelController，外部访问方式不变
     */
    public int currentPlayIndex = 0;

    // ====================== 视图相关 ======================
    /** 播放器视图（ExoPlayer 的 PlayerView） */
    private PlayerView playerView;

    // ====================================================================
    // ✅ 防花屏：播放器占位图（退到后台时显示，盖住 SurfaceView）
    // ====================================================================
    /**
     * 播放器占位图
     * 【作用】退到后台时显示黑色背景，盖住 SurfaceView，防止 Surface 销毁时花屏
     * 【时机】onPause 时显示，onResume 后延迟 100ms 隐藏
     */
    private ImageView ivPlayerPlaceholder;

    // ====================== 管理器相关 ======================
    /** 播放器管理器（单例，基于 ExoPlayer 封装） */
    public TVPlayerManager mPlayerManager;
    /** 应用配置管理（SP 封装） */
    private AppConfig appConfig;
    /** 屏幕比例管理（全屏/填充/原始） */
    private ScreenRatioManager screenRatioManager;
    /** 手势管理（滑动、点击等手势处理） */
    private GestureManager gestureManager;
    /** 按键事件管理（遥控器按键分发） */
    private KeyEventManager keyEventManager;
    /** 播放器状态监听器（空实现，不弹 Toast） */
    private PlayerStateListenerImpl playerStateListener;

    // ====================================================================
    // 拆分新增：各个 Manager
    // ====================================================================
    /** 数字选台管理器 */
    private ChannelNumberManager channelNumberManager;
    /** 显示管理器（全面屏适配 + 加载动画） */
    private DisplayManager displayManager;
    /** 信息展示管理器（频道号 + 信息栏 + EPG 节目单） */
    private InfoDisplayManager infoDisplayManager;
    /** 频道面板控制器（分组 + 频道切换 + 面板控制 + 焦点管理） */
    private ChannelPanelController channelPanelController;
    /** 应用核心管理器（数据加载 + 广播 + 生命周期） */
    private AppCoreManager appCoreManager;

    // ====================== 状态标志 ======================
    /** 频道切换是否反向（上键=下一台，下键=上一台） */
    private boolean channel_reverse;
    /** 数字选台是否启用 */
    private boolean number_channel_enable;

    // ====================================================================
    // ✅ 新增：打开设置页面的标志位
    // ====================================================================
    /**
     * 是否正在打开设置页面
     *
     * 【作用】
     * 打开设置页面时，MainActivity 会走 onPause() 生命周期，
     * 但这时候不应该显示占位图，因为设置页面是透明主题，
     * 用户需要看到后面的播放画面。
     *
     * 【true = 正在打开设置，不显示占位图】
     * 【false = 正常退到后台，显示占位图防花屏】
     *
     * 【设置时机】
     * - openSettings() 中设为 true（打开设置前）
     * - onResume() 中重置为 false（从设置页面回来后）
     *
     * 【为什么需要这个标志位？】
     * 如果没有这个标志位，打开设置页面时：
     * 1. MainActivity.onPause() 被调用
     * 2. showPlayerPlaceholder() 显示黑色占位图
     * 3. 设置页面透明背景透过来 → 看到的是黑色占位图，不是播放画面
     *
     * 有了这个标志位后：
     * 1. openSettings() 设 isOpeningSettings = true
     * 2. MainActivity.onPause() 被调用
     * 3. 判断 !isOpeningSettings → 不显示占位图
     * 4. 设置页面透明背景透过来 → 能看到播放画面 ✅
     */
    private boolean isOpeningSettings = false;

    // ====================== 其他 ======================
    /** 本地日志列表（保留最近 100 条，供其他类访问） */
    public static List<String> logList = new ArrayList<>();

    // ====================== onCreate 生命周期 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");
        mInstance = this;

        // ===== 自动旋转横屏 =====
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // 全面屏适配
        displayManager = new DisplayManager(this);
        displayManager.applyFullScreen();

        // 加载布局
        setContentView(R.layout.activity_main);

        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 信息展示管理器初始化
        initInfoDisplayManager();

        // 初始化配置
        appConfig = AppConfig.getInstance(this);
        loadSettings();

        // 应用自定义直播源/EPG 地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        // ====================================================================
        // ✅ 绑定播放器视图 + 占位图
        // ====================================================================
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        // 绑定防花屏占位图
        ivPlayerPlaceholder = findViewById(R.id.iv_player_placeholder);

        // 频道面板控制器初始化
        initChannelPanelController();

        // 播放器初始化
        initPlayer();

        // 屏幕比例
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 手势管理
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        // 按键事件管理
        keyEventManager = new KeyEventManager(this);

        // 恢复上次播放的频道索引
        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        log("【播放】记录上次播放索引：" + currentPlayIndex);

        // 数字选台管理器初始化
        initChannelNumberManager();

        // 应用核心管理器初始化
        initAppCoreManager();

        // 显示加载动画
        displayManager.showLoading("正在加载直播源...");

        // 加载直播源和 EPG
        appCoreManager.loadLiveAndEpg();
    }

    // ====================================================================
    // 信息展示管理器初始化
    // ====================================================================
    private void initInfoDisplayManager() {
        TextView tv_channel_num = findViewById(R.id.tv_channel_num);
        View info_bar = findViewById(R.id.info_bar);
        TextView tv_channel_name = findViewById(R.id.tv_channel_name);
        TextView tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        TextView tv_tag_audio = findViewById(R.id.tv_tag_audio);
        TextView tv_bitrate = findViewById(R.id.tv_bitrate);
        TextView tv_current_program_name = findViewById(R.id.tv_current_program_name);
        TextView tv_current_time_range = findViewById(R.id.tv_current_time_range);
        ProgressBar progress_program = findViewById(R.id.progress_program);
        TextView tv_remaining_time = findViewById(R.id.tv_remaining_time);
        TextView tv_next_program_name = findViewById(R.id.tv_next_program_name);
        TextView tv_next_time_range = findViewById(R.id.tv_next_time_range);

        infoDisplayManager = new InfoDisplayManager(
                this,
                tv_channel_num,
                info_bar,
                tv_channel_name,
                tv_tag_fhd,
                tv_tag_audio,
                tv_bitrate,
                tv_current_program_name,
                tv_current_time_range,
                progress_program,
                tv_remaining_time,
                tv_next_program_name,
                tv_next_time_range
        );
    }

    // ====================================================================
    // 频道面板控制器初始化
    // ====================================================================
    /**
     * 初始化频道面板控制器
     *
     * 【两个完整面板切换】
     * 原左右面板切换模式（只有日期+EPG）改为两个完整面板切换：
     * - 左侧面板：分组 + 频道列表 + 节目单按钮
     * - 右侧面板：返回按钮 + 频道列表 + 日期 + EPG
     *
     * 【新增内容】
     * 1. 新增节目单页面的频道列表（lv_channel_list_epg）
     * 2. 新增返回分组按钮（btn_back_group）
     * 3. 新增节目单页面的频道列表管理器（channelListManagerEpg）
     * 4. ChannelPanelController 构造函数新增 3 个参数
     */
    private void initChannelPanelController() {
        // ===== 面板根布局 =====
        View panel_layout = findViewById(R.id.panel_layout);

        // ================================================================
        // 左右面板容器
        // ================================================================
        View ll_left_panel = findViewById(R.id.ll_left_panel);
        View ll_right_panel = findViewById(R.id.ll_right_panel);

        // ================================================================
        // 列表控件
        // ================================================================
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);

        // ================================================================
        // ✅ 新增：节目单页面的频道列表
        // ================================================================
        // 【作用】
        // 右侧面板（节目单页面）也有一个频道列表，用户在看节目单时
        // 可以直接切换频道，不用切回左侧面板。
        //
        // 【布局 ID】
        // lv_channel_list_epg：节目单页面的频道列表，在 ll_right_panel 里面
        ListView lvChannelListEpg = findViewById(R.id.lv_channel_list_epg);

        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);

        // ================================================================
        // 按钮控件
        // ================================================================
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // ================================================================
        // ✅ 新增：返回分组按钮
        // ================================================================
        // 【作用】
        // 右侧面板（节目单页面）最左边的返回按钮，点击后切回左侧面板。
        // 文字是竖排的"频道组"。
        //
        // 【布局 ID】
        // btn_back_group：返回按钮，在 ll_right_panel 最左边
        TextView btn_back_group = findViewById(R.id.btn_back_group);

        // ================================================================
        // 子管理器初始化
        // ================================================================
        EpgManager.getInstance(this);

        // 主页面频道列表管理器（左侧面板用）
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);

        // ================================================================
        // ✅ 新增：节目单页面频道列表管理器
        // ================================================================
        // 【作用】
        // 管理右侧面板的频道列表，和左侧面板的频道列表管理器是两个独立的实例，
        // 分别管理各自的 ListView，但数据保持同步。
        //
        // 【为什么需要两个管理器？】
        // 因为有两个 ListView，每个 ListView 需要自己的 Adapter 和选中状态管理。
        // 两个管理器的数据来源相同，切台时同时更新两边的选中状态。
        ChannelListManager channelListManagerEpg = new ChannelListManager(this, lvChannelListEpg);

        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        DateListManager dateListManager = new DateListManager(this, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 日期列表初始化
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            channelPanelController.setCurrentDateIndex(pos);
        });

        // ================================================================
        // 创建频道面板控制器
        // ================================================================
        channelPanelController = new ChannelPanelController(
                this,
                panel_layout,
                ll_left_panel,
                ll_right_panel,
                lvGroup,
                lvChannelList,
                lvChannelListEpg,        // ✅ 新增：节目单页面频道列表
                lvDate,
                lvEpg,
                btn_show_epg,
                btn_back_group,          // ✅ 新增：返回分组按钮
                groupListManager,
                channelListManager,
                channelListManagerEpg,   // ✅ 新增：节目单页面频道管理器
                dateListManager,
                epgManagerWrapper,
                panelManager
        );

        // 设置频道切换监听器
        channelPanelController.setOnChannelChangeListener(new ChannelPanelController.OnChannelChangeListener() {
            @Override
            public void onChannelChanged(Channel channel, int index) {
                playChannel(channel, index);
            }
        });
    }

    // ====================================================================
    // 播放器初始化
    // ====================================================================
    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                infoDisplayManager.updateLiveInfo(info);
            }
        });
    }

    // ====================================================================
    // 数字选台管理器初始化
    // ====================================================================
    private void initChannelNumberManager() {
        channelNumberManager = new ChannelNumberManager(
                new ChannelNumberManager.OnChannelNumberListener() {
                    @Override
                    public void onChannelSelected(int channelIndex) {
                        channelPanelController.playChannel(channelIndex);
                    }
                    @Override
                    public void showChannelNumber(String number) {
                        infoDisplayManager.showChannelNum(Integer.parseInt(number));
                    }
                    @Override
                    public void hideChannelNumber() {
                        infoDisplayManager.hideChannelNum();
                    }
                },
                number_channel_enable
        );
    }

    // ====================================================================
    // 应用核心管理器初始化
    // ====================================================================
    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveSourceLoaded(List<Channel> channels, boolean fromCache) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // ✅ 同步到兼容变量 channelSourceList
                        channelSourceList.clear();
                        channelSourceList.addAll(channels);
                        // 更新频道面板
                        channelPanelController.setChannels(channels);
                        // 设置数字选台的总频道数
                        channelNumberManager.setTotalChannelCount(channels.size());
                        // 如果还没用缓存播放过，就播放
                        if (!appCoreManager.hasPlayedWithCache()) {
                            if (currentPlayIndex >= 0 && currentPlayIndex < channels.size()) {
                                Channel ch = channels.get(currentPlayIndex);
                                playChannel(ch, currentPlayIndex);
                                appCoreManager.setHasPlayedWithCache(true);
                            }
                        }
                        // 隐藏加载动画
                        displayManager.hideLoading();
                        log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channels.size());
                    }
                });
            }
            @Override
            public void onLiveSourceFailed(String errorMsg) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (channelSourceList.isEmpty()) {
                            displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                            SettingsActivity.logOperation("【加载】直播源加载失败：" + errorMsg);
                        } else {
                            log("【缓存】使用缓存数据继续播放");
                            displayManager.hideLoading();
                        }
                    }
                });
            }
            @Override
            public void onEpgLoaded() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                            Channel curr = channelSourceList.get(currentPlayIndex);
                            infoDisplayManager.updateEpgInfo(curr);
                        }
                    }
                });
            }
            @Override
            public void onLoadTimeout(boolean hasData) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        log("【加载】超时，自动隐藏加载动画");
                        if (!hasData) {
                            displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                            SettingsActivity.logOperation("【加载】直播源加载超时");
                        }
                        displayManager.hideLoading();
                    }
                });
            }
        });
        appCoreManager.registerReceivers();
    }

    // ====================== 设置加载 ======================
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);
        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        }
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
        }
        log("【设置】EPG开关：" + epg_enable);
        log("【设置】切台反转：" + channel_reverse);
        log("【设置】数字选台：" + number_channel_enable);
        log("【设置】自动更新源：" + auto_update_source);
    }

    // ====================================================================
    // ✅ 兼容层：旧的 playChannel(int) 方法，供其他类调用
    // ====================================================================
    /**
     * 播放指定索引的频道（兼容旧接口）
     *
     * @param index 频道在 channelSourceList 中的全局索引
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    // ====================== 播放频道（内部方法） ======================
    /**
     * 播放指定频道（内部实现）
     *
     * @param channel 频道
     * @param index   全局索引
     */
    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        // ✅ 同步到兼容变量
        currentPlayIndex = index;
        log("========================================");
        log("【播放】频道名称：" + channel.getName());
        log("【播放】播放地址：" + channel.getPlayUrl());
        log("【播放】当前索引：" + index);
        log("========================================");
        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        // 先播放
        mPlayerManager.playUrl(channel.getPlayUrl());
        // 显示信息栏
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
        // ✅ 显示频道号（从 1 开始，用户看到的是 1、2、3...）
        infoDisplayManager.showChannelNum(index + 1);
    }

    // ====================================================================
    // ✅ 兼容层：旧的 togglePanel() 方法，供 GestureManager 等调用
    // ====================================================================
    /**
     * 切换频道面板显示/隐藏（兼容旧接口）
     */
    public void togglePanel() {
        channelPanelController.togglePanel();
    }

    // ====================================================================
    // ✅ 兼容层：旧的 playPrev() 方法，供 GestureManager 等调用
    // ====================================================================
    /**
     * 播放上一个频道（兼容旧接口）
     */
    public void playPrev() {
        channelPanelController.playPrev();
    }

    // ====================================================================
    // ✅ 兼容层：旧的 playNext() 方法，供 GestureManager 等调用
    // ====================================================================
    /**
     * 播放下一个频道（兼容旧接口）
     */
    public void playNext() {
        channelPanelController.playNext();
    }

    // ====================== 返回键处理 ======================
    @Override
    public void onBackPressed() {
        if (channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            return;
        }
        if (channelPanelController.handleBackPressed()) {
            playerView.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    // ====================== 方向键处理 ======================
    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (channel_reverse) {
                    playNext();
                } else {
                    playPrev();
                }
                SettingsActivity.logOperation("【切台】上键 → "
                        + (channel_reverse ? "下一台" : "上一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channel_reverse) {
                    playPrev();
                } else {
                    playNext();
                }
                SettingsActivity.logOperation("【切台】下键 → "
                        + (channel_reverse ? "上一台" : "下一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (channelNumberManager.isInputting()) {
                    channelNumberManager.confirmChannelNum();
                    return true;
                }
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                togglePanel();
                return true;
            default:
                return false;
        }
    }

    // ====================== 按键分发 ======================
    /**
     * 按键事件分发
     *
     * 【按键分发优先级】
     * 1. 数字选台（ChannelNumberManager）- 数字键 0-9
     * 2. 频道面板（ChannelPanelController）- 左右键、OK键（面板打开时）
     * 3. 方向键切台（handleDirectionKey）- 上下键（面板关闭时）
     * 4. 按键事件管理（KeyEventManager）- 其他按键
     *
     * 【为什么要先让频道面板处理？】
     * 因为频道面板打开时，左右键应该在面板内移动焦点，
     * 而不是切换频道面板的显示/隐藏。
     * 如果频道面板处理了这个按键，就直接返回 true，不再往下分发。
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 1. 先处理数字选台
        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        // ================================================================
        // ✅ 新增：再让频道面板处理按键（左右键、OK键）
        // ================================================================
        // 【为什么要在这里加？】
        // 因为频道面板有自己的焦点管理逻辑，需要处理左右键在面板内的焦点移动，
        // 以及OK键选中当前项。
        //
        // 【什么时候生效？】
        // 只有面板打开时才会处理，面板关闭时直接返回 false，
        // 不会影响正常的切台逻辑。
        //
        // 【如果频道面板处理了按键】
        // 直接返回 true，不再往下分发（不会再触发 handleDirectionKey）。
        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // 3. 再处理方向键切台（面板关闭时）
        if (handleDirectionKey(keyCode)) return true;

        // 4. 最后交给按键事件管理
        if (keyEventManager.dispatchKey(keyCode)) return true;

        return super.onKeyDown(keyCode, event);
    }

    // ====================== 打开设置页面 ======================
    /**
     * 打开设置页面
     *
     * 【2026-06-20 修改：设置 isOpeningSettings 标志位】
     * 打开设置前设为 true，这样 onPause() 时就不会显示占位图，
     * 设置页面透明背景透过来就能看到播放画面了。
     */
    public void openSettings() {
        // ✅ 设置标志位：正在打开设置，不显示占位图
        isOpeningSettings = true;
        log("【设置】打开设置页面，不显示占位图");

        appCoreManager.beforeOpenSettings();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ====================== 接收远程配置 ======================
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    // ====================== 生命周期方法 ======================
    // ====================================================================
    // ✅ 防花屏：退到后台前显示占位图（参照老版本，简单直接）
    // ====================================================================
    @Override
    protected void onPause() {
        super.onPause();

        // ====================================================================
        // 【2026-06-20 修改：打开设置页面时不显示占位图】
        // ====================================================================
        //
        // 【为什么要判断？】
        // 1. 正常退到后台（按 Home 键/切到其他应用）→ 显示占位图，防花屏
        // 2. 打开设置页面 → 不显示占位图，因为设置页面是透明的，用户要看播放画面
        //
        // 【怎么区分？】
        // 用 isOpeningSettings 标志位：
        // - 打开设置前设为 true → onPause 时不显示占位图
        // - onResume 时重置为 false → 下次退到后台还是会显示
        //
        // 【如果没有这个判断会怎样？】
        // 打开设置页面时，MainActivity 走 onPause()，然后显示黑色占位图。
        // 设置页面是透明主题，背景透过来就会看到黑色占位图，看不到播放画面。
        // 用户就会觉得"被占位图黑屏盖住了"。
        if (!isOpeningSettings) {
            // 正常退到后台 → 显示占位图，防花屏
            showPlayerPlaceholder();
        } else {
            // 打开设置页面 → 不显示占位图，让设置页面能看到播放画面
            log("【防花屏】打开设置页面，不显示占位图");
        }

        appCoreManager.onPause();
    }

    // ====================================================================
    // ✅ 防花屏：回到前台后延迟隐藏占位图（参照老版本，简单直接）
    // ====================================================================
    @Override
    protected void onResume() {
        super.onResume();

        // ====================================================================
        // 【2026-06-20 修改：重置打开设置的标志位】
        // ====================================================================
        // 从设置页面回来后，重置标志位。
        // 下次退到后台（按 Home 键）还是会正常显示占位图。
        isOpeningSettings = false;
        log("【设置】从设置页面返回，重置标志位");

        boolean resumed = appCoreManager.onResume();
        if (resumed) {
            loadSettings();
            screenRatioManager.apply();
        }
        displayManager.reapplyFullScreen();

        // 【防花屏】延迟 100ms 再隐藏占位图
        // 等 Surface 重新创建并准备好第一帧后，再隐藏占位图
        // 避免 Surface 还没准备好就隐藏，导致短暂黑屏/花屏
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                hidePlayerPlaceholder();
            }
        }, 500);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            displayManager.reapplyFullScreen();
        }
        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        if (infoDisplayManager != null) infoDisplayManager.release();
        if (channelNumberManager != null) channelNumberManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();
        mInstance = null;
    }

    // ====================================================================
    // ✅ 防花屏：占位图显示/隐藏方法（参照老版本，简单直接）
    // ====================================================================
    /**
     * 显示播放器占位图
     *
     * 【作用】用黑色背景盖住 SurfaceView，防止退到后台时 Surface 销毁导致花屏
     * 【调用时机】onPause() 时调用，在 super.onPause() 之后
     *
     * 【为什么能防花屏？】
     * SurfaceView 的 Surface 销毁是异步的，在销毁过程中可能出现：
     * 1. 花屏（显示垃圾数据）
     * 2. 绿屏（Surface 未初始化）
     * 3. 撕裂（部分显示旧帧，部分显示新帧）
     *
     * 用一个 ImageView 盖在 SurfaceView 上面，退到后台时显示黑色背景，
     * 这样用户看到的就是平滑的黑色过渡，而不是花屏。
     */
    private void showPlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.VISIBLE);
            log("【防花屏】显示占位图");
        }
    }

    /**
     * 隐藏播放器占位图
     *
     * 【作用】回到前台后，等 Surface 准备好再隐藏占位图
     * 【调用时机】onResume() 后延迟 100ms 调用
     *
     * 【为什么要延迟？】
     * Surface 的创建是异步的，onResume 时 Surface 可能还没准备好。
     * 如果这时候立刻隐藏占位图，可能会看到短暂的黑屏/花屏。
     * 延迟 100ms 等 Surface 准备好第一帧再隐藏，过渡更平滑。
     */
    private void hidePlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.GONE);
            log("【防花屏】隐藏占位图");
        }
    }

    // ====================== 日志工具 ======================
    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }
}
