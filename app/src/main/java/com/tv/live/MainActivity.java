package com.tv.live;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.ui.PlayerView;

import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.*;
import com.tv.live.util.LifecycleHelper;
import com.tv.live.util.LogCollector;
import com.tv.live.util.RemoteKeyHandler;
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

    private RemoteKeyHandler remoteKeyHandler;
    private LifecycleHelper lifecycleHelper;

    private boolean pipEnable = false;
    private boolean channel_reverse;
    private boolean number_channel_enable;

    private boolean isOpeningSettings = false;
    private SettingsDialog settingsDialog;
    private long settingsCloseTime = 0;
    private long lastSettingsOpenTime = 0;
    private long okKeyDownTime = 0;
    private boolean okKeyTriggered = false;
    private boolean okKeyLongPressed = false;
    private static final long OK_LONG_PRESS_DURATION = 1500;
    // 🔧 安卓13+/16手势返回双保险：避免 OnBackPressedCallback 与系统 OnBackInvokedCallback 同时触发导致弹窗两次
    private long mLastBackHandleMs = 0L;
    // 🔧 已注册的系统级 API33+ OnBackInvokedCallback（用于 onDestroy 清理或重建时去重，避免内存泄漏）
    private Object mSystemBackInvokedCb = null;

    private static boolean isOkKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == 100;
    }

    private static boolean isMenuKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_HELP
                || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == 101;
    }

    private static boolean isChannelUpKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    private static boolean isChannelDownKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT;
    }

    private Runnable openSettingsRunnable = () -> {
        okKeyLongPressed = true;
        okKeyTriggered = true;
        openSettings();
    };

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private View logWindowContainer;
    private ScrollView logScrollView;
    private TextView tvLogContent;
    private boolean logWindowVisible = false;
    private Runnable logUpdateRunnable;

    private boolean isInCatchUpMode = false;

    private final StringBuilder numberInputBuffer = new StringBuilder();
    private final Runnable numberInputConfirmTask = () -> confirmNumberInputJump();

    // 🟢【回看快速调节】长按左/右键重复触发进度调节
    private static final long SEEK_REPEAT_DELAY_MS = 400;
    private static final long SEEK_REPEAT_INTERVAL_MS = 200;
    private int longPressKeyCode = -1;
    private final Runnable longPressSeekRunnable = new Runnable() {
        @Override
        public void run() {
            if (longPressKeyCode == -1 || !isInCatchUpMode) return;
            if (playerControlManager == null) {
                longPressKeyCode = -1;
                return;
            }
            if (longPressKeyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                playerControlManager.seekBackward();
            } else if (longPressKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                playerControlManager.seekForward();
            }
            mMainHandler.postDelayed(this, SEEK_REPEAT_INTERVAL_MS);
        }
    };

    private AlertDialog exitMenuDialog = null;

    private BroadcastReceiver unlockReceiver;

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

    private PlayerTouchListener touchListener;
    public PlayerTouchListener getTouchListener() {
        return touchListener;
    }

    public PlayerView getPlayerView() {
        return playerView;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // APK 签名校验（防二次打包/防反编译后重新签名）
        SecurityCheck.verifyOnStart(this);
        mInstanceRef = new WeakReference<>(this);
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        // 跟随重力传感器在左横屏/右横屏间自动切换（与 Manifest 的 sensorLandscape 一致）。
        // TV 设备无加速度传感器，系统会自动回退为固定横屏，不影响 TV 体验。
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        displayManager = new DisplayManager(this);
        setContentView(R.layout.activity_main);
        displayManager.applyFullScreen();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        logScrollView = findViewById(R.id.log_scroll_view);
        tvLogContent = findViewById(R.id.tv_log_content);

        initInfoDisplayManager();
        appConfig = AppConfig.getInstance(this);

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null && !customLive.isEmpty()) {
            UrlConfig.LIVE_URL = customLive;
        } else {
            // 冷启动：如果没有网页推送的自定义地址，使用历史默认源
            // （SourceManager 会自动注入两个内置源，这里取默认选中的那个）
            SourceManager liveMgr = new SourceManager(this, "live_history");
            String defaultLive = liveMgr.getDefaultUrl();
            if (defaultLive != null && !defaultLive.isEmpty()) {
                UrlConfig.LIVE_URL = defaultLive;
            }
        }
        if (customEpg != null && !customEpg.isEmpty()) {
            UrlConfig.EPG_URL = customEpg;
        } else {
            SourceManager epgMgr = new SourceManager(this, "epg_history");
            String defaultEpg = epgMgr.getDefaultUrl();
            if (defaultEpg != null && !defaultEpg.isEmpty()) {
                UrlConfig.EPG_URL = defaultEpg;
            }
        }
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
        int savedBg = getSharedPreferences("app_settings", MODE_PRIVATE).getInt("panel_background", 0);
        applyPanelBackground(savedBg);
        initPictureInPicture();
        channelPanelController.handleFirstLaunch();

        initPlayer();
        mPlayerManager.registerDecoderModeReceiver();
        mPlayerManager.registerRendererModeReceiver();

        loadSettings();

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);

        initAppCoreManager();
        // 🟢 首次打开直接出画面：移除全屏Loading阻塞，后台静默加载直播源
        //    用户立刻看到播放器/频道面板UI，数据加载完毕自动刷新并续播
        new Thread(() -> appCoreManager.loadLiveAndEpg()).start();

        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.UNLOCK_SETTINGS".equals(intent.getAction())) {
                    isOpeningSettings = false;
                    settingsCloseTime = System.currentTimeMillis();
                    Log.d("MainActivity", "📡 收到解锁广播，isOpeningSettings 已重置");
                }
            }
        };
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            new IntentFilter("com.tv.live.UNLOCK_SETTINGS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        );

        // 🔧 兼容安卓13+/16预测式返回（滑动退出）：注册 OnBackPressedCallback + 系统回调双保险
        // 旧的 onBackPressed() 仅靠硬件按键触发，在荣耀/安卓16的手势边缘滑动下会被系统直接跳过，导致退出弹窗不弹出
        setupBackPressHandling();

        initSubModules();
    }

    private void initSubModules() {
        lifecycleHelper = new LifecycleHelper(this);
        lifecycleHelper.setInfoDisplayManager(() -> { if (infoDisplayManager != null) infoDisplayManager.release(); });
        lifecycleHelper.setDisplayManager(() -> { if (displayManager != null) displayManager.release(); });
        lifecycleHelper.setChannelPanelController(() -> { if (channelPanelController != null) channelPanelController.release(); });
        lifecycleHelper.setAppCoreManager(() -> { if (appCoreManager != null) appCoreManager.release(); });
        lifecycleHelper.setPipManager(() -> { if (pipManager != null) pipManager.release(); });
        lifecycleHelper.setPlayerControlManager(() -> { if (playerControlManager != null) playerControlManager.release(); });
        lifecycleHelper.setPlayerManager(() -> {
            if (mPlayerManager != null) {
                mPlayerManager.setOnPlayStateListener(null);
                mPlayerManager.setOnLiveInfoUpdateListener(null);
                mPlayerManager.setOnSourceFailedListener(null);
                mPlayerManager.release();
            }
        });
        lifecycleHelper.setUnlockReceiver(unlockReceiver);
        lifecycleHelper.setTouchListenerSource(playerView);

        remoteKeyHandler = new RemoteKeyHandler(new RemoteKeyHandler.OnKeyAction() {
            @Override
            public void onMenuKey() {
                openSettings();
            }
            @Override
            public void onOkKey() {
                if (channelPanelController != null) channelPanelController.togglePanel();
            }
            @Override
            public void onChannelUp() {
                if (channelPanelController != null) channelPanelController.switchUp();
            }
            @Override
            public void onChannelDown() {
                if (channelPanelController != null) channelPanelController.switchDown();
            }
            @Override
            public void onSeekBackward() {
                if (playerControlManager != null) playerControlManager.seekBackward();
            }
            @Override
            public void onSeekForward() {
                if (playerControlManager != null) playerControlManager.seekForward();
            }
            @Override
            public void onPlayPause() {
                if (mPlayerManager != null) {
                    if (mPlayerManager.isPlaying()) mPlayerManager.pause();
                    else mPlayerManager.resume();
                }
            }
            @Override
            public void onStop() {
                if (mPlayerManager != null) mPlayerManager.pause();
            }
            @Override
            public void onBackKey() {
                if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                    channelPanelController.hidePanel();
                } else {
                    onBackPressed();
                }
            }
        });

        remoteKeyHandler.setNumberInputCallback(input -> {
            if (infoDisplayManager != null) {
                infoDisplayManager.showChannelNumInput(input);
            }
        });
        remoteKeyHandler.setNumberInputEnabled(number_channel_enable);
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

    private boolean panelOpenOnBackDown = false;

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (channelPanelController == null || mPlayerManager == null || event == null) {
            return super.dispatchKeyEvent(event);
        }

        int keyCode = event.getKeyCode();
        int action = event.getAction();
        boolean panelOpen;
        try {
            panelOpen = channelPanelController.isPanelOpen();
        } catch (Exception e) {
            android.util.Log.e("KEY_DEBUG", "isPanelOpen 异常: " + e.getMessage(), e);
            panelOpen = false;
        }

        try {
            android.util.Log.d("KEY_DEBUG", "keyCode=" + keyCode + " action=" + action + " repeat=" + event.getRepeatCount());

            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    if (isMenuKey(keyCode)) {
                        if (panelOpen) {
                            channelPanelController.hidePanel();
                        }
                        openSettings();
                        return true;
                    }

                    if (isOkKey(keyCode)) {
                        if (!panelOpen) {
                            channelPanelController.togglePanel();
                            return true;
                        }
                    }

                    if (isChannelUpKey(keyCode)) {
                        if (!panelOpen) {
                            channelPanelController.switchUp();
                            return true;
                        }
                    } else if (isChannelDownKey(keyCode)) {
                        if (!panelOpen) {
                            channelPanelController.switchDown();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (isInCatchUpMode && playerControlManager != null) {
                            // 🟢 回看模式：第一次按下立即触发一次，400ms 后开始每 200ms 重复
                            if (event.getRepeatCount() == 0) {
                                playerControlManager.seekBackward();
                                longPressKeyCode = keyCode;
                                mMainHandler.removeCallbacks(longPressSeekRunnable);
                                mMainHandler.postDelayed(longPressSeekRunnable, SEEK_REPEAT_DELAY_MS);
                            }
                            return true;
                        }
                        if (!panelOpen) {
                            channelPanelController.togglePanel();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (isInCatchUpMode && playerControlManager != null) {
                            if (event.getRepeatCount() == 0) {
                                playerControlManager.seekForward();
                                longPressKeyCode = keyCode;
                                mMainHandler.removeCallbacks(longPressSeekRunnable);
                                mMainHandler.postDelayed(longPressSeekRunnable, SEEK_REPEAT_DELAY_MS);
                            }
                            return true;
                        }
                        if (!panelOpen) {
                            openSettings();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if (mPlayerManager != null) {
                            if (mPlayerManager.isPlaying()) {
                                mPlayerManager.pause();
                            } else {
                                mPlayerManager.resume();
                            }
                        }
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                        if (mPlayerManager != null) {
                            mPlayerManager.pause();
                        }
                        return true;
                    } else if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                        handleNumberKey(keyCode);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                        panelOpenOnBackDown = panelOpen;
                        android.util.Log.d("KEY_DEBUG", "Back DOWN: panelOpen=" + panelOpen + ", panelOpenOnBackDown=" + panelOpenOnBackDown);
                        if (panelOpen) {
                            channelPanelController.hidePanel();
                        }
                        if (settingsDialog != null && settingsDialog.isShowing()) {
                            return super.dispatchKeyEvent(event);
                        }
                        return true;
                    }
                }
            } else if (action == KeyEvent.ACTION_UP) {
                // 🟢 长按左/右键抬起时停止重复调节
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (longPressKeyCode == keyCode) {
                        longPressKeyCode = -1;
                        mMainHandler.removeCallbacks(longPressSeekRunnable);
                    }
                }
                if (isOkKey(keyCode)) {
                    okKeyLongPressed = false;
                    okKeyTriggered = false;
                    okKeyDownTime = 0;
                    if (!panelOpen) {
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    android.util.Log.d("KEY_DEBUG", "Back UP: panelOpenOnBackDown=" + panelOpenOnBackDown + ", panelOpen=" + panelOpen);
                    if (settingsDialog != null && settingsDialog.isShowing()) {
                        return super.dispatchKeyEvent(event);
                    }
                    if (!panelOpenOnBackDown && !panelOpen) {
                        android.util.Log.d("KEY_DEBUG", "Calling onBackPressed()");
                        onBackPressed();
                    }
                    return true;
                }
            }

            if (panelOpen && panelLayout != null) {
                if (panelLayout.dispatchKeyEvent(event)) {
                    return true;
                }
            }
        } catch (Exception e) {
            android.util.Log.e("KEY_DEBUG", "dispatchKeyEvent 异常 keyCode=" + keyCode + ": " + e.getMessage(), e);
        }

        return super.dispatchKeyEvent(event);
    }

    private void handleNumberKey(int keyCode) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (!number_channel_enable) return;

        int num = keyCode - KeyEvent.KEYCODE_0;
        if (num < 0 || num > 9) return;

        // 取消之前的确认任务
        mMainHandler.removeCallbacks(numberInputConfirmTask);

        // 累积输入数字
        numberInputBuffer.append(num);

        // 最多保留 4 位数字（防止溢出）
        if (numberInputBuffer.length() > 4) {
            numberInputBuffer.delete(0, numberInputBuffer.length() - 4);
        }

        // 显示正在输入的数字（如 "115-")
        if (infoDisplayManager != null) {
            infoDisplayManager.showChannelNumInput(numberInputBuffer.toString());
        }

        // 1.5 秒后自动确认跳转
        mMainHandler.postDelayed(numberInputConfirmTask, 1500);
    }

    private void confirmNumberInputJump() {
        if (numberInputBuffer.length() == 0) return;

        try {
            int channelNum = Integer.parseInt(numberInputBuffer.toString());
            numberInputBuffer.setLength(0);

            if (channelNum <= 0) return;

            int targetIndex = channelNum - 1; // 频道号从 1 开始
            if (targetIndex < 0) targetIndex = 0;
            if (targetIndex >= channelSourceList.size()) {
                targetIndex = channelSourceList.size() - 1;
            }

            playChannel(channelSourceList.get(targetIndex), targetIndex);
        } catch (NumberFormatException e) {
            numberInputBuffer.setLength(0);
        }
    }

    public void showLogWindow() {
        if (logWindowVisible) return;
        logWindowVisible = true;
        logWindowContainer.setVisibility(View.VISIBLE);
        startLogUpdate();
    }

    public void hideLogWindow() {
        if (!logWindowVisible) return;
        logWindowVisible = false;
        logWindowContainer.setVisibility(View.GONE);
        stopLogUpdate();
    }

    // 🟢【核心修改】自动计算长度，让分割线完美铺满屏幕
    private void startLogUpdate() {
        if (logUpdateRunnable != null) return;
        logUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!logWindowVisible) {
                    stopLogUpdate();
                    return;
                }

                String logs = LogCollector.getInstance().getAllLogs();

                // 🔴 检测并替换特殊的分割线标记
                if (logs.contains(LogCollector.DIVIDER_TOKEN)) {
                    int viewWidth = tvLogContent.getMeasuredWidth();
                    int eqCount = 30; // 最小兜底值

                    if (viewWidth > 0) {
                        // 测量单个 "=" 字符的像素宽度
                        float eqWidth = tvLogContent.getPaint().measureText("=");
                        eqCount = (int) (viewWidth / eqWidth);
                        if (eqCount > 200) eqCount = 200; // 防止极端情况太长
                    }

                    // 生成指定数量的 "="
                    String eqString = new String(new char[eqCount]).replace('\0', '=');
                    // 将标记替换为生成的等号
                    logs = logs.replace(LogCollector.DIVIDER_TOKEN, eqString);
                }

                tvLogContent.setText(logs);
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                mMainHandler.postDelayed(this, 300);
            }
        };
        mMainHandler.post(logUpdateRunnable);
    }

    private void stopLogUpdate() {
        if (logUpdateRunnable != null) {
            mMainHandler.removeCallbacks(logUpdateRunnable);
            logUpdateRunnable = null;
        }
    }

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

    public void setCatchUpMode(boolean enabled) {
        this.isInCatchUpMode = enabled;
        if (!enabled) {
            // 退出回看模式时清理长按状态
            longPressKeyCode = -1;
            mMainHandler.removeCallbacks(longPressSeekRunnable);
        }
    }

    public ChannelPanelController getChannelPanelController() {
        return channelPanelController;
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

    public void applyPanelBackground(int bgIndex) {
        int resId;
        switch (bgIndex) {
            case 1: resId = R.drawable.panel_bg_1; break;
            case 2: resId = R.drawable.panel_bg_2; break;
            case 3: resId = R.drawable.panel_bg_3; break;
            case 4: resId = R.drawable.panel_bg_4; break;
            default: resId = R.drawable.panel_bg; break;
        }
        panelLayout.setBackgroundColor(0x00000000); // 全屏容器保持透明，不遮挡视频画面
        if (channelPanelController != null) channelPanelController.updatePanelBackground(resId);
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

        // 🔴【关键修复】SDK 播放器容器也绑定同一套手势监听
        // 否则切换到虎牙 SDK 播放器后，SDK 容器遮挡 PlayerView，手势面板无法唤起
        FrameLayout sdkContainer = mPlayerManager.getSdkPlayerContainer();
        if (sdkContainer != null) {
            sdkContainer.setOnTouchListener(touchListener);
            sdkContainer.setClickable(true);
            sdkContainer.setFocusable(false);
            Log.d("MainActivity", "SDK 播放器容器手势监听已绑定");
        }

        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            infoDisplayManager.updateLiveInfo(info);
            if (pipManager != null) pipManager.updatePlayState(true);
        });
        
        // ================================================================
        // ✅【核心修复 1】将 runOnUiThread 改为 mMainHandler.post，防止空指针
        // ================================================================
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
                // ✅【核心修复 2】替换 runOnUiThread 为 mMainHandler.post
                mMainHandler.post(() -> {
                    List<Channel> finalList = appCoreManager.getChannelList();
                    channelSourceList.clear();
                    channelSourceList.addAll(finalList);
                    channelPanelController.setChannels(channelSourceList);

                    if (!channelSourceList.isEmpty()) {
                        // 🔧 修复：优先匹配当前正在播放的频道名
                        // （切源后优先续播用户之前看的频道）
                        String lastChannelName = null;
                        if (mPlayerManager != null
                                && mPlayerManager.getCurrentChannel() != null) {
                            lastChannelName = mPlayerManager.getCurrentChannel().getName();
                        }
                        // 如果播放器没有记录，回退到 AppConfig 保存的 lastPlayIndex 对应频道名
                        if (TextUtils.isEmpty(lastChannelName)) {
                            int savedIdx = appConfig.getLastPlayIndex();
                            if (savedIdx >= 0 && savedIdx < channelSourceList.size()) {
                                lastChannelName = channelSourceList.get(savedIdx).getName();
                            }
                        }

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
                            // 如果频道名完全不匹配（换源后列表完全不同），
                            // 并且 currentPlayIndex 越界，则使用 AppConfig 保存的索引或 0
                            int savedIdx = appConfig.getLastPlayIndex();
                            if (savedIdx >= 0 && savedIdx < channelSourceList.size()) {
                                currentPlayIndex = savedIdx;
                            } else {
                                currentPlayIndex = 0;
                            }
                        }
                    } else {
                        currentPlayIndex = 0;
                    }
                    appConfig.setLastPlayIndex(currentPlayIndex);
                    channelPanelController.setCurrentPlayIndex(currentPlayIndex);

                    appCoreManager.setHasPlayedWithCache(true);
                    // 🔧 防御式播放：确保 index 合法 且 频道有播放地址 才调用 playChannel
                    if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                        Channel ch = channelSourceList.get(currentPlayIndex);
                        if (ch != null && !TextUtils.isEmpty(ch.getPlayUrl())) {
                            log("【" + (fromCache ? "缓存" : "网络") + "】自动播放频道 #" + currentPlayIndex + "：" + ch.getName());
                            playChannel(ch, currentPlayIndex);
                        } else {
                            log("【" + (fromCache ? "缓存" : "网络") + "】⚠️ 当前索引频道无播放地址，尝试从头播放");
                            // 兜底：找到第一个有播放地址的频道播放
                            for (int i = 0; i < channelSourceList.size(); i++) {
                                Channel fallback = channelSourceList.get(i);
                                if (fallback != null && !TextUtils.isEmpty(fallback.getPlayUrl())) {
                                    currentPlayIndex = i;
                                    appConfig.setLastPlayIndex(i);
                                    channelPanelController.setCurrentPlayIndex(i);
                                    playChannel(fallback, i);
                                    break;
                                }
                            }
                        }
                    }
                    log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channelSourceList.size());
                });
            }

            @Override
            public void onLiveSourceFailed(String errorMsg) {
                mMainHandler.post(() -> {
                    if (channelSourceList.isEmpty()) {
                        Toast.makeText(MainActivity.this, "加载失败，请检查网络或稍后重试", Toast.LENGTH_LONG).show();
                    } else {
                        log("【网络】加载失败，继续使用现有数据（频道数" + channelSourceList.size() + "）");
                    }
                });
            }

            @Override
            public void onEpgLoaded() {
                // ✅【核心修复 2】替换 runOnUiThread 为 mMainHandler.post
                mMainHandler.post(() -> {
                    if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                        Channel curr = channelSourceList.get(currentPlayIndex);
                        infoDisplayManager.updateEpgInfo(curr);
                    }
                });
            }

            @Override
            public void onLoadTimeout(boolean hasData) {
                // 🟢 已移除启动Loading阻塞，超时不再需要隐藏UI
                mMainHandler.post(() -> {
                    log("【加载】超时（当前已" + (hasData ? "有缓存数据" : "空列表") + "），后台将继续加载");
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
            // ✅【核心修复 2】替换 runOnUiThread 为 mMainHandler.post
            mMainHandler.post(() -> {
                List<Channel> newList = appCoreManager.getChannelList();
                // 🔧 关键修复：如果频道列表为空（新数据还在加载中），
                // 不做任何操作，避免把 currentPlayIndex 错误重置为 0，
                // 也避免覆盖 AppConfig 中保存的上次播放索引。
                // 真正的播放切换等 onLiveSourceLoaded 加载完成后再执行。
                if (newList == null || newList.isEmpty()) {
                    log("【刷新】频道列表尚未就绪，等待 onLiveSourceLoaded 加载完成后自动播放");
                    return;
                }
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

    private void loadSettings() {
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);
        pipEnable = sp.getBoolean("pip_enable", false);

        String decoderMode = sp.getString("decoder_mode", "auto");
        int mode = TVPlayerManager.DECODER_MODE_AUTO;
        if ("hard".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_HARD;
        } else if ("soft".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_SOFT;
        } else {
            mode = TVPlayerManager.DECODER_MODE_AUTO;
        }

        if (mPlayerManager != null) mPlayerManager.setDecoderMode(mode);
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

        // 切台时清空数字输入缓存
        numberInputBuffer.setLength(0);
        mMainHandler.removeCallbacks(numberInputConfirmTask);

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

        // 🟢【修改为自动延长分割线】
        if (sp.getBoolean("debug_log_enable", false)) {
            LogCollector.getInstance().addDivider();
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
        if (exitMenuDialog != null && exitMenuDialog.isShowing()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_exit_menu, null);
        builder.setView(view);

        Button btnRest = view.findViewById(R.id.btn_rest);
        Button btnSettings = view.findViewById(R.id.btn_settings);

        exitMenuDialog = builder.create();

        if (exitMenuDialog != null) {
            if (exitMenuDialog.getWindow() != null) {
                exitMenuDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                WindowManager.LayoutParams lp = exitMenuDialog.getWindow().getAttributes();
                lp.dimAmount = 0.5f;
                exitMenuDialog.getWindow().setAttributes(lp);
                exitMenuDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            exitMenuDialog.setOnDismissListener(dialog -> exitMenuDialog = null);
            exitMenuDialog.show();

            btnRest.setFocusable(true);
            btnRest.setFocusableInTouchMode(true);
            btnSettings.setFocusable(true);
            btnSettings.setFocusableInTouchMode(true);

            btnRest.post(() -> btnRest.requestFocus());
        }

        btnRest.setOnClickListener(v -> {
            if (exitMenuDialog != null) {
                exitMenuDialog.dismiss();
            }
            finishAffinity();
        });

        btnSettings.setOnClickListener(v -> {
            if (exitMenuDialog != null) {
                exitMenuDialog.dismiss();
            }
            mMainHandler.postDelayed(() -> {
                isOpeningSettings = false;
                openSettings();
            }, 100);
        });
    }

    /**
     * 🔧 统一的返回处理入口。
     * 所有路径（AndroidX OnBackPressedCallback、系统OnBackInvokedCallback、硬件KEYCODE_BACK、onBackKey远程回调）
     * 都走这里。使用 300ms 去抖避免双路径同时触发（尤其安卓13+预测性返回场景）。
     *
     * @return true 表示返回事件已被消费（不再需要传递给系统默认行为）
     */
    private boolean handleBackPressed() {
        long now = System.currentTimeMillis();
        if (now - mLastBackHandleMs < 300L) {
            android.util.Log.d("KEY_DEBUG", "handleBackPressed: 300ms 内重复触发，已忽略 (安卓13+双路径去抖)");
            return true;
        }
        mLastBackHandleMs = now;
        try {
            // 🔘 时移回看模式 & 控制器显示中 → 先退出控制器
            if (isInCatchUpMode && playerControlManager != null && playerControlManager.isControllerShowing()) {
                exitPlaybackMode();
                return true;
            }

            // 📺 频道面板打开中 → 先尝试从右侧面板返回（如节目单/历史等），否则关闭整个面板
            if (channelPanelController != null) {
                try {
                    if (channelPanelController.isPanelOpen()) {
                        if (channelPanelController.backFromRightPanel()) {
                            return true;
                        }
                        channelPanelController.hidePanel();
                        return true;
                    }
                } catch (Exception e) {
                    android.util.Log.e("KEY_DEBUG", "handleBackPressed hidePanel 异常: " + e.getMessage(), e);
                }
            }

            // 🛑 退出菜单弹窗打开中 → 关闭它
            if (exitMenuDialog != null) {
                try {
                    if (exitMenuDialog.isShowing()) {
                        exitMenuDialog.dismiss();
                        exitMenuDialog = null;
                        return true;
                    }
                } catch (Exception e) {
                    android.util.Log.e("KEY_DEBUG", "handleBackPressed exitMenu 异常: " + e.getMessage(), e);
                    exitMenuDialog = null;
                }
            }

            // ⚙️ 设置面板打开中 → 关闭它
            // NOTE: 这里不更新 settingsCloseTime，避免用户通过返回键关闭设置后
            //       想立刻按第二次返回退出时被 1 秒保护卡住（与旧 onBackPressed 行为保持一致）。
            //       settingsCloseTime 仅在设置自身主动关闭并在面板内部显式写入时才用于防抖。
            if (settingsDialog != null) {
                try {
                    if (settingsDialog.isShowing()) {
                        settingsDialog.dismiss();
                        settingsDialog = null;
                        return true;
                    }
                } catch (Exception e) {
                    android.util.Log.e("KEY_DEBUG", "handleBackPressed settingsDialog 异常: " + e.getMessage(), e);
                    settingsDialog = null;
                }
            }

            // ⏱ 设置刚关闭（1 秒内）→ 忽略一次退出操作，避免用户按返回键导致设置关闭后立刻触发退出确认
            if (settingsCloseTime > 0 && System.currentTimeMillis() - settingsCloseTime < 1000) {
                android.util.Log.d("KEY_DEBUG", "设置刚关闭，忽略退出操作");
                return true;
            }

            // 🚪 主退出逻辑：根据设置判断是否弹出退出确认弹窗
            boolean exitDialogEnabled = sp != null && sp.getBoolean("exit_dialog_enable", false);
            android.util.Log.d("KEY_DEBUG", "退出确认弹窗开关: " + exitDialogEnabled + "，Build.VERSION.SDK_INT=" + Build.VERSION.SDK_INT);
            if (exitDialogEnabled) {
                showExitMenu();
            } else {
                finishAffinity();
            }
            return true;
        } catch (Exception e) {
            android.util.Log.e("KEY_DEBUG", "handleBackPressed 异常: " + e.getMessage(), e);
            // 出错兜底：直接退出避免卡死
            try { finishAffinity(); } catch (Exception ignored) {}
            return true;
        }
    }

    /**
     * 🔧 注册安卓13+预测式返回 / 滑动返回处理
     *
     * 问题根因：
     * - 旧版只重写 onBackPressed() 且未调用 super.onBackPressed()，在 targetSdk=36 / 安卓16（荣耀）
     *   手势导航「边缘滑动退出」时，系统走 OnBackInvokedDispatcher 新链路，旧的 onBackPressed()
     *   和 dispatchKeyEvent(KEYCODE_BACK) 都不执行 → exit_dialog_enable 判断被跳过 → 退出弹窗不弹出。
     *
     * 修复策略（双保险）：
     *  1) AndroidX OnBackPressedCallback — 跨所有 API 级别，谷歌官方推荐，在
     *     ComponentActivity/AppCompatActivity 中自动桥接安卓13+预测性返回动画
     *  2) API 33+ 系统 OnBackInvokedCallback — 直接注册到 Window 级别，应对部分 OEM（荣耀/鸿蒙）
     *     不按 AndroidX 预期分发、直接走系统窗口层的情况
     * 两者通过 handleBackPressed 内的 300ms 去抖避免双触发。
     */
    private void setupBackPressHandling() {
        try {
            // —— 保险 1：AndroidX OnBackPressedCallback（全API级别）
            OnBackPressedCallback callback = new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    android.util.Log.d("KEY_DEBUG", "【OnBackPressedCallback】(AndroidX) triggered");
                    handleBackPressed();
                }
            };
            getOnBackPressedDispatcher().addCallback(this, callback);
            log("【BackCompat】已注册 AndroidX OnBackPressedCallback (通用方案)");
        } catch (Throwable t) {
            Log.e("BackCompat", "注册 OnBackPressedCallback 失败: " + t.getMessage());
        }

        // —— 保险 2：系统 API 33+ OnBackInvokedCallback（荣耀安卓16手势返回兜底）
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                android.window.OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
                if (dispatcher != null) {
                    // 如果上次已经注册过，先反注册避免重复（横竖屏切换 / Activity 重建时）
                    if (mSystemBackInvokedCb instanceof android.window.OnBackInvokedCallback) {
                        try {
                            dispatcher.unregisterOnBackInvokedCallback(
                                (android.window.OnBackInvokedCallback) mSystemBackInvokedCb);
                        } catch (Throwable ignore) {}
                    }
                    final Runnable handler = () -> {
                        android.util.Log.d("KEY_DEBUG", "【OnBackInvokedCallback】(System API 33+) gesture back triggered");
                        runOnUiThread(this::handleBackPressed);
                    };
                    android.window.OnBackInvokedCallback systemCb = new android.window.OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() { handler.run(); }
                    };
                    dispatcher.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, systemCb
                    );
                    mSystemBackInvokedCb = systemCb;
                    log("【BackCompat】已注册系统级 OnBackInvokedCallback (API 33+ 荣耀安卓16兜底)");
                }
            } catch (Throwable t) {
                Log.e("BackCompat", "注册系统 OnBackInvokedCallback 失败: " + t.getMessage());
            }
        }
    }

    /**
     * @deprecated 保留仅为兼容：dispatchKeyEvent / onBackKey 等地方仍在显式调用 onBackPressed()。
     * 新代码 / 系统手势返回现在走 handleBackPressed()（由 OnBackPressedCallback 驱动）。
     */
    @Override
    @Deprecated
    public void onBackPressed() {
        handleBackPressed();
    }

    public void openSettings() {
        try {
            long now = System.currentTimeMillis();

            if (isOpeningSettings) {
                if (now - lastSettingsOpenTime > 5000) {
                    Log.d("Settings", "强制解锁 isOpeningSettings（超过 5 秒）");
                    isOpeningSettings = false;
                } else {
                    Log.d("Settings", "isOpeningSettings 为 true，被拦截（距离上次尝试不到 5 秒）");
                    return;
                }
            }

            if (isInCatchUpMode) return;

            lastSettingsOpenTime = now;
            isOpeningSettings = true;

            try {
                if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                    channelPanelController.hidePanel();
                }
            } catch (Exception e) {
                Log.e("Settings", "hidePanel 失败", e);
            }

            try {
                if (playerControlManager != null) {
                    playerControlManager.onOpenSettings();
                }
            } catch (Exception e) {
                Log.e("Settings", "onOpenSettings 失败", e);
            }

            try {
                settingsDialog = new SettingsDialog(this);
                settingsDialog.show();
                Log.d("Settings", "SettingsDialog 显示成功");
            } catch (Exception e) {
                Log.e("Settings", "显示 SettingsDialog 失败", e);
                isOpeningSettings = false;
            }
        } catch (Exception e) {
            Log.e("Settings", "打开设置失败", e);
            isOpeningSettings = false;
        }
    }

    // 🔧 刷新配置
    public void refreshSettings() {
        // ✅【核心修复 3】将 runOnUiThread 改为 mMainHandler.post
        mMainHandler.post(() -> {
            loadSettings();
            
            if (screenRatioManager != null) {
                screenRatioManager.apply();
            }
            
            if (pipManager != null) {
                pipManager.setPipEnabled(pipEnable);
            }

            if (channelPanelController != null) {
                channelPanelController.setReverse(channel_reverse);
            }
            
            Log.d("MainActivity", "设置已主动刷新，无需切后台");
        });
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
        if (pipManager != null) pipManager.enterPip(this, mPlayerManager, pipEnable);
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

    // 🔴【核心修改】严格控制的日志写入
    private void log(String msg) {
        if (!sp.getBoolean("debug_log_enable", false)) {
            return; // 没开记录开关，直接拦截
        }
        Log.d("MainActivity", msg);
        // 传入 "播放" tag 以通过白名单过滤
        LogCollector.getInstance().addLog("播放", msg);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isOpeningSettings) {
            return;
        }
        mMainHandler.removeCallbacks(openSettingsRunnable);
        mMainHandler.removeCallbacks(logUpdateRunnable);
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

    // 🔧 修复：小窗/自由窗口模式调整窗口大小时保持播放不中断。
    // AndroidManifest 已配置 configChanges 包含 screenSize|smallestScreenSize|screenLayout，
    // 所以窗口大小变化不会重建 Activity，但 Surface 可能被销毁重建。
    // 在这里主动恢复播放器，避免黑屏或停止播放。
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 窗口大小变化后，延迟恢复播放器，给 Surface 重建留出时间
        mMainHandler.postDelayed(() -> {
            if (mPlayerManager != null) {
                mPlayerManager.onForeground();
            }
            displayManager.reapplyFullScreen();
        }, 200);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100) {
            isOpeningSettings = false;
            okKeyTriggered = false;
            android.util.Log.d("MainActivity", "onActivityResult: Settings closed, isOpeningSettings reset to false");
        }
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
            // 🔧 修复：失去窗口焦点时不暂停播放器。
            // 在高版本安卓（10-16）上，以下场景会触发失去焦点：
            // - 通知栏下拉
            // - 系统对话框弹出（权限请求等）
            // - 多窗口/自由窗口模式切换
            // - 最近任务键
            // 如果在这里暂停播放器，回前台时容易黑屏（Surface 已销毁需要重建）。
            // 播放器的暂停应该由 onPause/onStop 生命周期控制，而不是窗口焦点。
            // 仅在非多窗口/非画中画模式下才考虑暂停。
            if (mPlayerManager != null && !isOpeningSettings) {
                boolean isSystemVisible = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    isSystemVisible = isInMultiWindowMode() || isInPictureInPictureMode();
                }
                if (!isSystemVisible) {
                    mPlayerManager.pause();
                }
            }
        }
        wasWindowFocused = hasFocus;
        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    // ================================================================
    // ✅【核心修复 4】移除全部 Message，解除播放器的所有监听器
    // ================================================================
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mInstanceRef != null) {
            mInstanceRef.clear();
            mInstanceRef = null;
        }

        mMainHandler.removeCallbacksAndMessages(null);

        if (remoteKeyHandler != null) {
            remoteKeyHandler.release();
            remoteKeyHandler = null;
        }

        if (lifecycleHelper != null) {
            lifecycleHelper.releaseAll();
            lifecycleHelper = null;
        }

        // 清理触摸监听器中的手势辅助
        if (touchListener != null) {
            touchListener.updateGestureHelper(null);
            if (playerView != null) {
                playerView.setOnTouchListener(null);
            }
            touchListener = null;
        }

        // 清理Dialogs
        if (settingsDialog != null) {
            try {
                if (settingsDialog.isShowing()) {
                    settingsDialog.dismiss();
                }
            } catch (Exception ignored) {}
            settingsDialog = null;
        }
        if (exitMenuDialog != null) {
            try {
                if (exitMenuDialog.isShowing()) {
                    exitMenuDialog.dismiss();
                }
            } catch (Exception ignored) {}
            exitMenuDialog = null;
        }

        // 释放管理器
        if (infoDisplayManager != null) {
            infoDisplayManager.release();
            infoDisplayManager = null;
        }
        if (displayManager != null) {
            displayManager.release();
            displayManager = null;
        }
        if (channelPanelController != null) {
            channelPanelController.release();
            channelPanelController = null;
        }
        if (appCoreManager != null) {
            appCoreManager.release();
            appCoreManager = null;
        }
        if (pipManager != null) {
            pipManager.release();
            pipManager = null;
        }

        // 释放播放器
        if (playerControlManager != null) {
            playerControlManager.release();
            playerControlManager = null;
        }

        if (mPlayerManager != null) {
            mPlayerManager.setOnPlayStateListener(null);
            mPlayerManager.setOnLiveInfoUpdateListener(null);
            mPlayerManager.setOnSourceFailedListener(null);
            mPlayerManager.release();
            mPlayerManager = null;
        }

        // 关闭TVPlayerManager的静态线程池
        // 🔧 修复：不在 onDestroy 关闭静态线程池，避免重开应用后播放列表无法解析导致黑屏
        // TVPlayerManager.shutdownThreadPool();

        // 注销广播接收器
        if (unlockReceiver != null) {
            try {
                unregisterReceiver(unlockReceiver);
            } catch (Exception ignored) {}
            unlockReceiver = null;
        }

        // 清理其他引用
        gestureManager = null;
        screenRatioManager = null;
        playerStateListener = null;
        appConfig = null;
        channelSourceList.clear();
        channelSourceList = null;
    }
}
