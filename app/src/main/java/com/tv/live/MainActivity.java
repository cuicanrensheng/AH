package com.tv.live;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public List<Channel> channelSourceList = new ArrayList<>();
    public int currentPlayIndex = 0;
    private PlayerView playerView;
    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private GestureManager gestureManager;
    // ✅ 修复1：手势帮助类 成员变量
    private PlayerGestureHelper gestureHelper;
    private KeyEventManager keyEventManager;
    private PlayerStateListenerImpl playerStateListener;
    private ChannelNumberManager channelNumberManager;
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
    public static List<String> logList = new ArrayList<>();

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

        initChannelPanelController();
        initRemoteManager();
        initPictureInPicture();

        if (mIsFirstLaunch) {
            mPanelAutoHideHandler.postDelayed(mPanelAutoHideRunnable, 3000);
            mIsFirstLaunch = false;
        }

        initPlayer();
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // ✅ 修复2：初始化手势（使用成员变量）
        gestureManager = new GestureManager(this);
        gestureHelper = gestureManager.create();
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
        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        }

        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();
    }

    // ✅ 修复3：画中画初始化 + 注册稳定回调
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

            // 退出画中画稳定后 恢复所有交互
            pipManager.setCompleteListener(new PictureInPictureManager.OnPipCompleteListener() {
                @Override
                public void onPipEnterComplete() {}
                @Override
                public void onPipExitComplete() {
                    SettingsActivity.logOperation("【画中画】退出稳定，恢复交互");
                    restoreAllInteraction();
                }
            });

            log("【画中画】初始化完成，开关状态：" + (pipEnable ? "开启" : "关闭"));
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持：" + pipManager.isPipSupported());
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
    }

    // ✅ 核心修复：统一恢复 手势/切台/焦点/触摸监听
    private void restoreAllInteraction() {
        SettingsActivity.logOperation("【画中画恢复】========== 开始恢复所有交互 ==========");

        // 1. 恢复手势
        if (gestureManager != null) {
            gestureManager.setEnabled(true);
            gestureManager.reset();
            SettingsActivity.logOperation("【画中画恢复】✅ 手势已启用");
        }

        // 2. 重新绑定触摸监听
        if (playerView != null && gestureHelper != null) {
            playerView.setOnTouchListener((v, event) -> {
                gestureHelper.handleTouch(event);
                return true;
            });
            SettingsActivity.logOperation("【画中画恢复】✅ 触摸监听已重绑");
        }

        // 3. 强制获取焦点
        if (playerView != null) {
            playerView.setFocusable(true);
            playerView.setFocusableInTouchMode(true);
            playerView.requestFocus();
            playerView.requestFocusFromTouch();
            SettingsActivity.logOperation("【画中画恢复】✅ 焦点已获取");
        }

        // 4. 同步遥控器/切台
        syncRemoteMode();
        if (channelPanelController != null) {
            channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        }
        SettingsActivity.logOperation("【画中画恢复】✅ 切台/遥控器已恢复");

        // 5. 延迟二次恢复（解决系统动画延迟问题）
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (playerView != null) {
                playerView.requestFocus();
                playerView.requestLayout();
            }
            if (gestureManager != null) gestureManager.reset();
            SettingsActivity.logOperation("【画中画恢复】========== 恢复完成 ==========");
        }, 200);
    }

    private void hideAllUiForPip() {
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            channelPanelController.hidePanel();
        }
        if (infoDisplayManager != null) {
            infoDisplayManager.hideInfoBar();
            infoDisplayManager.hideChannelNum();
        }
    }

    private void keepPlayingInPip() {
        try {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
                if (playerView != null) {
                    mPlayerManager.attachPlayerView(playerView);
                    mPlayerManager.resume();
                }
            }
        } catch (Exception e) {
            log("【画中画】恢复播放失败：" + e.getMessage());
        }
    }

    private void resumeCurrentChannel() {
        try {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        } catch (Exception e) {
            log("【画中画】恢复播放失败：" + e.getMessage());
        }
    }

    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            @Override
            public void onPlayChannelUp() { channelPanelController.switchUp(); }
            @Override
            public void onPlayChannelDown() { channelPanelController.switchDown(); }
            @Override
            public void onPlayTogglePanel() { togglePanel(); syncRemoteMode(); }
            @Override
            public void onPlayOpenSettings() { openSettings(); }
            @Override
            public boolean onPlayBack() { return false; }
            @Override
            public void onPanelMoveUp() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_UP); }
            @Override
            public void onPanelMoveDown() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN); }
            @Override
            public void onPanelMoveLeft() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT); }
            @Override
            public void onPanelMoveRight() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT); }
            @Override
            public void onPanelConfirm() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER); }
            @Override
            public boolean onPanelBack() {
                boolean handled = channelPanelController.handleBackPressed();
                if (!channelPanelController.isPanelOpen()) syncRemoteMode();
                return handled;
            }
            @Override
            public void onPanelMenu() {
                boolean isFavorite = channelPanelController.toggleCurrentFavorite();
            }
            @Override
            public void onPanelNumber(int number) {
                int keyCode = KeyEvent.KEYCODE_0 + number;
                channelNumberManager.handleNumberKey(keyCode);
            }
            @Override
            public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {}
            @Override public void onSettingsMoveUp() {}
            @Override public void onSettingsMoveDown() {}
            @Override public void onSettingsConfirm() {}
            @Override public boolean onSettingsBack() { return false; }
            @Override public void onSettingsMenu() {}
            @Override public void onSettingsFocusChanged(int position) {}
        });
    }

    private void syncRemoteMode() {
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            remoteManager.setMode(TvRemoteManager.Mode.CHANNEL_PANEL_MODE);
            remoteManager.setRightPanelOpen(channelPanelController.isRightPanelOpen());
        } else {
            remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
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
                this, tv_channel_num, info_bar, tv_channel_name, tv_tag_fhd,
                tv_tag_audio, tv_bitrate, tv_current_program_name, tv_current_time_range,
                progress_program, tv_remaining_time, tv_next_program_name, tv_next_time_range
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
                this, panel_layout, ll_left_panel, ll_right_panel, lvGroup,
                lvChannelList, lvChannelListEpg, lvDate, lvEpg, btn_show_epg, btn_back_group,
                groupListManager, channelListManager, channelListManagerEpg,
                dateListManager, epgManagerWrapper, panelManager
        );

        channelPanelController.setOnChannelChangeListener((channel, index) -> playChannel(channel, index));
    }

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            infoDisplayManager.updateLiveInfo(info);
            if (pipManager != null) pipManager.updatePlayState(true);
        });
    }

    private void initChannelNumberManager() {
        channelNumberManager = new ChannelNumberManager(
                new ChannelNumberManager.OnChannelNumberListener() {
                    @Override
                    public void onChannelSelected(int channelIndex) {
                        channelPanelController.playChannel(channelIndex);
                    }
                    @Override
                    public void showChannelNumber(String number) {
                        try { infoDisplayManager.showChannelNum(Integer.parseInt(number)); } catch (Exception ignored) {}
                    }
                    @Override
                    public void hideChannelNumber() { infoDisplayManager.hideChannelNum(); }
                }, number_channel_enable
        );
    }

    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveSourceLoaded(List<Channel> channels, boolean fromCache) {
                runOnUiThread(() -> {
                    channelSourceList.clear();
                    channelSourceList.addAll(channels);
                    channelPanelController.setChannels(channels);
                    if (!appCoreManager.hasPlayedWithCache()) {
                        if (currentPlayIndex >= 0 && currentPlayIndex < channels.size()) {
                            playChannel(channels.get(currentPlayIndex), currentPlayIndex);
                            appCoreManager.setHasPlayedWithCache(true);
                        }
                    }
                    displayManager.hideLoading();
                });
            }
            @Override
            public void onLiveSourceFailed(String errorMsg) {
                runOnUiThread(() -> {
                    if (channelSourceList.isEmpty()) {
                        displayManager.updateLoadingText("加载失败，请检查网络");
                    } else {
                        displayManager.hideLoading();
                    }
                });
            }
            @Override
            public void onEpgLoaded() {
                runOnUiThread(() -> {
                    if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                        infoDisplayManager.updateEpgInfo(channelSourceList.get(currentPlayIndex));
                    }
                });
            }
            @Override
            public void onLoadTimeout(boolean hasData) {
                runOnUiThread(() -> {
                    displayManager.hideLoading();
                });
            }
        });
        appCoreManager.registerReceivers();
    }

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        pipEnable = sp.getBoolean("pip_enable", false);

        if (channelNumberManager != null) channelNumberManager.setEnable(number_channel_enable);
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }
        if (pipManager != null) pipManager.setPipEnabled(pipEnable);
    }

    public boolean isChannelReverse() { return channel_reverse; }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty() || index <0 || index >= channelSourceList.size()) return;
        playChannel(channelSourceList.get(index), index);
    }

    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;
        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        mPlayerManager.playUrl(channel.getPlayUrl());
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
        infoDisplayManager.showChannelNum(index + 1);
    }

    public void togglePanel() {
        channelPanelController.togglePanel();
        syncRemoteMode();
    }

    public void playPrev() { channelPanelController.playPrev(); }
    public void playNext() { channelPanelController.playNext(); }

    @Override
    public void onBackPressed() {
        if (pipManager != null && pipManager.isInPipMode()) {
            moveTaskToBack(false);
            return;
        }
        if (channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            return;
        }
        if (remoteManager != null && remoteManager.dispatchKeyEvent(KeyEvent.KEYCODE_BACK)) return;
        if (channelPanelController.handleBackPressed()) {
            playerView.requestFocus();
            syncRemoteMode();
            return;
        }
        super.onBackPressed();
    }

    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: channelPanelController.switchUp(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: channelPanelController.switchDown(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (channelNumberManager.isInputting()) {
                    channelNumberManager.confirmChannelNum();
                } else {
                    togglePanel();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                togglePanel();
                return true;
            default: return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (pipManager != null && pipManager.isInPipMode()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                moveTaskToBack(false);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        cancelPanelAutoHide();
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) return true;
        if (channelNumberManager.handleNumberKey(keyCode)) return true;
        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) return true;
        if (handleDirectionKey(keyCode)) return true;
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    private void cancelPanelAutoHide() {
        mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
    }

    public void openSettings() {
        isOpeningSettings = true;
        appCoreManager.beforeOpenSettings();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isOpeningSettings) return;
        if (pipManager == null) return;
        if (pipManager.shouldEnterPip()) {
            try {
                PictureInPictureParams pipParams = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    pipParams = new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9)).build();
                }
                pipManager.enterPictureInPicture(this, pipParams);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // ✅ 修复4：画中画模式切换
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        SettingsActivity.logOperation("【画中画】模式变化：" + (isInPictureInPictureMode ? "进入" : "退出"));

        if (pipManager != null) {
            pipManager.onPipModeChanged(this, isInPictureInPictureMode);
        }

        if (isInPictureInPictureMode) {
            // 进入画中画：禁用手势
            if (gestureManager != null) gestureManager.setEnabled(false);
            hideAllUiForPip();
            keepPlayingInPip();
        } else {
            // 退出画中画：基础恢复 + 稳定回调二次恢复
            if (gestureManager != null) {
                gestureManager.setEnabled(true);
                gestureManager.reset();
            }
            pipManager.handleExitPip(() -> mPlayerManager.release());
            resumeCurrentChannel();
            syncRemoteMode();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        appCoreManager.onPause();
        if (pipManager != null) {
            pipManager.handleOnPause(mPlayerManager::resume, mPlayerManager::pause);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (pipManager != null) pipManager.setStopCalled(true);
    }

    // ✅ 修复5：onResume 双重恢复
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
            new Handler(Looper.getMainLooper()).postDelayed(()->{
                resumeCurrentChannel();
                restoreAllInteraction();
            }, 300);
        }
        syncRemoteMode();
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
        mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        if (infoDisplayManager != null) infoDisplayManager.release();
        if (channelNumberManager != null) channelNumberManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();
        if (pipManager != null) pipManager.release();
        mInstance = null;
    }

    private void log(String msg) {
        logList.add(msg);
        Log.d("MainActivity", msg);
    }

    private void logPipViewSize(String tag, View view) {}
    private void logPipWindowSize() {}
}
