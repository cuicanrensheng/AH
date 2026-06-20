package com.tv.live;

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
 * 3. 回到前台后延迟 2000ms 隐藏占位图，等 Surface 完全准备好
 * 4. 占位图显示移到 super.onPause() 前面，先盖住再销毁
 *
 * 【兼容层说明】
 * 为了兼容其他类对旧的方法和变量的调用，保留了以下兼容接口：
 * - 方法：togglePanel()、playPrev()、playNext()、playChannel(int)
 * - 变量：channelSourceList、currentPlayIndex
 * 这些接口内部都委托给对应的 Manager，外部调用方式不变。
 *
 * 【2026-06-20 优化：两个完整面板切换 + 焦点管理】
 * 原左右面板切换模式改为两个完整面板切换：
 * - 左侧面板：分组列表 + 频道列表 + 节目单按钮（默认显示）
 * - 右侧面板：返回按钮 + 频道列表 + 日期 + EPG（默认隐藏）
 * - 两个面板都有频道列表，切换时选中状态保持同步
 *
 * 【2026-06-20 优化：按键处理统一管理（合并 KeyEventManager）】
 * 【问题原因】
 * 之前按键处理分散在三个地方：
 * 1. ChannelPanelController - 面板打开时的左右键、OK键
 * 2. handleDirectionKey() - 面板关闭时的上下键
 * 3. KeyEventManager - 其他按键（Menu键等）
 * 分散管理容易出现逻辑不同步的问题，比如换台反转失效。
 *
 * 【解决方案】
 * 把 KeyEventManager 的功能全部合并到 ChannelPanelController 里，
 * 所有按键统一走 channelPanelController.dispatchKeyEvent() 入口，
 * 面板打开/关闭都能处理，反转逻辑统一管理。
 *
 * 【合并后效果】
 * 1. 反转逻辑统一管理：所有切台入口都走 switchUp()/switchDown()，反转肯定生效
 * 2. 减少文件：可以删掉 KeyEventManager.java
 * 3. 调用简单：MainActivity 只需要调用一个 dispatchKeyEvent() 方法
 * 4. 避免不同步：不会出现"KeyEventManager 改了，ChannelPanelController 没改"的情况
 *
 * 【按键分发优先级（合并后）】
 * 1. 数字选台（ChannelNumberManager）- 数字键 0-9
 * 2. 频道面板（ChannelPanelController）- 所有按键（面板打开/关闭都能处理）
 *    - 面板打开时：左右键移焦点、OK键选中、Menu键打开设置
 *    - 面板关闭时：上下键切台（带反转）、OK键打开面板、Menu键打开设置
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
     * 【时机】onPause 时显示（在 super.onPause() 之前），onResume 后延迟 2000ms 隐藏
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

    /**
     * 频道面板控制器（分组 + 频道切换 + 面板控制 + 焦点管理 + 反转 + 按键）
     * 
     * 【2026-06-20 合并说明】
     * 已合并 ChannelSwitchManager 和 KeyEventManager 的全部功能：
     * - 频道切换（上/下切台、分组内循环、防抖、反转）
     * - 按键处理（面板打开/关闭都能处理，统一入口）
     */
    private ChannelPanelController channelPanelController;

    /** 应用核心管理器（数据加载 + 广播 + 生命周期） */
    private AppCoreManager appCoreManager;

    // ====================== 状态标志 ======================

    /**
     * 频道切换是否反向（上键=下一台，下键=上一台）
     *
     * 【说明】
     * 保留这个变量是为了：
     * 1. 兼容旧代码直接访问这个变量
     * 2. 日志输出时用（显示"上键→下一台"还是"上键→上一台"）
     *
     * 实际的反转逻辑已经统一由 ChannelPanelController 管理，
     * loadSettings() 时会同步到 channelPanelController.setReverse()。
     */
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
     */
    private boolean isOpeningSettings = false;

    // ====================================================================
    // ✅ 新增：频道面板自动隐藏
    // ====================================================================

    /**
     * Handler 用于延迟隐藏面板
     */
    private Handler mPanelAutoHideHandler = new Handler(Looper.getMainLooper());

    /**
     * 自动隐藏面板的 Runnable
     */
    private Runnable mPanelAutoHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelPanelController != null) {
                channelPanelController.hidePanel();
            }
        }
    };

    /**
     * 是否是首次打开 app
     *
     * 【true = 首次打开，3 秒后自动隐藏】
     * 【false = 非首次，不自动隐藏】
     */
    private boolean mIsFirstLaunch = true;

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

        // 自动旋转横屏
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

        // ====================================================================
        // ✅ 新增：首次打开时，3 秒后自动隐藏面板
        // ====================================================================
        if (mIsFirstLaunch) {
            mPanelAutoHideHandler.postDelayed(mPanelAutoHideRunnable, 3000);
            mIsFirstLaunch = false;
        }

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

        // ====================================================================
        // ✅ 合并 KeyEventManager 后：删除 KeyEventManager 初始化
        // ====================================================================
        // 【原来的代码】keyEventManager = new KeyEventManager(this);
        // 【为什么删除？】
        // KeyEventManager 的功能已经全部合并到 ChannelPanelController 里了，
        // 所有按键统一走 channelPanelController.dispatchKeyEvent()，
        // 不需要单独的 KeyEventManager 了。

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
     * - 左侧面板：分组 + 频道列表 + 节目单按钮
     * - 右侧面板：返回按钮 + 频道列表 + 日期 + EPG
     *
     * 【2026-06-20 新增：设置面板动作监听器】
     * 【作用】
     * ChannelPanelController 合并 KeyEventManager 后，
     * Menu 键打开设置的功能也移到了 ChannelPanelController 里，
     * 通过回调接口通知 MainActivity 打开设置页面。
     *
     * 【为什么用回调而不是直接引用？】
     * 解耦：ChannelPanelController 不需要知道 MainActivity 的存在，
     * 只需要通过回调通知外部，外部自己决定怎么处理。
     */
    private void initChannelPanelController() {
        // 面板根布局
        View panel_layout = findViewById(R.id.panel_layout);

        // 左右面板容器
        View ll_left_panel = findViewById(R.id.ll_left_panel);
        View ll_right_panel = findViewById(R.id.ll_right_panel);

        // 列表控件
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvChannelListEpg = findViewById(R.id.lv_channel_list_epg);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);

        // 按钮控件
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);
        TextView btn_back_group = findViewById(R.id.btn_back_group);

        // 子管理器初始化
        EpgManager.getInstance(this);

        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
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

        // 创建频道面板控制器
        channelPanelController = new ChannelPanelController(
                this,
                panel_layout,
                ll_left_panel,
                ll_right_panel,
                lvGroup,
                lvChannelList,
                lvChannelListEpg,
                lvDate,
                lvEpg,
                btn_show_epg,
                btn_back_group,
                groupListManager,
                channelListManager,
                channelListManagerEpg,
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

        // ====================================================================
        // ✅ 新增：设置面板动作监听器（处理打开设置等回调）
        // ====================================================================
        //
        // 【为什么要新增这个监听器？】
        // KeyEventManager 合并到 ChannelPanelController 后，
        // Menu 键打开设置的功能也移到了 ChannelPanelController 里。
        // 但是 ChannelPanelController 不能直接启动 SettingsActivity，
        // 需要通过回调通知 MainActivity 来处理。
        //
        // 【回调时机】
        // 用户按 Menu 键时，ChannelPanelController 会调用 onOpenSettings()。
        //
        // 【处理逻辑】
        // 直接调用 MainActivity 自己的 openSettings() 方法，
        // 保持和原来的行为一致。
        channelPanelController.setOnPanelActionListener(new ChannelPanelController.OnPanelActionListener() {
            @Override
            public void onOpenSettings() {
                // 调用 MainActivity 自己的 openSettings() 方法
                openSettings();
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
                        // 同步到兼容变量 channelSourceList
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

    /**
     * 加载设置
     *
     * 【从 SP 读取的设置】
     * 1. EPG 开关
     * 2. 换台反转
     * 3. 数字选台
     * 4. 自动更新源
     *
     * 【同步反转设置到 ChannelPanelController】
     * 统一由 ChannelPanelController 管理反转，
     * loadSettings() 时把反转设置同步过去。
     */
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
            // 同步反转设置到 ChannelPanelController
            channelPanelController.setReverse(channel_reverse);
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

        // 同步到兼容变量
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

        // 显示频道号（从 1 开始，用户看到的是 1、2、3...）
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
     *
     * 【注意】这是底层方法，直接切换到上一台，不考虑反转。
     * 如果需要考虑反转，请调用 channelPanelController.switchUp()。
     */
    public void playPrev() {
        channelPanelController.playPrev();
    }

    // ====================================================================
    // ✅ 兼容层：旧的 playNext() 方法，供 GestureManager 等调用
    // ====================================================================

    /**
     * 播放下一个频道（兼容旧接口）
     *
     * 【注意】这是底层方法，直接切换到下一台，不考虑反转。
     * 如果需要考虑反转，请调用 channelPanelController.switchDown()。
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

    // ====================== 方向键处理（保留，兼容旧代码） ======================

    /**
     * 处理方向键（面板关闭时切台）
     *
     * 【2026-06-20 说明】
     * 合并 KeyEventManager 后，这个方法理论上可以删掉了，
     * 因为 ChannelPanelController.dispatchKeyEvent() 已经能处理所有情况。
     *
     * 【为什么保留？】
     * 为了兼容其他可能直接调用 handleDirectionKey() 的地方，
     * 比如 GestureManager 或者其他类。
     *
     * 【实际使用】
     * 合并后，onKeyDown() 里不会再调用这个方法了，
     * 所有按键都走 channelPanelController.dispatchKeyEvent()。
     *
     * @param keyCode 按键码
     * @return 是否处理了按键
     */
    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                channelPanelController.switchUp();
                SettingsActivity.logOperation("【切台】上键 → "
                        + (channel_reverse ? "下一台" : "上一台"));
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                channelPanelController.switchDown();
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

    // ====================== 按键分发（合并 KeyEventManager 后简化版） ======================

    /**
     * 按键事件分发
     *
     * 【2026-06-20 优化：合并 KeyEventManager 后简化】
     * 原来的按键分发有 4 层：
     * 1. 数字选台
     * 2. 频道面板（面板打开时）
     * 3. 方向键切台（面板关闭时）
     * 4. KeyEventManager（其他按键）
     *
     * 合并后简化为 2 层：
     * 1. 数字选台（ChannelNumberManager）- 数字键 0-9
     * 2. 频道面板（ChannelPanelController）- 所有按键（面板打开/关闭都能处理）
     *    - 面板打开时：左右键移焦点、OK键选中、Menu键打开设置
     *    - 面板关闭时：上下键切台（带反转）、OK键打开面板、Menu键打开设置
     *
     * 【为什么简化？】
     * 因为 KeyEventManager 的功能已经全部合并到 ChannelPanelController 里了，
     * ChannelPanelController 的 dispatchKeyEvent() 方法面板打开/关闭都能处理，
     * 不需要再分多层了。
     *
     * 【好处】
     * 1. 代码更简洁：onKeyDown() 里只有 2 个 if 判断
     * 2. 逻辑更清晰：所有按键逻辑都在 ChannelPanelController 里
     * 3. 反转更可靠：所有切台入口都走 switchUp()/switchDown()，反转肯定生效
     *
     * @param keyCode 按键码
     * @param event   按键事件
     * @return 是否处理了按键
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ====================================================================
        // ✅ 用户有按键操作时，取消自动隐藏
        // ====================================================================
        cancelPanelAutoHide();

        // 1. 先处理数字选台（优先级最高）
        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        // ====================================================================
        // ✅ 合并后：统一交给 ChannelPanelController 处理所有按键
        // ====================================================================
        //
        // 【为什么只需要这一个调用？】
        // 因为 KeyEventManager 的功能已经全部合并到 ChannelPanelController 里了，
        // dispatchKeyEvent() 方法面板打开/关闭都能处理：
        //
        // 面板打开时：
        //   - 左右键：移动焦点
        //   - OK键：选中当前项
        //   - Menu键：打开设置
        //   - 上下键：ListView 自己处理（返回 false，让系统处理）
        //
        // 面板关闭时：
        //   - 上下键：切台（带反转）
        //   - OK键：打开面板
        //   - 左右键：打开面板
        //   - Menu键：打开设置
        //
        // 【如果 ChannelPanelController 处理了按键】
        // 直接返回 true，不再往下分发。
        //
        // 【如果 ChannelPanelController 没处理】
        // 继续往下走，交给系统默认处理。
        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // ====================================================================
        // ✅ 合并后：删除 handleDirectionKey 和 keyEventManager 的调用
        // ====================================================================
        // 【原来的代码】
        // if (handleDirectionKey(keyCode)) return true;
        // if (keyEventManager.dispatchKey(keyCode)) return true;
        //
        // 【为什么删除？】
        // 因为这些功能都已经合并到 ChannelPanelController.dispatchKeyEvent() 里了，
        // 不需要再单独调用了。

        // 其他按键交给系统处理
        return super.onKeyDown(keyCode, event);
    }

    // ====================================================================
    // ✅ 取消频道面板自动隐藏
    // ====================================================================

    /**
     * 取消频道面板自动隐藏
     *
     * 【调用时机】
     * 用户有任何操作时调用，比如按键、点击面板等。
     */
    private void cancelPanelAutoHide() {
        if (mPanelAutoHideHandler != null && mPanelAutoHideRunnable != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        }
    }

    // ====================== 打开设置页面 ======================

    /**
     * 打开设置页面
     *
     * 【设置 isOpeningSettings 标志位】
     * 打开设置前设为 true，这样 onPause() 时就不会显示占位图，
     * 设置页面透明背景透过来就能看到播放画面了。
     */
    public void openSettings() {
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
    // ✅ 防花屏优化：退到后台前先显示占位图（先盖住再销毁）
    // ====================================================================

    /**
     * 页面暂停回调（退到后台时调用）
     *
     * 【优化】
     * 把 showPlayerPlaceholder() 移到 super.onPause() 前面执行。
     *
     * 【为什么要移到前面？】
     * super.onPause() 会触发 Surface 销毁，
     * 如果在 super.onPause() 之后才显示占位图，
     * Surface 已经开始销毁了，那一瞬间还是会看到花屏。
     *
     * 【移到前面的效果】
     * 先显示占位图，盖住 SurfaceView，
     * 然后再调用 super.onPause() 销毁 Surface，
     * 这样销毁过程完全被占位图盖住，用户看不到花屏。
     */
    @Override
    protected void onPause() {
        // 先判断是否需要显示占位图
        if (!isOpeningSettings) {
            // 正常退到后台 → 显示占位图，防花屏
            showPlayerPlaceholder();
        } else {
            // 打开设置页面 → 不显示占位图，让设置页面能看到播放画面
            log("【防花屏】打开设置页面，不显示占位图");
        }

        super.onPause();
        appCoreManager.onPause();
    }

    // ====================================================================
    // ✅ 防花屏优化：回到前台后延迟 2000ms 隐藏占位图
    // ====================================================================

    /**
     * 页面恢复回调（从后台回到前台时调用）
     *
     * 【优化】
     * 把隐藏占位图的延迟从 500ms 改成 2000ms。
     *
     * 【为什么改成 2000ms？】
     * 原来的 500ms 可能不够，Surface 还没完全准备好第一帧，
     * 这时候隐藏占位图就会看到短暂的花屏/黑屏。
     *
     * 【修复：换台反转 + 屏幕比例失效】
     * 把 loadSettings() 和 screenRatioManager.apply() 移到 if (resumed) 外面，
     * 确保每次 onResume 都重新加载设置和应用屏幕比例。
     */
    @Override
    protected void onResume() {
        super.onResume();

        // 重置打开设置的标志位
        isOpeningSettings = false;
        log("【设置】从设置页面返回，重置标志位");

        boolean resumed = appCoreManager.onResume();

        // 每次 onResume 都重新加载设置和应用屏幕比例
        loadSettings();
        screenRatioManager.apply();

        displayManager.reapplyFullScreen();

        // 延迟 2000ms 再隐藏占位图
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                hidePlayerPlaceholder();
            }
        }, 2000);
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

        // 清理自动隐藏的 Handler，防止内存泄漏
        if (mPanelAutoHideHandler != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
            mPanelAutoHideHandler = null;
        }

        if (infoDisplayManager != null) infoDisplayManager.release();
        if (channelNumberManager != null) channelNumberManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();

        mInstance = null;
    }

    // ====================================================================
    // ✅ 防花屏：占位图显示/隐藏方法
    // ====================================================================

    /**
     * 显示播放器占位图
     *
     * 【作用】用黑色背景盖住 SurfaceView，防止退到后台时 Surface 销毁导致花屏
     * 【调用时机】onPause() 时调用，在 super.onPause() 之前
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
     * 【调用时机】onResume() 后延迟 2000ms 调用
     */
    private void hidePlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.GONE);
            log("【防花屏】隐藏占位图");
        }
    }

    // ====================== 日志方法 ======================

    /**
     * 记录日志
     *
     * @param msg 日志内容
     */
    private void log(String msg) {
        logList.add(msg);
        // 只保留最近 100 条
        if (logList.size() > 100) {
            logList.remove(0);
        }
        SettingsActivity.log(msg);
    }
}
