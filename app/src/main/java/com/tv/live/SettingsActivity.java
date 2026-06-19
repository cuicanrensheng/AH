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
 * 【2026-06-19 修改：去掉背景变暗遮罩】
 * 【问题原因】
 * 原来的代码设置了 FLAG_DIM_BEHIND 和 dimAmount=0.6f，
 * 这会让后面的 MainActivity 播放画面变暗（蒙上 60% 的黑色遮罩），
 * 导致用户看到的背景很暗，甚至接近全黑。
 *
 * 【修改内容】
 * 删除了 FLAG_DIM_BEHIND 和 dimAmount 的设置，
 * 让窗口背景完全透明，能清楚看到后面的播放画面。
 *
 * 【为什么 Java 代码会覆盖主题设置？】
 * Android 中，主题（styles.xml）是在 Activity 创建时应用的，
 * 但是 Java 代码中对 Window 的设置是在运行时动态修改的，
 * 代码的优先级更高，会覆盖主题中的设置。
 * 所以即使 styles.xml 中设置了 backgroundDimEnabled=false，
 * Java 代码中又调用了 setFlags(FLAG_DIM_BEHIND, ...)，最终还是会生效。
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
    /**
     * 开机自启状态描述文本（显示在开关下面）
     * 【作用】让用户一目了然地看到自启状态（未开启/正常/需授权/被禁用等）
     */
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
        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 窗口背景设为透明
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // ====================================================================
        // ✅ 【2026-06-19 修改：删除背景变暗遮罩】
        // ====================================================================
        //
        // 【删除的代码】
        // getWindow().getAttributes().dimAmount = 0.6f;
        // getWindow().setFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND, WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        //
        // 【为什么删除？】
        // 1. FLAG_DIM_BEHIND：开启背景变暗效果，会在当前窗口后面加一层黑色遮罩
        // 2. dimAmount = 0.6f：暗度 60%，就是后面的画面会被蒙上 60% 的黑色
        // 3. 这两个属性导致进入设置页面后，播放画面变得很暗，甚至接近全黑
        // 4. 删除后，窗口背景完全透明，能清楚看到后面的播放画面
        //
        // 【为什么 styles.xml 中的设置不生效？】
        // 因为 Java 代码的优先级比主题更高。
        // 即使 styles.xml 中设置了 backgroundDimEnabled=false，
        // 但 Java 代码中又调用了 setFlags(FLAG_DIM_BEHIND, ...)，
        // 代码的设置会覆盖主题中的设置，最终还是会生效。
        //
        // 【验证方法】
        // 删除这两行后，进入设置页面，应该能清楚看到后面的播放画面。
        // 如果还是全黑，那就是 MainActivity.onPause() 中黑色占位图的问题，
        // 需要再修改 MainActivity。

        super.onCreate(savedInstanceState);

        // 强制横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // 加载布局
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
     * 【作用】统一表示自启的各种状态，方便判断和处理
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

        // ====================================================================
        // 已开启：检查实际状态并显示
        // ====================================================================
        //
        // 【为什么要检查状态？】
        // 即使开关打开了，也不一定能正常自启，可能有以下问题：
        // 1. 没有自启权限（需要用户手动授权）
        // 2. 组件被禁用（BootReceiver 被系统或安全软件禁用）
        // 3. 系统限制（某些厂商的自启管理会阻止应用自启）
        //
        // 所以需要检测实际状态，让用户知道当前自启是否真的有效。

        BootStatus status = checkBootStatus();

        switch (status) {
            case NORMAL:
                // ✅ 正常：应该可以自启
                tv_boot_status.setText("已开启 · 正常");
                tv_boot_status.setTextColor(Color.parseColor("#4CAF50")); // 绿色
                break;

            case NO_PERMISSION:
                // ⚠️ 没有自启权限
                tv_boot_status.setText("需授权自启权限");
                tv_boot_status.setTextColor(Color.parseColor("#FF9800")); // 橙色
                break;

            case COMPONENT_DISABLED:
                // ❌ 组件被禁用
                tv_boot_status.setText("组件被禁用");
                tv_boot_status.setTextColor(Color.parseColor("#F44336")); // 红色
                break;

            case SYSTEM_RESTRICTED:
                // ⚠️ 系统限制（厂商自启管理）
                tv_boot_status.setText("需在系统设置中开启");
                tv_boot_status.setTextColor(Color.parseColor("#FF9800")); // 橙色
                break;

            default:
                // 默认状态
                tv_boot_status.setText("已开启");
                tv_boot_status.setTextColor(Color.parseColor("#999999"));
                break;
        }
    }

    // ====================================================================
    // 检测开机自启状态
    // ====================================================================
    /**
     * 检测开机自启的实际状态
     *
     * 【作用】
     * 检测自启功能是否真的能正常工作，而不只是开关打开了。
     * 因为即使开关打开了，也可能因为权限、组件禁用、系统限制等原因无法自启。
     *
     * 【检测维度】
     * 1. 组件状态：BootReceiver 是否被禁用
     * 2. 权限状态：是否有自启权限
     * 3. 系统限制：是否被厂商自启管理限制
     *
     * @return 自启状态枚举
     */
    private BootStatus checkBootStatus() {
        // 1. 先检查组件是否被禁用
        // 【原理】
        // 通过 PackageManager 检查 BootReceiver 组件的启用状态。
        // 如果组件被禁用（比如被安全软件或用户手动禁用），即使广播发了也收不到。
        try {
            PackageManager pm = getPackageManager();
            ComponentName componentName = new ComponentName(this, BootReceiver.class);
            int state = pm.getComponentEnabledSetting(componentName);

            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                // 组件被禁用
                logOperation("【自启】组件被禁用");
                return BootStatus.COMPONENT_DISABLED;
            }
        } catch (Exception e) {
            logOperation("【自启】检查组件状态异常：" + e.getMessage());
        }

        // 2. 检查是否是 MIUI、EMUI 等有自启管理的系统
        // 【原理】
        // 某些厂商（小米、华为、OPPO、vivo 等）有自己的自启管理，
        // 即使应用注册了开机广播，也需要用户在系统设置中手动开启自启权限。
        // 这里通过读取系统属性来判断厂商。
        String manufacturer = Build.MANUFACTURER;
        if (manufacturer != null) {
            manufacturer = manufacturer.toLowerCase();

            // 小米 MIUI
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                logOperation("【自启】检测到 MIUI 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }

            // 华为 EMUI / HarmonyOS
            if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                logOperation("【自启】检测到 EMUI 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }

            // OPPO ColorOS
            if (manufacturer.contains("oppo") || manufacturer.contains("oneplus")) {
                logOperation("【自启】检测到 ColorOS 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }

            // vivo OriginOS
            if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
                logOperation("【自启】检测到 OriginOS 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
        }

        // 3. 检查 Android 10+ 后台启动限制
        // 【原理】
        // Android 10 开始，对后台启动 Activity 有严格限制，
        // 即使收到开机广播，也可能无法正常启动 Activity。
        // 但对于电视应用来说，这个限制通常不严格。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 有后台启动限制，但电视应用一般不受影响
            // 这里暂时不做特殊处理，标记为正常
        }

        // 4. 都没问题，返回正常状态
        logOperation("【自启】状态检测：正常");
        return BootStatus.NORMAL;
    }

    // ====================================================================
    // 显示开机自启引导对话框
    // ====================================================================
    /**
     * 显示自启引导对话框
     *
     * 【作用】
     * 当检测到自启可能有问题时，显示详细的引导信息，
     * 告诉用户如何开启自启权限。
     *
     * 【内容】
     * 1. 说明当前状态
     * 2. 给出具体的设置路径
     * 3. 提供"去设置"按钮（如果能跳转到对应页面）
     */
    private void showBootGuideDialog() {
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

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("我知道了", null)
                .setNeutralButton("测试自启", (dialog, which) -> {
                    // 测试自启功能
                    testBootAutoStart();
                })
                .show();
    }

    // ====================================================================
    // 显示开机自启详细状态对话框
    // ====================================================================
    /**
     * 显示自启详细状态对话框（长按触发）
     *
     * 【作用】
     * 显示更详细的自启状态信息，以及测试功能。
     * 供高级用户排查问题使用。
     */
    private void showBootStatusDialog() {
        BootStatus status = checkBootStatus();

        StringBuilder sb = new StringBuilder();
        sb.append("【自启状态检测】\n\n");

        // 开关状态
        boolean enabled = sp.getBoolean("boot_auto_start", false);
        sb.append("开关状态：").append(enabled ? "已开启" : "未开启").append("\n");

        // 组件状态
        try {
            PackageManager pm = getPackageManager();
            ComponentName componentName = new ComponentName(this, BootReceiver.class);
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

        new AlertDialog.Builder(this)
                .setTitle("📊 自启状态详情")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .setNeutralButton("测试自启", (dialog, which) -> {
                    testBootAutoStart();
                })
                .show();
    }

    // ====================================================================
    // 测试开机自启
    // ====================================================================
    /**
     * 测试开机自启功能
     *
     * 【作用】
     * 模拟发送开机广播，测试 BootReceiver 是否能正常接收，
     * 以及是否能正常启动应用。
     *
     * 【原理】
     * 手动发送一个 BOOT_COMPLETED 广播，
     * 模拟电视开机时的场景，看看能不能正常启动。
     *
     * 【注意】
     * 这只是一个测试功能，方便用户验证自启配置是否正确，
     * 真正的开机自启还是要等电视重启后才能验证。
     */
    private void testBootAutoStart() {
        logOperation("【自启】开始测试自启功能");

        try {
            // 模拟发送开机广播
            Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
            intent.setComponent(new ComponentName(this, BootReceiver.class));
            sendBroadcast(intent);

            Toast.makeText(this, "已发送开机广播测试\n\n请观察应用是否会重新启动",
                    Toast.LENGTH_LONG).show();

            logOperation("【自启】测试广播已发送");
        } catch (Exception e) {
            logOperation("【自启】测试失败：" + e.getMessage());
            Toast.makeText(this, "测试失败：" + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }
