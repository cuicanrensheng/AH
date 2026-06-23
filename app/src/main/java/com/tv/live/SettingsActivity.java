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
 * 设置页面 Activity
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
 * 12. 检查更新（委托给 UpdateManager）
 * 13. 崩溃日志查看 ← ✅ 2026-06-23 新增
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
 * - UpdateManager：应用更新管理
 * - CrashHandler：崩溃捕获和日志管理 ← ✅ 新增
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
 *
 * 【2026-06-20 修改：添加点击空白区域关闭功能】
 * 【需求来源】
 * 用户希望设置页面和频道面板一样，点击播放界面（空白区域）就退出设置。
 *
 * 【实现方式】
 * 1. 布局中添加 view_outside（全屏透明 View）
 * 2. Java 代码中设置点击事件，点击就调用 finish() 关闭页面
 * 3. 因为 view_outside 在 ScrollView 下面，所以点击面板区域不会触发关闭
 *
 * 【2026-06-20 修改：全面屏 + 刘海屏适配】
 * 【问题原因】
 * 打开设置页面后，后面的播放画面左侧有黑边，没有延伸到屏幕边缘。
 * 因为 SettingsActivity 没有设置刘海屏适配，系统以为这个窗口不需要延伸到刘海区域，
 * 导致下面的 MainActivity 播放画面也被限制了显示区域。
 *
 * 【解决方案】
 * 1. 沉浸式全屏（SYSTEM_UI_FLAG_FULLSCREEN）- 隐藏状态栏
 * 2. 刘海屏适配（LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES）- 内容延伸到刘海区域
 * 3. onWindowFocusChanged 中重新设置 - 防止被系统恢复
 *
 * 【效果】
 * 设置页面打开后，后面的播放画面和频道面板时一样，都是全屏的，没有黑边。
 *
 * 【2026-06-20 修改：彻底清除背景变暗（三重保险）】
 * 【问题原因】
 * 透明主题（windowIsTranslucent=true）的 Activity，
 * 系统默认会给下面的 Activity 加一层变暗遮罩。
 * 即使 styles.xml 中设置了 backgroundDimEnabled=false，
 * 某些设备上还是不生效，播放画面还是灰暗的。
 *
 * 【解决方案】
 * 在 Java 代码中用三种方式彻底清除变暗效果：
 * 1. 清除 FLAG_DIM_BEHIND 标志位
 * 2. 明确设置 dimAmount = 0（完全透明）
 * 3. 再清除一次（保险起见）
 *
 * 【效果】
 * 播放画面完全正常，和打开设置页面前一模一样，没有任何变暗。
 *
 * 【2026-06-20 新增：真正的检查更新功能】
 * 【功能说明】
 * 原来的检查更新只有一个 Toast "已是最新版本"，没有实际功能。
 * 现在改成真正的检查更新，委托给 UpdateManager：
 * 1. 请求服务器 JSON 配置文件
 * 2. 对比版本号
 * 3. 有新版本显示更新对话框（含更新日志）
 * 4. 点击更新后自动下载 APK
 * 5. 下载完成后自动安装
 *
 * 【配置方式】
 * 修改 UpdateManager.java 中的 UPDATE_JSON_URL 为你自己的地址。
 * （GitHub 项目的话，用 raw.githubusercontent.com 链接）
 *
 * 【2026-06-23 新增：崩溃日志查看功能】
 * 【功能说明】
 * 在设置页面增加"崩溃日志"入口，点击后可以：
 * 1. 查看所有历史崩溃记录列表（最多保留 10 条）
 * 2. 点击某条崩溃记录，查看详细堆栈信息
 * 3. 一键清空所有崩溃日志
 *
 * 【数据来源】
 * 崩溃日志由 CrashHandler 保存到本地文件，
 * 这里通过 CrashHandler.getAllCrashLogs() 读取。
 *
 * 【为什么要加这个功能？】
 * 方便用户查看和导出崩溃日志，反馈给开发者排查问题。
 * 不用用户去文件管理器里找，直接在设置里就能看。
 */
public class SettingsActivity extends AppCompatActivity {

    // ====================== 控件声明 ======================
    /** 5个开关控件 */
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;

    /** 纯文本点击项 */
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;

