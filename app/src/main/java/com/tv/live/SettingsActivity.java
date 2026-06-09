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
 * 设置界面 Activity
 * 功能：开机自启、EPG开关、源更新、换台反转、数字选台、屏幕比例、自定义源、日志查看等
 * 本次修改：新增【操作崩溃日志】按钮对接，实现崩溃日志展示/清空
 */
public class SettingsActivity extends AppCompatActivity {

    // ===================== 控件声明 =====================
    // 开关控件
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    // 文本点击控件
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    // 日志相关控件
    private TextView log_viewer, log_operation;
    // 本地配置存储
    private SharedPreferences sp;

    // ===================== 生命周期：创建 =====================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置横屏（TV端适配）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 加载设置界面布局
        setContentView(R.layout.activity_settings);

        // 初始化本地配置
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        // 绑定所有界面控件
        initView();
        // 初始化控件点击/开关事件
        initListener();
    }

    /**
     * 初始化绑定所有布局控件
     * 对应 activity_settings.xml 中的所有 id
     */
    private void initView() {
        // 开关
        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);

        // 功能文本项
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);

        // 日志控件（核心新增：操作崩溃日志）
        log_viewer = findViewById(R.id.log_viewer);       // 查看解析日志
        log_operation = findViewById(R.id.log_operation); // 操作崩溃日志
    }

    /**
     * 初始化所有控件的事件监听
     * 开关状态监听 + 文本点击监听 + 日志按钮监听
     */
    private void initListener() {
        // ===================== 开关事件 =====================
        // 开机自启开关
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        sw_boot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("boot_auto_start", isChecked).apply();
            Toast.makeText(this, "开机自启" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 节目单(EPG)开关
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_epg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            Toast.makeText(this, "节目单" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 自动更新源开关
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        sw_auto_update.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            Toast.makeText(this, "自动更新源" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 换台反转开关
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_reverse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            Toast.makeText(this, "换台反转" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 数字选台开关
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        sw_num_channel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            Toast.makeText(this, "数字选台" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // ===================== 功能点击事件（占位，可自行扩展） =====================
        tv_screen_ratio.setOnClickListener(v -> {
            Toast.makeText(this, "屏幕比例设置", Toast.LENGTH_SHORT).show();
        });
        tv_custom_source.setOnClickListener(v -> {
            Toast.makeText(this, "自定义订阅源", Toast.LENGTH_SHORT).show();
        });
        tv_custom_epg.setOnClickListener(v -> {
            Toast.makeText(this, "自定义节目单", Toast.LENGTH_SHORT).show();
        });
        tv_multi_source.setOnClickListener(v -> {
            Toast.makeText(this, "多订阅源管理", Toast.LENGTH_SHORT).show();
        });
        tv_multi_epg.setOnClickListener(v -> {
            Toast.makeText(this, "多节目单管理", Toast.LENGTH_SHORT).show();
        });
        tv_qr_code.setOnClickListener(v -> {
            Toast.makeText(this, "扫码添加源/节目单", Toast.LENGTH_SHORT).show();
        });

        // ===================== 核心：日志按钮事件对接 =====================
        // 1. 查看解析日志（原有功能）
        log_viewer.setOnClickListener(v -> showNormalLogDialog());
        // 2. 查看操作崩溃日志（本次新增对接）
        log_operation.setOnClickListener(v -> showCrashLogDialog());
    }

    /**
     * 展示【普通解析日志】弹窗
     * 读取 MainActivity 中的公共日志列表，展示所有运行日志
     */
    private void showNormalLogDialog() {
        // 创建滚动视图，防止日志过长
        ScrollView scrollView = new ScrollView(this);
        // 创建日志文本展示控件
        TextView tvLog = new TextView(this);
        tvLog.setTextColor(Color.WHITE);    // 白色文字
        tvLog.setTextSize(14);             // 文字大小
        tvLog.setPadding(20, 20, 20, 20);  // 内边距

        // 拼接日志内容
        StringBuilder sb = new StringBuilder();
        for (String log : MainActivity.logList) {
            sb.append(log).append("\n");
        }

        // 设置日志文本，无日志时显示提示
        tvLog.setText(sb.length() > 0 ? sb.toString() : "暂无解析运行日志");
        scrollView.addView(tvLog);

        // 构建弹窗
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📄 解析运行日志");
        builder.setView(scrollView);
        // 关闭按钮
        builder.setPositiveButton("关闭", null);
        // 清空日志按钮
        builder.setNegativeButton("清空日志", (dialog, which) -> {
            MainActivity.logList.clear();
            Toast.makeText(this, "解析日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    /**
     * 展示【操作崩溃日志】弹窗
     * 过滤 MainActivity 中的崩溃日志（带【崩溃】标识），单独展示
     * 支持：查看崩溃堆栈、单独清空崩溃日志
     */
    private void showCrashLogDialog() {
        // 创建滚动视图
        ScrollView scrollView = new ScrollView(this);
        // 创建日志文本控件
        TextView tvLog = new TextView(this);
        tvLog.setTextColor(Color.RED);      // 崩溃日志标红，醒目区分
        tvLog.setTextSize(14);             // 文字大小
        tvLog.setPadding(20, 20, 20, 20);  // 内边距

        // 过滤日志：只保留包含【崩溃】的日志
        List<String> crashLogs = new ArrayList<>();
        for (String log : MainActivity.logList) {
            if (log.contains("【崩溃】")) {
                crashLogs.add(log);
            }
        }

        // 设置展示内容
        if (crashLogs.isEmpty()) {
            tvLog.setText("✅ 暂无崩溃日志，应用运行正常！");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String log : crashLogs) {
                sb.append(log).append("\n");
            }
            tvLog.setText(sb.toString());
        }

        scrollView.addView(tvLog);

        // 构建崩溃日志弹窗
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📌 操作崩溃日志");
        builder.setView(scrollView);
        // 关闭按钮
        builder.setPositiveButton("关闭", null);
        // 单独清空崩溃日志（不影响普通日志）
        builder.setNegativeButton("清空崩溃日志", (dialog, which) -> {
            MainActivity.logList.removeIf(log -> log.contains("【崩溃】"));
            Toast.makeText(this, "崩溃日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    /**
     * 获取当前设备WiFi的IP地址
     * 用于网页设置提示：http://设备IP:10481
     */
    private String getDeviceIPAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ipInt = wifiInfo.getIpAddress();
        return String.format("%d.%d.%d.%d",
                (ipInt & 0xff),
                (ipInt >> 8) & 0xff,
                (ipInt >> 16) & 0xff,
                (ipInt >> 24) & 0xff);
    }
}
