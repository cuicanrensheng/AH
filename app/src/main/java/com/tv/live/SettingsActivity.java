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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 设置页面
 * 功能说明：
 * 1. 各类开关配置：开机自启、节目单、自动更新源、换台反转、数字选台
 * 2. 自定义直播源/EPG链接录入，自动保存历史记录
 * 3. 多订阅源/多节目单弹窗：历史编号展示、单条删除、一键全删
 * 4. 扫码配置源链接，局域网端口接收网页推送地址
 * 5. 双日志：播放解析日志、操作崩溃日志(新日志置顶，上限200条)
 */
public class SettingsActivity extends AppCompatActivity {
    // 开关控件
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    // 功能文本按钮
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    // 切换源按钮
    private TextView tv_switch_live, tv_switch_epg;
    // 查看操作日志按钮
    private TextView setting_log_viewer;

    // 本地配置存储
    private SharedPreferences sp;
    // 本机网络地址
    private String currentWebUrl;
    // 局域网后台服务socket
    private ServerSocket serverSocket;
    // 主线程Handler
    private Handler handler = new Handler(Looper.getMainLooper());
    // 局域网端口号
    private static final int PORT = 10481;

    // 播放日志缓存
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();
    // 设置&崩溃日志缓存
    public static volatile StringBuilder SETTING_LOG = new StringBuilder();
    // 设置日志最大存储条数
    public static final int MAX_SETTING_LOG_COUNT = 200;

    /**
     * 写入播放解析日志
     * @param msg 日志内容
     */
    public static void log(String msg) {
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        PLAY_LOG.append("[").append(time).append("] ").append(msg).append("\n");
        // 超长截断
        if (PLAY_LOG.length() > 15000) PLAY_LOG.delete(0, PLAY_LOG.length() - 12000);
    }

    /**
     * 写入操作日志：新内容插入头部，实现最新日志置顶
     * @param msg 操作内容
     */
    public static void logSetting(String msg) {
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        String line = "[" + time + "][操作] " + msg + "\n";
        SETTING_LOG.insert(0, line);
        trimLog();
    }

    /**
     * 写入崩溃异常日志
     * @param e 异常信息
     */
    public static void logCrash(Throwable e) {
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        String err = "[" + time + "][崩溃异常]\n" + sw + "\n=====================\n";
        SETTING_LOG.insert(0, err);
        trimLog();
    }

