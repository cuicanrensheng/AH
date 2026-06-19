package com.tv.live;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
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
 * 【模块化拆分说明】
 * 为了避免 MainActivity 过于臃肿，已将以下功能拆分为独立 Manager：
 *
 * 【已拆分的 Manager】
 * - ChannelNumberManager：数字选台
 * - DisplayManager：全面屏适配 + 加载动画
 * - InfoDisplayManager：信息展示（频道号 + 信息栏 + EPG 节目单）
 * - ChannelPanelController：频道面板（分组 + 频道切换 + 面板控制）
 * - AppCoreManager：应用核心（数据加载 + 广播 + 生命周期）
 * - TVPlayerManager：播放器管理
 * - PanelManager：面板显示隐藏
 * - ChannelSwitchManager：频道切换
 * - GestureManager：手势处理
 * - KeyEventManager：按键事件分发
 * - ScreenRatioManager：屏幕比例
 * - CacheManager：缓存管理
 *
 * 【屏幕方向】
 * 使用 sensorLandscape，支持左横屏/右横屏自动旋转，
 * 但不会变成竖屏，保证观看体验。
 *
 * 【电视兼容说明】
 * 所有全面屏适配代码都加了 try-catch，
 * 即使电视不支持这些 API，也不会崩溃，只是不显示全屏效果而已。
 */
public class MainActivity extends AppCompatActivity {

    // ====================== 单例 ======================
    /** Activity 单例，供其他类访问 */
    public static MainActivity mInstance;

    // ====================== 视图相关 ======================
    /** 播放器视图（ExoPlayer 的 PlayerView） */
    private PlayerView playerView;

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
    // ✅ 拆分新增：数字选台管理器
    // ====================================================================
    /** 数字选台管理器（拆分自 MainActivity） */
    private ChannelNumberManager channelNumberManager;

    // ====================================================================
    // ✅ 拆分新增：显示管理器（全面屏 + 加载动画）
    // ====================================================================
    /** 显示管理器（全面屏适配 + 加载动画，拆分自 MainActivity） */
    private DisplayManager displayManager;

    // ====================================================================
    // ✅ 拆分新增：信息展示管理器（频道号 + 信息栏 + EPG 节目单）
    // ====================================================================
    /**
     * 信息展示管理器（拆分自 MainActivity）
     * 统一管理：频道号显示、底部信息栏、EPG 节目信息展示
     */
    private InfoDisplayManager infoDisplayManager;

    // ====================================================================
    // ✅ 拆分新增：频道面板控制器（分组 + 频道切换 + 面板控制）
    // ====================================================================
    /**
     * 频道面板控制器（拆分自 MainActivity）
     * 统一管理：分组管理、频道切换、面板控制
     */
    private ChannelPanelController channelPanelController;

    // ====================================================================
    // ✅ 拆分新增：应用核心管理器（数据加载 + 广播 + 生命周期）
    // ====================================================================
    /**
     * 应用核心管理器（拆分自 MainActivity）
     * 统一管理：数据加载、广播接收、生命周期
     */
    private AppCoreManager appCoreManager;

    // ====================== 状态标志 ======================
    /** 频道切换是否反向（上键=下一台，下键=上一台） */
    private boolean channel_reverse;
    /** 数字选台是否启用 */
    private boolean number_channel_enable;

    // ====================== 其他 ======================
    /** 本地日志列表（保留最近 100 条，供其他类访问） */
    public static List<String> logList = new java.util.ArrayList<>();

    // ====================== onCreate 生命周期 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");
        mInstance = this;

        // ===== 自动旋转横屏 =====
        // sensorLandscape：支持左横屏/右横屏自动旋转，但不会变成竖屏
        // 比固定 LANDSCAPE 更灵活，适合手机/平板使用
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // ====================================================================
        // ✅ 拆分：全面屏适配移到 DisplayManager
        // ====================================================================
        displayManager = new DisplayManager(this);
        displayManager.applyFullScreen();

        // 加载布局
        setContentView(R.layout.activity_main);

        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // ====================================================================
        // ✅ 拆分：信息展示管理器初始化
        // ====================================================================
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

        // 绑定播放器视图
        playerView = findViewById(R.id.player_view);
        // 屏蔽原生控制器（防止弹出"播放异常"等文字）
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        // ====================================================================
        // ✅ 拆分：频道面板控制器初始化
        // ====================================================================
        initChannelPanelController();

        // ====================================================================
        // ✅ 拆分：播放器初始化
        // ====================================================================
        initPlayer();

        // 屏幕比例
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 手势管理
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        // 播放器触摸事件
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
        int lastPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(lastPlayIndex);
        log("【播放】记录上次播放索引：" + lastPlayIndex);

        // ====================================================================
        // ✅ 拆分：数字选台管理器初始化
        // ====================================================================
        initChannelNumberManager();

        // ====================================================================
        // ✅ 拆分：应用核心管理器初始化
        // ====================================================================
        initAppCoreManager();

        // ====================================================================
        // ✅ 拆分：加载动画移到 DisplayManager
        // ====================================================================
        displayManager.showLoading("正在加载直播源...");

