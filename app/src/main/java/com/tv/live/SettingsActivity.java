package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

/**
 * 电视直播应用 - 设置界面 Activity
 * 核心功能：
 * 1. 基础功能开关配置（开机自启、EPG节目单、源自动更新、换台反转、数字选台）
 * 2. 扩展功能入口（屏幕比例、自定义源/EPG、多源管理、扫码添加）
 * 3. 日志管理（解析运行日志查看/清空、崩溃日志筛选查看/单独清空）
 * 4. 设备IP地址获取（用于网页端配置提示）
 * 
 * 版本迭代：
 * - 基础版：实现各类开关配置和基础功能入口
 * - 新增版：对接崩溃日志功能，支持崩溃日志单独筛选、展示、清空
 * 
 * 适配说明：强制横屏显示，适配TV端操作逻辑
 * 存储说明：所有配置项存储在名为 "app_settings" 的 SharedPreferences 中，模式为私有
 */
public class SettingsActivity extends AppCompatActivity {

    // ===================== 界面控件声明区 =====================
    // 基础功能开关控件
    private Switch sw_boot;         // 开机自启开关
    private Switch sw_epg;          // EPG节目单开关
    private Switch sw_auto_update;  // 直播源自动更新开关
    private Switch sw_reverse;      // 换台方向反转开关
    private Switch sw_num_channel;  // 数字按键选台开关

    // 扩展功能文本按钮（点击触发对应功能）
    private TextView tv_screen_ratio;   // 屏幕比例设置入口
    private TextView tv_custom_source;  // 自定义订阅源入口
    private TextView tv_custom_epg;     // 自定义EPG节目单入口
    private TextView tv_multi_source;   // 多订阅源管理入口
    private TextView tv_multi_epg;      // 多EPG节目单管理入口
    private TextView tv_qr_code;        // 扫码添加源/节目单入口

    // 日志管理相关文本按钮
    private TextView log_viewer;    // 查看解析运行日志按钮
    private TextView log_operation; // 查看/操作崩溃日志按钮（核心新增）

    // 本地配置存储实例：用于持久化保存用户设置
    private SharedPreferences sp;

    // ===================== Activity 生命周期 =====================
    /**
     * Activity创建时的初始化逻辑
     * 1. 设置屏幕方向（强制横屏，适配TV端）
     * 2. 加载布局文件
     * 3. 初始化SharedPreferences配置存储
     * 4. 绑定界面控件
     * 5. 初始化控件事件监听
     * 
     * @param savedInstanceState 保存的Activity状态（如横竖屏切换、后台回收恢复）
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置屏幕为横屏（TV端应用固定横屏显示）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 加载设置界面布局文件（activity_settings.xml）
        setContentView(R.layout.activity_settings);

        // 初始化SharedPreferences：私有模式，仅本应用可访问
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        // 绑定布局中的所有控件ID
        initView();
        // 为所有控件设置事件监听（开关状态变化、按钮点击）
        initListener();
    }

    /**
     * 初始化绑定所有界面控件
     * 功能：将Java控件对象与XML布局中的ID一一绑定
     * 注意：需确保XML布局中对应ID存在，否则会抛出NullPointerException
     */
    private void initView() {
        // 绑定基础功能开关控件
        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);

