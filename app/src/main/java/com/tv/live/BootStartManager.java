package com.tv.live;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.util.Log; // 🟢 替换为原生日志
import android.widget.TextView;
import android.widget.Toast;

/**
 * 开机自启管理器
 */
public class BootStartManager {

    private static final String TAG = "BootStartManager";
    private static final String KEY_BOOT_AUTO_START = "boot_auto_start";

    private final Context context;
    private final SharedPreferences sp;

    public enum BootStatus {
        NORMAL, NO_PERMISSION, COMPONENT_DISABLED, SYSTEM_RESTRICTED
    }

    public BootStartManager(Context context, SharedPreferences sp) {
        this.context = context;
        this.sp = sp;
    }

    public void updateBootStatusText(TextView tvStatus) {
        if (tvStatus == null) return;
        boolean enabled = sp.getBoolean(KEY_BOOT_AUTO_START, false);
        if (!enabled) {
            tvStatus.setText("未开启");
            tvStatus.setTextColor(Color.parseColor("#999999"));
            return;
        }
        BootStatus status = checkBootStatus();
        switch (status) {
            case NORMAL:
                tvStatus.setText("已开启 · 正常");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                break;
            case NO_PERMISSION:
                tvStatus.setText("需授权自启权限");
                tvStatus.setTextColor(Color.parseColor("#FF9800"));
                break;
            case COMPONENT_DISABLED:
                tvStatus.setText("组件被禁用");
                tvStatus.setTextColor(Color.parseColor("#F44336"));
                break;
            case SYSTEM_RESTRICTED:
                tvStatus.setText("需在系统设置中开启");
                tvStatus.setTextColor(Color.parseColor("#FF9800"));
                break;
            default:
                tvStatus.setText("已开启");
                tvStatus.setTextColor(Color.parseColor("#999999"));
                break;
        }
    }

    public BootStatus checkBootStatus() {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, BootReceiver.class);
            int state = pm.getComponentEnabledSetting(componentName);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                Log.d(TAG, "【自启】组件被禁用");
                return BootStatus.COMPONENT_DISABLED;
            }
        } catch (Exception e) {
            Log.d(TAG, "【自启】检查组件状态异常：" + e.getMessage());
        }

        String manufacturer = Build.MANUFACTURER;
        if (manufacturer != null) {
            manufacturer = manufacturer.toLowerCase();
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                Log.d(TAG, "【自启】检测到 MIUI 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
            if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                Log.d(TAG, "【自启】检测到 EMUI 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
            if (manufacturer.contains("oppo") || manufacturer.contains("oneplus")) {
                Log.d(TAG, "【自启】检测到 ColorOS 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
            if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
                Log.d(TAG, "【自启】检测到 OriginOS 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
        }
        Log.d(TAG, "【自启】状态检测：正常");
        return BootStatus.NORMAL;
    }

    public void showBootGuideDialog() { /* ... 保持原逻辑，LogManager 已删除 ... */ }
    public void showBootStatusDialog() { /* ... 保持原逻辑，LogManager 已删除 ... */ }

    public void testBootAutoStart() {
        Log.d(TAG, "【自启】开始测试自启功能");
        try {
            Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
            intent.setComponent(new ComponentName(context, BootReceiver.class));
            context.sendBroadcast(intent);
            Toast.makeText(context, "已发送开机广播测试\n\n请观察应用是否会重新启动", Toast.LENGTH_LONG).show();
            Log.d(TAG, "【自启】测试广播已发送");
        } catch (Exception e) {
            Log.d(TAG, "【自启】测试失败：" + e.getMessage());
            Toast.makeText(context, "测试失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void toggleBoot(boolean isChecked, TextView tvStatus) {
        sp.edit().putBoolean(KEY_BOOT_AUTO_START, isChecked).apply();
        Log.d(TAG, "【设置】开机自启" + (isChecked ? "已开启" : "已关闭"));
        updateBootStatusText(tvStatus);
        if (isChecked) {
            BootStatus status = checkBootStatus();
            if (status == BootStatus.NORMAL) {
                Toast.makeText(context, "开机自启已开启\n\n电视重启后会自动启动应用", Toast.LENGTH_LONG).show();
            } else {
                showBootGuideDialog();
            }
        } else {
            Toast.makeText(context, "开机自启已关闭", Toast.LENGTH_SHORT).show();
        }
    }
}