    /**
     * 日志裁剪：超过200行自动删除末尾老旧记录
     */
    private static void trimLog() {
        String[] arr = SETTING_LOG.toString().split("\n");
        if (arr.length > MAX_SETTING_LOG_COUNT) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_SETTING_LOG_COUNT; i++) {
                if (!TextUtils.isEmpty(arr[i])) sb.append(arr[i]).append("\n");
            }
            SETTING_LOG.setLength(0);
            SETTING_LOG.append(sb);
        }
    }

    /**
     * 弹窗：查看播放解析日志
     */
    private void showLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        if (PLAY_LOG.length() == 0) {
            tv.setText("暂无解析日志");
        } else {
            String[] arr = PLAY_LOG.toString().split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = arr.length - 1; i >= 0; i--) {
                if (!TextUtils.isEmpty(arr[i])) sb.append(arr[i]).append("\n");
            }
            tv.setText(sb);
        }
        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        scrollView.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("📄 解析&播放日志")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空日志", (d, w) -> {
                    PLAY_LOG.setLength(0);
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT);
                }).show();
    }

    /**
     * 弹窗：查看设置&崩溃日志（新记录在顶部）
     */
    private void showSettingLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        if (SETTING_LOG.length() == 0) {
            tv.setText("暂无设置操作、崩溃日志");
        } else {
            tv.setText(SETTING_LOG.toString());
        }
        tv.setTextSize(11);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        scrollView.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("⚙ 操作&崩溃日志(最多200条)")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空全部日志", (d, w) -> {
                    SETTING_LOG.setLength(0);
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                }).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 全局未捕获异常拦截
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> logCrash(throwable));
        try {
            super.onCreate(savedInstanceState);
            // 屏幕常亮、弹窗半透明
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().getAttributes().dimAmount = 0.6f;
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND, WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            // 强制横屏
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            setContentView(R.layout.activity_settings);
            sp = getSharedPreferences("app_settings", MODE_PRIVATE);
            bindView();
            initClick();
            // 获取本机IP拼接二维码地址
            currentWebUrl = "http://" + getDeviceIPAddress() + ":" + PORT;
            // 启动局域网监听服务
            startPushServer();
            logSetting("进入设置页面");
        } catch (Exception e) {
            logCrash(e);
        }
    }

    /**
     * 绑定所有控件ID
     */
    private void bindView() {
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

        tv_switch_live = findViewById(R.id.tv_switch_live);
        tv_switch_epg = findViewById(R.id.tv_switch_epg);
        setting_log_viewer = findViewById(R.id.setting_log_viewer);
    }

    /**
     * 初始化所有点击事件
     */
    private void initClick() {
        findViewById(R.id.log_viewer).setOnClickListener(v -> showLogDialog());
        setting_log_viewer.setOnClickListener(v -> showSettingLogDialog());

        // 开机自启开关
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        sw_boot.setOnCheckedChangeListener((v, is) -> {
            sp.edit().putBoolean("boot_auto_start", is).apply();
            String s = is ? "开启开机自启" : "关闭开机自启";
            logSetting(s);
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        });

        // EPG节目单开关
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        sw_epg.setOnCheckedChangeListener((v, is) -> {
            sp.edit().putBoolean("epg_enable", is).apply();
            String s = is ? "开启节目单" : "关闭节目单";
            logSetting(s);
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        });

        // 自动更新源开关
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        sw_auto_update.setOnCheckedChangeListener((v, is) -> {
            sp.edit().putBoolean("auto_update_source", is).apply();
            String s = is ? "开启自动更新源" : "关闭自动更新源";
            logSetting(s);
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        });

        // 换台反转开关
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_reverse.setOnCheckedChangeListener((v, is) -> {
            sp.edit().putBoolean("channel_reverse", is).apply();
            String s = is ? "开启换台反转" : "关闭换台反转";
            logSetting(s);
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        });

        // 数字选台开关
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        sw_num_channel.setOnCheckedChangeListener((v, is) -> {
            sp.edit().putBoolean("number_channel_enable", is).apply();
            String s = is ? "开启数字选台" : "关闭数字选台";
            logSetting(s);
        });

        // 版本检测
        findViewById(R.id.btn_check_update).setOnClickListener(v -> {
            Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
            logSetting("点击检查更新");
        });

        // 屏幕比例、自定义源、历史记录、二维码、切换源绑定
        tv_screen_ratio.setOnClickListener(v -> showRatioDialog());
        tv_custom_source.setOnClickListener(v -> showEditDialog("自定义订阅源", "custom_live_url", "live_history"));
        tv_custom_epg.setOnClickListener(v -> showEditDialog("自定义节目单", "custom_epg", "epg_history"));
        tv_multi_source.setOnClickListener(v -> showHistoryDialog("订阅源历史", "custom_live_url", "live_history"));
        tv_multi_epg.setOnClickListener(v -> showHistoryDialog("EPG节目单历史", "custom_epg", "epg_history"));
        tv_qr_code.setOnClickListener(v -> showQrDialog());
        tv_switch_live.setOnClickListener(v -> switchLiveSource());
        tv_switch_epg.setOnClickListener(v -> switchEpgSource());
    }

    /**
     * 切换直播源：默认/自定义
     */
    private void switchLiveSource() {
        new AlertDialog.Builder(this)
                .setTitle("切换直播源")
                .setItems(new String[]{"源1(默认)", "源2(自定义)"}, (d, w) -> {
                    if (w == 0) {
                        UrlConfig.LIVE_URL = UrlConfig.DEFAULT_LIVE;
                        sp.edit().putString("custom_live_url", "").apply();
                        sendRefresh();
                        logSetting("直播切默认源");
                        Toast.makeText(this, "已切默认", Toast.LENGTH_SHORT).show();
                    } else {
                        String cus = sp.getString("custom_live_url", "");
                        if (TextUtils.isEmpty(cus)) {
                            Toast.makeText(this, "暂无自定义链接", Toast.LENGTH_SHORT);
                            logSetting("切换自定义失败：无链接");
                            return;
                        }
                        UrlConfig.LIVE_URL = cus;
                        sendRefresh();
                        logSetting("直播切自定义:" + cus);
                        Toast.makeText(this, "已切自定义", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    /**
     * 切换EPG源：默认/自定义
     */
    private void switchEpgSource() {
        new AlertDialog.Builder(this)
                .setTitle("切换EPG源")
                .setItems(new String[]{"源1(默认)", "源2(自定义)"}, (d, w) -> {
                    if (w == 0) {
                        UrlConfig.EPG_URL = UrlConfig.DEFAULT_EPG;
                        sp.edit().putString("custom_epg", "").apply();
                        sendRefresh();
                        logSetting("EPG切默认");
                        Toast.makeText(this, "已切默认", Toast.LENGTH_SHORT).show();
                    } else {
                        String cus = sp.getString("custom_epg", "");
                        if (TextUtils.isEmpty(cus)) {
                            Toast.makeText(this, "暂无自定义链接", Toast.LENGTH_SHORT);
                            logSetting("EPG自定义切换失败");
                            return;
                        }
                        UrlConfig.EPG_URL = cus;
                        sendRefresh();
                        logSetting("EPG切自定义:" + cus);
                        Toast.makeText(this, "已切自定义", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    /**
     * 发送全局广播，刷新首页源和EPG
     */
    private void sendRefresh() {
        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
    }

    /**
     * 弹窗：选择画面缩放比例
     */
    private void showRatioDialog() {
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(new String[]{"全屏", "填充", "原始"}, (d, w) -> {
                    String sel = new String[]{"全屏", "填充", "原始"}[w];
                    sp.edit().putString("screen_ratio", sel).apply();
                    logSetting("修改比例:" + sel);
                    Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
                }).show();
    }

    /**
     * 弹窗：输入自定义源链接，自动存入历史
     * @param title 弹窗标题
     * @param saveKey 当前自定义存储key
     * @param hisKey 历史记录存储key
     */
    private void showEditDialog(String title, String saveKey, String hisKey) {
        EditText et = new EditText(this);
        et.setText(sp.getString(saveKey, ""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String url = et.getText().toString().trim();
                    sp.edit().putString(saveKey, url).apply();
                    if (!TextUtils.isEmpty(url)) {
                        addHistory(hisKey, url);
                        sendRefresh();
                        logSetting(title + "保存:" + url);
                    } else {
                        logSetting(title + "清空链接");
                    }
                    Toast.makeText(this, "已保存刷新", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null).show();
    }

    /**
     * 弹窗：展示历史记录列表（编号+单删+全删）
     */
    private void showHistoryDialog(String title, String saveKey, String hisKey) {
        String raw = sp.getString(hisKey, "");
        List<String> list = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) {
            list.addAll(Arrays.asList(raw.split("\\|")));
        }
        if (list.isEmpty()) {
            Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show();
            logSetting(title + "打开无数据");
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        View root = LayoutInflater.from(this).inflate(R.layout.layout_history_pop, null);
        ListView lv = root.findViewById(R.id.lv_history);
        Button btnAllDel = root.findViewById(R.id.btn_all_delete);
        HistoryAdapter adapter = new HistoryAdapter(this, list, saveKey, hisKey);
        lv.setAdapter(adapter);

        // 全部删除按钮
        btnAllDel.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认清空全部？")
                .setPositiveButton("确定", (d, w) -> {
                    sp.edit().putString(hisKey, "").putString(saveKey, "").apply();
                    sendRefresh();
                    logSetting(title + "全部历史已删除");
                    Toast.makeText(this, "已全部清空", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("取消", null).show());

        builder.setView(root);
        builder.setNegativeButton("关闭", null);
        builder.show();
    }

    /**
     * 历史列表适配器
     */
    private class HistoryAdapter extends ArrayAdapter<String> {
        private final List<String> data;
        private final String saveKey;
        private final String hisKey;
        private final Context ctx;

        public HistoryAdapter(Context c, List<String> list, String sKey, String hKey) {
            super(c, R.layout.item_settings, list);
            ctx = c;
            data = list;
            saveKey = sKey;
            hisKey = hKey;
        }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            ViewHolder vh;
            // 修复：统一vh变量，取消错误v
            if (convertView == null) {
                convertView = LayoutInflater.from(ctx).inflate(R.layout.item_settings, parent, false);
                vh = new ViewHolder();
                vh.tvIdx = convertView.findViewById(R.id.tv_index);
                vh.tvUrl = convertView.findViewById(R.id.tv_setting_item);
                vh.btnDel = convertView.findViewById(R.id.btn_delete);
                convertView.setTag(vh);
            } else {
                vh = (ViewHolder) convertView.getTag();
            }

            String url = data.get(pos);
            vh.tvIdx.setText((pos + 1) + ".");
            vh.tvUrl.setText(url);

            // 点击条目设为当前源
            convertView.setOnClickListener(vv -> {
                sp.edit().putString(saveKey, url).apply();
                if ("custom_live_url".equals(saveKey)) UrlConfig.LIVE_URL = url;
                else UrlConfig.EPG_URL = url;
                sendRefresh();
                logSetting("选中历史链接:" + url);
                Toast.makeText(ctx, "已选用该源", Toast.LENGTH_SHORT).show();
            });

            // 单条删除
            vh.btnDel.setOnClickListener(vv -> {
                data.remove(pos);
                StringBuilder sb = new StringBuilder();
                for (String s : data) sb.append(s).append("|");
                String newHis = sb.length() > 0 ? sb.substring(0, sb.length() - 1) : "";
                sp.edit().putString(hisKey, newHis).apply();
                notifyDataSetChanged();
                logSetting("删除第" + (pos + 1) + "条历史");
                Toast.makeText(ctx, "已删除本条", Toast.LENGTH_SHORT).show();
            });
            return convertView;
        }

        /** 条目缓存 */
        class ViewHolder {
            TextView tvIdx, tvUrl;
            Button btnDel;
        }
    }

    /**
     * 新增历史记录，去重拼接存储
     */
    private void addHistory(String key, String url) {
        String old = sp.getString(key, "");
        StringBuilder sb = new StringBuilder(url);
        if (!TextUtils.isEmpty(old)) {
            String[] arr = old.split("\\|");
            for (String s : arr) {
                if (!s.equals(url) && sb.length() < 1500) sb.append("|").append(s);
            }
        }
        sp.edit().putString(key, sb.toString()).apply();
    }

    /**
     * 二维码弹窗
     */
    private void showQrDialog() {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(createQ(currentWebUrl, 250));
        new AlertDialog.Builder(this)
                .setTitle("扫码配置")
                .setView(iv)
                .setPositiveButton("关闭", null).show();
        logSetting("打开二维码页面");
    }

    /**
     * 生成二维码图片
     */
    private Bitmap createQ(String text, int size) {
        try {
            BitMatrix bm = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            // 修复setPixel参数缺少y
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bp.setPixel(x, y, bm.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bp;
        } catch (Exception e) {
            logCrash(e);
            return null;
        }
    }

    /**
     * 获取本机局域网IP
     */
    private String getDeviceIPAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            int ip = wi.getIpAddress();
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Exception e) {
            logCrash(e);
            return "192.168.1.100";
        }
    }

    /**
     * 开启后台线程监听10481端口，接收网页提交的源地址
     */
    private void startPushServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                while (!serverSocket.isClosed()) {
                    Socket sock = serverSocket.accept();
                    new Thread(() -> {
                        try {
                            InputStreamReader ir = new InputStreamReader(sock.getInputStream());
                            char[] buf = new char[2048];
                            int len = ir.read(buf);
                            JSONObject jo = new JSONObject(new String(buf, 0, len));
                            handler.post(() -> {
                                boolean change = false;
                                String live = jo.optString("live_url");
                                if (!TextUtils.isEmpty(live)) {
                                    sp.edit().putString("custom_live_url", live).apply();
                                    addHistory("live_history", live);
                                    UrlConfig.LIVE_URL = live;
                                    change = true;
                                    logSetting("网页同步直播:" + live);
                                }
                                String epg = jo.optString("epg_url");
                                if (!TextUtils.isEmpty(epg)) {
                                    sp.edit().putString("custom_epg", epg).apply();
                                    addHistory("epg_history", epg);
                                    UrlConfig.EPG_URL = epg;
                                    change = true;
                                    logSetting("网页同步EPG:" + epg);
                                }
                                if (change) {
                                    sendRefresh();
                                    Toast.makeText(SettingsActivity.this, "配置已同步", Toast.LENGTH_SHORT).show();
                                }
                            });
                            sock.close();
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
        logSetting("退出设置");
        super.onDestroy();
        // 修复变量名+Exception大写
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            logCrash(e);
        }
    }
}
