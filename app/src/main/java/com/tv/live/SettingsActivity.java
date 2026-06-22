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
 * 6. 画中画开关 ✅ 新增
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
    /** 6个开关控件 */
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel, sw_pip;
    /** 纯文本点击项 */
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    /** 开机自启状态描述文本 */
    private TextView tv_boot_status;

    // ====================== 配置相关 ======================
    /** SharedPreferences 配置存储 */
    private SharedPreferences sp;

    // ====================================================================
    // 遥控器统一管理器
    // ====================================================================
    private TvRemoteManager remoteManager;

    /**
     * 可聚焦的设置项列表（按从上到下的顺序排列）
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

    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;

    // ====================== SP Key 常量 ======================
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";

    // ====================================================================
    // 全局日志系统（兼容 WebServerManager）
    // ====================================================================
    public static StringBuilder OPERATION_LOG = new StringBuilder();
    public static StringBuilder PLAY_LOG = new StringBuilder();

    public static void logOperation(String msg) {
        if (OPERATION_LOG == null) OPERATION_LOG = new StringBuilder();
        OPERATION_LOG.append(msg).append("\n");
        LogManager.logOperation(msg);
    }

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
        sw_pip = findViewById(R.id.sw_pip); // ✅ 新增：画中画开关
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
        // ====================================================================
        initSettingsItemList();

        // 初始化遥控器管理器
        initRemoteManager();

        // ====================================================================
        // 初始化各个开关的状态
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
        // ✅ 6. 画中画开关（新增）
        // ====================================================================
        sw_pip.setChecked(sp.getBoolean("pip_enable", true));
        sw_pip.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("pip_enable", isChecked).apply();
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
    private void initSettingsItemList() {
        settingsItemList.clear();

        // 按页面从上到下的顺序添加
        settingsItemList.add(findViewById(R.id.item_boot));           // 1. 开机自启
        settingsItemList.add(findViewById(R.id.item_epg));            // 2. 节目单开关
        settingsItemList.add(findViewById(R.id.item_auto_update));    // 3. 自动更新源
        settingsItemList.add(findViewById(R.id.item_reverse));        // 4. 换台反转
        settingsItemList.add(findViewById(R.id.item_num_channel));    // 5. 数字选台
        settingsItemList.add(findViewById(R.id.item_pip));            // 6. 画中画 ✅ 新增
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
        // ✅ 画中画 item 点击（新增）
        // ====================================================================
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            sw_pip.setChecked(!sw_pip.isChecked());
        });
    }

    // ====================================================================
    // 按键事件处理（直接调用 TvRemoteManager）
    // ====================================================================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ====================================================================
    // 更新设置项焦点高亮显示
    // ====================================================================
    private void updateSettingsFocus() {
        int selectedPosition = remoteManager.getSettingsFocusPosition();
        SettingsActivity.logOperation("【设置遥控】准备更新焦点，选中位置：" + (selectedPosition + 1));

        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;

            if (i == selectedPosition) {
                // 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                setItemStyle(item, "#40A9FF", Typeface.BOLD, 0x3340A9FF);
                SettingsActivity.logOperation("【设置遥控】第 " + (i + 1) + " 项 → 选中状态");
                item.requestFocus();
                scrollToView(item);
            } else if (item.isFocused()) {
                // 焦点状态：蓝色文字 + 常规 + 透明背景
                setItemStyle(item, "#40A9FF", Typeface.NORMAL, Color.TRANSPARENT);
                SettingsActivity.logOperation("【设置遥控】第 " + (i + 1) + " 项 → 焦点状态");
            } else {
                // 未选中状态：白色文字 + 常规 + 透明背景
                setItemStyle(item, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT);
            }
        }
        SettingsActivity.logOperation("【设置遥控】焦点更新完成，当前选中位置：" + (selectedPosition + 1));
    }

    // ====================================================================
    // 辅助方法 - 设置单个设置项的样式
    // ====================================================================
    private void setItemStyle(View item, String textColor, int typeface, int bgColor) {
        item.setBackgroundColor(bgColor);

        if (item instanceof TextView) {
            TextView tv = (TextView) item;
            tv.setTextColor(Color.parseColor(textColor));
            tv.setTypeface(null, typeface);
        } else if (item instanceof ViewGroup) {
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
    private TextView findFirstTextView(ViewGroup viewGroup) {
        if (viewGroup == null) return null;

        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                return (TextView) child;
            } else if (child instanceof ViewGroup) {
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    // ====================================================================
    // 辅助方法：滚动到指定 View 可见
    // ====================================================================
    private void scrollToView(View view) {
        if (scrollView == null || view == null) return;

        int viewTop = view.getTop();
        int viewBottom = view.getBottom();
        int scrollViewHeight = scrollView.getHeight();

        if (viewTop < scrollView.getScrollY()) {
            scrollView.smoothScrollTo(0, viewTop - 50);
        } else if (viewBottom > scrollView.getScrollY() + scrollViewHeight) {
            scrollView.smoothScrollTo(0, viewBottom - scrollViewHeight + 50);
        }
    }

    // ====================================================================
    // 辅助方法 - 处理设置项点击
    // ====================================================================
    private void handleSettingsItemClick(int position) {
        if (position < 0 || position >= settingsItemList.size()) return;
        View item = settingsItemList.get(position);
        if (item == null) return;

        item.performClick();
        logOperation("【设置遥控】选中第 " + (position + 1) + " 项");
    }

    // ====================== 屏幕比例对话框 ======================
    private void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(ratios, (d, w) -> {
                    sp.edit().putString("screen_ratio", ratios[w]).apply();
                    Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
                    logOperation("【设置】屏幕比例设为：" + ratios[w]);
                })
                .show();
    }

    // ====================== 输入对话框（自定义源/节目单） ======================
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

    // ====================== 日志对话框 ======================
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

        if (webServerManager != null) {
            webServerManager.stopServer();
        }

        if (bootStartManager != null) {
            bootStartManager.release();
        }

        if (updateManager != null) {
            updateManager.release();
        }

        settingsItemList.clear();
        settingsItemList = null;
        remoteManager = null;
    }
}
