package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private SharedPreferences sp;
    private String currentWebUrl;
    private ServerSocket serverSocket;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int PORT = 10481;

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

        sw_boot.setOnCheckedChangeListener((button, isChecked) -> save("boot_auto_start", isChecked));
        sw_epg.setOnCheckedChangeListener((button, isChecked) -> save("epg_enable", isChecked));
        sw_auto_update.setOnCheckedChangeListener((button, isChecked) -> save("auto_update_source", isChecked));
        sw_reverse.setOnCheckedChangeListener((button, isChecked) -> save("channel_reverse", isChecked));
        sw_num_channel.setOnCheckedChangeListener((button, isChecked) -> save("number_channel_enable", isChecked));

        tv_screen_ratio.setOnClickListener(v -> showRatioDialog());

        // 手动输入
        tv_custom_source.setOnClickListener(v -> showInputDialog("自定义订阅源", "请输入直播源地址", "custom_live_url"));
        tv_custom_epg.setOnClickListener(v -> showInputDialog("自定义节目单", "请输入EPG节目单地址", "custom_epg_url"));

        // 历史记录（查看 + 删除 + 选择）
        tv_multi_source.setOnClickListener(v -> showHistoryDialog("直播源历史记录", "live_history"));
        tv_multi_epg.setOnClickListener(v -> showHistoryDialog("节目单历史记录", "epg_history"));

        currentWebUrl = "http://" + getDeviceIPAddress() + ":" + PORT;
        tv_qr_code.setOnClickListener(v -> showQRCodeDialog());
        startPushServer();
    }

    // ================== 手动输入链接 ==================
    private void showInputDialog(String title, String hint, String key) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        final EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(sp.getString(key, ""));
        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                sp.edit().putString(key, url).apply();
                addToHistory(key.contains("live") ? "live_history" : "epg_history", url);
                Toast.makeText(this, "已保存，正在刷新...", Toast.LENGTH_SHORT).show();
                if (MainActivity.mInstance != null) MainActivity.mInstance.loadLiveAndEpg();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    // ================== 历史记录：查看 + 选择 + 删除 ==================
    private void showHistoryDialog(String title, String historyKey) {
        List<String> list = getHistory(historyKey);
        if (list.isEmpty()) {
            Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = list.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items, (d, i) -> {
                String selected = items[i];
                String saveKey = historyKey.equals("live_history") ? "custom_live_url" : "custom_epg_url";
                sp.edit().putString(saveKey, selected).apply();
                Toast.makeText(this, "已切换：" + selected, Toast.LENGTH_SHORT).show();
                if (MainActivity.mInstance != null) MainActivity.mInstance.loadLiveAndEpg();
            })
            .setNeutralButton("删除选中", (d, i) -> {
                new AlertDialog.Builder(this)
                    .setMessage("确定删除这条记录？")
                    .setPositiveButton("删除", (dl, ii) -> {
                        removeHistory(historyKey, items[i]);
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    // ================== 历史记录存储 ==================
    private List<String> getHistory(String key) {
        String str = sp.getString(key, "");
        if (str.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(str.split("\\|")));
    }

    private void addToHistory(String key, String url) {
        List<String> list = getHistory(key);
        if (list.contains(url)) list.remove(url);
        list.add(0, url);
        while (list.size() > 10) list.remove(list.size() - 1);
        sp.edit().putString(key, String.join("|", list)).apply();
    }

    private void removeHistory(String key, String url) {
        List<String> list = getHistory(key);
        list.remove(url);
        sp.edit().putString(key, String.join("|", list)).apply();
    }

    // ================== 二维码 250×250 ==================
    private void showQRCodeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("扫码管理直播源/EPG");
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(createQRCode(currentWebUrl, 250));
        builder.setView(iv);
        builder.setPositiveButton("关闭", null);
        builder.show();
    }

    private Bitmap createQRCode(String content, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 250, 250);
            Bitmap bmp = Bitmap.createBitmap(250, 250, Bitmap.Config.RGB_565);
            for (int x = 0; x < 250; x++)
                for (int y = 0; y < 250; y++)
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    // ================== 网页推送 ==================
    private void startPushServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(PORT));
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    handlePush(socket);
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private void handlePush(Socket socket) {
        new Thread(() -> {
            try {
                InputStream is = socket.getInputStream();
                InputStreamReader reader = new InputStreamReader(is);
                char[] buf = new char[2048];
                int len = reader.read(buf);
                String data = new String(buf, 0, len);
                socket.close();

                JSONObject json = new JSONObject(data);
                String liveUrl = json.optString("live_url");
                String epgUrl = json.optString("epg_url");

                handler.post(() -> {
                    if (!liveUrl.isEmpty()) {
                        sp.edit().putString("custom_live_url", liveUrl).apply();
                        addToHistory("live_history", liveUrl);
                    }
                    if (!epgUrl.isEmpty()) {
                        sp.edit().putString("custom_epg_url", epgUrl).apply();
                        addToHistory("epg_history", epgUrl);
                    }
                    Toast.makeText(this, "已更新并保存到历史记录", Toast.LENGTH_SHORT).show();
                    if (MainActivity.mInstance != null) MainActivity.mInstance.loadLiveAndEpg();
                });
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    // ================== 工具 ==================
    private String getDeviceIPAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            int ip = info.getIpAddress();
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Exception e) {
            return "192.168.1.100";
        }
    }

    private void save(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    private void loadConfig() {
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
    }

    private void showRatioDialog() {
        String[] r = {"全屏", "填充", "原始"};
        new AlertDialog.Builder(this)
            .setTitle("屏幕比例")
            .setItems(r, (d, w) -> {
                sp.edit().putString("screen_ratio", r[w]).apply();
                Toast.makeText(this, "已设置：" + r[w], Toast.LENGTH_SHORT).show();
            }).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) {}
    }
}
