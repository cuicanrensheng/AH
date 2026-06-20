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
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    /** 5个开关控件 */
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    /** 纯文本点击项 */
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
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
       
