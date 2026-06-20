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
 * 【2026-06-20 新增：接入 TvRemoteManager 统一遥控器管理】
 * 【集成说明】
 * 1. 创建 TvRemoteManager 实例，统一管理所有遥控器按键
 * 2. 根据面板状态自动切换模式（播放模式 / 频道面板模式）
 * 3. 所有按键都走 remoteManager.dispatchKeyEvent() 统一入口
 * 4. 通过回调接口调用现有的业务逻辑
 *
 * 【遥控器操作】
 * 播放模式：
 * - ↑/↓：切换频道（带反转）
 * - OK/左右键：切换频道面板
 * - 菜单：打开设置
 * - 数字键：数字选台
 *
 * 频道面板模式：
 * - ↑/↓：列表上下移动
 * - ←/→：切换列（分组/频道/节目单）
 * - OK：选中当前项
 * - 返回：返回/关闭面板
 * - 菜单：关闭面板
 * - 数字键：数字选台
 */
public class MainActivity extends AppCompatActivity {

    // ====================== 单例 ======================

    public static MainActivity mInstance;

    // ====================================================================
    // 兼容层：保留旧的 public 变量
    // ====================================================================

    public List<Channel> channelSourceList = new ArrayList<>();
    public int currentPlayIndex = 0;

    // ====================== 视图相关 ======================

    private PlayerView playerView;
    private ImageView ivPlayerPlaceholder;

    // ====================== 管理器相关 ======================

    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private PlayerStateListenerImpl playerStateListener;

    // ====================================================================
    // 拆分新增：各个 Manager
    // ====================================================================

    private ChannelNumberManager channelNumberManager;
    private DisplayManager displayManager;
    private InfoDisplayManager infoDisplayManager;
    private ChannelPanelController channelPanelController;
    private AppCoreManager appCoreManager;

    // ====================================================================
    // ✅ 新增：遥控器统一管理器
    // ====================================================================

    /**
     * 遥控器统一管理器
     *
     * 【功能】
     * 统一管理所有遥控器按键操作，支持两种模式：
     * 1. PLAY_MODE（播放模式）- 全屏播放时
     * 2. CHANNEL_PANEL_MODE（频道面板模式）- 面板打开时
     *
     * 【为什么用统一管理器？】
     * 1. 所有按键逻辑集中管理，不分散在多个 Manager 里
     * 2. 模式切换自动处理，不用手动判断
     * 3. 自带完整的操作日志，方便排查问题
     * 4. 和 SettingsActivity 用同一套体系
     *
     * 【什么时候切换模式？】
     * - 打开频道面板 → 切换到 CHANNEL_PANEL_MODE
     * - 关闭频道面板 → 切换到 PLAY_MODE
     */
    private TvRemoteManager remoteManager;

    // ====================== 状态标志 ======================

    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean isOpeningSettings = false;

    // ====================================================================
    // 频道面板自动隐藏
    // ====================================================================

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

    // ====================== 其他 ======================

    public static List<String> logList = new ArrayList<>();

    // ====================== onCreate 生命周期 ======================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");

        mInstance = this;

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        displayManager = new DisplayManager(this);
        displayManager.applyFullScreen();

        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initInfoDisplayManager();

        appConfig = AppConfig.getInstance(this);
        loadSettings();

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        ivPlayerPlaceholder = findViewById(R.id.iv_player_placeholder);

        initChannelPanelController();

        // ====================================================================
        // ✅ 新增：初始化遥控器管理器
        // ====================================================================
        initRemoteManager();

        if (mIsFirstLaunch) {
            mPanelAutoHideHandler.postDelayed(mPanelAutoHideRunnable, 3000);
            mIsFirstLaunch = false;
        }

        initPlayer();

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        keyEventManager = new KeyEventManager(this);

        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        log("【播放】记录上次播放索引：" + currentPlayIndex);

