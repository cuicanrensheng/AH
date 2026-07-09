package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.ui.PlayerView;

import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.*;
import com.tv.live.util.LogCollector;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 主活动类：直播APP的核心页面
 */
public class MainActivity extends AppCompatActivity {
    // 🟢【优化1】使用弱引用替换强引用，彻底解决静态变量导致的内存泄漏和空指针闪退
    private static WeakReference<MainActivity> mInstanceRef;
    
    public List<Channel> channelSourceList = new ArrayList<>(512);
    public int currentPlayIndex = 0;

    private PlayerView playerView;
    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private GestureManager gestureManager;
    private PlayerStateListenerImpl playerStateListener;
    private DisplayManager displayManager;
    private InfoDisplayManager infoDisplayManager;
    private ChannelPanelController channelPanelController;
    private AppCoreManager appCoreManager;
    private TvRemoteManager remoteManager;
    private PictureInPictureManager pipManager;

    private boolean pipEnable = false;
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean isOpeningSettings = false;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // 🔴【新增】统一读取日志开关的 SharedPreferences
    private SharedPreferences sp;

    // 🔴【新增】居中日志悬浮窗相关控件
    private View logWindowContainer;
    private ScrollView logScrollView;
    private TextView tvLogContent;
    private boolean logWindowVisible = false;
    private Runnable logUpdateRunnable;

    // 🔴【新增】回看模式状态与控制栏自动隐藏
    private boolean isInCatchUpMode = false;
    private boolean isControllerShowing = false;
    private final Runnable hideControllerRunnable = new Runnable() {
        @Override
        public void run() {
            if (playerView != null && isControllerShowing) {
                hideExoController();
            }
        }
    };

    /**
     * 🟢【新增】提供给外部（如 ChannelListActivity）安全获取当前实例的方法
     */
    public static MainActivity getRunningInstance() {
        return mInstanceRef != null ? mInstanceRef.get() : null;
    }

    // 🟢【新增】提供回看模式状态查询
    public boolean isInCatchUpMode() {
        return isInCatchUpMode;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 🟢【优化1】初始化弱引用
        mInstanceRef = new WeakReference<>(this);
        
        // 🔴【新增】初始化 SharedPreferences
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        displayManager = new DisplayManager(this);
        setContentView(R.layout.activity_main);
        displayManager.applyFullScreen();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 🔴【新增】绑定居中日志悬浮窗控件
        logWindowContainer = findViewById(R.id.log_window_container);
        logScrollView = findViewById(R.id.log_scroll_view);
        tvLogContent = findViewById(R.id.tv_log_content);

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
        playerView.setUseController(true);
        try {
            playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);
        } catch (Exception e) {
            // 高版本 Media3 直接忽略即可，不影响功能
        }

        initChannelPanelController();
        initRemoteManager();
        initPictureInPicture();
        channelPanelController.handleFirstLaunch();
        initPlayer();
        mPlayerManager.registerDecoderModeReceiver();
        mPlayerManager.registerRendererModeReceiver();

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        remoteManager.setNumberChannelEnable(number_channel_enable);

        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");

