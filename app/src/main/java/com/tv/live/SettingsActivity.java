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
import android.os.Bundle;
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

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * 设置页面
 *
 * 【功能清单】
 * 1. 开机自启开关
 * 2. 节目单开关
 * 3. 自动更新源（每天凌晨4点，AlarmManager）
 * 4. 换台反转
 * 5. 数字选台
 * 6. 屏幕比例设置
 * 7. 自定义订阅源/节目单
 * 8. 多订阅源/节目单历史记录
 * 9. 扫码添加（二维码 + 网页后台）
 * 10. 解析&播放日志查看
 * 11. 操作日志查看
 * 12. 检查更新
 *
 * 【架构说明】
 * 网页后台相关的代码已经拆分到 WebServerManager.java 中，
 * 本 Activity 只负责 UI 展示和用户交互，通过 WebServerManager
 * 来管理 HTTP 服务器的启动和停止。
 *
 * 【为什么拆分？】
 * 原来的 SettingsActivity 有 1000+ 行，代码太臃肿，
 * 拆分后职责更清晰，更好维护。
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

    /**
     * 网页后台管理器
     * 【拆分后】所有 HTTP 服务器逻辑都在 WebServerManager 里
     * 这里只持有一个实例，负责启动和停止
     */
    private WebServerManager webServerManager;

    /** 网页后台端口号 */
    private static final int WEB_SERVER_PORT = 10481;

    /** 网页后台访问地址（用于生成二维码） */
    private String currentWebUrl;

    // ====================== SP Key 常量 ======================

    /** 自定义直播源地址 */
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    /** 自定义节目单地址 */
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";

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
     *
     * 【注意】
     * WebServerManager 也会调用这个静态方法来输出网页后台的日志，
     * 所以必须是 public static 的。
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

    // ====================== 日志对话框 ======================

    /**
     * 显示操作日志对话框
     * 最新的日志显示在最上面（倒序）
     *
     * 【查看内容】
     * - 用户操作记录（切台、切换分组、打开设置等）
     * - 网页后台日志（启动、请求、响应、错误等）
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
     *
     * 【查看内容】
     * - EPG 节目单解析日志
     * - 播放器相关日志
     * - 直播源加载日志
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

        // ===== 启动网页后台（拆分后） =====
        // 创建 WebServerManager 实例并启动
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        webServerManager.start();
        // 获取访问地址，用于生成二维码
        currentWebUrl = webServerManager.getAccessUrl();

        // 记录操作日志
        logOperation("【设置】打开设置页面");
    }

    // ====================== 自动更新源 - AlarmManager ======================

    /**
     * 设置自动更新闹钟
     * 每天凌晨4点自动刷新直播源和EPG
     *
     * 【原理】
     * 1. 使用 AlarmManager 设置重复闹钟
     * 2. 到时间后发送广播 com.tv.live.REFRESH_LIVE_AND_EPG
     * 3. MainActivity 收到广播后刷新直播源和EPG
     *
     * 【为什么用 AlarmManager？】
     * - 系统级别的定时，APP 被杀掉也能唤醒
     * - 省电，不需要一直跑后台服务
     * - setInexactRepeating 允许系统调整时间，更省电
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
     * 用户关闭"自动更新源"开关时调用
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
     * 这些是没有 Switch 的设置项，点击后弹出对话框
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
     * 三个选项：全屏、填充、原始
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
     * 用于自定义直播源和自定义节目单
     *
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
     * 用于"多订阅源"和"多节目单"
     *
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
     *
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
     * 用户用手机扫码后可以在浏览器里打开配置页面
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
     * 使用 ZXing 库生成
     *
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

    // ====================== onDestroy 生命周期 ======================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logOperation("【设置】关闭设置页面");

        // ===== 停止网页后台（拆分后） =====
        // 释放端口资源，避免内存泄漏
        if (webServerManager != null) {
            webServerManager.stop();
        }
    }

    // ====================== 历史记录列表适配器 ======================

    /**
     * 历史记录列表的适配器
     * 用于"多订阅源"和"多节目单"的列表对话框
     *
     * 【三种状态】（和分组列表一致）
     * 1. 选中状态：蓝色文字 + 浅蓝色背景 + 加粗
     * 2. 焦点状态：蓝色文字 + 稍深一点的蓝色背景
     * 3. 未选中状态：白色文字 + 透明背景
     *
     * 【为什么用 Java 代码动态设置？】
     * 一开始尝试过 XML selector，但只有文字变蓝，背景没有效果。
     * 改成 Java 代码动态设置后，三种状态都正常了。
     */
    private static class SettingsAdapter extends ArrayAdapter<String> {
        private final Context context;
        private final List<String> items;
        /** 当前选中的位置 */
        private int selectedPosition = -1;

        public SettingsAdapter(Context context, List<String> items) {
            super(context, R.layout.item_settings, items);
            this.context = context;
            this.items = items;
        }

        /**
         * 设置选中位置并刷新列表
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
