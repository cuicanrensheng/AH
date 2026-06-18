package com.tv.live;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
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

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置页面
 * 功能清单：
 * 1. 开机自启开关
 * 2. 节目单开关
 * 3. 自动更新源（每天凌晨4点）
 * 4. 换台反转
 * 5. 数字选台
 * 6. 屏幕比例设置
 * 7. 自定义订阅源/节目单
 * 8. 多订阅源/节目单历史记录
 * 9. 扫码添加（二维码 + 网页后台）
 * 10. 解析&播放日志查看
 * 11. 操作日志查看
 * 12. 检查更新
 */
public class SettingsActivity extends AppCompatActivity {

    // ====================== 控件声明 ======================
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;

    // ====================== 配置相关 ======================
    private SharedPreferences sp;

    // ====================== 网页后台相关 ======================
    private String currentWebUrl;           // 网页后台地址
    private ServerSocket serverSocket;      // HTTP 服务器 Socket
    private Handler handler = new Handler(Looper.getMainLooper());  // 主线程 Handler
    private static final int PORT = 10481;  // 网页后台端口号

    // ====================== 历史记录列表适配器 ======================
    private SettingsAdapter adapter;

    // ====================== 全局日志系统 ======================

    /**
     * 解析&播放日志
     */
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();

    public static void log(String msg) {
        if (PLAY_LOG == null) {
            PLAY_LOG = new StringBuilder();
        }
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        PLAY_LOG.append("[").append(time).append("] ").append(msg).append("\n");
        if (PLAY_LOG.length() > 20000) {
            PLAY_LOG.delete(0, PLAY_LOG.length() - 15000);
        }
    }

    // ====================== 操作日志 ======================

    /**
     * 操作日志
     */
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();

    public static void logOperation(String msg) {
        if (OPERATION_LOG == null) {
            OPERATION_LOG = new StringBuilder();
        }
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        OPERATION_LOG.append("[").append(time).append("] ").append(msg).append("\n");
        if (OPERATION_LOG.length() > 20000) {
            OPERATION_LOG.delete(0, OPERATION_LOG.length() - 15000);
        }
    }

