package com.tv.live;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.tv.live.manager.TvRemoteManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页面 Activity
 *
 * 【功能清单】
 * 1. 开机自启开关
 * 2. 节目单开关
 * 3. 自动更新源
 * 4. 换台反转
 * 5. 数字选台
 * 6. 画中画开关 ✅ 2026-06-22 新增
 * 7. 屏幕比例设置
 * 8. 自定义订阅源/节目单
 * 9. 多订阅源/节目单历史
 * 10. 扫码添加
 * 11. 解析&播放日志查看
 * 12. 操作日志查看
 * 13. 检查更新
 *
 * 【遥控器操作】
 * - ↑/↓：在设置项之间上下移动
 * - OK/确认：选中当前项（点击/切换开关）
 * - 返回/菜单：关闭设置页面
 */
public class SettingsActivity extends AppCompatActivity {

    // ====================== 控件声明 ======================
    /**
     * 开关控件列表
     * 按页面从上到下的顺序排列
     */
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;

    // ====================================================================
    // ✅ 2026-06-22 新增：画中画开关变量
    // 【作用】控制画中画功能的开启和关闭
    // ====================================================================
    private Switch sw_pip;

    /**
     * 纯文本点击项
     * 这些是点击后弹出对话框的设置项
     */
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;

    /** 开机自启状态描述文本 */
    private TextView tv_boot_status;

    // ====================== 配置相关 ======================
    /** SharedPreferences 配置存储 */
    private SharedPreferences sp;

    // ====================================================================
    // 遥控器统一管理器
    // ====================================================================
    /**
     * 遥控器统一管理器
     * 【作用】统一处理所有遥控器按键事件，和 MainActivity、ChannelPanelController 用同一套体系
     */
    private TvRemoteManager remoteManager;

    /**
     * 可聚焦的设置项列表（按从上到下的顺序排列）
     * 【作用】遥控器在这些项之间上下移动焦点
     */
    private List<View> settingsItemList = new ArrayList<>();

    /** 滚动视图（用于滚动到可见区域） */
    private ScrollView scrollView;

    // ====================================================================
    // 管理器相关
    // ====================================================================
    private BootStartManager bootStartManager;
    private AutoUpdateManager autoUpdateManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private UpdateManager updateManager;

    /** 网页后台端口号 */
    private static final int WEB_SERVER_PORT = 10481;

    /** 当前网页后台访问地址 */
    private String currentWebUrl;

    // ====================== SP Key 常量 ======================
    /** 自定义直播源地址的 SP Key */
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";

    /** 自定义 EPG 地址的 SP Key */
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";

    // ====================================================================
    // 全局日志系统（兼容 WebServerManager）
    // ====================================================================
    /**
     * 操作日志 StringBuilder
     * 【作用】记录用户的所有操作，供网页后台查看
     * 【注意】WebServerManager 会引用这个静态变量，所以必须声明为 public static
     */
    public static StringBuilder OPERATION_LOG = new StringBuilder();

    /**
     * 解析&播放日志 StringBuilder
     * 【作用】记录直播源解析和播放的详细日志，供网页后台查看
     * 【注意】WebServerManager 会引用这个静态变量，所以必须声明为 public static
     */
    public static StringBuilder PLAY_LOG = new StringBuilder();

    /**
     * 记录操作日志
     * @param msg 日志内容
     */
    public static void logOperation(String msg) {
        if (OPERATION_LOG == null) OPERATION_LOG = new StringBuilder();
        OPERATION_LOG.append(msg).append("\n");
        LogManager.logOperation(msg);
    }

    /**
     * 记录解析&播放日志
     * @param msg 日志内容
     */
    public static void log(String msg) {
        if (PLAY_LOG == null) PLAY_LOG = new StringBuilder();
        PLAY_LOG.append(msg).append("\n");
        LogManager.logPlay(msg);
    }

    // ====================== onCreate 生命周期 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ====================================================================
        // 全面屏设置（隐藏状态栏 + 导航栏）
        // ====================================================================
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(uiOptions);

