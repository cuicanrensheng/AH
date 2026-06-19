package com.tv.live;

import android.content.Intent;
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
 * 3. 播放器视图绑定（三播放器：主播放器 + 下一个预加载 + 上一个预加载）
 * 4. 手势事件绑定
 *
 * 【三播放器双向预加载】
 * 三个播放器叠加：
 * - 主播放器（player_view）：正在播放，有声音，显示
 * - 预加载播放器（player_view_preload_next）：提前缓冲下一个台，静音，隐藏
 * - 预加载播放器（player_view_preload_prev）：提前缓冲上一个台，静音，隐藏
 * 切台时直接交换主播放器和对应方向的预加载播放器，0 毫秒黑屏，完全无缝。
 * 上下两个方向都预加载，不管按上还是按下都是无缝的。
 *
 * 【防花屏增强】
 * 三层防护，解决滑动退到后台时 SurfaceView 花屏问题：
 * 1. 保持最后一帧：播放器暂停时不清空画面
 * 2. 占位图过渡：退到后台时用 ImageView 盖住 SurfaceView
 * 3. 延迟隐藏：回到前台后等 Surface 准备好再隐藏占位图
 *
 * 【模块化拆分说明】
 * 已拆分的 Manager：
 * - ChannelNumberManager：数字选台
 * - DisplayManager：全面屏适配 + 加载动画
 * - InfoDisplayManager：信息展示（频道号 + 信息栏 + EPG）
 * - ChannelPanelController：频道面板（分组 + 切台 + 面板）
 * - AppCoreManager：应用核心（数据加载 + 广播 + 生命周期）
 * - MainController：主控制器（按键 + 播放 + 设置 + 日志）
 * - TVPlayerManager：播放器管理（三播放器双向预加载版）
 * - ... 等
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
     */
    public List<Channel> channelSourceList = new ArrayList<>();

    /**
     * 当前正在播放的频道索引（全局索引，对应 channelSourceList）
     */
    public int currentPlayIndex = 0;

    /**
     * 本地日志列表（保留最近 100 条，供其他类访问）
     */
    public static List<String> logList = new ArrayList<>();

    // ====================== 视图相关 ======================
    /** 主播放器视图（正在播放，显示） */
    private PlayerView playerView;
    /** 预加载播放器视图 - 下一个频道（提前缓冲，隐藏） */
    private PlayerView playerViewPreloadNext;
    /** 预加载播放器视图 - 上一个频道（提前缓冲，隐藏） */
    private PlayerView playerViewPreloadPrev;

    // ====================================================================
    // ✅ 防花屏增强：播放器占位图
    // ====================================================================
    /**
     * 播放器占位图（退到后台时显示，防止 SurfaceView 花屏）
     */
    private ImageView ivPlayerPlaceholder;

    // ====================== 管理器相关 ======================
    /** 播放器管理器（单例，三播放器双向预加载版） */
    public TVPlayerManager mPlayerManager;
    /** 应用配置管理（SP 封装） */
    private AppConfig appConfig;
    /** 屏幕比例管理（全屏/填充/原始） */
    private ScreenRatioManager screenRatioManager;
    /** 手势管理（滑动、点击等手势处理） */
    private GestureManager gestureManager;
    /** 按键事件管理（遥控器按键分发） */
    private KeyEventManager keyEventManager;

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
    /** 主控制器（按键 + 播放 + 设置 + 日志） */
    private MainController mainController;

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

        // 应用自定义直播源/EPG 地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        // ====================================================================
        // ✅ 三播放器：绑定三个播放器视图
        // ====================================================================
        playerView = findViewById(R.id.player_view);
        playerViewPreloadNext = findViewById(R.id.player_view_preload_next);
        playerViewPreloadPrev = findViewById(R.id.player_view_preload_prev);

        // ====================================================================
        // ✅ 防花屏增强：初始化防花屏设置
        // ====================================================================
        initAntiFlicker();

        // 频道面板控制器初始化
        initChannelPanelController();

        // 播放器初始化（三播放器双向预加载版）
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

        // 数字选台管理器初始化
        initChannelNumberManager();

        // 主控制器初始化（按键 + 播放 + 设置 + 日志）
        initMainController();

        // 恢复上次播放的频道索引
        currentPlayIndex = appConfig.getLastPlayIndex();
        mainController.setCurrentPlayIndex(currentPlayIndex);
        log("【播放】记录上次播放索引：" + currentPlayIndex);

        // 应用核心管理器初始化
        initAppCoreManager();

        // 显示加载动画
        displayManager.showLoading("正在加载直播源...");

        // 加载直播源和 EPG
        appCoreManager.loadLiveAndEpg();
    }

    // ====================================================================
    // ✅ 防花屏增强：初始化防花屏相关设置
    // ====================================================================
    /**
     * 初始化防花屏相关设置
     *
     * 【三层防护】
     * 1. 保持最后一帧：播放器暂停时不清空画面（布局里设置）
     * 2. 占位图过渡：退到后台时用 ImageView 盖住 SurfaceView
     * 3. 延迟隐藏：回到前台后等 Surface 准备好再隐藏占位图
     */
    private void initAntiFlicker() {
        // 占位图初始化（默认隐藏，退到后台时显示）
        ivPlayerPlaceholder = findViewById(R.id.iv_player_placeholder);

        log("【防花屏】防花屏增强已启用（三层防护，兼容旧版 ExoPlayer）");
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

        // 频道切换监听 → 交给 MainController 处理实际播放
        channelPanelController.setOnChannelChangeListener(new ChannelPanelController.OnChannelChangeListener() {
            @Override
            public void onChannelChanged(Channel channel, int index) {
                mainController.doPlayChannel(channel, index);
                // 同步到兼容变量
                currentPlayIndex = index;
            }
        });
    }

    // ====================================================================
    // ✅ 三播放器：播放器初始化
    // ====================================================================
    /**
     * 播放器初始化（三播放器双向预加载版）
     *
     * 绑定三个播放器视图：
     * - 主播放器：正在播放，有声音，显示
     * - 预加载播放器 - 下一个：提前缓冲下一个台，静音，隐藏
     * - 预加载播放器 - 上一个：提前缓冲上一个台，静音，隐藏
     */
    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);

        // 绑定三个播放器视图（主播放器 + 下一个预加载 + 上一个预加载）
        mPlayerManager.attachPlayerViews(playerView, playerViewPreloadNext, playerViewPreloadPrev);

        // 直播信息更新监听（画质、音频、码率）
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
                true  // 默认启用，后面 loadSettings 会覆盖
        );
    }

    // ====================================================================
    // 主控制器初始化（按键 + 播放 + 设置 + 日志）
    // ====================================================================
    private void initMainController() {
        // 播放器状态监听器（空实现，不弹 Toast）
        PlayerStateListenerImpl playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 创建主控制器
        mainController = new MainController(
                this,
                channelPanelController,
                channelNumberManager,
                infoDisplayManager,
                mPlayerManager,
                appConfig,
                playerStateListener
        );

        // 面板控制回调
        mainController.setOnPanelControlListener(new MainController.OnPanelControlListener() {
            @Override
            public void onTogglePanel() {
                // 面板切换后的额外处理（如果需要）
            }

            @Override
            public void onRequestFocus() {
                // 关闭面板后给播放器请求焦点
                playerView.requestFocus();
            }
        });

        // 加载设置
        mainController.loadSettings();
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

                        // ====================================================================
                        // ✅ 三播放器：把频道列表传给 MainController，用于双向预加载
                        // ====================================================================
                        mainController.setChannelList(channels);

                        // 如果还没用缓存播放过，就播放
                        if (!appCoreManager.hasPlayedWithCache()) {
                            if (currentPlayIndex >= 0 && currentPlayIndex < channels.size()) {
                                Channel ch = channels.get(currentPlayIndex);
                                mainController.doPlayChannel(ch, currentPlayIndex);
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
        mainController.doPlayChannel(channel, index);
        currentPlayIndex = index;
    }

    // ====================================================================
    // ✅ 兼容层：旧的 togglePanel() 方法，供 GestureManager 等调用
    // ====================================================================
    /**
     * 切换频道面板显示/隐藏（兼容旧接口）
     */
    public void togglePanel() {
        mainController.togglePanel();
    }

    // ====================================================================
    // ✅ 兼容层：旧的 playPrev() 方法，供 GestureManager 等调用
    // ====================================================================
    /**
     * 播放上一个频道（兼容旧接口）
     */
    public void playPrev() {
        mainController.playPrev();
    }

    // ====================================================================
    // ✅ 兼容层：旧的 playNext() 方法，供 GestureManager 等调用
    // ====================================================================
    /**
     * 播放下一个频道（兼容旧接口）
     */
    public void playNext() {
        mainController.playNext();
    }

    // ====================== 返回键处理 ======================
    @Override
    public void onBackPressed() {
        if (mainController.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    // ====================== 按键分发 ======================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 主控制器处理（数字键 + 方向键）
        if (mainController.handleKeyDown(keyCode, event)) {
            return true;
        }
        // 最后交给按键事件管理器
        if (keyEventManager.dispatchKey(keyCode)) {
            return true;
        }
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
    @Override
    protected void onPause() {
        super.onPause();

        // ====================================================================
        // ✅ 防花屏：进入后台前显示占位图，盖住 SurfaceView
        // ====================================================================
        showPlayerPlaceholder();

        appCoreManager.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        boolean resumed = appCoreManager.onResume();

        if (resumed) {
            // 真正回到前台时，重新加载设置
            mainController.loadSettings();
            screenRatioManager.apply();
        }

        displayManager.reapplyFullScreen();

        // ====================================================================
        // ✅ 防花屏：回到前台后延迟隐藏占位图
        //    等 Surface 渲染好第一帧再隐藏，避免短暂黑屏或花屏
        // ====================================================================
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                hidePlayerPlaceholder();
            }
        }, 100);  // 延迟 100ms，等 Surface 准备好
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
        if (mainController != null) mainController.release();
        if (appCoreManager != null) appCoreManager.release();

        mInstance = null;
    }

    // ====================================================================
    // ✅ 防花屏增强：占位图显示/隐藏方法
    // ====================================================================

    /**
     * 显示播放器占位图（防止退到后台时 SurfaceView 花屏）
     *
     * 【原理】
     * SurfaceView 有自己独立的渲染线程和 Surface，
     * 在 Activity 切换动画时容易出现花屏、撕裂、绿屏等问题。
     *
     * 用一个普通的 ImageView 显示黑色背景，
     * 退到后台时显示 ImageView，盖住 SurfaceView，
     * 动画时用户看到的是 ImageView，就不会花屏了。
     *
     * 【兼容说明】
     * 旧版 ExoPlayer 没有 getBitmap() 方法，
     * 所以不支持截取当前帧，直接用黑色背景作为占位图。
     * 黑色背景虽然没有最后一帧那么自然，但总比花屏好看多了。
     */
    private void showPlayerPlaceholder() {
        if (ivPlayerPlaceholder == null) return;

        // 直接显示黑色背景（兼容所有版本的 ExoPlayer）
        ivPlayerPlaceholder.setImageResource(android.R.color.black);
        ivPlayerPlaceholder.setVisibility(View.VISIBLE);
        log("【防花屏】显示占位图（黑色背景，兼容旧版 ExoPlayer）");
    }

    /**
     * 隐藏播放器占位图
     *
     * 【注意】
     * 要等 Surface 渲染好第一帧再隐藏，
     * 否则可能会出现短暂的黑屏或花屏。
     * 所以调用方需要延迟 100ms 左右再调用这个方法。
     */
    private void hidePlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.GONE);
            // 清空图片，释放内存
            ivPlayerPlaceholder.setImageDrawable(null);
            log("【防花屏】隐藏占位图");
        }
    }

    // ====================================================================
    // ✅ 兼容层：旧的 log() 静态方法，供其他类调用
    // ====================================================================
    /**
     * 记录日志（兼容旧接口）
     * 同时保存到本地列表和 SettingsActivity 的全局日志
     *
     * @param msg 日志内容
     */
    public static void log(String msg) {
        // 同步到 MainController 的日志
        MainController.log(msg);
        // 同步到兼容变量 logList
        logList.add(0, msg);
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
    }
}
