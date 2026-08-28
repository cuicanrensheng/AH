package com.tv.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import com.tv.live.util.LogBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
 * 8. 外部存储挂载（ACTION_MEDIA_MOUNTED，U 盘/内存卡，开机后常见）
 * 9. 时间/时区变更（ACTION_TIME_SET / TIMEZONE_CHANGED，开机联网校时触发）
 * 10. 网络连接变化（CONNECTIVITY_CHANGE，5.1/6.0 可静态注册，其余靠动态注册）
 *
 * 【为什么要加 SCREEN_ON / USER_PRESENT？】
 * 创维/酷开等国产电视默认开启"快速开机"，关机后实际进入深度休眠，
 * 再次开机只是唤醒屏幕，不会发送 BOOT_COMPLETED。监听 SCREEN_ON 和
 * USER_PRESENT 可以在这种场景下兜底启动应用。
 *
 * 【Android 7.0 (API 24) 重要限制】
 * 从 Android 7.0 起，ACTION_SCREEN_ON / ACTION_USER_PRESENT 不再投递给
 * Manifest 静态注册的 receiver，必须由 Application 动态注册兜底
 * （见 registerDynamic()，在 MyApplication.onCreate 中调用）。
 * 因此本项目"静态注册 + 动态注册"双通道，覆盖 5.1.1 ~ 最新版。
 *
 * 【防重复启动机制】
 *  SCREEN_ON / USER_PRESENT / POWER_CONNECTED / MEDIA_MOUNTED /
 *  TIME_SET / TIMEZONE_CHANGED / CONNECTIVITY_CHANGE 可能在一次
 *  开机过程中触发多次，通过记录上次启动时间戳，5 分钟内只响应一次，
 *  避免重复启动（BOOT_COMPLETED 等一次性广播不限制，保证主通道可靠）。
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
    /** 外部存储挂载（U 盘/内存卡，电视开机后常见触发源） */
    private static final String ACTION_MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED";
    /** 外部存储拔出 */
    private static final String ACTION_MEDIA_EJECT = "android.intent.action.MEDIA_EJECT";
    /** 系统时间被修改（开机联网校时触发，老电视兜底） */
    private static final String ACTION_TIME_SET = "android.intent.action.TIME_SET";
    /** 时区变更（开机校时可能触发） */
    private static final String ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED";
    /** 网络连接变化（Android 7.0 前可静态注册，之后靠 Application 动态注册） */
    private static final String ACTION_CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE";

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
            writeBootLog(context, "onReceive: context 或 intent 为 null");
            return;
        }

        String action = intent.getAction();
        LogBridge.d(TAG, "收到广播：" + action);
        writeBootLog(context, "收到广播 action=" + action);

        // 第一步：判断是否是我们关心的广播
        if (!isBootRelatedAction(action)) {
            LogBridge.d(TAG, "非开机相关广播，忽略：" + action);
            writeBootLog(context, "忽略非开机相关广播 action=" + action);
            return;
        }

        // 第二步：读取自启开关状态
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean autoStart = sp.getBoolean("boot_auto_start", false);
        LogBridge.d(TAG, "开机自启开关状态：" + autoStart);
        writeBootLog(context, "开机相关广播 action=" + action + ", 自启开关=" + autoStart);
        if (!autoStart) {
            LogBridge.d(TAG, "用户未开启开机自启，不启动");
            writeBootLog(context, "自启开关未开启，放弃启动");
            return;
        }

        // 第三步：防重复启动检查（可重复触发的广播可能多次到达）
        if (isRepeatableAction(action)) {
            long lastStart = sp.getLong(SP_KEY_LAST_BOOT_START, 0);
            long elapsed = SystemClock.elapsedRealtime() - lastStart;
            // ⚠️ 必须处理"跨重启"场景：elapsedRealtime 是开机以来单调时钟，
            // 电视重启后归零，而 lastStart 仍是重启前旧值，elapsed 会变成负数。
            // 若直接按 <5min 判断，开机后的兜底广播会被全部误判跳过 → 自启失败。
            if (elapsed <= 0) {
                writeBootLog(context, "防重复：elapsed=" + elapsed + "ms（跨重启导致），重置时间戳并放行 action=" + action);
                sp.edit().putLong(SP_KEY_LAST_BOOT_START, SystemClock.elapsedRealtime()).apply();
            } else if (elapsed < MIN_START_INTERVAL_MS) {
                LogBridge.d(TAG, "距离上次启动仅 " + elapsed + "ms，跳过重复启动");
                writeBootLog(context, "防重复触发：距上次启动 " + elapsed + "ms，跳过 action=" + action);
                return;
            }
        }

        // 第四步：根据广播类型决定延迟时间
        long delay = getDelayByAction(action);
        LogBridge.d(TAG, "延迟 " + delay + "ms 后启动应用");
        writeBootLog(context, "自启链路通过检查，准备延迟 " + delay + "ms 启动");

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
                || action.equals(ACTION_USER_PRESENT)
                || action.equals(ACTION_MEDIA_MOUNTED)
                || action.equals(ACTION_MEDIA_EJECT)
                || action.equals(ACTION_TIME_SET)
                || action.equals(ACTION_TIMEZONE_CHANGED)
                || action.equals(ACTION_CONNECTIVITY_CHANGE);
    }

    /**
     * 可能被系统重复触发的广播：需要做防重复启动
     * （BOOT_COMPLETED / MY_PACKAGE_REPLACED 等一次性广播不在此列，保证主通道可靠）
     */
    private boolean isRepeatableAction(String action) {
        return ACTION_SCREEN_ON.equals(action)
                || ACTION_USER_PRESENT.equals(action)
                || ACTION_POWER_CONNECTED.equals(action)
                || ACTION_MEDIA_MOUNTED.equals(action)
                || ACTION_MEDIA_EJECT.equals(action)
                || ACTION_TIME_SET.equals(action)
                || ACTION_TIMEZONE_CHANGED.equals(action)
                || ACTION_CONNECTIVITY_CHANGE.equals(action);
    }

    private long getDelayByAction(String action) {
        if (action == null) return START_DELAY_MS;
        if (ACTION_MY_PACKAGE_REPLACED.equals(action)) return SHORT_DELAY_MS;
        if (ACTION_SCREEN_ON.equals(action) || ACTION_USER_PRESENT.equals(action)) return SCREEN_ON_DELAY_MS;
        // 网络/存储/校时类广播：给系统留出初始化时间
        if (ACTION_CONNECTIVITY_CHANGE.equals(action)
                || ACTION_MEDIA_MOUNTED.equals(action)
                || ACTION_MEDIA_EJECT.equals(action)
                || ACTION_TIME_SET.equals(action)
                || ACTION_TIMEZONE_CHANGED.equals(action)) {
            return START_DELAY_MS;
        }
        return START_DELAY_MS;
    }

    /**
     * 调度延迟启动（AlarmManager 为主，JobScheduler 兜底）
     */
    private void scheduleDelayedStart(Context context, long delayMs, SharedPreferences sp) {
        // 记录启动时间（用于防重复）
        sp.edit().putLong(SP_KEY_LAST_BOOT_START, SystemClock.elapsedRealtime()).apply();
        writeBootLog(context, "scheduleDelayedStart: 已记录防重复时间戳");

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

                // ⚠️ 必须用 ELAPSED_REALTIME（基于开机以来单调时钟）而非 RTC（墙钟）：
                // 创维/酷开等老电视开机瞬间系统时钟常未校时（出厂值/1970），
                // 用 System.currentTimeMillis() + RTC_WAKEUP 计算出的触发时刻会错，
                // 导致 Alarm 永不触发或严重延迟，这是 5.1.1~7.0 自启失败的头号原因。
                long triggerAt = SystemClock.elapsedRealtime() + delayMs;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // setAndAllowWhileIdle：Doze 模式下也能触发（Android 6.0+ 省电限制）
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                }
                LogBridge.d(TAG, "已设置 AlarmManager 延迟启动（ELAPSED_REALTIME_WAKEUP），" + delayMs + "ms 后启动");
                writeBootLog(context, "AlarmManager 设置成功，触发点=" + triggerAt + "ms，延迟=" + delayMs + "ms");
                alarmScheduled = true;
            } else {
                writeBootLog(context, "AlarmManager 为 null，无法设置延迟启动");
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "AlarmManager 设置失败", e);
            writeBootLog(context, "AlarmManager 设置失败: " + e);
        }

        // 兜底方案：JobScheduler（AlarmManager 被限制时使用）
        if (!alarmScheduled || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                scheduleBootJob(context, delayMs);
            } catch (Exception e) {
                LogBridge.e(TAG, "JobScheduler 设置失败", e);
                writeBootLog(context, "JobScheduler 设置失败: " + e);
            }
        }

        // 最后兜底：直接启动
        if (!alarmScheduled) {
            LogBridge.w(TAG, "AlarmManager 不可用，尝试直接启动");
            writeBootLog(context, "AlarmManager 不可用，直接启动 MainActivity");
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
            LogBridge.d(TAG, "JobScheduler 兜底任务已设置");
            writeBootLog(context, "JobScheduler 兜底任务已设置，delay=" + delayMs + "ms");
        } else {
            LogBridge.w(TAG, "JobScheduler 兜底任务设置失败");
            writeBootLog(context, "JobScheduler 兜底任务设置失败 result=" + result);
        }
    }

    private void startMainActivity(Context context) {
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.startActivity(mainIntent);
            LogBridge.d(TAG, "已启动 MainActivity");
            writeBootLog(context, "startActivity(MainActivity) 调用成功");
        } catch (Exception e) {
            LogBridge.e(TAG, "启动 MainActivity 失败", e);
            writeBootLog(context, "startActivity(MainActivity) 失败: " + e);
        }
    }

    /**
     * 动态注册开机兜底监听（在 Application.onCreate 中调用）
     *
     * 【为什么必须动态注册？】
     * 1. Android 7.0 (API 24) 起，ACTION_SCREEN_ON / ACTION_USER_PRESENT
     *    不再投递给 Manifest 静态注册的 receiver，静态通道在 7.0 上失效。
     * 2. 创维/酷开电视"快速开机"只唤醒屏幕、不发 BOOT_COMPLETED，
     *    必须靠 SCREEN_ON / USER_PRESENT / 网络变化等广播兜底。
     * 3. 动态注册只要应用进程存活即有效（电视内存充裕，应用常驻后台），
     *    与静态注册形成双通道，覆盖 Android 5.1.1 ~ 最新版。
     *
     * 【Android 14 兼容】MyApplication 已覆写 registerReceiver()，
     * 自动为 API 33+ 补 RECEIVER_NOT_EXPORTED 标志，系统广播不受影响。
     */
    public static void registerDynamic(Context context) {
        try {
            BootReceiver receiver = new BootReceiver();

            // 第一组：无 data 的广播
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            filter.addAction("android.intent.action.LOCKED_BOOT_COMPLETED");
            filter.addAction(Intent.ACTION_MY_PACKAGE_REPLACED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction("android.intent.action.TIME_SET");
            filter.addAction("android.intent.action.TIMEZONE_CHANGED");
            filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
            context.registerReceiver(receiver, filter);

            // 第二组：MEDIA_MOUNTED / MEDIA_EJECT 需要 file scheme，单独注册
            IntentFilter mediaFilter = new IntentFilter();
            mediaFilter.addAction("android.intent.action.MEDIA_MOUNTED");
            mediaFilter.addAction("android.intent.action.MEDIA_EJECT");
            mediaFilter.addDataScheme("file");
            context.registerReceiver(receiver, mediaFilter);

            LogBridge.d(TAG, "动态开机兜底监听已注册（覆盖快速开机/SCREEN_ON 场景）");
            writeBootLog(context, "动态开机监听注册成功（SCREEN_ON/USER_PRESENT/POWER_CONNECTED/MEDIA_MOUNTED 等）");
        } catch (Exception e) {
            LogBridge.e(TAG, "动态注册开机监听失败", e);
            writeBootLog(context, "动态开机监听注册失败: " + e);
        }
    }

    // ====================================================================
    // 自启日志（持久化到 files/boot_logs.txt，可通过 LogServer /api/bootlog 拉取）
    // 用于诊断"开机自启失败"：区分 广播未到达 / 开关未开 / 启动链路异常
    // ====================================================================
    private static final String BOOT_LOG_FILE = "boot_logs.txt";

    /** 写入自启日志（append 追加，重启后仍保留） */
    public static void writeBootLog(Context context, String message) {
        if (context == null) return;
        try {
            File file = new File(context.getFilesDir(), BOOT_LOG_FILE);
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date())
                    + " | " + message + "\n";
            FileOutputStream fos = new FileOutputStream(file, true);
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    /** 启动时截断自启日志，防止文件无限膨胀（仅当超过 200KB 时保留最近 500 行） */
    public static void trimBootLog(Context context) {
        if (context == null) return;
        try {
            File file = new File(context.getFilesDir(), BOOT_LOG_FILE);
            if (!file.exists() || file.length() < 200 * 1024) return;
            List<String> lines = new ArrayList<>();
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            br.close();
            int start = Math.max(0, lines.size() - 500);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
            FileOutputStream fos = new FileOutputStream(file, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }
}
