package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class SettingsActivity extends AppCompatActivity {
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private SharedPreferences sp;

    // 自动生成二维码用
    private String currentWebUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);

        // 绑定控件（和你的布局完全对应）
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
        tv_qr_code = findViewById(R.id.tv_qr_code); // 新增二维码按钮

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

        // 提示
        tv_custom_source.setOnClickListener(v -> showWebTip());
        tv_custom_epg.setOnClickListener(v -> showWebTip());
        tv_multi_source.setOnClickListener(v -> Toast.makeText(this, "多订阅源：短按切换，长按清除", Toast.LENGTH_SHORT).show());
        tv_multi_epg.setOnClickListener(v -> Toast.makeText(this, "多节目单：短按切换，长按清除", Toast.LENGTH_SHORT).show());

        // ============== 自动生成二维码 + 点击弹出 ==============
        currentWebUrl = "http://" + getDeviceIPAddress() + ":10481";
        tv_qr_code.setOnClickListener(v -> showQRCodeDialog());
    }

    // ============== 弹出二维码弹窗 ==============
    private void showQRCodeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("扫码添加直播源 / EPG节目单");

        ImageView imageView = new ImageView(this);
        Bitmap qrCode = createQRCode(currentWebUrl, 500);
        if (qrCode != null) {
            imageView.setImageBitmap(qrCode);
        } else {
            Toast.makeText(this, "二维码生成失败", Toast.LENGTH_SHORT).show();
        }

        builder.setView(imageView);
        builder.setPositiveButton("关闭", null);
        builder.show();
    }

    // ============== 生成二维码 ==============
    private Bitmap createQRCode(String content, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        }
    }

    // ============== 自动获取本机IP ==============
    private String getDeviceIPAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            int ip = wifiInfo.getIpAddress();
            return (ip & 0xFF) + "." +
                   ((ip >> 8) & 0xFF) + "." +
                   ((ip >> 16) & 0xFF) + "." +
                   ((ip >> 24) & 0xFF);
        } catch (Exception e) {
            return "192.168.1.100";
        }
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
        Toast.makeText(this, "请在浏览器打开：" + currentWebUrl, Toast.LENGTH_LONG).show();
    }
}