        new Thread(() -> {
            appCoreManager.loadLiveAndEpg();
        }).start();
    }

    // 🔴【新增】显示居中日志悬浮窗
    public void showLogWindow() {
        if (logWindowVisible) return;
        logWindowVisible = true;
        logWindowContainer.setVisibility(View.VISIBLE);
        startLogUpdate();
    }

    // 🔴【新增】隐藏居中日志悬浮窗
    public void hideLogWindow() {
        if (!logWindowVisible) return;
        logWindowVisible = false;
        logWindowContainer.setVisibility(View.GONE);
        stopLogUpdate();
    }

    // 🔴【新增】启动日志实时刷新
    private void startLogUpdate() {
        if (logUpdateRunnable != null) return;
        logUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!logWindowVisible) {
                    stopLogUpdate();
                    return;
                }
                // 获取 LogCollector 中收集的所有日志
                String logs = LogCollector.getInstance().getAllLogs();
                tvLogContent.setText(logs);
                // 自动滚动到最新日志（底部）
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                // 每 300ms 刷新一次
                mMainHandler.postDelayed(this, 300);
            }
        };
        mMainHandler.post(logUpdateRunnable);
    }

    // 🔴【新增】停止日志实时刷新
    private void stopLogUpdate() {
        if (logUpdateRunnable != null) {
            mMainHandler.removeCallbacks(logUpdateRunnable);
            logUpdateRunnable = null;
        }
    }

    // 🔴【新增】供 SettingsActivity 调用的日志开关
    public static void toggleLogWindow(boolean enable) {
        MainActivity activity = getRunningInstance();
        if (activity != null) {
            if (enable) {
                activity.showLogWindow();
            } else {
                activity.hideLogWindow();
            }
        }
    }

    // 🔴【新增】回看模式标记
    public void setCatchUpMode(boolean enabled) {
        this.isInCatchUpMode = enabled;
    }

    // 🔴【新增】公共访问器，供外部关闭设置页和操作面板
    public boolean isOpeningSettings() {
        return isOpeningSettings;
    }

    public void setOpeningSettings(boolean opening) {
        this.isOpeningSettings = opening;
    }

    public ChannelPanelController getChannelPanelController() {
        return channelPanelController;
    }

    // 🔴【联动升级】触发显示 ExoPlayer 原生控制栏，并隐藏底部信息栏
    public void showExoController() {
        if (playerView == null) return;
        mMainHandler.removeCallbacks(hideControllerRunnable);
        if (touchListener != null) {
            playerView.setOnTouchListener(null);
        }
        playerView.setUseController(true);
        playerView.showController();
        isControllerShowing = true;
        mMainHandler.postDelayed(hideControllerRunnable, 5000);

        // 🔥【关键】进入回看时，强制隐藏底部信息栏，防止和控制栏抢占底部空间！
        if (infoDisplayManager != null) {
            infoDisplayManager.hideInfoBar();
        }
    }

    // 🔴【新增】隐藏控制栏并归还触摸权
    public void hideExoController() {
        if (playerView == null) return;
        mMainHandler.removeCallbacks(hideControllerRunnable);
        playerView.hideController();
        isControllerShowing = false;
        if (touchListener != null) {
            if (gestureManager != null) {
                final PlayerGestureHelper newGestureHelper = gestureManager.create();
                touchListener.updateGestureHelper(newGestureHelper);
            }
            playerView.setOnTouchListener(touchListener);
        }
    }

    // 🔴【联动升级】退出回看逻辑（切回直播流，关闭控制栏，重置标记，恢复信息栏）
    private void exitPlaybackMode() {
        if (isInCatchUpMode) {
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel ch = channelSourceList.get(currentPlayIndex);
                if (ch != null && mPlayerManager != null) {
                    // 1. 切回当前频道的直播流
                    mPlayerManager.playUrl(ch.getPlayUrl(), ch.getName(), ch);

                    // 🔥【关键】退出回看时，立即获取当前直播信息并恢复底部信息栏！
                    TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
                    if (infoDisplayManager != null && live != null) {
                        infoDisplayManager.showInfoBar(ch, live);
                    }
                }
            }
            // 2. 隐藏控制栏并归还触摸权
            hideExoController();
            // 3. 重置回看标记
            isInCatchUpMode = false;
        } else {
            if (isControllerShowing) {
                hideExoController();
            }
        }
    }

    // ----------------------------------------------------------------------
    // 以下保持原有代码不变
    // ----------------------------------------------------------------------

    private void initPictureInPicture() {
        try {
            pipManager = PictureInPictureManager.getInstance(this);
            pipManager.setPipEnabled(pipEnable);
            pipManager.setListener(new PictureInPictureManager.OnPipListener() {
                @Override
                public void onPipModeChanged(boolean inPip) {
                    log("【画中画】监听器回调：" + (inPip ? "进入" : "退出"));
                }
            });
            log("【画中画】初始化完成，开关状态：" + (pipEnable ? "开启" : "关闭"));
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
    }

    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        remoteManager.setChannelPanelController(channelPanelController);
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            @Override public void onPlayChannelUp() {
                exitPlaybackMode(); // 🔥 联动：切台先退出回看并收回触摸权
                channelPanelController.switchUp();
            }
            @Override public void onPlayChannelDown() {
                exitPlaybackMode(); // 🔥 联动：切台先退出回看并收回触摸权
                channelPanelController.switchDown();
            }
            @Override public void onPlayTogglePanel() { togglePanel(); remoteManager.syncMode(); }
            @Override public void onPlayOpenSettings() { openSettings(); }
            @Override public boolean onPlayBack() { return false; }
            @Override public void onPanelMoveUp() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_UP); }
            @Override public void onPanelMoveDown() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN); }
            @Override public void onPanelMoveLeft() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT); }
            @Override public void onPanelMoveRight() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT); }
            @Override public void onPanelConfirm() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER); }
            @Override public boolean onPanelBack() {
                boolean handled = channelPanelController.handleBackPressed();
                if (!channelPanelController.isPanelOpen()) { remoteManager.syncMode(); }
                return handled;
            }
            @Override public void onPanelMenu() { channelPanelController.toggleCurrentFavorite(); }
            @Override public void onPanelNumber(int number) {}
            @Override public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {}
            @Override public void onSettingsMoveUp() {}
            @Override public void onSettingsMoveDown() {}
            @Override public void onSettingsConfirm() {}
            @Override public boolean onSettingsBack() { return false; }
            @Override public void onSettingsMenu() {}
            @Override public void onSettingsFocusChanged(int position) {}
            @Override public boolean onPipBack() { moveTaskToBack(false); return true; }
            @Override public void onRequestPlayFocus() { playerView.requestFocus(); }
            @Override public void onChannelNumberSelected(int channelIndex) { channelPanelController.playChannel(channelIndex); }
            @Override public void onShowChannelNumber(String number) { try { infoDisplayManager.showChannelNum(Integer.parseInt(number)); } catch (Exception ignored) {} }
            @Override public void onHideChannelNumber() { infoDisplayManager.hideChannelNum(); }
        });
    }

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
                this, tv_channel_num, info_bar, tv_channel_name, tv_tag_fhd, tv_tag_audio,
                tv_bitrate, tv_current_program_name, tv_current_time_range, progress_program,
                tv_remaining_time, tv_next_program_name, tv_next_time_range
        );
    }

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
        dateListManager.setOnDateSelectedListener(pos -> channelPanelController.setCurrentDateIndex(pos));

        channelPanelController = new ChannelPanelController(
                this, panel_layout, ll_left_panel, ll_right_panel, lvGroup, lvChannelList,
                lvChannelListEpg, lvDate, lvEpg, btn_show_epg, btn_back_group,
                groupListManager, channelListManager, channelListManagerEpg,
                dateListManager, epgManagerWrapper, panelManager
        );

        channelPanelController.setOnChannelChangeListener((channel, index) -> playChannel(channel, index));
    }

    private static class PlayerTouchListener implements View.OnTouchListener {
        private final WeakReference<MainActivity> activityRef;
        private PlayerGestureHelper gestureHelper;

        public PlayerTouchListener(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        public void updateGestureHelper(PlayerGestureHelper helper) {
            this.gestureHelper = helper;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (gestureHelper != null) {
                gestureHelper.handleTouch(event);
            }
            return true;
        }
    }
    private PlayerTouchListener touchListener;

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.setOnPlayerViewRecreatedListener(newPlayerView -> {
            MainActivity.this.playerView = newPlayerView;
            gestureManager = new GestureManager(MainActivity.this);
            final PlayerGestureHelper newGestureHelper = gestureManager.create();

            if (touchListener == null) {
                touchListener = new PlayerTouchListener(MainActivity.this);
            }
            touchListener.updateGestureHelper(newGestureHelper);
            newPlayerView.setOnTouchListener(touchListener);
            newPlayerView.requestFocus();
        });

        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            infoDisplayManager.updateLiveInfo(info);
            if (pipManager != null) pipManager.updatePlayState(true);
        });
        mPlayerManager.setOnSourceFailedListener(() -> runOnUiThread(() -> {
            String channelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel ch = channelSourceList.get(currentPlayIndex);
                if (ch != null) channelName = ch.getName();
            }
            appCoreManager.handleSourceFailed(channelName);
        }));
    }

    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveSourceLoaded(List<Channel> channels, boolean fromCache) {
                runOnUiThread(() -> {
                    List<Channel> finalList = appCoreManager.getChannelList();
                    channelSourceList.clear();
                    channelSourceList.addAll(finalList);
                    channelPanelController.setChannels(channelSourceList);
                    if (remoteManager != null) {
                        remoteManager.setTotalChannelCount(channelSourceList.size());
                    }
                    if (!appCoreManager.hasPlayedWithCache()) {
                        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                            Channel ch = channelSourceList.get(currentPlayIndex);
                            playChannel(ch, currentPlayIndex);
                            appCoreManager.setHasPlayedWithCache(true);
                        }
                    }
                    displayManager.hideLoading();
                    log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channelSourceList.size());
                });
            }

            @Override
            public void onLiveSourceFailed(String errorMsg) {
                runOnUiThread(() -> {
                    if (channelSourceList.isEmpty()) {
                        displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                    } else {
                        log("【缓存】使用缓存数据继续播放");
                        displayManager.hideLoading();
                    }
                });
            }

            @Override
            public void onEpgLoaded() {
                runOnUiThread(() -> {
                    if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                        Channel curr = channelSourceList.get(currentPlayIndex);
                        infoDisplayManager.updateEpgInfo(curr);
                    }
                });
            }

            @Override
            public void onLoadTimeout(boolean hasData) {
                runOnUiThread(() -> {
                    log("【加载】超时，自动隐藏加载动画");
                    if (!hasData) {
                        displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                    }
                    displayManager.hideLoading();
                });
            }
        });

        appCoreManager.setOnSourceSkipListener(new AppCoreManager.OnSourceSkipListener() {
            @Override
            public void onNeedSkipChannel() { channelPanelController.switchDown(); }
            @Override
            public void onSkipLimitReached(int maxSkip) {
                Toast.makeText(MainActivity.this, "已跳过 " + maxSkip + " 个失效频道，请检查直播源", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onSourceFailed(String channelName, int failedCount) {}
        });
        appCoreManager.registerReceivers();
    }

    private void loadSettings() {
        // 🔴 直接使用 onCreate 中初始化的 sp，无需重复获取
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);
        pipEnable = sp.getBoolean("pip_enable", false);
        
        // 🔴【修正】确保 ffmpeg 模式在应用重启时也能被正确读取！
        String decoderMode = sp.getString("decoder_mode", "auto");
        int mode = TVPlayerManager.DECODER_MODE_AUTO;
        if ("hard".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_HARD;
        } else if ("soft".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_SOFT;
        } else if ("ffmpeg".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_FFMPEG;
        }
        
        if (mPlayerManager != null) mPlayerManager.setDecoderMode(mode);
        if (remoteManager != null) remoteManager.setNumberChannelEnable(number_channel_enable);
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }
        if (pipManager != null) pipManager.setPipEnabled(pipEnable);
    }

    public boolean isChannelReverse() { return channel_reverse; }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;
        log("【播放】频道名称：" + channel.getName());

        // 🔥 联动：只要切台，立刻强制退出回看模式
        if (isInCatchUpMode) {
            exitPlaybackMode();
        }

        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        mPlayerManager.playUrl(channel.getPlayUrl(), channel.getName(), channel);
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
        infoDisplayManager.showChannelNum(index + 1);
        try {
            appConfig.addRecentChannel(channel.getName());
        } catch (Exception ignored) {}
        appCoreManager.resetSourceFailedCount();

        if (pipManager != null && pipManager.isInPipMode() && channel != null) {
            try {
                pipManager.updateChannelInfo(index + 1, channel.getName() != null ? channel.getName() : "", live != null ? live.bitrate : "");
            } catch (Exception e) {
                log("【画中画】同步频道信息失败：" + e.getMessage());
            }
        }
    }

    public void togglePanel() {
        // 🔥【回看模式禁用面板】
        if (isInCatchUpMode) {
            // 如果处于回看模式，禁止打开面板
            return;
        }
        // 🔥 联动：如果当前设置页是打开的，发送广播关闭它
        if (isOpeningSettings) {
            sendBroadcast(new Intent("com.tv.live.CLOSE_SETTINGS"));
            isOpeningSettings = false;
        }
        channelPanelController.togglePanel();
        remoteManager.syncMode();
    }

    public void playPrev() { channelPanelController.playPrev(); }
    public void playNext() { channelPanelController.playNext(); }

    @Override
    public void onBackPressed() {
        // 🔥 联动：如果处于回看模式且控制栏可见，按返回键优先退出回看
        if (isInCatchUpMode && isControllerShowing) {
            exitPlaybackMode();
            return;
        }
        if (remoteManager != null && remoteManager.handleBackPressed()) return;
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_HELP) {
            if (action == KeyEvent.ACTION_DOWN) {
                // 🔥【回看模式禁用菜单键打开设置】
                if (isInCatchUpMode) {
                    return true; // 直接拦截，不处理
                }
                openSettings();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        // 🔥【回看模式禁用长按返回键打开设置】
        if (isInCatchUpMode && keyCode == KeyEvent.KEYCODE_BACK) {
            return true; // 拦截并消费事件
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            openSettings();
            return true;
        }
        if (remoteManager != null && remoteManager.dispatchKeyLongPress(keyCode)) {
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    public void openSettings() {
        // 🔥【防止重复打开设置】
        if (isOpeningSettings) return;
        // 🔥【回看模式禁用设置】
        if (isInCatchUpMode) return;

        isOpeningSettings = true;
        appCoreManager.beforeOpenSettings();
        
        // 🔥 联动：打开设置时，如果频道面板开着，自动隐藏
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            channelPanelController.hidePanel();
        }
        
        // 🔥 联动：收起 ExoPlayer 控制栏
        hideExoController();
        
        // 🔥【已移除】打开设置时暂停播放（去除自动暂停）
        // if (mPlayerManager != null) {
        //     mPlayerManager.pause();
        // }

        startActivity(new Intent(this, SettingsActivity.class));
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isOpeningSettings) return;
        if (pipManager != null) pipManager.enterPip(this, mPlayerManager, pipEnable);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (remoteManager != null) remoteManager.setInPipMode(isInPictureInPictureMode);
        if (pipManager != null) {
            try {
                pipManager.onPipModeChanged(this, isInPictureInPictureMode);
            } catch (Exception ignored) {}
        }
        if (pipManager != null) {
            if (isInPictureInPictureMode) {
                pipManager.handleEnterPip(this, channelPanelController, infoDisplayManager, mPlayerManager, playerView);
            } else {
                pipManager.handleExitPip(() -> {});
                pipManager.handleExitPipRestore(this, displayManager, playerView, mPlayerManager, channelSourceList, currentPlayIndex, infoDisplayManager);
                remoteManager.syncMode();
            }
        }
    }

    // 🔴【核心改动】让 log() 读取 app_settings 里的 "log_enable"，代替 BuildConfig.DEBUG
    private void log(String msg) {
        if (sp.getBoolean("log_enable", false)) {
            Log.d("MainActivity", msg);
            // 🔴 同时写入 LogCollector，让悬浮窗能显示
            LogCollector.getInstance().addLog("MainActivity", msg);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMainHandler.removeCallbacksAndMessages(null);
        appCoreManager.onPause();
        if (pipManager != null) {
            pipManager.handleOnPause(() -> {
                if (mPlayerManager != null) mPlayerManager.resume();
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (pipManager != null) pipManager.setStopCalled(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isOpeningSettings = false;
        appCoreManager.onResume();
        if (pipManager != null) pipManager.setStopCalled(false);
        loadSettings();
        screenRatioManager.apply();
        displayManager.reapplyFullScreen();

        if (pipManager == null || !pipManager.isInPipMode()) {
            mMainHandler.postDelayed(() -> {
                if (pipManager != null && mPlayerManager != null) {
                    pipManager.resumePlayback(mPlayerManager);
                }
            }, 200);
        }
        remoteManager.syncMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) displayManager.reapplyFullScreen();
        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 🟢【优化1】销毁时清空弱引用，释放所有资源
        if (mInstanceRef != null) {
            mInstanceRef.clear();
            mInstanceRef = null;
        }
        
        mMainHandler.removeCallbacksAndMessages(null);
        if (infoDisplayManager != null) infoDisplayManager.release();
        if (remoteManager != null) remoteManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();
        if (pipManager != null) pipManager.release();
        if (mPlayerManager != null) mPlayerManager.release();
    }
}
