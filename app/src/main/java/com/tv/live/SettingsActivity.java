package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);

        // 绑定控件
        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse_channel);
        sw_num_channel = findViewById(R.id.sw_num_channel);

        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);

        // 加载配置
        loadConfig();

        // 开关监听
        sw_boot.setOnCheckedChangeListener((button, isChecked) -> save("boot_auto_start", isChecked));
        sw_epg.setOnCheckedChangeListener((button, isChecked) -> save("epg_enable", isChecked));
        sw_auto_update.setOnCheckedChangeListener((button, isChecked) -> save("auto_update_source", isChecked));
        sw_reverse.setOnCheckedChangeListener((button, isChecked) -> save("channel_reverse", isChecked));
        sw_num_channel.setOnCheckedChangeListener((button, isChecked) -> save("number_channel_enable", isChecked));

        // 屏幕比例
        tv_screen_ratio.setOnClickListener(v -> showRatioDialog());

        // 自定义设置提示
        tv_custom_source.setOnClickListener(v -> showWebTip());
        tv_custom_epg.setOnClickListener(v -> showWebTip());

        // 多订阅源/多节目单提示
        tv_multi_source.setOnClickListener(v -> Toast.makeText(this, "多订阅源：短按切换，长按清除", Toast.LENGTH_SHORT).show());
        tv_multi_epg.setOnClickListener(v -> Toast.makeText(this, "多节目单：短按切换，长按清除", Toast.LENGTH_SHORT).show());
    }

    private void save(String key, boolean value) {
        sp.edit().putBoolean(key, value).apply();
    }

    private void loadConfig() {
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
    }

    private void showRatioDialog() {
        String[] ratios = {"全屏", "填充", "原始"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(ratios, (dialog, which) -> {
                    sp.edit().putString("screen_ratio", ratios[which]).apply();
                    Toast.makeText(this, "已设置：" + ratios[which], Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showWebTip() {
        Toast.makeText(this, "请在浏览器打开：http://<设备IP>:10481", Toast.LENGTH_LONG).show();
    }
}
