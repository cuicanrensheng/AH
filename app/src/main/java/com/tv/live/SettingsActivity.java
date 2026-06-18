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

import java.io.BufferedReader;
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
 *
 * 功能清单：
 * 1. 开机自启开关
 * 2. 节目单开关
 * 3. 自动更新源（每天凌晨4点，AlarmManager）
 * 4. 换台反转
 * 5. 数字选台
 * 6. 屏幕比例设置
 * 7. 自定义订阅源/节目单
 * 8. 多订阅源/节目单历史记录
 * 9. 扫码添加（二维码 + 自建HTTP网页后台）
 * 10. 解析&播放日志查看
 * 11. 操作日志查看
 * 12. 检查更新
 *
 * 【网页后台说明】
 * 自己实现简易 HTTP 服务器，不依赖外部库。
 * 支持：
 * - GET /        → 配置页（直播源+节目单+播放器+调试）
 * - GET /log     → 日志页（操作日志/解析日志切换）
 * - POST /submit → 提交配置
 */
public class SettingsActivity extends AppCompatActivity {

    // ====================== 控件声明 ======================

    /** 5个开关控件 */
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    /** 纯文本点击项 */
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;

    // ====================== 配置相关 ======================

    /** SharedPreferences 配置存储 */
    private SharedPreferences sp;

    // ====================== 网页后台相关 ======================

    /** 网页后台访问地址（用于生成二维码） */
    private String currentWebUrl;
    /** HTTP 服务器 Socket */
    private ServerSocket serverSocket;
    /** 主线程 Handler，用于子线程更新 UI 和发送广播 */
    private Handler handler = new Handler(Looper.getMainLooper());
    /** 网页后台端口号 */
    private static final int PORT = 10481;

    // ====================== SP Key 常量 ======================

    /** 自定义直播源地址 */
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    /** 自定义节目单地址 */
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    /** 自定义 User-Agent */
    private static final String KEY_CUSTOM_UA = "custom_user_agent";

    // ====================== 历史记录列表适配器 ======================

    /** 历史记录列表适配器（多订阅源/多节目单用） */
    private SettingsAdapter adapter;

    // ====================== 全局日志系统 ======================

    /**
     * 解析&播放日志
     * 用 volatile 保证多线程可见性（因为 HTTP 服务器在子线程写日志）
     */
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();

