package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 设置页面
 *
 * 【功能清单】
 * 1. 开机自启开关（委托给 BootStartManager）
 * 2. 节目单开关
 * 3. 自动更新源（委托给 AutoUpdateManager）
 * 4. 换台反转
 * 5. 数字选台
 * 6. 屏幕比例设置
 * 7. 自定义订阅源/节目单
 * 8. 多订阅源/节目单管理（委托给 SourceDialogManager）
 * 9. 扫码添加（委托给 QRCodeManager）
 * 10. 解析&播放日志查看
 * 11. 操作日志查看
 * 12. 检查更新
 *
 * 【架构说明】
 * 本 Activity 只负责 UI 展示和用户交互，
 * 业务逻辑都委托给专门的管理类：
 * - BootStartManager：开机自启管理
 * - AutoUpdateManager：自动更新闹钟管理
 * - SourceDialogManager：多源对话框管理
 * - QRCodeManager：二维码管理
 * - WebServerManager：网页后台 HTTP 服务器
 * - SourceManager：多源数据管理
 *
 * 【日志兼容说明】
 * 为了兼容其他文件调用 SettingsActivity.log() 和 SettingsActivity.logOperation()，
 * 这里保留了这些静态方法，内部调用 LogManager。
 * 其他文件不需要修改，直接就能用。
 *
 * 【2026-06-19 修改：去掉背景变暗遮罩】
 * 【问题原因】
 * 原来的代码设置了 FLAG_DIM_BEHIND 和 dimAmount=0.6f，
 * 这会让后面的 MainActivity 播放画面变暗（蒙上 60% 的黑色遮罩）。
 *
 * 【修改内容】
 * 删除了 FLAG_DIM_BEHIND 和 dimAmount 的设置，
 * 让窗口背景完全透明，能清楚看到后面的播放画面。
 */
public class SettingsActivity extends AppCompatActivity {

    // ====================== 控件声明 ======================
    /** 5个开关控件 */
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    /** 纯文本点击项 */
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;

    // ====================================================================
    // ✅ 开机自启状态显示
    // ====================================================================
    /**
     * 开机自启状态描述文本（显示在开关下面）
     */
    private TextView tv_boot_status;

    // ====================== 配置相关 ======================
    /** SharedPreferences 配置存储 */
    private SharedPreferences sp;

    // ====================================================================
    // ✅ 管理器相关（全部拆分后）
    // ====================================================================
    /**
     * 开机自启管理器
     */
    private BootStartManager bootStartManager;

    /**
     * 自动更新闹钟管理器
     */
    private AutoUpdateManager autoUpdateManager;

    /**
     * 多源对话框管理器
     */
    private SourceDialogManager sourceDialogManager;

    /**
     * 二维码管理器
     */
    private QRCodeManager qrCodeManager;

    /**
     * 网页后台管理器
     */
    private WebServerManager webServerManager;

    /**
     * 网页后台端口号
     */
    private static final int WEB_SERVER_PORT = 10481;

    /**
     * 网页后台访问地址（用于生成二维码）
     */
    private String currentWebUrl;

    // ====================== SP Key 常量 ======================
    /** 自定义直播源地址 */
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    /** 自定义节目单地址 */
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";

    // ====================================================================
    // ✅ 全局日志系统（加回兼容层）
    // ====================================================================
    /**
     * 解析&播放日志
     * 用 volatile 保证多线程可见性
     *
     * 【兼容说明】
     * 为了不修改其他文件，这里保留这个静态变量。
     * 内部实际存储在 LogManager 里。
     */
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();

    /**
     * 操作日志
     * 记录用户的所有操作 + 网页后台日志
     *
     * 【兼容说明】
     * 为了不修改其他文件，这里保留这个静态变量。
     * 内部实际存储在 LogManager 里。
     */
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();

    /**
     * 记录解析&播放日志
     * @param msg 日志内容
     *
     * 【兼容说明】
     * 为了不修改其他文件，这里保留这个静态方法。
     * 内部调用 LogManager.log()。
     */
    public static void log(String msg) {
        // 调用 LogManager 统一管理
        LogManager.log(msg);
        // 同步更新一下本地变量（兼容直接访问变量的情况）
        // 注意：WebServerManager 直接访问了这个变量，所以需要同步
        PLAY_LOG = new StringBuilder(LogManager.getPlayLog());
    }

