package com.tv.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import com.tv.live.util.LogBridge;

/**
 * 开机自启 + 常驻保活前台服务
 *
 * 【作用】
 * 1. Android 10+ 对后台启动 Activity 有严格限制，
 *    先启动一个前台服务（有通知栏），再从前台服务中启动 Activity，
 *    系统更可能允许。
 * 2. 常驻保活：返回 START_STICKY。进程被系统回收
 *    （酷开等电视系统待机/内存清理时会杀掉第三方后台进程）后，
 *    系统会在条件允许时自动重建本服务进程，并再次进入
 *    onStartCommand 拉起 MainActivity，实现"待机唤醒后自动回到应用"。
 *
 * 【生命周期】
 * 启动后拉起 MainActivity，并保持前台服务常驻（不自我停止），
 * 进程被杀后由 START_STICKY 机制重建。
 */
public class BootStartForegroundService extends Service {

    private static final String TAG = "BootStartFgService";
    private static final String CHANNEL_ID = "boot_start_channel";
    private static final int NOTIFICATION_ID = 2001;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundInternal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogBridge.d(TAG, "保活服务启动(sticky)，尝试拉起 MainActivity");

        // 尝试启动 MainActivity
        boolean success = startMainActivity();

        if (!success) {
            // 如果直接启动失败，延迟 500ms 再试一次（等通知栏完全展示）
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            startMainActivity();
        }

        // 常驻：不自我停止。START_STICKY 保证进程被系统回收后重建本服务，
        // 重建时 intent 为 null，仍会再次进入本方法拉起 MainActivity。
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "开机自启",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于提高开机自启成功率");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(getString(R.string.app_name))
                .setContentText("后台保活运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setAutoCancel(true);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private boolean startMainActivity() {
        try {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT);
            }
            startActivity(mainIntent);
            LogBridge.d(TAG, "从前台服务启动 MainActivity 成功");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "从前台服务启动 MainActivity 失败", e);
            return false;
        }
    }
}
