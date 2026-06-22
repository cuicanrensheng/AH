package com.tv.live.manager;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rational;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import androidx.media.MediaSessionManager;
import androidx.media.session.MediaButtonReceiver;
import androidx.core.media.MediaMetadataCompat;
import androidx.core.media.session.MediaSessionCompat;
import androidx.core.media.session.PlaybackStateCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 画中画管理器 - 终极修复版
 * 保留功能：
 * 1.基础画中画(进入/退出/回调) 2.4种宽高比 3.控制按钮 4.通知栏控制 5.MediaSession
 * 6.双击全屏 7.频道信息 8.播放同步 9.耳机暂停 10.超时关闭 11.省流回调
 * 12.使用统计 13.配置导入导出 14.自动进入 15.屏幕常亮 16.自动静音
 * 
 * 修复项：
 * 1. 替换support包为AndroidX，解决编译兼容问题
 * 2. 完善单例线程安全
 * 3. 修复广播注册/解注册泄漏问题
 * 4. 新增MainActivity交互的开关同步方法
 * 5. 新增频道信息更新方法
 * 6. 优化PendingIntent FLAG兼容性
 * 7. 完善异常捕获，防止崩溃
 * 8. 适配带PictureInPictureParams的进入方法
 */
public class PictureInPictureManager {
    private static final String TAG = "PIP_Manager";
    private static volatile PictureInPictureManager instance; // 线程安全单例

    // 单例上下文
    private final Context appContext;
    private final SharedPreferences sp;
    private final Handler mainHandler;
    private final AudioManager audioManager;

    // 核心状态
    private boolean isInPipMode = false;
    private boolean isPlaying = false;
    private boolean pipEnabled = false; // 与MainActivity开关同步
    private Activity currentActivity;

    // 宽高比配置
    public static final int RATIO_16_9 = 0;
    public static final int RATIO_4_3 = 1;
    public static final int RATIO_1_1 = 2;
    public static final int RATIO_21_9 = 3;
    private static final float[][] RATIO_ARRAY = {{16,9},{4,3},{1,1},{21,9}};
    private static final String[] RATIO_NAMES = {"16:9","4:3","1:1","21:9"};

    // 广播/通知常量
    private static final String ACTION_PREV = "pip_prev";
    private static final String ACTION_PLAY_PAUSE = "pip_play";
    private static final String ACTION_NEXT = "pip_next";
    private static final int NOTIFICATION_ID = 888;
    private static final String CHANNEL_ID = "pip_control";

    // 计时器/双击
    private long lastClickTime = 0;
    private static final long DOUBLE_CLICK = 300;
    private Runnable timeoutRunnable;

    // 组件
    private MediaSessionCompat mediaSession;
    private BroadcastReceiver controlReceiver;
    private BroadcastReceiver headsetReceiver;
    private OnPipListener listener;

    // SP配置键
    private static final String SP_NAME = "pip_config";
    private static final String KEY_RATIO = "aspect_ratio";
    private static final String KEY_AUTO_ENTER = "auto_enter";
    private static final String KEY_TIMEOUT = "timeout_min";
    private static final String KEY_AUTO_MUTE = "auto_mute";
    private static final String KEY_KEEP_SCREEN = "keep_screen";
    private static final String KEY_DATA_SAVER = "data_saver";

    // 统计
    private static final String KEY_USE_COUNT = "use_count";
    private static final String KEY_TOTAL_TIME = "total_time";
    private long enterTime;

