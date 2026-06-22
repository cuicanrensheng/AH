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
 * 【2026-06-22 新增：画中画功能】
 * 【功能说明】
 * 1. 按 Home 键自动进入画中画模式（可在设置中开关）
 * 2. 画中画模式下继续播放视频
 * 3. 画中画模式下隐藏所有面板
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
    // ✅ 画中画相关（优化：调整初始化顺序 + 完善状态同步）
    // ====================================================================
    private PictureInPictureManager pipManager;
    private boolean pipEnable = false; // 初始值与SharedPreferences默认值保持一致
    private boolean isInPipMode = false;

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
        loadSettings(); // ✅ 优先加载设置，保证画中画开关先初始化

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
        // ✅ 初始化画中画管理器（优化：移到loadSettings之后，保证开关已加载）
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
        // ✅ 修复：loadSettings中channelNumberManager可能未初始化，这里重新同步一次
        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        }

        initAppCoreManager();

        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();
    }

    // ====================================================================
    // ✅ 初始化画中画（优化：完善异常处理 + 强制同步开关状态）
    // ====================================================================
    private void initPictureInPicture() {
        try {
            pipManager = PictureInPictureManager.getInstance(this);
            // 强制同步开关状态
            pipManager.setPipEnabled(pipEnable);
            // 设置PIP监听器（同步播放状态）
            pipManager.setListener(new PictureInPictureManager.OnPipListener() {
                @Override
                public void onPlayPause() {
                    // 耳机拔出时暂停/恢复播放
                    if (mPlayerManager != null) {
                        boolean isPlaying = mPlayerManager.isPlaying();
                        if (isPlaying) {
                            mPlayerManager.pause();
                        } else {
                            mPlayerManager.play();
                        }
                        pipManager.updatePlayState(!isPlaying);
                    }
                }

                @Override
                public void onPipModeChanged(boolean inPip) {
                    // 同步播放状态：PIP模式下保持播放
                    if (inPip && mPlayerManager != null && !mPlayerManager.isPlaying()) {
                        mPlayerManager.play();
                    }
                }
            });
            log("【画中画】初始化完成，开关状态：" + (pipEnable ? "开启" : "关闭"));
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持：" + pipManager.isPipSupported());
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
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
                channelPanelController.switchUp();
            }

            @Override
            public void onPlayChannelDown() {
                channelPanelController.switchDown();
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
            public void onPanelMoveUp() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
            }

            @Override
            public void onPanelMoveDown() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN);
            }

            @Override
            public void onPanelMoveLeft() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
            }

            @Override
            public void onPanelMoveRight() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
            }

            @Override
            public void onPanelConfirm() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER);
            }

            @Override
            public boolean onPanelBack() {
                boolean handled = channelPanelController.handleBackPressed();
                if (!channelPanelController.isPanelOpen()) {
                    syncRemoteMode();
                }
                return handled;
            }

            /**
             * ✅ 2026-06-21 修改：菜单键 = 收藏/取消收藏
             */
            @Override
            public void onPanelMenu() {
                boolean isFavorite = channelPanelController.toggleCurrentFavorite();
                SettingsActivity.logOperation("【遥控】菜单键 → "
                        + (isFavorite ? "已添加收藏" : "已取消收藏"));
            }

            @Override
            public void onPanelNumber(int number) {
                int keyCode = KeyEvent.KEYCODE_0 + number;
                channelNumberManager.handleNumberKey(keyCode);
            }

            @Override
            public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {
                SettingsActivity.logOperation("【遥控】面板焦点切换：" + newFocus);
            }

            // ================== 设置模式回调（空实现） ==================
            @Override public void onSettingsMoveUp() {}
            @Override public void onSettingsMoveDown() {}
            @Override public void onSettingsConfirm() {}
            @Override public boolean onSettingsBack() { return false; }
            @Override public void onSettingsMenu() {}
            @Override public void onSettingsFocusChanged(int position) {}
        });
    }

    // ====================================================================
    // 同步遥控器模式
    // ====================================================================
    private void syncRemoteMode() {
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            remoteManager.setMode(TvRemoteManager.Mode.CHANNEL_PANEL_MODE);
            remoteManager.setRightPanelOpen(channelPanelController.isRightPanelOpen());
        } else {
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
                // ✅ 同步播放状态到PIP管理器
                if (pipManager != null) {
                    pipManager.updatePlayState(mPlayerManager.isPlaying());
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

                    // ✅ 修复：String 转 int 后再调用
                    @Override
                    public void showChannelNumber(String number) {
                        try {
                            infoDisplayManager.showChannelNum(Integer.parseInt(number));
                        } catch (Exception e) {
                            // 转换失败就忽略
                        }
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
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);

        // ✅ 优化：先读取开关，再同步给管理器
        pipEnable = sp.getBoolean("pip_enable", false);

        // ✅ 修复：channelNumberManager可能未初始化，增加判空
        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        } else {
            log("【设置】channelNumberManager未初始化，暂不同步数字选台开关");
        }

        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }

        SettingsActivity.logOperation("【设置】EPG开关：" + epg_enable);
        SettingsActivity.logOperation("【设置】切台反转：" + channel_reverse);
        SettingsActivity.logOperation("【设置】数字选台：" + number_channel_enable);
        SettingsActivity.logOperation("【设置】自动更新源：" + auto_update_source);
        SettingsActivity.logOperation("【设置】画中画开关：" + pipEnable);
    }

    // ====================================================================
    // 获取反转状态
    // ====================================================================
    public boolean isChannelReverse() {
        return channel_reverse;
    }

    // ====================================================================
    // 兼容层：旧的 playChannel(int) 方法
    // ====================================================================
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
     * 【2026-06-21 修改：添加到最近观看（双保险）】
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

        // ✅ 新增：添加到最近观看（双保险）
        try {
            appConfig.addRecentChannel(channel.getName());
        } catch (Exception e) {
            // 忽略错误
        }

        // ====================================================================
        // ✅ 画中画：同步频道信息（优化：增加非空判断 + 异常捕获）
        // ====================================================================
        if (pipManager != null && isInPipMode && channel != null) {
            try {
                pipManager.updateChannelInfo(index + 1,
                        channel.getName() != null ? channel.getName() : "",
                        live != null ? live.bitrate : "");
            } catch (Exception e) {
                log("【画中画】同步频道信息失败：" + e.getMessage());
            }
        }
    }

    // ====================================================================
    // 兼容层：旧的 togglePanel() 方法
    // ====================================================================
    public void togglePanel() {
        channelPanelController.togglePanel();
        syncRemoteMode();
    }

    // ====================================================================
    // 兼容层：旧的 playPrev() 方法
    // ====================================================================
    public void playPrev() {
        channelPanelController.playPrev();
    }

    // ====================================================================
    // 兼容层：旧的 playNext() 方法
    // ====================================================================
    public void playNext() {
        channelPanelController.playNext();
    }

    // ====================== 返回键处理 ======================
    @Override
    public void onBackPressed() {
        // ✅ 画中画模式下按返回键退到后台（优化：增加状态判断准确性）
        if (isInPipMode) {
            moveTaskToBack(false);
            return;
        }

        if (channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            return;
        }

        if (remoteManager != null) {
            if (remoteManager.dispatchKeyEvent(KeyEvent.KEYCODE_BACK)) {
                return;
            }
        }

        if (channelPanelController.handleBackPressed()) {
            playerView.requestFocus();
            syncRemoteMode();
            return;
        }

        super.onBackPressed();
    }

    // ====================== 方向键处理（保留，备用） ======================
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

    // ====================== 按键分发 ======================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ✅ 画中画模式下，仅处理返回键，其他按键直接返回（优化：逻辑更清晰）
        if (isInPipMode) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                moveTaskToBack(false);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        cancelPanelAutoHide();

        // 1. 先交给遥控器统一管理器处理
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // 2. 数字选台（备用）
        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        // 3. 频道面板（备用）
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
    private void cancelPanelAutoHide() {
        if (mPanelAutoHideHandler != null && mPanelAutoHideRunnable != null) {
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
    // ✅ 画中画：用户按 Home 键时自动进入画中画（优化：完善参数 + 状态同步 + 版本兼容）
    // ====================================================================
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        SettingsActivity.logOperation("【画中画排查】========== 开始 ==========");
        SettingsActivity.logOperation("【画中画排查】onUserLeaveHint 被调用");

        // 1. 检查是否打开设置页面
        if (isOpeningSettings) {
            SettingsActivity.logOperation("【画中画排查】打开设置页面，跳过");
            SettingsActivity.logOperation("【画中画排查】========== 结束 ==========");
            return;
        }

        // 2. 检查 MainActivity 的开关状态
        SettingsActivity.logOperation("【画中画排查】MainActivity开关状态：" + pipEnable);

        // 3. 检查 PictureInPictureManager 是否存在
        if (pipManager == null) {
            SettingsActivity.logOperation("【画中画排查】❌ pipManager 为 null，初始化失败");
            SettingsActivity.logOperation("【画中画排查】========== 结束 ==========");
            return;
        }
        SettingsActivity.logOperation("【画中画排查】✅ pipManager 已初始化");

        // 4. 检查设备是否支持画中画
        boolean supported = pipManager.isPipSupported();
        SettingsActivity.logOperation("【画中画排查】设备支持画中画：" + supported);

        // 5. 检查 PictureInPictureManager 内部开关
        boolean enabled = pipManager.isPipEnabled();
        SettingsActivity.logOperation("【画中画排查】PIP管理器内部开关：" + enabled);

        // 6. 如果开关不同步，尝试同步
        if (!enabled && pipEnable) {
            SettingsActivity.logOperation("【画中画排查】⚠️ 开关不同步，尝试同步...");
            pipManager.setPipEnabled(pipEnable);
            enabled = pipManager.isPipEnabled();
            SettingsActivity.logOperation("【画中画排查】同步后开关状态：" + enabled);
        }

        // 7. 尝试进入画中画（优化：版本兼容 + 参数校验 + 播放状态同步）
        if (pipEnable && supported && enabled) {
            SettingsActivity.logOperation("【画中画排查】所有条件满足，尝试进入画中画...");
            try {
                // 构建画中画参数（16:9 比例，适配直播画面，版本兼容）
                PictureInPictureParams pipParams = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
                    pipBuilder.setAspectRatio(new Rational(16, 9));
                    pipParams = pipBuilder.build();
                }

                // 同步播放状态到PIP管理器
                if (mPlayerManager != null) {
                    pipManager.updatePlayState(mPlayerManager.isPlaying());
                }

                boolean result = pipManager.enterPictureInPicture(this, pipParams);
                // 同步画中画状态
                isInPipMode = result;
                SettingsActivity.logOperation("【画中画排查】进入结果：" + (result ? "✅ 成功" : "❌ 失败"));
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画排查】❌ 异常：" + e.getMessage());
                e.printStackTrace();
            }
        } else {
            SettingsActivity.logOperation("【画中画排查】❌ 条件不满足，不进入画中画");
            SettingsActivity.logOperation("【画中画排查】  - MainActivity开关：" + pipEnable);
            SettingsActivity.logOperation("【画中画排查】  - 设备支持：" + supported);
            SettingsActivity.logOperation("【画中画排查】  - PIP管理器开关：" + enabled);
        }

        SettingsActivity.logOperation("【画中画排查】========== 结束 ==========");
    }

    // ====================================================================
    // ✅ 画中画：模式变化回调（优化：精准同步状态 + 完善视图处理 + 播放状态保持）
    // ====================================================================
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);

        // 强制同步状态
        isInPipMode = isInPictureInPictureMode;
        SettingsActivity.logOperation("【画中画】模式变化 → " + (isInPictureInPictureMode ? "进入画中画" : "退出画中画"));

        // 同步状态到PIP管理器
        if (pipManager != null) {
            try {
                pipManager.onPipModeChanged(this, isInPictureInPictureMode);
            } catch (Exception e) {
                log("【画中画】模式变化回调失败：" + e.getMessage());
            }
        }

        if (isInPictureInPictureMode) {
            // 进入画中画：隐藏所有面板 + 隐藏信息栏 + 保持播放
            if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                channelPanelController.hidePanel();
            }
            if (infoDisplayManager != null) {
                infoDisplayManager.hideInfoBar();
                infoDisplayManager.hideChannelNum();
            }
            // 画中画模式下保持屏幕常亮
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // 确保PIP模式下播放器继续播放
            if (mPlayerManager != null && !mPlayerManager.isPlaying()) {
                mPlayerManager.play();
            }
        } else {
            // 退出画中画：恢复全屏 + 同步遥控器模式 + 显示信息栏
            if (displayManager != null) {
                displayManager.reapplyFullScreen();
            }
            syncRemoteMode();
            // 恢复当前频道的信息展示
            if (infoDisplayManager != null && channelSourceList.size() > currentPlayIndex) {
                Channel currChannel = channelSourceList.get(currentPlayIndex);
                TVPlayerManager.LiveInfo liveInfo = mPlayerManager.getLiveInfo();
                infoDisplayManager.showInfoBar(currChannel, liveInfo);
                infoDisplayManager.showChannelNum(currentPlayIndex + 1);
            }
            // 退出PIP后仍保持屏幕常亮
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ====================== 生命周期方法 ======================
    @Override
    protected void onPause() {
        // ✅ 画中画模式下不显示占位图、不暂停播放（优化：状态判断更精准）
        if (!isInPipMode) {
            if (!isOpeningSettings) {
                showPlayerPlaceholder();
                // 非PIP模式下暂停播放
                if (mPlayerManager != null && mPlayerManager.isPlaying()) {
                    mPlayerManager.pause();
                }
            } else {
                SettingsActivity.logOperation("【防花屏】打开设置页面，不显示占位图");
            }
        } else {
            // PIP模式下保持播放
            SettingsActivity.logOperation("【画中画】onPause - PIP模式，保持播放");
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

        // 重新加载设置（防止设置页面修改了画中画开关）
        loadSettings();
        // 同步画中画开关到管理器（增加非空判断）
        if (pipManager != null) {
            pipManager.setPipEnabled(pipEnable);
            SettingsActivity.logOperation("【画中画】onResume 同步开关状态：" + pipEnable);
        }

        screenRatioManager.apply();
        displayManager.reapplyFullScreen();

        // ✅ 画中画模式下不隐藏占位图（优化：延迟更合理 + 状态判断）
        if (!isInPipMode) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                hidePlayerPlaceholder();
                // 恢复播放
                if (mPlayerManager != null && !mPlayerManager.isPlaying()) {
                    mPlayerManager.play();
                }
            }, 1500); // 缩短延迟，提升体验
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

        // ✅ 释放画中画管理器（优化：增加状态重置 + 播放暂停）
        if (pipManager != null) {
            try {
                // PIP模式下先退出
                if (isInPipMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    this.finish(); // 退出PIP并销毁Activity
                }
                pipManager.release();
                isInPipMode = false;
                pipEnable = false;
                SettingsActivity.logOperation("【画中画】onDestroy 释放资源完成");
            } catch (Exception e) {
                log("【画中画】释放管理器失败：" + e.getMessage());
            } 
