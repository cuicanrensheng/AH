package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
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
 * 【2026-06-20 新增：接入 TvRemoteManager 统一遥控器管理】
 * 【集成说明】
 * 1. 创建设置项列表，统一管理所有可聚焦的设置项
 * 2. 初始化 TvRemoteManager，设置为 SETTINGS_MODE
 * 3. 在 onKeyDown 中统一分发按键
 * 4. 通过回调处理焦点移动和选中操作
 *
 * 【遥控器操作】
 * - ↑/↓：在设置项之间上下移动
 * - OK/确认：选中当前项（点击/切换开关）
 * - 返回/菜单：关闭设置页面
 *
 * 【效果】
 * - 统一的遥控器按键处理
 * - 完整的操作日志
 * - 焦点位置记忆
 * - 易于扩展（新增设置项只需要加到列表里）
 */
public class SettingsActivity extends AppCompatActivity {

    // ====================== 控件声明 ======================

    /** 5个开关控件 */
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;

    /** 纯文本点击项 */
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;

    /** 开机自启状态描述文本 */
    private TextView tv_boot_status;

    // ====================== 配置相关 ======================

    /** SharedPreferences 配置存储 */
    private SharedPreferences sp;

    // ====================================================================
    // ✅ 新增：遥控器统一管理器
    // ====================================================================

    /**
     * 遥控器统一管理器
     *
     * 【功能】
     * 统一管理设置页面的所有遥控器按键操作
     *
     * 【为什么用统一管理器？】
     * 1. 所有按键逻辑集中管理，不分散
     * 2. 新增/删除设置项只需要调整列表，不用改按键逻辑
     * 3. 自带完整的操作日志，方便排查问题
     * 4. 和 MainActivity、ChannelPanelController 用同一套体系
     */
    private TvRemoteManager remoteManager;

    /**
     * 可聚焦的设置项列表（按从上到下的顺序排列）
     *
     * 【说明】
     * 所有需要遥控器焦点的 View 都加到这个列表里，
     * 遥控器上下键就按这个顺序移动焦点。
     *
     * 【为什么用列表？】
     * 新增/删除设置项只需要调整这个列表，
     * 不用修改任何按键处理逻辑，非常方便。
     */
    private List<View> settingsItemList = new ArrayList<>();

    /** 滚动视图（用于滚动到可见区域） */
    private ScrollView scrollView;

    // ====================================================================
    // 管理器相关（全部拆分后）
    // ====================================================================

    private BootStartManager bootStartManager;
    private AutoUpdateManager autoUpdateManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;

    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;

    // ====================================================================
    // 应用更新管理器
    // ====================================================================

    private UpdateManager updateManager;

    // ====================== SP Key 常量 ======================

    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";

    // ====================================================================
    // 全局日志系统（加回兼容层）
    // ====================================================================

    public static volatile StringBuilder PLAY_LOG = new StringBuilder();
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();

    public static void log(String msg) {
        LogManager.log(msg);
        PLAY_LOG = new StringBuilder(LogManager.getPlayLog());
    }

