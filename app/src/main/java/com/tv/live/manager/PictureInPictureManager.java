package com.tv.live.manager;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Rational;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 画中画管理类（单例模式）【究极无敌完整版】
 *
 * 【完整功能清单 - 共 89 项功能】
 *
 * 一、基础功能（5项）
 * 二、显示设置（11项）
 * 三、播放控制（9项）
 * 四、智能功能（9项）
 * 五、录制功能（10项）
 * 六、多画中画（7项）
 * 七、手势操作（3项）
 * 八、快捷方式（3项）
 * 九、夜间模式（3项）
 * 十、网络优化（3项）
 * 十一、游戏模式（4项）
 * 十二、多音频（3项）
 * 十三、弹幕（5项）
 * 十四、滤镜（4项）
 * 十五、数据面板（4项）
 * 十六、无缝切换（4项）
 * 十七、统计与配置（5项）
 */
public class PictureInPictureManager {
    private static final String TAG = "PIPManager";
    private static PictureInPictureManager instance;
    private final Context context;
    private final SharedPreferences sp;
    private final Handler handler;

    // ================================================
    // 配置常量
    // ================================================
    private static final String PREFS_NAME = "pip_settings";

    // 开关类
    private static final String KEY_PIP_ENABLED = "pip_enabled";
    private static final String KEY_PIP_AUTO_ENTER = "pip_auto_enter";
    private static final String KEY_PIP_SHOW_CONTROLS = "pip_show_controls";
    private static final String KEY_PIP_AUTO_MUTE = "pip_auto_mute";
    private static final String KEY_PIP_KEEP_SCREEN_ON = "pip_keep_screen_on";
    private static final String KEY_PIP_HEADSET_PAUSE = "pip_headset_pause";
    private static final String KEY_PIP_SHOW_CHANNEL_NUM = "pip_show_channel_num";
    private static final String KEY_PIP_DATA_SAVER = "pip_data_saver";
    private static final String KEY_PIP_EPG_REMIND = "pip_epg_remind";
    private static final String KEY_PIP_NOTIFICATION = "pip_notification";
    private static final String KEY_PIP_RECORD_ENABLED = "pip_record_enabled";
    private static final String KEY_PIP_MULTI_WINDOW = "pip_multi_window";
    private static final String KEY_PIP_BATTERY_SAVER = "pip_battery_saver";
    private static final String KEY_PIP_TOUCH_BLOCK = "pip_touch_block";
    private static final String KEY_PIP_SHOW_INFO = "pip_show_info";
    private static final String KEY_PIP_AUTO_BRIGHTNESS = "pip_auto_brightness";
    private static final String KEY_PIP_AUTO_ROTATE = "pip_auto_rotate";
    private static final String KEY_PIP_NIGHT_MODE = "pip_night_mode";
    private static final String KEY_PIP_LOW_PRIORITY_NETWORK = "pip_low_priority_network";
    private static final String KEY_PIP_GAME_MODE = "pip_game_mode";
    private static final String KEY_PIP_MULTI_AUDIO = "pip_multi_audio";
    private static final String KEY_PIP_DANMAKU = "pip_danmaku";
    private static final String KEY_PIP_FILTER = "pip_filter";
    private static final String KEY_PIP_DATA_PANEL = "pip_data_panel";
    private static final String KEY_PIP_SEAMLESS_SWITCH = "pip_seamless_switch";

    // 参数类
    private static final String KEY_PIP_ASPECT_RATIO = "pip_aspect_ratio";
    private static final String KEY_PIP_TIMEOUT_MINUTES = "pip_timeout_minutes";
    private static final String KEY_PIP_GRAVITY = "pip_gravity";
    private static final String KEY_PIP_RECORD_QUALITY = "pip_record_quality";
    private static final String KEY_PIP_RECORD_AUDIO = "pip_record_audio";
    private static final String KEY_PIP_THEME = "pip_theme";
    private static final String KEY_PIP_INFO_POSITION = "pip_info_position";
    private static final String KEY_PIP_SMALL_WINDOW_SIZE = "pip_small_window_size";
    private static final String KEY_PIP_MULTI_LAYOUT = "pip_multi_layout";
    private static final String KEY_PIP_NIGHT_START = "pip_night_start";
    private static final String KEY_PIP_NIGHT_END = "pip_night_end";
    private static final String KEY_PIP_GAME_LATENCY = "pip_game_latency";
    private static final String KEY_PIP_MAIN_VOLUME = "pip_main_volume";
    private static final String KEY_PIP_SECOND_VOLUME = "pip_second_volume";
    private static final String KEY_PIP_DANMAKU_SIZE = "pip_danmaku_size";
    private static final String KEY_PIP_DANMAKU_SPEED = "pip_danmaku_speed";
    private static final String KEY_PIP_DANMAKU_OPACITY = "pip_danmaku_opacity";
    private static final String KEY_PIP_FILTER_INTENSITY = "pip_filter_intensity";
    private static final String KEY_PIP_DATA_ITEMS = "pip_data_items";
    private static final String KEY_PIP_PRELOAD_COUNT = "pip_preload_count";

    // 统计相关
    private static final String KEY_PIP_TOTAL_TIME = "pip_total_time";
    private static final String KEY_PIP_USE_COUNT = "pip_use_count";
    private static final String KEY_PIP_RECORD_COUNT = "pip_record_count";
    private static final String KEY_PIP_RECORD_TOTAL_TIME = "pip_record_total_time";

    // 宽高比枚举
    public static final int RATIO_16_9 = 0;
    public static final int RATIO_4_3 = 1;
    public static final int RATIO_21_9 = 2;
    public static final int RATIO_1_1 = 3;

    private static final float[][] RATIO_VALUES = {
            {16, 9}, {4, 3}, {21, 9}, {1, 1}
    };

    private static final String[] RATIO_NAMES = {
            "16:9 (默认)", "4:3 (标清)", "21:9 (电影)", "1:1 (正方形)"
    };

    // 默认位置枚举
    public static final int GRAVITY_BOTTOM_RIGHT = 0;
    public static final int GRAVITY_BOTTOM_LEFT = 1;
    public static final int GRAVITY_TOP_RIGHT = 2;
    public static final int GRAVITY_TOP_LEFT = 3;

    private static final String[] GRAVITY_NAMES = {
            "右下角", "左下角", "右上角", "左上角"
    };

    // 录制质量枚举
    public static final int RECORD_QUALITY_LOW = 0;
    public static final int RECORD_QUALITY_MEDIUM = 1;
    public static final int RECORD_QUALITY_HIGH = 2;

    private static final String[] RECORD_QUALITY_NAMES = {
            "标清 (480p)", "高清 (720p)", "全高清 (1080p)"
    };

    private static final int[][] RECORD_RESOLUTIONS = {
            {854, 480}, {1280, 720}, {1920, 1080}
    };

    // 小窗主题枚举
    public static final int THEME_DEFAULT = 0;
    public static final int THEME_TRANSPARENT = 1;
    public static final int THEME_BLUE = 2;
    public static final int THEME_GLASS = 3;
    public static final int THEME_MINIMAL = 4;

    private static final String[] THEME_NAMES = {
            "默认", "无边框", "科技蓝", "毛玻璃", "极简"
    };

    // 信息显示位置枚举
    public static final int INFO_POS_TOP = 0;
    public static final int INFO_POS_BOTTOM = 1;
    public static final int INFO_POS_LEFT = 2;
    public static final int INFO_POS_RIGHT = 3;

    private static final String[] INFO_POS_NAMES = {
            "顶部", "底部", "左侧", "右侧"
    };

    // 小窗大小枚举
    public static final int WINDOW_SIZE_SMALL = 0;
    public static final int WINDOW_SIZE_MEDIUM = 1;
    public static final int WINDOW_SIZE_LARGE = 2;

    private static final String[] WINDOW_SIZE_NAMES = {
            "小", "中", "大"
    };

    private static final float[] WINDOW_SIZE_RATIOS = {
            0.2f, 0.3f, 0.4f
    };

    // 多窗口布局枚举
    public static final int MULTI_LAYOUT_FLOAT = 0;
    public static final int MULTI_LAYOUT_GRID = 1;
    public static final int MULTI_LAYOUT_PIP = 2;

    private static final String[] MULTI_LAYOUT_NAMES = {
            "浮动", "网格", "画中画"
    };

    // 游戏延迟等级
    public static final int LATENCY_LOW = 0;
    public static final int LATENCY_NORMAL = 1;
    public static final int LATENCY_HIGH = 2;

    private static final String[] LATENCY_NAMES = {
            "低延迟（游戏）", "正常", "高延迟（流畅）"
    };

    // 弹幕大小
    public static final int DANMAKU_SIZE_SMALL = 0;
    public static final int DANMAKU_SIZE_MEDIUM = 1;
    public static final int DANMAKU_SIZE_LARGE = 2;

    private static final String[] DANMAKU_SIZE_NAMES = {
            "小", "中", "大"
    };

    // 弹幕速度
    public static final int DANMAKU_SPEED_SLOW = 0;
    public static final int DANMAKU_SPEED_NORMAL = 1;
    public static final int DANMAKU_SPEED_FAST = 2;

    private static final String[] DANMAKU_SPEED_NAMES = {
            "慢", "正常", "快"
    };

    // 滤镜类型
    public static final int FILTER_NONE = 0;
    public static final int FILTER_NORMAL = 1;
    public static final int FILTER_VIVID = 2;
    public static final int FILTER_CINEMA = 3;
    public static final int FILTER_MONOCHROME = 4;
    public static final int FILTER_SEPIA = 5;
    public static final int FILTER_COOL = 6;
    public static final int FILTER_WARM = 7;
    public static final int FILTER_NIGHT = 8;

    private static final String[] FILTER_NAMES = {
            "无", "标准", "鲜艳", "电影", "黑白", "复古", "冷色调", "暖色调", "夜视"
    };

    // 数据项标志位
    public static final int DATA_BITRATE = 1 << 0;
    public static final int DATA_FRAMERATE = 1 << 1;
    public static final int DATA_BUFFER = 1 << 2;
    public static final int DATA_RESOLUTION = 1 << 3;
    public static final int DATA_DURATION = 1 << 4;
    public static final int DATA_NETWORK = 1 << 5;

    private static final int DEFAULT_TIMEOUT_MINUTES = 0;
    private static final int DEFAULT_NIGHT_START_HOUR = 22;
    private static final int DEFAULT_NIGHT_END_HOUR = 6;

    // 通知相关
    private static final String NOTIFICATION_CHANNEL_ID = "pip_playback_control";
    private static final String RECORD_CHANNEL_ID = "pip_record";
    private static final String SCHEDULE_CHANNEL_ID = "pip_schedule";
    private static final int NOTIFICATION_ID = 1001;
    private static final int RECORD_NOTIFICATION_ID = 1002;
    private static final int SCHEDULE_NOTIFICATION_ID = 1003;

    // 广播 Action
    private static final String ACTION_PIP_PLAY_PAUSE = "com.tv.live.pip.PLAY_PAUSE";
    private static final String ACTION_PIP_PREV = "com.tv.live.pip.PREV";
    private static final String ACTION_PIP_NEXT = "com.tv.live.pip.NEXT";
    private static final String ACTION_PIP_CLOSE = "com.tv.live.pip.CLOSE";
    private static final String ACTION_PIP_FULLSCREEN = "com.tv.live.pip.FULLSCREEN";
    private static final String ACTION_PIP_RECORD = "com.tv.live.pip.RECORD";
    private static final String ACTION_PIP_SCHEDULE_RECORD = "com.tv.live.pip.SCHEDULE_RECORD";

    // 快捷方式 ID
    private static final String SHORTCUT_ID_PIP = "pip_shortcut";
    private static final String SHORTCUT_ID_RECORD = "record_shortcut";

    // 手势相关常量
    private static final float SWIPE_THRESHOLD = 50;
    private static final float PINCH_THRESHOLD = 20;

    // ================================================
    // 成员变量
    // ================================================
    private OnPipModeChangedListener pipListener;
    private OnRecordStateListener recordListener;
    private OnMultiWindowListener multiWindowListener;
    private OnEpgJumpListener epgJumpListener;
    private OnSeamlessSwitchListener seamlessListener;

    private PipControlReceiver controlReceiver;
    private HeadsetReceiver headsetReceiver;
    private ScheduleReceiver scheduleReceiver;

    private boolean isPlaying = false;
    private boolean isInPipMode = false;
    private String currentChannelName = "";
    private int currentChannelNumber = 0;
    private String currentProgramName = "";
    private String currentUrl = "";

    private boolean isControlReceiverRegistered = false;
    private boolean isHeadsetReceiverRegistered = false;
    private boolean isScheduleReceiverRegistered = false;
    private boolean isAutoRotateRegistered = false;

    private Runnable timeoutRunnable;
    private boolean isTimeoutRunning = false;

    private MediaSessionCompat mediaSession;
    private NotificationManager notificationManager;
    private WindowManager windowManager;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    // 双击检测
    private long lastPipClickTime = 0;
    private static final long DOUBLE_CLICK_INTERVAL = 300;

    // 统计
    private long pipEnterTime = 0;

    // 手势状态
    private float gestureStartX = 0;
    private float gestureStartY = 0;
    private boolean isGestureStarted = false;
    private int gesturePointerCount = 0;
    private float gestureStartDistance = 0;

    // ================================================
    // 录制功能相关
    // ================================================
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private String recordFilePath = "";
    private long recordStartTime = 0;
    private Runnable recordTimeRunnable;
    private int recordDurationSeconds = 0;

