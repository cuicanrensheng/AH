package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // 1. 读取开关状态（和 SettingsActivity 保持一致）
            SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            boolean autoStart = sp.getBoolean("boot_auto_start", false);

            if (autoStart) {
                // 2. 启动 MainActivity
                Intent mainIntent = new Intent(context, MainActivity.class);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(mainIntent);
            }
        }
    }
}