    public static void logOperation(String msg) {
        LogManager.logOperation(msg);
        OPERATION_LOG = new StringBuilder(LogManager.getOperationLog());
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
        // ====================================================================
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.dimAmount = 0f;
        getWindow().setAttributes(layoutParams);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        // ===== 窗口设置 =====
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        // ====================================================================
        // 点击左侧空白区域关闭设置
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
        // ✅ 新增：初始化设置项列表（遥控器焦点顺序）
        // ====================================================================
        initSettingsItemList();

        // ====================================================================
        // ✅ 新增：初始化遥控器管理器
        // ====================================================================
        initRemoteManager();

        // ===== 日志查看按钮 =====
        findViewById(R.id.log_viewer).setOnClickListener(v -> {
            showLogDialog();
        });
        findViewById(R.id.log_operation).setOnClickListener(v -> {
            showOperationLogDialog();
        });

        // ====================================================================
        // 开机自启（委托给 BootStartManager）
        // ====================================================================
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        bootStartManager.updateBootStatusText(tv_boot_status);
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            boolean isChecked = !sw_boot.isChecked();
            sw_boot.setChecked(isChecked);
            bootStartManager.toggleBoot(isChecked, tv_boot_status);
        });
        findViewById(R.id.item_boot).setOnLongClickListener(v -> {
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
        // 检查更新（真正的版本检测 + 自动下载安装）
        // ====================================================================
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
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

    // ====================================================================
    // ✅ 新增：初始化设置项列表（遥控器焦点顺序）
    // ====================================================================

    /**
     * 初始化设置项列表
     *
     * 【重要】按页面从上到下的顺序添加，遥控器上下键就按这个顺序移动
     *
     * 【为什么用列表？】
     * 新增/删除设置项只需要调整这个列表，
     * 不用修改任何按键处理逻辑，非常方便。
     *
     * 【怎么新增设置项？】
     * 1. 在布局里添加新的设置项
     * 2. 在 initViews 里 findViewById
     * 3. 在这个方法里 add 到列表里（按顺序）
     * 4. 搞定！遥控器自动支持
     */
    private void initSettingsItemList() {
        settingsItemList.clear();

        // 按页面从上到下的顺序添加
        settingsItemList.add(findViewById(R.id.item_boot));           // 1. 开机自启
        settingsItemList.add(findViewById(R.id.item_epg));            // 2. 节目单开关
        settingsItemList.add(findViewById(R.id.item_auto_update));    // 3. 自动更新源
        settingsItemList.add(findViewById(R.id.item_reverse));        // 4. 换台反转
        settingsItemList.add(findViewById(R.id.item_num_channel));    // 5. 数字选台
        settingsItemList.add(findViewById(R.id.tv_screen_ratio));     // 6. 屏幕比例
        settingsItemList.add(findViewById(R.id.tv_custom_source));    // 7. 自定义订阅源
        settingsItemList.add(findViewById(R.id.tv_custom_epg));       // 8. 自定义节目单
        settingsItemList.add(findViewById(R.id.tv_multi_source));     // 9. 多订阅源
        settingsItemList.add(findViewById(R.id.tv_multi_epg));        // 10. 多节目单
        settingsItemList.add(findViewById(R.id.tv_qr_code));          // 11. 扫码添加
        settingsItemList.add(findViewById(R.id.log_viewer));          // 12. 查看解析日志
        settingsItemList.add(findViewById(R.id.log_operation));       // 13. 操作日志
        settingsItemList.add(findViewById(R.id.item_check_update));   // 14. 检查更新

        // 移除 null 的项（防止有的 View 找不到）
        for (int i = settingsItemList.size() - 1; i >= 0; i--) {
            if (settingsItemList.get(i) == null) {
                settingsItemList.remove(i);
            }
        }
    }

    // ====================================================================
    // ✅ 新增：初始化遥控器管理器
    // ====================================================================

    /**
     * 初始化遥控器管理器
     *
     * 【集成说明】
     * 1. 创建 TvRemoteManager 实例
     * 2. 设置为 SETTINGS_MODE（设置模式）
     * 3. 设置设置项总数
     * 4. 设置回调监听器，处理各种按键操作
     * 5. 默认聚焦第一项
     */
    private void initRemoteManager() {
        // 创建遥控器管理器
        remoteManager = new TvRemoteManager();

        // 设置为设置模式
        remoteManager.setMode(TvRemoteManager.Mode.SETTINGS_MODE);

        // 设置设置项总数
        remoteManager.setSettingsItemCount(settingsItemList.size());

        // 设置回调监听器
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {

            // ================== 播放模式回调（设置页面用不到，空实现） ==================
            @Override public void onPlayChannelUp() {}
            @Override public void onPlayChannelDown() {}
            @Override public void onPlayTogglePanel() {}
            @Override public void onPlayOpenSettings() {}
            @Override public boolean onPlayBack() { return false; }

            // ================== 频道面板模式回调（设置页面用不到，空实现） ==================
            @Override public void onPanelMoveUp() {}
            @Override public void onPanelMoveDown() {}
            @Override public void onPanelMoveLeft() {}
            @Override public void onPanelMoveRight() {}
            @Override public void onPanelConfirm() {}
            @Override public boolean onPanelBack() { return false; }
            @Override public void onPanelMenu() {}
            @Override public void onPanelNumber(int number) {}
            @Override public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {}

            // ================== 设置模式回调（核心，需要实现） ==================

            /**
             * 上移：焦点向上移动一项
             */
            @Override
            public void onSettingsMoveUp() {
                updateSettingsFocus();
            }

            /**
             * 下移：焦点向下移动一项
             */
            @Override
            public void onSettingsMoveDown() {
                updateSettingsFocus();
            }

            /**
             * OK键：选中当前项
             */
            @Override
            public void onSettingsConfirm() {
                int position = remoteManager.getSettingsFocusPosition();
                handleSettingsItemClick(position);
            }

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
             * 焦点变化：更新高亮显示
             */
            @Override
            public void onSettingsFocusChanged(int position) {
                updateSettingsFocus();
            }
        });

        // 默认聚焦第一项
        updateSettingsFocus();
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
    }

    // ====================================================================
    // ✅ 新增：按键事件处理（直接调用 TvRemoteManager）
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
    // ✅ 2026-06-20 优化：统一高亮样式，只用 setSelected 一种
    // ====================================================================

    /**
     * 更新设置项焦点高亮显示
     *
     * 【2026-06-20 优化：统一高亮，只用 setSelected 一种】
     *
     * 【原来的问题】
     * 同时设置了 setSelected(true) 和 setActivated(true)，
     * 和系统默认的焦点框叠加，导致有多个光标/高亮，很乱。
     *
     * 【优化方案】
     * 只用 setSelected 一种高亮方式，去掉 setActivated，
     * 保留 requestFocus（电视上必须有焦点，不然按键有问题）。
     *
     * 【效果】
     * - 只有一种高亮，清晰明了
     * - 遥控器移动到哪哪就亮
     * - 手机点击哪个哪个亮
     * - 不会有多个光标叠加的问题
     */
    private void updateSettingsFocus() {
        int position = remoteManager.getSettingsFocusPosition();

        // 1. 清除所有项的高亮
        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item != null) {
                // ✅ 只用 setSelected 一种高亮方式
                item.setSelected(false);
                // ❌ 已删除：item.setActivated(false);
                // 去掉 setActivated，减少一种高亮叠加
            }
        }

        // 2. 给当前焦点项设置高亮
        if (position >= 0 && position < settingsItemList.size()) {
            View currentItem = settingsItemList.get(position);
            if (currentItem != null) {
                // ✅ 只用 setSelected 一种高亮方式
                currentItem.setSelected(true);

                // ❌ 已删除：currentItem.setActivated(true);
                // 去掉 setActivated，减少一种高亮叠加

                // ✅ 保留 requestFocus，电视上必须有焦点
                // 不然遥控器按键事件分发可能有问题
                currentItem.requestFocus();

                // 3. 滚动到可见区域
                scrollToView(currentItem);
            }
        }

        logOperation("【设置遥控】焦点移动到第 " + (position + 1) + " 项");
    }
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
    // ✅ 新增：辅助方法 - 处理设置项点击
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
                }).show();
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
