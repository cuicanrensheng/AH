package com.tv.live;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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

@SuppressLint("UnsafeOptInUsageError")
public class MainActivity extends AppCompatActivity {
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
    private PictureInPictureManager pipManager;
    private View panelLayout;
    private PlayerControlManager playerControlManager;

    private ActivityKeyHandler keyHandler;
    private ActivityLogManager logManager;
    private ActivitySettingsManager settingsManager;
    private ActivityExitManager exitManager;

    private boolean isInCatchUpMode = false;

    private PlayerTouchListener touchListener;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;

    public static MainActivity getRunningInstance() {
        return mInstanceRef != null ? mInstanceRef.get() : null;
    }

    public boolean isInCatchUpMode() {
        return isInCatchUpMode;
    }

    public TVPlayerManager getPlayerManager() {
        return mPlayerManager;
    }

    public PictureInPictureManager getPipManager() {
        return pipManager;
    }

    public PlayerTouchListener getTouchListener() {
        return touchListener;
    }

    public PlayerView getPlayerView() {
        return playerView;
    }

    public ChannelPanelController getChannelPanelController() {
        return channelPanelController;
    }

    public List<Channel> getChannelSourceList() {
        return channelSourceList;
    }

    public boolean superDispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    public PlayerControlManager getPlayerControlManager() {
        return playerControlManager;
    }

    public InfoDisplayManager getInfoDisplayManager() {
        return infoDisplayManager;
    }

    public ScreenRatioManager getScreenRatioManager() {
        return screenRatioManager;
    }

    public ActivityLogManager getLogManager() {
        return logManager;
    }

    public ActivitySettingsManager getSettingsManager() {
        return settingsManager;
    }

    public ActivityKeyHandler getKeyHandler() {
        return keyHandler;
    }

    public boolean isSettingsDialogShowing() {
        return settingsManager != null && settingsManager.isSettingsDialogShowing();
    }

    public boolean dispatchPanelKeyEvent(KeyEvent event) {
        return panelLayout != null && panelLayout.dispatchKeyEvent(event);
    }

    public boolean hasPanelLayout() {
        return panelLayout != null;
    }

    public boolean isNumberChannelEnabled() {
        return settingsManager != null && settingsManager.isNumberChannelEnabled();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long tStart = System.currentTimeMillis();
        mInstanceRef = new WeakReference<>(this);
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        long t1 = System.currentTimeMillis();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        long t2 = System.currentTimeMillis();
        displayManager = new DisplayManager(this);
        long t3 = System.currentTimeMillis();
        setContentView(R.layout.activity_main);
        long t4 = System.currentTimeMillis();
        displayManager.applyFullScreen();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        long t5 = System.currentTimeMillis();

        initLogManager();
        initInfoDisplayManager();
        long t7 = System.currentTimeMillis();
        appConfig = AppConfig.getInstance(this);
        long t8 = System.currentTimeMillis();

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setFocusable(false);
        playerView.setFocusableInTouchMode(false);
        try {
            playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);
        } catch (Exception e) {}

        initChannelPanelController();
        long t9 = System.currentTimeMillis();
        initPictureInPicture();
        channelPanelController.handleFirstLaunch();

        initPlayer();
        mPlayerManager.registerDecoderModeReceiver();
        mPlayerManager.registerRendererModeReceiver();
        long t10 = System.currentTimeMillis();

