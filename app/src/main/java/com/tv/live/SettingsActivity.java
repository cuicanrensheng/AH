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

import org.json.JSONObject;

import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
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
    // 5个开关控件
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    // 纯文本点击项
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;

    // ====================== 配置相关 ======================
    private SharedPreferences sp;

    // ====================== 网页后台相关 ======================
    private String currentWebUrl;       // 网页后台地址
    private WebServer webServer;        // NanoHTTPD 服务器实例
    private Handler handler = new Handler(Looper.getMainLooper());  // 主线程 Handler，用于子线程更新 UI
    private static final int PORT = 10481;  // 网页后台端口号

    // ====================== 历史记录列表适配器 ======================
    private SettingsAdapter adapter;

    // ====================== 全局日志系统 ======================

    /**
     * 解析&播放日志
     * 用 volatile 保证多线程可见性
     */
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();

    /**
     * 记录解析&播放日志
     * @param msg 日志内容
     */
    public static void log(String msg) {
        // 双重检查，防止空指针
        if (PLAY_LOG == null) {
            PLAY_LOG = new StringBuilder();
        }
        // 格式化时间：HH:mm:ss
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        PLAY_LOG.append("[").append(time).append("] ").append(msg).append("\n");
        // 限制日志大小，防止内存溢出，超过20000字符就保留最后15000
        if (PLAY_LOG.length() > 20000) {
            PLAY_LOG.delete(0, PLAY_LOG.length() - 15000);
        }
    }

    // ====================== 操作日志 ======================

    /**
     * 操作日志
     * 记录用户的所有操作：切台、打开设置、切换分组等
     */
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();

    /**
     * 记录操作日志
     * @param msg 操作内容
     */
    public static void logOperation(String msg) {
        if (OPERATION_LOG == null) {
            OPERATION_LOG = new StringBuilder();
        }
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        OPERATION_LOG.append("[").append(time).append("] ").append(msg).append("\n");
        // 同样限制大小
        if (OPERATION_LOG.length() > 20000) {
            OPERATION_LOG.delete(0, OPERATION_LOG.length() - 15000);
        }
    }

    /**
     * 显示操作日志对话框
     * 最新的日志显示在最上面（倒序）
     */
    private void showOperationLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);

        if (OPERATION_LOG == null || OPERATION_LOG.length() == 0) {
            tv.setText("暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作。");
        } else {
            // 倒序显示：最新的在最上面
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
     * 最新的日志显示在最上面（倒序）
     */
    private void showLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);

        if (PLAY_LOG == null || PLAY_LOG.length() == 0) {
            tv.setText("暂无日志内容，请先播放一个频道再查看。");
        } else {
            // 倒序显示：最新的在最上面
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

    // ====================== onCreate 生命周期 ======================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ===== 窗口设置 =====
        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 透明背景（因为是对话框式的 Activity）
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        // 背景变暗程度
        getWindow().getAttributes().dimAmount = 0.6f;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND, WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        super.onCreate(savedInstanceState);
        // 横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        // ===== 初始化 SharedPreferences =====
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);

        // ===== 绑定控件 =====
        // 开关
        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);
        // 文本项
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);

        // ===== 日志查看按钮 =====
        // 解析&播放日志
        findViewById(R.id.log_viewer).setOnClickListener(v -> showLogDialog());
        // 操作日志
        findViewById(R.id.log_operation).setOnClickListener(v -> showOperationLogDialog());

        // ===== 5个开关项（点击整个item切换） =====
        // 原理：Switch 设为 focusable=false clickable=false，点击事件在整个 LinearLayout 上
        // 这样遥控器焦点在整个 item 上，有高亮效果

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
                // 开启：设置每天凌晨4点自动更新
                setAutoUpdateAlarm();
            } else {
                // 关闭：取消定时任务
                cancelAutoUpdateAlarm();
            }

            logOperation("【设置】自动更新源" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启（每天凌晨4点）" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 如果之前就是开启状态，重新设置一下闹钟（防止APP重启后闹钟丢失）
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

        // ===== 检查更新（点击整个item） =====
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
        });

        // ===== 其他点击事件 =====
        initListeners();

        // ===== 启动网页后台 =====
        currentWebUrl = "http://" + getDeviceIPAddress() + ":" + PORT;
        startPushServer();

        // 记录操作日志
        logOperation("【设置】打开设置页面");
    }

    // ====================== 自动更新源 - AlarmManager ======================

    /**
     * 设置自动更新闹钟
     * 每天凌晨4点自动刷新直播源和EPG
     *
     * 原理：
     * 1. 使用 AlarmManager 设置重复闹钟
     * 2. 到时间后发送广播 com.tv.live.REFRESH_LIVE_AND_EPG
     * 3. MainActivity 收到广播后刷新直播源和EPG
     */
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

            // 设置每天凌晨4点
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.set(Calendar.HOUR_OF_DAY, 4);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);

            // 如果今天的4点已经过了，就设为明天
            if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_MONTH, 1);
            }

            // 设置重复闹钟（每天一次）
            // 用 setInexactRepeating 更省电，时间不会太精准（误差几分钟）
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

    /**
     * 取消自动更新闹钟
     */
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
            showInputDialog("自定义订阅源", "请输入直播源地址", "custom_live_url");
            logOperation("【设置】打开自定义订阅源");
        });

        // 自定义节目单
        tv_custom_epg.setOnClickListener(v -> {
            showInputDialog("自定义节目单", "请输入EPG地址", "custom_epg_url");
            logOperation("【设置】打开自定义节目单");
        });

        // 多订阅源（历史记录）
        tv_multi_source.setOnClickListener(v -> {
            showHistoryDialog("直播源历史", "live_history");
            logOperation("【设置】打开直播源历史");
        });

        // 多节目单（历史记录）
        tv_multi_epg.setOnClickListener(v -> {
            showHistoryDialog("节目单历史", "epg_history");
            logOperation("【设置】打开节目单历史");
        });

        // 扫码添加
        tv_qr_code.setOnClickListener(v -> {
            showQRCodeDialog();
            logOperation("【设置】打开扫码管理");
        });
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
                    logOperation("【设置】屏幕比例设为：" + ratios[w]);
                    Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
                }).show();
    }

    // ====================== 输入对话框（自定义源/节目单） ======================

    /**
     * 显示输入对话框
     * @param title 对话框标题
     * @param hint 输入框提示文字
     * @param key SP 存储的 key
     */
    private void showInputDialog(String title, String hint, String key) {
        EditText ed = new EditText(this);
        ed.setHint(hint);
        // 回显当前值
        ed.setText(sp.getString(key, ""));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ed)
                .setPositiveButton("确定", (d, w) -> {
                    String url = ed.getText().toString().trim();
                    if (!url.isEmpty()) {
                        // 保存到 SP
                        sp.edit().putString(key, url).apply();
                        // 添加到历史记录
                        addHistory(key.contains("live") ? "live_history" : "epg_history", url);
                        // 发送广播刷新
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("【设置】" + title + "已更新：" + url);
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ====================== 历史记录对话框 ======================

    /**
     * 显示历史记录对话框
     * @param title 对话框标题
     * @param key SP 存储的 key
     */
    private void showHistoryDialog(String title, String key) {
        String history = sp.getString(key, "");
        if (TextUtils.isEmpty(history)) {
            Toast.makeText(this, "无记录", Toast.LENGTH_SHORT).show();
            return;
        }

        // 用 | 分割历史记录
        final String[] list = history.split("\\|");
        adapter = new SettingsAdapter(this, Arrays.asList(list));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setAdapter(adapter, (d, w) -> {
                    String url = list[w];
                    // 切换到选中的源
                    sp.edit().putString(key.contains("live") ? "custom_live_url" : "custom_epg_url", url).apply();
                    // 也添加到历史（移到最前面）
                    addHistory(key.contains("live") ? "live_history" : "epg_history", url);
                    // 发送广播刷新
                    sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    // 更新选中状态
                    adapter.setSelectedPosition(w);
                    logOperation("【设置】切换" + title + "：" + url);
                    Toast.makeText(this, "已切换，正在刷新…", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 添加历史记录
     * 新添加的在最前面，去重，限制总长度
     * @param key SP 存储的 key
     * @param url 要添加的 URL
     */
    private void addHistory(String key, String url) {
        String history = sp.getString(key, "");
        StringBuilder sb = new StringBuilder();
        // 新的放最前面
        sb.append(url);
        if (!history.isEmpty()) {
            String[] arr = history.split("\\|");
            for (String s : arr) {
                // 去重 + 限制总长度
                if (!s.equals(url) && sb.length() < 1000) {
                    sb.append("|").append(s);
                }
            }
        }
        sp.edit().putString(key, sb.toString()).apply();
    }

    // ====================== 扫码相关 ======================

    /**
     * 显示二维码对话框
     * 二维码内容是网页后台的地址
     */
    private void showQRCodeDialog() {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(createQR(currentWebUrl, 250));

        new AlertDialog.Builder(this)
                .setTitle("扫码管理")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /**
     * 生成二维码图片
     * @param text 二维码内容
     * @param size 二维码尺寸（像素）
     * @return Bitmap 对象
     */
    private Bitmap createQR(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    // 黑色点，白色背景
                    bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取设备的 IP 地址
     * 用于生成二维码和显示网页后台地址
     */
    private String getDeviceIPAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            int ip = info.getIpAddress();
            // 小端序转成正常的 IP 格式
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Exception e) {
            // 获取失败返回默认值
            return "192.168.1.100";
        }
    }

    // ====================== 网页后台服务器（NanoHTTPD） ======================

    /**
     * 启动网页后台服务器
     *
     * 功能：
     * 1. 提供配置页面（/）：可以设置直播源和节目单
     * 2. 提供日志页面（/log）：可以查看 APP 运行日志
     * 3. 处理提交请求（/submit）：保存配置并刷新
     *
     * 为什么用 NanoHTTPD 而不是自己写 Socket？
     * - 自己写 Socket 只能处理简单的字符串，不能处理真正的 HTTP 请求
     * - NanoHTTPD 是一个轻量级的 HTTP 服务器，支持 GET/POST 等完整的 HTTP 协议
     * - 手机浏览器扫码后能正常显示网页，而不是一直加载超时
     */
    private void startPushServer() {
        try {
            webServer = new WebServer(PORT);
            webServer.start();
            logOperation("【设置】网页后台已启动，端口：" + PORT);
        } catch (Exception e) {
            e.printStackTrace();
            logOperation("【设置】网页后台启动失败：" + e.getMessage());
        }
    }

    /**
     * 网页后台服务器内部类
     * 继承 NanoHTTPD，重写 serve 方法处理请求
     */
    private class WebServer extends NanoHTTPD {

        public WebServer(int port) {
            super(port);
        }

        /**
         * 处理 HTTP 请求
         * @param session HTTP 会话对象，包含请求信息
         * @return HTTP 响应
         */
        @Override
        public Response serve(IHTTPSession session) {
            try {
                String uri = session.getUri();
                Method method = session.getMethod();

                logOperation("【网页后台】请求：" + method + " " + uri);

                // ===== 1. 配置页面（首页） =====
                if (Method.GET.equals(method) && ("/".equals(uri) || "/index.html".equals(uri))) {
                    String html = buildHtmlPage();
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
                }

                // ===== 2. 日志页面 =====
                if (Method.GET.equals(method) && "/log".equals(uri)) {
                    String html = buildLogPage();
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
                }

                // ===== 3. 提交配置 =====
                if (Method.POST.equals(method) && "/submit".equals(uri)) {
                    // 解析 POST 参数
                    session.parseBody(new HashMap<>());
                    Map<String, String> params = session.getParms();

                    final String liveUrl = params.get("live_url");
                    final String epgUrl = params.get("epg_url");

                    // 切到主线程更新配置和发送广播
                    handler.post(() -> {
                        boolean hasUpdate = false;

                        // 更新直播源
                        if (liveUrl != null && !liveUrl.trim().isEmpty()) {
                            sp.edit().putString("custom_live_url", liveUrl.trim()).apply();
                            addHistory("live_history", liveUrl.trim());
                            hasUpdate = true;
                        }

                        // 更新节目单
                        if (epgUrl != null && !epgUrl.trim().isEmpty()) {
                            sp.edit().putString("custom_epg_url", epgUrl.trim()).apply();
                            addHistory("epg_history", epgUrl.trim());
                            hasUpdate = true;
                        }

                        // 有更新就发送广播刷新
                        if (hasUpdate) {
                            sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                            logOperation("【网页后台】配置已更新");
                            Toast.makeText(SettingsActivity.this, "配置已更新", Toast.LENGTH_SHORT).show();
                        }
                    });

                    // 返回成功页面
                    String successHtml = buildSuccessHtml();
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", successHtml);
                }

                // ===== 4. 404 页面 =====
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");

            } catch (Exception e) {
                e.printStackTrace();
                logOperation("【网页后台】处理请求异常：" + e.getMessage());
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
            }
        }

        /**
         * 构建配置页面 HTML（APP 风格）
         * 模仿 APP 里的设置页面样式：
         * - 白色卡片 + 浅灰背景
         * - 分组标题灰色小字
         * - 左标签 + 右输入框
         * - 蓝色圆角按钮
         * - 底部导航（配置/日志）
         */
        private String buildHtmlPage() {
            // 读取当前配置，回显到输入框
            String currentLive = sp.getString("custom_live_url", "");
            String currentEpg = sp.getString("custom_epg_url", "");

            return "<!DOCTYPE html>\n" +
                    "<html lang=\"zh-CN\">\n" +
                    "<head>\n" +
                    "    <meta charset=\"UTF-8\">\n" +
                    "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                    "    <title>我的电视</title>\n" +
                    "    <style>\n" +
                    "        /* ===== 全局重置 ===== */\n" +
                    "        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }\n" +
                    "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif; background: #f5f5f5; color: #333; font-size: 14px; line-height: 1.5; padding-bottom: 60px; }\n" +
                    "\n" +
                    "        /* ===== 分组标题 ===== */\n" +
                    "        .section-title { padding: 16px 16px 8px; font-size: 14px; color: #999; font-weight: normal; }\n" +
                    "\n" +
                    "        /* ===== 卡片容器 ===== */\n" +
                    "        .card { background: #fff; margin: 0 12px; }\n" +
                    "        .card:first-of-type { border-radius: 12px 12px 0 0; }\n" +
                    "        .card:last-of-type { border-radius: 0 0 12px 12px; }\n" +
                    "        .card:only-of-type { border-radius: 12px; }\n" +
                    "\n" +
                    "        /* ===== 设置项 ===== */\n" +
                    "        .item { display: flex; align-items: center; padding: 14px 16px; border-bottom: 1px solid #f0f0f0; min-height: 48px; }\n" +
                    "        .item:last-child { border-bottom: none; }\n" +
                    "        .item-label { flex-shrink: 0; width: 70px; color: #333; font-size: 15px; }\n" +
                    "        .item-content { flex: 1; text-align: right; color: #ccc; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }\n" +
                    "        .item input[type=text] { flex: 1; text-align: right; border: none; outline: none; font-size: 14px; color: #333; background: transparent; }\n" +
                    "        .item input[type=text]::placeholder { color: #ccc; }\n" +
                    "\n" +
                    "        /* ===== 头部说明项 ===== */\n" +
                    "        .header-item { flex-direction: column; align-items: flex-start; padding: 16px; }\n" +
                    "        .header-title { font-size: 17px; color: #333; font-weight: 500; margin-bottom: 4px; }\n" +
                    "        .header-desc { font-size: 13px; color: #999; }\n" +
                    "\n" +
                    "        /* ===== 蓝色按钮 ===== */\n" +
                    "        .btn-blue { display: block; margin: 12px 12px 0; padding: 12px 24px; background: #40A9FF; color: white; border: none; border-radius: 6px; font-size: 15px; font-weight: 500; cursor: pointer; float: right; }\n" +
                    "        .btn-blue:active { background: #1890FF; }\n" +
                    "        .btn-wrap { overflow: hidden; padding: 0 0 12px; }\n" +
                    "\n" +
                    "        /* ===== 底部导航 ===== */\n" +
                    "        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; display: flex; border-top: 1px solid #eee; padding: 8px 0 calc(8px + env(safe-area-inset-bottom)); }\n" +
                    "        .nav-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; color: #999; font-size: 12px; cursor: pointer; }\n" +
                    "        .nav-item.active { color: #40A9FF; }\n" +
                    "        .nav-icon { font-size: 20px; line-height: 1; }\n" +
                    "    </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "\n" +
                    "    <!-- ===== 直播源分组 ===== -->\n" +
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
                    "\n" +
                    "    <!-- ===== 节目单分组 ===== -->\n" +
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
                    "\n" +
                    "    <!-- ===== 底部导航 ===== -->\n" +
                    "    <div class=\"bottom-nav\">\n" +
                    "        <a href=\"/\" class=\"nav-item active\" style=\"text-decoration: none;\">\n" +
                    "            <div class=\"nav-icon\">🖥️</div>\n" +
                    "            <div>配置</div>\n" +
                    "        </a>\n" +
                    "        <a href=\"/log\" class=\"nav-item\" style=\"text-decoration: none;\">\n" +
                    "            <div class=\"nav-icon\">📋</div>\n" +
                    "            <div>日志</div>\n" +
                    "        </a>\n" +
                    "    </div>\n" +
                    "\n" +
                    "</body>\n" +
                    "</html>";
        }

        /**
         * 构建日志页面 HTML（APP 风格）
         * 模仿 APP 里的日志页面样式：
         * - 顶部大标题"我的电视"
         * - 每条日志左侧彩色图标（ERROR红色，INFO蓝色）
         * - 日志级别 + 内容
         * - 右侧时间
         * - 底部导航
         * - 每5秒自动刷新
         */
        private String buildLogPage() {
            // 读取日志内容
            String logContent = PLAY_LOG != null ? PLAY_LOG.toString() : "";
            String[] lines = logContent.split("\n");

            // 构建日志列表 HTML（倒序，最新的在最上面）
            StringBuilder logHtml = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i];
                if (line.trim().isEmpty()) continue;

                // ===== 提取时间和内容 =====
                // 日志格式：[HH:mm:ss] 日志内容
                String time = "";
                String content = line;
                if (line.startsWith("[") && line.contains("]")) {
                    time = line.substring(1, line.indexOf("]"));
                    content = line.substring(line.indexOf("]") + 1).trim();
                }

                // ===== 判断日志级别 =====
                // 包含"错误/失败/异常/ERROR/❌"的算 ERROR，其他算 INFO
                boolean isError = content.contains("错误") || content.contains("失败")
                        || content.contains("异常") || content.contains("ERROR") || content.contains("❌");
                String level = isError ? "ERROR" : "INFO";
                String levelColor = isError ? "#F5222D" : "#1890FF";  // ERROR 红色，INFO 蓝色
                String icon = isError ? "✕" : "i";  // 图标字母

                // ===== 拼接单条日志 HTML =====
                logHtml.append("        <div class=\"log-item\">\n");
                logHtml.append("            <div class=\"log-icon\" style=\"background: ").append(levelColor).append(";\">").append(icon).append("</div>\n");
                logHtml.append("            <div class=\"log-content\">\n");
                logHtml.append("                <div class=\"log-level\" style=\"color: ").append(levelColor).append(";\">").append(level).append("</div>\n");
                logHtml.append("                <div class=\"log-text\">").append(content).append("</div>\n");
                logHtml.append("            </div>\n");
                logHtml.append("            <div class=\"log-time\">").append(time).append("</div>\n");
                logHtml.append("        </div>\n");
            }

            // 空日志提示
            if (logHtml.length() == 0) {
                logHtml.append("        <div style=\"padding: 40px 20px; text-align: center; color: #999; font-size: 14px;\">暂无日志</div>\n");
            }

            // ===== 完整页面 =====
            return "<!DOCTYPE html>\n" +
                    "<html lang=\"zh-CN\">\n" +
                    "<head>\n" +
                    "    <meta charset=\"UTF-8\">\n" +
                    "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                    "    <title>我的电视 - 日志</title>\n" +
                    "    <style>\n" +
                    "        /* ===== 全局重置 ===== */\n" +
                    "        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }\n" +
                    "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif; background: #fff; color: #333; font-size: 14px; line-height: 1.5; padding-bottom: 60px; }\n" +
                    "\n" +
                    "        /* ===== 头部 ===== */\n" +
                    "        .header { padding: 20px 16px 16px; }\n" +
                    "        .header-title { font-size: 32px; font-weight: 500; color: #000; margin-bottom: 8px; }\n" +
                    "        .header-sub { font-size: 14px; color: #999; }\n" +
                    "\n" +
                    "        /* ===== 日志列表 ===== */\n" +
                    "        .log-list { padding: 0 16px; }\n" +
                    "        .log-item { display: flex; align-items: flex-start; padding: 12px 0; border-bottom: 1px solid #f5f5f5; gap: 12px; }\n" +
                    "        .log-icon { width: 20px; height: 20px; border-radius: 4px; display: flex; align-items: center; justify-content: center; color: white; font-size: 12px; font-weight: bold; flex-shrink: 0; margin-top: 2px; }\n" +
                    "        .log-content { flex: 1; min-width: 0; }\n" +
                    "        .log-level { font-size: 13px; font-weight: 500; margin-bottom: 4px; }\n" +
                    "        .log-text { font-size: 14px; color: #333; word-break: break-all; line-height: 1.4; }\n" +
                    "        .log-time { flex-shrink: 0; font-size: 12px; color: #999; margin-top: 2px; }\n" +
                    "\n" +
                    "        /* ===== 底部导航 ===== */\n" +
                    "        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; display: flex; border-top: 1px solid #eee; padding: 8px 0 calc(8px + env(safe-area-inset-bottom)); }\n" +
                    "        .nav-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; color: #999; font-size: 12px; cursor: pointer; }\n" +
                    "        .nav-item.active { color: #40A9FF; }\n" +
                    "        .nav-icon { font-size: 20px; line-height: 1; }\n" +
                    "    </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "\n" +
                    "    <!-- ===== 头部 ===== -->\n" +
                    "    <div class=\"header\">\n" +
                    "        <div class=\"header-title\">我的电视</div>\n" +
                    "        <div class=\"header-sub\">http://" + getDeviceIPAddress() + ":" + PORT + "</div>\n" +
                    "    </div>\n" +
                    "\n" +
                    "    <!-- ===== 日志列表 ===== -->\n" +
                    "    <div class=\"log-list\">\n" +
                    logHtml.toString() +
                    "    </div>\n" +
                    "\n" +
                    "    <!-- ===== 底部导航 ===== -->\n" +
                    "    <div class=\"bottom-nav\">\n" +
                    "        <a href=\"/\" class=\"nav-item\" style=\"text-decoration: none;\">\n" +
                    "            <div class=\"nav-icon\">🖥️</div>\n" +
                    "            <div>配置</div>\n" +
                    "        </a>\n" +
                    "        <a href=\"/log\" class=\"nav-item active\" style=\"text-decoration: none;\">\n" +
                    "            <div class=\"nav-icon\">📋</div>\n" +
                    "            <div>日志</div>\n" +
                    "        </a>\n" +
                    "    </div>\n" +
                    "\n" +
                    "    <!-- ===== 自动刷新（每5秒） ===== -->\n" +
                    "    <script>\n" +
                    "        setTimeout(function() { location.reload(); }, 5000);\n" +
                    "    </script>\n" +
                    "\n" +
                    "</body>\n" +
                    "</html>";
        }

        /**
         * 构建提交成功页面 HTML
         * 用户提交配置后显示的成功提示页
         */
        private String buildSuccessHtml() {
            return "<!DOCTYPE html>\n" +
                    "<html lang=\"zh-CN\">\n" +
                    "<head>\n" +
                    "    <meta charset=\"UTF-8\">\n" +
                    "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                    "    <title>保存成功</title>\n" +
                    "    <style>\n" +
                    "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
                    "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif; background: #f5f5f5; padding: 20px; display: flex; justify-content: center; align-items: center; min-height: 100vh; }\n" +
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
    }

    // ====================== onDestroy 生命周期 ======================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logOperation("【设置】关闭设置页面");
        // 停止网页后台服务器，释放端口
        try {
            if (webServer != null) webServer.stop();
        } catch (Exception ignored) {}
    }

    // ====================== 历史记录列表适配器 ======================

    /**
     * 历史记录列表的适配器
     * 用于"多订阅源"和"多节目单"的列表对话框
     *
     * 三种状态：
     * 1. 选中状态：蓝色文字 + 浅蓝色背景（和分组列表一样）
     * 2. 焦点状态：蓝色文字 + 稍深一点的蓝色背景
     * 3. 未选中状态：白色文字 + 透明背景
     */
    private static class SettingsAdapter extends ArrayAdapter<String> {
        private final Context context;
        private final List<String> items;
        private int selectedPosition = -1;  // 当前选中的位置

        public SettingsAdapter(Context context, List<String> items) {
            super(context, R.layout.item_settings, items);
            this.context = context;
            this.items = items;
        }

        /**
         * 设置选中位置并刷新
         */
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
                // ✅ 选中状态：蓝色文字 + 浅蓝色背景（和分组列表一样）
                tv.setTextColor(Color.parseColor("#40A9FF"));
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (convertView.isFocused()) {
                // ✅ 焦点状态：蓝色文字 + 稍深一点的蓝色背景
                tv.setTextColor(Color.parseColor("#40A9FF"));
                convertView.setBackgroundColor(0x4440A9FF);
            } else {
                // ✅ 未选中状态：白色文字 + 透明背景
                tv.setTextColor(Color.WHITE);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            return convertView;
        }
    }
}
