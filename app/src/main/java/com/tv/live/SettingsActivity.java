package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Switch;
import android.widget.TextView;

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
 * 6. 画中画开关
 * 7. 屏幕比例设置
 * 8. 自定义订阅源/节目单
 * 9. 多订阅源/节目单管理（委托给 SourceDialogManager）
 * 10. 扫码添加（委托给 QRCodeManager）
 * 11. 解析&播放日志查看
 * 12. 操作日志查看
 * 13. 检查更新（委托给 UpdateManager）
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
 *
 * 【2026-06-20 优化：设置项高亮改成代码动态设置，肯定生效】
 * 【优化原因】
 * 导致遥控器操作时看不到焦点高亮在哪里。
 * 【优化方案】
 * 改成代码动态设置背景色和文字颜色，不依赖布局里的 drawable 和 color selector，
 * 肯定能看到焦点，而且和频道面板的高亮样式完全统一。
 *
 * 【2026-06-20 优化：手机点击时光标跟随移动】
 * 因为点击只触发了 OnClickListener，没有更新焦点位置。
 * 【优化方案】
 * 1. 给每个设置项设置 focusableInTouchMode=true（手机点击时也能获得焦点）
 * 2. 给每个设置项设置 OnFocusChangeListener（焦点变化时自动更新高亮）
 *
 * 【2026-06-21 优化：统一三种状态样式，和列表完全一致】
 * 从两种状态（选中/普通）改成三种状态：
 * 1. 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
 * 2. 焦点状态：蓝色文字 + 常规 + 透明背景
 * 3. 未选中状态：白色文字 + 常规 + 透明背景
 * 【为什么改成三种状态？】
 * 【判断优先级】
 *
 * 【2026-06-22 新增：画中画设置】
 * 【功能说明】
 * 1. 添加画中画开关
 * 2. 画中画开启后，按 Home 键自动进入画中画模式
 * 3. 画中画模式下可以继续播放视频
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
    private android.widget.ScrollView scrollView;

    // ====================================================================
    // 管理器相关（全部拆分后）
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

    // 全局日志系统（加回兼容层）
    public static void logOperation(String msg) {
        LogManager.logOperation(msg);
    }

    public static void log(String msg) {
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
        // ✅ 新增：初始化设置项列表（遥控器焦点顺序）
        // ====================================================================
        initSettingsItemList();

        // ✅ 新增：初始化遥控器管理器
        initRemoteManager();

        // ====================================================================
        // 初始化各个开关的状态
        // ====================================================================

        // 1. 开机自启（委托给 BootStartManager）
        bootStartManager.initSwitch(sw_boot, tv_boot_status);

        // 2. 节目单开关
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_epg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            logOperation("【设置】节目单开关 → " + (isChecked ? "开启" : "关闭"));
        });

        // 3. 自动更新源（委托给 AutoUpdateManager）
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

        // 点击整个 item 也能切换开关
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            sw_pip.setChecked(!sw_pip.isChecked());
        });

        // ====================================================================
        // 检查更新（真正的版本检测 + 自动下载安装）
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
     *
     * 【2026-06-20 优化：加上焦点变化监听器，支持手机点击时光标跟随】
     * 手机点击设置项时，光标（高亮）不会跟着移动，
     * 因为点击只触发了 OnClickListener，没有更新焦点位置。
     * 【优化方案】
     * 1. 给每个设置项设置 focusableInTouchMode=true（手机点击时也能获得焦点）
     * 2. 给每个设置项设置 OnFocusChangeListener（焦点变化时自动更新高亮）
     * 这样无论是遥控器操作还是手机点击，光标都会跟着移动。
     */
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

        // ✅ 2026-06-20 新增：给每个设置项设置焦点变化监听器
        for (int i = 0; i < settingsItemList.size(); i++) {
            final int position = i;
            View item = settingsItemList.get(i);
            if (item == null) continue;

            // 手机点击时也能获得焦点
            item.setFocusableInTouchMode(true);

            // 焦点变化时更新高亮
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    remoteManager.setSettingsFocus(position);
                }
            });
        }
    }

    // ====================================================================
    // ✅ 新增：初始化遥控器管理器
    // ====================================================================
    /**
     * 初始化遥控器管理器
     *
     * 【步骤】
     * 1. 创建 TvRemoteManager 实例
     * 2. 设置为 SETTINGS_MODE
     * 3. 设置设置项列表
     * 4. 设置回调监听器，处理各种按键操作
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
                int position = remoteManager.getSettingsFocus();
                handleSettingsItemClick(position);
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
            sourceDialogManager.showSourceDialog();
            logOperation("【设置】打开多订阅源管理");
        });

        // 多节目单
        tv_multi_epg.setOnClickListener(v -> {
            sourceDialogManager.showEpgDialog();
            logOperation("【设置】打开多节目单管理");
        });

        // 扫码添加
        tv_qr_code.setOnClickListener(v -> {
            qrCodeManager.showQRCode();
            logOperation("【设置】打开扫码添加");
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
    }

    // ====================================================================
    // ✅ 新增：按键事件处理（直接调用 TvRemoteManager）
    // ====================================================================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null) {
            boolean handled = remoteManager.dispatchKey(keyCode);
            if (handled) return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ====================================================================
    // ✅ 2026-06-21 优化：统一三种状态样式，和列表完全一致
    // ====================================================================
    /**
     * 更新设置项焦点高亮
     *
     * 【三种状态】
     * 1. 选中状态（焦点所在项）：蓝色文字 + 加粗 + 浅蓝色背景
     * 2. 普通状态：白色文字 + 常规 + 透明背景
     *
     * 【为什么用代码动态设置？】
     * 不依赖布局里的 drawable 和 color selector，肯定能看到焦点。
     */
    private void updateSettingsFocus() {
        if (remoteManager == null) return;
        int focusPosition = remoteManager.getSettingsFocus();

        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;

            if (i == focusPosition) {
                // 焦点项：蓝色文字 + 加粗 + 浅蓝色背景
                setItemStyle(item, "#40A9FF", android.graphics.Typeface.BOLD, 0x3340A9FF);
                SettingsActivity.logOperation("【设置遥控】第 " + (i + 1) + " 项 → 选中状态");

                // 请求焦点（让系统知道焦点在哪）
                item.requestFocus();

                // 滚动到可见区域
                scrollView.requestChildFocus(item, item);
            } else {
                // 普通项：白色文字 + 常规 + 透明背景
                setItemStyle(item, "#FFFFFF", android.graphics.Typeface.NORMAL, 0x00000000);
            }
        }
    }

    // ====================================================================
    // ✅ 2026-06-21 新增：辅助方法 - 设置单个设置项的样式
    // ====================================================================
    /**
     * 设置单个设置项的样式
     *
     * @param item  设置项 View
     * @param textColor 文字颜色
     * @param typeface  字体样式（加粗/常规）
     * @param bgColor   背景颜色
     */
    private void setItemStyle(View item, String textColor, int typeface, int bgColor) {
        // 设置背景色
        item.setBackgroundColor(bgColor);

        // 找到所有 TextView，设置文字颜色和字体
        if (item instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) item;
            for (int j = 0; j < group.getChildCount(); j++) {
                View child = group.getChildAt(j);
                if (child instanceof TextView) {
                    TextView tv = (TextView) child;
                    tv.setTextColor(android.graphics.Color.parseColor(textColor));
                    tv.setTypeface(null, typeface);
                } else if (child instanceof android.view.ViewGroup) {
                    // 嵌套的 LinearLayout，继续找
                    setTextStyleInGroup((android.view.ViewGroup) child, textColor, typeface);
                }
            }
        } else if (item instanceof TextView) {
            TextView tv = (TextView) item;
            tv.setTextColor(android.graphics.Color.parseColor(textColor));
            tv.setTypeface(null, typeface);
        }
    }

    /**
     * 在 ViewGroup 中递归设置 TextView 样式
     */
    private void setTextStyleInGroup(android.view.ViewGroup group, String textColor, int typeface) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                tv.setTextColor(android.graphics.Color.parseColor(textColor));
                tv.setTypeface(null, typeface);
            } else if (child instanceof android.view.ViewGroup) {
                setTextStyleInGroup((android.view.ViewGroup) child, textColor, typeface);
            }
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
                    android.widget.Toast.makeText(this, "已设置", android.widget.Toast.LENGTH_SHORT).show();
                    logOperation("【设置】屏幕比例 → " + ratios[w]);
                })
                .show();
    }

    // ====================== 输入对话框（自定义源/节目单） ======================
    /**
     * 显示输入对话框
     */
    private void showInputDialog(String title, String hint, String key) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(hint);
        input.setText(sp.getString(key, ""));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    sp.edit().putString(key, value).apply();
                    android.widget.Toast.makeText(this, "已保存", android.widget.Toast.LENGTH_SHORT).show();
                    logOperation("【设置】" + title + " → " + value);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ====================== 日志对话框 ======================
    /**
     * 显示解析日志对话框
     */
    private void showLogDialog() {
        StringBuilder logText = new StringBuilder();
        for (String log : MainActivity.logList) {
            logText.append(log).append("\n");
        }

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(logText.toString());
        textView.setTextSize(12);
        textView.setTextColor(android.graphics.Color.WHITE);
        textView.setPadding(20, 20, 20, 20);
        scrollView.addView(textView);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("解析日志")
                .setView(scrollView)
                .setPositiveButton("确定", null);

        builder.setNeutralButton("清空日志", (dialog, which) -> {
            MainActivity.logList.clear();
            LogManager.clearPlayLog();
            android.widget.Toast.makeText(this, "日志已清空", android.widget.Toast.LENGTH_SHORT).show();
        });

        builder.show();
    }

    /**
     * 显示操作日志对话框
     */
    private void showOperationLogDialog() {
        String logText = LogManager.getOperationLog();

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(logText);
        textView.setTextSize(12);
        textView.setTextColor(android.graphics.Color.WHITE);
        textView.setPadding(20, 20, 20, 20);
        scrollView.addView(textView);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("操作日志")
                .setView(scrollView)
                .setPositiveButton("确定", null);

        builder.setNeutralButton("清空日志", (dialog, which) -> {
            LogManager.clearOperationLog();
            android.widget.Toast.makeText(this, "日志已清空", android.widget.Toast.LENGTH_SHORT).show();
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