        // ====================================================================
        // 刘海屏/挖孔屏适配
        // ====================================================================
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        // ====================================================================
        // 彻底清除背景变暗（三重保险）
        // 【为什么要三重保险？】
        // 不同 Android 版本的变暗机制不一样，三重保险确保所有版本都生效
        // ====================================================================
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.dimAmount = 0f;
        getWindow().setAttributes(layoutParams);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        // ===== 窗口设置 =====
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        // ====================================================================
        // 点击左侧空白区域关闭设置
        // 【作用】设置页面是右侧滑出的浮层，点击左侧空白区域可以关闭
        // ====================================================================
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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

        // ====================================================================
        // ✅ 2026-06-22 新增：绑定画中画开关控件
        // 【作用】从布局中找到画中画开关的 Switch 控件
        // ====================================================================
        sw_pip = findViewById(R.id.sw_pip);

        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);
        tv_boot_status = findViewById(R.id.tv_boot_status);

        // 获取 ScrollView（用于滚动到可见区域）
        scrollView = findViewById(R.id.settings_content);

        // ====================================================================
        // 初始化所有管理器
        // ====================================================================
        bootStartManager = new BootStartManager(this, sp);
        autoUpdateManager = new AutoUpdateManager(this);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);

        // ====================================================================
        // 初始化设置项列表（遥控器焦点顺序）
        // 【作用】告诉遥控器管理器，哪些设置项可以聚焦，以及顺序是什么
        // ====================================================================
        initSettingsItemList();

        // 初始化遥控器管理器
        initRemoteManager();

        // ====================================================================
        // 初始化各个开关的状态
        // 【作用】从 SharedPreferences 读取保存的设置，初始化开关状态
        // ====================================================================

        // 1. 开机自启
        bootStartManager.initSwitch(sw_boot, tv_boot_status);

        // 2. 节目单开关
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_epg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            logOperation("【设置】节目单开关 → " + (isChecked ? "开启" : "关闭"));
        });

        // 3. 自动更新源
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        autoUpdateManager.setSwitch(sw_auto_update);

        // 4. 换台反转
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_reverse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            logOperation("【设置】换台反转 → " + (isChecked ? "开启" : "关闭"));
        });

        // 5. 数字选台
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        sw_num_channel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            logOperation("【设置】数字选台 → " + (isChecked ? "开启" : "关闭"));
        });

        // ====================================================================
        // ✅ 2026-06-22 新增：6. 画中画开关
        // 【作用】
        // 1. 从 SharedPreferences 读取保存的画中画开关状态
        // 2. 设置开关状态变化监听器，切换时保存到 SharedPreferences
        // 3. 记录操作日志
        //
        // 【默认值】true（默认开启画中画功能）
        // 【SP Key】"pip_enable"
        // ====================================================================
        sw_pip.setChecked(sp.getBoolean("pip_enable", true));
        sw_pip.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 保存到 SharedPreferences
            sp.edit().putBoolean("pip_enable", isChecked).apply();
            // 记录操作日志
            logOperation("【设置】画中画 → " + (isChecked ? "开启" : "关闭"));
        });

        // ====================================================================
        // 检查更新
        // ====================================================================
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            logOperation("【设置】点击检查更新");
            updateManager.checkUpdate();
        });

        // ===== 其他点击事件 =====
        initListeners();

        // ===== 启动网页后台 =====
        webServerManager.startServer();
        currentWebUrl = webServerManager.getWebUrl();
    }

    // ====================================================================
    // 初始化设置项列表（遥控器焦点顺序）
    // ====================================================================
    /**
     * 初始化设置项列表
     *
     * 【作用】
     * 把所有可聚焦的设置项按页面从上到下的顺序添加到列表中，
     * 遥控器管理器会根据这个列表来移动焦点。
     *
     * 【顺序说明】
     * 1. 开机自启
     * 2. 节目单开关
     * 3. 自动更新源
     * 4. 换台反转
     * 5. 数字选台
     * 6. 画中画 ✅ 新增
     * 7. 屏幕比例
     * 8. 自定义订阅源
     * 9. 自定义节目单
     * 10. 多订阅源
     * 11. 多节目单
     * 12. 扫码添加
     * 13. 查看解析日志
     * 14. 操作日志
     * 15. 检查更新
     */
    private void initSettingsItemList() {
        settingsItemList.clear();

        // 按页面从上到下的顺序添加
        settingsItemList.add(findViewById(R.id.item_boot));           // 1. 开机自启
        settingsItemList.add(findViewById(R.id.item_epg));            // 2. 节目单开关
        settingsItemList.add(findViewById(R.id.item_auto_update));    // 3. 自动更新源
        settingsItemList.add(findViewById(R.id.item_reverse));        // 4. 换台反转
        settingsItemList.add(findViewById(R.id.item_num_channel));    // 5. 数字选台

        // ====================================================================
        // ✅ 2026-06-22 新增：画中画设置项
        // 【作用】把画中画设置项添加到遥控器焦点列表中，
        //         这样遥控器可以聚焦到画中画开关
        // 【位置】第 6 项，在数字选台下面
        // ====================================================================
        settingsItemList.add(findViewById(R.id.item_pip));            // 6. 画中画

        settingsItemList.add(findViewById(R.id.tv_screen_ratio));     // 7. 屏幕比例
        settingsItemList.add(findViewById(R.id.tv_custom_source));    // 8. 自定义订阅源
        settingsItemList.add(findViewById(R.id.tv_custom_epg));       // 9. 自定义节目单
        settingsItemList.add(findViewById(R.id.tv_multi_source));     // 10. 多订阅源
        settingsItemList.add(findViewById(R.id.tv_multi_epg));        // 11. 多节目单
        settingsItemList.add(findViewById(R.id.tv_qr_code));          // 12. 扫码添加
        settingsItemList.add(findViewById(R.id.log_viewer));          // 13. 查看解析日志
        settingsItemList.add(findViewById(R.id.log_operation));       // 14. 操作日志
        settingsItemList.add(findViewById(R.id.item_check_update));   // 15. 检查更新

        // 移除 null 的项（防止有的 View 找不到）
        for (int i = settingsItemList.size() - 1; i >= 0; i--) {
            if (settingsItemList.get(i) == null) {
                settingsItemList.remove(i);
            }
        }
    }

    // ====================================================================
    // 初始化遥控器管理器
    // ====================================================================
    /**
     * 初始化遥控器管理器
     *
     * 【作用】
     * 1. 创建遥控器管理器实例
     * 2. 设置模式为设置页面模式
     * 3. 设置可聚焦的设置项列表
     * 4. 设置滚动视图（用于滚动到可见区域）
     * 5. 设置遥控器动作监听器，处理各种按键事件
     */
    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.SETTINGS_MODE);
        remoteManager.setSettingsItems(settingsItemList);
        remoteManager.setScrollView(scrollView);
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {

            /**
             * 返回键：关闭设置页面
             */
            @Override
            public boolean onSettingsBack() {
                finish();
                return true;
            }

            /**
             * 菜单键：关闭设置页面
             */
            @Override
            public void onSettingsMenu() {
                finish();
            }

            /**
             * OK键：选中当前项（点击/切换开关）
             */
            @Override
            public void onSettingsConfirm() {
                int position = remoteManager.getSettingsFocusPosition();
                handleSettingsItemClick(position);
            }

            /**
             * 焦点变化：更新高亮显示
             */
            @Override
            public void onSettingsFocusChanged(int position) {
                updateSettingsFocus();
            }

            // 其他空实现
            @Override public void onSettingsMoveUp() {}
            @Override public void onSettingsMoveDown() {}
            @Override public void onPlayChannelUp() {}
            @Override public void onPlayChannelDown() {}
            @Override public void onPlayTogglePanel() {}
            @Override public void onPlayOpenSettings() {}
            @Override public boolean onPlayBack() { return false; }
            @Override public void onPanelMoveUp() {}
            @Override public void onPanelMoveDown() {}
            @Override public void onPanelMoveLeft() {}
            @Override public void onPanelMoveRight() {}
            @Override public void onPanelConfirm() {}
            @Override public boolean onPanelBack() { return false; }
            @Override public void onPanelMenu() {}
            @Override public void onPanelNumber(int number) {}
            @Override public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {}
        });

        // 默认聚焦第一项
        updateSettingsFocus();
    }

    // ====================== 其他点击事件初始化 ======================
    /**
     * 初始化纯文本项的点击事件
     *
     * 【作用】
     * 给所有纯文本类型的设置项设置点击监听器，
     * 点击后弹出对应的对话框。
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

        // 多订阅源
        tv_multi_source.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("直播源历史", "live_history");
            logOperation("【设置】打开直播源历史");
        });

        // 多节目单
        tv_multi_epg.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("节目单历史", "epg_history");
            logOperation("【设置】打开节目单历史");
        });

        // 扫码添加
        tv_qr_code.setOnClickListener(v -> {
            qrCodeManager.showQRCodeDialog(currentWebUrl);
            logOperation("【设置】打开扫码管理");
        });

        // 查看解析日志
        findViewById(R.id.log_viewer).setOnClickListener(v -> {
            showLogDialog();
            logOperation("【设置】查看解析日志");
        });

        // 操作崩溃日志
        findViewById(R.id.log_operation).setOnClickListener(v -> {
            showOperationLogDialog();
            logOperation("【设置】查看操作日志");
        });

        // 开机自启 item 点击
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            sw_boot.setChecked(!sw_boot.isChecked());
        });

        // 节目单 item 点击
        findViewById(R.id.item_epg).setOnClickListener(v -> {
            sw_epg.setChecked(!sw_epg.isChecked());
        });

        // 自动更新源 item 点击
        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            sw_auto_update.setChecked(!sw_auto_update.isChecked());
        });

        // 换台反转 item 点击
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            sw_reverse.setChecked(!sw_reverse.isChecked());
        });

        // 数字选台 item 点击
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            sw_num_channel.setChecked(!sw_num_channel.isChecked());
        });

        // ====================================================================
        // ✅ 2026-06-22 新增：画中画 item 点击
        // 【作用】点击整个画中画设置项时，切换开关状态
        // 【为什么要加这个？】
        // 因为用户可能点击的是 item 的空白区域，而不是 Switch 控件本身，
        // 这样点击整个 item 都能切换开关，用户体验更好。
        // ====================================================================
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            sw_pip.setChecked(!sw_pip.isChecked());
        });
    }

    // ====================================================================
    // 按键事件处理（直接调用 TvRemoteManager）
    // ====================================================================
    /**
     * 按键事件处理
     *
     * 【直接调用 TvRemoteManager】
     * 所有按键都交给 remoteManager.dispatchKeyEvent() 统一处理，
     * 不需要在这里写任何按键逻辑，全部在回调里处理。
     *
     * 【为什么这么设计？】
     * 1. 按键逻辑统一管理，不分散在 Activity 里
     * 2. 新增按键功能只需要改 TvRemoteManager，不用改 Activity
     * 3. 和 MainActivity、ChannelPanelController 用同一套体系
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 直接交给遥控器管理器处理
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ====================================================================
    // 更新设置项焦点高亮显示
    // ====================================================================
    /**
     * 更新设置项焦点高亮显示
     *
     * 【2026-06-21 优化：从两种状态改成三种状态，和列表完全统一】
     *
     * 【原来的两种状态】
     * 1. 高亮状态：蓝色文字 + 浅蓝色背景
     * 2. 普通状态：白色文字 + 透明背景
     *
     * 【现在的三种状态】
     * 1. ✅ 选中状态：蓝色文字 + 加粗 + 浅蓝色背景（当前选中的设置项）
     * 2. ✅ 焦点状态：蓝色文字 + 常规 + 透明背景（遥控器焦点所在的项）
     * 3. ✅ 未选中状态：白色文字 + 常规 + 透明背景（普通项）
     *
     * 【为什么改成三种状态？】
     * 和频道分组、频道列表、日期列表、节目单列表保持一致的样式体系，
     * 整个应用的高亮样式统一，用户体验一致。
     *
     * 【判断优先级】
     * 选中状态 > 焦点状态 > 未选中状态
     * 如果一个项既是选中又是焦点，显示选中样式
     *
     * 【处理两种类型的设置项】
     * 1. TextView 类型：比如"屏幕比例"、"自定义订阅源"等
     * 2. ViewGroup 类型：比如"开机自启"、"检查更新"等（LinearLayout 包裹文字和开关）
     */
    private void updateSettingsFocus() {
        // 获取当前选中位置（遥控器管理器记录的位置）
        int selectedPosition = remoteManager.getSettingsFocusPosition();
        SettingsActivity.logOperation("【设置遥控】准备更新焦点，选中位置：" + (selectedPosition + 1));

        // ====================================================================
        // 遍历所有设置项，分别设置对应的样式
        // ====================================================================
        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;

            if (i == selectedPosition) {
                // ================================================================
                // ✅ 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                // ================================================================
                // 【说明】当前选中的设置项，最明显的样式
                setItemStyle(item, "#40A9FF", Typeface.BOLD, 0x3340A9FF);
                SettingsActivity.logOperation("【设置遥控】第 " + (i + 1) + " 项 → 选中状态");
                // 请求焦点（让系统知道焦点在哪）
                item.requestFocus();
                // 滚动到可见区域
                scrollToView(item);
            } else if (item.isFocused()) {
                // ================================================================
                // ✅ 焦点状态：蓝色文字 + 常规 + 透明背景
                // ================================================================
                // 【说明】遥控器焦点所在的项，文字变蓝提示焦点位置
                // 背景透明，不会和选中状态冲突
                setItemStyle(item, "#40A9FF", Typeface.NORMAL, Color.TRANSPARENT);
                SettingsActivity.logOperation("【设置遥控】第 " + (i + 1) + " 项 → 焦点状态");
            } else {
                // ================================================================
                // ✅ 未选中状态：白色文字 + 常规 + 透明背景
                // ================================================================
                // 【说明】普通项，默认样式
                setItemStyle(item, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT);
            }
        }
        SettingsActivity.logOperation("【设置遥控】焦点更新完成，当前选中位置：" + (selectedPosition + 1));
    }

    // ====================================================================
    // 辅助方法 - 设置单个设置项的样式
    // ====================================================================
    /**
     * 设置单个设置项的样式（文字颜色 + 字重 + 背景色）
     *
     * 【作用】
     * 统一封装设置项样式的逻辑，避免在 updateSettingsFocus() 里重复写代码。
     *
     * 【处理两种类型的设置项】
     * 1. TextView 类型：直接设置文字颜色和字重
     * 2. ViewGroup 类型：找到第一个 TextView，设置文字颜色和字重
     *
     * @param item 设置项 View
     * @param textColor 文字颜色（十六进制字符串，如 "#40A9FF"）
     * @param typeface 字重（Typeface.BOLD 或 Typeface.NORMAL）
     * @param bgColor 背景色（如 0x3340A9FF 或 Color.TRANSPARENT）
     */
    private void setItemStyle(View item, String textColor, int typeface, int bgColor) {
        // 设置背景色
        item.setBackgroundColor(bgColor);

        // 设置文字颜色和字重
        if (item instanceof TextView) {
            // 情况 A：当前项就是 TextView（简单项，比如"屏幕比例"）
            TextView tv = (TextView) item;
            tv.setTextColor(Color.parseColor(textColor));
            tv.setTypeface(null, typeface);
        } else if (item instanceof ViewGroup) {
            // 情况 B：当前项是 ViewGroup（复杂项，比如"开机自启"，里面有文字和开关）
            // 找第一个 TextView，设置文字颜色和字重
            TextView tv = findFirstTextView((ViewGroup) item);
            if (tv != null) {
                tv.setTextColor(Color.parseColor(textColor));
                tv.setTypeface(null, typeface);
            }
        }
    }

    // ====================================================================
    // 辅助方法 - 在 ViewGroup 中找到第一个 TextView
    // ====================================================================
    /**
     * 在 ViewGroup 中递归查找第一个 TextView
     *
     * 【作用】
     * 对于复杂的设置项（比如开机自启，LinearLayout 里有文字和开关），
     * 找到里面的标题 TextView，用来设置文字颜色。
     *
     * 【为什么用递归？】
     * 因为有的布局可能嵌套多层（比如 LinearLayout 里又套了一个 LinearLayout），
     * 递归查找能确保找到第一个 TextView。
     *
     * @param viewGroup 要查找的 ViewGroup
     * @return 找到的第一个 TextView，如果没找到返回 null
     */
    private TextView findFirstTextView(ViewGroup viewGroup) {
        if (viewGroup == null) return null;

        // 遍历所有子 View
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                // 找到了，直接返回
                return (TextView) child;
            } else if (child instanceof ViewGroup) {
                // 子 View 也是 ViewGroup，递归查找
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) {
                    return result;
                }
            }
        }
        // 没找到
        return null;
    }

    // ====================================================================
    // 辅助方法：滚动到指定 View 可见
    // ====================================================================
    /**
     * 滚动到指定 View，让它显示在可见区域内
     *
     * 【作用】
     * 当焦点移动到屏幕外的项时，自动滚动，让用户能看到焦点在哪里。
     *
     * 【滚动规则】
     * - 如果 View 在可见区域上方：滚动到顶部（留 50dp 边距）
     * - 如果 View 在可见区域下方：滚动到底部（留 50dp 边距）
     * - 如果 View 已经在可见区域内：不滚动
     *
     * @param view 要滚动到的 View
     */
    private void scrollToView(View view) {
        if (scrollView == null || view == null) return;

        // 计算 View 在 ScrollView 中的位置
        int viewTop = view.getTop();
        int viewBottom = view.getBottom();
        int scrollViewHeight = scrollView.getHeight();

        // 如果 View 在当前可见区域上方，滚动到顶部
        if (viewTop < scrollView.getScrollY()) {
            scrollView.smoothScrollTo(0, viewTop - 50);
        }
        // 如果 View 在当前可见区域下方，滚动到底部
        else if (viewBottom > scrollView.getScrollY() + scrollViewHeight) {
            scrollView.smoothScrollTo(0, viewBottom - scrollViewHeight + 50);
        }
    }

    // ====================================================================
    // 辅助方法 - 处理设置项点击
    // ====================================================================
    /**
     * 处理设置项点击/选中
     *
     * @param position 选中项的位置索引
     *
     * 【说明】
     * 模拟点击事件，触发该 View 的 OnClickListener，
     * 这样就不用重复写一遍点击逻辑了。
     */
    private void handleSettingsItemClick(int position) {
        if (position < 0 || position >= settingsItemList.size()) return;
        View item = settingsItemList.get(position);
        if (item == null) return;

        // 模拟点击（触发 OnClickListener）
        item.performClick();
        logOperation("【设置遥控】选中第 " + (position + 1) + " 项");
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
                })
                .show();
    }

    // ====================== 输入对话框（自定义源/节目单） ======================
    /**
     * 显示输入对话框
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
     */
    private void showOperationLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);

        if (OPERATION_LOG == null || OPERATION_LOG.length() == 0) {
            tv.setText("暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作，\n以及网页后台的启动、请求、响应等详细信息。");
        } else {
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
            LogManager.clearOperationLog();
            Toast.makeText(this, "操作日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    /**
     * 显示解析&播放日志对话框
     */
    private void showLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);

        if (PLAY_LOG == null || PLAY_LOG.length() == 0) {
            tv.setText("暂无日志内容，请先播放一个频道再查看。");
        } else {
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
            LogManager.clearPlayLog();
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    // ====================================================================
    // 窗口焦点变化时，重新隐藏状态栏
    // ====================================================================
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }

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

        // 释放更新管理器
        if (updateManager != null) {
            updateManager.release();
        }

        // 释放遥控器管理器
        remoteManager = null;
        settingsItemList.clear();
        settingsItemList = null;
    }
}
