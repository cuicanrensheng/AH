package com.tv.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启广播接收器
 *
 * 【适配说明】
 * 适配所有电视设备和所有安卓版本，支持多种开机场景：
 * 1. 正常开机（BOOT_COMPLETED）
 * 2. 锁屏开机（LOCKED_BOOT_COMPLETED，Android 7.0+）
 * 3. 应用更新后（MY_PACKAGE_REPLACED）
 * 4. 厂商快速开机（QUICKBOOT_POWERON，小米/OPPO 等）
 * 5. 充电开机（ACTION_POWER_CONNECTED，部分设备）
 *
 * 【五层逻辑闭环】
 * 1. 状态管理层：读取自启开关状态
 * 2. 数据筛选层：过滤不需要响应的广播
 * 3. 状态同步层：开关状态 → 是否启动
 * 4. 异常兜底层：启动失败不崩溃，记录日志
 * 5. 交互闭环层：收到广播 → 延迟判断 → 启动应用
 *
 * 【为什么要延迟启动？】
 * 有些电视设备开机后系统服务还没准备好，立刻启动 Activity 会失败。
 * 延迟 3 秒再启动，等系统完全准备好，成功率更高。
 *
 * 【为什么用 AlarmManager 而不是 Handler？】
 * 广播接收器的生命周期很短（只有 10 秒左右），
 * 如果用 Handler postDelayed，可能还没执行完接收器就被销毁了。
 * 用 AlarmManager 设置一个一次性闹钟，更可靠。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    // ====================================================================
    // 支持的广播 Action 列表（多广播兼容，提高成功率）
    // ====================================================================
    /** 标准开机完成广播（所有安卓版本都支持） */
    private static final String ACTION_BOOT_COMPLETED = Intent.ACTION_BOOT_COMPLETED;

    /** 锁屏开机完成广播（Android 7.0+，直接启动到锁屏时发送） */
    private static final String ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";

    /** 应用替换/更新完成广播（应用更新后自动启动） */
    private static final String ACTION_MY_PACKAGE_REPLACED = Intent.ACTION_MY_PACKAGE_REPLACED;

    /** 小米/OPPO 等厂商的快速开机广播 */
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    /** HTC 快速开机广播 */
    private static final String ACTION_QUICKBOOT_POWERON_HTC = "com.htc.intent.action.QUICKBOOT_POWERON";

    /** 充电连接广播（部分电视充电时会自动开机，可作为兜底） */
    private static final String ACTION_POWER_CONNECTED = Intent.ACTION_POWER_CONNECTED;

    // ====================================================================
    // 延迟启动时间（毫秒）
    // ====================================================================
    /** 延迟启动时间：3 秒，等系统完全准备好再启动 */
    private static final long START_DELAY_MS = 3000;

    /** 短延迟：应用更新后启动，不需要等太久 */
    private static final long SHORT_DELAY_MS = 1000;

    // ====================================================================
    // 广播接收主方法
    // ====================================================================
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        String action = intent.getAction();
        Log.d(TAG, "收到广播：" + action);

        // ====================================================================
        // 第一步：判断是否是我们关心的广播
        // ====================================================================
        if (!isBootRelatedAction(action)) {
            Log.d(TAG, "非开机相关广播，忽略：" + action);
            return;
        }

        // ====================================================================
        // 第二步：读取自启开关状态
        // ====================================================================
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean autoStart = sp.getBoolean("boot_auto_start", false);

        Log.d(TAG, "开机自启开关状态：" + autoStart);

        if (!autoStart) {
            Log.d(TAG, "用户未开启开机自启，不启动");
            return;
        }

        // ====================================================================
        // 第三步：根据广播类型决定延迟时间
        // ====================================================================
        long delay = getDelayByAction(action);

        Log.d(TAG, "延迟 " + delay + "ms 后启动应用");

        // ====================================================================
        // 第四步：延迟启动应用（用 AlarmManager 更可靠）
        // ====================================================================
        scheduleDelayedStart(context, delay);
    }

    // ====================================================================
    // 判断是否是开机相关的广播
    // ====================================================================
    /**
     * 判断是否是开机相关的广播
     *
     * @param action 广播 Action
     * @return true=是开机相关广播，需要处理
     */
    private boolean isBootRelatedAction(String action) {
        if (action == null) {
            return false;
        }
        return action.equals(ACTION_BOOT_COMPLETED)
                || action.equals(ACTION_LOCKED_BOOT_COMPLETED)
                || action.equals(ACTION_MY_PACKAGE_REPLACED)
                || action.equals(ACTION_QUICKBOOT_POWERON)
                || action.equals(ACTION_QUICKBOOT_POWERON_HTC)
                || action.equals(ACTION_POWER_CONNECTED);
    }

    // ====================================================================
    // 根据广播类型获取延迟时间
    // ====================================================================
    /**
     * 根据广播类型获取延迟启动时间
     *
     * @param action 广播 Action
     * @return 延迟时间（毫秒）
     */
    private long getDelayByAction(String action) {
        if (action == null) {
            return START_DELAY_MS;
        }
        // 应用更新后启动，不需要等太久
        if (action.equals(ACTION_MY_PACKAGE_REPLACED)) {
            return SHORT_DELAY_MS;
        }
        // 其他开机广播，延迟 3 秒，等系统完全准备好
        return START_DELAY_MS;
    }

    // ====================================================================
    // 调度延迟启动（用 AlarmManager 更可靠）
    // ====================================================================
    /**
     * 调度延迟启动应用
     *
     * 【为什么用 AlarmManager 而不是 Handler？】
     * 广播接收器的生命周期很短（只有 10 秒左右），
     * 如果用 Handler postDelayed，可能还没执行完接收器就被销毁了。
     * 用 AlarmManager 设置一个一次性闹钟，更可靠。
     *
     * @param context 上下文
     * @param delayMs 延迟时间（毫秒）
     */
    private void scheduleDelayedStart(Context context, long delayMs) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

            Intent startIntent = new Intent(context, BootStartReceiver.class);
            startIntent.setAction("com.tv.live.START_APP");

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    startIntent,
                    getPendingIntentFlags()
            );

            long triggerAt = System.currentTimeMillis() + delayMs;

            // 设置一次性闹钟
            if (alarmManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Android 6.0+ 使用 setExactAndAllowWhileIdle
                    // 即使在低电耗模式下也能触发
                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAt,
                            pendingIntent
                    );
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    // Android 4.4+ 使用 setExact
                    alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            triggerAt,
                            pendingIntent
                    );
                } else {
                    // 低版本使用 set
                    alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            triggerAt,
                            pendingIntent
                    );
                }
                Log.d(TAG, "已设置延迟启动闹钟，" + delayMs + "ms 后启动");
            }

        } catch (Exception e) {
            Log.e(TAG, "设置延迟启动失败，尝试直接启动", e);
            // 兜底方案：直接启动（可能失败，但总比不启动好）
            try {
                startMainActivity(context);
            } catch (Exception e2) {
                Log.e(TAG, "直接启动也失败", e2);
            }
        }
    }

    // ====================================================================
    // 获取 PendingIntent 的 flags（适配不同安卓版本）
    // ====================================================================
    /**
     * 获取 PendingIntent 的 flags
     * 适配不同安卓版本：
     * - Android 12+ 必须指定 IMMUTABLE 或 MUTABLE
     * - 低版本使用 FLAG_UPDATE_CURRENT
     *
     * @return PendingIntent flags
     */
    private int getPendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // Android 6.0+ 可以加上 IMMUTABLE，更安全
        // 注意：Android 12+ 必须指定 IMMUTABLE 或 MUTABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    // ====================================================================
    // 启动主页面（兜底方法）
    // ====================================================================
    /**
     * 直接启动主页面
     *
     * @param context 上下文
     */
    private void startMainActivity(Context context) {
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // 加上从广播启动的标志
            mainIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.startActivity(mainIntent);
            Log.d(TAG, "已启动 MainActivity");
        } catch (Exception e) {
            Log.e(TAG, "启动 MainActivity 失败", e);
        }
    }
}