    /**
     * 记录操作日志
     * @param msg 操作内容
     *
     * 【兼容说明】
     * 为了不修改其他文件，这里保留这个静态方法。
     * 内部调用 LogManager.logOperation()。
     */
    public static void logOperation(String msg) {
        // 调用 LogManager 统一管理
        LogManager.logOperation(msg);
        // 同步更新一下本地变量（兼容直接访问变量的情况）
        // 注意：WebServerManager 直接访问了这个变量，所以需要同步
        OPERATION_LOG = new StringBuilder(LogManager.getOperationLog());
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
        // ✅ 绑定开机自启状态文本
        // ====================================================================
        tv_boot_status = findViewById(R.id.tv_boot_status);

        // ====================================================================
        // ✅ 初始化所有管理器
        // ====================================================================
        // 开机自启管理器
        bootStartManager = new BootStartManager(this, sp);
        // 自动更新闹钟管理器
        autoUpdateManager = new AutoUpdateManager(this);
        // 多源对话框管理器
        sourceDialogManager = new SourceDialogManager(this, sp);
        // 二维码管理器
        qrCodeManager = new QRCodeManager(this);
        // 网页后台管理器
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);

        // ===== 日志查看按钮 =====
        findViewById(R.id.log_viewer).setOnClickListener(v -> {
            showLogDialog();
        });
        findViewById(R.id.log_operation).setOnClickListener(v -> {
            showOperationLogDialog();
        });

        // ====================================================================
        // ✅ 开机自启（委托给 BootStartManager）
        // ====================================================================
        // 设置开关状态
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));

        // 更新状态显示
        bootStartManager.updateBootStatusText(tv_boot_status);

        // 点击整个 item 切换开关
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            boolean isChecked = !sw_boot.isChecked();
            sw_boot.setChecked(isChecked);
            // 委托给 BootStartManager 处理
            bootStartManager.toggleBoot(isChecked, tv_boot_status);
        });

        // 长按 item 显示详细状态和测试功能
        findViewById(R.id.item_boot).setOnLongClickListener(v -> {
            // 委托给 BootStartManager 处理
            bootStartManager.showBootStatusDialog();
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

        // ====================================================================
        // ✅ 自动更新源（委托给 AutoUpdateManager）
        // ====================================================================
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            boolean isChecked = !sw_auto_update.isChecked();
            sw_auto_update.setChecked(isChecked);
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            if (isChecked) {
                autoUpdateManager.setAutoUpdateAlarm();
            } else {
                autoUpdateManager.cancelAutoUpdateAlarm();
            }
            logOperation("【设置】自动更新源" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启（每天凌晨4点）" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 如果之前就是开启状态，重新设置一下闹钟
        if (sp.getBoolean("auto_update_source", true)) {
            autoUpdateManager.setAutoUpdateAlarm();
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

        // ===== 启动网页后台 =====
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();

        logOperation("【设置】打开设置页面");
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

        // ====================================================================
        // ✅ 多订阅源（委托给 SourceDialogManager）
        // ====================================================================
        tv_multi_source.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("直播源历史", "live_history");
            logOperation("【设置】打开直播源历史");
        });

        tv_multi_epg.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("节目单历史", "epg_history");
            logOperation("【设置】打开节目单历史");
        });

        // ====================================================================
        // ✅ 扫码添加（委托给 QRCodeManager）
        // ====================================================================
        tv_qr_code.setOnClickListener(v -> {
            qrCodeManager.showQRCodeDialog(currentWebUrl);
            logOperation("【设置】打开扫码管理");
        });
    }

    // ====================== 屏幕比例对话框 ======================
    /**
     * 显示屏幕比例选择对话框
     *
     * 【为什么不拆分？】
     * 只有 1 个方法，代码量太少，拆分反而增加文件数量。
     * 如果以后要加更多比例选项或高级设置，可以再拆。
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
     *
     * 【为什么不拆分？】
     * 只有 1 个方法，代码量太少，拆分反而增加文件数量。
     * 如果以后要加更多输入框或验证逻辑，可以再拆。
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

    // ====================================================================
    // ✅ 日志对话框（加回兼容层）
    // ====================================================================
    /**
     * 显示操作日志对话框
     * 最新的日志显示在最上面（倒序）
     *
     * 【兼容说明】
     * 内部调用 LogManager.showOperationLogDialog()
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
            // 同步清空 LogManager
            LogManager.clearOperationLog();
            Toast.makeText(this, "操作日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    /**
     * 显示解析&播放日志对话框
     * 最新的日志显示在最上面（倒序）
     *
     * 【兼容说明】
     * 内部调用 LogManager.showLogDialog()
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
            // 同步清空 LogManager
            LogManager.clearPlayLog();
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    // ====================== onDestroy 生命周期 ======================
    @Override
    protected void onDestroy() {
        super.onDestroy();
        logOperation("【设置】关闭设置页面");
        // 停止网页后台
        if (webServerManager != null) {
            webServerManager.stop();
        }
    }

}
