package com.tv.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * 开机自启广播接收器（增强版）
 *
 * 【适配说明】
 * 适配所有电视设备和所有安卓版本，支持多种开机场景：
 * 1. 正常开机（BOOT_COMPLETED）
 * 2. 锁屏开机（LOCKED_BOOT_COMPLETED，Android 7.0+）
 * 3. 应用更新后（MY_PACKAGE_REPLACED）
 * 4. 厂商快速开机（QUICKBOOT_POWERON，小米/OPPO 等）
 * 5. 充电开机（ACTION_POWER_CONNECTED，部分设备）
 * 6. 屏幕点亮（ACTION_SCREEN_ON，创维/酷开快速开机唤醒场景）
 * 7. 用户解锁（ACTION_USER_PRESENT，作为兜底）
 *
 * 【为什么要加 SCREEN_ON / USER_PRESENT？】
 * 创维/酷开等国产电视默认开启"快速开机"，关机后实际进入深度休眠，
 * 再次开机只是唤醒屏幕，不会发送 BOOT_COMPLETED。监听 SCREEN_ON 和
 * USER_PRESENT 可以在这种场景下兜底启动应用。
 *
 * 【防重复启动机制】
 *  SCREEN_ON / USER_PRESENT 可能在一次开机过程中触发多次，
 *  通过记录上次启动时间戳，5 分钟内只响应一次，避免重复启动。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    // ====================================================================
    // 支持的广播 Action 列表（多广播兼容，提高成功率）
    // ====================================================================
    private static final String ACTION_BOOT_COMPLETED = Intent.ACTION_BOOT_COMPLETED;
    private static final String ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";
    private static final String ACTION_MY_PACKAGE_REPLACED = Intent.ACTION_MY_PACKAGE_REPLACED;
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_QUICKBOOT_POWERON_HTC = "com.htc.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_POWER_CONNECTED = Intent.ACTION_POWER_CONNECTED;
    /** 屏幕点亮（创维/酷开快速开机唤醒时触发） */
    private static final String ACTION_SCREEN_ON = Intent.ACTION_SCREEN_ON;
    /** 用户解锁（作为兜底） */
    private static final String ACTION_USER_PRESENT = Intent.ACTION_USER_PRESENT;

    // ====================================================================
    // 延迟启动时间（毫秒）
    // ====================================================================
    private static final long START_DELAY_MS = 3000;
    private static final long SHORT_DELAY_MS = 1000;
    /** 屏幕唤醒场景用更短延迟，用户已在使用电视 */
    private static final long SCREEN_ON_DELAY_MS = 1500;

    /** 防重复启动间隔：5 分钟 */
    private static final long MIN_START_INTERVAL_MS = 5 * 60 * 1000;
    private static final String SP_KEY_LAST_BOOT_START = "last_boot_start_time";

    // ====================================================================
    // JobScheduler 兜底配置
    // ====================================================================
    private static final int BOOT_JOB_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        String action = intent.getAction();
        Log.d(TAG, "收到广播：" + action);

        // 第一步：判断是否是我们关心的广播
        if (!isBootRelatedAction(action)) {
            Log.d(TAG, "非开机相关广播，忽略：" + action);
            return;
        }

        // 第二步：读取自启开关状态
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean autoStart = sp.getBoolean("boot_auto_start", false);
        Log.d(TAG, "开机自启开关状态：" + autoStart);
        if (!autoStart) {
            Log.d(TAG, "用户未开启开机自启，不启动");
            return;
        }

        // 第三步：防重复启动检查（SCREEN_ON / USER_PRESENT 可能多次触发）
        if (isScreenOrUserAction(action)) {
            long lastStart = sp.getLong(SP_KEY_LAST_BOOT_START, 0);
            long elapsed = SystemClock.elapsedRealtime() - lastStart;
            if (elapsed < MIN_START_INTERVAL_MS && lastStart > 0) {
                Log.d(TAG, "距离上次启动仅 " + elapsed + "ms，跳过重复启动");
                return;
            }
        }

        // 第四步：根据广播类型决定延迟时间
        long delay = getDelayByAction(action);
        Log.d(TAG, "延迟 " + delay + "ms 后启动应用");

        // 第五步：延迟启动应用（AlarmManager + JobScheduler 双兜底）
        scheduleDelayedStart(context, delay, sp);
    }

    private boolean isBootRelatedAction(String action) {
        if (action == null) return false;
        return action.equals(ACTION_BOOT_COMPLETED)
                || action.equals(ACTION_LOCKED_BOOT_COMPLETED)
                || action.equals(ACTION_MY_PACKAGE_REPLACED)
                || action.equals(ACTION_QUICKBOOT_POWERON)
                || action.equals(ACTION_QUICKBOOT_POWERON_HTC)
                || action.equals(ACTION_POWER_CONNECTED)
                || action.equals(ACTION_SCREEN_ON)
                || action.equals(ACTION_USER_PRESENT);
    }

    private boolean isScreenOrUserAction(String action) {
        return ACTION_SCREEN_ON.equals(action) || ACTION_USER_PRESENT.equals(action);
    }

    private long getDelayByAction(String action) {
        if (action == null) return START_DELAY_MS;
        if (ACTION_MY_PACKAGE_REPLACED.equals(action)) return SHORT_DELAY_MS;
        if (ACTION_SCREEN_ON.equals(action) || ACTION_USER_PRESENT.equals(action)) return SCREEN_ON_DELAY_MS;
        return START_DELAY_MS;
    }

    /**
     * 调度延迟启动（AlarmManager 为主，JobScheduler 兜底）
     */
    private void scheduleDelayedStart(Context context, long delayMs, SharedPreferences sp) {
        // 记录启动时间（用于防重复）
        sp.edit().putLong(SP_KEY_LAST_BOOT_START, SystemClock.elapsedRealtime()).apply();

        boolean alarmScheduled = false;

        // 主方案：AlarmManager
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                Intent startIntent = new Intent(context, BootStartReceiver.class);
                startIntent.setAction("com.tv.live.START_APP");

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }

                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        context, 0, startIntent, flags);

                long triggerAt = System.currentTimeMillis() + delayMs;
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                Log.d(TAG, "已设置 AlarmManager 延迟启动，" + delayMs + "ms 后启动");
                alarmScheduled = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "AlarmManager 设置失败", e);
        }

        // 兜底方案：JobScheduler（AlarmManager 被限制时使用）
        if (!alarmScheduled || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                scheduleBootJob(context, delayMs);
            } catch (Exception e) {
                Log.e(TAG, "JobScheduler 设置失败", e);
            }
        }

        // 最后兜底：直接启动
        if (!alarmScheduled) {
            Log.w(TAG, "AlarmManager 不可用，尝试直接启动");
            startMainActivity(context);
        }
    }

    /**
     * 使用 JobScheduler 作为兜底方案
     */
    private void scheduleBootJob(Context context, long delayMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler == null) return;

        // 取消旧任务
        jobScheduler.cancel(BOOT_JOB_ID);

        ComponentName componentName = new ComponentName(context, BootJobService.class);
        JobInfo.Builder builder = new JobInfo.Builder(BOOT_JOB_ID, componentName);

        // 设置最小延迟
        builder.setMinimumLatency(delayMs);
        // 需要设备充电时执行（电视通常插电，此条件几乎恒满足，且能提高调度优先级）
        builder.setRequiresCharging(false);
        // 设备空闲时执行
        builder.setRequiresDeviceIdle(false);
        // 网络不要求
        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);
        // 持久化，重启后仍保留（需要 RECEIVE_BOOT_COMPLETED 权限）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPersisted(true);
        }

        int result = jobScheduler.schedule(builder.build());
        if (result == JobScheduler.RESULT_SUCCESS) {
            Log.d(TAG, "JobScheduler 兜底任务已设置");
        } else {
            Log.w(TAG, "JobScheduler 兜底任务设置失败");
        }
    }

    private void startMainActivity(Context context) {
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.startActivity(mainIntent);
            Log.d(TAG, "已启动 MainActivity");
        } catch (Exception e) {
            Log.e(TAG, "启动 MainActivity 失败", e);
        }
    }
}
