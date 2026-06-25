package com.tv.live;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 开机自启管理器
 *
 * 【职责】
 * 负责所有开机自启相关的逻辑，包括：
 * 1. 状态检测（组件状态、权限状态、系统限制）
 * 2. 状态文本更新（显示在开关下面）
 * 3. 引导对话框（告诉用户怎么开启自启）
 * 4. 详细状态对话框（长按触发，高级功能）
 * 5. 测试功能（模拟发送开机广播）
 *
 * 【为什么拆分？】
 * 原来的 SettingsActivity 有 1000+ 行，代码太臃肿。
 * 把开机自启相关的 6 个方法 + 1 个枚举拆分出来，
 * SettingsActivity 只负责 UI 展示和用户交互。
 *
 * 【五层逻辑范式】
 * 1. 状态管理层：维护自启开关状态、组件状态
 * 2. 数据检测层：检测组件、权限、系统限制
 * 3. 状态同步层：UI 文本与实际状态同步
 * 4. 异常兜底层：检测异常兜底、不同厂商兼容
 * 5. 交互闭环层：点击/长按/测试 都有对应反馈
 */
public class BootStartManager {

    // ====================== 常量 ======================
    /** SP Key：开机自启开关 */
    private static final String KEY_BOOT_AUTO_START = "boot_auto_start";

    // ====================== 成员变量 ======================
    /** 上下文 */
    private final Context context;
    /** SharedPreferences */
    private final SharedPreferences sp;

    // ====================================================================
    // 开机自启状态枚举
    // ====================================================================
    /**
     * 开机自启状态枚举
     * 【作用】统一表示自启的各种状态，方便判断和处理
     */
    public enum BootStatus {
        NORMAL,          // 正常，应该可以自启
        NO_PERMISSION,   // 没有自启权限
        COMPONENT_DISABLED, // 组件被禁用
        SYSTEM_RESTRICTED  // 系统限制（厂商自启管理）
    }

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     * @param context 上下文
     * @param sp SharedPreferences 实例
     */
    public BootStartManager(Context context, SharedPreferences sp) {
        this.context = context;
        this.sp = sp;
    }

    // ====================================================================
    // 1. 更新开机自启状态文本
    // ====================================================================
    /**
     * 更新开机自启状态文本
     * 显示在开关下面，让用户一目了然
     *
     * @param tvStatus 状态文本控件
     */
    public void updateBootStatusText(TextView tvStatus) {
        if (tvStatus == null) {
            return;
        }

        boolean enabled = sp.getBoolean(KEY_BOOT_AUTO_START, false);
        if (!enabled) {
            tvStatus.setText("未开启");
            tvStatus.setTextColor(Color.parseColor("#999999"));
            return;
        }

        // 已开启：检查实际状态并显示
        BootStatus status = checkBootStatus();

        switch (status) {
            case NORMAL:
                // ✅ 正常：应该可以自启
                tvStatus.setText("已开启 · 正常");
                tvStatus.setTextColor(Color.parseColor("#4CAF50")); // 绿色
                break;

            case NO_PERMISSION:
                // ⚠️ 没有自启权限
                tvStatus.setText("需授权自启权限");
                tvStatus.setTextColor(Color.parseColor("#FF9800")); // 橙色
                break;

            case COMPONENT_DISABLED:
                // ❌ 组件被禁用
                tvStatus.setText("组件被禁用");
                tvStatus.setTextColor(Color.parseColor("#F44336")); // 红色
                break;

            case SYSTEM_RESTRICTED:
                // ⚠️ 系统限制（厂商自启管理）
                tvStatus.setText("需在系统设置中开启");
                tvStatus.setTextColor(Color.parseColor("#FF9800")); // 橙色
                break;

            default:
                // 默认状态
                tvStatus.setText("已开启");
                tvStatus.setTextColor(Color.parseColor("#999999"));
                break;
        }
    }