    // 定时录制列表
    private List<ScheduleRecordInfo> scheduleList = new ArrayList<>();

    // 录制历史记录
    private List<RecordHistoryItem> recordHistory = new ArrayList<>();

    // ================================================
    // 多画中画相关
    // ================================================
    private boolean isMultiWindowMode = false;
    private List<MultiWindowInfo> multiWindowList = new ArrayList<>();
    private int mainWindowIndex = 0;

    // ================================================
    // 多音频相关
    // ================================================
    private boolean isMultiAudioMode = false;

    // ================================================
    // 弹幕相关
    // ================================================
    private boolean isDanmakuShowing = false;

    // ================================================
    // 滤镜相关
    // ================================================
    private int currentFilter = FILTER_NONE;

    // ================================================
    // 数据面板相关
    // ================================================
    private boolean isDataPanelShowing = false;
    private long currentBitrate = 0;
    private float currentFrameRate = 0;
    private long currentBufferMs = 0;
    private int currentWidth = 0;
    private int currentHeight = 0;
    private long playDurationMs = 0;
    private String currentNetworkType = "";

    // ================================================
    // 无缝切换相关
    // ================================================
    private boolean isSeamlessEnabled = false;
    private int preloadChannelIndex = -1;
    private String preloadChannelName = "";
    private boolean isPreloading = false;

    // ================================================
    // 内部类
    // ================================================

    /**
     * 定时录制信息
     */
    public static class ScheduleRecordInfo {
        public int id;
        public String channelName;
        public String url;
        public long startTime;
        public long durationMinutes;
        public String programName;
        public boolean isEnabled;

        public ScheduleRecordInfo(int id, String channelName, String url,
                                  long startTime, long durationMinutes, String programName) {
            this.id = id;
            this.channelName = channelName;
            this.url = url;
            this.startTime = startTime;
            this.durationMinutes = durationMinutes;
            this.programName = programName;
            this.isEnabled = true;
        }
    }

    /**
     * 录制历史记录
     */
    public static class RecordHistoryItem {
        public String filePath;
        public String channelName;
        public String programName;
        public long startTime;
        public int durationSeconds;
        public long fileSize;
        public int quality;

        public RecordHistoryItem(String filePath, String channelName, String programName,
                                 long startTime, int durationSeconds, long fileSize, int quality) {
            this.filePath = filePath;
            this.channelName = channelName;
            this.programName = programName;
            this.startTime = startTime;
            this.durationSeconds = durationSeconds;
            this.fileSize = fileSize;
            this.quality = quality;
        }
    }

    /**
     * 多窗口信息
     */
    public static class MultiWindowInfo {
        public int channelNumber;
        public String channelName;
        public String url;
        public boolean isPlaying;
        public Rect position;

        public MultiWindowInfo(int channelNumber, String channelName, String url) {
            this.channelNumber = channelNumber;
            this.channelName = channelName;
            this.url = url;
            this.isPlaying = false;
        }
    }

    // ================================================
    // 监听器接口
    // ================================================

    public interface OnPipModeChangedListener {
        void onPipModeChanged(boolean isInPipMode);
        void onPipPlayPause();
        void onPipPrevChannel();
        void onPipNextChannel();
        boolean onPipTimeout();
        void onDataSaverModeChanged(boolean enable);
        void onRequestFullscreen();
        void onRecordStateChanged(boolean isRecording, int durationSeconds);
        void onBatterySaverModeChanged(boolean enable);
        void onScheduleRecordStart(ScheduleRecordInfo info);
    }

    public interface OnRecordStateListener {
        void onRecordStarted(String filePath);
        void onRecordStopped(String filePath, int durationSeconds);
        void onRecordError(String error);
        void onRecordTimeUpdate(int seconds);
    }

    public interface OnMultiWindowListener {
        void onMultiWindowModeChanged(boolean isMultiWindow);
        void onWindowSwitched(int mainIndex);
        void onWindowAdded(int index, MultiWindowInfo info);
        void onWindowRemoved(int index);
    }

    public interface OnEpgJumpListener {
        void onJumpToProgram(String programId, String programName, long startTime);
    }

    public interface OnSeamlessSwitchListener {
        void onPreloadNext(String channelName, String url);
        void onPreloadPrev(String channelName, String url);
        void onSwitchToPreloaded(int direction);
    }

    // ================================================
    // 单例
    // ================================================

    private PictureInPictureManager(Context ctx) {
        context = ctx.getApplicationContext();
        sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        loadRecordHistory();
        loadScheduleList();
    }