    // ====================================================================
    // ✅ 新增：崩溃日志入口控件
    // ====================================================================
    /**
     * 崩溃日志入口
     *
     * 【功能】
     * 点击后弹出历史崩溃记录列表对话框。
     *
     * 【为什么单独声明？】
     * 因为崩溃日志是一个独立的设置项，
     * 和其他纯文本点击项（tv_screen_ratio 等）是同一类。
     *
     * 【什么时候初始化？】
     * 在 onCreate() 的 findViewById 中绑定。
     */
    private TextView tv_crash_log;

    // ====================================================================
    // 开机自启状态显示
    // ====================================================================
    /**
     * 开机自启状态描述文本（显示在开关下面）
     */
    private TextView tv_boot_status;

    // ====================== 配置相关 ======================
    /** SharedPreferences 配置存储 */
    private SharedPreferences sp;

    // ====================================================================
    // 管理器相关（全部拆分后）
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

    // ====================================================================
    // ✅ 新增：应用更新管理器
    // ====================================================================
    /**
     * 应用更新管理器
     *
     * 【功能】
     * 1. 检查更新（请求服务器 JSON 配置）
     * 2. 版本号对比
     * 3. 显示更新对话框（含更新日志）
     * 4. 下载 APK（使用系统 DownloadManager）
     * 5. 下载完成后自动安装
     *
     * 【为什么拆分？】
     * 更新功能独立，和设置页面其他功能没关系。
     * 拆出来后职责清晰，以后要加更多更新功能（如增量更新、静默更新）也方便。
     *
     * 【什么时候初始化？】
     * 在 onCreate() 中初始化。
     *
     * 【什么时候释放？】
     * 在 onDestroy() 中释放，防止内存泄漏。
     */
    private UpdateManager updateManager;

    // ====================== SP Key 常量 ======================
    /** 自定义直播源地址 */
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    /** 自定义节目单地址 */
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";

    // ====================================================================
    // 全局日志系统（加回兼容层）
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
        // ====================================================================
        // ✅ 全面屏设置（隐藏状态栏 + 导航栏）
        // ====================================================================
        //
        // 【为什么还要加这个？】
        // 透明主题的 windowFullscreen 有时候不生效，
        // 用代码设置 System UI Visibility 更保险。
        //
        // 【效果】
        // 1. 隐藏状态栏（顶部）
        // 2. 隐藏导航栏（底部虚拟按键）
        // 3. 和主页面完全一样的显示效果
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION       // 隐藏导航栏
                | View.SYSTEM_UI_FLAG_FULLSCREEN            // 隐藏状态栏
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;     // 沉浸式，滑动后自动隐藏
        getWindow().getDecorView().setSystemUiVisibility(uiOptions);

        // ====================================================================
        // ✅ 刘海屏/挖孔屏适配（让内容延伸到刘海区域）
        // ====================================================================
        //
        // 【作用】
        // 让页面内容延伸到刘海/挖孔区域，消除左右两侧的黑边。
        // 只对 Android 9.0 (API 28) 及以上版本生效。
        //
        // 【为什么需要这个？】
        // 光设置 FLAG_FULLSCREEN 只能隐藏状态栏，
        // 但内容不会延伸到刘海/挖孔区域，还是会有黑边。
        //
        // 【更重要的原因】
        // 当 SettingsActivity（透明）覆盖在 MainActivity 上面时，
        // 如果 SettingsActivity 没有设置刘海屏适配，
        // 系统会以为这个窗口不需要延伸到刘海区域，
        // 导致下面的 MainActivity 播放画面也被限制了显示区域，出现黑边。
        //
        // 【解决方案】
        // 设置 LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES，
        // 让系统知道这个窗口也是全屏的，
        // 这样下面的 MainActivity 就能保持全屏显示了。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            // 让内容延伸到刘海区域（短边模式，兼容性最好）
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        // ====================================================================
        // ✅ 彻底清除背景变暗（三重保险）
        // ====================================================================
        //
        // 【问题原因】
        // 透明主题（windowIsTranslucent=true）的 Activity，
        // 系统默认会给下面的 Activity 加一层变暗遮罩。
        // 即使 styles.xml 中设置了 backgroundDimEnabled=false，
        // 某些设备上还是不生效，播放画面还是灰暗的。
        //
        // 【为什么 styles.xml 中的设置不生效？】
        // 因为 Java 代码的优先级比主题更高。
        // 而且透明主题的变暗效果是系统级的，
        // 光靠主题设置可能不够，需要在代码中明确清除。
        //
        // 【解决方案】
        // 在 Java 代码中用三种方式彻底清除变暗效果：
        // 1. 清除 FLAG_DIM_BEHIND 标志位
        // 2. 明确设置 dimAmount = 0（完全透明）
        // 3. 再清除一次（保险起见）
        //
        // 【三重保险原理】
        // 第 1 重：clearFlags(FLAG_DIM_BEHIND) → 清除变暗标志
        // 第 2 重：dimAmount = 0f → 明确暗度为 0
        // 第 3 重：再 clearFlags 一次 → 保险起见
        //
        // 【效果】
        // 播放画面完全正常，和打开设置页面前一模一样，没有任何变暗。

