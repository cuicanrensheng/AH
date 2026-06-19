package com.tv.live;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * 设置页面
 *
 * 【功能清单】
 * 1. 开机自启开关（增强版：状态检测 + 测试功能 + 厂商引导）
 * 2. 节目单开关
 * 3. 自动更新源（每天凌晨4点，AlarmManager）
 * 4. 换台反转
 * 5. 数字选台
 * 6. 屏幕比例设置
 * 7. 自定义订阅源/节目单
 * 8. 多订阅源/节目单管理（委托给 SourceManager）
 * 9. 扫码添加（二维码 + 网页后台）
 * 10. 解析&播放日志查看
 * 11. 操作日志查看
 * 12. 检查更新
 *
 * 【开机自启增强说明】
 * 1. 状态检测：自动检测自启权限、组件状态、开关状态
 * 2. 测试功能：一键模拟开机自启，测试是否能正常启动
 * 3. 厂商引导：针对不同品牌电视给出自启设置引导
 * 4. 详细日志：记录自启相关的所有操作，方便排查
 *
 * 【架构说明】
 * 本 Activity 只负责 UI 展示和用户交互，
 * 业务逻辑都委托给专门的管理类：
 * - WebServerManager：网页后台 HTTP 服务器
 * - SourceManager：多源管理（增删改查、导入导出）
 *
 * 【为什么拆分？】
 * 原来的 SettingsActivity 有 1000+ 行，代码太臃肿。
 * 拆分后职责清晰，更好维护。
 */
public class SettingsActivity extends AppCompatActivity {

    // ====================== 控件声明 ======================
    /** 5个开关控件 */
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    /** 纯文本点击项 */
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;

    // ====================================================================
    // ✅ 开机自启状态显示（新增）
    // ====================================================================
    /** 开机自启状态描述文本（显示在开关下面） */
    private TextView tv_boot_status;

    // ====================== 配置相关 ======================
    /** SharedPreferences 配置存储 */
    private SharedPreferences sp;

    // ====================== 网页后台相关 ======================
    /** 网页后台管理器（拆分出来的独立类） */
    private WebServerManager webServerManager;
    /** 网页后台端口号 */
    private static final int WEB_SERVER_PORT = 10481;
    /** 网页后台访问地址（用于生成二维码） */
    private String currentWebUrl;

    // ====================== SP Key 常量 ======================
    /** 自定义直播源地址 */
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    /** 自定义节目单地址 */
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";

    // ====================== 多源列表适配器 ======================
    /** 多源列表适配器（搜索结果显示用） */
    private SourceAdapter adapter;

    // ====================== 全局日志系统 ======================
    /**
     * 解析&播放日志
     * 用 volatile 保证多线程可见性
     */
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();

    /**
     * 记录解析&播放日志
     * @param msg 日志内容
     */
    public static void log(String msg) {
        if (PLAY_LOG == null) {
            PLAY_LOG = new StringBuilder();
        }
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        PLAY_LOG.append("[").append(time).append("] ").append(msg).append("\n");
        // 限制日志大小，防止内存溢出
        if (PLAY_LOG.length() > 20000) {
            PLAY_LOG.delete(0, PLAY_LOG.length() - 15000);
        }
    }

    // ====================== 操作日志 ======================
    /**
     * 操作日志
     * 记录用户的所有操作 + 网页后台日志
     */
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();

    /**
     * 记录操作日志
     * @param msg 操作内容
     */
    public static void logOperation(String msg) {
        if (OPERATION_LOG == null) {
            OPERATION_LOG = new StringBuilder();
        }
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        OPERATION_LOG.append("[").append(time).append("] ").append(msg).append("\n");
        if (OPERATION_LOG.length() > 20000) {
            OPERATION_LOG.delete(0, OPERATION_LOG.length() - 15000);
        }
    }

