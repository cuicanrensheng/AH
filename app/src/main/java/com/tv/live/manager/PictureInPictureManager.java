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
import android.content.SharedPreferences;
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
 * 修复：Rational导包错误、移除所有AndroidX媒体依赖
 * 兼容：原生Android TV项目，无额外库依赖
 */
public class PictureInPictureManager {
    private static final String TAG = "PIP_Manager";
    private static PictureInPictureManager instance;

    private final Context appContext;
    private Activity currentActivity;
    private boolean isInPipMode = false;
    private boolean isPlaying = false;
    private boolean pipEnabled = false;

    // 宽高比
    public static final int RATIO_16_9 = 0;
    private static final float[][] RATIO_ARRAY = {{16,9}};

    // 广播
    private BroadcastReceiver headsetReceiver;
    private OnPipListener listener;

    // 单例
    private PictureInPictureManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static PictureInPictureManager getInstance(Context context) {
        if (instance == null) {
            instance = new PictureInPictureManager(context);
        }
        return instance;
    }

    // 画中画开关
    public void setPipEnabled(boolean enabled) {
        this.pipEnabled = enabled;
    }

    public boolean isPipEnabled() {
        return pipEnabled;
    }

    // 监听器
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

    // 设备是否支持画中画
    public boolean isPipSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        return appContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    // 进入画中画（带参数）
    public boolean enterPictureInPicture(Activity activity, PictureInPictureParams params) {
        if (!pipEnabled || activity == null || activity.isFinishing() || !isPipSupported()) {
            return false;
        }
        this.currentActivity = activity;

        try {
            if (params == null) {
                params = buildPipParams();
            }
            activity.enterPictureInPictureMode(params);
            isInPipMode = true;

            registerHeadsetReceiver();
            if (listener != null) {
                listener.onPipModeChanged(true);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "进入画中画失败", e);
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
        this.isInPipMode = isInPip;
        if (!isInPip) {
            release();
            if (listener != null) {
                listener.onPipModeChanged(false);
            }
        }
    }

    // 构建画中画参数
    private PictureInPictureParams buildPipParams() {
        Rational rational = new Rational(16, 9);
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        builder.setAspectRatio(rational);
        return builder.build();
    }

    // 更新播放状态
    public void updatePlayState(boolean playing) {
        this.isPlaying = playing;
    }

    // 更新频道信息
    public void updateChannelInfo(int channelNum, String channelName, String bitrate) {
        // 空实现，避免报错
    }

    // 耳机拔出暂停播放
    private void registerHeadsetReceiver() {
        if (headsetReceiver == null) {
            headsetReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                        int state = intent.getIntExtra("state", 0);
                        if (state == 0 && isPlaying && listener != null) {
                            listener.onPlayPause();
                        }
                    }
                }
            };
        }
        appContext.registerReceiver(headsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
    }

    // 释放资源
    public void release() {
        try {
            if (headsetReceiver != null) {
                appContext.unregisterReceiver(headsetReceiver);
                headsetReceiver = null;
            }
        } catch (Exception e) {
            // 忽略未注册异常
        }

        isInPipMode = false;
        currentActivity = null;
    }

    // 获取状态
    public boolean isInPipMode() {
        return isInPipMode;
    }
}
