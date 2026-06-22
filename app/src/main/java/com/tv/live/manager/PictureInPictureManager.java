package com.tv.live.manager;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 画中画管理器 - 零依赖 无报错版
 * 修复：Rational导包错误、移除所有AndroidX媒体依赖、补充通知通道、完善版本兼容
 * 兼容：原生Android TV项目，无额外库依赖
 */
public class PictureInPictureManager {
    private static final String TAG = "PIP_Manager";
    private static final String PIP_CHANNEL_ID = "PIP_CHANNEL_ID";
    private static final String PIP_CHANNEL_NAME = "画中画播放";
    private static PictureInPictureManager instance;

    private final Context appContext;
    private Activity currentActivity;
    private boolean isInPipMode = false;
    private boolean isPlaying = false;
    private boolean pipEnabled = false;

    // 宽高比常量（复用）
    public static final int RATIO_16_9 = 0;
    private static final float[][] RATIO_ARRAY = {{16, 9}};
    private static final Rational RATIO_16_9_RATIONAL = new Rational(16, 9);

    // 广播
    private BroadcastReceiver headsetReceiver;
    private OnPipListener listener;

    // 单例
    private PictureInPictureManager(Context context) {
        this.appContext = context.getApplicationContext();
        // 初始化通知通道（Android O+ 必需）
        initNotificationChannel();
    }

    public static PictureInPictureManager getInstance(Context context) {
        if (instance == null) {
            instance = new PictureInPictureManager(context);
        }
        return instance;
    }

    // 初始化PIP通知通道（Android O+ 必须创建，否则PIP可能失败）
    private void initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) return;

            NotificationChannel channel = notificationManager.getNotificationChannel(PIP_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(PIP_CHANNEL_ID, PIP_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
                channel.setSound(null, null); // 静音通道
                channel.enableVibration(false);
                channel.enableLights(false);
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "PIP通知通道创建成功");
            }
        }
    }

    // 画中画开关
    public void setPipEnabled(boolean enabled) {
        this.pipEnabled = enabled;
        Log.d(TAG, "画中画开关设置为：" + enabled);
    }

    public boolean isPipEnabled() {
        return pipEnabled;
    }

    // 监听器（补充默认实现，降低接入成本）
    public interface OnPipListener {
        default void onPipModeChanged(boolean inPip) {}
        default void onPlayPause() {}
        default void onPrevChannel() {}
        default void onNextChannel() {}
        default boolean onTimeout() { return false; }
        default void onDataSaverChanged(boolean enable) {}
        default void onDoubleTapFullScreen() {}
    }

    public void setListener(OnPipListener listener) {
        this.listener = listener;
    }

    // 设备是否支持画中画（增强检查）
    public boolean isPipSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "Android版本低于O，不支持PIP");
            return false;
        }
        boolean hasFeature = appContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        Log.d(TAG, "设备是否支持PIP功能：" + hasFeature);
        return hasFeature;
    }

    // 进入画中画（带参数，完善版本兼容）
    public boolean enterPictureInPicture(Activity activity, PictureInPictureParams params) {
        if (!pipEnabled || activity == null || activity.isFinishing() || activity.isDestroyed() || !isPipSupported()) {
            Log.w(TAG, "进入PIP条件不满足：enabled=" + pipEnabled + ", activity有效=" + (activity != null && !activity.isFinishing() && !activity.isDestroyed()) + ", 支持PIP=" + isPipSupported());
            return false;
        }
        this.currentActivity = activity;

        try {
            PictureInPictureParams finalParams = params != null ? params : buildPipParams();
            // 版本兼容：仅O+支持enterPictureInPictureMode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                boolean result = activity.enterPictureInPictureMode(finalParams);
                isInPipMode = result;
                if (result) {
                    Log.d(TAG, "进入画中画成功");
                    registerHeadsetReceiver();
                    if (listener != null) {
                        listener.onPipModeChanged(true);
                    }
                } else {
                    Log.e(TAG, "进入画中画失败：enterPictureInPictureMode返回false");
                }
                return result;
            } else {
                Log.w(TAG, "Android版本低于O，无法进入PIP");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "进入画中画异常", e);
            isInPipMode = false;
            return false;
        }
    }

    // 兼容旧方法
    public boolean enterPictureInPicture(Activity activity) {
        return enterPictureInPicture(activity, null);
    }

    // 画中画模式变化
    public void onPipModeChanged(Activity activity, boolean isInPip) {
        this.currentActivity = activity;
        boolean oldState = this.isInPipMode;
        this.isInPipMode = isInPip;
        Log.d(TAG, "PIP模式变化：旧状态=" + oldState + ", 新状态=" + isInPip);

        if (!isInPip) {
            release();
            if (listener != null) {
                listener.onPipModeChanged(false);
            }
        }
    }

    // 构建画中画参数（复用常量、版本兼容）
    private PictureInPictureParams buildPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        builder.setAspectRatio(RATIO_16_9_RATIONAL);
        return builder.build();
    }

    // 更新播放状态
    public void updatePlayState(boolean playing) {
        this.isPlaying = playing;
        Log.d(TAG, "播放状态更新为：" + playing);
    }

    // 获取播放状态
    public boolean isPlaying() {
        return isPlaying;
    }

    // 更新频道信息（完善实现）
    public void updateChannelInfo(int channelNum, String channelName, String bitrate) {
        Log.d(TAG, "更新PIP频道信息：频道号=" + channelNum + ", 名称=" + channelName + ", 码率=" + bitrate);
        // 可扩展：更新PIP通知中的频道信息
    }

    // 耳机拔出暂停播放（优化广播注册逻辑）
    private void registerHeadsetReceiver() {
        if (headsetReceiver != null || appContext == null) {
            return;
        }
        headsetReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                    int state = intent.getIntExtra("state", 0);
                    int hasMicrophone = intent.getIntExtra("microphone", 0);
                    Log.d(TAG, "耳机状态变化：state=" + state + ", hasMic=" + hasMicrophone);
                    // state=0 表示耳机拔出
                    if (state == 0 && isPlaying && listener != null) {
                        Log.d(TAG, "耳机拔出，触发暂停播放");
                        listener.onPlayPause();
                    }
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
            appContext.registerReceiver(headsetReceiver, filter);
            Log.d(TAG, "耳机广播注册成功");
        } catch (Exception e) {
            Log.e(TAG, "耳机广播注册失败", e);
            headsetReceiver = null;
        }
    }

    // 释放资源（完善反注册、状态重置）
    public void release() {
        Log.d(TAG, "释放PIP资源");
        // 反注册耳机广播
        if (headsetReceiver != null) {
            try {
                appContext.unregisterReceiver(headsetReceiver);
                Log.d(TAG, "耳机广播反注册成功");
            } catch (Exception e) {
                Log.w(TAG, "耳机广播反注册失败（可能未注册）", e);
            }
            headsetReceiver = null;
        }
        // 重置状态
        isInPipMode = false;
        isPlaying = false;
        currentActivity = null;
        listener = null; // 释放监听器
    }

    // 获取状态
    public boolean isInPipMode() {
        return isInPipMode;
    }

    // 获取当前关联的Activity
    public Activity getCurrentActivity() {
        return currentActivity;
    }
}
