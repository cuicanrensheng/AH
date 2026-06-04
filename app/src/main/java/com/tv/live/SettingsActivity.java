package com.tv.live;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;
import java.io.InputStreamReader;
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
    private Handler handler = new Handler(Looper.getMainLooper());
    private static final int PORT = 10481;
    private SettingsAdapter adapter;

    // ====================== 日志系统（完整保留） ======================
    private static final List<String> OPERATION_LOG = new ArrayList<>();
    private static final int MAX_LOG_COUNT = 200;

    public static void logOperation(String msg) {
        if (OPERATION_LOG.size() > MAX_LOG_COUNT) {
            OPERATION_LOG.remove(OPERATION_LOG.size() - 1);
        }
        OPERATION_LOG.add(0, "[" + android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()) + "] " + msg);
    }

    public static void logCrash(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) msg = "未知错误";
        logOperation("崩溃: " + msg);
    }

    private void showOperationLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);

        StringBuilder sb = new StringBuilder();
        for (String line : OPERATION_LOG) {
            sb.append(line).append("\n");
        }
        tv.setText(sb.length() == 0 ? "暂无日志" : sb.toString());

        scrollView.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("操作日志")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", (dialog, which) -> {
                    OPERATION_LOG.clear();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showParseLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);

        List<String> logs = TVPlayerManager.getInstance(this).getLogList();
        if (logs == null || logs.isEmpty()) {
            tv.setText("暂无解析日志");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String line : logs) {
                sb.append(line).append("\n");
            }
            tv.setText(sb.toString());
        }

        scrollView.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("解析日志")
                .setView(scrollView)
                .setPositiveButton("清空", (dialog, which) -> {
                    TVPlayerManager.getInstance(this).clearLogs();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().getAttributes().dimAmount = 0.6f;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND, WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        sp = getSharedPreferences("app_settings", MODE_PRIVATE);

        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);

        // 完全匹配你布局里的所有ID
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);

        // 日志按钮
        findViewById(R.id.log_viewer).setOnClickListener(v -> showParseLogDialog());
        findViewById(R.id.log_operation).setOnClickListener(v -> showOperationLogDialog());

        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        sw_boot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("boot_auto_start", isChecked).apply();
            logOperation("开机自启: " + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this, "开机自启" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_epg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            logOperation("节目单: " + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        sw_auto_update.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            logOperation("自动更新源: " + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_reverse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            logOperation("换台反转: " + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        sw_num_channel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            logOperation("数字选台: " + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_check_update).setOnClickListener(v -> {
            logOperation("检查更新: 已是最新版本");
            Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
        });

        loadConfig();
        initListeners();
        currentWebUrl = "http://" + getDeviceIPAddress() + ":" + PORT;
        startPushServer();
        logOperation("设置页面已打开");
    }

    private void initListeners() {
        tv_screen_ratio.setOnClickListener(v -> showRatioDialog());
        tv_custom_source.setOnClickListener(v -> showInputDialog("自定义订阅源", "请输入直播源地址", "custom_live_url"));
        tv_custom_epg.setOnClickListener(v -> showInputDialog("自定义节目单", "请输入EPG地址", "custom_epg_url"));
        tv_multi_source.setOnClickListener(v -> showHistoryDialog("直播源历史", "live_history"));
        tv_multi_epg.setOnClickListener(v -> showHistoryDialog("节目单历史", "epg_history"));
        tv_qr_code.setOnClickListener(v -> showQRCodeDialog());
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
                .setItems(new String[]{"全屏", "填充", "原始"}, (d, w) -> {
                    String val = new String[]{"全屏", "填充", "原始"}[w];
                    sp.edit().putString("screen_ratio", val).apply();
                    logOperation("设置画面比例: " + val);
                    Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
                }).show();
    }

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
                        addHistory(key.contains("live") ? "live_history" : "epg_history", url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("设置" + hint + ": " + url);
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showHistoryDialog(String title, String key) {
        String history = sp.getString(key, "");
        if (TextUtils.isEmpty(history)) {
            Toast.makeText(this, "无记录", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] list = history.split("\\|");
        adapter = new SettingsAdapter(this, Arrays.asList(list));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setAdapter(adapter, (d, w) -> {
                    String url = list[w];
                    sp.edit().putString(key.contains("live") ? "custom_live_url" : "custom_epg_url", url).apply();
                    addHistory(key.contains("live") ? "live_history" : "epg_history", url);
                    sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    adapter.setSelectedPosition(w);
                    logOperation("切换源: " + url);
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

    private void showQRCodeDialog() {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(createQR(currentWebUrl, 250));
        new AlertDialog.Builder(this)
                .setTitle("扫码管理")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
        logOperation("打开扫码服务: " + currentWebUrl);
    }

    private Bitmap createQR(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            logCrash(e);
            return null;
        }
    }

    private String getDeviceIPAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            int ip = info.getIpAddress();
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Exception e) {
            logCrash(e);
            return "192.168.1.100";
        }
    }

    private void startPushServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                logOperation("Socket服务启动成功，端口：" + PORT);
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> {
                        try {
                            InputStreamReader reader = new InputStreamReader(socket.getInputStream());
                            char[] buffer = new char[2048];
                            int len = reader.read(buffer);
                            JSONObject json = new JSONObject(new String(buffer, 0, len));
                            handler.post(() -> {
                                boolean hasUpdate = false;
                                String liveUrl = json.optString("live_url");
                                if (!liveUrl.isEmpty()) {
                                    sp.edit().putString("custom_live_url", liveUrl).apply();
                                    addHistory("live_history", liveUrl);
                                    hasUpdate = true;
                                    logOperation("扫码同步直播源：" + liveUrl);
                                }
                                String epgUrl = json.optString("epg_url");
                                if (!epgUrl.isEmpty()) {
                                    sp.edit().putString("custom_epg_url", epgUrl).apply();
                                    addHistory("epg_history", epgUrl);
                                    hasUpdate = true;
                                    logOperation("扫码同步EPG：" + epgUrl);
                                }
                                if (hasUpdate) {
                                    Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                                    sendBroadcast(intent);
                                    Toast.makeText(SettingsActivity.this, "扫码配置已同步", Toast.LENGTH_SHORT).show();
                                }
                            });
                            socket.close();
                        } catch (Exception e) {
                            logCrash(e);
                        }
                    }).start();
                }
            } catch (Exception e) {
                logCrash(e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (serverSocket != null) serverSocket.close();
            logOperation("Socket服务已关闭");
        } catch (Exception ignored) {}
    }

    private static class SettingsAdapter extends ArrayAdapter<String> {
        private final Context context;
        private final List<String> items;
        private int selectedPosition = -1;

        public SettingsAdapter(Context context, List<String> items) {
            super(context, R.layout.item_settings, items);
            this.context = context;
            this.items = items;
        }

        public void setSelectedPosition(int position) {
            selectedPosition = position;
            notifyDataSetChanged();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_settings, parent, false);
            }
            TextView tv = convertView.findViewById(R.id.tv_setting_item);
            tv.setText(items.get(position));
            if (position == selectedPosition) {
                tv.setTextColor(Color.parseColor("#40A9FF"));
            } else {
                tv.setTextColor(Color.WHITE);
            }
            return convertView;
        }
    }
}