        // 绑定扩展功能文本按钮控件
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);

        // 绑定日志管理控件（核心新增功能）
        log_viewer = findViewById(R.id.log_viewer);       // 解析运行日志查看按钮
        log_operation = findViewById(R.id.log_operation); // 崩溃日志操作按钮
    }

    /**
     * 初始化所有控件的事件监听逻辑
     * 分三类监听：
     * 1. 开关控件：状态变化时保存配置并提示用户
     * 2. 文本按钮：点击时给出功能提示（占位，可扩展具体逻辑）
     * 3. 日志按钮：点击时弹出对应日志弹窗（核心新增）
     */
    private void initListener() {
        // ===================== 基础开关 - 开机自启 =====================
        // 初始化开关状态：读取SharedPreferences中保存的值，默认关闭
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        // 开关状态变化监听：保存新状态并提示用户
        sw_boot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("boot_auto_start", isChecked).apply();
            Toast.makeText(this, "开机自启" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // ===================== 基础开关 - EPG节目单 =====================
        sw_epg.setChecked(sp.getBoolean("epg_enable", true)); // 默认开启
        sw_epg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            Toast.makeText(this, "节目单" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // ===================== 基础开关 - 自动更新源 =====================
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true)); // 默认开启
        sw_auto_update.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            Toast.makeText(this, "自动更新源" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // ===================== 基础开关 - 换台反转 =====================
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false)); // 默认关闭
        sw_reverse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            Toast.makeText(this, "换台反转" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // ===================== 基础开关 - 数字选台 =====================
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true)); // 默认开启
        sw_num_channel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            Toast.makeText(this, "数字选台" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // ===================== 扩展功能 - 文本按钮点击（占位） =====================
        // 屏幕比例设置（占位：可扩展分辨率/缩放比例选择逻辑）
        tv_screen_ratio.setOnClickListener(v -> {
            Toast.makeText(this, "屏幕比例设置", Toast.LENGTH_SHORT).show();
        });
        // 自定义订阅源（占位：可扩展输入URL/导入本地文件逻辑）
        tv_custom_source.setOnClickListener(v -> {
            Toast.makeText(this, "自定义订阅源", Toast.LENGTH_SHORT).show();
        });
        // 自定义EPG节目单（占位：可扩展EPG URL配置逻辑）
        tv_custom_epg.setOnClickListener(v -> {
            Toast.makeText(this, "自定义节目单", Toast.LENGTH_SHORT).show();
        });
        // 多订阅源管理（占位：可扩展源列表增删改查逻辑）
        tv_multi_source.setOnClickListener(v -> {
            Toast.makeText(this, "多订阅源管理", Toast.LENGTH_SHORT).show();
        });
        // 多EPG节目单管理（占位：可扩展EPG列表增删改查逻辑）
        tv_multi_epg.setOnClickListener(v -> {
            Toast.makeText(this, "多节目单管理", Toast.LENGTH_SHORT).show();
        });
        // 扫码添加源/节目单（占位：可扩展ZXing扫码解析URL逻辑）
        tv_qr_code.setOnClickListener(v -> {
            Toast.makeText(this, "扫码添加源/节目单", Toast.LENGTH_SHORT).show();
        });

        // ===================== 核心新增 - 日志管理按钮监听 =====================
        // 1. 查看解析运行日志（原有功能：展示所有运行日志）
        log_viewer.setOnClickListener(v -> showNormalLogDialog());
        // 2. 查看/操作崩溃日志（新增功能：仅展示崩溃相关日志，支持单独清空）
        log_operation.setOnClickListener(v -> showCrashLogDialog());
    }

    /**
     * 展示【解析运行日志】弹窗
     * 功能说明：
     * 1. 读取MainActivity中公共日志列表（logList）的所有日志
     * 2. 用ScrollView包裹文本，支持长日志滚动查看
     * 3. 提供「关闭」和「清空日志」按钮
     * 4. 无日志时显示"暂无解析运行日志"提示
     */
    private void showNormalLogDialog() {
        // 创建滚动视图：解决长日志无法完全展示的问题
        ScrollView scrollView = new ScrollView(this);
        // 创建日志展示文本控件：配置样式（白色文字、字号、内边距）
        TextView tvLog = new TextView(this);
        tvLog.setTextColor(Color.WHITE);    // 日志文字设为白色（适配TV端深色背景）
        tvLog.setTextSize(14);             // 字号适配TV端遥控器操作
        tvLog.setPadding(20, 20, 20, 20);  // 内边距避免文字贴边

        // 拼接所有解析运行日志
        StringBuilder sb = new StringBuilder();
        for (String log : MainActivity.logList) {
            sb.append(log).append("\n"); // 每条日志换行展示
        }

        // 设置日志文本：无日志时显示提示语
        tvLog.setText(sb.length() > 0 ? sb.toString() : "暂无解析运行日志");
        scrollView.addView(tvLog); // 将文本控件添加到滚动视图

        // 构建日志弹窗
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📄 解析运行日志"); // 标题带图标，更直观
        builder.setView(scrollView);         // 设置弹窗内容为滚动视图
        // 弹窗按钮1：关闭弹窗（无额外逻辑）
        builder.setPositiveButton("关闭", null);
        // 弹窗按钮2：清空解析运行日志
        builder.setNegativeButton("清空日志", (dialog, which) -> {
            MainActivity.logList.clear(); // 清空公共日志列表
            Toast.makeText(this, "解析日志已清空", Toast.LENGTH_SHORT).show(); // 反馈用户操作结果
        });
        builder.show(); // 显示弹窗
    }

    /**
     * 展示【操作崩溃日志】弹窗（核心新增功能）
     * 功能说明：
     * 1. 从MainActivity.logList中筛选含「【崩溃】」标识的日志
     * 2. 崩溃日志用红色文字展示，醒目区分
     * 3. 无崩溃日志时显示"✅ 暂无崩溃日志，应用运行正常！"
     * 4. 提供「关闭」和「清空崩溃日志」按钮（仅清空崩溃日志，不影响普通日志）
     */
    private void showCrashLogDialog() {
        // 创建滚动视图：支持崩溃日志滚动查看
        ScrollView scrollView = new ScrollView(this);
        // 创建崩溃日志展示文本控件：红色文字突出显示崩溃信息
        TextView tvLog = new TextView(this);
        tvLog.setTextColor(Color.RED);      // 崩溃日志标红，便于快速识别
        tvLog.setTextSize(14);             // 字号适配TV端
        tvLog.setPadding(20, 20, 20, 20);  // 内边距优化显示效果

        // 筛选日志：仅保留包含「【崩溃】」标识的日志
        List<String> crashLogs = new ArrayList<>();
        for (String log : MainActivity.logList) {
            if (log.contains("【崩溃】")) {
                crashLogs.add(log);
            }
        }

        // 设置崩溃日志展示内容
        if (crashLogs.isEmpty()) {
            // 无崩溃日志时显示正常提示（绿色对勾图标）
            tvLog.setText("✅ 暂无崩溃日志，应用运行正常！");
        } else {
            // 有崩溃日志时拼接所有崩溃信息
            StringBuilder sb = new StringBuilder();
            for (String log : crashLogs) {
                sb.append(log).append("\n");
            }
            tvLog.setText(sb.toString());
        }

        scrollView.addView(tvLog); // 将文本控件添加到滚动视图

        // 构建崩溃日志弹窗
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📌 操作崩溃日志"); // 标题带图标，区分普通日志
        builder.setView(scrollView);         // 设置弹窗内容为滚动视图
        // 弹窗按钮1：关闭弹窗
        builder.setPositiveButton("关闭", null);
        // 弹窗按钮2：单独清空崩溃日志（保留普通日志）
        builder.setNegativeButton("清空崩溃日志", (dialog, which) -> {
            // 移除列表中所有含「【崩溃】」的日志
            MainActivity.logList.removeIf(log -> log.contains("【崩溃】"));
            Toast.makeText(this, "崩溃日志已清空", Toast.LENGTH_SHORT).show(); // 反馈操作结果
        });
        builder.show(); // 显示弹窗
    }

    /**
     * 获取当前设备连接WiFi的IP地址
     * 用途：用于网页端配置提示（如 "http://设备IP:10481"）
     * 注意事项：
     * 1. 需要添加权限：<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
     * 2. 仅在WiFi连接状态下能获取有效IP，无WiFi时返回0.0.0.0
     * 
     * @return String 格式化的IPv4地址（如：192.168.1.100）
     */
    private String getDeviceIPAddress() {
        // 获取WiFi管理服务实例
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        // 获取当前WiFi连接信息
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        // 获取IP地址（整型，需转换为点分十进制）
        int ipInt = wifiInfo.getIpAddress();
        // 将整型IP转换为标准IPv4格式
        return String.format("%d.%d.%d.%d",
                (ipInt & 0xff),
                (ipInt >> 8) & 0xff,
                (ipInt >> 16) & 0xff,
                (ipInt >> 24) & 0xff);
    }
}
