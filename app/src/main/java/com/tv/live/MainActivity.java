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
    // ✅ 第1处修改：把 gestureHelper 改成成员变量，方便退出画中画时重新绑定
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
        gestureManager = new GestureManager(this);
        // ✅ 第2处修改：去掉 final 和类型声明，直接用成员变量
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
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持：" + pipManager.isPipSupported());
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
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
                log("【画中画】调用 resume() 恢复播放");
                if (playerView != null) {
                    mPlayerManager.attachPlayerView(playerView);
                    mPlayerManager.resume();
                    log("【画中画】重新绑定后再次恢复");
                }
            }
        } catch (Exception e) {
            log("【画中画】恢复播放失败：" + e.getMessage());
            try {
                if (channelSourceList != null 
                        && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                    Channel channel = channelSourceList.get(currentPlayIndex);
                    if (channel != null && channel.getPlayUrl() != null) {
                        mPlayerManager.playUrl(channel.getPlayUrl());
                        log("【画中画】兜底：重新加载当前频道");
                    }
                }
            } catch (Exception e2) {
                log("【画中画】兜底播放也失败：" + e2.getMessage());
            }
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
    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                infoDisplayManager.updateLiveInfo(info);
                if (pipManager != null) {
                    pipManager.updatePlayState(true);
                }
            }
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
                        try {
                            infoDisplayManager.showChannelNum(Integer.parseInt(number));
                        } catch (Exception e) {
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
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);
        pipEnable = sp.getBoolean("pip_enable", false);
        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        }
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }
        if (pipManager != null) {
            pipManager.setPipEnabled(pipEnable);
        }
        SettingsActivity.logOperation("【设置】EPG开关：" + epg_enable);
        SettingsActivity.logOperation("【设置】切台反转：" + channel_reverse);
        SettingsActivity.logOperation("【设置】数字选台：" + number_channel_enable);
        SettingsActivity.logOperation("【设置】自动更新源：" + auto_update_source);
        SettingsActivity.logOperation("【设置】画中画开关：" + pipEnable);
    }
    public boolean isChannelReverse() {
        return channel_reverse;
    }
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
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
        appConfig.setLastPlayIndex(index);
        mPlayerManager.playUrl(channel.getPlayUrl());
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
        infoDisplayManager.showChannelNum(index + 1);
        try {
            appConfig.addRecentChannel(channel.getName());
        } catch (Exception e) {
        }
        if (pipManager != null && pipManager.isInPipMode() && channel != null) {
            try {
                pipManager.updateChannelInfo(index + 1,
                        channel.getName() != null ? channel.getName() : "",
                        live != null ? live.bitrate : "");
            } catch (Exception e) {
                log("【画中画】同步频道信息失败：" + e.getMessage());
            }
        }
    }
    public void togglePanel() {
        channelPanelController.togglePanel();
        syncRemoteMode();
    }
    public void playPrev() {
        channelPanelController.playPrev();
    }
    public void playNext() {
        channelPanelController.playNext();
    }
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
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        if (channelNumberManager.handleNumberKey(keyCode)) return true;
        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }
        if (handleDirectionKey(keyCode)) return true;
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }
    private void cancelPanelAutoHide() {
        if (mPanelAutoHideHandler != null && mPanelAutoHideRunnable != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        }
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
        SettingsActivity.logOperation("【画中画排查】========== 开始 ==========");
        SettingsActivity.logOperation("【画中画排查】onUserLeaveHint 被调用");
        if (isOpeningSettings) {
            SettingsActivity.logOperation("【画中画排查】打开设置页面，跳过");
            SettingsActivity.logOperation("【画中画排查】========== 结束 ==========");
            return;
        }
        if (pipManager == null) {
            SettingsActivity.logOperation("【画中画排查】pipManager 为 null");
            SettingsActivity.logOperation("【画中画排查】========== 结束 ==========");
            return;
        }
        boolean shouldEnter = pipManager.shouldEnterPip();
        SettingsActivity.logOperation("【画中画排查】MainActivity开关状态：" + pipEnable);
        SettingsActivity.logOperation("【画中画排查】设备支持：" + pipManager.isPipSupported());
        SettingsActivity.logOperation("【画中画排查】PIP管理器开关：" + pipManager.isPipEnabled());
        SettingsActivity.logOperation("【画中画排查】已在画中画模式：" + pipManager.isInPipMode());
        SettingsActivity.logOperation("【画中画排查】正在进入画中画：" + pipManager.isPipEntering());
        if (shouldEnter) {
            SettingsActivity.logOperation("【画中画排查】所有条件满足，尝试进入画中画...");
            try {
                PictureInPictureParams pipParams = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
                    pipBuilder.setAspectRatio(new Rational(16, 9));
                    pipParams = pipBuilder.build();
                }
                if (mPlayerManager != null) {
                    pipManager.updatePlayState(true);
                }
                boolean result = pipManager.enterPictureInPicture(this, pipParams);
                SettingsActivity.logOperation("【画中画排查】进入结果：" + (result ? "成功" : "失败"));
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画排查】异常：" + e.getMessage());
                e.printStackTrace();
            }
        } else {
            SettingsActivity.logOperation("【画中画排查】条件不满足，不进入画中画");
        }
        SettingsActivity.logOperation("【画中画排查】========== 结束 ==========");
    }
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        SettingsActivity.logOperation("【画中画】模式变化 → " + (isInPictureInPictureMode ? "进入" : "退出"));
        if (pipManager != null) {
            try {
                pipManager.onPipModeChanged(this, isInPictureInPictureMode);
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画】模式变化回调失败：" + e.getMessage());
            }
        }
        if (isInPictureInPictureMode) {
            SettingsActivity.logOperation("【画中画】========== 进入画中画 ==========");
            
            // ✅ 画中画模式下禁用手势，防止误触
            if (gestureManager != null) {
                gestureManager.setEnabled(false);
            }
            
            hideAllUiForPip();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (mPlayerManager != null) {
                try {
                    mPlayerManager.resume();
                    SettingsActivity.logOperation("【画中画】恢复播放");
                } catch (Exception e) {
                    SettingsActivity.logOperation("【画中画】恢复播放失败：" + e.getMessage());
                }
            }
            logPipViewSize("进入画中画时", playerView);
            SettingsActivity.logOperation("【画中画】================================");
        } else {
            SettingsActivity.logOperation("【画中画】========== 退出画中画 ==========");
            
            // ✅ 退出画中画时启用手势 + 重置防抖
            if (gestureManager != null) {
                gestureManager.setEnabled(true);
                gestureManager.reset();
            }
            
            // ✅ 第3处修改：关键修复 - 重新绑定触摸监听 + 请求焦点
            // 画中画模式下可能导致触摸监听失效，退出后重新绑定
            if (playerView != null && gestureHelper != null) {
                playerView.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        gestureHelper.handleTouch(event);
                        return true;
                    }
                });
                playerView.requestFocus();
                SettingsActivity.logOperation("【画中画】✅ 已重新绑定触摸监听");
            }
            
            if (pipManager != null) {
                pipManager.handleExitPip(new Runnable() {
                    @Override
                    public void run() {
                        SettingsActivity.logOperation("【画中画】应用已关闭，释放播放器");
                    }
                });
            }
            SettingsActivity.logOperation("【画中画尺寸】===== 1. 刚退出画中画（初始状态） =====");
            logPipViewSize("PlayerView", playerView);
            
            if (playerView != null && playerView.getParent() instanceof View) {
                logPipViewSize("父布局", (View) playerView.getParent());
            }
            
            logPipWindowSize();
            if (displayManager != null) {
                SettingsActivity.logOperation("【画中画尺寸】执行 displayManager.reapplyFullScreen()");
                displayManager.reapplyFullScreen();
            }
            SettingsActivity.logOperation("【画中画尺寸】===== 2. reapplyFullScreen 后 =====");
            logPipViewSize("PlayerView", playerView);
            if (playerView != null) {
                playerView.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            playerView.requestLayout();
                            playerView.invalidate();
                            SettingsActivity.logOperation("【画中画】立即刷新 PlayerView 布局");
                            SettingsActivity.logOperation("【画中画尺寸】===== 3. 立即 requestLayout 后 =====");
                            logPipViewSize("PlayerView", playerView);
                        } catch (Exception e) {
                            SettingsActivity.logOperation("【画中画】刷新 PlayerView 失败：" + e.getMessage());
                        }
                    }
                });
                playerView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            playerView.requestLayout();
                            playerView.invalidate();
                            if (mPlayerManager != null) {
                                mPlayerManager.attachPlayerView(playerView);
                                mPlayerManager.resume();
                            }
                            SettingsActivity.logOperation("【画中画】延迟刷新 PlayerView + 重新绑定");
                            SettingsActivity.logOperation("【画中画尺寸】===== 4. 延迟200ms刷新 + 重新绑定后 =====");
                            logPipViewSize("PlayerView", playerView);
                            
                            if (playerView.getParent() instanceof View) {
                                logPipViewSize("父布局", (View) playerView.getParent());
                            }
                            
                            SettingsActivity.logOperation("【画中画尺寸】========================================");
                        } catch (Exception e) {
                            SettingsActivity.logOperation("【画中画】延迟刷新失败：" + e.getMessage());
                        }
                    }
                }, 200);
            }
            syncRemoteMode();
            if (infoDisplayManager != null && channelSourceList.size() > currentPlayIndex) {
                Channel currChannel = channelSourceList.get(currentPlayIndex);
                TVPlayerManager.LiveInfo liveInfo = mPlayerManager.getLiveInfo();
                infoDisplayManager.showInfoBar(currChannel, liveInfo);
                infoDisplayManager.showChannelNum(currentPlayIndex + 1);
            }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            resumeCurrentChannel();
            SettingsActivity.logOperation("【画中画】退出画中画完成");
            SettingsActivity.logOperation("【画中画】================================");
        }
    }
    private void logPipViewSize(String tag, View view) {
        if (view == null) {
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "：View 为 null");
            return;
        }
        try {
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "位置：left=" + view.getLeft() 
                + "，top=" + view.getTop()
                + "，right=" + view.getRight() 
                + "，bottom=" + view.getBottom());
            
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "尺寸：宽=" + view.getWidth() 
                + "，高=" + view.getHeight());
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                String widthStr = lp.width == ViewGroup.LayoutParams.MATCH_PARENT ? "MATCH_PARENT(-1)" :
                                  lp.width == ViewGroup.LayoutParams.WRAP_CONTENT ? "WRAP_CONTENT(-2)" :
                                  String.valueOf(lp.width);
                String heightStr = lp.height == ViewGroup.LayoutParams.MATCH_PARENT ? "MATCH_PARENT(-1)" :
                                   lp.height == ViewGroup.LayoutParams.WRAP_CONTENT ? "WRAP_CONTENT(-2)" :
                                   String.valueOf(lp.height);
                
                SettingsActivity.logOperation("【画中画尺寸】" + tag + "布局参数：width=" + widthStr 
                    + "，height=" + heightStr);
            }
            int visibility = view.getVisibility();
            String visStr = visibility == View.VISIBLE ? "VISIBLE" :
                            visibility == View.INVISIBLE ? "INVISIBLE" :
                            "GONE";
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "可见性：" + visStr);
        } catch (Exception e) {
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "获取尺寸失败：" + e.getMessage());
        }
    }
    private void logPipWindowSize() {
        try {
            Rect rect = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(rect);
            SettingsActivity.logOperation("【画中画尺寸】窗口可见区域：宽=" + rect.width() + "，高=" + rect.height());
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
            SettingsActivity.logOperation("【画中画尺寸】屏幕尺寸：宽=" + metrics.widthPixels + "，高=" + metrics.heightPixels);
            View decorView = getWindow().getDecorView();
            SettingsActivity.logOperation("【画中画尺寸】DecorView：宽=" + decorView.getWidth() + "，高=" + decorView.getHeight());
        } catch (Exception e) {
            SettingsActivity.logOperation("【画中画尺寸】获取窗口尺寸失败：" + e.getMessage());
        }
    }
    private void log(String msg) {
        logList.add(msg);
        Log.d("MainActivity", msg);
    }
    @Override
    protected void onPause() {
        super.onPause();
        appCoreManager.onPause();
        if (pipManager != null && (pipManager.isInPipMode() || pipManager.isPipEntering())) {
            try {
                if (mPlayerManager != null) {
                    mPlayerManager.resume();
                    SettingsActivity.logOperation("【画中画】onPause后立即恢复播放");
                }
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画】onPause恢复播放失败：" + e.getMessage());
            }
        }
    }
    @Override
    protected void onStop() {
        super.onStop();
        if (pipManager != null) {
            pipManager.setStopCalled(true);
            SettingsActivity.logOperation("【画中画】onStop 被调用");
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        isOpeningSettings = false;
        appCoreManager.onResume();
        if (pipManager != null) {
            pipManager.setStopCalled(false);
        }
        loadSettings();
        screenRatioManager.apply();
        displayManager.reapplyFullScreen();
        if (pipManager == null || !pipManager.isInPipMode()) {
            new Handler(Looper.getMainLooper()).postDelayed(this::resumeCurrentChannel, 200);
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
        if (mPanelAutoHideHandler != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        }
        if (infoDisplayManager != null) {
            infoDisplayManager.release();
        }
        if (channelNumberManager != null) {
            channelNumberManager.release();
        }
        if (displayManager != null) {
            displayManager.release();
        }
        if (channelPanelController != null) {
            channelPanelController.release();
        }
        if (appCoreManager != null) {
            appCoreManager.release();
        }
        if (pipManager != null) {
            pipManager.release();
        }
        mInstance = null;
    }
}