        // 第 1 重：清除变暗标志位
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        // 第 2 重：明确设置暗度为 0（完全透明）
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.dimAmount = 0f;  // 0 = 完全不变暗
        getWindow().setAttributes(layoutParams);

        // 第 3 重：再清除一次（保险起见）
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        // ===== 窗口设置 =====
        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 窗口背景设为透明（关键：让后面的播放界面能透过来）
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // ====================================================================
        // 【2026-06-19 修改：删除背景变暗遮罩】
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

        // 强制横屏（和 MainActivity 保持一致）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // 加载布局
        setContentView(R.layout.activity_settings);

        // ====================================================================
        // ✅ 点击左侧空白区域关闭设置
        // ====================================================================
        //
        // 【需求来源】
        // 用户希望设置页面和频道面板一样，点击播放界面（空白区域）就退出设置。
        //
        // 【实现原理】
        // 1. 布局中有一个 view_outside（全屏透明 View），占满整个屏幕
        // 2. 设置面板（ScrollView）盖在 view_outside 上面（右侧）
        // 3. 点击左侧空白区域 → 触发 view_outside 的点击事件 → 关闭设置
        // 4. 点击右侧面板区域 → 点击的是 ScrollView → 不触发关闭（因为盖在上面）
        //
        // 【为什么不用 onBackPressed()？】
        // 因为用户想要的是"点击空白区域关闭"，不是按返回键关闭。
        // 按返回键关闭是默认行为，这个是额外的交互方式。
        //
        // 【为什么不用 setFinishOnTouchOutside(true)？】
        // 因为那是 Dialog 的方法，Activity 没有这个方法。
        // 用透明 View 模拟是最通用的方案。
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 关闭设置页面
                finish();
            }
        });

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
        // ✅ 新增：绑定崩溃日志入口控件
        // ====================================================================
        //
        // 【作用】
        // 绑定布局中的 tv_crash_log 控件，
        // 后面才能设置点击事件。
        tv_crash_log = findViewById(R.id.tv_crash_log);

        // ====================================================================
        // 绑定开机自启状态文本
        // ====================================================================
        tv_boot_status = findViewById(R.id.tv_boot_status);

        // ====================================================================
        // 初始化所有管理器
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

        // ====================================================================
        // ✅ 初始化应用更新管理器
        // ====================================================================
        //
        // 【作用】
        // 创建 UpdateManager 实例，用于检查更新、下载 APK、自动安装等功能。
        //
        // 【为什么在这里初始化？】
        // 因为 UpdateManager 需要 Context，
        // 而且检查更新功能是在设置页面使用的，
        // 所以在 SettingsActivity 的 onCreate 中初始化最合适。
        //
        // 【注意】
        // UpdateManager 内部会用到 runOnUiThread，
        // 所以传入的 Context 必须是 Activity。
        updateManager = new UpdateManager(this);

        // ===== 日志查看按钮 =====
        findViewById(R.id.log_viewer).setOnClickListener(v -> {
            showLogDialog();
        });

        findViewById(R.id.log_operation).setOnClickListener(v -> {
            showOperationLogDialog();
        });

        // ====================================================================
        // ✅ 新增：崩溃日志入口点击事件
        // ====================================================================
        //
        // 【作用】
        // 点击"崩溃日志"后，弹出历史崩溃记录列表对话框。
        //
        // 【为什么放在这里？】
        // 和其他日志相关的点击事件（log_viewer、log_operation）放在一起，
        // 代码结构更清晰。
        tv_crash_log.setOnClickListener(v -> {
            showCrashLogDialog();
            logOperation("【设置】打开崩溃日志");
        });

        // ====================================================================
        // 开机自启（委托给 BootStartManager）
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
        // 自动更新源（委托给 AutoUpdateManager）
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

        // ====================================================================
        // ✅ 检查更新（真正的版本检测 + 自动下载安装）
        // ====================================================================
        //
        // 【功能说明】
        // 委托给 UpdateManager 处理：
        // 1. 请求服务器 JSON 配置文件
        // 2. 对比版本号
        // 3. 有新版本 → 显示更新对话框（含更新日志）
        // 4. 点击更新 → 开始下载 APK（通知栏显示进度）
        // 5. 下载完成 → 自动弹出安装界面
        // 6. 已是最新 → Toast 提示
        //
        // 【配置方式】
        // 修改 UpdateManager.java 中的 UPDATE_JSON_URL 为你自己的地址。
        // （GitHub 项目的话，用 raw.githubusercontent.com 链接）
        //
        // 【JSON 格式】
        // {
        //   "versionCode": 2,
        //   "versionName": "1.1.0",
        //   "downloadUrl": "https://xxx.com/app.apk",
        //   "updateLog": "1. 修复xxx\n2. 新增xxx",
        //   "forceUpdate": false
        // }
        //
        // 【原来的代码是什么样的？】
        // 原来只有一个 Toast：
        // findViewById(R.id.item_check_update).setOnClickListener(v -> {
        //     Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
        // });
        // 这是假的检查更新，不管有没有新版本都提示已是最新。
        //
        // 【修改后有什么不同？】
        // 1. 真正请求服务器检查版本
        // 2. 有新版本会显示更新对话框，让用户选择是否更新
        // 3. 点击更新后自动下载，下载完成自动安装
        // 4. 已是最新版本才提示"已是最新版本"
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            // 检查更新
            updateManager.checkUpdate();
            logOperation("【设置】点击检查更新");
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
        // 多订阅源（委托给 SourceDialogManager）
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
        // 扫码添加（委托给 QRCodeManager）
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
    // 日志对话框（加回兼容层）
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

    // ====================================================================
    // ✅ 新增：崩溃日志对话框
    // ====================================================================

    /**
     * 显示崩溃日志对话框
     *
     * 【功能】
     * 1. 先显示崩溃列表（时间 + 异常类型 + 简要信息）
     * 2. 点击某一条后，显示该次崩溃的详细堆栈信息
     * 3. 支持一键清空所有崩溃日志
     *
     * 【数据来源】
     * 通过 CrashHandler.getAllCrashLogs() 读取本地保存的崩溃日志文件。
     *
     * 【为什么用两级对话框？】
     * 如果直接把所有崩溃日志都显示在一个对话框里，
     * 内容太多，用户不好找。
     * 先显示列表，点进去看详情，体验更好。
     */
    private void showCrashLogDialog() {
        // 1. 获取所有历史崩溃日志
        String[] crashLogs = CrashHandler.getAllCrashLogs(this);

        if (crashLogs == null || crashLogs.length == 0) {
            // 没有崩溃日志，直接提示
            new AlertDialog.Builder(this)
                    .setTitle("💥 崩溃日志")
                    .setMessage("暂无崩溃记录。\n\n应用运行得很稳定，继续保持！🎉")
                    .setPositiveButton("关闭", null)
                    .show();
            return;
        }

        // 2. 生成列表项显示文本（时间 + 异常类型）
        final String[] listItems = new String[crashLogs.length];
        for (int i = 0; i < crashLogs.length; i++) {
            listItems[i] = parseCrashLogForList(crashLogs[i]);
        }

        // 3. 显示崩溃列表对话框
        final String[] finalCrashLogs = crashLogs;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("💥 历史崩溃记录 (" + crashLogs.length + ")");
        builder.setItems(listItems, (dialog, which) -> {
            // 点击某一条，显示详细信息
            showCrashDetailDialog(finalCrashLogs[which]);
        });
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            // 二次确认：清空崩溃日志
            new AlertDialog.Builder(this)
                    .setTitle("确认清空")
                    .setMessage("确定要清空所有崩溃日志吗？\n清空后无法恢复。")
                    .setPositiveButton("确定清空", (d, w) -> {
                        CrashHandler.clearAllCrashLogs(this);
                        logOperation("【设置】清空崩溃日志");
                        Toast.makeText(this, "崩溃日志已清空", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        builder.show();
    }

    /**
     * 解析崩溃日志，生成列表项显示文本
     *
     * 【格式】
     * 时间：异常类型
     * 异常信息（第一行）
     *
     * 【示例】
     * 2026-06-23 15:30:45
     * NullPointerException: Attempt to invoke...
     *
     * @param crashLog 完整的崩溃日志
     * @return 列表项显示文本
     */
    private String parseCrashLogForList(String crashLog) {
        try {
            String time = "";
            String exceptionType = "";
            String exceptionMsg = "";

            String[] lines = crashLog.split("\n");
            for (String line : lines) {
                if (line.startsWith("时间：")) {
                    time = line.replace("时间：", "").trim();
                } else if (line.startsWith("异常类型：")) {
                    // 只取类名（去掉包名，显示更简洁）
                    String fullType = line.replace("异常类型：", "").trim();
                    int dotIndex = fullType.lastIndexOf('.');
                    if (dotIndex >= 0 && dotIndex < fullType.length() - 1) {
                        exceptionType = fullType.substring(dotIndex + 1);
                    } else {
                        exceptionType = fullType;
                    }
                } else if (line.startsWith("异常信息：")) {
                    exceptionMsg = line.replace("异常信息：", "").trim();
                    // 太长的话截断
                    if (exceptionMsg.length() > 50) {
                        exceptionMsg = exceptionMsg.substring(0, 50) + "...";
                    }
                }
            }

            // 组合显示文本
            StringBuilder sb = new StringBuilder();
            if (!time.isEmpty()) {
                sb.append(time).append("\n");
            }
            if (!exceptionType.isEmpty()) {
                sb.append(exceptionType);
                if (!exceptionMsg.isEmpty()) {
                    sb.append(": ").append(exceptionMsg);
                }
            } else {
                sb.append("未知异常");
            }

            return sb.toString();

        } catch (Exception e) {
            return "解析失败";
        }
    }

    /**
     * 显示单条崩溃的详细信息对话框
     *
     * 【功能】
     * 显示完整的崩溃堆栈信息，支持滚动查看。
     *
     * @param crashLog 完整的崩溃日志内容
     */
    private void showCrashDetailDialog(String crashLog) {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);

        tv.setText(crashLog);
        tv.setTextSize(11);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        tv.setBackgroundColor(0xFFF5F5F5);
        scrollView.addView(tv);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📋 崩溃详情");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("复制日志", (dialog, which) -> {
            // 复制到剪贴板
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                android.content.ClipData clip =
                        android.content.ClipData.newPlainText("崩溃日志", crashLog);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        });
        builder.show();
    }

    // ====================================================================
    // ✅ 窗口焦点变化时，重新隐藏状态栏（防止被系统恢复）
    // ====================================================================
    /**
     * 窗口焦点变化回调
     *
     * 【作用】
     * 有时候系统会自动恢复状态栏（比如弹出通知、切换应用后回来），
     * 在获得焦点时重新设置沉浸式和刘海屏适配，确保一直是全屏状态。
     *
     * 【什么时候调用？】
     * - 页面打开时获得焦点 → 调用
     * - 从后台回到前台 → 调用
     * - 关闭弹窗后重新获得焦点 → 调用
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 重新设置沉浸式全屏
            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);

            // ====================================================================
            // ✅ 刘海屏适配（重新设置）
            // ====================================================================
            // 防止系统恢复后，刘海屏适配失效
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }

            // ====================================================================
            // ✅ 重新清除背景变暗（保险起见）
            // ====================================================================
            // 防止系统恢复后，变暗效果又回来了
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
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

        // ====================================================================
        // ✅ 释放更新管理器
        // ====================================================================
        //
        // 【为什么要释放？】
        // UpdateManager 内部注册了广播接收器（下载完成广播），
        // 如果不注销，会导致内存泄漏。
        //
        // 【释放了什么？】
        // 调用 updateManager.release()，内部会：
        // 1. 注销下载完成广播接收器
        // 2. 把广播接收器置为 null
        //
        // 【什么时候释放？】
        // Activity 销毁时（onDestroy）释放。
        if (updateManager != null) {
            updateManager.release();
        }
    }
}
