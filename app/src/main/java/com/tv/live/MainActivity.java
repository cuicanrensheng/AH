package com.tv.live;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.tv.live.manager.AppConfig;
import com.tv.live.manager.AppCoreManager;
import com.tv.live.manager.ChannelNumberManager;
import com.tv.live.manager.DisplayManager;
import com.tv.live.manager.GestureManager;
import com.tv.live.manager.InfoDisplayManager;
import com.tv.live.manager.KeyEventManager;
import com.tv.live.manager.PictureInPictureManager;
import com.tv.live.manager.TVPlayerManager;
import com.tv.live.manager.TvRemoteManager;
import com.tv.live.ui.ChannelPanelController;
import com.tv.live.ui.PlayerStateListenerImpl;
import com.tv.live.utils.SettingsActivity;

/**
 * 直播应用主界面
 * 适配：修复版 PictureInPictureManager 画中画管理器
 * 仅修改当前文件，无其他文件改动
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TV_Main";
    public static MainActivity mInstance;

    // ====================== 基础控件 ======================
    private RelativeLayout mainContainer;
    private ImageView ivPlayerPlaceholder;

    // ====================== 核心管理器 ======================
    private TVPlayerManager mPlayerManager;
    private PictureInPictureManager pipManager;
    private ChannelPanelController channelPanelController;
    private DisplayManager displayManager;
    private InfoDisplayManager infoDisplayManager;
    private TvRemoteManager tvRemoteManager;
    private ChannelNumberManager channelNumberManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;

    // ====================== 状态变量 ======================
    private boolean isOpeningSettings = false;
    private boolean isInPipMode = false;
    private boolean pipEnable = false;
    /**
     * 【画中画适配新增】
     * 因TVPlayerManager无isPlaying()方法，手动维护播放状态标记
     */
    private boolean isPlaying = false;

    // ====================== 配置与线程 ======================
    private SharedPreferences sp;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        // 初始化窗口：全屏、横屏、常亮
        initWindow();
        setContentView(R.layout.activity_main);

        // 初始化控件
        initViews();

        // 初始化核心组件
        initPlayer();
        initChannelPanel();
        initManagers();
        // 【核心】初始化适配后的画中画管理器
        initPictureInPicture();

        // 加载配置
        loadSettings();

        // 初始化数据
        initData();
    }

    /**
     * 初始化窗口参数
     */
    private void initWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * 初始化控件
     */
    private void initViews() {
        mainContainer = findViewById(R.id.main_container);
        ivPlayerPlaceholder = findViewById(R.id.iv_player_placeholder);
    }

    /**
     * 初始化播放器（适配画中画状态同步）
     */
    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);

        // 重写播放器状态监听器，同步状态到画中画
        PlayerStateListenerImpl playerStateListener = new PlayerStateListenerImpl(this) {
            @Override
            public void onPlayReady() {
                super.onPlayReady();
                // 播放就绪，更新状态
                isPlaying = true;
                syncPipPlayState(true);
                ivPlayerPlaceholder.setVisibility(View.GONE);
            }

            @Override
            public void onBuffering() {
                super.onBuffering();
                // 缓冲中，同步播放状态
                syncPipPlayState(true);
            }

            @Override
            public void onPlayEnd() {
                super.onPlayEnd();
                // 播放结束
                isPlaying = false;
                syncPipPlayState(false);
            }

            @Override
            public void onPlayError(String msg) {
                super.onPlayError(msg);
                // 播放异常
                isPlaying = false;
                syncPipPlayState(false);
            }

            @Override
            public void onIdle() {
                super.onIdle();
                // 闲置状态
                isPlaying = false;
                syncPipPlayState(false);
            }
        };

        mPlayerManager.setOnPlayStateListener(playerStateListener);
    }

    /**
     * 初始化频道面板
     */
    private void initChannelPanel() {
        channelPanelController = new ChannelPanelController(this);
    }

    /**
     * 初始化其他管理器
     */
    private void initManagers() {
        displayManager = new DisplayManager(this);
        infoDisplayManager = new InfoDisplayManager(this);
        tvRemoteManager = new TvRemoteManager(this);
        channelNumberManager = new ChannelNumberManager(this);
        gestureManager = new GestureManager(this);
        keyEventManager = new KeyEventManager(this);
    }

    /**
     * 【完整重写】适配修复版 PictureInPictureManager
     * 画中画核心初始化 + 监听器绑定
     */
    private void initPictureInPicture() {
        try {
            pipManager = PictureInPictureManager.getInstance(this);
            // 设置画中画回调监听
            pipManager.setListener(new PictureInPictureManager.OnPipListener() {
                @Override
                public void onPipModeChanged(boolean inPip) {
                    isInPipMode = inPip;
                    log("【画中画】模式切换：" + (inPip ? "进入" : "退出"));
                }

                @Override
                public void onPlayPause() {
                    // 画中画播放/暂停控制
                    if (mPlayerManager == null) return;
                    if (isPlaying) {
                        mPlayerManager.pause();
                        isPlaying = false;
                    } else {
                        mPlayerManager.resume();
                        isPlaying = true;
                    }
                    syncPipPlayState(isPlaying);
                }

                @Override
                public void onPrevChannel() {
                    // 上一频道
                    if (channelPanelController != null) {
                        channelPanelController.playPrev();
                    }
                }

                @Override
                public void onNextChannel() {
                    // 下一频道
                    if (channelPanelController != null) {
                        channelPanelController.playNext();
                    }
                }

                @Override
                public boolean onTimeout() {
                    log("【画中画】超时自动关闭");
                    moveTaskToBack(false);
                    return false;
                }

                @Override
                public void onDataSaverChanged(boolean enable) {
                    log("【画中画】省流模式：" + enable);
                }

                @Override
                public void onDoubleTapFullScreen() {
                    log("【画中画】双击返回全屏");
                }
            });

            log("【画中画】初始化完成，设备支持：" + pipManager.isPipSupported());
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持：" + pipManager.isPipSupported());
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
    }

    /**
     * 加载应用配置（修正画中画开关逻辑）
     */
    private void loadSettings() {
        sp = getSharedPreferences("app_config", Context.MODE_PRIVATE);
        // 加载画中画开关，修复版无内部开关，仅由Activity控制
        pipEnable = sp.getBoolean("pip_enable", false);
        SettingsActivity.logOperation("【设置】画中画开关：" + pipEnable);
    }

    /**
     * 初始化直播数据
     */
    private void initData() {
        AppCoreManager.getInstance(this).loadChannels();
    }

    /**
     * 播放频道（修正画中画频道信息同步）
     */
    private void playChannel(int index, Channel channel) {
        if (channel == null) return;

        // 播放地址
        String playUrl = channel.getPlayUrl();
        mPlayerManager.playUrl(playUrl);

        // 更新信息显示
        infoDisplayManager.updateChannelInfo(index + 1, channel.getName());

        // ====================== 画中画频道信息同步 ======================
        if (pipManager != null && isInPipMode) {
            try {
                String channelName = (index + 1) + " " + channel.getName();
                pipManager.updateChannelInfo(channelName, channel.getPlayUrl());
            } catch (Exception e) {
                // 忽略异常，不影响主播放
            }
        }
    }

    /**
     * Home键触发：自动进入画中画
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // 设置页面打开时不触发画中画
        if (isOpeningSettings) return;

        // 满足条件则进入画中画
        if (pipEnable && pipManager != null && pipManager.isPipSupported()) {
            try {
                pipManager.enterPictureInPicture(this);
                SettingsActivity.logOperation("【画中画】Home键触发进入");
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画】进入失败：" + e.getMessage());
            }
        }
    }

    /**
     * 系统画中画模式变化回调
     */
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        isInPipMode = isInPictureInPictureMode;

        // 同步状态到管理器
        if (pipManager != null) {
            try {
                pipManager.onPipModeChanged(isInPictureInPictureMode);
            } catch (Exception e) {
                // 忽略异常
            }
        }

        // 进入画中画隐藏所有面板
        if (isInPictureInPictureMode) {
            channelPanelController.hidePanel();
            infoDisplayManager.hideAll();
        }
    }

    /**
     * 【新增】同步播放状态到画中画管理器
     */
    private void syncPipPlayState(boolean playing) {
        if (pipManager == null || !isInPipMode) return;
        try {
            pipManager.updatePlayState(playing);
        } catch (Exception e) {
            // 忽略异常
        }
    }

    /**
     * 日志打印
     */
    public void log(String msg) {
        Log.d(TAG, msg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 非画中画模式隐藏占位图
        if (!isInPipMode) {
            mainHandler.postDelayed(() -> {
                if (ivPlayerPlaceholder != null) {
                    ivPlayerPlaceholder.setVisibility(View.GONE);
                }
            }, 1000);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 非画中画模式显示占位图，防花屏
        if (!isInPipMode && !isOpeningSettings) {
            ivPlayerPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放播放器
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }

        // ====================== 释放画中画资源 ======================
        if (pipManager != null) {
            try {
                pipManager.setListener(null);
            } catch (Exception e) {
                // 忽略异常
            }
            pipManager = null;
        }

        // 清空Handler
        mainHandler.removeCallbacksAndMessages(null);
        mInstance = null;
    }

    // ====================== 内部实体类（保持原有逻辑） ======================
    public static class Channel {
        private String name;
        private String playUrl;

        public String getName() {
            return name;
        }

        public String getPlayUrl() {
            return playUrl;
        }
    }
}
