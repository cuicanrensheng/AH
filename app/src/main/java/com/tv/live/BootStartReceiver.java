package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启 - 实际启动执行器
 *
 * 【作用】
 * 接收 BootReceiver 通过 AlarmManager 发送的延迟启动广播，
 * 实际执行启动应用的操作。
 *
 * 【为什么要拆成两个 Receiver？】
 * 1. BootReceiver：负责接收开机广播，调度延迟启动
 * 2. BootStartReceiver：负责实际启动应用
 *
 * 这样职责更清晰，而且可以避免广播接收器生命周期太短导致的问题。
 *
 * 【适配 Android 10+ 后台启动限制】
 * Android 10 及以上版本对后台启动 Activity 有严格限制，
 * 但电视设备通常有例外，或者可以通过以下方式绕过：
 * 1. 使用 full-screen intent
 * 2. 启动前台服务后再启动 Activity
 * 3. 电视设备（Android TV）通常不受限制
 *
 * 这里我们做了多重兜底，确保能启动成功。
 */
public class BootStartReceiver extends BroadcastReceiver {

    private static final String TAG = "BootStartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        String action = intent.getAction();
        Log.d(TAG, "收到启动广播：" + action);

        if (!"com.tv.live.START_APP".equals(action)) {
            return;
        }

        Log.d(TAG, "开始启动应用...");

        // ====================================================================
        // 多重兜底方案，确保能启动成功
        // ====================================================================

        // 方案 1：直接启动 Activity（大多数情况都能成功）
        boolean success = startActivityDirectly(context);

        if (success) {
            Log.d(TAG, "方案 1 成功：直接启动 Activity");
            return;
        }

        // 方案 2：先启动前台服务，再从服务里启动 Activity
        // （Android 10+ 后台启动限制的兜底方案）
        Log.w(TAG, "方案 1 失败，尝试方案 2：启动前台服务");
        success = startForegroundServiceThenActivity(context);

        if (success) {
            Log.d(TAG, "方案 2 成功：通过前台服务启动");
            return;
        }

        // 方案 3：用 FLAG_ACTIVITY_NEW_TASK + FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        Log.w(TAG, "方案 2 失败，尝试方案 3：特殊标志启动");
        success = startActivityWithSpecialFlags(context);

        if (success) {
            Log.d(TAG, "方案 3 成功：特殊标志启动");
            return;
        }

        Log.e(TAG, "所有方案都失败，启动应用失败");
    }

    // ====================================================================
    // 方案 1：直接启动 Activity（最常用）
    // ====================================================================
    /**
     * 直接启动 MainActivity
     *
     * @param context 上下文
     * @return true=启动成功，false=启动失败
     */
    private boolean startActivityDirectly(Context context) {
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

            // Android 11+ 可以加上这个标志，提高后台启动成功率
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT);
            }

            context.startActivity(mainIntent);
            Log.d(TAG, "直接启动 Activity 成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "直接启动 Activity 失败", e);
            return false;
        }
    }

    // ====================================================================
    // 方案 2：启动前台服务（Android 10+ 后台启动限制兜底）
    // ====================================================================
    /**
     * 先启动前台服务，再从服务里启动 Activity
     *
     * 【原理】
     * Android 10+ 限制后台启动 Activity，但前台服务不受限制。
     * 先启动一个前台服务，然后从服务里启动 Activity，成功率更高。
     *
     * 注意：这里只是预留方案，实际项目中需要有对应的 Service 类。
     * 如果没有 BootStartService，这个方案会失败，不影响其他方案。
     *
     * @param context 上下文
     * @return true=启动成功，false=启动失败
     */
    private boolean startForegroundServiceThenActivity(Context context) {
        try {
            // 尝试启动前台服务（如果有的话）
            // 注意：实际项目中需要有 BootStartService 这个类
            // 这里只是预留方案，没有的话会抛异常，被 catch 住
            Intent serviceIntent = new Intent();
            serviceIntent.setClassName(context, "com.tv.live.BootStartService");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

            Log.d(TAG, "启动前台服务成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务失败（可能没有这个服务类）", e);
            return false;
        }
    }

    // ====================================================================
    // 方案 3：特殊标志启动（最后的兜底）
    // ====================================================================
    /**
     * 用特殊的 Intent 标志启动 Activity
     *
     * 【原理】
     * 加上各种可能的标志，尝试绕过后台启动限制。
     * 这是最后的兜底方案，能启动就启动，不能启动也没办法。
     *
     * @param context 上下文
     * @return true=启动成功，false=启动失败
     */
    private boolean startActivityWithSpecialFlags(Context context) {
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);

            // 加上 category，提高匹配成功率
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            mainIntent.setAction(Intent.ACTION_MAIN);

            context.startActivity(mainIntent);
            Log.d(TAG, "特殊标志启动成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "特殊标志启动失败", e);
            return false;
        }
    }
}
