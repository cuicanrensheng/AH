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
 * 【2026-06-20 修改：去掉占位图】
 * 移除了 iv_player_placeholder 占位图相关的所有代码，
 * 包括成员变量、显示/隐藏方法、onPause/onResume 中的调用。
 *
 * 【为什么去掉？】
 * 占位图方案会导致一些问题（如抢焦点、遮挡画面等），
 * 改用 TextureView 方案来解决花屏问题，不再需要占位图。
 *
 * 【2026-06-20 修改：添加切台加载动画】
 * 切台开始时显示加载动画，第一帧渲染后隐藏。
 * 这样完全看不到花屏，体验更好。
 *
 * 【加载动画时机】
 * 1. playChannel() 中：切台开始 → 显示加载动画
 * 2. onFirstFrameRendered() 回调：第一帧渲染完成 → 隐藏加载动画
 * 3. onPlayError() 回调：播放错误 → 隐藏加载动画（避免一直转）
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
    // ✅ 标志位 - 是否正在打开设置页面
    // ====================================================================
    /**
     * 是否正在打开设置页面
     *
     * 【作用】
     * 区分"打开设置页面"和"真正退到后台"两种情况，
     * 用于日志输出和后续可能的逻辑判断。
     */
    private boolean isOpeningSettings = false;

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

    // ====================== 其他 ======================
    /** 本地日志列表（保留最近 100 条，供其他类访问） */
    public static List<String> logList = new ArrayList<>();

    // ====================== onCreate 生命周期 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        log("【主页】onCreate -> 页面创建");
        log("【花屏分析】========== APP 启动 ==========");
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
        // ✅ 绑定播放器视图
        // ====================================================================
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        log("【花屏分析】播放器视图绑定完成");

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
        ListView lvChannelListEpg = findViewById(R.id.lv_channel_list_epg);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);

        // ================================================================
        // 按钮控件
        // ================================================================
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);
        TextView btn_back_group = findViewById(R.id.btn_back_group);

        // ================================================================
        // 子管理器初始化
        // ================================================================
        EpgManager.getInstance(this);

        // 主页面频道列表管理器（左侧面板用）
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);

        // 节目单页面频道列表管理器
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
    }

    // ====================================================================
    // 播放器初始化
    // ====================================================================
    /**
     * 初始化播放器
     *
     * 【2026-06-20 修改：添加切台加载动画】
     * 设置自定义的 OnPlayStateListener，在第一帧渲染完成时隐藏加载动画。
     *
     * 【为什么不用 PlayerStateListenerImpl？】
     * PlayerStateListenerImpl 是空实现，我们需要自己处理加载动画的逻辑。
     * 但保留 playerStateListener 的引用，在新的监听器中调用它的方法，
     * 这样以后如果 PlayerStateListenerImpl 有了实际逻辑，也不会丢失。
     */
    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        // 创建 PlayerStateListenerImpl（保留原有引用，供其他地方使用）
        playerStateListener = new PlayerStateListenerImpl(this);

        // ====================================================================
        // ✅ 设置播放状态监听器（处理切台加载动画）
        // ====================================================================
        mPlayerManager.setOnPlayStateListener(new TVPlayerManager.OnPlayStateListener() {
            @Override
            public void onIdle() {
                // 空闲状态：转发给 PlayerStateListenerImpl
                if (playerStateListener != null) {
                    playerStateListener.onIdle();
                }
            }

            @Override
            public void onBuffering() {
                // 缓冲中：转发给 PlayerStateListenerImpl
                if (playerStateListener != null) {
                    playerStateListener.onBuffering();
                }
            }

            @Override
            public void onPlayReady() {
                // 播放就绪：转发给 PlayerStateListenerImpl
                if (playerStateListener != null) {
                    playerStateListener.onPlayReady();
                }
            }

            @Override
            public void onPlayEnd() {
                // 播放结束：转发给 PlayerStateListenerImpl
                if (playerStateListener != null) {
                    playerStateListener.onPlayEnd();
                }
            }

            @Override
            public void onPlayError(String msg) {
                // 播放错误：隐藏加载动画（避免一直转）
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        displayManager.hideLoading();
                    }
                });
                // 转发给 PlayerStateListenerImpl
                if (playerStateListener != null) {
                    playerStateListener.onPlayError(msg);
                }
            }

            // ====================================================================
            // ✅ 第一帧渲染完成：隐藏加载动画（关键！）
            // ====================================================================
            @Override
            public void onFirstFrameRendered() {
                // 第一帧渲染出来了，隐藏加载动画
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        displayManager.hideLoading();
                    }
                });
                // 转发给 PlayerStateListenerImpl
                if (playerStateListener != null) {
                    playerStateListener.onFirstFrameRendered();
                }
            }
        });

        // 直播信息更新监听
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                infoDisplayManager.updateLiveInfo(info);
            }
        });

        log("【花屏分析】播放器初始化完成");
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
     * 【2026-06-20 修改：添加切台加载动画】
     * 切台开始时显示加载动画，盖住画面，完全看不到花屏。
     *
     * 【加载动画时机】
     * 显示：切台开始时（playChannel 调用时）
     * 隐藏：第一帧渲染完成时（onFirstFrameRendered 回调）
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

        // ====================================================================
        // ✅ 切台开始：显示加载动画（盖住画面，看不到花屏）
        // ====================================================================
        // 【为什么要显示加载动画？】
        // 虽然我们已经用 TextureView 了，理论上不会花屏，
        // 但加上加载动画可以让切台体验更好，用户看到的是"正在切换"，
        // 而不是旧画面突然变成新画面，感觉更流畅。
        //
        // 【双重保险】
        // 即使 TextureView 有某些极端情况会花屏，加载动画也能盖住，
        // 完全看不到花屏，体验最好。
        displayManager.showLoading("正在切换频道...");

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
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ✅ 花屏日志：按键事件
        log("【花屏分析】收到按键：keyCode=" + keyCode);

        // 1. 先处理数字选台
        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        // 2. 再让频道面板处理按键（左右键、OK键）
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
     */
    public void openSettings() {
        // 设置标志位：正在打开设置页面
        isOpeningSettings = true;

        log("【花屏分析】打开设置页面");

        appCoreManager.beforeOpenSettings();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ====================== 接收远程配置 ======================
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    // ====================== 生命周期方法 ======================
    // ====================================================================
    // onPause
    // ====================================================================
    @Override
    protected void onPause() {
        super.onPause();

        log("【花屏分析】onPause 被调用");

        if (isOpeningSettings) {
            log("【花屏分析】打开设置页面");
            log("【主页】onPause -> 打开设置页面，继续播放");
        } else {
            log("【花屏分析】onPause -> 退到后台");
        }

        appCoreManager.onPause();
    }

    // ====================================================================
    // onResume
    // ====================================================================
    @Override
    protected void onResume() {
        super.onResume();

        // 重置标志位
        isOpeningSettings = false;

        boolean resumed = appCoreManager.onResume();
        if (resumed) {
            loadSettings();
            screenRatioManager.apply();
        }

        displayManager.reapplyFullScreen();

        log("【花屏分析】onResume -> 回到前台");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        log("【花屏分析】窗口焦点变化：" + (hasFocus ? "获得焦点" : "失去焦点"));

        if (hasFocus) {
            displayManager.reapplyFullScreen();
        }

        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        log("【主页】onDestroy -> 页面销毁");
        log("【花屏分析】========== APP 销毁 ==========");

        if (infoDisplayManager != null) infoDisplayManager.release();
        if (channelNumberManager != null) channelNumberManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();

        mInstance = null;
    }

    // ====================== 日志工具 ======================
    /**
     * 记录日志
     *
     * 【说明】
     * 1. 先添加到本地 logList（保留最近 100 条）
     * 2. 再同步到 SettingsActivity.PLAY_LOG（供设置页面查看）
     *
     * @param msg 日志内容
     */
    public static void log(String msg) {
        // 添加到本地列表（最新的在最前面）
        logList.add(0, msg);
        // 只保留最近 100 条
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        // 同步到 SettingsActivity，供日志查看器显示
        SettingsActivity.log(msg);
    }
}