        // 加载直播源和 EPG
        appCoreManager.loadLiveAndEpg();
    }

    // ====================================================================
    // ✅ 拆分：信息展示管理器初始化
    // ====================================================================
    /**
     * 初始化信息展示管理器
     * 负责频道号、底部信息栏、EPG 节目信息的展示
     */
    private void initInfoDisplayManager() {
        // 绑定各个 View
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

        // 创建信息展示管理器
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
    // ✅ 拆分：频道面板控制器初始化
    // ====================================================================
    /**
     * 初始化频道面板控制器
     * 负责分组管理、频道切换、面板控制
     */
    private void initChannelPanelController() {
        // 绑定各个 View
        View panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 先初始化 EpgManager（必须在 EpgManagerWrapper 之前）
        EpgManager.getInstance(this);

        // 创建子管理器
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        DateListManager dateListManager = new DateListManager(this, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        dateListManager.initDate();

        // 日期选中监听
        dateListManager.setOnDateSelectedListener(pos -> {
            channelPanelController.setCurrentDateIndex(pos);
        });

        // 创建频道面板控制器
        channelPanelController = new ChannelPanelController(
                this,
                panel_layout,
                lvGroup,
                lvChannelList,
                lvDate,
                lvEpg,
                btn_show_epg,
                groupListManager,
                channelListManager,
                dateListManager,
                epgManagerWrapper,
                panelManager
        );

        // 频道切换监听
        channelPanelController.setOnChannelChangeListener(new ChannelPanelController.OnChannelChangeListener() {
            @Override
            public void onChannelChanged(Channel channel, int index) {
                // 频道切换时，调用播放器播放
                playChannel(channel, index);
            }
        });
    }

    // ====================================================================
    // ✅ 拆分：播放器初始化
    // ====================================================================
    /**
     * 初始化播放器
     */
    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        // 播放器状态监听器（空实现，不弹 Toast）
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 直播信息更新监听（画质、音频、码率）
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                // 通过 InfoDisplayManager 更新直播信息
                infoDisplayManager.updateLiveInfo(info);
            }
        });
    }

    // ====================================================================
    // ✅ 拆分：数字选台管理器初始化
    // ====================================================================
    /**
     * 初始化数字选台管理器
     */
    private void initChannelNumberManager() {
        channelNumberManager = new ChannelNumberManager(
                new ChannelNumberManager.OnChannelNumberListener() {
                    @Override
                    public void onChannelSelected(int channelIndex) {
                        // 用户选中了频道，调用播放
                        channelPanelController.playChannel(channelIndex);
                    }

                    @Override
                    public void showChannelNumber(String number) {
                        // 显示频道号（通过 InfoDisplayManager）
                        infoDisplayManager.showChannelNum(Integer.parseInt(number));
                    }

                    @Override
                    public void hideChannelNumber() {
                        // 隐藏频道号（通过 InfoDisplayManager）
                        infoDisplayManager.hideChannelNum();
                    }
                },
                number_channel_enable  // 是否启用
        );
    }

    // ====================================================================
    // ✅ 拆分：应用核心管理器初始化
    // ====================================================================
    /**
     * 初始化应用核心管理器
     * 负责数据加载、广播接收、生命周期管理
     */
    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);

        // 数据加载监听
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveSourceLoaded(List<Channel> channels, boolean fromCache) {
                // 直播源加载成功
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 更新频道面板
                        channelPanelController.setChannels(channels);

                        // 设置数字选台的总频道数
                        channelNumberManager.setTotalChannelCount(channels.size());

                        // 如果还没用缓存播放过，就播放
                        if (!appCoreManager.hasPlayedWithCache()) {
                            int index = channelPanelController.getCurrentPlayIndex();
                            if (index >= 0 && index < channels.size()) {
                                Channel ch = channels.get(index);
                                playChannel(ch, index);
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
                // 直播源加载失败
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (appCoreManager.getChannelList().isEmpty()) {
                            // 没有缓存，加载失败
                            displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                            displayManager.hideLoading();
                            SettingsActivity.logOperation("【加载】直播源加载失败：" + errorMsg);
                        } else {
                            // 有缓存，继续用缓存
                            log("【缓存】使用缓存数据继续播放");
                            displayManager.hideLoading();
                        }
                    }
                });
            }

            @Override
            public void onEpgLoaded() {
                // EPG 加载完成，刷新信息栏
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int index = channelPanelController.getCurrentPlayIndex();
                        List<Channel> channels = appCoreManager.getChannelList();
                        if (index >= 0 && index < channels.size()) {
                            Channel curr = channels.get(index);
                            infoDisplayManager.updateEpgInfo(curr);
                        }
                    }
                });
            }

            @Override
            public void onLoadTimeout(boolean hasData) {
                // 加载超时
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

        // 注册广播
        appCoreManager.registerReceivers();
    }

    // ====================== 设置加载 ======================
    /**
     * 从 SharedPreferences 加载各项设置
     */
    private void loadSettings() {
        android.content.SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);

        // 同步数字选台启用状态
        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        }

        // 同步 EPG 启用状态
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
        }

        log("【设置】EPG开关：" + epg_enable);
        log("【设置】切台反转：" + channel_reverse);
        log("【设置】数字选台：" + number_channel_enable);
        log("【设置】自动更新源：" + auto_update_source);
    }

    // ====================== 播放频道 ======================
    /**
     * 播放指定频道
     *
     * @param channel 频道
     * @param index   全局索引
     */
    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;

        log("========================================");
        log("【播放】频道名称：" + channel.getName());
        log("【播放】播放地址：" + channel.getPlayUrl());
        log("【播放】当前索引：" + index);
        log("========================================");

        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);

        // 先播放（最重要的事情先做）
        mPlayerManager.playUrl(channel.getPlayUrl());

        // 显示信息栏
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
    }

    // ====================== 返回键处理 ======================
    @Override
    public void onBackPressed() {
        // 数字选台取消输入
        if (channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            return;
        }

        // 面板关闭
        if (channelPanelController.handleBackPressed()) {
            playerView.requestFocus();
            return;
        }

        // 否则正常返回
        super.onBackPressed();
    }

    // ====================== 方向键处理 ======================
    /**
     * 处理方向按键
     *
     * 【按键映射】
     * - 上/下键：切换频道（受换台反转影响）
     * - 确认/OK键：切换面板显示（或确认数字选台）
     * - 左/右键：切换面板显示
     *
     * @param keyCode 按键码
     * @return 是否处理了该按键
     */
    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                // 上键
                if (channel_reverse) {
                    channelPanelController.playNext();  // 反转：上键 = 下一台
                } else {
                    channelPanelController.playPrev();  // 正常：上键 = 上一台
                }
                SettingsActivity.logOperation("【切台】上键 → "
                        + (channel_reverse ? "下一台" : "上一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 下键
                if (channel_reverse) {
                    channelPanelController.playPrev();  // 反转：下键 = 上一台
                } else {
                    channelPanelController.playNext();  // 正常：下键 = 下一台
                }
                SettingsActivity.logOperation("【切台】下键 → "
                        + (channel_reverse ? "上一台" : "下一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 确认键
                if (channelNumberManager.isInputting()) {
                    // 如果正在输入数字选台，确认输入
                    channelNumberManager.confirmChannelNum();
                    return true;
                }
                // 否则切换面板显示
                channelPanelController.togglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 左右键：切换面板显示
                channelPanelController.togglePanel();
                return true;
            default:
                return false;
        }
    }

    // ====================== 按键分发 ======================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 数字选台处理
        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        // 然后处理方向键
        if (handleDirectionKey(keyCode)) return true;

        // 最后交给按键事件管理器
        if (keyEventManager.dispatchKey(keyCode)) return true;

        return super.onKeyDown(keyCode, event);
    }

    // ====================== 打开设置页面 ======================
    /**
     * 打开设置页面
     *
     * 【进入设置不暂停】
     * 打开设置前设置 isOpeningSettings = true，
     * 这样 onPause 时就不会暂停播放器。
     */
    public void openSettings() {
        appCoreManager.beforeOpenSettings();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ====================== 接收远程配置 ======================
    /**
     * 接收远程配置（网页后台下发）
     *
     * @param liveUrl 直播源地址
     * @param epgUrl  EPG 地址
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    // ====================== 生命周期方法 ======================
    /**
     * onPause：页面暂停
     */
    @Override
    protected void onPause() {
        super.onPause();
        appCoreManager.onPause();
    }

    /**
     * onResume：页面恢复
     */
    @Override
    protected void onResume() {
        super.onResume();
        boolean resumed = appCoreManager.onResume();

        if (resumed) {
            // 真正回到前台时，重新加载设置
            loadSettings();
            screenRatioManager.apply();
        }

        // ====================================================================
        // ✅ 拆分：全面屏恢复移到 DisplayManager
        // ====================================================================
        displayManager.reapplyFullScreen();
    }

    /**
     * onWindowFocusChanged：窗口焦点变化
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // ====================================================================
            // ✅ 拆分：全面屏恢复移到 DisplayManager
            // ====================================================================
            displayManager.reapplyFullScreen();
        }
        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    /**
     * onDestroy：页面销毁
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");

        // ====================================================================
        // ✅ 拆分：各管理器释放
        // ====================================================================
        if (infoDisplayManager != null) {
            infoDisplayManager.release();
        }
        if (channelNumberManager != null) {
            channelNumberManager.release();
        }
        if (displayManager != null) {
            displayManager.release();
        }
        if (channelPanelController != null) {
            channelPanelController.release();
        }
        if (appCoreManager != null) {
            appCoreManager.release();
        }

        mInstance = null;
    }

    // ====================== 日志工具 ======================
    /**
     * 记录日志
     * 同时保存到本地列表和 SettingsActivity 的全局日志
     *
     * @param msg 日志内容
     */
    public static void log(String msg) {
        logList.add(0, msg);
        // 只保留最近 100 条，防止内存溢出
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        // 同步到 SettingsActivity 的全局日志
        SettingsActivity.log(msg);
    }
}
