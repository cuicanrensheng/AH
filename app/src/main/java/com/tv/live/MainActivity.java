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
 * 3. 播放器视图绑定
 * 4. 数据加载触发
 *
 * 【2026-06-20 优化：按键+手势统一管理（合并到 ChannelPanelController）】
 * 把 KeyEventManager 和 GestureManager 的功能全部合并到 ChannelPanelController 里，
 * 所有切台入口统一走 switchUp()/switchDown()，反转逻辑统一管理。
 */
public class MainActivity extends AppCompatActivity {

    // ====================== 单例 ======================
    // ✅ 修复：改成 public，供 ChannelListActivity 等外部类访问
    public static MainActivity mInstance;

    public static MainActivity getInstance() {
        return mInstance;
    }

    // ✅ 兼容层：保留旧的 public 变量，供其他类直接访问
    /** 所有频道数据源列表（全部频道，未筛选） */
    public List<Channel> channelSourceList = new ArrayList<>();

    /** 当前正在播放的频道索引（全局索引，对应 channelSourceList） */
    public int currentPlayIndex = 0;

    // ====================== 播放器相关 ======================
    /** 播放器视图 */
    private PlayerView playerView;
    /** 播放器管理器（单例，基于 ExoPlayer 封装） */
    private TVPlayerManager mPlayerManager;
    /** 播放器状态监听器 */
    private PlayerStateListenerImpl playerStateListener;

    // ====================== 管理器相关 ======================
    /** 应用配置管理（SP 封装） */
    private AppConfig appConfig;
    /** 显示管理器（全面屏+加载动画） */
    private DisplayManager displayManager;
    /** 信息展示管理器（频道号+信息栏+EPG 节目单） */
    private InfoDisplayManager infoDisplayManager;
    /** 数字选台管理器 */
    private ChannelNumberManager channelNumberManager;
    /** 屏幕比例管理器 */
    private ScreenRatioManager screenRatioManager;
    /** 应用核心管理器（数据加载 + 广播 + 生命周期） */
    private AppCoreManager appCoreManager;

    /**
     * 频道面板控制器（分组 + 频道切换 + 面板控制 + 焦点管理 + 按键 + 手势 + 反转）
     */
    private ChannelPanelController channelPanelController;

    // ====================== 状态标志 ======================
    /** 频道切换是否反向（上键=下一台，下键=上一台） */
    private boolean channel_reverse;

    /** 数字选台是否启用 */
    private boolean number_channel_enable;

    /** 是否正在打开设置页面 */
    private boolean isOpeningSettings = false;

    // ====================== 防花屏相关 ======================
    /** 防花屏占位图（退到后台时显示，避免看到花屏） */
    private ImageView ivPlayerPlaceholder;

    // ====================== 日志相关 ======================
    /** 本地日志列表（保留最近 100 条，供其他类访问） */
    public static List<String> logList = new ArrayList<>();

    // ====================== 首次打开自动隐藏相关 ======================
    private Handler mPanelAutoHideHandler = new Handler(Looper.getMainLooper());