    /**
     * 显示操作日志对话框
     */
    private void showOperationLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);

        if (OPERATION_LOG == null || OPERATION_LOG.length() == 0) {
            tv.setText("暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作。");
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
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    // ====================== onCreate ======================

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

        // ===== 绑定控件 =====
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

        // ===== 日志查看 =====
        findViewById(R.id.log_viewer).setOnClickListener(v -> showLogDialog());
        findViewById(R.id.log_operation).setOnClickListener(v -> showOperationLogDialog());

        // ===== 5个开关项（点击整个item切换） =====

        // 1. 开机自启
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            boolean isChecked = !sw_boot.isChecked();
            sw_boot.setChecked(isChecked);
            sp.edit().putBoolean("boot_auto_start", isChecked).apply();
            logOperation("【设置】开机自启" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "开机自启" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 2. 节目单开关
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        findViewById(R.id.item_epg).setOnClickListener(v -> {
            boolean isChecked = !sw_epg.isChecked();
            sw_epg.setChecked(isChecked);
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            logOperation("【设置】节目单" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 3. 自动更新源
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            boolean isChecked = !sw_auto_update.isChecked();
            sw_auto_update.setChecked(isChecked);
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            if (isChecked) {
                setAutoUpdateAlarm();
            } else {
                cancelAutoUpdateAlarm();
            }
            logOperation("【设置】自动更新源" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启（每天凌晨4点）" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        if (sp.getBoolean("auto_update_source", true)) {
            setAutoUpdateAlarm();
        }

        // 4. 换台反转
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            logOperation("【设置】换台反转" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 5. 数字选台
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean isChecked = !sw_num_channel.isChecked();
            sw_num_channel.setChecked(isChecked);
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            logOperation("【设置】数字选台" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // ===== 检查更新 =====
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
        });

        // ===== 其他点击事件 =====
        initListeners();

        // ===== 启动网页后台 =====
        currentWebUrl = "http://" + getDeviceIPAddress() + ":" + PORT;
        startHttpServer();

        logOperation("【设置】打开设置页面");
    }

    // ====================== 自动更新源 ======================

    private void setAutoUpdateAlarm() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.set(Calendar.HOUR_OF_DAY, 4);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);

            if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_MONTH, 1);
            }

            alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
            );

            logOperation("【设置】已设置自动更新闹钟，每天凌晨4点执行");
        } catch (Exception e) {
            e.printStackTrace();
            logOperation("【设置】设置自动更新闹钟失败：" + e.getMessage());
        }
    }

    private void cancelAutoUpdateAlarm() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            alarmManager.cancel(pendingIntent);

            logOperation("【设置】已取消自动更新闹钟");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ====================== 其他点击事件 ======================

    private void initListeners() {
        tv_screen_ratio.setOnClickListener(v -> {
            showRatioDialog();
            logOperation("【设置】打开屏幕比例设置");
        });
        tv_custom_source.setOnClickListener(v -> {
            showInputDialog("自定义订阅源", "请输入直播源地址", "custom_live_url");
            logOperation("【设置】打开自定义订阅源");
        });
        tv_custom_epg.setOnClickListener(v -> {
            showInputDialog("自定义节目单", "请输入EPG地址", "custom_epg_url");
            logOperation("【设置】打开自定义节目单");
        });
        tv_multi_source.setOnClickListener(v -> {
            showHistoryDialog("直播源历史", "live_history");
            logOperation("【设置】打开直播源历史");
        });
        tv_multi_epg.setOnClickListener(v -> {
            showHistoryDialog("节目单历史", "epg_history");
            logOperation("【设置】打开节目单历史");
        });
        tv_qr_code.setOnClickListener(v -> {
            showQRCodeDialog();
            logOperation("【设置】打开扫码管理");
        });
    }

    // ====================== 屏幕比例 ======================

    private void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(ratios, (d, w) -> {
                    sp.edit().putString("screen_ratio", ratios[w]).apply();
                    logOperation("【设置】屏幕比例设为：" + ratios[w]);
                    Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
                }).show();
    }

    // ====================== 输入对话框 ======================

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
                        logOperation("【设置】" + title + "已更新：" + url);
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ====================== 历史记录 ======================

    private void showHistoryDialog(String title, String key) {
        String history = sp.getString(key, "");
        if (TextUtils.isEmpty(history)) {
            Toast.makeText(this, "无记录", Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] list = history.split("\\|");
        adapter = new SettingsAdapter(this, Arrays.asList(list));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setAdapter(adapter, (d, w) -> {
                    String url = list[w];
                    sp.edit().putString(key.contains("live") ? "custom_live_url" : "custom_epg_url", url).apply();
                    addHistory(key.contains("live") ? "live_history" : "epg_history", url);
                    sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    adapter.setSelectedPosition(w);
                    logOperation("【设置】切换" + title + "：" + url);
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

    // ====================== 扫码相关 ======================

    private void showQRCodeDialog() {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(createQR(currentWebUrl, 250));

        new AlertDialog.Builder(this)
                .setTitle("扫码管理")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
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
            return "192.168.1.100";
        }
    }

    // ====================== ✅ 自建 HTTP 服务器（核心） ======================

    /**
     * 启动 HTTP 服务器
     * 自己实现，不依赖任何外部库
     *
     * 支持的路径：
     * GET  /        → 配置页面
     * GET  /log     → 日志页面
     * POST /submit  → 提交配置
     */
    private void startHttpServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                logOperation("【设置】网页后台已启动，端口：" + PORT);

                // 循环接受连接
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    // 每个请求开一个线程处理
                    new Thread(() -> handleHttpRequest(socket)).start();
                }
            } catch (Exception e) {
                e.printStackTrace();
                logOperation("【设置】网页后台启动失败：" + e.getMessage());
            }
        }).start();
    }

    /**
     * 处理单个 HTTP 请求
     *
     * 【为什么自己写而不用 NanoHTTPD？】
     * 因为项目里的 NanoHTTPD 是极度简化版，只支持 GET，而且页面样式很丑。
     * 自己写可以完全控制功能和样式，支持配置页 + 日志页 + POST 提交。
     */
    private void handleHttpRequest(Socket socket) {
        try {
            // ===== 1. 读取完整请求 =====
            InputStreamReader reader = new InputStreamReader(socket.getInputStream(), "UTF-8");
            StringBuilder requestBuilder = new StringBuilder();
            char[] buffer = new char[4096];
            int totalRead = 0;

            while (true) {
                int len = reader.read(buffer);
                if (len <= 0) break;
                requestBuilder.append(buffer, 0, len);
                totalRead += len;

                String req = requestBuilder.toString();

                // GET 请求：读到空行就结束
                if (req.contains("\r\n\r\n") && req.startsWith("GET")) {
                    break;
                }

                // POST 请求：根据 Content-Length 判断是否读完
                if (req.contains("\r\n\r\n") && req.startsWith("POST")) {
                    int headerEnd = req.indexOf("\r\n\r\n");
                    String headers = req.substring(0, headerEnd);
                    int contentLength = 0;

                    // 从请求头里找 Content-Length
                    String[] headerLines = headers.split("\r\n");
                    for (String line : headerLines) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.split(":")[1].trim());
                            break;
                        }
                    }

                    // body 长度够了就停止读取
                    int bodyLen = req.length() - headerEnd - 4;
                    if (bodyLen >= contentLength) {
                        break;
                    }
                }

                // 防止无限循环，最多读 8KB
                if (totalRead > 8192) break;
            }

            String request = requestBuilder.toString();
            String[] lines = request.split("\r\n");
            if (lines.length == 0) {
                socket.close();
                return;
            }

            // ===== 2. 解析请求行 =====
            // 格式：GET /path HTTP/1.1
            String firstLine = lines[0];
            String[] parts = firstLine.split(" ");
            if (parts.length < 2) {
                sendResponse(socket, "400 Bad Request", "text/plain", "Bad Request");
                return;
            }

            String method = parts[0];  // GET / POST
            String path = parts[1];    // /  /log  /submit

            logOperation("【网页后台】请求：" + method + " " + path);

            // ===== 3. 路由分发 =====
            String responseBody = "";
            String contentType = "text/html; charset=utf-8";

            // 去掉 URL 里的查询参数（只取路径部分）
            String purePath = path.contains("?") ? path.split("\\?")[0] : path;

            // 3.1 配置页面
            if ("GET".equals(method) && ("/".equals(purePath) || "/index.html".equals(purePath))) {
                responseBody = buildConfigPage();
            }
            // 3.2 日志页面
            else if ("GET".equals(method) && "/log".equals(purePath)) {
                responseBody = buildLogPage();
            }
            // 3.3 提交配置（POST）
            else if ("POST".equals(method) && "/submit".equals(purePath)) {
                // 解析 POST body
                int headerEnd = request.indexOf("\r\n\r\n");
                String body = request.substring(headerEnd + 4);
                Map<String, String> params = parseFormData(body);

                final String liveUrl = params.get("live_url");
                final String epgUrl = params.get("epg_url");

                // 切到主线程保存配置
                handler.post(() -> {
                    boolean hasUpdate = false;
                    if (liveUrl != null && !liveUrl.trim().isEmpty()) {
                        sp.edit().putString("custom_live_url", liveUrl.trim()).apply();
                        addHistory("live_history", liveUrl.trim());
                        hasUpdate = true;
                    }
                    if (epgUrl != null && !epgUrl.trim().isEmpty()) {
                        sp.edit().putString("custom_epg_url", epgUrl.trim()).apply();
                        addHistory("epg_history", epgUrl.trim());
                        hasUpdate = true;
                    }
                    if (hasUpdate) {
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("【网页后台】配置已更新");
                        Toast.makeText(SettingsActivity.this, "配置已更新", Toast.LENGTH_SHORT).show();
                    }
                });

                responseBody = buildSuccessPage();
            }
            // 3.4 404
            else {
                responseBody = "404 Not Found";
                contentType = "text/plain; charset=utf-8";
            }

            // ===== 4. 发送响应 =====
            sendResponse(socket, "200 OK", contentType, responseBody);

        } catch (Exception e) {
            e.printStackTrace();
            logOperation("【网页后台】处理请求异常：" + e.getMessage());
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 发送 HTTP 响应
     */
    private void sendResponse(Socket socket, String status, String contentType, String body) throws Exception {
        byte[] bodyBytes = body.getBytes("UTF-8");

        String header = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes("UTF-8"));
        out.write(bodyBytes);
        out.flush();
        socket.close();
    }

    /**
     * 解析表单数据（application/x-www-form-urlencoded）
     * 格式：key1=value1&key2=value2
     */
    private Map<String, String> parseFormData(String body) {
        Map<String, String> params = new HashMap<>();
        if (body == null || body.isEmpty()) return params;

        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    String key = URLDecoder.decode(kv[0], "UTF-8");
                    String value = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
                    params.put(key, value);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return params;
    }

    // ====================== ✅ HTML 页面构建 ======================

    /**
     * 构建配置页面 HTML（APP 风格）
     * 模仿 APP 里的设置页面样式
     */
    private String buildConfigPage() {
        String currentLive = sp.getString("custom_live_url", "");
        String currentEpg = sp.getString("custom_epg_url", "");

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                "    <title>我的电视</title>\n" +
                "    <style>\n" +
                "        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif; background: #f5f5f5; color: #333; font-size: 14px; line-height: 1.5; padding-bottom: 60px; }\n" +
                "        .section-title { padding: 16px 16px 8px; font-size: 14px; color: #999; font-weight: normal; }\n" +
                "        .card { background: #fff; margin: 0 12px; }\n" +
                "        .card:first-of-type { border-radius: 12px 12px 0 0; }\n" +
                "        .card:last-of-type { border-radius: 0 0 12px 12px; }\n" +
                "        .item { display: flex; align-items: center; padding: 14px 16px; border-bottom: 1px solid #f0f0f0; min-height: 48px; }\n" +
                "        .item:last-child { border-bottom: none; }\n" +
                "        .item-label { flex-shrink: 0; width: 70px; color: #333; font-size: 15px; }\n" +
                "        .item input[type=text] { flex: 1; text-align: right; border: none; outline: none; font-size: 14px; color: #333; background: transparent; }\n" +
                "        .item input[type=text]::placeholder { color: #ccc; }\n" +
                "        .header-item { flex-direction: column; align-items: flex-start; padding: 16px; }\n" +
                "        .header-title { font-size: 17px; color: #333; font-weight: 500; margin-bottom: 4px; }\n" +
                "        .header-desc { font-size: 13px; color: #999; }\n" +
                "        .btn-blue { display: block; margin: 12px 12px 0; padding: 12px 24px; background: #40A9FF; color: white; border: none; border-radius: 6px; font-size: 15px; font-weight: 500; cursor: pointer; float: right; }\n" +
                "        .btn-blue:active { background: #1890FF; }\n" +
                "        .btn-wrap { overflow: hidden; padding: 0 0 12px; }\n" +
                "        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; display: flex; border-top: 1px solid #eee; padding: 8px 0 calc(8px + env(safe-area-inset-bottom)); }\n" +
                "        .nav-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; color: #999; font-size: 12px; cursor: pointer; text-decoration: none; }\n" +
                "        .nav-item.active { color: #40A9FF; }\n" +
                "        .nav-icon { font-size: 20px; line-height: 1; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"section-title\">直播源</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item header-item\">\n" +
                "            <div class=\"header-title\">自定义直播源</div>\n" +
                "            <div class=\"header-desc\">支持m3u、txt格式</div>\n" +
                "        </div>\n" +
                "        <form method=\"post\" action=\"/submit\">\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">链接</div>\n" +
                "                <input type=\"text\" name=\"live_url\" placeholder=\"直播源链接\" value=\"" + currentLive + "\">\n" +
                "            </div>\n" +
                "            <div class=\"btn-wrap\">\n" +
                "                <button type=\"submit\" class=\"btn-blue\">推送直播源</button>\n" +
                "            </div>\n" +
                "        </form>\n" +
                "    </div>\n" +
                "    <div class=\"section-title\">节目单</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item header-item\">\n" +
                "            <div class=\"header-title\">自定义节目单</div>\n" +
                "            <div class=\"header-desc\">支持xml、xml.gz格式</div>\n" +
                "        </div>\n" +
                "        <form method=\"post\" action=\"/submit\">\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">链接</div>\n" +
                "                <input type=\"text\" name=\"epg_url\" placeholder=\"节目单链接\" value=\"" + currentEpg + "\">\n" +
                "            </div>\n" +
                "            <div class=\"btn-wrap\">\n" +
                "                <button type=\"submit\" class=\"btn-blue\">推送节目单</button>\n" +
                "            </div>\n" +
                "        </form>\n" +
                "    </div>\n" +
                "    <div class=\"bottom-nav\">\n" +
                "        <a href=\"/\" class=\"nav-item active\">\n" +
                "            <div class=\"nav-icon\">🖥️</div>\n" +
                "            <div>配置</div>\n" +
                "        </a>\n" +
                "        <a href=\"/log\" class=\"nav-item\">\n" +
                "            <div class=\"nav-icon\">📋</div>\n" +
                "            <div>日志</div>\n" +
                "        </a>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * 构建日志页面 HTML（APP 风格）
     */
    private String buildLogPage() {
        String logContent = PLAY_LOG != null ? PLAY_LOG.toString() : "";
        String[] lines = logContent.split("\n");

        StringBuilder logHtml = new StringBuilder();
        // 倒序显示，最新的在最上面
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;

            // 提取时间和内容
            String time = "";
            String content = line;
            if (line.startsWith("[") && line.contains("]")) {
                time = line.substring(1, line.indexOf("]"));
                content = line.substring(line.indexOf("]") + 1).trim();
            }

            // 判断日志级别
            boolean isError = content.contains("错误") || content.contains("失败")
                    || content.contains("异常") || content.contains("ERROR") || content.contains("❌");
            String level = isError ? "ERROR" : "INFO";
            String levelColor = isError ? "#F5222D" : "#1890FF";
            String icon = isError ? "✕" : "i";

            logHtml.append("        <div class=\"log-item\">\n");
            logHtml.append("            <div class=\"log-icon\" style=\"background: ").append(levelColor).append(";\">").append(icon).append("</div>\n");
            logHtml.append("            <div class=\"log-content\">\n");
            logHtml.append("                <div class=\"log-level\" style=\"color: ").append(levelColor).append(";\">").append(level).append("</div>\n");
            logHtml.append("                <div class=\"log-text\">").append(content).append("</div>\n");
            logHtml.append("            </div>\n");
            logHtml.append("            <div class=\"log-time\">").append(time).append("</div>\n");
            logHtml.append("        </div>\n");
        }

        if (logHtml.length() == 0) {
            logHtml.append("        <div style=\"padding: 40px 20px; text-align: center; color: #999; font-size: 14px;\">暂无日志</div>\n");
        }

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                "    <title>我的电视 - 日志</title>\n" +
                "    <style>\n" +
                "        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif; background: #fff; color: #333; font-size: 14px; line-height: 1.5; padding-bottom: 60px; }\n" +
                "        .header { padding: 20px 16px 16px; }\n" +
                "        .header-title { font-size: 32px; font-weight: 500; color: #000; margin-bottom: 8px; }\n" +
                "        .header-sub { font-size: 14px; color: #999; }\n" +
                "        .log-list { padding: 0 16px; }\n" +
                "        .log-item { display: flex; align-items: flex-start; padding: 12px 0; border-bottom: 1px solid #f5f5f5; gap: 12px; }\n" +
                "        .log-icon { width: 20px; height: 20px; border-radius: 4px; display: flex; align-items: center; justify-content: center; color: white; font-size: 12px; font-weight: bold; flex-shrink: 0; margin-top: 2px; }\n" +
                "        .log-content { flex: 1; min-width: 0; }\n" +
                "        .log-level { font-size: 13px; font-weight: 500; margin-bottom: 4px; }\n" +
                "        .log-text { font-size: 14px; color: #333; word-break: break-all; line-height: 1.4; }\n" +
                "        .log-time { flex-shrink: 0; font-size: 12px; color: #999; margin-top: 2px; }\n" +
                "        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; display: flex; border-top: 1px solid #eee; padding: 8px 0 calc(8px + env(safe-area-inset-bottom)); }\n" +
                "        .nav-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; color: #999; font-size: 12px; cursor: pointer; text-decoration: none; }\n" +
                "        .nav-item.active { color: #40A9FF; }\n" +
                "        .nav-icon { font-size: 20px; line-height: 1; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"header\">\n" +
                "        <div class=\"header-title\">我的电视</div>\n" +
                "        <div class=\"header-sub\">http://" + getDeviceIPAddress() + ":" + PORT + "</div>\n" +
                "    </div>\n" +
                "    <div class=\"log-list\">\n" +
                logHtml.toString() +
                "    </div>\n" +
                "    <div class=\"bottom-nav\">\n" +
                "        <a href=\"/\" class=\"nav-item\">\n" +
                "            <div class=\"nav-icon\">🖥️</div>\n" +
                "            <div>配置</div>\n" +
                "        </a>\n" +
                "        <a href=\"/log\" class=\"nav-item active\">\n" +
                "            <div class=\"nav-icon\">📋</div>\n" +
                "            <div>日志</div>\n" +
                "        </a>\n" +
                "    </div>\n" +
                "    <script>\n" +
                "        setTimeout(function() { location.reload(); }, 5000);\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * 构建提交成功页面
     */
    private String buildSuccessPage() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>保存成功</title>\n" +
                "    <style>\n" +
                "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif; background: #f5f5f5; padding: 20px; display: flex; justify-content: center; align-items: center; min-height: 100vh; }\n" +
                "        .container { max-width: 400px; background: white; border-radius: 12px; padding: 32px 24px; text-align: center; box-shadow: 0 2px 12px rgba(0,0,0,0.1); }\n" +
                "        .icon { font-size: 48px; margin-bottom: 16px; }\n" +
                "        h2 { font-size: 20px; color: #333; margin-bottom: 12px; }\n" +
                "        p { font-size: 14px; color: #666; margin-bottom: 24px; }\n" +
                "        a { display: inline-block; padding: 10px 24px; background: #40A9FF; color: white; text-decoration: none; border-radius: 8px; font-size: 14px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <div class=\"icon\">✅</div>\n" +
                "        <h2>配置保存成功！</h2>\n" +
                "        <p>直播源和节目单已更新，电视端正在刷新...</p>\n" +
                "        <a href=\"/\">返回继续修改</a>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    // ====================== onDestroy ======================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logOperation("【设置】关闭设置页面");
        // 关闭 HTTP 服务器
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }

    // ====================== 历史记录适配器 ======================

    /**
     * 历史记录列表适配器
     * 三种状态：选中 / 焦点 / 未选中
     */
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
                // 选中状态：蓝色文字 + 浅蓝色背景
                tv.setTextColor(Color.parseColor("#40A9FF"));
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (convertView.isFocused()) {
                // 焦点状态：蓝色文字 + 稍深一点的蓝色背景
                tv.setTextColor(Color.parseColor("#40A9FF"));
                convertView.setBackgroundColor(0x4440A9FF);
            } else {
                // 未选中状态：白色文字 + 透明背景
                tv.setTextColor(Color.WHITE);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            return convertView;
        }
    }
}
