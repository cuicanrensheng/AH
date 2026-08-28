package com.tv.live;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import com.tv.live.util.LogBridge; // 🟢 替换为原生日志
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

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
                LogBridge.d(TAG, "【自启】组件被禁用");
                return BootStatus.COMPONENT_DISABLED;
            }
        } catch (Exception e) {
            LogBridge.d(TAG, "【自启】检查组件状态异常：" + e.getMessage());
        }

        String manufacturer = Build.MANUFACTURER;
        String brand = Build.BRAND;
        if (manufacturer != null) {
            manufacturer = manufacturer.toLowerCase(Locale.ROOT);
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                LogBridge.d(TAG, "【自启】检测到 MIUI 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
            if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                LogBridge.d(TAG, "【自启】检测到 EMUI 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
            if (manufacturer.contains("oppo") || manufacturer.contains("oneplus")) {
                LogBridge.d(TAG, "【自启】检测到 ColorOS 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
            if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
                LogBridge.d(TAG, "【自启】检测到 OriginOS 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
            // 创维 / 酷开 / 康佳等同属国产电视阵营，系统限制类似
            if (manufacturer.contains("skyworth") || manufacturer.contains("coocaa")
                    || (brand != null && (brand.toLowerCase(Locale.ROOT).contains("skyworth")
                                          || brand.toLowerCase(Locale.ROOT).contains("coocaa")))) {
                LogBridge.d(TAG, "【自启】检测到创维/酷开系统，需手动开启自启");
                BootReceiver.writeBootLog(context, "checkBootStatus: 检测到创维/酷开系统，需系统自启白名单授权");
                return BootStatus.SYSTEM_RESTRICTED;
            }
        }
        LogBridge.d(TAG, "【自启】状态检测：正常");
        BootReceiver.writeBootLog(context, "checkBootStatus: 状态正常，广播接收组件可用");
        return BootStatus.NORMAL;
    }

    /**
     * 显示系统自启授权引导对话框
     *
     * 创维/酷开/小米/华为等国产电视 ROM 自带"自启管理"白名单，
     * 即使应用内开关已打开，系统也可能拦截 BOOT_COMPLETED 广播。
     * 必须引导用户到系统设置中手动允许本应用开机自启，自启才会生效。
     */
    public void showBootGuideDialog() {
        if (!(context instanceof android.app.Activity)) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("检测到当前系统会拦截第三方应用的开机广播。\n");
            sb.append("仅开启应用内开关还不够，请在系统设置中允许本应用开机自启：\n\n");

            String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
            String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.ROOT);
            if (manufacturer.contains("skyworth") || brand.contains("skyworth")
                    || manufacturer.contains("coocaa") || brand.contains("coocaa")) {
                sb.append("【创维/酷开电视】\n");
                sb.append("① 设置 → 应用管理 → 应用权限管理\n");
                sb.append("② 找到本应用，允许「开机自启」/「后台自启」\n");
                sb.append("（部分机型需在「酷开应用圈」或系统管家中授权）\n\n");
            } else if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                sb.append("【小米电视】\n设置 → 应用 → 应用管理 → 找到本应用\n→ 自启动 → 允许\n\n");
            } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                sb.append("【华为/荣耀电视】\n设置 → 应用 → 应用启动管理\n→ 关闭「自动管理」，手动开启自启动\n\n");
            } else if (manufacturer.contains("oppo") || manufacturer.contains("oneplus")) {
                sb.append("【OPPO/一加电视】\n设置 → 应用管理 → 自启动管理 → 允许\n\n");
            } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
                sb.append("【vivo电视】\n设置 → 应用与权限 → 权限管理 → 自启动 → 允许\n\n");
            } else {
                sb.append("设置 → 应用/应用管理 → 找到本应用\n→ 允许「开机自启动」「后台运行」\n\n");
            }

            sb.append("设置完成后重启电视，应用即可自动启动。\n\n");
            sb.append("若系统设置中没有相关选项，说明系统不自带自启管理，");
            sb.append("应用已通过多广播兜底（开机/屏幕唤醒/网络/存储挂载）自动拉起。");

            new AlertDialog.Builder(context)
                    .setTitle("需在系统设置中授权自启")
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .show();
            LogBridge.d(TAG, "【自启】已显示系统自启授权引导");
            BootReceiver.writeBootLog(context, "showBootGuideDialog: 已引导用户在系统设置中授权自启");
        } catch (Exception e) {
            LogBridge.d(TAG, "【自启】显示引导对话框失败：" + e.getMessage());
            BootReceiver.writeBootLog(context, "showBootGuideDialog 失败: " + e.getMessage());
        }
    }

    /**
     * 显示开机自启状态详情对话框（诊断用）
     */
    public void showBootStatusDialog() {
        if (!(context instanceof android.app.Activity)) {
            return;
        }
        try {
            boolean enabled = sp.getBoolean(KEY_BOOT_AUTO_START, false);
            BootStatus status = checkBootStatus();
            StringBuilder sb = new StringBuilder();
            sb.append("应用内开关：").append(enabled ? "已开启" : "未开启").append("\n");
            sb.append("自启条件：");
            switch (status) {
                case NORMAL:
                    sb.append("正常（已具备接收开机广播条件）");
                    break;
                case NO_PERMISSION:
                    sb.append("缺少开机广播权限");
                    break;
                case COMPONENT_DISABLED:
                    sb.append("广播接收组件被系统禁用");
                    break;
                case SYSTEM_RESTRICTED:
                    sb.append("需在系统自启管理中授权");
                    break;
                default:
                    sb.append("未知");
            }
            sb.append("\n\n设备：").append(Build.MANUFACTURER).append(" / ").append(Build.MODEL);
            sb.append("\n系统：Android ").append(Build.VERSION.RELEASE)
              .append(" (API ").append(Build.VERSION.SDK_INT).append(")");
            sb.append("\n\n提示：开机自启依赖系统广播，若长时间无效，");
            sb.append("请在系统自启管理中允许本应用，并避免使用「一键清理」关闭本应用。");

            new AlertDialog.Builder(context)
                    .setTitle("开机自启状态")
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .show();
            LogBridge.d(TAG, "【自启】已显示状态详情");
            BootReceiver.writeBootLog(context, "showBootStatusDialog: 已显示自启状态详情");
        } catch (Exception e) {
            LogBridge.d(TAG, "【自启】显示状态对话框失败：" + e.getMessage());
            BootReceiver.writeBootLog(context, "showBootStatusDialog 失败: " + e.getMessage());
        }
    }

    public void testBootAutoStart() {
        LogBridge.d(TAG, "【自启】开始测试自启功能");
        BootReceiver.writeBootLog(context, "testBootAutoStart: 手动测试，发送 BOOT_COMPLETED 广播");
        try {
            Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
            intent.setComponent(new ComponentName(context, BootReceiver.class));
            context.sendBroadcast(intent);
            Toast.makeText(context, "已发送开机广播测试\n\n请观察应用是否会重新启动", Toast.LENGTH_LONG).show();
            LogBridge.d(TAG, "【自启】测试广播已发送");
            BootReceiver.writeBootLog(context, "testBootAutoStart: BOOT_COMPLETED 测试广播已发送");
        } catch (Exception e) {
            LogBridge.d(TAG, "【自启】测试失败：" + e.getMessage());
            BootReceiver.writeBootLog(context, "testBootAutoStart 发送失败: " + e.getMessage());
            Toast.makeText(context, "测试失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void toggleBoot(boolean isChecked, TextView tvStatus) {
        sp.edit().putBoolean(KEY_BOOT_AUTO_START, isChecked).apply();
        LogBridge.d(TAG, "【设置】开机自启" + (isChecked ? "已开启" : "已关闭"));
        BootReceiver.writeBootLog(context, "toggleBoot: 用户" + (isChecked ? "开启" : "关闭") + "应用内自启开关");
        updateBootStatusText(tvStatus);
        if (isChecked) {
            // 开启自启时立即拉起常驻保活服务（START_STICKY）
            try {
                Intent svc = new Intent(context, BootStartForegroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
                BootReceiver.writeBootLog(context, "toggleBoot: 已启动常驻保活服务");
            } catch (Throwable t) {
                LogBridge.w(TAG, "启动保活服务失败: " + t.getMessage());
            }

            BootStatus status = checkBootStatus();
            if (status == BootStatus.NORMAL) {
                Toast.makeText(context, "开机自启已开启\n\n电视重启后会自动启动应用", Toast.LENGTH_LONG).show();
            } else {
                showBootGuideDialog();
            }
        } else {
            // 关闭自启时停止常驻保活服务
            try {
                context.stopService(new Intent(context, BootStartForegroundService.class));
                BootReceiver.writeBootLog(context, "toggleBoot: 已停止常驻保活服务");
            } catch (Throwable t) {
                LogBridge.w(TAG, "停止保活服务失败: " + t.getMessage());
            }
            Toast.makeText(context, "开机自启已关闭", Toast.LENGTH_SHORT).show();
        }
    }
}
