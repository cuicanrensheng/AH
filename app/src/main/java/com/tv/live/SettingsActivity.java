package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
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
 * 电视直播应用 - 设置页面
 * 功能：配置开关、日志查看、崩溃日志管理、设备信息展示
 * 最新修复：添加静态 log() 方法，解决 MainActivity 调用报错
 */
public class SettingsActivity extends AppCompatActivity {

    // ======================== 【编译报错修复】核心静态 log 方法 ========================
    /**
     * 供 MainActivity 调用的日志方法
     * 解决错误：找不到符号 SettingsActivity.log(String)
     */
    public static void log(String msg) {
        if (MainActivity.logList != null) {
            // 日志插入到最前面，最新日志优先显示
            MainActivity.logList.add(0, msg);
            // 限制最多100条，防止内存溢出
            if (MainActivity.logList.size() > 100) {
                MainActivity.logList.remove(MainActivity.logList.size() - 1);
            }
        }
    }

    // ======================== 控件声明 ========================
    private Switch sw_boot;
    private Switch sw_epg;
    private Switch sw_auto_update;
    private Switch sw_reverse;
    private Switch sw_num_channel;

    private TextView tv_screen_ratio;
    private TextView tv_custom_source;
    private TextView tv_custom_epg;
    private TextView tv_multi_source;
    private TextView tv_multi_epg;
    private TextView tv_qr_code;

    private TextView log_viewer;
    private TextView log_operation;

    private SharedPreferences sp;

    // ======================== 生命周期 ========================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // TV端强制横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        // 初始化配置存储
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 绑定控件
        initView();
        // 绑定事件
        initListener();
    }

    /**
     * 绑定 XML 中的所有控件
     */
    private void initView() {
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

        log_viewer = findViewById(R.id.log_viewer);
        log_operation = findViewById(R.id.log_operation);
    }

    /**
     * 初始化所有点击/开关事件
     */
    private void initListener() {
        // 开机自启
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        sw_boot.setOnCheckedChangeListener((btn, isChecked) -> {
            sp.edit().putBoolean("boot_auto_start", isChecked).apply();
            Toast.makeText(this, "开机自启" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 节目单开关
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_epg.setOnCheckedChangeListener((btn, isChecked) -> {
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            Toast.makeText(this, "节目单" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 自动更新源
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        sw_auto_update.setOnCheckedChangeListener((btn, isChecked) -> {
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            Toast.makeText(this, "自动更新源" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 换台反转
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_reverse.setOnCheckedChangeListener((btn, isChecked) -> {
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            Toast.makeText(this, "换台反转" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 数字选台
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        sw_num_channel.setOnCheckedChangeListener((btn, isChecked) -> {
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            Toast.makeText(this, "数字选台" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 屏幕比例
        tv_screen_ratio.setOnClickListener(v -> {
            Toast.makeText(this, "屏幕比例设置", Toast.LENGTH_SHORT).show();
        });

        // 自定义订阅源
        tv_custom_source.setOnClickListener(v -> {
            Toast.makeText(this, "自定义订阅源", Toast.LENGTH_SHORT).show();
        });

        // 自定义节目单
        tv_custom_epg.setOnClickListener(v -> {
            Toast.makeText(this, "自定义节目单", Toast.LENGTH_SHORT).show();
        });

        // 多订阅源
        tv_multi_source.setOnClickListener(v -> {
            Toast.makeText(this, "多订阅源管理", Toast.LENGTH_SHORT).show();
        });

        // 多节目单
        tv_multi_epg.setOnClickListener(v -> {
            Toast.makeText(this, "多节目单管理", Toast.LENGTH_SHORT).show();
        });

        // 扫码添加
        tv_qr_code.setOnClickListener(v -> {
            Toast.makeText(this, "扫码添加直播源/节目单", Toast.LENGTH_SHORT).show();
        });

        // 查看解析日志
        log_viewer.setOnClickListener(v -> showNormalLogDialog());

        // 查看崩溃日志
        log_operation.setOnClickListener(v -> showCrashLogDialog());
    }

    /**
     * 显示普通解析日志弹窗
     */
    private void showNormalLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tvLog = new TextView(this);
        tvLog.setTextColor(Color.WHITE);
        tvLog.setTextSize(14);
        tvLog.setPadding(20, 20, 20, 20);

        StringBuilder sb = new StringBuilder();
        for (String log : MainActivity.logList) {
            sb.append(log).append("\n");
        }

        tvLog.setText(sb.length() > 0 ? sb.toString() : "暂无解析日志");
        scrollView.addView(tvLog);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📄 解析日志");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNegativeButton("清空日志", (dialog, which) -> {
            MainActivity.logList.clear();
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    /**
     * 显示崩溃日志弹窗（只看崩溃）
     */
    private void showCrashLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tvLog = new TextView(this);
        tvLog.setTextColor(Color.RED);
        tvLog.setTextSize(14);
        tvLog.setPadding(20, 20, 20, 20);

        List<String> crashLogs = new ArrayList<>();
        for (String log : MainActivity.logList) {
            if (log.contains("【崩溃】")) {
                crashLogs.add(log);
            }
        }

        if (crashLogs.isEmpty()) {
            tvLog.setText("✅ 暂无崩溃日志");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String log : crashLogs) {
                sb.append(log).append("\n");
            }
            tvLog.setText(sb.toString());
        }

        scrollView.addView(tvLog);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📌 操作崩溃日志");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNegativeButton("清空崩溃日志", (dialog, which) -> {
            MainActivity.logList.removeIf(log -> log.contains("【崩溃】"));
            Toast.makeText(this, "崩溃日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    /**
     * 获取设备 WiFi IP 地址
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
