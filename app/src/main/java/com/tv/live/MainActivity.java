package com.tv.live;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
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
 * 【2026-06-21 新增：收藏 + 最近观看】
 * 【修改说明】
 * 1. 频道面板模式下，菜单键 = 收藏/取消收藏当前频道
 * 2. 切换频道时添加到最近观看（双保险）
 *
 * 【2026-06-22 新增：画中画功能集成】
 * 【功能说明】
 * 1. 按 Home 键自动进入画中画模式（可在设置中开关）
 * 2. 画中画模式下继续播放视频
 * 3. 画中画模式下隐藏所有面板
 * 4. 播放状态、频道信息同步到画中画
 * 5. 双击画中画窗口切换回全屏
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
    // 遥控器统一管理器
    // ====================================================================
    private TvRemoteManager remoteManager;

    // ====================================================================
    // ✅ 画中画管理器（新增）
    // ====================================================================
    private PictureInPictureManager pipManager;
    private boolean pipEnable = true; // 画中画总开关
    private boolean isInPipMode = false; // 是否处于画中画模式

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

        SettingsActivity.logOperation("【主页】onCreate -> 页面创建");
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
        initRemoteManager();

        // ====================================================================
        // ✅ 初始化画中画管理器（新增）
        // ====================================================================
        initPictureInPicture();

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
        SettingsActivity.logOperation("【播放】记录上次播放索引：" + currentPlayIndex);

        initChannelNumberManager();
        initAppCoreManager();

        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();
    }

    // ====================================================================
    // ✅ 初始化画中画管理器（新增）
    // ====================================================================
    /**
     * 初始化画中画功能
     *
     * 【功能】
     * 1. 创建 PictureInPictureManager 实例
     * 2. 设置画中画模式变化监听器
     * 3. 从配置中读取画中画开关状态
     */
    private void initPictureInPicture() {
        pipManager = PictureInPictureManager.getInstance(this);

        // 设置画中画模式变化监听器
        pipManager.setOnPipModeChangedListener(new PictureInPictureManager.OnPipModeChangedListener() {
            @Override
            public void onPipModeChanged(boolean isInPip) {
                isInPipMode = isInPip;
                SettingsActivity.logOperation("【画中画】模式变化 → " + (isInPip ? "进入画中画" : "退出画中画"));

                if (isInPip) {
                    // 进入画中画：隐藏所有面板
                    if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                        channelPanelController.hidePanel();
                    }
                    // 隐藏信息栏
                    if (infoDisplayManager != null) {
                        infoDisplayManager.hideInfoBar();
                    }
                    // 隐藏频道号
                    if (infoDisplayManager != null) {
                        infoDisplayManager.hideChannelNum();
                    }
                } else {
                    // 退出画中画：恢复全屏显示
                    if (displayManager != null) {
                        displayManager.reapplyFullScreen();
                    }
                }
            }
        });

        // 从配置中读取画中画开关状态
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        pipEnable = sp.getBoolean("pip_enable", true);

        log("【画中画】初始化完成，开关状态：" + (pipEnable ? "开启" : "关闭"));
    }

    // ====================================================================
    // 初始化遥控器管理器
    // ====================================================================
    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {

            // ================== 播放模式回调 ==================
            @Override
            public void onPlayChannelUp() {
                playPrev();
            }

            @Override
            public void onPlayChannelDown() {
                playNext();
            }

            @Override
            public void onPlayTogglePanel() {
                togglePanel();
                syncRemoteMode();
            }

            @Override
            public void onPlayOpenSettings() {
                openSettings();
            }

            @Override
            public boolean onPlayBack() {
                return false;
            }

            // ================== 频道面板模式回调 ==================
            @Override
            public void onPanelUp() {
                channelPanelController.switchUp();
            }

            @Override
            public void onPanelDown() {
                channelPanelController.switchDown();
            }

            @Override
            public void onPanelLeft() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
            }

            @Override
            public void onPanelRight() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
            }

            @Override
            public void onPanelConfirm() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER);
            }

            @Override
            public boolean onPanelBack() {
                boolean handled = channelPanelController.handleBackPressed();
                syncRemoteMode();
                return handled;
            }

            /**
             * ✅ 2026-06-21 修改：菜单键 = 收藏/取消收藏
             */
            @Override
            public void onPanelMenu() {
                SettingsActivity.logOperation("【遥控】菜单键 → 收藏/取消收藏");
                boolean isFavorite = channelPanelController.toggleCurrentFavorite();
                SettingsActivity.logOperation("【收藏】操作结果 → " + (isFavorite ? "已收藏" : "已取消"));
            }

            @Override
            public void onPanelNumber(int number) {
                int keyCode = KeyEvent.KEYCODE_0 + number;
                channelPanelController.dispatchKeyEvent(keyCode);
            }

            @Override
            public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {
                // 焦点变化时的处理
            }

            // ================== 设置模式回调（空实现） ==================
            @Override public void onSettingsConfirm() {}
            @Override public void onSettingsMenu() {}
        });
    }

    // ====================================================================
    // 同步遥控器模式
    // ====================================================================
    private void syncRemoteMode() {
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            remoteManager.setMode(TvRemoteManager.Mode.PANEL_MODE);
        } else {
            remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        }
    }

    // ====================================================================
    // 信息展示管理器初始化
    // ====================================================================
    private void initInfoDisplayManager() {
        TextView tv_channel_num = findViewById(R.id.tv_channel_num);
        TextView tv_current_program_name = findViewById(R.id.tv_current_program_name);
        TextView tv_current_time_range = findViewById(R.id.tv_current_time_range);
        ProgressBar progress_program = findViewById(R.id.progress_program);
        TextView tv_next_program_name = findViewById(R.id.tv_next_program_name);
        TextView tv_next_time_range = findViewById(R.id.tv_next_time_range);

        View info_bar = findViewById(R.id.info_bar);
        TextView tv_channel_name = findViewById(R.id.tv_channel_name);
        TextView tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        TextView tv_tag_audio = findViewById(R.id.tv_tag_audio);
        TextView tv_bitrate = findViewById(R.id.tv_bitrate);
        TextView tv_remaining_time = findViewById(R.id.tv_remaining_time);

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

                // ====================================================================
                // ✅ 画中画：同步播放信息（新增）
                // ====================================================================
                if (isInPipMode && pipManager != null) {
                    pipManager.updatePlayState(MainActivity.this, mPlayerManager.isPlaying());
                }
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
                        infoDisplayManager.showChannelNum(number);
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
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        pipEnable = sp.getBoolean("pip_enable", true); // ✅ 新增：加载画中画开关状态

        // 把反转状态同步给 ChannelPanelController
        if (channelPanelController != null) {
            channelPanelController.setReverse(channel_reverse);
        }

        // 把 EPG 开关状态同步给 ChannelPanelController
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
        }
    }

    // 获取反转状态
    public boolean isChannelReverse() {
        return channel_reverse;
    }

    // ====================== 播放频道（内部方法） ======================
    private void playChannel(Channel channel, int index) {
        if (channel == null || mPlayerManager == null) return;

        currentPlayIndex = index;
        mPlayerManager.playUrl(channel.getUrl());
        mPlayerManager.setCurrentChannelNumber(index + 1);

        // 更新信息栏
        infoDisplayManager.showInfoBar();
        infoDisplayManager.updateChannelInfo(channel, index + 1);

        // 同步到频道面板
        channelPanelController.setCurrentPlayIndex(index);

        // 保存到最近观看
        appConfig.addToRecent(channel.getName());

        // 保存上次播放索引
        appConfig.setLastPlayIndex(index);

        // ====================================================================
        // ✅ 画中画：同步频道信息（新增）
        // ====================================================================
        if (pipManager != null) {
            pipManager.updateChannelInfo(index + 1, channel.getName(), "");
            if (isInPipMode) {
                pipManager.updatePlayState(this, mPlayerManager.isPlaying());
            }
        }

        SettingsActivity.logOperation("【播放】切换频道：" + channel.getName() + "（第" + (index + 1) + "台）");
    }

    // 上一台
    public void playPrev() {
        if (channelPanelController != null) {
            channelPanelController.switchUp();
        }
        syncRemoteMode();
    }

    // 下一台
    public void playNext() {
        if (channelPanelController != null) {
            channelPanelController.switchDown();
        }
        syncRemoteMode();
    }

    // 切换面板显示/隐藏
    public void togglePanel() {
        if (channelPanelController != null) {
            channelPanelController.togglePanel();
        }
        syncRemoteMode();
    }

    // ====================== 返回键处理 ======================
    @Override
    public void onBackPressed() {
        // 如果正在输入数字选台，先取消
        if (channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            return;
        }

        // 如果频道面板打开，先关闭面板
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            boolean handled = channelPanelController.handleBackPressed();
            if (handled) {
                syncRemoteMode();
                return;
            }
        }

        // 画中画模式下按返回键退出画中画
        if (isInPipMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                moveTaskToBack(false);
            }
            return;
        }

        if (remoteManager != null) {
            boolean handled = remoteManager.dispatchKey(KeyEvent.KEYCODE_BACK);
            if (handled) return;
        }

        syncRemoteMode();
        super.onBackPressed();
    }

    // ====================== 按键事件处理 ======================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 画中画模式下，大部分按键都不处理，让系统自己处理
        if (isInPipMode) {
            // 只有返回键特殊处理
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                moveTaskToBack(false);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        cancelPanelAutoHide();

        // 数字选台
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            if (number_channel_enable) {
                int number = keyCode - KeyEvent.KEYCODE_0;
                channelNumberManager.inputNumber(number);
                return true;
            }
        }

        // 先让遥控器管理器处理
        if (remoteManager != null) {
            boolean handled = remoteManager.dispatchKey(keyCode);
            if (handled) return true;
        }

        // 再让 KeyEventManager 处理（兼容旧逻辑）
        if (keyEventManager != null) {
            boolean handled = keyEventManager.dispatchKey(keyCode);
            if (handled) return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    // 取消频道面板自动隐藏
    private void cancelPanelAutoHide() {
        if (mPanelAutoHideHandler != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        }
    }

    // ====================== 打开设置页面 ======================
    public void openSettings() {
        isOpeningSettings = true;
        SettingsActivity.logOperation("【设置】打开设置页面，不显示占位图");
        appCoreManager.beforeOpenSettings();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ====================== 接收远程配置 ======================
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    // ====================================================================
    // ✅ 画中画：用户按 Home 键时自动进入画中画（新增）
    // ====================================================================
    /**
     * 当用户按下 Home 键时调用
     *
     * 【作用】
     * 如果画中画开关开启，并且设备支持画中画，就自动进入画中画模式。
     *
     * 【为什么用这个方法？】
     * onUserLeaveHint() 是 Android 专门为画中画设计的回调，
     * 当用户按下 Home 键或最近任务键时会调用，
     * 这是进入画中画模式的最佳时机。
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        // 如果画中画开关开启，并且设备支持画中画，就自动进入
        if (pipEnable && pipManager != null && pipManager.isPipSupported()) {
            SettingsActivity.logOperation("【画中画】用户按 Home 键 → 自动进入画中画");
            pipManager.enterPictureInPicture(this);
        }
    }

    // ====================================================================
    // ✅ 画中画：模式变化回调（新增）
    // ====================================================================
    /**
     * 画中画模式变化时的回调
     *
     * 【作用】
     * 当进入或退出画中画模式时，系统会调用这个方法。
     * 我们在这里通知 PictureInPictureManager 更新状态。
     */
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);

        if (pipManager != null) {
            pipManager.onPipModeChanged(this, isInPictureInPictureMode);
        }
    }

    // ====================== 生命周期方法 ======================
    @Override
    protected void onPause() {
        // 如果是打开设置页面，不显示占位图
        if (!isOpeningSettings) {
            // 画中画模式下不显示占位图
            if (!isInPipMode) {
                showPlayerPlaceholder();
            }
        } else {
            SettingsActivity.logOperation("【防花屏】打开设置页面，不显示占位图");
        }

        super.onPause();
        appCoreManager.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        isOpeningSettings = false;
        SettingsActivity.logOperation("【设置】从设置页面返回，重置标志位");

        boolean resumed = appCoreManager.onResume();

        // 重新加载设置（可能在设置页面修改了）
        loadSettings();

        screenRatioManager.apply();
        displayManager.reapplyFullScreen();

        // 画中画模式下不隐藏占位图（因为还在画中画）
        if (!isInPipMode) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    hidePlayerPlaceholder();
                }
            }, 2000);
        }

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

        SettingsActivity.logOperation("【主页】onDestroy -> 页面销毁");

        if (mPanelAutoHideHandler != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
            mPanelAutoHideHandler = null;
        }

        if (infoDisplayManager != null) infoDisplayManager.release();
        if (channelNumberManager != null) channelNumberManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();

        // ====================================================================
        // ✅ 释放画中画管理器（新增）
        // ====================================================================
        if (pipManager != null) {
            pipManager.release();
            pipManager = null;
        }

        remoteManager = null;
        mInstance = null;
    }

    // ====================================================================
    // 防花屏：占位图显示/隐藏方法
    // ====================================================================
    private void showPlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.VISIBLE);
            SettingsActivity.logOperation("【防花屏】显示占位图");
        }
    }

    private void hidePlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.GONE);
            SettingsActivity.logOperation("【防花屏】隐藏占位图");
        }
    }

    // ====================== 日志方法 ======================
    private void log(String msg) {
        logList.add(msg);
        if (logList.size() > 100) {
            logList.remove(0);
        }
        SettingsActivity.log(msg);
    }

    // ====================================================================
    // 兼容方法
    // ====================================================================
    public TVPlayerManager getPlayerManager() {
        return mPlayerManager;
    }

    public static MainActivity getInstance() {
        return mInstance;
    }
}