    // ====================== 单例初始化（线程安全） ======================
    private PictureInPictureManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    public static PictureInPictureManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PictureInPictureManager.class) {
                if (instance == null) {
                    instance = new PictureInPictureManager(context);
                }
            }
        }
        return instance;
    }

    // ====================== 开关同步（适配MainActivity） ======================
    public void setPipEnabled(boolean enabled) {
        this.pipEnabled = enabled;
        Log.d(TAG, "画中画开关已设置为：" + enabled);
    }

    public boolean isPipEnabled() {
        return this.pipEnabled;
    }

    // ====================== 频道信息更新（适配MainActivity） ======================
    public void updateChannelInfo(int channelNum, String channelName, String bitrate) {
        try {
            // 更新MediaSession元数据
            if (mediaSession != null) {
                MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, channelName)
                        .putString(MediaMetadataCompat.METADATA_KEY_SUBTITLE, "频道 " + channelNum + " | 码率：" + bitrate)
                        .build();
                mediaSession.setMetadata(metadata);
            }
            // 更新通知栏
            showNotification();
            Log.d(TAG, "频道信息已更新：频道" + channelNum + " " + channelName);
        } catch (Exception e) {
            Log.e(TAG, "更新频道信息失败", e);
        }
    }

    // ====================== 监听器接口 ======================
    public interface OnPipListener {
        void onPipModeChanged(boolean inPip);
        void onPlayPause();
        void onPrevChannel();
        void onNextChannel();
        boolean onTimeout();
        void onDataSaverChanged(boolean enable);
        void onDoubleTapFullScreen();
    }

    public void setListener(OnPipListener listener) {
        this.listener = listener;
    }

    // ====================== 1.基础画中画（修复版） ======================
    public boolean isPipSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "Android版本低于8.0，不支持画中画");
            return false;
        }
        boolean supported = appContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        Log.d(TAG, "设备画中画支持状态：" + supported);
        return supported;
    }

    // 适配MainActivity调用：带PictureInPictureParams的进入方法
    public boolean enterPictureInPicture(Activity activity, PictureInPictureParams params) {
        if (!pipEnabled) {
            Log.d(TAG, "画中画开关未开启，跳过进入");
            return false;
        }
        if (activity == null || activity.isFinishing() || !isPipSupported()) {
            Log.d(TAG, "进入画中画失败：参数无效或设备不支持");
            return false;
        }
        this.currentActivity = activity;

        try {
            PictureInPictureParams finalParams = params != null ? params : buildPipParams();
            activity.enterPictureInPictureMode(finalParams);

            isInPipMode = true;
            enterTime = System.currentTimeMillis();
            incrementUseCount();

            // 初始化功能
            registerReceivers();
            initMediaSession();
            startScreenOn();
            startAutoMute();
            startTimeout();
            startDataSaver();
            showNotification();

            if (listener != null) listener.onPipModeChanged(true);
            Log.d(TAG, "画中画进入成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "画中画进入失败", e);
            return false;
        }
    }

    // 兼容旧调用方式
    public boolean enterPictureInPicture(Activity activity) {
        return enterPictureInPicture(activity, null);
    }

    public void onPipModeChanged(Activity activity, boolean isInPip) {
        this.currentActivity = activity;
        this.isInPipMode = isInPip;
        Log.d(TAG, "画中画模式变化：" + isInPip);
        
        if (!isInPip) {
            // 退出时释放所有资源
            releaseAll();
            if (listener != null) listener.onPipModeChanged(false);
        }
    }

    private void releaseAll() {
        // 统计时长
        if (enterTime > 0) {
            addTotalTime(System.currentTimeMillis() - enterTime);
            enterTime = 0;
        }

        unregisterReceiversSafely();
        releaseMediaSession();
        stopScreenOn();
        stopTimeout();
        cancelNotification();
        stopDataSaver();
        currentActivity = null;
    }

    // ====================== 资源释放（供MainActivity调用） ======================
    public void release() {
        Log.d(TAG, "画中画管理器释放资源");
        releaseAll();
        isInPipMode = false;
        pipEnabled = false;
        instance = null; // 重置单例
    }

    // ====================== 2.宽高比设置（4种，修复版） ======================
    public void setAspectRatio(int ratio) {
        int finalRatio = Math.max(0, Math.min(3, ratio));
        sp.edit().putInt(KEY_RATIO, finalRatio).apply();
        Log.d(TAG, "宽高比已设置为：" + RATIO_NAMES[finalRatio]);
    }

    private PictureInPictureParams buildPipParams() {
        int ratioIndex = sp.getInt(KEY_RATIO, RATIO_16_9);
        float[] ratio = RATIO_ARRAY[ratioIndex];
        Rational rational = new Rational((int) ratio[0], (int) ratio[1]);

        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        builder.setAspectRatio(rational);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true);
            builder.setSeamlessResizeEnabled(true);
        }
        
        builder.setActions(buildControlActions());
        return builder.build();
    }

    // ====================== 3.控制按钮（修复版） ======================
    private List<RemoteAction> buildControlActions() {
        List<RemoteAction> actions = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return actions;

        actions.add(createAction(ACTION_PREV, "上一台", android.R.drawable.ic_media_previous));
        actions.add(createAction(ACTION_PLAY_PAUSE, isPlaying ? "暂停" : "播放",
                isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play));
        actions.add(createAction(ACTION_NEXT, "下一台", android.R.drawable.ic_media_next));
        return actions;
    }

    private RemoteAction createAction(String action, String title, int iconRes) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(appContext.getPackageName());

            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            }

            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    appContext, action.hashCode(), intent, flags);

            return new RemoteAction(
                    android.graphics.drawable.Icon.createWithResource(appContext, iconRes),
                    title, title, pi
            );
        } catch (Exception e) {
            Log.e(TAG, "创建RemoteAction失败", e);
            return null;
        }
    }

    // ====================== 4.通知栏控制（修复兼容性） ======================
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "画中画控制", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.createNotificationChannel(channel);
            } catch (Exception e) {
                Log.e(TAG, "创建通知渠道失败", e);
            }
        }
    }

    private void showNotification() {
        try {
            createNotificationChannel();
            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID);
            builder.setSmallIcon(android.R.drawable.ic_media_play);
            builder.setContentTitle("电视直播");
            builder.setContentText("画中画播放中");
            builder.setOngoing(true);
            builder.setPriority(NotificationCompat.PRIORITY_LOW);

            // 按钮
            builder.addAction(android.R.drawable.ic_media_previous, "上一台", getPi(ACTION_PREV));
            builder.addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    isPlaying ? "暂停" : "播放", getPi(ACTION_PLAY_PAUSE));
            builder.addAction(android.R.drawable.ic_media_next, "下一台", getPi(ACTION_NEXT));

            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "显示通知失败", e);
        }
    }

    private android.app.PendingIntent getPi(String action) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(appContext.getPackageName());
            
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            }
            
            return android.app.PendingIntent.getBroadcast(appContext, action.hashCode(), intent, flags);
        } catch (Exception e) {
            Log.e(TAG, "创建PendingIntent失败", e);
            return null;
        }
    }

    private void cancelNotification() {
        try {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(NOTIFICATION_ID);
        } catch (Exception e) {
            Log.e(TAG, "取消通知失败", e);
        }
    }

    // ====================== 5.MediaSession（修复版） ======================
    private void initMediaSession() {
        if (mediaSession != null) return;
        try {
            mediaSession = new MediaSessionCompat(appContext, "TV_PIP");
            mediaSession.setActive(true);
            updateMediaSession();
        } catch (Exception e) {
            Log.e(TAG, "初始化MediaSession失败", e);
        }
    }

    public void updatePlayState(boolean playing) {
        this.isPlaying = playing;
        updateMediaSession();
        showNotification();
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;
        try {
            PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                    .setState(isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                            0, 1.0f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
                    .build();
            mediaSession.setPlaybackState(state);
        } catch (Exception e) {
            Log.e(TAG, "更新MediaSession状态失败", e);
        }
    }

    private void releaseMediaSession() {
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Exception e) {
                Log.e(TAG, "释放MediaSession失败", e);
            }
            mediaSession = null;
        }
    }

    // ====================== 6.双击切换全屏（修复版） ======================
    public boolean onPipClick() {
        long now = System.currentTimeMillis();
        if (now - lastClickTime < DOUBLE_CLICK) {
            lastClickTime = 0;
            if (listener != null) {
                listener.onDoubleTapFullScreen();
            }
            return true;
        }
        lastClickTime = now;
        return false;
    }

    // ====================== 7.耳机拔出暂停（修复版） ======================
    private BroadcastReceiver getHeadsetReceiver() {
        if (headsetReceiver == null) {
            headsetReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                        int state = intent.getIntExtra("state", 0);
                        if (state == 0 && isPlaying && listener != null) {
                            listener.onPlayPause();
                            Log.d(TAG, "耳机拔出，自动暂停播放");
                        }
                    }
                }
            };
        }
        return headsetReceiver;
    }

    // ====================== 8.超时自动关闭（修复内存泄漏） ======================
    public void setTimeout(int minutes) {
        int finalMinutes = Math.max(0, minutes);
        sp.edit().putInt(KEY_TIMEOUT, finalMinutes).apply();
        Log.d(TAG, "画中画超时时间已设置为：" + finalMinutes + "分钟");
    }

    private void startTimeout() {
        int minutes = sp.getInt(KEY_TIMEOUT, 0);
        if (minutes <= 0) return;

        stopTimeout();
        timeoutRunnable = () -> {
            if (listener != null && !listener.onTimeout()) {
                releaseAll();
            }
        };
        mainHandler.postDelayed(timeoutRunnable, (long) minutes * 60 * 1000);
    }

    private void stopTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    // ====================== 9.屏幕常亮（修复版） ======================
    private void startScreenOn() {
        if (currentActivity != null && sp.getBoolean(KEY_KEEP_SCREEN, true)) {
            try {
                currentActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception e) {
                Log.e(TAG, "设置屏幕常亮失败", e);
            }
        }
    }

    private void stopScreenOn() {
        if (currentActivity != null) {
            try {
                currentActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception e) {
                Log.e(TAG, "取消屏幕常亮失败", e);
            }
        }
    }

    // ====================== 10.自动静音（修复版） ======================
    private void startAutoMute() {
        try {
            if (sp.getBoolean(KEY_AUTO_MUTE, false)) {
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true);
                Log.d(TAG, "画中画模式自动静音");
            }
        } catch (Exception e) {
            Log.e(TAG, "设置自动静音失败", e);
        }
    }

    // ====================== 11.省流模式回调 ======================
    private void startDataSaver() {
        if (sp.getBoolean(KEY_DATA_SAVER, false) && listener != null) {
            listener.onDataSaverChanged(true);
            Log.d(TAG, "进入省流模式");
        }
    }

    private void stopDataSaver() {
        if (sp.getBoolean(KEY_DATA_SAVER, false) && listener != null) {
            listener.onDataSaverChanged(false);
            Log.d(TAG, "退出省流模式");
        }
    }

    // ====================== 12.使用统计 ======================
    private void incrementUseCount() {
        try {
            sp.edit().putInt(KEY_USE_COUNT, sp.getInt(KEY_USE_COUNT, 0) + 1).apply();
        } catch (Exception e) {
            Log.e(TAG, "统计使用次数失败", e);
        }
    }

    private void addTotalTime(long time) {
        try {
            sp.edit().putLong(KEY_TOTAL_TIME, sp.getLong(KEY_TOTAL_TIME, 0) + time).apply();
        } catch (Exception e) {
            Log.e(TAG, "统计使用时长失败", e);
        }
    }

    // ====================== 13.配置导入导出（无开关） ======================
    public String exportConfig() {
        try {
            JSONObject json = new JSONObject();
            json.put("ratio", sp.getInt(KEY_RATIO, RATIO_16_9));
            json.put("auto_enter", sp.getBoolean(KEY_AUTO_ENTER, true));
            json.put("timeout", sp.getInt(KEY_TIMEOUT, 0));
            json.put("auto_mute", sp.getBoolean(KEY_AUTO_MUTE, false));
            json.put("keep_screen", sp.getBoolean(KEY_KEEP_SCREEN, true));
            json.put("data_saver", sp.getBoolean(KEY_DATA_SAVER, false));
            return json.toString();
        } catch (Exception e) {
            Log.e(TAG, "导出配置失败", e);
            return "";
        }
    }

    public void importConfig(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            sp.edit().putInt(KEY_RATIO, obj.optInt("ratio", RATIO_16_9))
                    .putBoolean(KEY_AUTO_ENTER, obj.optBoolean("auto_enter", true))
                    .putInt(KEY_TIMEOUT, obj.optInt("timeout", 0))
                    .putBoolean(KEY_AUTO_MUTE, obj.optBoolean("auto_mute", false))
                    .putBoolean(KEY_KEEP_SCREEN, obj.optBoolean("keep_screen", true))
                    .putBoolean(KEY_DATA_SAVER, obj.optBoolean("data_saver", false))
                    .apply();
            Log.d(TAG, "画中画配置导入成功");
        } catch (Exception e) {
            Log.e(TAG, "导入配置失败", e);
        }
    }

    // ====================== 14.自动进入画中画 ======================
    public boolean autoEnterPip(Activity activity) {
        if (sp.getBoolean(KEY_AUTO_ENTER, true) && !isInPipMode && pipEnabled) {
            return enterPictureInPicture(activity);
        }
        return false;
    }

    // ====================== 广播注册（修复泄漏 + 安全解注册） ======================
    private void registerReceivers() {
        // 控制广播
        if (controlReceiver == null) {
            controlReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (listener == null) return;
                    String action = intent.getAction();
                    switch (action) {
                        case ACTION_PREV: 
                            listener.onPrevChannel(); 
                            break;
                        case ACTION_PLAY_PAUSE: 
                            listener.onPlayPause(); 
                            break;
                        case ACTION_NEXT: 
                            listener.onNextChannel(); 
                            break;
                    }
                }
            };
        }
        
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_PREV);
            filter.addAction(ACTION_PLAY_PAUSE);
            filter.addAction(ACTION_NEXT);
            appContext.registerReceiver(controlReceiver, filter);

            // 耳机广播
            appContext.registerReceiver(getHeadsetReceiver(), new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        } catch (Exception e) {
            Log.e(TAG, "注册广播失败", e);
        }
    }

    // 安全解注册广播，防止IllegalArgumentException
    private void unregisterReceiversSafely() {
        try {
            if (controlReceiver != null) {
                appContext.unregisterReceiver(controlReceiver);
                controlReceiver = null;
            }
            if (headsetReceiver != null) {
                appContext.unregisterReceiver(headsetReceiver);
                headsetReceiver = null;
            }
        } catch (IllegalArgumentException e) {
            // 忽略未注册的异常
            Log.d(TAG, "广播未注册，无需解注册");
        } catch (Exception e) {
            Log.e(TAG, "解注册广播失败", e);
        }
    }

    // ====================== 状态获取方法 ======================
    public boolean isInPipMode() {
        return isInPipMode;
    }

    public boolean isPlaying() {
        return isPlaying;
    }
}
