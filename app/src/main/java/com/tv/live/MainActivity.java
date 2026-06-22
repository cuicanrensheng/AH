package com.tv.live;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public List<Channel> channelSourceList = new ArrayList<>();
    public int currentPlayIndex = 0;

    private PlayerView playerView;
    private ImageView ivPlayerPlaceholder;
    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private GestureManager gestureManager;
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
    private boolean isInPipMode = false;

    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean isOpeningSettings = false;

    private Handler mPanelAutoHideHandler = new Handler(Looper.getMainLooper());
    private Runnable mPanelAutoHideRunnable = () -> {
        if (channelPanelController != null) {
            channelPanelController.hidePanel();
        }
    };
    private boolean mIsFirstLaunch = true;
    public static List<String> logList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        displayManager = new DisplayManager(this);
        displayManager.applyFullScreen();
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initInfoDisplayManager();
        appConfig = AppConfig.getInstance(this);
        loadSettings();

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        ivPlayerPlaceholder = findViewById(R.id.iv_player_placeholder);

        initChannelPanelController();
        initRemoteManager();
        initPictureInPicture();
        initPlayer();

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        gestureManager = new GestureManager(this);
        PlayerGestureHelper gestureHelper = gestureManager.create();

        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        keyEventManager = new KeyEventManager(this);
        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        initChannelNumberManager();
        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        }

        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();

        if (mIsFirstLaunch) {
            mPanelAutoHideHandler.postDelayed(mPanelAutoHideRunnable, 3000);
            mIsFirstLaunch = false;
        }
    }

    // ✅ 修复画中画初始化（无闪退）
    private void initPictureInPicture() {
        pipManager = PictureInPictureManager.getInstance(this);
        pipManager.setPipEnabled(pipEnable);
        pipManager.setListener(inPip -> {
            isInPipMode = inPip;
            if (inPip) {
                // ✅ 画中画模式自动播放（核心修复）
                resumePlay();
                // 隐藏所有UI，避免黑屏/重叠
                hideAllUi();
            } else {
                // 退出画中画恢复UI
                showPlayerPlaceholder();
                new Handler(Looper.getMainLooper()).postDelayed(this::resumePlay, 500);
            }
        });
    }

    // ✅ 核心：恢复播放（复用当前频道地址）
    private void resumePlay() {
        try {
            if (channelSourceList == null || currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size())
                return;
            Channel ch = channelSourceList.get(currentPlayIndex);
            if (ch != null && ch.getPlayUrl() != null) {
                mPlayerManager.playUrl(ch.getPlayUrl());
                hidePlayerPlaceholder();
            }
        } catch (Exception ignored) {}
    }

    private void hideAllUi() {
        if (channelPanelController != null) channelPanelController.hidePanel();
        if (infoDisplayManager != null) infoDisplayManager.hideInfoBar();
        hidePlayerPlaceholder();
    }

    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
    }

    private void initInfoDisplayManager() {
        infoDisplayManager = new InfoDisplayManager(
                this, findViewById(R.id.tv_channel_num), findViewById(R.id.info_bar),
                findViewById(R.id.tv_channel_name), findViewById(R.id.tv_tag_fhd),
                findViewById(R.id.tv_tag_audio), findViewById(R.id.tv_bitrate),
                findViewById(R.id.tv_current_program_name), findViewById(R.id.tv_current_time_range),
                findViewById(R.id.progress_program), findViewById(R.id.tv_remaining_time),
                findViewById(R.id.tv_next_program_name), findViewById(R.id.tv_next_time_range)
        );
    }

    private void initChannelPanelController() {
        channelPanelController = new ChannelPanelController(
                this, findViewById(R.id.panel_layout), findViewById(R.id.ll_left_panel),
                findViewById(R.id.ll_right_panel), findViewById(R.id.lv_group),
                findViewById(R.id.lv_channel_list), findViewById(R.id.lv_channel_list_epg),
                findViewById(R.id.lv_date), findViewById(R.id.lv_epg),
                findViewById(R.id.btn_show_epg), findViewById(R.id.btn_back_group),
                new GroupListManager(this, findViewById(R.id.lv_group)),
                new ChannelListManager(this, findViewById(R.id.lv_channel_list)),
                new ChannelListManager(this, findViewById(R.id.lv_channel_list_epg)),
                new DateListManager(this, findViewById(R.id.lv_date)),
                new EpgManagerWrapper(this, findViewById(R.id.lv_epg)),
                new PanelManager(findViewById(R.id.panel_layout),
                        new ChannelListManager(this, findViewById(R.id.lv_channel_list)),
                        new EpgManagerWrapper(this, findViewById(R.id.lv_epg)))
        );
        channelPanelController.setOnChannelChangeListener((channel, index) -> playChannel(channel, index));
    }

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> infoDisplayManager.updateLiveInfo(info));
    }

    private void initChannelNumberManager() {
        channelNumberManager = new ChannelNumberManager(new ChannelNumberManager.OnChannelNumberListener() {
            @Override
            public void onChannelSelected(int channelIndex) {
                channelPanelController.playChannel(channelIndex);
            }
            @Override
            public void showChannelNumber(String number) {}
            @Override
            public void hideChannelNumber() {}
        }, number_channel_enable);
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
                    if (currentPlayIndex >= 0 && currentPlayIndex < channels.size()) {
                        playChannel(channels.get(currentPlayIndex), currentPlayIndex);
                    }
                    displayManager.hideLoading();
                });
            }
            @Override
            public void onLiveSourceFailed(String errorMsg) {}
            @Override
            public void onEpgLoaded() {}
            @Override
            public void onLoadTimeout(boolean hasData) {
                runOnUiThread(displayManager::hideLoading);
            }
        });
        appCoreManager.registerReceivers();
    }

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        pipEnable = sp.getBoolean("pip_enable", false);
        if (pipManager != null) pipManager.setPipEnabled(pipEnable);
    }

    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;
        appConfig.setLastPlayIndex(index);
        mPlayerManager.playUrl(channel.getPlayUrl());
        hidePlayerPlaceholder();
    }

    public void togglePanel() {
        channelPanelController.togglePanel();
    }

    @Override
    public void onBackPressed() {
        if (isInPipMode) {
            moveTaskToBack(false);
            return;
        }
        if (channelPanelController.handleBackPressed()) return;
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isInPipMode) return super.onKeyDown(keyCode, event);
        return super.onKeyDown(keyCode, event);
    }

    public void openSettings() {
        isOpeningSettings = true;
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ✅ 退后台自动进入画中画 + 自动播放（核心修复）
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isOpeningSettings || isInPipMode) return;
        if (pipManager.isPipSupported() && pipEnable) {
            try {
                PictureInPictureParams params = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    params = new PictureInPictureParams.Builder()
                            .setAspectRatio(new Rational(16, 9))
                            .build();
                }
                pipManager.enterPictureInPicture(this, params);
            } catch (Exception ignored) {}
        }
    }

    // ✅ 画中画模式变化（无黑屏、无重复小窗）
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        pipManager.onPipModeChanged(this, isInPictureInPictureMode);
    }

    private void showPlayerPlaceholder() {
        if (ivPlayerPlaceholder != null && !isInPipMode) {
            ivPlayerPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private void hidePlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.GONE);
        }
    }

    private void log(String msg) {
        Log.d("MainActivity", msg);
    }

    // ✅ 修复生命周期：画中画模式不暂停、不黑屏
    @Override
    protected void onPause() {
        if (!isInPipMode && !isOpeningSettings) {
            showPlayerPlaceholder();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isOpeningSettings = false;
        loadSettings();
        if (!isInPipMode) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                hidePlayerPlaceholder();
                resumePlay();
            }, 500);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        if (pipManager != null) pipManager.release();
    }
}
