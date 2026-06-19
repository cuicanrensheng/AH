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

    /** 频道面板控制器（分组 + 频道切换 + 面板控制） */
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
    private void initChannelPanelController() {
        View panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        EpgManager.getInstance(this);
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        DateListManager dateListManager = new DateListManager(this, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            channelPanelController.setCurrentDateIndex(pos);
        });

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
                            displayManager.hideLoading();
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
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (channelNumberManager.handleNumberKey(keyCode)) return true;
        if (handleDirectionKey(keyCode)) return true;
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    // ====================== 打开设置页面 ======================
    public void openSettings() {
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
        // 【防花屏】退到后台前显示黑色占位图，盖住 SurfaceView
        // 防止 Surface 销毁过程中出现花屏、绿屏、撕裂等问题
        showPlayerPlaceholder();
        appCoreManager.onPause();
    }

    // ====================================================================
    // ✅ 防花屏：回到前台后延迟隐藏占位图（参照老版本，简单直接）
    // ====================================================================
    @Override
    protected void onResume() {
        super.onResume();
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
        }, 100);
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
