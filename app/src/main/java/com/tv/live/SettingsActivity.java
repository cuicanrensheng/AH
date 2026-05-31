package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sp = getSharedPreferences("app_settings", MODE_PRIVATE);

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

        loadConfig();
        initListeners();
    }

    private void initListeners() {
        sw_boot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("boot_auto_start", isChecked).apply();
            Toast.makeText(this, "开机自启" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_epg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_auto_update.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_reverse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_num_channel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        tv_screen_ratio.setOnClickListener(v -> showRatioDialog());
        tv_custom_source.setOnClickListener(v -> showInputDialog("自定义订阅源", "请输入直播源地址", "custom_live_url"));
        tv_custom_epg.setOnClickListener(v -> showInputDialog("自定义节目单", "请输入EPG地址", "custom_epg_url"));
        tv_multi_source.setOnClickListener(v -> showHistoryDialog("直播源历史", "live_history"));
        tv_multi_epg.setOnClickListener(v -> showHistoryDialog("节目单历史", "epg_history"));

        findViewById(R.id.btn_check_update).setOnClickListener(v -> {
            Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadConfig() {
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
    }

    private void showRatioDialog() {
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(new String[]{"全屏","填充","原始"}, (d,w)->{
                    sp.edit().putString("screen_ratio", new String[]{"全屏","填充","原始"}[w]).apply();
                    Toast.makeText(this,"已设置",Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showInputDialog(String title, String hint, String key) {
        EditText ed = new EditText(this);
        ed.setHint(hint);
        ed.setText(sp.getString(key,""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ed)
                .setPositiveButton("确定",(d,w)->{
                    String url = ed.getText().toString().trim();
                    if(!url.isEmpty()){
                        sp.edit().putString(key,url).apply();
                        addHistory(key.contains("live")?"live_history":"epg_history",url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消",null)
                .show();
    }

    private void showHistoryDialog(String title, String key) {
        String history = sp.getString(key, "");
        if (TextUtils.isEmpty(history)) {
            Toast.makeText(this, "无记录", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] list = history.split("\\|");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(list, (d, w) -> {
                    String url = list[w];
                    sp.edit().putString(key.contains("live") ? "custom_live_url" : "custom_epg_url", url).apply();
                    addHistory(key.contains("live") ? "live_history" : "epg_history", url);
                    sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    Toast.makeText(this, "已切换，正在刷新…", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void addHistory(String key, String url) {
        String history = sp.getString(key, "");
        StringBuilder sb = new StringBuilder();
        sb.append(url);
        if (!history.isEmpty()) {
            String[] arr = history.split("\\|");
            for (String s : arr) {
                if (!s.equals(url) && sb.length() < 1000) {
                    sb.append("|").append(s);
                }
            }
        }
        sp.edit().putString(key, sb.toString()).apply();
    }
}