        initSettingsManager();
        settingsManager.loadSettings();
        long t11 = System.currentTimeMillis();

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);

        initAppCoreManager();
        long t12 = System.currentTimeMillis();
        displayManager.showLoading("正在加载直播源...");
        new Thread(() -> appCoreManager.loadLiveAndEpg()).start();
        long t13 = System.currentTimeMillis();

        initKeyHandler();
        initExitManager();

        android.util.Log.i("MainActivity", "【启动计时】onCreate 各段耗时(ms)："
                + " sp_init=" + (t1 - tStart)
                + " setOrientation=" + (t2 - t1)
                + " DisplayManager=" + (t3 - t2)
                + " setContentView=" + (t4 - t3)
                + " fullscreen+flags=" + (t5 - t4)
                + " initInfoDisplay+AppConfig=" + (t8 - t7)
                + " initChannelPanel=" + (t9 - t8)
                + " initPlayer=" + (t10 - t9)
                + " loadSettings=" + (t11 - t10)
                + " initAppCore=" + (t12 - t11)
                + " loadLiveEpgSchedule=" + (t13 - t12)
                + " 总计=" + (System.currentTimeMillis() - tStart));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            return super.onKeyDown(keyCode, event);
        } catch (Exception e) {
            android.util.Log.e("KEY_DEBUG", "onKeyDown 异常 keyCode=" + keyCode + ": " + e.getMessage(), e);
            return true;
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        try {
            return super.onKeyUp(keyCode, event);
        } catch (Exception e) {
            android.util.Log.e("KEY_DEBUG", "onKeyUp 异常 keyCode=" + keyCode + ": " + e.getMessage(), e);
            return true;
        }
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (keyHandler != null) {
            return keyHandler.dispatchKeyEvent(event);
        }
        return super.dispatchKeyEvent(event);
    }

    private void initLogManager() {
        View logWindowContainer = findViewById(R.id.log_window_container);
        ScrollView logScrollView = findViewById(R.id.log_scroll_view);
        TextView tvLogContent = findViewById(R.id.tv_log_content);

        logManager = new ActivityLogManager(mMainHandler);
        logManager.initViews(logWindowContainer, logScrollView, tvLogContent);
    }

    private void initSettingsManager() {
        settingsManager = new ActivitySettingsManager(this, sp, appConfig);
        settingsManager.registerReceiver();
    }

    private void initKeyHandler() {
        keyHandler = new ActivityKeyHandler(this, mMainHandler);
    }

    private void initExitManager() {
        exitManager = new ActivityExitManager(this, mMainHandler);
    }

    public void showLogWindow() {
        if (logManager != null) {
            logManager.showLogWindow();
        }
    }

    public void hideLogWindow() {
        if (logManager != null) {
            logManager.hideLogWindow();
        }
    }

    public static void toggleLogWindow(boolean enable) {
        MainActivity activity = getRunningInstance();
        ActivityLogManager.toggleLogWindow(activity, enable);
    }

    public void setCatchUpMode(boolean enabled) {
        this.isInCatchUpMode = enabled;
        if (!enabled && keyHandler != null) {
            keyHandler.resetOkKeyState();
        }
    }

    public void showExoController() {
        if (playerControlManager != null) {
            playerControlManager.showExoController();
        }
    }

    public void hideExoController() {
        if (playerControlManager != null) {
            playerControlManager.hideExoController();
        }
    }

    private void exitPlaybackMode() {
        if (isInCatchUpMode) {
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel ch = channelSourceList.get(currentPlayIndex);
                if (ch != null && mPlayerManager != null) {
                    mPlayerManager.playUrl(ch.getPlayUrl(), ch.getName(), ch);
                    TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
                    if (infoDisplayManager != null && live != null) {
                        infoDisplayManager.showInfoBar(ch, live);
                    }
                }
            }
            hideExoController();
            isInCatchUpMode = false;
        } else {
            if (playerControlManager != null && playerControlManager.isControllerShowing()) {
                hideExoController();
            }
        }
    }

    private void initPictureInPicture() {
        try {
            pipManager = PictureInPictureManager.getInstance(this);
            pipManager.setPipEnabled(settingsManager != null && settingsManager.isPipEnabled());
            pipManager.setListener(new PictureInPictureManager.OnPipListener() {
                @Override
                public void onPipModeChanged(boolean inPip) {
                    log("【画中画】监听器回调：" + (inPip ? "进入" : "退出"));
                }
            });
            log("【画中画】初始化完成");
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
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
        panelLayout = findViewById(R.id.panel_layout);
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
        PanelManager panelManager = new PanelManager(panelLayout, channelListManager, epgManagerWrapper);

        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> channelPanelController.setCurrentDateIndex(pos));

        channelPanelController = new ChannelPanelController(
                this, panelLayout, ll_left_panel, ll_right_panel, lvGroup, lvChannelList,
                lvChannelListEpg, lvDate, lvEpg, btn_show_epg, btn_back_group,
                groupListManager, channelListManager, channelListManagerEpg,
                dateListManager, epgManagerWrapper, panelManager
        );

        channelPanelController.setOnChannelChangeListener((channel, index) -> playChannel(channel, index));
    }

    public static class PlayerTouchListener implements View.OnTouchListener {
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
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick();
            }
            return true;
        }
    }

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);

        gestureManager = new GestureManager(this);
        playerControlManager = new PlayerControlManager(this, gestureManager, infoDisplayManager);

        mPlayerManager.setOnPlayerViewRecreatedListener(newPlayerView -> {
            MainActivity.this.playerView = newPlayerView;

            gestureManager = new GestureManager(MainActivity.this);
            final PlayerGestureHelper newGestureHelper = gestureManager.create();

            if (playerControlManager != null) {
                playerControlManager.updateGestureManager(gestureManager);
            }

            if (touchListener == null) {
                touchListener = new PlayerTouchListener(MainActivity.this);
            }
            touchListener.updateGestureHelper(newGestureHelper);
            newPlayerView.setOnTouchListener(touchListener);

            if (playerControlManager != null) {
                newPlayerView.setUseController(false);
                playerControlManager.hideExoController();
            }

            Log.d("MainActivity", "PlayerView 重建完成");
        });

        mPlayerManager.attachPlayerView(playerView);

        touchListener = new PlayerTouchListener(MainActivity.this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        touchListener.updateGestureHelper(gestureHelper);
        playerView.setOnTouchListener(touchListener);

        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            infoDisplayManager.updateLiveInfo(info);
            if (pipManager != null) pipManager.updatePlayState(true);
        });

        mPlayerManager.setOnSourceFailedListener(() -> mMainHandler.post(() -> {
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
                mMainHandler.post(() -> {
                    List<Channel> finalList = appCoreManager.getChannelList();
                    channelSourceList.clear();
                    channelSourceList.addAll(finalList);
                    channelPanelController.setChannels(channelSourceList);

                    if (!channelSourceList.isEmpty()) {
                        String lastChannelName = mPlayerManager != null
                                ? (mPlayerManager.getCurrentChannel() != null
                                    ? mPlayerManager.getCurrentChannel().getName()
                                    : null)
                                : null;
                        int matchedIndex = -1;
                        if (!TextUtils.isEmpty(lastChannelName)) {
                            for (int i = 0; i < channelSourceList.size(); i++) {
                                if (lastChannelName.equals(channelSourceList.get(i).getName())) {
                                    matchedIndex = i;
                                    break;
                                }
                            }
                        }
                        if (matchedIndex >= 0) {
                            currentPlayIndex = matchedIndex;
                        } else if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
                            currentPlayIndex = 0;
                        }
                    } else {
                        currentPlayIndex = 0;
                    }
                    appConfig.setLastPlayIndex(currentPlayIndex);
                    channelPanelController.setCurrentPlayIndex(currentPlayIndex);

                    appCoreManager.setHasPlayedWithCache(true);
                    if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                        Channel ch = channelSourceList.get(currentPlayIndex);
                        playChannel(ch, currentPlayIndex);
                    }

                    displayManager.hideLoading();
                    log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channelSourceList.size());
                });
            }

            @Override
            public void onLiveSourceFailed(String errorMsg) {
                mMainHandler.post(() -> {
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
                mMainHandler.post(() -> {
                    if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                        Channel curr = channelSourceList.get(currentPlayIndex);
                        infoDisplayManager.updateEpgInfo(curr);
                    }
                });
            }

            @Override
            public void onLoadTimeout(boolean hasData) {
                mMainHandler.post(() -> {
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

        appCoreManager.setOnRefreshListener(() -> {
            mMainHandler.post(() -> {
                List<Channel> newList = appCoreManager.getChannelList();
                channelSourceList.clear();
                channelSourceList.addAll(newList);
                channelPanelController.setChannels(channelSourceList);

                if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
                    currentPlayIndex = 0;
                }
                appConfig.setLastPlayIndex(currentPlayIndex);
                channelPanelController.setCurrentPlayIndex(currentPlayIndex);
                if (!channelSourceList.isEmpty()) {
                    playChannel(channelSourceList.get(currentPlayIndex), currentPlayIndex);
                }
                log("【刷新】频道列表已更新，频道数：" + channelSourceList.size());
            });
        });

        appCoreManager.registerReceivers();
    }

    public boolean isChannelReverse() {
        return settingsManager != null && settingsManager.isChannelReverse();
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    public void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;

        if (keyHandler != null) {
            keyHandler.clearNumberInputBuffer();
        }

        if (isInCatchUpMode) {
            exitPlaybackMode();
        }

        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        mPlayerManager.playUrl(channel.getPlayUrl(), channel.getName(), channel);
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        if (infoDisplayManager != null) {
            infoDisplayManager.showInfoBar(channel, live);
            infoDisplayManager.showChannelNum(index + 1);
        }
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
        if (isInCatchUpMode) {
            return;
        }
        channelPanelController.togglePanel();
    }

    public void playPrev() { channelPanelController.playPrev(); }
    public void playNext() { channelPanelController.playNext(); }

    public void showExitMenu() {
        if (exitManager != null) {
            exitManager.showExitMenu();
        }
    }

    @Override
    public void onBackPressed() {
        try {
            if (isInCatchUpMode && playerControlManager != null && playerControlManager.isControllerShowing()) {
                exitPlaybackMode();
                return;
            }

            if (channelPanelController != null) {
                try {
                    if (channelPanelController.isPanelOpen()) {
                        if (channelPanelController.backFromRightPanel()) {
                            return;
                        }
                        channelPanelController.hidePanel();
                        return;
                    }
                } catch (Exception e) {
                    android.util.Log.e("KEY_DEBUG", "onBackPressed hidePanel 异常: " + e.getMessage(), e);
                }
            }

            if (exitManager != null && exitManager.isExitMenuShowing()) {
                try {
                    exitManager.dismissExitMenu();
                    return;
                } catch (Exception e) {
                    android.util.Log.e("KEY_DEBUG", "onBackPressed exitMenu 异常: " + e.getMessage(), e);
                }
            }

            if (settingsManager != null && settingsManager.isSettingsDialogShowing()) {
                try {
                    settingsManager.dismissSettingsDialog();
                    return;
                } catch (Exception e) {
                    android.util.Log.e("KEY_DEBUG", "onBackPressed settingsDialog 异常: " + e.getMessage(), e);
                }
            }

            if (settingsManager != null && settingsManager.hasRecentSettingsClose()) {
                android.util.Log.d("KEY_DEBUG", "设置刚关闭，忽略退出操作");
                return;
            }

            if (settingsManager != null && settingsManager.isExitDialogEnabled()) {
                showExitMenu();
            } else {
                finishAffinity();
            }
        } catch (Exception e) {
            android.util.Log.e("KEY_DEBUG", "onBackPressed 异常: " + e.getMessage(), e);
        }
    }

    public void performBackPressed() {
        onBackPressed();
    }

    public void openSettings() {
        if (settingsManager != null) {
            settingsManager.openSettings();
        }
    }

    public void refreshSettings() {
        if (settingsManager != null) {
            mMainHandler.post(() -> settingsManager.refreshSettings());
        }
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        currentPlayIndex = 0;
        appConfig.setLastPlayIndex(0);
        channelPanelController.setCurrentPlayIndex(0);
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (pipManager != null) pipManager.enterPip(this, mPlayerManager, settingsManager != null && settingsManager.isPipEnabled());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
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
            }
        }
    }

    private void log(String msg) {
        Log.d("MainActivity", msg);
        LogCollector.getInstance().addLog("播放", msg);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (settingsManager != null && settingsManager.isOpeningSettings()) {
            return;
        }
        mMainHandler.removeCallbacksAndMessages(null);
        if (appCoreManager != null) {
            appCoreManager.onPause();
        }

        boolean isSystemVisible = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            isSystemVisible = isInMultiWindowMode() || isInPictureInPictureMode();
        }

        if (pipManager != null && pipManager.isInPipMode()) {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        } else if (isSystemVisible) {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        } else {
            if (mPlayerManager != null) {
                mPlayerManager.onBackground();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (pipManager != null) pipManager.setStopCalled(true);

        boolean isSystemVisible = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            isSystemVisible = isInMultiWindowMode() || isInPictureInPictureMode();
        }
        if (isSystemVisible) {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        }
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        if (isInMultiWindowMode) {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100) {
            if (settingsManager != null) {
                settingsManager.setOpeningSettings(false);
            }
            if (keyHandler != null) {
                keyHandler.resetOkKeyState();
            }
            android.util.Log.d("MainActivity", "onActivityResult: Settings closed");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settingsManager != null) {
            settingsManager.setOpeningSettings(false);
            settingsManager.loadSettings();
        }
        appCoreManager.onResume();
        if (pipManager != null) pipManager.setStopCalled(false);
        if (screenRatioManager != null) {
            screenRatioManager.apply();
        }
        displayManager.reapplyFullScreen();

        if (pipManager == null || !pipManager.isInPipMode()) {
            if (mPlayerManager != null) {
                mPlayerManager.onForeground();
            }
            if (playerControlManager != null) {
                playerControlManager.onResume();
            }
        } else {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        }

        if (channelPanelController != null) {
            channelPanelController.clearPanelFocus();
        }

        if (playerControlManager != null) {
            playerControlManager.onSettingsClosed();
        }
    }

    private boolean wasWindowFocused = true;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            displayManager.reapplyFullScreen();
            if (!wasWindowFocused && mPlayerManager != null) {
                mPlayerManager.resume();
            }
        } else {
            boolean isSystemVisible = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isSystemVisible = isInMultiWindowMode() || isInPictureInPictureMode();
            }
            boolean isPip = pipManager != null && pipManager.isInPipMode();
            if (mPlayerManager != null && !isOpeningSettings() && !isSystemVisible && !isPip) {
                mPlayerManager.pause();
            }
        }
        wasWindowFocused = hasFocus;
        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    private boolean isOpeningSettings() {
        return settingsManager != null && settingsManager.isOpeningSettings();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mInstanceRef != null) {
            mInstanceRef.clear();
            mInstanceRef = null;
        }

        mMainHandler.removeCallbacksAndMessages(null);

        if (keyHandler != null) {
            keyHandler.cleanup();
        }
        if (logManager != null) {
            logManager.cleanup();
        }
        if (settingsManager != null) {
            settingsManager.cleanup();
        }
        if (exitManager != null) {
            exitManager.cleanup();
        }

        if (infoDisplayManager != null) infoDisplayManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();
        if (pipManager != null) pipManager.release();

        if (mPlayerManager != null) {
            mPlayerManager.setOnPlayStateListener(null);
            mPlayerManager.setOnLiveInfoUpdateListener(null);
            mPlayerManager.setOnSourceFailedListener(null);
            mPlayerManager.release();
            mPlayerManager = null;
        }

        if (playerControlManager != null) {
            playerControlManager.release();
        }
    }
}