        initChannelNumberManager();
        initAppCoreManager();

        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();
    }

    // ====================================================================
    // ✅ 新增：初始化遥控器管理器
    // ====================================================================

    /**
     * 初始化遥控器管理器
     *
     * 【集成说明】
     * 1. 创建 TvRemoteManager 实例
     * 2. 默认设置为播放模式
     * 3. 设置回调监听器，调用现有的业务逻辑
     * 4. 面板打开/关闭时自动切换模式
     */
    private void initRemoteManager() {
        // 创建遥控器管理器
        remoteManager = new TvRemoteManager();

        // 默认播放模式
        remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);

        // 设置回调监听器
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {

            // ================== 播放模式回调 ==================

            /**
             * 上键（播放模式：上一台，带反转）
             */
            @Override
            public void onPlayChannelUp() {
                // 调用 ChannelPanelController 的统一入口，带反转
                channelPanelController.switchUp();
            }

            /**
             * 下键（播放模式：下一台，带反转）
             */
            @Override
            public void onPlayChannelDown() {
                // 调用 ChannelPanelController 的统一入口，带反转
                channelPanelController.switchDown();
            }

            /**
             * OK键（播放模式：切换面板）
             */
            @Override
            public void onPlayTogglePanel() {
                togglePanel();
                // 切换面板后同步遥控器模式
                syncRemoteMode();
            }

            /**
             * 菜单键（播放模式：打开设置）
             */
            @Override
            public void onPlayOpenSettings() {
                openSettings();
            }

            /**
             * 返回键（播放模式）
             */
            @Override
            public boolean onPlayBack() {
                // 交给系统处理（退出应用等）
                return false;
            }

            // ================== 频道面板模式回调 ==================

            /**
             * 上键（面板模式：列表上移）
             */
            @Override
            public void onPanelMoveUp() {
                // 交给 ChannelPanelController 处理
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
            }

            /**
             * 下键（面板模式：列表下移）
             */
            @Override
            public void onPanelMoveDown() {
                // 交给 ChannelPanelController 处理
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN);
            }

            /**
             * 左键（面板模式：向左切换列）
             */
            @Override
            public void onPanelMoveLeft() {
                // 交给 ChannelPanelController 处理
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
            }

            /**
             * 右键（面板模式：向右切换列）
             */
            @Override
            public void onPanelMoveRight() {
                // 交给 ChannelPanelController 处理
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
            }

            /**
             * OK键（面板模式：选中当前项）
             */
            @Override
            public void onPanelConfirm() {
                // 交给 ChannelPanelController 处理
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER);
            }

            /**
             * 返回键（面板模式：返回/关闭）
             */
            @Override
            public boolean onPanelBack() {
                // 交给 ChannelPanelController 处理
                boolean handled = channelPanelController.handleBackPressed();
                // 如果面板关闭了，同步遥控器模式
                if (!channelPanelController.isPanelOpen()) {
                    syncRemoteMode();
                }
                return handled;
            }

            /**
             * 菜单键（面板模式：关闭面板）
             */
            @Override
            public void onPanelMenu() {
                channelPanelController.hidePanel();
                syncRemoteMode();
            }

            /**
             * 数字键（面板模式：数字选台）
             */
            @Override
            public void onPanelNumber(int number) {
                // 交给数字选台管理器处理
                int keyCode = KeyEvent.KEYCODE_0 + number;
                channelNumberManager.handleNumberKey(keyCode);
            }

            /**
             * 焦点面板变化
             */
            @Override
            public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {
                // 可以在这里做焦点高亮等处理
                SettingsActivity.logOperation("【遥控】面板焦点切换：" + newFocus);
            }

            // ================== 设置模式回调（MainActivity 用不到，空实现） ==================

            @Override public void onSettingsMoveUp() {}
            @Override public void onSettingsMoveDown() {}
            @Override public void onSettingsConfirm() {}
            @Override public boolean onSettingsBack() { return false; }
            @Override public void onSettingsMenu() {}
            @Override public void onSettingsFocusChanged(int position) {}
        });
    }

    // ====================================================================
    // ✅ 新增：同步遥控器模式（根据面板状态）
    // ====================================================================

    /**
     * 同步遥控器模式
     *
     * 【说明】
     * 根据频道面板的打开/关闭状态，自动切换遥控器模式：
     * - 面板打开 → CHANNEL_PANEL_MODE
     * - 面板关闭 → PLAY_MODE
     *
     * 【什么时候调用？】
     * 1. 切换面板后（togglePanel）
     * 2. 关闭面板后（hidePanel / handleBackPressed）
     * 3. 打开面板后（showPanel）
     */
    private void syncRemoteMode() {
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            // 面板打开 → 频道面板模式
            remoteManager.setMode(TvRemoteManager.Mode.CHANNEL_PANEL_MODE);
            // 同步右侧面板状态
            remoteManager.setRightPanelOpen(channelPanelController.isRightPanelOpen());
        } else {
            // 面板关闭 → 播放模式
            remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        }
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
                        channelSourceList.clear();
                        channelSourceList.addAll(channels);
                        channelPanelController.setChannels(channels);
                        channelNumberManager.setTotalChannelCount(channels.size());

                        if (!appCoreManager.hasPlayedWithCache()) {
                            if (currentPlayIndex >= 0 && currentPlayIndex < channels.size()) {
                                Channel ch = channels.get(currentPlayIndex);
                                playChannel(ch, currentPlayIndex);
                                appCoreManager.setHasPlayedWithCache(true);
                            }
                        }

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
    // 获取反转状态（供外部类调用）
    // ====================================================================

    /**
     * 获取换台反转状态
     */
    public boolean isChannelReverse() {
        return channel_reverse;
    }

    // ====================================================================
    // 兼容层：旧的 playChannel(int) 方法
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

        currentPlayIndex = index;

        log("========================================");
        log("【播放】频道名称：" + channel.getName());
        log("【播放】播放地址：" + channel.getPlayUrl());
        log("【播放】当前索引：" + index);
        log("========================================");

        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);

        mPlayerManager.playUrl(channel.getPlayUrl());

        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
        infoDisplayManager.showChannelNum(index + 1);
    }

    // ====================================================================
    // 兼容层：旧的 togglePanel() 方法
    // ====================================================================

    /**
     * 切换频道面板显示/隐藏（兼容旧接口）
     */
    public void togglePanel() {
        channelPanelController.togglePanel();
        // 切换面板后同步遥控器模式
        syncRemoteMode();
    }

    // ====================================================================
    // 兼容层：旧的 playPrev() 方法
    // ====================================================================

    /**
     * 播放上一个频道（兼容旧接口）
     */
    public void playPrev() {
        channelPanelController.playPrev();
    }

    // ====================================================================
    // 兼容层：旧的 playNext() 方法
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

        // 先交给遥控器管理器处理
        if (remoteManager != null) {
            if (remoteManager.dispatchKeyEvent(KeyEvent.KEYCODE_BACK)) {
                return;
            }
        }

        if (channelPanelController.handleBackPressed()) {
            playerView.requestFocus();
            // 面板关闭后同步遥控器模式
            syncRemoteMode();
            return;
        }

        super.onBackPressed();
    }

    // ====================== 方向键处理（保留，备用） ======================

    /**
     * 处理方向键（面板关闭时切台）
     *
     * 【说明】
     * 保留这个方法，作为备用。
     * 实际按键处理已经交给 TvRemoteManager 统一管理了。
     */
    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                SettingsActivity.logOperation("【按键】handleDirectionKey 上键 → 反转状态：" 
                        + (channel_reverse ? "开启" : "关闭"));
                channelPanelController.switchUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                SettingsActivity.logOperation("【按键】handleDirectionKey 下键 → 反转状态：" 
                        + (channel_reverse ? "开启" : "关闭"));
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

    // ====================== 按键分发（核心：直接调用 TvRemoteManager） ======================

    /**
     * 按键事件分发
     *
     * 【直接调用 TvRemoteManager】
     * 所有按键都先交给 remoteManager.dispatchKeyEvent() 统一处理，
     * 处理不了的再交给其他 Manager。
     *
     * 【为什么这么设计？】
     * 1. 统一入口：所有按键都走一个地方，好管理
     * 2. 模式自动切换：不用手动判断面板是否打开
     * 3. 完整日志：每个按键都有操作日志
     * 4. 向后兼容：TvRemoteManager 处理不了的，还是走原来的逻辑
     *
     * 【按键分发优先级】
     * 1. TvRemoteManager（统一遥控器管理器）
     * 2. 数字选台（ChannelNumberManager）- 备用
     * 3. 频道面板（ChannelPanelController）- 备用
     * 4. 方向键切台（handleDirectionKey）- 备用
     * 5. 按键事件管理（KeyEventManager）- 其他按键
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        cancelPanelAutoHide();

        // 1. 先交给遥控器统一管理器处理
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // 2. 数字选台（备用，TvRemoteManager 已经处理了数字键）
        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        // 3. 频道面板（备用，TvRemoteManager 已经处理了面板按键）
        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // 4. 方向键切台（备用）
        if (handleDirectionKey(keyCode)) return true;

        // 5. 最后交给按键事件管理
        if (keyEventManager.dispatchKey(keyCode)) return true;

        return super.onKeyDown(keyCode, event);
    }

    // ====================================================================
    // 取消频道面板自动隐藏
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

    @Override
    protected void onPause() {
        if (!isOpeningSettings) {
            showPlayerPlaceholder();
        } else {
            log("【防花屏】打开设置页面，不显示占位图");
        }

        super.onPause();
        appCoreManager.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        isOpeningSettings = false;
        log("【设置】从设置页面返回，重置标志位");

        boolean resumed = appCoreManager.onResume();

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

        // 从设置页面回来后，同步遥控器模式
        syncRemoteMode();
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

        if (mPanelAutoHideHandler != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
            mPanelAutoHideHandler = null;
        }

        if (infoDisplayManager != null) infoDisplayManager.release();
        if (channelNumberManager != null) channelNumberManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();

        // 释放遥控器管理器
        remoteManager = null;

        mInstance = null;
    }

    // ====================================================================
    // 防花屏：占位图显示/隐藏方法
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
        if (logList.size() > 100) {
            logList.remove(0);
        }
        SettingsActivity.log(msg);
    }

    // ====================================================================
    // 兼容方法：供外部获取播放器管理器
    // ====================================================================

    /**
     * 获取播放器管理器
     */
    public TVPlayerManager getPlayerManager() {
        return mPlayerManager;
    }

    /**
     * 获取单例
     */
    public static MainActivity getInstance() {
        return mInstance;
    }
}
