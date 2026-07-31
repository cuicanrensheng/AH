package com.tv.live;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * JobScheduler 兜底服务
 *
 * 【作用】
 * 当 AlarmManager 被国产电视系统（创维/酷开/小米等）限制时，
 * JobScheduler 作为兜底方案，在系统允许的时机启动应用。
 *
 * 【执行逻辑】
 * 1. 接收 JobScheduler 调度
 * 2. 尝试直接启动 MainActivity
 * 3. 如果失败，尝试前台服务方式
 * 4. 无论成功与否都调用 jobFinished，释放资源
 */
public class BootJobService extends JobService {

    private static final String TAG = "BootJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "JobScheduler 触发启动");

        // 在子线程中执行启动，避免阻塞主线程
        new Thread(() -> {
            boolean success = false;

            // 方案 1：直接启动 Activity
            success = startActivityDirectly();
            if (success) {
                Log.d(TAG, "JobScheduler 直接启动成功");
                jobFinished(params, false);
                return;
            }

            // 方案 2：Launcher 方式启动
            success = startActivityAsLauncher();
            if (success) {
                Log.d(TAG, "JobScheduler Launcher 方式启动成功");
                jobFinished(params, false);
                return;
            }

            // 方案 3：前台服务兜底（Android 10+ 后台启动限制）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                success = startWithForegroundService();
                if (success) {
                    Log.d(TAG, "JobScheduler 前台服务方式启动成功");
                    jobFinished(params, false);
                    return;
                }
            }

            Log.e(TAG, "JobScheduler 所有启动方案均失败");
            jobFinished(params, false);
        }).start();

        // 返回 true 表示任务在异步线程中执行
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.w(TAG, "JobScheduler 任务被系统中断");
        // 返回 true 表示希望系统稍后重试
        return true;
    }

    private boolean startActivityDirectly() {
        try {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT);
            }
            startActivity(mainIntent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "直接启动失败", e);
            return false;
        }
    }

    private boolean startActivityAsLauncher() {
        try {
            Intent mainIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (mainIntent != null) {
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(mainIntent);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Launcher 方式启动失败", e);
        }
        return false;
    }

    /**
     * Android 10+ 后台启动限制时，先启动一个临时前台服务，再从中启动 Activity
     */
    private boolean startWithForegroundService() {
        try {
            Intent serviceIntent = new Intent(this, BootStartForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d(TAG, "已启动前台服务兜底");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "前台服务启动失败", e);
            return false;
        }
    }
}