    // ====================================================================
    // 2. 检测开机自启状态
    // ====================================================================
    /**
     * 检测开机自启的实际状态
     *
     * 【检测维度】
     * 1. 组件状态：BootReceiver 是否被禁用
     * 2. 权限状态：是否有自启权限
     * 3. 系统限制：是否被厂商自启管理限制
     *
     * @return 自启状态枚举
     */
    public BootStatus checkBootStatus() {
        // 1. 先检查组件是否被禁用
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, BootReceiver.class);
            int state = pm.getComponentEnabledSetting(componentName);

            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                LogManager.logOperation("【自启】组件被禁用");
                return BootStatus.COMPONENT_DISABLED;
            }
        } catch (Exception e) {
            LogManager.logOperation("【自启】检查组件状态异常：" + e.getMessage());
        }

        // 2. 检查是否是 MIUI、EMUI 等有自启管理的系统
        String manufacturer = Build.MANUFACTURER;
        if (manufacturer != null) {
            manufacturer = manufacturer.toLowerCase();

            // 小米 MIUI
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                LogManager.logOperation("【自启】检测到 MIUI 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }

            // 华为 EMUI / HarmonyOS
            if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                LogManager.logOperation("【自启】检测到 EMUI 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }

            // OPPO ColorOS
            if (manufacturer.contains("oppo") || manufacturer.contains("oneplus")) {
                LogManager.logOperation("【自启】检测到 ColorOS 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }

            // vivo OriginOS
            if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
                LogManager.logOperation("【自启】检测到 OriginOS 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
        }

        // 3. 检查 Android 10+ 后台启动限制
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 有后台启动限制，但电视应用一般不受影响
            // 这里暂时不做特殊处理，标记为正常
        }

        // 4. 都没问题，返回正常状态
        LogManager.logOperation("【自启】状态检测：正常");
        return BootStatus.NORMAL;
    }

    // ====================================================================
    // 3. 显示开机自启引导对话框
    // ====================================================================
    /**
     * 显示自启引导对话框
     */
    public void showBootGuideDialog() {
        BootStatus status = checkBootStatus();

        String title = "开机自启设置";
        String message;

        switch (status) {
            case NO_PERMISSION:
                message = "检测到您的设备需要手动授权自启权限。\n\n"
                        + "请在系统设置中找到本应用，开启「自启动」权限。\n\n"
                        + "不同品牌的设置路径可能不同，一般在：\n"
                        + "「安全中心」→「应用管理」→「自启动管理」";
                break;

            case COMPONENT_DISABLED:
                message = "检测到开机自启组件被禁用。\n\n"
                        + "这可能是安全软件或系统优化工具导致的。\n\n"
                        + "请检查安全软件的「自启管理」或「开机加速」设置，"
                        + "将本应用加入白名单。";
                break;

            case SYSTEM_RESTRICTED:
                message = "您的设备有系统级的自启管理，需要手动开启。\n\n"
                        + "请按以下步骤操作：\n"
                        + "1. 打开系统「设置」\n"
                        + "2. 找到「应用管理」或「安全中心」\n"
                        + "3. 找到「自启动管理」\n"
                        + "4. 找到本应用，开启自启动权限\n\n"
                        + "开启后电视重启就能自动启动应用了~";
                break;

            default:
                message = "开机自启已开启，电视重启后会自动启动应用。\n\n"
                        + "如果重启后没有自动启动，请检查系统的自启管理设置。";
                break;
        }

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("我知道了", null)
                .setNeutralButton("测试自启", (dialog, which) -> {
                    testBootAutoStart();
                })
                .show();
    }

    // ====================================================================
    // 4. 显示开机自启详细状态对话框
    // ====================================================================
    /**
     * 显示自启详细状态对话框（长按触发）
     */
    public void showBootStatusDialog() {
        BootStatus status = checkBootStatus();

        StringBuilder sb = new StringBuilder();
        sb.append("【自启状态检测】\n\n");

        // 开关状态
        boolean enabled = sp.getBoolean(KEY_BOOT_AUTO_START, false);
        sb.append("开关状态：").append(enabled ? "已开启" : "未开启").append("\n");

        // 组件状态
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, BootReceiver.class);
            int state = pm.getComponentEnabledSetting(componentName);
            String stateStr;
            switch (state) {
                case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                    stateStr = "已启用";
                    break;
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                    stateStr = "已禁用";
                    break;
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                    stateStr = "用户禁用";
                    break;
                case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                    stateStr = "默认";
                    break;
                default:
                    stateStr = "未知(" + state + ")";
                    break;
            }
            sb.append("组件状态：").append(stateStr).append("\n");
        } catch (Exception e) {
            sb.append("组件状态：检测失败(").append(e.getMessage()).append(")\n");
        }

        // 系统版本
        sb.append("系统版本：Android ").append(Build.VERSION.RELEASE).append(" (API ")
                .append(Build.VERSION.SDK_INT).append(")\n");

        // 厂商
        sb.append("设备厂商：").append(Build.MANUFACTURER).append("\n");
        sb.append("设备型号：").append(Build.MODEL).append("\n");

        sb.append("\n【状态说明】\n");
        sb.append("检测结果：").append(status.name()).append("\n\n");

        sb.append("【测试功能】\n");
        sb.append("点击「测试自启」可以模拟开机广播，测试是否能正常启动。");

        new AlertDialog.Builder(context)
                .setTitle("📊 自启状态详情")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .setNeutralButton("测试自启", (dialog, which) -> {
                    testBootAutoStart();
                })
                .show();
    }

    // ====================================================================
    // 5. 测试开机自启
    // ====================================================================
    /**
     * 测试开机自启功能
     *
     * 【原理】
     * 手动发送一个 BOOT_COMPLETED 广播，
     * 模拟电视开机时的场景，看看能不能正常启动。
     */
    public void testBootAutoStart() {
        LogManager.logOperation("【自启】开始测试自启功能");

        try {
            // 模拟发送开机广播
            Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
            intent.setComponent(new ComponentName(context, BootReceiver.class));
            context.sendBroadcast(intent);

            Toast.makeText(context, "已发送开机广播测试\n\n请观察应用是否会重新启动",
                    Toast.LENGTH_LONG).show();

            LogManager.logOperation("【自启】测试广播已发送");
        } catch (Exception e) {
            LogManager.logOperation("【自启】测试失败：" + e.getMessage());
            Toast.makeText(context, "测试失败：" + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ====================================================================
    // 6. 切换开机自启开关
    // ====================================================================
    /**
     * 切换开机自启开关
     *
     * @param isChecked 新的开关状态
     * @param tvStatus 状态文本控件（用于更新显示）
     */
    public void toggleBoot(boolean isChecked, TextView tvStatus) {
        // 保存开关状态
        sp.edit().putBoolean(KEY_BOOT_AUTO_START, isChecked).apply();

        // 记录日志
        LogManager.logOperation("【设置】开机自启" + (isChecked ? "已开启" : "已关闭"));

        // 更新状态文本
        updateBootStatusText(tvStatus);

        // 显示提示
        if (isChecked) {
            // 开启时，检测自启状态并提示
            BootStatus status = checkBootStatus();
            if (status == BootStatus.NORMAL) {
                Toast.makeText(context, "开机自启已开启\n\n电视重启后会自动启动应用",
                        Toast.LENGTH_LONG).show();
            } else {
                // 有问题，显示详细引导
                showBootGuideDialog();
            }
        } else {
            Toast.makeText(context, "开机自启已关闭", Toast.LENGTH_SHORT).show();
        }
    }

}