    public static PictureInPictureManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new PictureInPictureManager(ctx);
        }
        return instance;
    }
        // ================================================
    // ✅ 一、基础功能
    // ================================================

    public boolean isPipSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "系统版本低于 Android 8.0，不支持画中画");
            return false;
        }
        PackageManager pm = context.getPackageManager();
        boolean supported = pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        Log.d(TAG, "设备是否支持画中画：" + supported);
        return supported;
    }

    public boolean isPipEnabled() {
        return sp.getBoolean(KEY_PIP_ENABLED, false);
    }

    public void setPipEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_ENABLED, enabled).apply();
        Log.d(TAG, "画中画总开关：" + enabled);
        if (!enabled) {
            cancelNotification();
            stopRecording();
            exitMultiWindowMode();
            cancelAllSchedules();
        }
    }

    public boolean isAutoEnterPip() {
        return sp.getBoolean(KEY_PIP_AUTO_ENTER, true);
    }

    public void setAutoEnterPip(boolean auto) {
        sp.edit().putBoolean(KEY_PIP_AUTO_ENTER, auto).apply();
        Log.d(TAG, "自动进入画中画：" + auto);
    }

    // ================================================
    // ✅ 二、显示设置
    // ================================================

    public boolean isShowControls() {
        return sp.getBoolean(KEY_PIP_SHOW_CONTROLS, true);
    }

    public void setShowControls(boolean show) {
        sp.edit().putBoolean(KEY_PIP_SHOW_CONTROLS, show).apply();
        Log.d(TAG, "显示画中画控制按钮：" + show);
    }

    public int getAspectRatio() {
        return sp.getInt(KEY_PIP_ASPECT_RATIO, RATIO_16_9);
    }

    public void setAspectRatio(int ratio) {
        if (ratio < 0 || ratio >= RATIO_VALUES.length) ratio = RATIO_16_9;
        sp.edit().putInt(KEY_PIP_ASPECT_RATIO, ratio).apply();
        Log.d(TAG, "画中画宽高比：" + RATIO_NAMES[ratio]);
    }

    public String getAspectRatioName() {
        return RATIO_NAMES[getAspectRatio()];
    }

    public String[] getAllAspectRatioNames() {
        return RATIO_NAMES.clone();
    }

    public boolean isShowChannelNum() {
        return sp.getBoolean(KEY_PIP_SHOW_CHANNEL_NUM, true);
    }

    public void setShowChannelNum(boolean show) {
        sp.edit().putBoolean(KEY_PIP_SHOW_CHANNEL_NUM, show).apply();
        Log.d(TAG, "画中画显示频道号：" + show);
    }

    public int getDefaultGravity() {
        return sp.getInt(KEY_PIP_GRAVITY, GRAVITY_BOTTOM_RIGHT);
    }

    public void setDefaultGravity(int gravity) {
        if (gravity < 0 || gravity >= GRAVITY_NAMES.length) gravity = GRAVITY_BOTTOM_RIGHT;
        sp.edit().putInt(KEY_PIP_GRAVITY, gravity).apply();
        Log.d(TAG, "画中画默认位置：" + GRAVITY_NAMES[gravity]);
    }

    public String getDefaultGravityName() {
        return GRAVITY_NAMES[getDefaultGravity()];
    }

    public String[] getAllGravityNames() {
        return GRAVITY_NAMES.clone();
    }

    // 小窗主题
    public int getTheme() {
        return sp.getInt(KEY_PIP_THEME, THEME_DEFAULT);
    }

    public void setTheme(int theme) {
        if (theme < 0 || theme >= THEME_NAMES.length) theme = THEME_DEFAULT;
        sp.edit().putInt(KEY_PIP_THEME, theme).apply();
        Log.d(TAG, "小窗主题：" + THEME_NAMES[theme]);
    }

    public String getThemeName() {
        return THEME_NAMES[getTheme()];
    }

    public String[] getAllThemeNames() {
        return THEME_NAMES.clone();
    }

    // 实时信息显示
    public boolean isShowInfo() {
        return sp.getBoolean(KEY_PIP_SHOW_INFO, false);
    }

    public void setShowInfo(boolean show) {
        sp.edit().putBoolean(KEY_PIP_SHOW_INFO, show).apply();
        Log.d(TAG, "显示实时信息：" + show);
    }

    public int getInfoPosition() {
        return sp.getInt(KEY_PIP_INFO_POSITION, INFO_POS_BOTTOM);
    }

    public void setInfoPosition(int position) {
        if (position < 0 || position >= INFO_POS_NAMES.length) position = INFO_POS_BOTTOM;
        sp.edit().putInt(KEY_PIP_INFO_POSITION, position).apply();
        Log.d(TAG, "信息显示位置：" + INFO_POS_NAMES[position]);
    }

    public String getInfoPositionName() {
        return INFO_POS_NAMES[getInfoPosition()];
    }

    public String[] getAllInfoPositionNames() {
        return INFO_POS_NAMES.clone();
    }

    // 小窗大小
    public int getSmallWindowSize() {
        return sp.getInt(KEY_PIP_SMALL_WINDOW_SIZE, WINDOW_SIZE_MEDIUM);
    }

    public void setSmallWindowSize(int size) {
        if (size < 0 || size >= WINDOW_SIZE_NAMES.length) size = WINDOW_SIZE_MEDIUM;
        sp.edit().putInt(KEY_PIP_SMALL_WINDOW_SIZE, size).apply();
        Log.d(TAG, "小窗大小：" + WINDOW_SIZE_NAMES[size]);
    }

    public String getSmallWindowSizeName() {
        return WINDOW_SIZE_NAMES[getSmallWindowSize()];
    }

    public String[] getAllWindowSizeNames() {
        return WINDOW_SIZE_NAMES.clone();
    }

    public Point getSmallWindowPixelSize() {
        Display display = windowManager.getDefaultDisplay();
        Point screenSize = new Point();
        display.getSize(screenSize);

        float ratio = WINDOW_SIZE_RATIOS[getSmallWindowSize()];
        int width = (int) (screenSize.x * ratio);
        int height = (int) (width * 9f / 16f);

        return new Point(width, height);
    }

    // ================================================
    // ✅ 三、播放控制设置
    // ================================================

    public boolean isAutoMute() {
        return sp.getBoolean(KEY_PIP_AUTO_MUTE, false);
    }

    public void setAutoMute(boolean autoMute) {
        sp.edit().putBoolean(KEY_PIP_AUTO_MUTE, autoMute).apply();
        Log.d(TAG, "画中画自动静音：" + autoMute);
    }

    public boolean isKeepScreenOn() {
        return sp.getBoolean(KEY_PIP_KEEP_SCREEN_ON, true);
    }

    public void setKeepScreenOn(boolean keepOn) {
        sp.edit().putBoolean(KEY_PIP_KEEP_SCREEN_ON, keepOn).apply();
        Log.d(TAG, "画中画保持屏幕常亮：" + keepOn);
    }

    public boolean isHeadsetPause() {
        return sp.getBoolean(KEY_PIP_HEADSET_PAUSE, true);
    }

    public void setHeadsetPause(boolean pause) {
        sp.edit().putBoolean(KEY_PIP_HEADSET_PAUSE, pause).apply();
        Log.d(TAG, "耳机拔出自动暂停：" + pause);
    }

    public boolean isNotificationEnabled() {
        return sp.getBoolean(KEY_PIP_NOTIFICATION, true);
    }

    public void setNotificationEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_NOTIFICATION, enabled).apply();
        Log.d(TAG, "通知栏播放控制：" + enabled);
        if (!enabled) cancelNotification();
    }

    // 误触防护
    public boolean isTouchBlockEnabled() {
        return sp.getBoolean(KEY_PIP_TOUCH_BLOCK, false);
    }

    public void setTouchBlockEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_TOUCH_BLOCK, enabled).apply();
        Log.d(TAG, "误触防护：" + enabled);
    }

    public boolean isInTouchBlockArea(float x, float y) {
        if (!isTouchBlockEnabled()) return false;
        float blockSize = 20 * context.getResources().getDisplayMetrics().density;
        Display display = windowManager.getDefaultDisplay();
        Point screenSize = new Point();
        display.getSize(screenSize);
        return x < blockSize || x > screenSize.x - blockSize
                || y < blockSize || y > screenSize.y - blockSize;
    }

    // ================================================
    // ✅ 四、智能功能
    // ================================================

    public int getTimeoutMinutes() {
        return sp.getInt(KEY_PIP_TIMEOUT_MINUTES, DEFAULT_TIMEOUT_MINUTES);
    }

    public void setTimeoutMinutes(int minutes) {
        if (minutes < 0) minutes = 0;
        sp.edit().putInt(KEY_PIP_TIMEOUT_MINUTES, minutes).apply();
        Log.d(TAG, "画中画超时自动关闭：" + minutes + " 分钟");
    }

    public void resetTimeout() {
        if (!isTimeoutRunning) return;
        if (timeoutRunnable != null) handler.removeCallbacks(timeoutRunnable);
        startTimeoutTimer();
    }

    private void startTimeoutTimer() {
        int minutes = getTimeoutMinutes();
        if (minutes <= 0) {
            isTimeoutRunning = false;
            return;
        }
        if (timeoutRunnable == null) {
            timeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "画中画超时，准备自动关闭");
                    if (pipListener != null) {
                        boolean shouldContinue = pipListener.onPipTimeout();
                        if (shouldContinue) {
                            startTimeoutTimer();
                            return;
                        }
                    }
                    isTimeoutRunning = false;
                }
            };
        }
        handler.postDelayed(timeoutRunnable, minutes * 60 * 1000L);
        isTimeoutRunning = true;
    }

    private void stopTimeoutTimer() {
        if (timeoutRunnable != null) handler.removeCallbacks(timeoutRunnable);
        isTimeoutRunning = false;
    }

    public boolean isDataSaverEnabled() {
        return sp.getBoolean(KEY_PIP_DATA_SAVER, false);
    }

    public void setDataSaverEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_DATA_SAVER, enabled).apply();
        Log.d(TAG, "画中画省流模式：" + enabled);
    }

    public boolean isEpgRemindEnabled() {
        return sp.getBoolean(KEY_PIP_EPG_REMIND, true);
    }

    public void setEpgRemindEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_EPG_REMIND, enabled).apply();
        Log.d(TAG, "画中画 EPG 提醒：" + enabled);
    }

    // 省电模式
    public boolean isBatterySaverEnabled() {
        return sp.getBoolean(KEY_PIP_BATTERY_SAVER, false);
    }

    public void setBatterySaverEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_BATTERY_SAVER, enabled).apply();
        Log.d(TAG, "画中画省电模式：" + enabled);
    }

    public boolean shouldEnableBatterySaver() {
        if (!isBatterySaverEnabled()) return false;
        try {
            Intent batteryIntent = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra("level", -1);
                int scale = batteryIntent.getIntExtra("scale", -1);
                if (level > 0 && scale > 0) {
                    float batteryPct = level * 100 / (float) scale;
                    return batteryPct < 20;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取电量失败", e);
        }
        return false;
    }

    // 智能亮度调节
    public boolean isAutoBrightnessEnabled() {
        return sp.getBoolean(KEY_PIP_AUTO_BRIGHTNESS, false);
    }

    public void setAutoBrightnessEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_AUTO_BRIGHTNESS, enabled).apply();
        Log.d(TAG, "智能亮度调节：" + enabled);
    }

    public float getSuggestedBrightness() {
        if (!isAutoBrightnessEnabled()) return -1;
        return 0.7f;
    }

    // ================================================
    // ✅ 五、进入/退出画中画
    // ================================================

    public boolean enterPictureInPicture(Activity activity) {
        if (activity == null) {
            Log.e(TAG, "Activity 为空，无法进入画中画");
            return false;
        }
        if (!isPipSupported()) {
            Log.w(TAG, "设备不支持画中画");
            return false;
        }
        if (!isPipEnabled()) {
            Log.d(TAG, "画中画开关未开启");
            return false;
        }
        if (isMultiWindowMode) {
            exitMultiWindowMode();
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PictureInPictureParams params = buildPipParams();
                activity.enterPictureInPictureMode(params);
                Log.d(TAG, "成功进入画中画模式");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "进入画中画失败", e);
        }
        return false;
    }

    public boolean onActivityPaused(Activity activity) {
        if (isPipEnabled() && isAutoEnterPip() && !isInPipMode && !isMultiWindowMode) {
            return enterPictureInPicture(activity);
        }
        return false;
    }

    public boolean isInPictureInPictureMode(Activity activity) {
        if (activity == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return activity.isInPictureInPictureMode();
        }
        return false;
    }

    // ================================================
    // ✅ 六、双击检测
    // ================================================

    public boolean onPipClicked() {
        long now = System.currentTimeMillis();
        if (now - lastPipClickTime < DOUBLE_CLICK_INTERVAL) {
            lastPipClickTime = 0;
            Log.d(TAG, "检测到画中画双击，请求切换全屏");
            if (pipListener != null) {
                pipListener.onRequestFullscreen();
            }
            return true;
        } else {
            lastPipClickTime = now;
            return false;
        }
    }

    // ================================================
    // ✅ 七、频道信息更新
    // ================================================

    public void updateChannelInfo(int channelNumber, String channelName, String programName) {
        updateChannelInfo(channelNumber, channelName, programName, null);
    }

    public void updateChannelInfo(int channelNumber, String channelName, String programName, String url) {
        this.currentChannelNumber = channelNumber;
        this.currentChannelName = channelName != null ? channelName : "";
        this.currentProgramName = programName != null ? programName : "";
        this.currentUrl = url != null ? url : "";
        Log.d(TAG, "更新频道信息：" + channelNumber + " - " + channelName);

        if (isInPipMode && isNotificationEnabled()) {
            updateNotification();
        }
        resetTimeout();
    }
         // ================================================
    // ✅ 八、画中画模式变化处理
    // ================================================

    public void onPipModeChanged(Activity activity, boolean isInPipMode) {
        Log.d(TAG, "画中画模式变化：" + isInPipMode);
        this.isInPipMode = isInPipMode;

        if (isInPipMode) {
            pipEnterTime = System.currentTimeMillis();
            incrementUseCount();

            registerControlReceiver();
            registerHeadsetReceiver();
            registerScheduleReceiver();
            startTimeoutTimer();
            initMediaSession();

            if (isKeepScreenOn() && activity != null) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            if (isDataSaverEnabled() && pipListener != null) {
                Log.d(TAG, "进入画中画，开启省流模式");
                pipListener.onDataSaverModeChanged(true);
            }

            if (shouldEnableBatterySaver() && pipListener != null) {
                Log.d(TAG, "电量低，开启省电模式");
                pipListener.onBatterySaverModeChanged(true);
            }

            if (isNotificationEnabled()) {
                showNotification();
            }

        } else {
            long useTime = System.currentTimeMillis() - pipEnterTime;
            addTotalUseTime(useTime);

            unregisterControlReceiver();
            unregisterHeadsetReceiver();
            unregisterScheduleReceiver();
            stopTimeoutTimer();
            releaseMediaSession();

            if (isKeepScreenOn() && activity != null) {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            if (isDataSaverEnabled() && pipListener != null) {
                Log.d(TAG, "退出画中画，关闭省流模式");
                pipListener.onDataSaverModeChanged(false);
            }

            if (shouldEnableBatterySaver() && pipListener != null) {
                pipListener.onBatterySaverModeChanged(false);
            }

            cancelNotification();
        }

        if (pipListener != null) {
            pipListener.onPipModeChanged(isInPipMode);
        }
    }

    // ================================================
    // ✅ 九、播放状态更新
    // ================================================

    public void updatePlayState(Activity activity, boolean playing) {
        isPlaying = playing;
        if (activity != null && isInPictureInPictureMode(activity)) {
            updatePipParams(activity);
            updateMediaSessionState();
            updateNotification();
            resetTimeout();
        }
    }

    private void updatePipParams(Activity activity) {
        if (activity == null) return;
        if (!isPipSupported()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PictureInPictureParams params = buildPipParams();
                activity.setPictureInPictureParams(params);
            }
        } catch (Exception e) {
            Log.e(TAG, "更新画中画参数失败", e);
        }
    }

    // ================================================
    // ✅ 十、构建画中画参数
    // ================================================

    private PictureInPictureParams buildPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;

        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();

        int ratioIndex = getAspectRatio();
        float[] ratio = RATIO_VALUES[ratioIndex];
        Rational aspectRatio = new Rational((int) ratio[0], (int) ratio[1]);
        builder.setAspectRatio(aspectRatio);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true);
            builder.setSeamlessResizeEnabled(true);
        }

        if (isShowControls()) {
            List<RemoteAction> actions = buildControlActions();
            if (actions != null && !actions.isEmpty()) {
                builder.setActions(actions);
            }
        }

        return builder.build();
    }

    private List<RemoteAction> buildControlActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;

        List<RemoteAction> actions = new ArrayList<>();
        try {
            RemoteAction prevAction = createRemoteAction(
                    ACTION_PIP_PREV, "上一台", "上一个频道",
                    android.R.drawable.ic_media_previous
            );
            if (prevAction != null) actions.add(prevAction);

            int playPauseIcon = isPlaying
                    ? android.R.drawable.ic_media_pause
                    : android.R.drawable.ic_media_play;
            String playPauseTitle = isPlaying ? "暂停" : "播放";
            RemoteAction playPauseAction = createRemoteAction(
                    ACTION_PIP_PLAY_PAUSE, playPauseTitle, playPauseTitle + "播放",
                    playPauseIcon
            );
            if (playPauseAction != null) actions.add(playPauseAction);

            RemoteAction nextAction = createRemoteAction(
                    ACTION_PIP_NEXT, "下一台", "下一个频道",
                    android.R.drawable.ic_media_next
            );
            if (nextAction != null) actions.add(nextAction);

            if (isRecordEnabled()) {
                int recordIcon = isRecording
                        ? android.R.drawable.ic_menu_save
                        : android.R.drawable.ic_menu_camera;
                String recordTitle = isRecording ? "停止录制" : "开始录制";
                RemoteAction recordAction = createRemoteAction(
                        ACTION_PIP_RECORD, recordTitle, recordTitle,
                        recordIcon
                );
                if (recordAction != null) actions.add(recordAction);
            }

        } catch (Exception e) {
            Log.e(TAG, "构建控制按钮失败", e);
        }
        return actions;
    }

    private RemoteAction createRemoteAction(String action, String title,
                                            String contentDesc, int iconResId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            Intent intent = new Intent(action);
            intent.setPackage(context.getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, action.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            android.graphics.drawable.Icon icon = android.graphics.drawable.Icon.createWithResource(context, iconResId);
            return new RemoteAction(icon, title, contentDesc, pendingIntent);
        } catch (Exception e) {
            Log.e(TAG, "创建 RemoteAction 失败: " + action, e);
            return null;
        }
    }

    // ================================================
    // ✅ 十一、MediaSession 和通知栏
    // ================================================

    private void initMediaSession() {
        if (mediaSession != null) return;
        try {
            mediaSession = new MediaSessionCompat(context, "TVLivePip");
            mediaSession.setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            );
            mediaSession.setActive(true);
            updateMediaSessionState();
        } catch (Exception e) {
            Log.e(TAG, "初始化 MediaSession 失败", e);
        }
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) return;
        try {
            PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                    .setActions(
                            PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    );
            if (isPlaying) {
                stateBuilder.setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f);
            } else {
                stateBuilder.setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f);
            }
            mediaSession.setPlaybackState(stateBuilder.build());

            android.support.v4.media.MediaMetadataCompat.Builder metaBuilder =
                    new android.support.v4.media.MediaMetadataCompat.Builder();
            metaBuilder.putString(
                    android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE,
                    currentChannelName.isEmpty() ? "电视直播" : currentChannelName
            );
            metaBuilder.putString(
                    android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST,
                    currentProgramName.isEmpty() ? "正在播放" : currentProgramName
            );
            mediaSession.setMetadata(metaBuilder.build());
        } catch (Exception e) {
            Log.e(TAG, "更新 MediaSession 状态失败", e);
        }
    }

    private void releaseMediaSession() {
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;
            } catch (Exception e) {
                Log.e(TAG, "释放 MediaSession 失败", e);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "画中画播放控制",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("画中画模式下的播放控制通知");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void showNotification() {
        if (!isNotificationEnabled()) return;
        if (notificationManager == null) return;
        try {
            createNotificationChannel();
            Intent contentIntent = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            PendingIntent contentPendingIntent = PendingIntent.getActivity(
                    context, 0, contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(currentChannelName.isEmpty() ? "电视直播" : currentChannelName)
                    .setContentText(currentProgramName.isEmpty() ? "正在播放" : currentProgramName)
                    .setContentIntent(contentPendingIntent)
                    .setOngoing(isPlaying)
                    .setOnlyAlertOnce(true)
                                        .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

            builder.addAction(android.R.drawable.ic_media_previous, "上一台",
                    createBroadcastPendingIntent(ACTION_PIP_PREV));
            if (isPlaying) {
                builder.addAction(android.R.drawable.ic_media_pause, "暂停",
                        createBroadcastPendingIntent(ACTION_PIP_PLAY_PAUSE));
            } else {
                builder.addAction(android.R.drawable.ic_media_play, "播放",
                        createBroadcastPendingIntent(ACTION_PIP_PLAY_PAUSE));
            }
            builder.addAction(android.R.drawable.ic_media_next, "下一台",
                    createBroadcastPendingIntent(ACTION_PIP_NEXT));

            MediaStyle mediaStyle = new MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2);
            if (mediaSession != null) {
                mediaStyle.setMediaSession(mediaSession.getSessionToken());
            }
            builder.setStyle(mediaStyle);

            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "显示通知失败", e);
        }
    }

    private void updateNotification() {
        if (isInPipMode && isNotificationEnabled()) {
            showNotification();
        }
    }

    private void cancelNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    private PendingIntent createBroadcastPendingIntent(String action) {
        Intent intent = new Intent(action);
        intent.setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(
                context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    // ================================================
    // ✅ 十二、广播接收器
    // ================================================

    private void registerControlReceiver() {
        if (isControlReceiverRegistered) return;
        try {
            controlReceiver = new PipControlReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_PIP_PLAY_PAUSE);
            filter.addAction(ACTION_PIP_PREV);
            filter.addAction(ACTION_PIP_NEXT);
            filter.addAction(ACTION_PIP_CLOSE);
            filter.addAction(ACTION_PIP_FULLSCREEN);
            filter.addAction(ACTION_PIP_RECORD);
            filter.addAction(ACTION_PIP_SCHEDULE_RECORD);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(controlReceiver, filter);
            }
            isControlReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册控制广播失败", e);
        }
    }

    private void unregisterControlReceiver() {
        if (!isControlReceiverRegistered || controlReceiver == null) return;
        try {
            context.unregisterReceiver(controlReceiver);
            controlReceiver = null;
            isControlReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "注销控制广播失败", e);
        }
    }

    private class PipControlReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            Log.d(TAG, "收到画中画控制事件：" + action);
            resetTimeout();

            if (ACTION_PIP_RECORD.equals(action)) {
                if (isRecording) stopRecording();
                else startRecording();
                return;
            }

            if (pipListener == null) return;
            switch (action) {
                case ACTION_PIP_PLAY_PAUSE:
                    pipListener.onPipPlayPause();
                    break;
                case ACTION_PIP_PREV:
                    pipListener.onPipPrevChannel();
                    break;
                case ACTION_PIP_NEXT:
                    pipListener.onPipNextChannel();
                    break;
                case ACTION_PIP_FULLSCREEN:
                    pipListener.onRequestFullscreen();
                    break;
            }
        }
    }

    private void registerHeadsetReceiver() {
        if (!isHeadsetPause()) return;
        if (isHeadsetReceiverRegistered) return;
        try {
            headsetReceiver = new HeadsetReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_HEADSET_PLUG);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(headsetReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(headsetReceiver, filter);
            }
            isHeadsetReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册耳机监听失败", e);
        }
    }

    private void unregisterHeadsetReceiver() {
        if (!isHeadsetReceiverRegistered || headsetReceiver == null) return;
        try {
            context.unregisterReceiver(headsetReceiver);
            headsetReceiver = null;
            isHeadsetReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "注销耳机监听失败", e);
        }
    }

    private class HeadsetReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                int state = intent.getIntExtra("state", -1);
                if (state == 0 && isPlaying && pipListener != null) {
                    Log.d(TAG, "耳机拔出，自动暂停");
                    pipListener.onPipPlayPause();
                }
            }
        }
    }

    // ================================================
    // ✅ 十三、EPG 提醒
    // ================================================

    public void showEpgReminder(String programName, String startTime) {
        if (!isEpgRemindEnabled()) return;
        if (!isInPipMode && !isMultiWindowMode) return;
        Log.d(TAG, "EPG 提醒：" + programName + " - " + startTime);
    }

    public void setOnEpgJumpListener(OnEpgJumpListener listener) {
        this.epgJumpListener = listener;
    }

    public void jumpToProgram(String programId, String programName, long startTime) {
        Log.d(TAG, "跳转到节目：" + programName);
        if (epgJumpListener != null) {
            epgJumpListener.onJumpToProgram(programId, programName, startTime);
        }
    }

    // ================================================
    // 🆕 十四、录制功能（增强版）
    // ================================================

    public boolean isRecordEnabled() {
        return sp.getBoolean(KEY_PIP_RECORD_ENABLED, false);
    }

    public void setRecordEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_RECORD_ENABLED, enabled).apply();
        Log.d(TAG, "画中画录制功能：" + enabled);
        if (!enabled) {
            stopRecording();
            cancelAllSchedules();
        }
    }

    public int getRecordQuality() {
        return sp.getInt(KEY_PIP_RECORD_QUALITY, RECORD_QUALITY_MEDIUM);
    }

    public void setRecordQuality(int quality) {
        if (quality < 0 || quality >= RECORD_QUALITY_NAMES.length) quality = RECORD_QUALITY_MEDIUM;
        sp.edit().putInt(KEY_PIP_RECORD_QUALITY, quality).apply();
        Log.d(TAG, "录制质量：" + RECORD_QUALITY_NAMES[quality]);
    }

    public String getRecordQualityName() {
        return RECORD_QUALITY_NAMES[getRecordQuality()];
    }

    public String[] getAllRecordQualityNames() {
        return RECORD_QUALITY_NAMES.clone();
    }

    public boolean isRecordAudioEnabled() {
        return sp.getBoolean(KEY_PIP_RECORD_AUDIO, true);
    }

    public void setRecordAudioEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_RECORD_AUDIO, enabled).apply();
        Log.d(TAG, "录制声音：" + enabled);
    }

    public boolean isRecording() {
        return isRecording;
    }

    public int getRecordDuration() {
        return recordDurationSeconds;
    }

    public String getRecordFilePath() {
        return recordFilePath;
    }

    public boolean startRecording() {
        if (isRecording) {
            Log.w(TAG, "已经在录制中了");
            return false;
        }
        if (!isRecordEnabled()) {
            Log.w(TAG, "录制功能未开启");
            return false;
        }
        try {
            File recordDir = getRecordDirectory();
            if (recordDir == null) {
                if (recordListener != null) recordListener.onRecordError("创建录制目录失败");
                return false;
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String safeChannelName = currentChannelName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
            String fileName = "TVLive_" + safeChannelName + "_" + timeStamp + ".mp4";
            File recordFile = new File(recordDir, fileName);
            recordFilePath = recordFile.getAbsolutePath();

            int qualityIndex = getRecordQuality();
            int[] resolution = RECORD_RESOLUTIONS[qualityIndex];
            int width = resolution[0];
            int height = resolution[1];

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.DEFAULT);
            if (isRecordAudioEnabled()) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
            }
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoEncodingBitRate(width * height * 3);
            if (isRecordAudioEnabled()) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioEncodingBitRate(128000);
                mediaRecorder.setAudioSamplingRate(44100);
            }
            mediaRecorder.setOutputFile(recordFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            recordStartTime = System.currentTimeMillis();
            recordDurationSeconds = 0;
            startRecordTimeTimer();
            showRecordNotification();
            incrementRecordCount();

            Log.d(TAG, "开始录制：" + recordFilePath);
            Log.d(TAG, "录制分辨率：" + width + "×" + height);

            if (recordListener != null) recordListener.onRecordStarted(recordFilePath);
            if (pipListener != null) pipListener.onRecordStateChanged(true, 0);

            return true;

        } catch (IOException e) {
            Log.e(TAG, "开始录制失败", e);
            isRecording = false;
            if (recordListener != null) recordListener.onRecordError(e.getMessage());
            return false;
        }
    }

    public boolean stopRecording() {
        if (!isRecording || mediaRecorder == null) return false;

        try {
            mediaRecorder.stop();
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;

            int duration = recordDurationSeconds;
            String filePath = recordFilePath;

            isRecording = false;
            stopRecordTimeTimer();
            cancelRecordNotification();
            addToRecordHistory(filePath, duration);
            addRecordTotalTime(duration);

            Log.d(TAG, "停止录制：" + filePath);
            Log.d(TAG, "录制时长：" + duration + " 秒");

            if (recordListener != null) recordListener.onRecordStopped(filePath, duration);
            if (pipListener != null) pipListener.onRecordStateChanged(false, duration);

            return true;

        } catch (Exception e) {
            Log.e(TAG, "停止录制失败", e);
            isRecording = false;
            if (mediaRecorder != null) {
                try { mediaRecorder.reset(); mediaRecorder.release(); } catch (Exception ex) { /* ignore */ }
                mediaRecorder = null;
            }
            return false;
        }
    }

    private File getRecordDirectory() {
        try {
            File moviesDir = new File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_MOVIES
                    ), "TVLive"
            );
            if (!moviesDir.exists()) {
                if (!moviesDir.mkdirs()) {
                    moviesDir = new File(context.getExternalFilesDir(null), "Records");
                    if (!moviesDir.exists()) moviesDir.mkdirs();
                }
            }
            return moviesDir;
        } catch (Exception e) {
            Log.e(TAG, "获取录制目录失败", e);
            return null;
        }
    }

    private void startRecordTimeTimer() {
        if (recordTimeRunnable == null) {
            recordTimeRunnable = new Runnable() {
                @Override
                public void run() {
                    recordDurationSeconds++;
                    if (recordListener != null) {
                        recordListener.onRecordTimeUpdate(recordDurationSeconds);
                    }
                    updateRecordNotification();
                    handler.postDelayed(this, 1000);
                }
            };
        }
        handler.postDelayed(recordTimeRunnable, 1000);
    }

    private void stopRecordTimeTimer() {
        if (recordTimeRunnable != null) {
            handler.removeCallbacks(recordTimeRunnable);
            recordTimeRunnable = null;
        }
    }

    private void showRecordNotification() {
        if (notificationManager == null) return;
        try {
            createRecordNotificationChannel();
            Intent stopIntent = new Intent(ACTION_PIP_RECORD);
            stopIntent.setPackage(context.getPackageName());
            PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
                    context, ACTION_PIP_RECORD.hashCode(), stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, RECORD_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("正在录制")
                    .setContentText(currentChannelName + "  00:00")
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止录制", stopPendingIntent);
            notificationManager.notify(RECORD_NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "显示录制通知失败", e);
        }
    }

    private void updateRecordNotification() {
        if (notificationManager == null || !isRecording) return;
        try {
            int minutes = recordDurationSeconds / 60;
            int seconds = recordDurationSeconds % 60;
            String timeStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
            Intent stopIntent = new Intent(ACTION_PIP_RECORD);
            stopIntent.setPackage(context.getPackageName());
            PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
                    context, ACTION_PIP_RECORD.hashCode(), stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, RECORD_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("正在录制")
                    .setContentText(currentChannelName + "  " + timeStr)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止录制", stopPendingIntent);
            notificationManager.notify(RECORD_NOTIFICATION_ID, builder.build());
        } catch (Exception e) { /* ignore */ }
    }

    private void cancelRecordNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(RECORD_NOTIFICATION_ID);
        }
    }

    private void createRecordNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(RECORD_CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    RECORD_CHANNEL_ID, "录制通知",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("电视直播录制通知");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void setOnRecordStateListener(OnRecordStateListener listener) {
        this.recordListener = listener;
    }

    // ================================================
    // 🆕 十五、录制历史管理
    // ================================================

    private void addToRecordHistory(String filePath, int duration) {
        File file = new File(filePath);
        long fileSize = file.exists() ? file.length() : 0;

        RecordHistoryItem item = new RecordHistoryItem(
                filePath, currentChannelName, currentProgramName,
                recordStartTime, duration, fileSize, getRecordQuality()
        );
        recordHistory.add(0, item);

        if (recordHistory.size() > 100) {
            recordHistory = recordHistory.subList(0, 100);
        }
        saveRecordHistory();
    }

    public List<RecordHistoryItem> getRecordHistory() {
        return new ArrayList<>(recordHistory);
    }

    public boolean deleteRecordHistory(int index) {
        if (index < 0 || index >= recordHistory.size()) return false;
        RecordHistoryItem item = recordHistory.get(index);
        try {
            File file = new File(item.filePath);
            if (file.exists()) file.delete();
        } catch (Exception e) {
            Log.e(TAG, "删除录制文件失败", e);
        }
        recordHistory.remove(index);
        saveRecordHistory();
        Log.d(TAG, "删除录制历史：" + item.channelName);
        return true;
    }

    public void clearRecordHistory(boolean deleteFiles) {
        if (deleteFiles) {
            for (RecordHistoryItem item : recordHistory) {
                try {
                    File file = new File(item.filePath);
                    if (file.exists()) file.delete();
                } catch (Exception e) { /* ignore */ }
            }
        }
        recordHistory.clear();
        saveRecordHistory();
        Log.d(TAG, "清空录制历史");
    }

    private void saveRecordHistory() {
        try {
            StringBuilder sb = new StringBuilder();
            for (RecordHistoryItem item : recordHistory) {
                sb.append(item.filePath).append("|||");
                sb.append(item.channelName).append("|||");
                sb.append(item.programName).append("|||");
                sb.append(item.startTime).append("|||");
                sb.append(item.durationSeconds).append("|||");
                sb.append(item.fileSize).append("|||");
                sb.append(item.quality).append(";;;");
            }
            sp.edit().putString("record_history", sb.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "保存录制历史失败", e);
        }
    }

    private void loadRecordHistory() {
        try {
            String data = sp.getString("record_history", "");
            if (TextUtils.isEmpty(data)) return;
            String[] items = data.split(";;;");
            for (String itemStr : items) {
                if (TextUtils.isEmpty(itemStr)) continue;
                String[] fields = itemStr.split("\\|\\|\\|");
                if (fields.length >= 7) {
                    RecordHistoryItem item = new RecordHistoryItem(
                            fields[0], fields[1], fields[2],
                            Long.parseLong(fields[3]),
                            Integer.parseInt(fields[4]),
                            Long.parseLong(fields[5]),
                            Integer.parseInt(fields[6])
                    );
                    recordHistory.add(item);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载录制历史失败", e);
        }
    }

    public long getRecordHistoryTotalSize() {
        long total = 0;
        for (RecordHistoryItem item : recordHistory) {
            total += item.fileSize;
        }
        return total;
    }

    public String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", size / 1024f);
        if (size < 1024 * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", size / (1024f * 1024));
        return String.format(Locale.getDefault(), "%.2f GB", size / (1024f * 1024 * 1024));
    }

    // ================================================
    // 🆕 十六、定时录制
    // ================================================

    public int addScheduleRecord(String channelName, String url,
                                  long startTime, long durationMinutes, String programName) {
        int id = (int) (System.currentTimeMillis() % 100000);
        ScheduleRecordInfo info = new ScheduleRecordInfo(id, channelName, url,
                startTime, durationMinutes, programName);
        scheduleList.add(info);
        saveScheduleList();
        setScheduleAlarm(info);
        Log.d(TAG, "添加定时录制：" + channelName + " - " + programName);
        return id;
    }

    public boolean cancelScheduleRecord(int id) {
        for (int i = 0; i < scheduleList.size(); i++) {
            if (scheduleList.get(i).id == id) {
                ScheduleRecordInfo info = scheduleList.get(i);
                cancelScheduleAlarm(info);
                scheduleList.remove(i);
                saveScheduleList();
                Log.d(TAG, "取消定时录制：" + info.channelName);
                return true;
            }
        }
        return false;
    }

    public void cancelAllSchedules() {
        for (ScheduleRecordInfo info : scheduleList) {
            cancelScheduleAlarm(info);
        }
        scheduleList.clear();
        saveScheduleList();
        Log.d(TAG, "取消所有定时录制");
    }

    public List<ScheduleRecordInfo> getScheduleList() {
        List<ScheduleRecordInfo> result = new ArrayList<>();
        for (ScheduleRecordInfo info : scheduleList) {
            if (info.isEnabled && info.startTime > System.currentTimeMillis()) {
                result.add(info);
            }
        }
        Collections.sort(result, new Comparator<ScheduleRecordInfo>() {
            @Override
            public int compare(ScheduleRecordInfo o1, ScheduleRecordInfo o2) {
                return Long.compare(o1.startTime, o2.startTime);
            }
        });
        return result;
    }

    private void setScheduleAlarm(ScheduleRecordInfo info) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) return;
            Intent intent = new Intent(ACTION_PIP_SCHEDULE_RECORD);
            intent.setPackage(context.getPackageName());
            intent.putExtra("schedule_id", info.id);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, info.id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, info.startTime, pendingIntent
                );
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, info.startTime, pendingIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置定时闹钟失败", e);
        }
    }

    private void cancelScheduleAlarm(ScheduleRecordInfo info) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) return;
            Intent intent = new Intent(ACTION_PIP_SCHEDULE_RECORD);
            intent.setPackage(context.getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, info.id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            alarmManager.cancel(pendingIntent);
        } catch (Exception e) {
            Log.e(TAG, "取消定时闹钟失败", e);
        }
    }

    private void registerScheduleReceiver() {
        if (isScheduleReceiverRegistered) return;
        try {
            scheduleReceiver = new ScheduleReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_PIP_SCHEDULE_RECORD);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(scheduleReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(scheduleReceiver, filter);
            }
            isScheduleReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册定时广播失败", e);
        }
    }

    private void unregisterScheduleReceiver() {
        if (!isScheduleReceiverRegistered || scheduleReceiver == null) return;
        try {
            context.unregisterReceiver(scheduleReceiver);
            scheduleReceiver = null;
            isScheduleReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "注销定时广播失败", e);
        }
    }

    private class ScheduleReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_PIP_SCHEDULE_RECORD.equals(intent.getAction())) return;
            int scheduleId = intent.getIntExtra("schedule_id", -1);
            Log.d(TAG, "定时录制触发：" + scheduleId);
            ScheduleRecordInfo targetInfo = null;
            for (ScheduleRecordInfo info : scheduleList) {
                if (info.id == scheduleId) {
                    targetInfo = info;
                    break;
                }
            }
            if (targetInfo == null) return;
            showScheduleNotification(targetInfo);
            if (pipListener != null) {
                pipListener.onScheduleRecordStart(targetInfo);
            }
            targetInfo.isEnabled = false;
            saveScheduleList();
        }
    }

    private void showScheduleNotification(ScheduleRecordInfo info) {
        if (notificationManager == null) return;
        try {
            createScheduleNotificationChannel();
            Intent contentIntent = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            PendingIntent contentPendingIntent = PendingIntent.getActivity(
                    context, info.id, contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, SCHEDULE_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("开始录制：" + info.channelName)
                    .setContentText(info.programName)
                    .setContentIntent(contentPendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);
            notificationManager.notify(SCHEDULE_NOTIFICATION_ID + info.id, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "显示定时通知失败", e);
        }
    }

    private void createScheduleNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(SCHEDULE_CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    SCHEDULE_CHANNEL_ID, "定时录制",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("定时录制提醒");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void saveScheduleList() {
        try {
            StringBuilder sb = new StringBuilder();
            for (ScheduleRecordInfo info : scheduleList) {
                sb.append(info.id).append("|||");
                sb.append(info.channelName).append("|||");
                sb.append(info.url).append("|||");
                sb.append(info.startTime).append("|||");
                sb.append(info.durationMinutes).append("|||");
                sb.append(info.programName).append("|||");
                sb.append(info.isEnabled ? 1 : 0).append(";;;");
            }
            sp.edit().putString("schedule_list", sb.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "保存定时列表失败", e);
        }
    }

    private void loadScheduleList() {
        try {
            String data = sp.getString("schedule_list", "");
            if (TextUtils.isEmpty(data)) return;
            String[] items = data.split(";;;");
            for (String itemStr : items) {
                if (TextUtils.isEmpty(itemStr)) continue;
                String[] fields = itemStr.split("\\|\\|\\|");
                if (fields.length >= 7) {
                    ScheduleRecordInfo info = new ScheduleRecordInfo(
                            Integer.parseInt(fields[0]),
                            fields[1], fields[2],
                            Long.parseLong(fields[3]),
                            Long.parseLong(fields[4]),
                            fields[5]
                    );
                    info.isEnabled = "1".equals(fields[6]);
                    scheduleList.add(info);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载定时列表失败", e);
        }
    }
            // ================================================
    // 🆕 十七、多画中画（应用内多窗口）增强版
    // ================================================

    public boolean isMultiWindowEnabled() {
        return sp.getBoolean(KEY_PIP_MULTI_WINDOW, false);
    }

    public void setMultiWindowEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_MULTI_WINDOW, enabled).apply();
        Log.d(TAG, "多画中画模式：" + enabled);
        if (!enabled) exitMultiWindowMode();
    }

    public boolean isInMultiWindowMode() {
        return isMultiWindowMode;
    }

    public int getMultiWindowLayout() {
        return sp.getInt(KEY_PIP_MULTI_LAYOUT, MULTI_LAYOUT_FLOAT);
    }

    public void setMultiWindowLayout(int layout) {
        if (layout < 0 || layout >= MULTI_LAYOUT_NAMES.length) layout = MULTI_LAYOUT_FLOAT;
        sp.edit().putInt(KEY_PIP_MULTI_LAYOUT, layout).apply();
        Log.d(TAG, "多窗口布局：" + MULTI_LAYOUT_NAMES[layout]);
    }

    public String getMultiWindowLayoutName() {
        return MULTI_LAYOUT_NAMES[getMultiWindowLayout()];
    }

    public String[] getAllMultiLayoutNames() {
        return MULTI_LAYOUT_NAMES.clone();
    }

    public boolean enterMultiWindowMode(Activity activity) {
        if (activity == null) return false;
        if (!isMultiWindowEnabled()) {
            Log.w(TAG, "多窗口功能未开启");
            return false;
        }
        if (isMultiWindowMode) return false;
        if (isInPipMode) {
            Log.w(TAG, "请先退出系统画中画模式");
            return false;
        }
        try {
            isMultiWindowMode = true;
            mainWindowIndex = 0;
            Log.d(TAG, "进入多窗口模式");
            if (multiWindowListener != null) {
                multiWindowListener.onMultiWindowModeChanged(true);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "进入多窗口模式失败", e);
            isMultiWindowMode = false;
            return false;
        }
    }

    public boolean exitMultiWindowMode() {
        if (!isMultiWindowMode) return false;
        try {
            isMultiWindowMode = false;
            multiWindowList.clear();
            mainWindowIndex = 0;
            Log.d(TAG, "退出多窗口模式");
            if (multiWindowListener != null) {
                multiWindowListener.onMultiWindowModeChanged(false);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "退出多窗口模式失败", e);
            return false;
        }
    }

    public void closeAllSmallWindows() {
        if (!isMultiWindowMode) return;
        if (mainWindowIndex >= 0 && mainWindowIndex < multiWindowList.size()) {
            MultiWindowInfo mainInfo = multiWindowList.get(mainWindowIndex);
            multiWindowList.clear();
            multiWindowList.add(mainInfo);
            mainWindowIndex = 0;
        } else {
            multiWindowList.clear();
        }
        Log.d(TAG, "一键关闭所有小窗，剩余：" + multiWindowList.size());
    }

    public int addSmallWindow(int channelNumber, String channelName, String url) {
        if (!isMultiWindowMode) return -1;
        MultiWindowInfo info = new MultiWindowInfo(channelNumber, channelName, url);
        multiWindowList.add(info);
        int index = multiWindowList.size() - 1;
        Log.d(TAG, "添加小窗口：" + index + " - " + channelName);
        if (multiWindowListener != null) {
            multiWindowListener.onWindowAdded(index, info);
        }
        return index;
    }

    public boolean removeSmallWindow(int index) {
        if (!isMultiWindowMode) return false;
        if (index < 0 || index >= multiWindowList.size()) return false;
        MultiWindowInfo removed = multiWindowList.remove(index);
        Log.d(TAG, "移除小窗口：" + index + " - " + removed.channelName);
        if (index < mainWindowIndex) mainWindowIndex--;
        else if (index == mainWindowIndex) mainWindowIndex = 0;
        if (multiWindowListener != null) {
            multiWindowListener.onWindowRemoved(index);
        }
        return true;
    }

    public boolean switchMainWindow(int index) {
        if (!isMultiWindowMode) return false;
        if (index < 0 || index >= multiWindowList.size()) return false;
        if (index == mainWindowIndex) return true;
        mainWindowIndex = index;
        Log.d(TAG, "切换主窗口到：" + index + " - " + multiWindowList.get(index).channelName);
        if (multiWindowListener != null) {
            multiWindowListener.onWindowSwitched(mainWindowIndex);
        }
        return true;
    }

    public MultiWindowInfo getMainWindowInfo() {
        if (mainWindowIndex >= 0 && mainWindowIndex < multiWindowList.size()) {
            return multiWindowList.get(mainWindowIndex);
        }
        return null;
    }

    public List<MultiWindowInfo> getAllWindows() {
        return new ArrayList<>(multiWindowList);
    }

    public int getWindowCount() {
        return multiWindowList.size();
    }

    public int getMainWindowIndex() {
        return mainWindowIndex;
    }

    public void updateSmallWindowPosition(int index, int x, int y, int width, int height) {
        if (index < 0 || index >= multiWindowList.size()) return;
        MultiWindowInfo info = multiWindowList.get(index);
        if (info.position == null) info.position = new Rect();
        info.position.set(x, y, x + width, y + height);
    }

    public Point getDefaultSmallWindowPosition(int windowWidth, int windowHeight) {
        Point point = new Point();
        Display display = windowManager.getDefaultDisplay();
        Point screenSize = new Point();
        display.getSize(screenSize);
        int gravity = getDefaultGravity();
        int margin = 20;
        switch (gravity) {
            case GRAVITY_BOTTOM_RIGHT:
                point.x = screenSize.x - windowWidth - margin;
                point.y = screenSize.y - windowHeight - margin - 100;
                break;
            case GRAVITY_BOTTOM_LEFT:
                point.x = margin;
                point.y = screenSize.y - windowHeight - margin - 100;
                break;
            case GRAVITY_TOP_RIGHT:
                point.x = screenSize.x - windowWidth - margin;
                point.y = margin + 100;
                break;
            case GRAVITY_TOP_LEFT:
            default:
                point.x = margin;
                point.y = margin + 100;
                break;
        }
        return point;
    }

    public void setOnMultiWindowListener(OnMultiWindowListener listener) {
        this.multiWindowListener = listener;
    }

    // ================================================
    // 🆕 十八、手势操作辅助
    // ================================================

    public int handleTouchEvent(MotionEvent event) {
        if (!isInPipMode && !isMultiWindowMode) return 0;
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (pointerCount == 1) {
                    gestureStartX = event.getX();
                    gestureStartY = event.getY();
                    isGestureStarted = true;
                    gesturePointerCount = 1;
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerCount == 2) {
                    gesturePointerCount = 2;
                    gestureStartDistance = getFingerDistance(event);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (!isGestureStarted) break;
                if (gesturePointerCount == 1 && pointerCount == 1) {
                    float dx = event.getX() - gestureStartX;
                    float dy = event.getY() - gestureStartY;
                    if (Math.abs(dx) > SWIPE_THRESHOLD && Math.abs(dx) > Math.abs(dy)) {
                        isGestureStarted = false;
                        return dx > 0 ? 2 : 1;
                    } else if (Math.abs(dy) > SWIPE_THRESHOLD && Math.abs(dy) > Math.abs(dx)) {
                        isGestureStarted = false;
                        return dy > 0 ? 4 : 3;
                    }
                } else if (gesturePointerCount == 2 && pointerCount == 2) {
                    float currentDistance = getFingerDistance(event);
                    float delta = currentDistance - gestureStartDistance;
                    if (Math.abs(delta) > PINCH_THRESHOLD) {
                        gestureStartDistance = currentDistance;
                        return delta > 0 ? 5 : 6;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isGestureStarted = false;
                gesturePointerCount = 0;
                break;
        }
        return 0;
    }

    private float getFingerDistance(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public String getGestureDesc(int gestureType) {
        switch (gestureType) {
            case 1: return "左滑";
            case 2: return "右滑";
            case 3: return "上滑";
            case 4: return "下滑";
            case 5: return "双指放大";
            case 6: return "双指缩小";
            default: return "无手势";
        }
    }

    // ================================================
    // 🆕 十九、自动旋转
    // ================================================

    public boolean isAutoRotateEnabled() {
        return sp.getBoolean(KEY_PIP_AUTO_ROTATE, false);
    }

    public void setAutoRotateEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_AUTO_ROTATE, enabled).apply();
        Log.d(TAG, "画中画自动旋转：" + enabled);
    }

    public void registerOrientationListener(android.view.OrientationEventListener listener) {
        if (!isAutoRotateEnabled()) return;
        if (isAutoRotateRegistered) return;
        try {
            if (listener != null) {
                listener.enable();
                isAutoRotateRegistered = true;
                Log.d(TAG, "方向监听已注册");
            }
        } catch (Exception e) {
            Log.e(TAG, "注册方向监听失败", e);
        }
    }

    public void unregisterOrientationListener(android.view.OrientationEventListener listener) {
        if (!isAutoRotateRegistered) return;
        try {
            if (listener != null) listener.disable();
            isAutoRotateRegistered = false;
            Log.d(TAG, "方向监听已注销");
        } catch (Exception e) {
            Log.e(TAG, "注销方向监听失败", e);
        }
    }

    public int getSuggestedAspectRatio(int degrees) {
        if (degrees == 90 || degrees == 270) {
            return RATIO_16_9;
        } else {
            return RATIO_1_1;
        }
    }

    // ================================================
    // 🆕 二十、桌面快捷方式
    // ================================================

    public boolean createPipShortcut(int channelNumber, String channelName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            Log.w(TAG, "系统版本不支持快捷方式");
            return false;
        }
        try {
            android.content.pm.ShortcutManager shortcutManager =
                    context.getSystemService(android.content.pm.ShortcutManager.class);
            if (shortcutManager == null) return false;
            Intent intent = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            if (intent == null) return false;
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("auto_pip", true);
            intent.putExtra("channel_number", channelNumber);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(context, SHORTCUT_ID_PIP)
                    .setShortLabel("画中画")
                    .setLongLabel("画中画：" + channelName)
                    .setIcon(android.graphics.drawable.Icon.createWithResource(
                            context, android.R.drawable.ic_media_play))
                    .setIntent(intent)
                    .build();
            shortcutManager.addDynamicShortcuts(Collections.singletonList(shortcut));
            Log.d(TAG, "创建画中画快捷方式成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "创建快捷方式失败", e);
            return false;
        }
    }

    public boolean createRecordShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false;
        try {
            android.content.pm.ShortcutManager shortcutManager =
                    context.getSystemService(android.content.pm.ShortcutManager.class);
            if (shortcutManager == null) return false;
            Intent intent = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            if (intent == null) return false;
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("auto_record", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(context, SHORTCUT_ID_RECORD)
                    .setShortLabel("一键录制")
                    .setLongLabel("一键开始录制")
                    .setIcon(android.graphics.drawable.Icon.createWithResource(
                            context, android.R.drawable.ic_menu_camera))
                    .setIntent(intent)
                    .build();
            shortcutManager.addDynamicShortcuts(Collections.singletonList(shortcut));
            Log.d(TAG, "创建录制快捷方式成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "创建录制快捷方式失败", e);
            return false;
        }
    }

    public void removeAllShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;
        try {
            android.content.pm.ShortcutManager shortcutManager =
                    context.getSystemService(android.content.pm.ShortcutManager.class);
            if (shortcutManager != null) {
                shortcutManager.removeAllDynamicShortcuts();
                Log.d(TAG, "已移除所有快捷方式");
            }
        } catch (Exception e) {
            Log.e(TAG, "移除快捷方式失败", e);
        }
    }

    public boolean isFromShortcut(Intent intent) {
        if (intent == null) return false;
        return intent.getBooleanExtra("auto_pip", false)
                || intent.getBooleanExtra("auto_record", false);
    }

    public int handleShortcutIntent(Intent intent) {
        if (intent == null) return 0;
        if (intent.getBooleanExtra("auto_pip", false)) {
            Log.d(TAG, "来自画中画快捷方式");
            return 1;
        }
        if (intent.getBooleanExtra("auto_record", false)) {
            Log.d(TAG, "来自录制快捷方式");
            return 2;
        }
        return 0;
    }

    // ================================================
    // 🆕 二十一、夜间模式
    // ================================================

    public boolean isNightModeEnabled() {
        return sp.getBoolean(KEY_PIP_NIGHT_MODE, false);
    }

    public void setNightModeEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_NIGHT_MODE, enabled).apply();
        Log.d(TAG, "夜间模式：" + enabled);
    }

    public int getNightStartHour() {
        return sp.getInt(KEY_PIP_NIGHT_START, DEFAULT_NIGHT_START_HOUR);
    }

    public void setNightStartHour(int hour) {
        sp.edit().putInt(KEY_PIP_NIGHT_START, hour).apply();
        Log.d(TAG, "夜间开始时间：" + hour + ":00");
    }

    public int getNightEndHour() {
        return sp.getInt(KEY_PIP_NIGHT_END, DEFAULT_NIGHT_END_HOUR);
    }

    public void setNightEndHour(int hour) {
        sp.edit().putInt(KEY_PIP_NIGHT_END, hour).apply();
        Log.d(TAG, "夜间结束时间：" + hour + ":00");
    }

    public boolean isNightTime() {
        if (!isNightModeEnabled()) return false;
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        int startHour = getNightStartHour();
        int endHour = getNightEndHour();
        if (startHour > endHour) {
            return currentHour >= startHour || currentHour < endHour;
        } else {
            return currentHour >= startHour && currentHour < endHour;
        }
    }

    public float getNightSuggestedBrightness() {
        if (!isNightTime()) return -1;
        return 0.3f;
    }

    public float getNightSuggestedVolume() {
        if (!isNightTime()) return -1;
        return 0.5f;
    }

    public String getNightModeDesc() {
        if (!isNightModeEnabled()) return "未开启";
        return getNightStartHour() + ":00 ~ " + getNightEndHour() + ":00";
    }

    // ================================================
    // 🆕 二十二、网络优化
    // ================================================

    public boolean isLowPriorityNetworkEnabled() {
        return sp.getBoolean(KEY_PIP_LOW_PRIORITY_NETWORK, false);
    }

    public void setLowPriorityNetworkEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_LOW_PRIORITY_NETWORK, enabled).apply();
        Log.d(TAG, "低优先级网络：" + enabled);
    }

    public int getSuggestedBufferMs() {
        if (!isDataSaverEnabled() && !isLowPriorityNetworkEnabled()) return -1;
        return 10000;
    }

    public int getSuggestedMaxHeight() {
        if (!isDataSaverEnabled()) return -1;
        return 480;
    }

    public String getNetworkOptimizationDesc() {
        StringBuilder sb = new StringBuilder();
        if (isDataSaverEnabled()) sb.append("省流模式 ");
        if (isLowPriorityNetworkEnabled()) sb.append("低优先级网络 ");
        if (sb.length() == 0) sb.append("未开启");
        return sb.toString();
    }
        // ================================================
    // 🆕 二十三、游戏模式
    // ================================================

    public boolean isGameModeEnabled() {
        return sp.getBoolean(KEY_PIP_GAME_MODE, false);
    }

    public void setGameModeEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_GAME_MODE, enabled).apply();
        Log.d(TAG, "游戏模式：" + enabled);
    }

    public int getGameLatencyLevel() {
        return sp.getInt(KEY_PIP_GAME_LATENCY, LATENCY_NORMAL);
    }

    public void setGameLatencyLevel(int level) {
        if (level < 0 || level >= LATENCY_NAMES.length) level = LATENCY_NORMAL;
        sp.edit().putInt(KEY_PIP_GAME_LATENCY, level).apply();
        Log.d(TAG, "游戏延迟：" + LATENCY_NAMES[level]);
    }

    public String getGameLatencyName() {
        return LATENCY_NAMES[getGameLatencyLevel()];
    }

    public String[] getAllLatencyNames() {
        return LATENCY_NAMES.clone();
    }

    public int getGameModeBufferMs() {
        if (!isGameModeEnabled()) return -1;
        switch (getGameLatencyLevel()) {
            case LATENCY_LOW: return 500;
            case LATENCY_HIGH: return 3000;
            default: return 1500;
        }
    }

    public int getGameModeFrameRate() {
        if (!isGameModeEnabled()) return -1;
        return 60;
    }

    public String getGameModeDesc() {
        if (!isGameModeEnabled()) return "未开启";
        return "已开启（" + getGameLatencyName() + "）";
    }

    // ================================================
    // 🆕 二十四、多音频模式
    // ================================================

    public boolean isMultiAudioEnabled() {
        return sp.getBoolean(KEY_PIP_MULTI_AUDIO, false);
    }

    public void setMultiAudioEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_MULTI_AUDIO, enabled).apply();
        Log.d(TAG, "多音频模式：" + enabled);
        if (!enabled) isMultiAudioMode = false;
    }

    public float getMainVolume() {
        return sp.getFloat(KEY_PIP_MAIN_VOLUME, 1.0f);
    }

    public void setMainVolume(float volume) {
        volume = Math.max(0, Math.min(1, volume));
        sp.edit().putFloat(KEY_PIP_MAIN_VOLUME, volume).apply();
        Log.d(TAG, "主音量：" + (int) (volume * 100) + "%");
    }

    public float getSecondVolume() {
        return sp.getFloat(KEY_PIP_SECOND_VOLUME, 0.3f);
    }

    public void setSecondVolume(float volume) {
        volume = Math.max(0, Math.min(1, volume));
        sp.edit().putFloat(KEY_PIP_SECOND_VOLUME, volume).apply();
        Log.d(TAG, "副音量：" + (int) (volume * 100) + "%");
    }

    public boolean enterMultiAudioMode() {
        if (!isMultiAudioEnabled()) {
            Log.w(TAG, "多音频模式未开启");
            return false;
        }
        if (isMultiAudioMode) return false;
        isMultiAudioMode = true;
        Log.d(TAG, "进入多音频模式");
        return true;
    }

    public boolean exitMultiAudioMode() {
        if (!isMultiAudioMode) return false;
        isMultiAudioMode = false;
        Log.d(TAG, "退出多音频模式");
        return true;
    }

    public boolean isInMultiAudioMode() {
        return isMultiAudioMode;
    }

    public String getMultiAudioDesc() {
        if (!isMultiAudioEnabled()) return "未开启";
        return "主 " + (int) (getMainVolume() * 100) + "% / 副 " + (int) (getSecondVolume() * 100) + "%";
    }

    // ================================================
    // 🆕 二十五、弹幕功能
    // ================================================

    public boolean isDanmakuEnabled() {
        return sp.getBoolean(KEY_PIP_DANMAKU, false);
    }

    public void setDanmakuEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_DANMAKU, enabled).apply();
        Log.d(TAG, "画中画弹幕：" + enabled);
        if (!enabled) hideDanmaku();
    }

    public int getDanmakuSize() {
        return sp.getInt(KEY_PIP_DANMAKU_SIZE, DANMAKU_SIZE_MEDIUM);
    }

    public void setDanmakuSize(int size) {
        if (size < 0 || size >= DANMAKU_SIZE_NAMES.length) size = DANMAKU_SIZE_MEDIUM;
        sp.edit().putInt(KEY_PIP_DANMAKU_SIZE, size).apply();
        Log.d(TAG, "弹幕大小：" + DANMAKU_SIZE_NAMES[size]);
    }

    public String getDanmakuSizeName() {
        return DANMAKU_SIZE_NAMES[getDanmakuSize()];
    }

    public int getDanmakuSpeed() {
        return sp.getInt(KEY_PIP_DANMAKU_SPEED, DANMAKU_SPEED_NORMAL);
    }

    public void setDanmakuSpeed(int speed) {
        if (speed < 0 || speed >= DANMAKU_SPEED_NAMES.length) speed = DANMAKU_SPEED_NORMAL;
        sp.edit().putInt(KEY_PIP_DANMAKU_SPEED, speed).apply();
        Log.d(TAG, "弹幕速度：" + DANMAKU_SPEED_NAMES[speed]);
    }

    public String getDanmakuSpeedName() {
        return DANMAKU_SPEED_NAMES[getDanmakuSpeed()];
    }

    public int getDanmakuOpacity() {
        return sp.getInt(KEY_PIP_DANMAKU_OPACITY, 80);
    }

    public void setDanmakuOpacity(int opacity) {
        opacity = Math.max(10, Math.min(100, opacity));
        sp.edit().putInt(KEY_PIP_DANMAKU_OPACITY, opacity).apply();
        Log.d(TAG, "弹幕透明度：" + opacity + "%");
    }

    public boolean showDanmaku() {
        if (!isDanmakuEnabled()) {
            Log.w(TAG, "弹幕未开启");
            return false;
        }
        if (isDanmakuShowing) return true;
        isDanmakuShowing = true;
        Log.d(TAG, "显示弹幕");
        return true;
    }

    public boolean hideDanmaku() {
        if (!isDanmakuShowing) return false;
        isDanmakuShowing = false;
        Log.d(TAG, "隐藏弹幕");
        return true;
    }

    public boolean toggleDanmaku() {
        if (isDanmakuShowing) {
            hideDanmaku();
            return false;
        } else {
            showDanmaku();
            return true;
        }
    }

    public boolean isDanmakuShowing() {
        return isDanmakuShowing;
    }

    public void sendDanmaku(String text, int color, boolean isSelf) {
        if (!isDanmakuShowing) return;
        Log.d(TAG, "发送弹幕：" + text + "（" + (isSelf ? "自己" : "他人") + "）");
    }

    public String getDanmakuDesc() {
        if (!isDanmakuEnabled()) return "未开启";
        return getDanmakuSizeName() + " / " + getDanmakuSpeedName() + " / " + getDanmakuOpacity() + "%";
    }

    // ================================================
    // 🆕 二十六、滤镜效果
    // ================================================

    public int getFilter() {
        return sp.getInt(KEY_PIP_FILTER, FILTER_NONE);
    }

    public void setFilter(int filter) {
        if (filter < 0 || filter >= FILTER_NAMES.length) filter = FILTER_NONE;
        sp.edit().putInt(KEY_PIP_FILTER, filter).apply();
        currentFilter = filter;
        Log.d(TAG, "滤镜：" + FILTER_NAMES[filter]);
    }

    public String getFilterName() {
        return FILTER_NAMES[getFilter()];
    }

    public String[] getAllFilterNames() {
        return FILTER_NAMES.clone();
    }

    public int getFilterIntensity() {
        return sp.getInt(KEY_PIP_FILTER_INTENSITY, 100);
    }

    public void setFilterIntensity(int intensity) {
        intensity = Math.max(0, Math.min(100, intensity));
        sp.edit().putInt(KEY_PIP_FILTER_INTENSITY, intensity).apply();
        Log.d(TAG, "滤镜强度：" + intensity + "%");
    }

    public int nextFilter() {
        int current = getFilter();
        int next = (current + 1) % FILTER_NAMES.length;
        setFilter(next);
        return next;
    }

    public int prevFilter() {
        int current = getFilter();
        int prev = (current - 1 + FILTER_NAMES.length) % FILTER_NAMES.length;
        setFilter(prev);
        return prev;
    }

    public float[] getFilterColorMatrix() {
        float intensity = getFilterIntensity() / 100f;
        float[] matrix = new float[20];
        matrix[0] = 1; matrix[5] = 1; matrix[10] = 1; matrix[15] = 1;

        switch (getFilter()) {
            case FILTER_VIVID:
                float sat = 1.0f + 0.3f * intensity;
                matrix[0] = sat; matrix[5] = sat; matrix[10] = sat;
                break;
            case FILTER_MONOCHROME:
                matrix[0] = 0.3f; matrix[1] = 0.59f; matrix[2] = 0.11f;
                matrix[5] = 0.3f; matrix[6] = 0.59f; matrix[7] = 0.11f;
                matrix[10] = 0.3f; matrix[11] = 0.59f; matrix[12] = 0.11f;
                break;
            case FILTER_SEPIA:
                matrix[0] = 0.393f; matrix[1] = 0.769f; matrix[2] = 0.189f;
                matrix[5] = 0.349f; matrix[6] = 0.686f; matrix[7] = 0.168f;
                matrix[10] = 0.272f; matrix[11] = 0.534f; matrix[12] = 0.131f;
                break;
            case FILTER_COOL:
                matrix[10] = 1.0f + 0.2f * intensity;
                break;
            case FILTER_WARM:
                matrix[0] = 1.0f + 0.2f * intensity;
                break;
            case FILTER_NIGHT:
                matrix[5] = 1.0f + 0.5f * intensity;
                matrix[0] = 0.3f;
                matrix[10] = 0.3f;
                break;
            case FILTER_CINEMA:
                matrix[4] = -20 * intensity;
                matrix[9] = -20 * intensity;
                matrix[14] = -20 * intensity;
                break;
        }
        return matrix;
    }

    public String getFilterDesc() {
        if (getFilter() == FILTER_NONE) return "无";
        return getFilterName() + "（" + getFilterIntensity() + "%）";
    }

    // ================================================
    // 🆕 二十七、实时数据面板
    // ================================================

    public boolean isDataPanelEnabled() {
        return sp.getBoolean(KEY_PIP_DATA_PANEL, false);
    }

    public void setDataPanelEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_DATA_PANEL, enabled).apply();
        Log.d(TAG, "实时数据面板：" + enabled);
        if (!enabled) hideDataPanel();
    }

    public int getDataItems() {
        return sp.getInt(KEY_PIP_DATA_ITEMS,
                DATA_BITRATE | DATA_FRAMERATE | DATA_BUFFER | DATA_RESOLUTION);
    }

    public void setDataItems(int items) {
        sp.edit().putInt(KEY_PIP_DATA_ITEMS, items).apply();
        Log.d(TAG, "数据面板显示项：" + items);
    }

    public boolean showDataPanel() {
        if (!isDataPanelEnabled()) {
            Log.w(TAG, "数据面板未开启");
            return false;
        }
        if (isDataPanelShowing) return true;
        isDataPanelShowing = true;
        Log.d(TAG, "显示实时数据面板");
        return true;
    }

    public boolean hideDataPanel() {
        if (!isDataPanelShowing) return false;
        isDataPanelShowing = false;
        Log.d(TAG, "隐藏实时数据面板");
        return true;
    }

    public boolean toggleDataPanel() {
        if (isDataPanelShowing) {
            hideDataPanel();
            return false;
        } else {
            showDataPanel();
            return true;
        }
    }

    public boolean isDataPanelShowing() {
        return isDataPanelShowing;
    }

    public void updateBitrate(long bitrate) {
        this.currentBitrate = bitrate;
    }

    public void updateFrameRate(float frameRate) {
        this.currentFrameRate = frameRate;
    }

    public void updateBuffer(long bufferMs) {
        this.currentBufferMs = bufferMs;
    }

    public void updateResolution(int width, int height) {
        this.currentWidth = width;
        this.currentHeight = height;
    }

    public void updatePlayDuration(long durationMs) {
        this.playDurationMs = durationMs;
    }

    public void updateNetworkType(String type) {
        this.currentNetworkType = type;
    }

    // ================================================
    // 数据面板 - 格式化方法
    // ================================================

    public String getFormattedBitrate() {
        if (currentBitrate <= 0) return "--";
        if (currentBitrate < 1000 * 1000) {
            return String.format(Locale.getDefault(), "%.1f Kbps", currentBitrate / 1000f);
        } else {
            return String.format(Locale.getDefault(), "%.2f Mbps", currentBitrate / (1000f * 1000));
        }
    }

    public String getFormattedFrameRate() {
        if (currentFrameRate <= 0) return "--";
        return String.format(Locale.getDefault(), "%.1f fps", currentFrameRate);
    }

    public String getFormattedBuffer() {
        if (currentBufferMs <= 0) return "--";
        return String.format(Locale.getDefault(), "%.1f s", currentBufferMs / 1000f);
    }

    public String getFormattedResolution() {
        if (currentWidth <= 0 || currentHeight <= 0) return "--";
        return currentWidth + "×" + currentHeight;
    }

    public String getFormattedDuration() {
        if (playDurationMs <= 0) return "--";
        long seconds = playDurationMs / 1000;
        long min = seconds / 60;
        long sec = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
    }

    public String getDataPanelText() {
        StringBuilder sb = new StringBuilder();
        int items = getDataItems();

        if ((items & DATA_RESOLUTION) != 0) {
            sb.append("分辨率：").append(getFormattedResolution()).append("\n");
        }
        if ((items & DATA_BITRATE) != 0) {
            sb.append("码率：").append(getFormattedBitrate()).append("\n");
        }
        if ((items & DATA_FRAMERATE) != 0) {
            sb.append("帧率：").append(getFormattedFrameRate()).append("\n");
        }
        if ((items & DATA_BUFFER) != 0) {
            sb.append("缓冲：").append(getFormattedBuffer()).append("\n");
        }
        if ((items & DATA_DURATION) != 0) {
            sb.append("时长：").append(getFormattedDuration()).append("\n");
        }
        if ((items & DATA_NETWORK) != 0 && !currentNetworkType.isEmpty()) {
            sb.append("网络：").append(currentNetworkType).append("\n");
        }

        return sb.toString().trim();
    }

    public String getDataPanelDesc() {
        if (!isDataPanelEnabled()) return "未开启";
        int count = Integer.bitCount(getDataItems());
        return "已开启（" + count + " 项数据）";
    }

    // ================================================
    // 🆕 二十八、无缝切换
    // ================================================

    public boolean isSeamlessSwitchEnabled() {
        return sp.getBoolean(KEY_PIP_SEAMLESS_SWITCH, false);
    }

    public void setSeamlessSwitchEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_SEAMLESS_SWITCH, enabled).apply();
        Log.d(TAG, "无缝切换：" + enabled);
        isSeamlessEnabled = enabled;
        if (!enabled) cancelPreload();
    }

    public int getPreloadCount() {
        return sp.getInt(KEY_PIP_PRELOAD_COUNT, 1);
    }

    public void setPreloadCount(int count) {
        count = Math.max(1, Math.min(3, count));
        sp.edit().putInt(KEY_PIP_PRELOAD_COUNT, count).apply();
        Log.d(TAG, "预加载数量：" + count);
    }

    public void preloadNextChannel(String channelName, String url) {
        if (!isSeamlessSwitchEnabled()) return;
        if (isPreloading) return;
        isPreloading = true;
        preloadChannelName = channelName;
        Log.d(TAG, "预加载下一个频道：" + channelName);
        if (seamlessListener != null) {
            seamlessListener.onPreloadNext(channelName, url);
        }
    }

    public void preloadPrevChannel(String channelName, String url) {
        if (!isSeamlessSwitchEnabled()) return;
        if (isPreloading) return;
        isPreloading = true;
        preloadChannelName = channelName;
        Log.d(TAG, "预加载上一个频道：" + channelName);
        if (seamlessListener != null) {
            seamlessListener.onPreloadPrev(channelName, url);
        }
    }

    public boolean doSeamlessSwitch(int direction) {
        if (!isSeamlessSwitchEnabled()) return false;
        if (!isPreloading) return false;
        Log.d(TAG, "执行无缝切换：" + (direction > 0 ? "下一台" : "上一台"));
        if (seamlessListener != null) {
            seamlessListener.onSwitchToPreloaded(direction);
        }
        isPreloading = false;
        return true;
    }

    public void cancelPreload() {
        if (!isPreloading) return;
        isPreloading = false;
        preloadChannelName = "";
        Log.d(TAG, "取消预加载");
    }

    public boolean isPreloading() {
        return isPreloading;
    }

    public String getPreloadChannelName() {
        return preloadChannelName;
    }

    public void setOnSeamlessSwitchListener(OnSeamlessSwitchListener listener) {
        this.seamlessListener = listener;
    }

    public String getSeamlessSwitchDesc() {
        if (!isSeamlessSwitchEnabled()) return "未开启";
        return "已开启（预加载 " + getPreloadCount() + " 个）";
    }

    // ================================================
    // 🆕 二十九、使用统计
    // ================================================

    public long getTotalUseTime() {
        return sp.getLong(KEY_PIP_TOTAL_TIME, 0);
    }

    public int getUseCount() {
        return sp.getInt(KEY_PIP_USE_COUNT, 0);
    }

    public int getRecordCount() {
        return sp.getInt(KEY_PIP_RECORD_COUNT, 0);
    }

    public long getRecordTotalTime() {
        return sp.getLong(KEY_PIP_RECORD_TOTAL_TIME, 0);
    }

    private void incrementUseCount() {
        int count = getUseCount() + 1;
        sp.edit().putInt(KEY_PIP_USE_COUNT, count).apply();
    }

    private void addTotalUseTime(long timeMs) {
        long total = getTotalUseTime() + timeMs;
        sp.edit().putLong(KEY_PIP_TOTAL_TIME, total).apply();
    }

    private void incrementRecordCount() {
        int count = getRecordCount() + 1;
        sp.edit().putInt(KEY_PIP_RECORD_COUNT, count).apply();
    }

    private void addRecordTotalTime(int seconds) {
        long total = getRecordTotalTime() + seconds;
        sp.edit().putLong(KEY_PIP_RECORD_TOTAL_TIME, total).apply();
    }

    public String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d小时%d分钟", hours, minutes);
        } else if (minutes > 0) {
            return String.format(Locale.getDefault(), "%d分钟%d秒", minutes, secs);
        } else {
            return String.format(Locale.getDefault(), "%d秒", secs);
        }
    }

    public String getStatsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("══════════ 使用统计 ══════════\n");
        sb.append("  使用次数：").append(getUseCount()).append(" 次\n");
        sb.append("  总使用时长：").append(formatDuration(getTotalUseTime() / 1000)).append("\n");
        sb.append("  录制次数：").append(getRecordCount()).append(" 次\n");
        sb.append("  录制总时长：").append(formatDuration(getRecordTotalTime())).append("\n");
        sb.append("  录制文件数：").append(getRecordHistory().size()).append(" 个\n");
        sb.append("  录制总大小：").append(formatFileSize(getRecordHistoryTotalSize())).append("\n");
        sb.append("═══════════════════════════════");
        return sb.toString();
    }

    // ================================================
    // 🆕 三十、配置导入导出
    // ================================================

    public String exportConfig() {
        try {
            JSONObject json = new JSONObject();

            // 基础
            json.put("pip_enabled", isPipEnabled());
            json.put("auto_enter", isAutoEnterPip());
            json.put("aspect_ratio", getAspectRatio());
            json.put("gravity", getDefaultGravity());

            // 显示
            json.put("show_controls", isShowControls());
            json.put("show_channel_num", isShowChannelNum());
            json.put("notification", isNotificationEnabled());
            json.put("theme", getTheme());
            json.put("show_info", isShowInfo());
            json.put("info_position", getInfoPosition());
            json.put("small_window_size", getSmallWindowSize());

            // 播放
            json.put("auto_mute", isAutoMute());
            json.put("keep_screen_on", isKeepScreenOn());
            json.put("headset_pause", isHeadsetPause());
            json.put("touch_block", isTouchBlockEnabled());

            // 智能
            json.put("data_saver", isDataSaverEnabled());
            json.put("epg_remind", isEpgRemindEnabled());
            json.put("timeout_minutes", getTimeoutMinutes());
            json.put("battery_saver", isBatterySaverEnabled());
            json.put("auto_brightness", isAutoBrightnessEnabled());

            // 录制
            json.put("record_enabled", isRecordEnabled());
            json.put("record_quality", getRecordQuality());
            json.put("record_audio", isRecordAudioEnabled());

            // 多窗口
            json.put("multi_window", isMultiWindowEnabled());
            json.put("multi_layout", getMultiWindowLayout());

            // 显示增强
            json.put("auto_rotate", isAutoRotateEnabled());
            json.put("night_mode", isNightModeEnabled());
            json.put("night_start", getNightStartHour());
            json.put("night_end", getNightEndHour());

            // 网络优化
            json.put("low_priority_network", isLowPriorityNetworkEnabled());

            // 游戏模式
            json.put("game_mode", isGameModeEnabled());
            json.put("game_latency", getGameLatencyLevel());

            // 多音频
            json.put("multi_audio", isMultiAudioEnabled());
            json.put("main_volume", getMainVolume());
            json.put("second_volume", getSecondVolume());

            // 弹幕
            json.put("danmaku", isDanmakuEnabled());
            json.put("danmaku_size", getDanmakuSize());
            json.put("danmaku_speed", getDanmakuSpeed());
            json.put("danmaku_opacity", getDanmakuOpacity());

            // 滤镜
            json.put("filter", getFilter());
            json.put("filter_intensity", getFilterIntensity());

            // 数据面板
            json.put("data_panel", isDataPanelEnabled());
            json.put("data_items", getDataItems());

            // 无缝切换
            json.put("seamless_switch", isSeamlessSwitchEnabled());
            json.put("preload_count", getPreloadCount());

            return json.toString(2);

        } catch (Exception e) {
            Log.e(TAG, "导出配置失败", e);
            return "";
        }
    }

    public boolean importConfig(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);

            // 基础
            if (json.has("pip_enabled")) setPipEnabled(json.getBoolean("pip_enabled"));
            if (json.has("auto_enter")) setAutoEnterPip(json.getBoolean("auto_enter"));
            if (json.has("aspect_ratio")) setAspectRatio(json.getInt("aspect_ratio"));
            if (json.has("gravity")) setDefaultGravity(json.getInt("gravity"));

            // 显示
            if (json.has("show_controls")) setShowControls(json.getBoolean("show_controls"));
            if (json.has("show_channel_num")) setShowChannelNum(json.getBoolean("show_channel_num"));
            if (json.has("notification")) setNotificationEnabled(json.getBoolean("notification"));
            if (json.has("theme")) setTheme(json.getInt("theme"));
            if (json.has("show_info")) setShowInfo(json.getBoolean("show_info"));
            if (json.has("info_position")) setInfoPosition(json.getInt("info_position"));
            if (json.has("small_window_size")) setSmallWindowSize(json.getInt("small_window_size"));

            // 播放
            if (json.has("auto_mute")) setAutoMute(json.getBoolean("auto_mute"));
            if (json.has("keep_screen_on")) setKeepScreenOn(json.getBoolean("keep_screen_on"));
            if (json.has("headset_pause")) setHeadsetPause(json.getBoolean("headset_pause"));
            if (json.has("touch_block")) setTouchBlockEnabled(json.getBoolean("touch_block"));

            // 智能
            if (json.has("data_saver")) setDataSaverEnabled(json.getBoolean("data_saver"));
            if (json.has("epg_remind")) setEpgRemindEnabled(json.getBoolean("epg_remind"));
            if (json.has("timeout_minutes")) setTimeoutMinutes(json.getInt("timeout_minutes"));
            if (json.has("battery_saver")) setBatterySaverEnabled(json.getBoolean("battery_saver"));
            if (json.has("auto_brightness")) setAutoBrightnessEnabled(json.getBoolean("auto_brightness"));

            // 录制
            if (json.has("record_enabled")) setRecordEnabled(json.getBoolean("record_enabled"));
            if (json.has("record_quality")) setRecordQuality(json.getInt("record_quality"));
            if (json.has("record_audio")) setRecordAudioEnabled(json.getBoolean("record_audio"));

            // 多窗口
            if (json.has("multi_window")) setMultiWindowEnabled(json.getBoolean("multi_window"));
            if (json.has("multi_layout")) setMultiWindowLayout(json.getInt("multi_layout"));

            // 显示增强
            if (json.has("auto_rotate")) setAutoRotateEnabled(json.getBoolean("auto_rotate"));
            if (json.has("night_mode")) setNightModeEnabled(json.getBoolean("night_mode"));
            if (json.has("night_start")) setNightStartHour(json.getInt("night_start"));
            if (json.has("night_end")) setNightEndHour(json.getInt("night_end"));

            // 网络优化
            if (json.has("low_priority_network")) setLowPriorityNetworkEnabled(json.getBoolean("low_priority_network"));

            // 游戏模式
            if (json.has("game_mode")) setGameModeEnabled(json.getBoolean("game_mode"));
            if (json.has("game_latency")) setGameLatencyLevel(json.getInt("game_latency"));

            // 多音频
            if (json.has("multi_audio")) setMultiAudioEnabled(json.getBoolean("multi_audio"));
            if (json.has("main_volume")) setMainVolume((float) json.getDouble("main_volume"));
            if (json.has("second_volume")) setSecondVolume((float) json.getDouble("second_volume"));

            // 弹幕
            if (json.has("danmaku")) setDanmakuEnabled(json.getBoolean("danmaku"));
            if (json.has("danmaku_size")) setDanmakuSize(json.getInt("danmaku_size"));
            if (json.has("danmaku_speed")) setDanmakuSpeed(json.getInt("danmaku_speed"));
            if (json.has("danmaku_opacity")) setDanmakuOpacity(json.getInt("danmaku_opacity"));

            // 滤镜
            if (json.has("filter")) setFilter(json.getInt("filter"));
            if (json.has("filter_intensity")) setFilterIntensity(json.getInt("filter_intensity"));

            // 数据面板
            if (json.has("data_panel")) setDataPanelEnabled(json.getBoolean("data_panel"));
            if (json.has("data_items")) setDataItems(json.getInt("data_items"));

            // 无缝切换
            if (json.has("seamless_switch")) setSeamlessSwitchEnabled(json.getBoolean("seamless_switch"));
            if (json.has("preload_count")) setPreloadCount(json.getInt("preload_count"));

            Log.d(TAG, "导入配置成功");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "导入配置失败", e);
            return false;
        }
    }

    public void resetConfig() {
        sp.edit().clear().apply();
        Log.d(TAG, "画中画配置已重置为默认值");
    }

    // ================================================
    // ✅ 三十一、监听器设置
    // ================================================

    public void setOnPipModeChangedListener(OnPipModeChangedListener listener) {
        this.pipListener = listener;
    }

    public void removeOnPipModeChangedListener() {
        this.pipListener = null;
    }

    // ================================================
    // ✅ 三十二、工具方法
    // ================================================

    public String getPipDescription() {
        if (!isPipSupported()) {
            return "当前设备不支持画中画功能（需要 Android 8.0 及以上）";
        }
        if (!isPipEnabled()) {
            return "点击开启画中画功能，按 Home 键自动小窗播放";
        }
        return "画中画已开启，按 Home 键自动小窗播放，支持切台控制";
    }

    public String getConfigSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("══════════ 画中画配置 ══════════\n");
        sb.append("【基础】\n");
        sb.append("  总开关：").append(isPipEnabled() ? "✅ 开" : "❌ 关").append("\n");
        sb.append("  自动进入：").append(isAutoEnterPip() ? "开" : "关").append("\n");
        sb.append("  宽高比：").append(getAspectRatioName()).append("\n");
        sb.append("  默认位置：").append(getDefaultGravityName()).append("\n");
        sb.append("\n【显示】\n");
        sb.append("  控制按钮：").append(isShowControls() ? "显示" : "隐藏").append("\n");
        sb.append("  频道号浮窗：").append(isShowChannelNum() ? "显示" : "隐藏").append("\n");
        sb.append("  通知栏控制：").append(isNotificationEnabled() ? "开" : "关").append("\n");
        sb.append("  小窗主题：").append(getThemeName()).append("\n");
        sb.append("  实时信息：").append(isShowInfo() ? "显示" : "隐藏").append("\n");
        sb.append("  信息位置：").append(getInfoPositionName()).append("\n");
        sb.append("  小窗大小：").append(getSmallWindowSizeName()).append("\n");
        sb.append("\n【播放】\n");
        sb.append("  自动静音：").append(isAutoMute() ? "开" : "关").append("\n");
        sb.append("  屏幕常亮：").append(isKeepScreenOn() ? "开" : "关").append("\n");
        sb.append("  耳机暂停：").append(isHeadsetPause() ? "开" : "关").append("\n");
        sb.append("  误触防护：").append(isTouchBlockEnabled() ? "开" : "关").append("\n");
        sb.append("\n【智能】\n");
        sb.append("  省流模式：").append(isDataSaverEnabled() ? "开" : "关").append("\n");
        sb.append("  EPG 提醒：").append(isEpgRemindEnabled() ? "开" : "关").append("\n");
        sb.append("  省电模式：").append(isBatterySaverEnabled() ? "开" : "关").append("\n");
        sb.append("  智能亮度：").append(isAutoBrightnessEnabled() ? "开" : "关").append("\n");
        sb.append("  超时关闭：").append(getTimeoutMinutes() == 0 ? "关闭" : getTimeoutMinutes() + "分钟").append("\n");
        sb.append("\n【录制】\n");
        sb.append("  录制功能：").append(isRecordEnabled() ? "开" : "关").append("\n");
        sb.append("  录制质量：").append(getRecordQualityName()).append("\n");
        sb.append("  录制声音：").append(isRecordAudioEnabled() ? "开" : "关").append("\n");
        sb.append("  正在录制：").append(isRecording ? "是 (" + formatRecordDuration(recordDurationSeconds) + ")" : "否").append("\n");
        sb.append("  定时任务：").append(getScheduleList().size()).append(" 个\n");
        sb.append("\n【多窗口】\n");
        sb.append("  多窗口功能：").append(isMultiWindowEnabled() ? "开" : "关").append("\n");
        sb.append("  布局方式：").append(getMultiWindowLayoutName()).append("\n");
        sb.append("  窗口数量：").append(getWindowCount()).append("\n");
        sb.append("\n【网络优化】\n");
        sb.append("  省流模式：").append(isDataSaverEnabled() ? "开" : "关").append("\n");
        sb.append("  低优先级网络：").append(isLowPriorityNetworkEnabled() ? "开" : "关").append("\n");
        sb.append("\n【显示增强】\n");
        sb.append("  自动旋转：").append(isAutoRotateEnabled() ? "开" : "关").append("\n");
        sb.append("  夜间模式：").append(getNightModeDesc()).append("\n");
        sb.append("\n【游戏模式】\n");
        sb.append("  游戏模式：").append(getGameModeDesc()).append("\n");
        sb.append("\n【多音频】\n");
        sb.append("  多音频模式：").append(getMultiAudioDesc()).append("\n");
        sb.append("\n【弹幕】\n");
        sb.append("  弹幕功能：").append(getDanmakuDesc()).append("\n");
        sb.append("\n【滤镜】\n");
        sb.append("  当前滤镜：").append(getFilterDesc()).append("\n");
        sb.append("\n【数据面板】\n");
        sb.append("  实时数据：").append(getDataPanelDesc()).append("\n");
        sb.append("\n【无缝切换】\n");
        sb.append("  无缝切换：").append(getSeamlessSwitchDesc()).append("\n");
        sb.append("═══════════════════════════════");
        return sb.toString();
    }

    public String getCurrentChannelInfo() {
        if (currentChannelNumber > 0 && !currentChannelName.isEmpty()) {
            return currentChannelNumber + " " + currentChannelName;
        } else if (!currentChannelName.isEmpty()) {
            return currentChannelName;
        } else if (currentChannelNumber > 0) {
            return "频道 " + currentChannelNumber;
        }
        return "";
    }

    public String formatRecordDuration(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
    }

    public String formatDateTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    // ================================================
    // ✅ 三十三、释放资源
    // ================================================

    public void release() {
        unregisterControlReceiver();
        unregisterHeadsetReceiver();
        unregisterScheduleReceiver();
        stopTimeoutTimer();
        releaseMediaSession();
        cancelNotification();

        // 释放录制资源
        stopRecording();

        // 释放多窗口资源
        exitMultiWindowMode();

        // 释放 WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception e) { /* ignore */ }
            wakeLock = null;
        }

        pipListener = null;
        recordListener = null;
        multiWindowListener = null;
        epgJumpListener = null;
        seamlessListener = null;

        Log.d(TAG, "画中画管理器已释放");
    }
}