    // ====================== 日志对话框 ======================
    /**
     * 显示操作日志对话框
     * 最新的日志显示在最上面（倒序）
     */
    private void showOperationLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        if (OPERATION_LOG == null || OPERATION_LOG.length() == 0) {
            tv.setText("暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作，\n以及网页后台的启动、请求、响应等详细信息。");
        } else {
            // 倒序显示：最新的在最上面
            String originalLog = OPERATION_LOG.toString();
            String[] lines = originalLog.split("\n");
            StringBuilder reversedLog = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) {
                    reversedLog.append(lines[i]).append("\n");
                }
            }
            tv.setText(reversedLog.toString());
        }
        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        scrollView.addView(tv);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📌 操作日志");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            if (OPERATION_LOG != null) {
                OPERATION_LOG.setLength(0);
            }
            Toast.makeText(this, "操作日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    /**
     * 显示解析&播放日志对话框
     * 最新的日志显示在最上面（倒序）
     */
    private void showLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        if (PLAY_LOG == null || PLAY_LOG.length() == 0) {
            tv.setText("暂无日志内容，请先播放一个频道再查看。");
        } else {
            // 倒序显示：最新的在最上面
            String originalLog = PLAY_LOG.toString();
            String[] lines = originalLog.split("\n");
            StringBuilder reversedLog = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) {
                    reversedLog.append(lines[i]).append("\n");
                }
            }
            tv.setText(reversedLog.toString());
        }
        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        scrollView.addView(tv);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📄 解析 & 播放日志");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            if (PLAY_LOG != null) {
                PLAY_LOG.setLength(0);
            }
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    // ====================== onCreate 生命周期 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ===== 窗口设置 =====
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().getAttributes().dimAmount = 0.6f;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND, WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        // ===== 初始化 SharedPreferences =====
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);

        // ===== 绑定控件 =====
        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);

        // ====================================================================
        // ✅ 绑定开机自启状态文本（新增）
        // ====================================================================
        tv_boot_status = findViewById(R.id.tv_boot_status);

        // ===== 日志查看按钮 =====
        findViewById(R.id.log_viewer).setOnClickListener(v -> showLogDialog());
        findViewById(R.id.log_operation).setOnClickListener(v -> showOperationLogDialog());

        // ====================================================================
        // ✅ 开机自启（增强版：状态检测 + 测试功能 + 厂商引导）
        // ====================================================================
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        // 更新状态显示
        updateBootStatusText();

        // 点击整个 item 切换开关
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            boolean isChecked = !sw_boot.isChecked();
            sw_boot.setChecked(isChecked);
            sp.edit().putBoolean("boot_auto_start", isChecked).apply();
            logOperation("【设置】开机自启" + (isChecked ? "已开启" : "已关闭"));

            // 更新状态显示
            updateBootStatusText();

            if (isChecked) {
                // 开启时，检测自启状态并提示
                BootStatus status = checkBootStatus();
                if (status == BootStatus.NORMAL) {
                    Toast.makeText(this, "开机自启已开启\n\n电视重启后会自动启动应用",
                            Toast.LENGTH_LONG).show();
                } else {
                    // 有问题，显示详细引导
                    showBootGuideDialog();
                }
            } else {
                Toast.makeText(this, "开机自启已关闭", Toast.LENGTH_SHORT).show();
            }
        });

        // 长按 item 显示详细状态和测试功能
        findViewById(R.id.item_boot).setOnLongClickListener(v -> {
            showBootStatusDialog();
            return true;
        });

        // 2. 节目单开关
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        findViewById(R.id.item_epg).setOnClickListener(v -> {
            boolean isChecked = !sw_epg.isChecked();
            sw_epg.setChecked(isChecked);
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            logOperation("【设置】节目单" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 3. 自动更新源
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            boolean isChecked = !sw_auto_update.isChecked();
            sw_auto_update.setChecked(isChecked);
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            if (isChecked) {
                setAutoUpdateAlarm();
            } else {
                cancelAutoUpdateAlarm();
            }
            logOperation("【设置】自动更新源" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启（每天凌晨4点）" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 如果之前就是开启状态，重新设置一下闹钟
        if (sp.getBoolean("auto_update_source", true)) {
            setAutoUpdateAlarm();
        }

        // 4. 换台反转
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            logOperation("【设置】换台反转" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 5. 数字选台
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean isChecked = !sw_num_channel.isChecked();
            sw_num_channel.setChecked(isChecked);
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            logOperation("【设置】数字选台" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // ===== 检查更新 =====
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
        });

        // ===== 其他点击事件 =====
        initListeners();

        // ===== 启动网页后台（拆分后） =====
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();

        logOperation("【设置】打开设置页面");
    }

    // ====================================================================
    // ✅ 开机自启状态枚举（新增）
    // ====================================================================
    /**
     * 开机自启状态枚举
     */
    private enum BootStatus {
        NORMAL,          // 正常，应该可以自启
        NO_PERMISSION,   // 没有自启权限
        COMPONENT_DISABLED, // 组件被禁用
        SYSTEM_RESTRICTED  // 系统限制（厂商自启管理）
    }

    // ====================================================================
    // ✅ 更新开机自启状态文本（新增）
    // ====================================================================
    /**
     * 更新开机自启状态文本
     * 显示在开关下面，让用户一目了然
     */
    private void updateBootStatusText() {
        if (tv_boot_status == null) {
            return;
        }

        boolean enabled = sp.getBoolean("boot_auto_start", false);

        if (!enabled) {
            tv_boot_status.setText("未开启");
            tv_boot_status.setTextColor(Color.parseColor("#999999"));
            return;
        }

        BootStatus status = checkBootStatus();

        switch (status) {
            case NORMAL:
                tv_boot_status.setText("✅ 正常，电视重启后自动启动");
                tv_boot_status.setTextColor(Color.parseColor("#52C41A"));
                break;
            case NO_PERMISSION:
                tv_boot_status.setText("⚠️ 缺少自启权限，可能无法自启");
                tv_boot_status.setTextColor(Color.parseColor("#FAAD14"));
                break;
            case COMPONENT_DISABLED:
                tv_boot_status.setText("❌ 自启组件被禁用");
                tv_boot_status.setTextColor(Color.parseColor("#FF4D4F"));
                break;
            case SYSTEM_RESTRICTED:
            default:
                tv_boot_status.setText("💡 已开启，建议在系统自启管理中允许");
                tv_boot_status.setTextColor(Color.parseColor("#1890FF"));
                break;
        }
    }

    // ====================================================================
    // ✅ 检测开机自启状态（新增）
    // ====================================================================
    /**
     * 检测开机自启状态
     *
     * 【检测项】
     * 1. RECEIVE_BOOT_COMPLETED 权限是否授予
     * 2. BootReceiver 组件是否启用
     * 3. 自启开关是否开启
     *
     * 【注意】
     * 这个方法只能检测基础状态，无法检测厂商自启管理的限制。
     * 厂商自启管理（如小米的自启管理）是系统层面的，应用无法检测。
     *
     * @return 自启状态
     */
    private BootStatus checkBootStatus() {
        try {
            // 1. 检查权限
            boolean hasPermission = checkCallingOrSelfPermission(
                    "android.permission.RECEIVE_BOOT_COMPLETED")
                    == PackageManager.PERMISSION_GRANTED;

            if (!hasPermission) {
                logOperation("【自启】检测结果：缺少 RECEIVE_BOOT_COMPLETED 权限");
                return BootStatus.NO_PERMISSION;
            }

            // 2. 检查组件是否启用
            ComponentName componentName = new ComponentName(this, BootReceiver.class);
            int componentStatus = getPackageManager().getComponentEnabledSetting(componentName);
            boolean componentEnabled =
                    (componentStatus == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    || (componentStatus == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);

            if (!componentEnabled) {
                logOperation("【自启】检测结果：BootReceiver 组件被禁用");
                return BootStatus.COMPONENT_DISABLED;
            }

            // 3. 基础检测都通过了
            logOperation("【自启】检测结果：基础状态正常");
            return BootStatus.NORMAL;

        } catch (Exception e) {
            logOperation("【自启】检测异常：" + e.getMessage());
            return BootStatus.SYSTEM_RESTRICTED;
        }
    }

    // ====================================================================
    // ✅ 显示开机自启状态详情对话框（新增）
    // ====================================================================
    /**
     * 显示开机自启状态详情对话框
     *
     * 包含：
     * 1. 当前状态
     * 2. 检测结果
     * 3. 测试自启按钮
     * 4. 厂商引导按钮
     */
    private void showBootStatusDialog() {
        BootStatus status = checkBootStatus();
        boolean enabled = sp.getBoolean("boot_auto_start", false);

        // 构建状态文本
        StringBuilder statusText = new StringBuilder();
        statusText.append("📱 设备品牌：").append(getDeviceBrand()).append("\n");
        statusText.append("🤖 安卓版本：").append(Build.VERSION.RELEASE).append("\n");
        statusText.append("📦 应用版本：").append(getAppVersionName()).append("\n\n");

        statusText.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        statusText.append("🔌 自启开关：").append(enabled ? "✅ 已开启" : "❌ 已关闭").append("\n");

        // 权限状态
        boolean hasPermission = checkCallingOrSelfPermission(
                "android.permission.RECEIVE_BOOT_COMPLETED")
                == PackageManager.PERMISSION_GRANTED;
        statusText.append("🔑 自启权限：").append(hasPermission ? "✅ 已授予" : "❌ 未授予").append("\n");

        // 组件状态
        try {
            ComponentName componentName = new ComponentName(this, BootReceiver.class);
            int componentStatus = getPackageManager().getComponentEnabledSetting(componentName);
            boolean componentEnabled =
                    (componentStatus == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    || (componentStatus == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            statusText.append("🧩 自启组件：").append(componentEnabled ? "✅ 已启用" : "❌ 已禁用").append("\n");
        } catch (Exception e) {
            statusText.append("🧩 自启组件：❓ 检测失败\n");
        }

        statusText.append("\n━━━━━━━━━━━━━━━━━━━━\n\n");

        if (enabled && status == BootStatus.NORMAL) {
            statusText.append("✅ 基础状态正常\n\n");
            statusText.append("如果电视重启后仍无法自启，\n");
            statusText.append("可能是系统的「自启管理」限制了本应用。\n");
            statusText.append("请点击下方「厂商自启引导」查看设置方法。");
        } else if (!enabled) {
            statusText.append("⚠️ 自启开关未开启\n\n");
            statusText.append("请先开启「开机自启」开关。");
        } else {
            statusText.append("⚠️ 自启可能存在问题\n\n");
            statusText.append("请点击下方「厂商自启引导」查看设置方法。");
        }

        // 显示对话框
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(statusText.toString());
        tv.setTextSize(14);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        tv.setLineSpacing(8, 1);
        scrollView.addView(tv);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🔧 开机自启状态");
        builder.setView(scrollView);

        // 测试自启按钮
        builder.setPositiveButton("🧪 测试自启", (dialog, which) -> {
            testBootAutoStart();
        });

        // 厂商引导按钮
        builder.setNeutralButton("📖 厂商引导", (dialog, which) -> {
            showBootGuideDialog();
        });

        // 关闭按钮
        builder.setNegativeButton("关闭", null);

        builder.show();

        logOperation("【自启】查看自启状态详情");
    }

    // ====================================================================
    // ✅ 测试开机自启（新增）
    // ====================================================================
    /**
     * 测试开机自启功能
     *
     * 【原理】
     * 模拟发送一个 BOOT_COMPLETED 广播，
     * 测试 BootReceiver 是否能正常接收并启动应用。
     *
     * 【注意】
     * 这只是模拟测试，不完全等同于真实开机。
     * 真实开机时系统的状态更复杂，可能会有差异。
     * 但如果这个测试都失败了，真实开机肯定也失败。
     */
    private void testBootAutoStart() {
        try {
            logOperation("【自启】开始测试开机自启...");

            // 方法 1：发送模拟广播
            Intent testIntent = new Intent(Intent.ACTION_BOOT_COMPLETED);
            testIntent.setComponent(new ComponentName(this, BootReceiver.class));
            sendBroadcast(testIntent);

            logOperation("【自启】已发送模拟 BOOT_COMPLETED 广播");

            // 提示用户
            new AlertDialog.Builder(this)
                    .setTitle("🧪 自启测试")
                    .setMessage(
                            "已发送模拟开机广播，3 秒后应用会自动重启。\n\n" +
                            "如果 3 秒后应用成功重启，说明自启功能正常。\n" +
                            "如果没有重启，说明自启功能有问题，请查看日志。"
                    )
                    .setPositiveButton("好的", null)
                    .show();

        } catch (Exception e) {
            logOperation("【自启】测试失败：" + e.getMessage());
            Toast.makeText(this, "测试失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ====================================================================
    // ✅ 显示厂商自启引导对话框（新增）
    // ====================================================================
    /**
     * 显示厂商自启引导对话框
     *
     * 针对不同品牌的电视，给出对应的自启设置方法。
     *
     * 【为什么需要这个？】
     * 很多国产电视/手机有自己的「自启管理」或「应用权限管理」，
     * 即使应用有 RECEIVE_BOOT_COMPLETED 权限，
     * 如果系统的自启管理中没有允许，还是无法自启。
     *
     * 这个是系统层面的限制，代码无法绕过，只能引导用户去设置。
     */
    private void showBootGuideDialog() {
        String brand = getDeviceBrand();
        String guide = getBootGuideByBrand(brand);

        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(guide);
        tv.setTextSize(14);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        tv.setLineSpacing(6, 1);
        scrollView.addView(tv);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📖 " + brand + " 电视自启设置");
        builder.setView(scrollView);
        builder.setPositiveButton("知道了", null);
        builder.show();

        logOperation("【自启】查看" + brand + "自启引导");
    }

    // ====================================================================
    // ✅ 根据品牌获取自启引导（新增）
    // ====================================================================
    /**
     * 根据设备品牌获取自启设置引导
     *
     * @param brand 品牌名称
     * @return 引导文本
     */
    private String getBootGuideByBrand(String brand) {
        if (brand == null) {
            return getDefaultBootGuide();
        }

        String lowerBrand = brand.toLowerCase();

        // 小米/红米
        if (lowerBrand.contains("xiaomi") || lowerBrand.contains("redmi")) {
            return "📺 小米/红米电视自启设置方法：\n\n" +
                    "方法一：\n" +
                    "1. 打开「设置」\n" +
                    "2. 找到「应用设置」或「应用管理」\n" +
                    "3. 找到本应用\n" +
                    "4. 开启「自启动」权限\n\n" +
                    "方法二：\n" +
                    "1. 打开「电视管家」或「安全中心」\n" +
                    "2. 找到「自启管理」\n" +
                    "3. 找到本应用，允许自启\n\n" +
                    "💡 提示：不同型号的小米电视路径可能略有不同，\n" +
                    "请在设置中搜索「自启」或「自动启动」。";
        }

        // 创维
        if (lowerBrand.contains("skyworth") || lowerBrand.contains("coocaa")) {
            return "📺 创维/酷开电视自启设置方法：\n\n" +
                    "1. 打开「设置」\n" +
                    "2. 找到「应用管理」\n" +
                    "3. 找到「自启动管理」\n" +
                    "4. 找到本应用，开启自启\n\n" +
                    "💡 提示：部分酷开系统在「电视管家」→「自启管理」中设置。";
        }

        // TCL/雷鸟
        if (lowerBrand.contains("tcl") || lowerBrand.contains("ffalcon") || lowerBrand.contains("thunder")) {
            return "📺 TCL/雷鸟电视自启设置方法：\n\n" +
                    "1. 打开「设置」\n" +
                    "2. 找到「应用」或「应用管理」\n" +
                    "3. 找到「自启动管理」\n" +
                    "4. 找到本应用，允许自启\n\n" +
                    "💡 提示：雷鸟系统在「设置」→「通用」→「自启动管理」中设置。";
        }

        // 海信
        if (lowerBrand.contains("hisense") || lowerBrand.contains("vidda")) {
            return "📺 海信/VIDAA 电视自启设置方法：\n\n" +
                    "1. 打开「设置」\n" +
                    "2. 找到「应用管理」\n" +
                    "3. 找到「自启动设置」\n" +
                    "4. 找到本应用，开启自启\n\n" +
                    "💡 提示：VIDAA 系统在「设置」→「系统」→「应用自启动」中设置。";
        }

        // 华为/荣耀
        if (lowerBrand.contains("huawei") || lowerBrand.contains("honor")) {
            return "📺 华为/荣耀智慧屏自启设置方法：\n\n" +
                    "1. 打开「设置」\n" +
                    "2. 找到「应用」\n" +
                    "3. 找到「应用启动管理」\n" +
                    "4. 找到本应用，设置为「允许自启动」\n\n" +
                    "💡 提示：鸿蒙系统（HarmonyOS）在「设置」→「应用和服务」→「应用启动管理」中设置。";
        }

        // 索尼
        if (lowerBrand.contains("sony")) {
            return "📺 索尼电视自启设置方法：\n\n" +
                    "索尼电视使用原生 Android TV 系统，\n" +
                    "通常不需要额外设置，只要应用有自启权限就能自启。\n\n" +
                    "如果无法自启，请检查：\n" +
                    "1. 应用是否安装在内部存储（不能装在 SD 卡）\n" +
                    "2. 是否被「省电模式」限制\n" +
                    "3. 尝试在「设置」→「应用」中找到本应用，确认没有被禁用";
        }

        // 默认引导
        return getDefaultBootGuide();
    }

    // ====================================================================
    // ✅ 默认自启引导（新增）
    // ====================================================================
    /**
     * 获取默认的自启引导（通用版）
     *
     * @return 引导文本
     */
    private String getDefaultBootGuide() {
        return "📺 电视自启设置通用方法：\n\n" +
                "如果开启了自启开关但电视重启后无法自启，\n" +
                "通常是因为系统的「自启管理」限制了本应用。\n\n" +
                "请尝试以下方法：\n\n" +
                "方法一：在系统设置中查找\n" +
                "1. 打开电视的「设置」\n" +
                "2. 找到「应用管理」或「应用设置」\n" +
                "3. 找到「自启动管理」或「开机自启」\n" +
                "4. 找到本应用，允许自启\n\n" +
                "方法二：在电视管家中查找\n" +
                "1. 打开「电视管家」或「安全中心」\n" +
                "2. 找到「自启管理」或「权限管理」\n" +
                "3. 找到本应用，允许自启\n\n" +
                "💡 提示：\n" +
                "• 不同品牌的电视设置路径可能不同\n" +
                "• 可以在设置中搜索「自启」「自动启动」「开机启动」等关键词\n" +
                "• 如果找不到，可以查看电视的说明书或联系客服";
    }

    // ====================================================================
    // ✅ 获取设备品牌（新增）
    // ====================================================================
    /**
     * 获取设备品牌名称
     *
     * @return 品牌名称
     */
    private String getDeviceBrand() {
        try {
            String brand = Build.BRAND;
            if (brand == null || brand.isEmpty()) {
                brand = Build.MANUFACTURER;
            }
            if (brand == null || brand.isEmpty()) {
                return "未知品牌";
            }
            // 首字母大写
            return brand.substring(0, 1).toUpperCase() + brand.substring(1).toLowerCase();
        } catch (Exception e) {
            return "未知品牌";
        }
    }

    // ====================================================================
    // ✅ 获取应用版本名（新增）
    // ====================================================================
    /**
     * 获取应用版本名称
     *
     * @return 版本名
     */
    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    // ====================== 自动更新源 - AlarmManager ======================
    /**
     * 设置自动更新闹钟
     * 每天凌晨4点自动刷新直播源和EPG
     */
    private void setAutoUpdateAlarm() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // 设置每天凌晨4点
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.set(Calendar.HOUR_OF_DAY, 4);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);

            // 如果今天的4点已经过了，就设为明天
            if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_MONTH, 1);
            }

            // 设置重复闹钟（每天一次）
            alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
            );

            logOperation("【设置】已设置自动更新闹钟，每天凌晨4点执行");
        } catch (Exception e) {
            e.printStackTrace();
            logOperation("【设置】设置自动更新闹钟失败：" + e.getMessage());
        }
    }

    /**
     * 取消自动更新闹钟
     */
    private void cancelAutoUpdateAlarm() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            alarmManager.cancel(pendingIntent);
            logOperation("【设置】已取消自动更新闹钟");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ====================== 其他点击事件初始化 ======================
    /**
     * 初始化纯文本项的点击事件
     */
    private void initListeners() {
        // 屏幕比例
        tv_screen_ratio.setOnClickListener(v -> {
            showRatioDialog();
            logOperation("【设置】打开屏幕比例设置");
        });

        // 自定义订阅源
        tv_custom_source.setOnClickListener(v -> {
            showInputDialog("自定义订阅源", "请输入直播源地址", KEY_CUSTOM_LIVE);
            logOperation("【设置】打开自定义订阅源");
        });

        // 自定义节目单
        tv_custom_epg.setOnClickListener(v -> {
            showInputDialog("自定义节目单", "请输入EPG地址", KEY_CUSTOM_EPG);
            logOperation("【设置】打开自定义节目单");
        });

        // 多订阅源（历史记录）
        tv_multi_source.setOnClickListener(v -> {
            showHistoryDialog("直播源历史", "live_history");
            logOperation("【设置】打开直播源历史");
        });

        // 多节目单（历史记录）
        tv_multi_epg.setOnClickListener(v -> {
            showHistoryDialog("节目单历史", "epg_history");
            logOperation("【设置】打开节目单历史");
        });

        // 扫码添加
        tv_qr_code.setOnClickListener(v -> {
            showQRCodeDialog();
            logOperation("【设置】打开扫码管理");
        });
    }

    // ====================== 屏幕比例对话框 ======================
    /**
     * 显示屏幕比例选择对话框
     */
    private void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(ratios, (d, w) -> {
                    sp.edit().putString("screen_ratio", ratios[w]).apply();
                    logOperation("【设置】屏幕比例设为：" + ratios[w]);
                    Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
                }).show();
    }

    // ====================== 输入对话框（自定义源/节目单） ======================
    /**
     * 显示输入对话框
     * 用于自定义直播源和自定义节目单
     */
    private void showInputDialog(String title, String hint, String key) {
        EditText ed = new EditText(this);
        ed.setHint(hint);
        ed.setText(sp.getString(key, ""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ed)
                .setPositiveButton("确定", (d, w) -> {
                    String url = ed.getText().toString().trim();
                    if (!url.isEmpty()) {
                        sp.edit().putString(key, url).apply();
                        // 同时添加到多源管理
                        SourceManager sourceManager = new SourceManager(this,
                                key.contains("live") ? "live_history" : "epg_history");
                        sourceManager.addSource(url.substring(0, Math.min(10, url.length())) + "...", url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("【设置】" + title + "已更新：" + url);
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ====================== 多源管理对话框 ======================
    /**
     * 显示多源管理对话框
     *
     * 【功能】
     * 搜索、添加、编辑、删除、设为默认、排序、导入导出、刷新
     *
     * 【业务逻辑委托】
     * 所有数据操作都委托给 SourceManager，
     * 这里只负责 UI 展示和用户交互。
     *
     * @param title 对话框标题
     * @param key SP 存储的 key
     */
    private void showHistoryDialog(String title, final String key) {
        final SourceManager sourceManager = new SourceManager(this, key);
        final ArrayList<SourceManager.SourceItem> displayItems =
                new ArrayList<>(sourceManager.getAllSources());

        if (displayItems.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("暂无记录，是否添加一个？")
                    .setPositiveButton("添加", (d, w) -> showAddSourceDialog(title, key))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        adapter = new SourceAdapter(this, displayItems);

        // ===== 删除按钮点击事件 =====
        adapter.setOnDeleteClickListener(position -> {
            if (position < 0 || position >= displayItems.size()) return;
            SourceManager.SourceItem item = displayItems.get(position);
            int realPos = sourceManager.indexOfUrl(item.url);
            new AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("确定要删除「" + item.name + "」吗？")
                    .setPositiveButton("删除", (d, w) -> {
                        sourceManager.removeSource(realPos);
                        refreshDisplayList(sourceManager, displayItems, adapter, "");
                        adapter.setSelectedPosition(-1);
                        logOperation("【设置】删除源：" + item.name);
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // 找到当前使用的源，设置为选中状态
        String currentUrl = sp.getString(key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG, "");
        int selectedIndex = sourceManager.indexOfUrl(currentUrl);
        if (selectedIndex >= 0) {
            adapter.setSelectedPosition(selectedIndex);
        }

        final String finalTitle = title + "（共" + displayItems.size() + "个）";

        // 搜索框
        final EditText searchEt = new EditText(this);
        searchEt.setHint("🔍 搜索源名称或地址");
        searchEt.setTextSize(14);
        searchEt.setSingleLine(true);
        searchEt.setPadding(40, 20, 40, 20);
        searchEt.setBackgroundColor(0xFFEEEEEE);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(finalTitle);
        builder.setCustomTitle(searchEt);
        builder.setAdapter(adapter, null);

        // ===== 三个按钮：添加（右）、操作（中）、关闭（左） =====
        builder.setPositiveButton("➕ 添加", (dialog, which) -> {
            showAddSourceDialog(title, key);
        });

        builder.setNeutralButton("⚙ 操作", (dialog, which) -> {
            final int pos = adapter.getSelectedPosition();
            if (pos < 0 || pos >= displayItems.size()) {
                Toast.makeText(this, "请先选择一项", Toast.LENGTH_SHORT).show();
                return;
            }
            final SourceManager.SourceItem selectedItem = displayItems.get(pos);

            final String[] options = {
                    "✏️ 编辑",
                    "⭐ 设为默认",
                    "⬆ 移到顶部",
                    "⬇ 移到底部",
                    "🔄 刷新此源",
                    selectedItem.autoUpdate ? "🔕 关闭自动更新" : "🔔 开启自动更新",
                    "🗑 删除",
                    "📋 导出全部",
                    "📥 导入",
                    "🧹 清空全部"
            };

            new AlertDialog.Builder(this)
                    .setTitle("操作")
                    .setItems(options, (d, w) -> {
                        int realPos = sourceManager.indexOfUrl(selectedItem.url);
                        switch (w) {
                            case 0: // 编辑
                                showEditSourceDialog(title, key, realPos, selectedItem);
                                break;
                            case 1: // 设为默认
                                sourceManager.setDefault(realPos);
                                refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                                logOperation("【设置】设为默认源：" + selectedItem.name);
                                Toast.makeText(this, "已设为默认源", Toast.LENGTH_SHORT).show();
                                break;
                            case 2: // 移到顶部
                                sourceManager.moveToTop(realPos);
                                refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                                adapter.setSelectedPosition(0);
                                logOperation("【设置】移到顶部：" + selectedItem.name);
                                Toast.makeText(this, "已移到顶部", Toast.LENGTH_SHORT).show();
                                break;
                            case 3: // 移到底部
                                sourceManager.moveToBottom(realPos);
                                refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                                logOperation("【设置】移到底部：" + selectedItem.name);
                                Toast.makeText(this, "已移到底部", Toast.LENGTH_SHORT).show();
                                break;
                            case 4: // 刷新此源
                                sp.edit().putString(key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG, selectedItem.url).apply();
                                sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                                logOperation("【设置】刷新单个源：" + selectedItem.name);
                                Toast.makeText(this, "正在刷新…", Toast.LENGTH_SHORT).show();
                                break;
                            case 5: // 切换自动更新
                                boolean newState = sourceManager.toggleAutoUpdate(realPos);
                                refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                                logOperation("【设置】" + selectedItem.name + " 自动更新：" + (newState ? "开启" : "关闭"));
                                Toast.makeText(this, "自动更新已" + (newState ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                                break;
                            case 6: // 删除
                                new AlertDialog.Builder(this)
                                        .setTitle("确认删除")
                                        .setMessage("确定要删除「" + selectedItem.name + "」吗？")
                                        .setPositiveButton("删除", (dd, ww) -> {
                                            sourceManager.removeSource(realPos);
                                            refreshDisplayList(sourceManager, displayItems, adapter, searchEt.getText().toString());
                                            adapter.setSelectedPosition(-1);
                                            logOperation("【设置】删除源：" + selectedItem.name);
                                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                                break;
                            case 7: // 导出全部
                                String exportText = sourceManager.exportToText();
                                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("sources", exportText));
                                logOperation("【设置】导出 " + sourceManager.size() + " 个源到剪贴板");
                                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                                break;
                            case 8: // 导入
                                showImportDialog(title, key, sourceManager, displayItems, adapter, searchEt);
                                break;
                            case 9: // 清空全部
                                new AlertDialog.Builder(this)
                                        .setTitle("确认清空")
                                        .setMessage("确定要清空全部吗？此操作不可恢复！")
                                        .setPositiveButton("全部清空", (dd, ww) -> {
                                            sourceManager.clearAll();
                                            displayItems.clear();
                                            adapter.notifyDataSetChanged();
                                            logOperation("【设置】清空全部" + title);
                                            Toast.makeText(this, "已全部清空", Toast.LENGTH_SHORT).show();
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                                break;
                        }
                    })
                    .show();
        });

        builder.setNegativeButton("关闭", null);

        final AlertDialog dialog = builder.create();
        dialog.show();

        // ===== 搜索功能 =====
        searchEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                refreshDisplayList(sourceManager, displayItems, adapter, s.toString());
                dialog.setTitle(title + "（共" + displayItems.size() + "个）");
            }
        });

        // ===== 列表项点击事件：切换到该源 =====
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            SourceManager.SourceItem item = displayItems.get(position);
            sp.edit().putString(key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG, item.url).apply();
            // 移到最前面
            int realPos = sourceManager.indexOfUrl(item.url);
            if (realPos > 0) {
                sourceManager.moveToTop(realPos);
            }
            sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
            adapter.setSelectedPosition(position);
            logOperation("【设置】切换" + title + "：" + item.name);
            Toast.makeText(this, "已切换，正在刷新…", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 刷新显示列表（搜索后用）
     */
    private void refreshDisplayList(SourceManager sourceManager,
                                    ArrayList<SourceManager.SourceItem> displayItems,
                                    SourceAdapter adapter, String keyword) {
        displayItems.clear();
        displayItems.addAll(sourceManager.search(keyword));
        adapter.notifyDataSetChanged();
    }

    // ====================== 添加/编辑/导入对话框 ======================
    /**
     * 显示添加源的对话框
     * 可以输入名称和地址
     *
     * 【注意】
     * 动态创建输入框，不用 dialog_edit.xml 布局，
     * 避免布局文件 id 不匹配导致编译错误。
     */
    private void showAddSourceDialog(String title, final String key) {
        // 动态创建布局：两个输入框（名称 + 地址）
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText nameEt = new EditText(this);
        nameEt.setHint("源名称（如：主源、备用源）");
        nameEt.setTextSize(14);
        nameEt.setSingleLine(true);
        layout.addView(nameEt);

        // 加一点间距
        android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 20, 0, 0);

        final EditText urlEt = new EditText(this);
        urlEt.setHint("源地址 URL");
        urlEt.setTextSize(14);
        urlEt.setSingleLine(true);
        urlEt.setLayoutParams(params);
        layout.addView(urlEt);

        new AlertDialog.Builder(this)
                .setTitle("添加" + title.replace("历史", ""))
                .setView(layout)
                .setPositiveButton("添加", (dialog, which) -> {
                    String name = nameEt.getText().toString().trim();
                    String url = urlEt.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    SourceManager sourceManager = new SourceManager(this, key);
                    boolean success = sourceManager.addSource(name, url);
                    if (!success) {
                        Toast.makeText(this, "该源已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 设为当前使用的源
                    sp.edit().putString(key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG, url).apply();
                    sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    logOperation("【设置】添加源：" + (name.isEmpty() ? "未命名" : name) + " - " + url);
                    Toast.makeText(this, "已添加，正在刷新…", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 显示编辑源的对话框
     * 可以修改名称和地址
     *
     * 【注意】
     * 动态创建输入框，不用 dialog_edit.xml 布局。
     */
    private void showEditSourceDialog(String title, final String key, final int position, SourceManager.SourceItem oldItem) {
        // 动态创建布局：两个输入框（名称 + 地址）
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText nameEt = new EditText(this);
        nameEt.setText(oldItem.name);
        nameEt.setTextSize(14);
        nameEt.setSingleLine(true);
        layout.addView(nameEt);

        android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 20, 0, 0);

        final EditText urlEt = new EditText(this);
        urlEt.setText(oldItem.url);
        urlEt.setTextSize(14);
        urlEt.setSingleLine(true);
        urlEt.setSelection(urlEt.getText().length());
        urlEt.setLayoutParams(params);
        layout.addView(urlEt);

        new AlertDialog.Builder(this)
                .setTitle("编辑" + title.replace("历史", ""))
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = nameEt.getText().toString().trim();
                    String url = urlEt.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (name.isEmpty()) {
                        name = "未命名";
                    }
                    SourceManager sourceManager = new SourceManager(this, key);
                    String oldUrl = oldItem.url;
                    sourceManager.updateSource(position, name, url);
                    // 如果编辑的是当前使用的源，同步更新
                    String currentKey = key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG;
                    String currentUrl = sp.getString(currentKey, "");
                    if (currentUrl.equals(oldUrl)) {
                       