    private Runnable mPanelAutoHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelPanelController != null) {
                channelPanelController.hidePanel();
            }
        }
    };

    private boolean mIsFirstLaunch = true;

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

        // 绑定播放器视图 + 占位图
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        ivPlayerPlaceholder = findViewById(R.id.iv_player_placeholder);

        // 频道面板控制器初始化
        initChannelPanelController();

        // 首次打开时，3 秒后自动隐藏面板
        if (mIsFirstLaunch) {
            mPanelAutoHideHandler.postDelayed(mPanelAutoHideRunnable, 3000);
            mIsFirstLaunch = false;
        }

        // 播放器初始化
        initPlayer();

        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);

        // 数字选台管理器初始化
        initChannelNumberManager();

        // ====================================================================
        // ✅ 修复：ScreenRatioManager 构造函数参数不对
        // 【错误原因】原来传了 3 个参数，实际只需要 2 个 (TVPlayerManager, AppConfig)
        // ====================================================================
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);

        // 应用核心管理器初始化
        initAppCoreManager();

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
        View ll_left_panel = findViewById(R.id.ll_left_panel);
        View ll_right_panel = findViewById(R.id.ll_right_panel);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvChannelListEpg = findViewById(R.id.lv_channel_list_epg);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);

        TextView btn_show_epg = findViewById(R.id.btn_show_epg);
        TextView btn_back_group = findViewById(R.id.btn_back_group);

        EpgManager.getInstance(this);

        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
        ChannelListManager channelListManagerEpg = new ChannelListManager(this, lvChannelListEpg);
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
                panelManager
        );

        // 设置频道切换监听器
        channelPanelController.setOnChannelChangeListener(new ChannelPanelController.OnChannelChangeListener() {
            @Override
            public void onChannelChanged(Channel channel, int index) {
                playChannel(channel, index);
            }

            @Override
            public void onChannelSelected(int channelIndex) {
                channelPanelController.playChannel(channelIndex);
            }
        });

        // 设置面板动作监听器（打开设置等操作）
        channelPanelController.setOnPanelActionListener(new ChannelPanelController.OnPanelActionListener() {
            @Override
            public void onOpenSettings() {
                openSettings();
            }
        });
    }

    // ====================================================================
    // ✅ 修复：播放器初始化（适配你实际的 TVPlayerManager API）
    // ====================================================================

    private void initPlayer() {
        // 1. 获取播放器单例（需要传 Context）
        mPlayerManager = TVPlayerManager.getInstance(this);

        // 2. 设置播放状态监听器
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 3. 绑定播放器视图 ✅ 方法名：attachPlayerView
        mPlayerManager.attachPlayerView(playerView);
    }

    // ====================================================================
    // 数字选台管理器初始化
    // ====================================================================

    private void initChannelNumberManager() {
        channelNumberManager = new ChannelNumberManager(this, new ChannelNumberManager.OnChannelNumberListener() {
            @Override
            public void onChannelNumberConfirmed(int channelNum) {
                // 数字选台确认后，播放对应频道
                if (channelPanelController != null) {
                    channelPanelController.playChannel(channelNum - 1);
                }
            }

            // ====================================================================
            // ✅ 修复：加上缺失的 hideChannelNumber() 方法
            // ====================================================================
            @Override
            public void hideChannelNumber() {
                if (infoDisplayManager != null) {
                    infoDisplayManager.hideChannelNum();
                }
            }
        });
    }

    // ====================================================================
    // 应用核心管理器初始化
    // ====================================================================

    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);

        // 设置数据加载监听器
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveLoaded(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);

                channelPanelController.setChannels(channels);

                int lastIndex = appConfig.getLastPlayIndex();
                if (lastIndex >= 0 && lastIndex < channels.size()) {
                    currentPlayIndex = lastIndex;
                    channelPanelController.setCurrentPlayIndex(lastIndex);
                    playChannel(channels.get(lastIndex), lastIndex);
                } else if (!channels.isEmpty()) {
                    currentPlayIndex = 0;
                    playChannel(channels.get(0), 0);
                }
            }

            @Override
            public void onEpgLoaded() {
                log("【主页】EPG 加载完成");
            }

            @Override
            public void onLoadError(String error) {
                log("【主页】加载失败：" + error);
            }

            // ====================================================================
            // ✅ 修复：加上缺失的 onLoadTimeout() 方法
            // ====================================================================
            @Override
            public void onLoadTimeout(boolean isLive) {
                if (isLive) {
                    log("【主页】直播源加载超时");
                    SettingsActivity.logOperation("【错误】直播源加载超时");
                } else {
                    log("【主页】EPG加载超时");
                    SettingsActivity.logOperation("【错误】EPG加载超时");
                }
            }
        });
    }

    // ====================================================================
    // 加载设置
    // ====================================================================

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
            // ✅ 同步反转设置到 ChannelPanelController
            channelPanelController.setReverse(channel_reverse);
        }

        log("【设置】EPG开关：" + epg_enable);
        log("【设置】切台反转：" + channel_reverse);
        log("【设置】数字选台：" + number_channel_enable);
        log("【设置】自动更新源：" + auto_update_source);
    }

    // ====================================================================
    // 播放相关
    // ====================================================================

    public void playChannel(int index) {
        if (channelSourceList == null || index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;

        currentPlayIndex = index;

        log("========================================");
        log("【播放】频道名称：" + channel.getName());
        log("【播放】播放地址：" + channel.getPlayUrl());
        log("【播放】当前索引：" + index);
        log("========================================");

        playerStateListener.setCurrentChannelName(channel.getName());

        mPlayerManager.playUrl(channel.getPlayUrl());

        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);

        infoDisplayManager.showChannelNum(index + 1);

        appConfig.setLastPlayIndex(index);
    }

    // ====================================================================
    // 兼容层方法
    // ====================================================================

    public void togglePanel() {
        if (channelPanelController != null) {
            channelPanelController.togglePanel();
        }
    }

    public void playPrev() {
        if (channelPanelController != null) {
            channelPanelController.playPrev();
        }
    }

    public void playNext() {
        if (channelPanelController != null) {
            channelPanelController.playNext();
        }
    }

    // ====================================================================
    // ✅ 新增：获取播放器管理器（供 EpgManagerWrapper 等外部类访问）
    // ====================================================================

    public TVPlayerManager getPlayerManager() {
        return mPlayerManager;
    }

    /**
     * 换台反转是否开启
     */
    public boolean isChannelReverse() {
        return channel_reverse;
    }

    // ====================================================================
    // 打开设置页面
    // ====================================================================

    private void openSettings() {
        // ✅ 设置标志位，onPause 时不显示占位图
        isOpeningSettings = true;
        log("【设置】打开设置页面");
        SettingsActivity.logOperation("【设置】打开设置页面");

        appCoreManager.beforeOpenSettings();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ====================== 返回键处理 ======================

    @Override
    public void onBackPressed() {
        if (channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            return;
        }

        if (channelPanelController != null && channelPanelController.handleBackPressed()) {
            playerView.requestFocus();
            return;
        }

        super.onBackPressed();
    }

    // ====================== 方向键处理（保留兼容层） ======================

    private boolean handleDirectionKey(int keyCode) {
        if (channelPanelController == null) return false;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                channelPanelController.switchUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                channelPanelController.switchDown();
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
        cancelPanelAutoHide();

        // 1. 先处理数字选台
        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        // 2. 统一交给 ChannelPanelController 处理所有按键
        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    // ====================================================================
    // 取消面板自动隐藏
    // ====================================================================

    private void cancelPanelAutoHide() {
        if (mPanelAutoHideHandler != null && mPanelAutoHideRunnable != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        }
    }

    // ====================== 触摸事件 ======================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            cancelPanelAutoHide();
        }
        return super.onTouchEvent(event);
    }

    // ====================== onResume 生命周期 ======================

    @Override
    protected void onResume() {
        super.onResume();

        isOpeningSettings = false;
        log("【设置】从设置页面返回，重置标志位");

        boolean resumed = appCoreManager.onResume();

        // 每次 onResume 都重新加载设置和应用屏幕比例
        loadSettings();
        screenRatioManager.apply();

        displayManager.reapplyFullScreen();

        // 延迟 2000ms 隐藏占位图
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                hidePlayerPlaceholder();
            }
        }, 2000);
    }

    // ====================== onPause 生命周期 ======================

    @Override
    protected void onPause() {
        // 先显示占位图，再执行 super.onPause()
        if (!isOpeningSettings) {
            showPlayerPlaceholder();
        } else {
            log("【防花屏】打开设置页面，不显示占位图");
        }

        super.onPause();

        appCoreManager.onPause();
    }

    // ====================== 防花屏占位图相关 ======================

    private void showPlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.VISIBLE);
            log("【防花屏】显示占位图");
        }
    }

    private void hidePlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.GONE);
            log("【防花屏】隐藏占位图");
        }
    }

    // ====================== onDestroy 生命周期 ======================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        SettingsActivity.logOperation("【系统】APP退出");

        cancelPanelAutoHide();
        mPanelAutoHideHandler = null;

        if (channelPanelController != null) {
            channelPanelController.release();
        }

        if (appCoreManager != null) {
            appCoreManager.release();
        }

        if (mPlayerManager != null) {
            mPlayerManager.release();
        }

        mInstance = null;
    }

    // ====================== 日志方法 ======================

    private void log(String msg) {
        logList.add(0, msg);
        if (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }
}
