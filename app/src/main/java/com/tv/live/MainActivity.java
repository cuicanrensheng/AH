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
 * 【2026-06-20 修复：换台反转失效】
 * 【问题原因】
 * 之前反转逻辑只在 handleDirectionKey() 里，
 * KeyEventManager 等其他切台入口直接调用 playPrev()/playNext()，
 * 不考虑反转设置，导致反转在某些场景下失效。
 *
 * 【解决方案】
 * 1. 在 ChannelPanelController 里统一管理反转逻辑
 * 2. 新增 switchUp() / switchDown() 带反转的统一入口
 * 3. handleDirectionKey() 改用统一入口
 * 4. loadSettings() 同步反转设置到 ChannelPanelController
 * 5. KeyEventManager 也加上反转判断
 *
 * 【效果】
 * 所有切台入口都走统一的反转逻辑，反转肯定生效，不会不同步。
 */
public class MainActivity extends AppCompatActivity {

    // ====================== 单例 ======================

    /** Activity 单例，供其他类访问 */
    public static MainActivity mInstance;

    // ====================================================================
    // ✅ 兼容层：保留旧的 public 变量，供其他类直接访问
    // ====================================================================

    /** 所有频道数据源列表（全部频道，未筛选） */
    public List<Channel> channelSourceList = new ArrayList<>();

    /** 当前正在播放的频道索引（全局索引，对应 channelSourceList） */
    public int currentPlayIndex = 0;

    // ====================== 视图相关 ======================

    /** 播放器视图（ExoPlayer 的 PlayerView） */
    private PlayerView playerView;

    // ====================================================================
    // ✅ 防花屏：播放器占位图（退到后台时显示，盖住 SurfaceView）
    // ====================================================================

    /** 播放器占位图 */
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

    /** 频道面板控制器（分组 + 频道切换 + 面板控制 + 焦点管理 + 反转） */
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
     * 2. 日志输出时用
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
     * true = 正在打开设置，不显示占位图
     * false = 正常退到后台，显示占位图防花屏
     */
    private boolean isOpeningSettings = false;

    // ====================================================================
    // ✅ 新增：频道面板自动隐藏
    // ====================================================================

    /** Handler 用于延迟隐藏面板 */
    private Handler mPanelAutoHideHandler = new Handler(Looper.getMainLooper());

    /** 自动隐藏面板的 Runnable */
    private Runnable mPanelAutoHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelPanelController != null) {
                channelPanelController.hidePanel();
            }
        }
    };

    /** 是否是首次打开 app */
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

        // 绑定播放器视图 + 占位图
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        // 绑定防花屏占位图
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
     * 【2026-06-20 修复：同步反转设置到 ChannelPanelController】
     * 【问题原因】
     * 之前只同步了 EPG 开关，没同步反转设置，
     * 导致 ChannelPanelController 里的反转状态一直是默认值 false。
     *
     * 【修复方案】
     * 新增 channelPanelController.setReverse(channel_reverse)，
     * 把反转设置同步到 ChannelPanelController。
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
            // ====================================================================
            // ✅ 新增：同步反转设置到 ChannelPanelController
            // ====================================================================
            //
            // 【为什么要同步？】
            // 反转逻辑现在统一由 ChannelPanelController 管理，
            // 需要把设置同步过去，否则 switchUp()/switchDown() 不知道反转状态。
            //
            // 【同步后效果】
            // 所有地方调用 channelPanelController.switchUp()/switchDown() 时，
            // 都会自动考虑反转设置，不会出现不同步的问题。
            channelPanelController.setReverse(channel_reverse);
        }

        log("【设置】EPG开关：" + epg_enable);
        log("【设置】切台反转：" + channel_reverse);
        log("【设置】数字选台：" + number_channel_enable);
        log("【设置】自动更新源：" + auto_update_source);
    }

    // ====================================================================
    // ✅ 新增：获取反转状态（供 KeyEventManager 等外部类调用）
    // ====================================================================

    /**
     * 获取换台反转状态
     *
     * @return true = 开启反转，false = 关闭反转
     *
     * 【为什么需要这个方法？】
     * KeyEventManager 等外部类需要知道反转状态，
     * 但是 channel_reverse 是 private 的，不能直接访问。
     * 所以提供一个 public 的 getter 方法。
     */
    public boolean isChannelReverse() {
        return channel_reverse;
    }

    // ====================================================================
    // ✅ 兼容层：旧的 playChannel(int) 方法，供其他类调用
    // ====================================================================

    /**
     * 播放指定索引的频道（兼容旧接口）
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

        // 显示频道号（从 1 开始）
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

    // ====================== 方向键处理 ======================

    /**
     * 处理方向键（面板关闭时切台）
     *
     * 【2026-06-20 修复：改用 ChannelPanelController 统一方法】
     * 【问题原因】
     * 之前反转逻辑在 MainActivity 里判断，然后调用 playPrev() 或 playNext()，
     * 这样反转逻辑分散在两个地方，容易出现不同步。
     *
     * 【修复方案】
     * 统一由 ChannelPanelController 管理反转，
     * 直接调用 switchUp() 和 switchDown() 方法，内部自动考虑反转。
     */
    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                // ====================================================================
                // ✅ 修复：改用统一方法，内部自动考虑反转
                // ====================================================================
                // 【原来的代码】
                // if (channel_reverse) {
                //     playNext();
                // } else {
                //     playPrev();
                // }
                //
                // 【为什么改成这样？】
                // 反转逻辑现在统一由 ChannelPanelController 管理，
                // 直接调用 switchUp() 就行，内部会自动判断 isReverse。
                // 这样反转逻辑只有一份，不会出现不同步的问题。
                channelPanelController.switchUp();
                SettingsActivity.logOperation("【切台】上键 → "
                        + (channel_reverse ? "下一台" : "上一台"));
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                // ✅ 修复：改用统一方法，内部自动考虑反转
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

    // ====================== 按键分发 ======================

    /**
     * 按键事件分发
     *
     * 【按键分发优先级】
     * 1. 数字选台（ChannelNumberManager）- 数字键 0-9
     * 2. 频道面板（ChannelPanelController）- 左右键、OK键（面板打开时）
     * 3. 方向键切台（handleDirectionKey）- 上下键（面板关闭时，带反转）
     * 4. 按键事件管理（KeyEventManager）- 其他按键（也带反转）
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 用户有按键操作时，取消自动隐藏
        cancelPanelAutoHide();

        // 1. 先处理数字选台
        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        // 2. 再让频道面板处理按键（左右键、OK键）
        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // 3. 再处理方向键切台（面板关闭时，带反转）
        if (handleDirectionKey(keyCode)) return true;

        // 4. 最后交给按键事件管理（也带反转）
        if (keyEventManager.dispatchKey(keyCode)) return true;

        return super.onKeyDown(keyCode, event);
    }

    // ====================================================================
    // ✅ 取消频道面板自动隐藏
    // ====================================================================

    /**
     * 取消频道面板自动隐藏
     */
    private void cancelPanelAutoHide() {
        if (mPanelAutoHideHandler != null && mPanelAutoHideRunnable != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        }
    }

    // ====================== 打开设置页面 ======================

    /**
     * 打开设置页面
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
    // ✅ 防花屏优化：退到后台前先显示占位图
    // ====================================================================

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
     */
    private void showPlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.VISIBLE);
            log("【防花屏】显示占位图");
        }
    }

    /**
     * 隐藏播放器占位图
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