    /**
     * 记录解析&播放日志
     * @param msg 日志内容
     */
    public static void log(String msg) {
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
     * 记录用户的所有操作：切台、打开设置、切换分组、网页后台请求等
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
            tv.setText("暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作，\n以及网页后台的启动、请求、响应等详细信息。");
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
        // 操作日志（包含网页后台详细日志）
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
        startHttpServer();

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
            showInputDialog("自定义订阅源", "请输入直播源地址", KEY_CUSTOM_LIVE);
            logOperation("【设置】打开自定义订阅源");
        });

        // 自定义节目单
        tv_custom_epg.setOnClickListener(v -> {
            showInputDialog("自定义节目单", "请输入EPG地址", KEY_CUSTOM_EPG);
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
                    sp.edit().putString(key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG, url).apply();
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

    // ====================== ✅ 自建 HTTP 服务器（核心修复版） ======================

    /**
     * 启动 HTTP 服务器
     *
     * 【修复说明】
     * 之前的版本用 reader.read(buffer) 循环读取，会导致阻塞——
     * 因为 HTTP/1.1 默认是 keep-alive 的，客户端发完请求后不会立即关闭连接，
     * read() 就一直等，页面就一直在加载转圈。
     *
     * 修复方案：
     * 1. 用 BufferedReader.readLine() 按行读取请求头
     * 2. 读到空行就知道请求头结束了
     * 3. POST 请求再根据 Content-Length 读取指定长度的 body
     * 4. 每一步都打详细日志到操作日志，方便排查
     */
    private void startHttpServer() {
        new Thread(() -> {
            try {
                logOperation("【网页后台】正在启动服务器，端口：" + PORT);

                // 创建 ServerSocket，监听指定端口
                serverSocket = new ServerSocket(PORT);
                // 允许端口复用，避免重启时端口被占用
                serverSocket.setReuseAddress(true);

                logOperation("【网页后台】✅ 启动成功，监听端口：" + PORT);
                logOperation("【网页后台】访问地址：http://" + getDeviceIPAddress() + ":" + PORT);

                // 循环接受连接
                while (!serverSocket.isClosed()) {
                    try {
                        // accept() 会阻塞，直到有新连接进来
                        Socket socket = serverSocket.accept();
                        logOperation("【网页后台】新连接进入：" + socket.getInetAddress());

                        // 每个请求开一个线程处理，避免阻塞其他请求
                        new Thread(() -> handleHttpRequest(socket)).start();
                    } catch (Exception e) {
                        // 正常关闭时也会抛异常，这里判断一下
                        if (!serverSocket.isClosed()) {
                            logOperation("【网页后台】接受连接异常：" + e.getMessage());
                        }
                    }
                }

                logOperation("【网页后台】服务器已停止");
            } catch (Exception e) {
                e.printStackTrace();
                logOperation("【网页后台】❌ 启动失败：" + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }).start();
    }

    /**
     * 处理单个 HTTP 请求
     *
     * 【完整流程】
     * 1. 按行读取请求头，读到空行结束
     * 2. 解析请求行（方法、路径、协议版本）
     * 3. 解析 Content-Length（POST 请求用）
     * 4. POST 请求：读取指定长度的 body
     * 5. 路由分发到对应页面
     * 6. 发送 HTTP 响应
     *
     * 【每一步都打日志】
     * 所有关键步骤都输出到操作日志，方便排查"进不去后台"的问题
     */
    private void handleHttpRequest(Socket socket) {
        try {
            logOperation("【网页后台】开始处理请求，客户端：" + socket.getInetAddress());

            // ===== 1. 读取请求头（按行读，读到空行结束） =====
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));

            List<String> headerLines = new ArrayList<>();
            String line;
            int lineCount = 0;

            while ((line = reader.readLine()) != null) {
                lineCount++;
                // 空行表示请求头结束（HTTP 协议规定）
                if (line.isEmpty()) {
                    break;
                }
                headerLines.add(line);
                // 防止恶意请求，最多读 100 行
                if (lineCount > 100) {
                    logOperation("【网页后台】请求头超过100行，停止读取");
                    break;
                }
            }

            logOperation("【网页后台】读取到 " + headerLines.size() + " 行请求头");

            // 请求为空，直接关闭连接
            if (headerLines.isEmpty()) {
                logOperation("【网页后台】请求为空，关闭连接");
                socket.close();
                return;
            }

            // ===== 2. 解析请求行 =====
            // 格式：GET /path HTTP/1.1
            String firstLine = headerLines.get(0);
            String[] parts = firstLine.split(" ");
            if (parts.length < 2) {
                logOperation("【网页后台】请求行格式错误：" + firstLine);
                sendResponse(socket, "400 Bad Request", "text/plain", "Bad Request");
                return;
            }

            String method = parts[0];  // GET / POST
            String path = parts[1];    // /  /log  /submit

            logOperation("【网页后台】请求：" + method + " " + path);

            // ===== 3. 解析 Content-Length（POST 请求用） =====
            int contentLength = 0;
            for (String headerLine : headerLines) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                    } catch (Exception e) {
                        contentLength = 0;
                    }
                    break;
                }
            }

            logOperation("【网页后台】Content-Length: " + contentLength);

            // ===== 4. POST 请求：读取 body =====
            String body = "";
            if ("POST".equals(method) && contentLength > 0) {
                char[] bodyBuffer = new char[contentLength];
                int totalRead = 0;
                // 读满 contentLength 个字符就停，不会阻塞
                while (totalRead < contentLength) {
                    int len = reader.read(bodyBuffer, totalRead, contentLength - totalRead);
                    if (len <= 0) break;
                    totalRead += len;
                }
                body = new String(bodyBuffer, 0, totalRead);
                logOperation("【网页后台】POST body 内容：" + body);
            }

            // ===== 5. 路由分发 =====
            String responseBody = "";
            String contentType = "text/html; charset=utf-8";

            // 去掉 URL 里的查询参数（只取路径部分）
            String purePath = path.contains("?") ? path.split("\\?")[0] : path;

            // 5.1 配置页面（首页）
            if ("GET".equals(method) && ("/".equals(purePath) || "/index.html".equals(purePath))) {
                logOperation("【网页后台】→ 返回配置页面");
                responseBody = buildConfigPage();
            }
            // 5.2 日志页面
            else if ("GET".equals(method) && "/log".equals(purePath)) {
                logOperation("【网页后台】→ 返回日志页面");
                responseBody = buildLogPage();
            }
            // 5.3 提交配置（POST）
            else if ("POST".equals(method) && "/submit".equals(purePath)) {
                logOperation("【网页后台】→ 处理配置提交");
                Map<String, String> params = parseFormData(body);

                final String liveUrl = params.get("live_url");
                final String epgUrl = params.get("epg_url");
                final String customUa = params.get("custom_ua");

                logOperation("【网页后台】提交参数 - live: " + liveUrl + ", epg: " + epgUrl + ", ua: " + customUa);

                // 切到主线程保存配置（因为 SP 和广播都要在主线程）
                handler.post(() -> {
                    boolean hasUpdate = false;

                    // 更新直播源
                    if (liveUrl != null && !liveUrl.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_LIVE, liveUrl.trim()).apply();
                        addHistory("live_history", liveUrl.trim());
                        hasUpdate = true;
                    }

                    // 更新节目单
                    if (epgUrl != null && !epgUrl.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_EPG, epgUrl.trim()).apply();
                        addHistory("epg_history", epgUrl.trim());
                        hasUpdate = true;
                    }

                    // 更新自定义 UA
                    if (customUa != null && !customUa.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_UA, customUa.trim()).apply();
                        hasUpdate = true;
                    }

                    // 有更新就发送广播刷新
                    if (hasUpdate) {
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("【网页后台】配置已更新，发送刷新广播");
                        Toast.makeText(SettingsActivity.this, "配置已更新", Toast.LENGTH_SHORT).show();
                    }
                });

                responseBody = buildSuccessPage();
            }
            // 5.4 404 页面
            else {
                logOperation("【网页后台】→ 404 Not Found: " + path);
                responseBody = "404 Not Found";
                contentType = "text/plain; charset=utf-8";
            }

            // ===== 6. 发送响应 =====
            logOperation("【网页后台】准备发送响应，内容长度：" + responseBody.getBytes("UTF-8").length + " 字节");
            sendResponse(socket, "200 OK", contentType, responseBody);
            logOperation("【网页后台】✅ 响应发送完成");

        } catch (Exception e) {
            e.printStackTrace();
            logOperation("【网页后台】❌ 处理请求异常：" + e.getClass().getSimpleName() + " - " + e.getMessage());
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 发送 HTTP 响应
     *
     * 【HTTP 响应格式】
     * 第一行：状态行（HTTP/1.1 200 OK）
     * 然后是响应头（Content-Type、Content-Length 等）
     * 空行
     * 响应体（HTML 内容）
     *
     * @param socket 客户端连接
     * @param status 状态码（如 "200 OK"）
     * @param contentType 内容类型
     * @param body 响应体内容
     */
    private void sendResponse(Socket socket, String status, String contentType, String body) throws Exception {
        byte[] bodyBytes = body.getBytes("UTF-8");

        // 构建响应头
        String header = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +  // 告诉客户端发完就关，避免 keep-alive
                "\r\n";  // 空行分隔头和体

        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes("UTF-8"));
        out.write(bodyBytes);
        out.flush();

        logOperation("【网页后台】响应头+体已写入输出流");

        socket.close();
    }

    /**
     * 解析表单数据（application/x-www-form-urlencoded）
     * 格式：key1=value1&key2=value2
     *
     * @param body POST 请求体
     * @return 参数键值对 Map
     */
    private Map<String, String> parseFormData(String body) {
        Map<String, String> params = new HashMap<>();
        if (body == null || body.isEmpty()) return params;

        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    // URL 解码，处理中文和特殊字符
                    String key = URLDecoder.decode(kv[0], "UTF-8");
                    String value = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
                    params.put(key, value);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            logOperation("【网页后台】解析表单数据失败：" + e.getMessage());
        }
        return params;
    }

    // ====================== ✅ HTML 页面构建 ======================

    /**
     * 构建配置页面 HTML（APP 风格，4个分组）
     *
     * 分组：
     * 1. 直播源 - 自定义直播源链接 + 推送按钮
     * 2. 节目单 - 自定义节目单链接 + 推送按钮
     * 3. 播放器 - 自定义UA + 推送按钮
     * 4. 调试 - 上传apk
     *
     * 样式完全模仿 APP 里的设置页面
     */
    private String buildConfigPage() {
        // 读取当前配置，回显到输入框
        String currentLive = sp.getString(KEY_CUSTOM_LIVE, "");
        String currentEpg = sp.getString(KEY_CUSTOM_EPG, "");
        String currentUa = sp.getString(KEY_CUSTOM_UA, "");

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
                "\n" +
                "        /* ===== 设置项 ===== */\n" +
                "        .item { display: flex; align-items: center; padding: 14px 16px; border-bottom: 1px solid #f0f0f0; min-height: 48px; }\n" +
                "        .item:last-child { border-bottom: none; }\n" +
                "        .item-label { flex-shrink: 0; width: 70px; color: #333; font-size: 15px; }\n" +
                "        .item input[type=text] { flex: 1; text-align: right; border: none; outline: none; font-size: 14px; color: #333; background: transparent; }\n" +
                "        .item input[type=text]::placeholder { color: #ccc; }\n" +
                "\n" +
                "        /* ===== 头部说明项 ===== */\n" +
                "        .header-item { flex-direction: column; align-items: flex-start; padding: 16px; }\n" +
                "        .header-title { font-size: 17px; color: #333; font-weight: 500; margin-bottom: 4px; }\n" +
                "        .header-desc { font-size: 13px; color: #999; }\n" +
                "\n" +
                "        /* ===== 蓝色按钮 ===== */\n" +
                "        .btn-blue { display: block; margin: 12px 12px 0; padding: 8px 20px; background: #40A9FF; color: white; border: none; border-radius: 6px; font-size: 14px; font-weight: 500; cursor: pointer; float: right; }\n" +
                "        .btn-blue:active { background: #1890FF; }\n" +
                "        .btn-wrap { overflow: hidden; padding: 0 0 12px; }\n" +
                "\n" +
                "        /* ===== 上传区域 ===== */\n" +
                "        .upload-box { width: 80px; height: 80px; border: 1px solid #eee; border-radius: 8px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; color: #ccc; cursor: pointer; margin-left: auto; }\n" +
                "        .upload-icon { font-size: 24px; }\n" +
                "        .upload-text { font-size: 11px; }\n" +
                "\n" +
                "        /* ===== 底部导航 ===== */\n" +
                "        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; display: flex; border-top: 1px solid #eee; padding: 8px 0 calc(8px + env(safe-area-inset-bottom)); }\n" +
                "        .nav-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; color: #999; font-size: 12px; cursor: pointer; text-decoration: none; }\n" +
                "        .nav-item.active { color: #40A9FF; }\n" +
                "        .nav-icon { font-size: 20px; line-height: 1; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "\n" +
                "    <!-- ===== 1. 直播源分组 ===== -->\n" +
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
                "    <!-- ===== 2. 节目单分组 ===== -->\n" +
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
                "    <!-- ===== 3. 播放器分组（新增） ===== -->\n" +
                "    <div class=\"section-title\">播放器</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item header-item\">\n" +
                "            <div class=\"header-title\">自定义UA</div>\n" +
                "            <div class=\"header-desc\">播放器自定义UA</div>\n" +
                "        </div>\n" +
                "        <form method=\"post\" action=\"/submit\">\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\"></div>\n" +
                "                <input type=\"text\" name=\"custom_ua\" placeholder=\"自定义 User-Agent\" value=\"" + currentUa + "\">\n" +
                "            </div>\n" +
                "            <div class=\"btn-wrap\">\n" +
                "                <button type=\"submit\" class=\"btn-blue\">推送</button>\n" +
                "            </div>\n" +
                "        </form>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- ===== 4. 调试分组（新增） ===== -->\n" +
                "    <div class=\"section-title\">调试</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item\">\n" +
                "            <div class=\"item-label\">上传apk</div>\n" +
                "            <div class=\"upload-box\" onclick=\"alert('功能开发中，敬请期待~')\">\n" +
                "                <div class=\"upload-icon\">📷</div>\n" +
                "                <div class=\"upload-text\">上传</div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- ===== 底部导航 ===== -->\n" +
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
                "\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * 构建日志页面 HTML（APP 风格，支持 tab 切换）
     *
     * 【新增功能】
     * - 顶部 tab 切换：操作日志 / 解析日志
     * - 操作日志：显示 OPERATION_LOG（用户操作 + 网页后台日志）
     * - 解析日志：显示 PLAY_LOG（EPG解析 + 播放器日志）
     * - 默认显示操作日志
     * - 每5秒自动刷新
     *
     * 【为什么用 tab 切换？】
     * 两种日志用途不同，分开显示更清晰，不会混在一起难找
     */
    private String buildLogPage() {
        // ===== 1. 构建操作日志 HTML =====
        String operationLogContent = OPERATION_LOG != null ? OPERATION_LOG.toString() : "";
        String[] opLines = operationLogContent.split("\n");
        StringBuilder opLogHtml = new StringBuilder();

        for (int i = opLines.length - 1; i >= 0; i--) {
            String line = opLines[i];
            if (line.trim().isEmpty()) continue;

            // 提取时间和内容
            String time = "";
            String content = line;
            if (line.startsWith("[") && line.contains("]")) {
                time = line.substring(1, line.indexOf("]"));
                content = line.substring(line.indexOf("]") + 1).trim();
            }

            // 判断级别（操作日志一般都是 INFO 级别，除了错误）
            boolean isError = content.contains("失败") || content.contains("异常") || content.contains("❌");
            String level = isError ? "ERROR" : "INFO";
            String levelColor = isError ? "#F5222D" : "#1890FF";
            String icon = isError ? "✕" : "⚙";  // 操作日志用齿轮图标

            opLogHtml.append("        <div class=\"log-item\">\n");
            opLogHtml.append("            <div class=\"log-icon\" style=\"background: ").append(levelColor).append(";\">").append(icon).append("</div>\n");
            opLogHtml.append("            <div class=\"log-content\">\n");
            opLogHtml.append("                <div class=\"log-level\" style=\"color: ").append(levelColor).append(";\">").append(level).append("</div>\n");
            opLogHtml.append("                <div class=\"log-text\">").append(content).append("</div>\n");
            opLogHtml.append("            </div>\n");
            opLogHtml.append("            <div class=\"log-time\">").append(time).append("</div>\n");
            opLogHtml.append("        </div>\n");
        }

        if (opLogHtml.length() == 0) {
            opLogHtml.append("        <div style=\"padding: 40px 20px; text-align: center; color: #999; font-size: 14px;\">暂无操作日志</div>\n");
        }

        // ===== 2. 构建解析日志 HTML =====
        String playLogContent = PLAY_LOG != null ? PLAY_LOG.toString() : "";
        String[] playLines = playLogContent.split("\n");
        StringBuilder playLogHtml = new StringBuilder();

        for (int i = playLines.length - 1; i >= 0; i--) {
            String line = playLines[i];
            if (line.trim().isEmpty()) continue;

            // 提取时间和内容
            String time = "";
            String content = line;
            if (line.startsWith("[") && line.contains("]")) {
                time = line.substring(1, line.indexOf("]"));
                content = line.substring(line.indexOf("]") + 1).trim();
            }

            // 判断级别
            boolean isError = content.contains("错误") || content.contains("失败")
                    || content.contains("异常") || content.contains("ERROR") || content.contains("❌");
            String level = isError ? "ERROR" : "INFO";
            String levelColor = isError ? "#F5222D" : "#1890FF";
            String icon = isError ? "✕" : "i";  // 解析日志用 i 图标

            playLogHtml.append("        <div class=\"log-item\">\n");
            playLogHtml.append("            <div class=\"log-icon\" style=\"background: ").append(levelColor).append(";\">").append(icon).append("</div>\n");
            playLogHtml.append("            <div class=\"log-content\">\n");
            playLogHtml.append("                <div class=\"log-level\" style=\"color: ").append(levelColor).append(";\">").append(level).append("</div>\n");
            playLogHtml.append("                <div class=\"log-text\">").append(content).append("</div>\n");
            playLogHtml.append("            </div>\n");
            playLogHtml.append("            <div class=\"log-time\">").append(time).append("</div>\n");
            playLogHtml.append("        </div>\n");
        }

        if (playLogHtml.length() == 0) {
            playLogHtml.append("        <div style=\"padding: 40px 20px; text-align: center; color: #999; font-size: 14px;\">暂无解析日志</div>\n");
        }

        // ===== 3. 完整页面（带 tab 切换） =====
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
                "        .header { padding: 20px 16px 12px; }\n" +
                "        .header-title { font-size: 32px; font-weight: 500; color: #000; margin-bottom: 8px; }\n" +
                "        .header-sub { font-size: 14px; color: #999; }\n" +
                "\n" +
                "        /* ===== Tab 切换 ===== */\n" +
                "        .tab-bar { display: flex; border-bottom: 1px solid #f0f0f0; padding: 0 16px; }\n" +
                "        .tab-item { padding: 12px 20px; font-size: 15px; color: #666; cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; }\n" +
                "        .tab-item.active { color: #40A9FF; border-bottom-color: #40A9FF; font-weight: 500; }\n" +
                "\n" +
                "        /* ===== 日志内容区 ===== */\n" +
                "        .log-panel { display: none; }\n" +
                "        .log-panel.active { display: block; }\n" +
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
                "        .nav-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; color: #999; font-size: 12px; cursor: pointer; text-decoration: none; }\n" +
                "        .nav-item.active { color: #40A9FF; }\n" +
                "        .nav-icon { font-size: 20px; line-height: 1; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "\n" +
                "    <!-- ===== 头部 ===== -->\n" +
                "    <div class=\"header\">\n" +
                "        <div class=\"header-title\">我的电视</div>\n" +
                "        <div class=\"header
                -sub\">http://" + getDeviceIPAddress() + ":" + PORT + "</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- ===== Tab 切换 ===== -->\n" +
                "    <div class=\"tab-bar\">\n" +
                "        <div class=\"tab-item active\" onclick=\"switchTab('operation')\">操作日志</div>\n" +
                "        <div class=\"tab-item\" onclick=\"switchTab('play')\">解析日志</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- ===== 操作日志面板 ===== -->\n" +
                "    <div id=\"panel-operation\" class=\"log-panel active\">\n" +
                "        <div class=\"log-list\">\n" +
                opLogHtml.toString() +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- ===== 解析日志面板 ===== -->\n" +
                "    <div id=\"panel-play\" class=\"log-panel\">\n" +
                "        <div class=\"log-list\">\n" +
                playLogHtml.toString() +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- ===== 底部导航 ===== -->\n" +
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
                "\n" +
                "    <!-- ===== Tab 切换脚本 ===== -->\n" +
                "    <script>\n" +
                "        function switchTab(tabName) {\n" +
                "            // 切换 tab 高亮\n" +
                "            document.querySelectorAll('.tab-item').forEach(function(item) {\n" +
                "                item.classList.remove('active');\n" +
                "            });\n" +
                "            event.target.classList.add('active');\n" +
                "\n" +
                "            // 切换面板显示\n" +
                "            document.querySelectorAll('.log-panel').forEach(function(panel) {\n" +
                "                panel.classList.remove('active');\n" +
                "            });\n" +
                "            document.getElementById('panel-' + tabName).classList.add('active');\n" +
                "        }\n" +
                "\n" +
                "        // 每5秒自动刷新\n" +
                "        setTimeout(function() { location.reload(); }, 5000);\n" +
                "    </script>\n" +
                "\n" +
                "</body>\n" +
                "</html>";
    }
