package com.tv.live;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.tv.live.manager.TvRemoteManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页面 Activity
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private SwitchCompat sw_boot, sw_reverse, sw_pip;
    private TextView tv_screen_ratio, tv_decoder_mode, tv_renderer_type, tv_redirect_setting, tv_boot_status;
    private TextView tv_channel_line;
    private TextView tv_resolution_status;
    private View itemResolution;
    
    private View itemLog;
    private TextView tv_log_status;

    private View itemVersionInfo;
    private TextView tv_version_short;
    
    private LinearLayout itemLiveSubscribe, itemEpgSubscribe;
    
    private SharedPreferences sp;
    private TvRemoteManager remoteManager;
    private List<View> settingsItemList = new ArrayList<>();
    private ScrollView scrollView;
    
    private BootStartManager bootStartManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;
    private UpdateManager updateManager;
    
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";
    private static final String KEY_USER_AGENT_MODE = "user_agent_mode";
    private static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable focusUpdateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try { applyFullScreen(); } catch (Exception e) { }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception e) { }
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Exception e) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> finish());
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        initRedirectDefaultConfig();

        sp.edit().putBoolean("epg_enable", true).apply();
        sp.edit().putBoolean("number_channel_enable", true).apply();

        sw_boot = findViewById(R.id.sw_boot);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_pip = findViewById(R.id.sw_pip);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_renderer_type = findViewById(R.id.tv_renderer_type);
        tv_redirect_setting = findViewById(R.id.tv_redirect_setting);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        scrollView = findViewById(R.id.settings_content);
        
        itemResolution = findViewById(R.id.item_resolution);
        tv_resolution_status = findViewById(R.id.tv_resolution_status);

        itemLog = findViewById(R.id.item_log);
        tv_log_status = findViewById(R.id.tv_log_status);

        itemVersionInfo = findViewById(R.id.item_version_info);
        tv_version_short = findViewById(R.id.tv_version_short);
        
        bootStartManager = new BootStartManager(this, sp);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);
        
        itemLiveSubscribe = findViewById(R.id.item_live_subscribe);
        itemEpgSubscribe = findViewById(R.id.item_epg_subscribe);

        initSettingsItemList();
        initRemoteManager();

        tv_channel_line = findViewById(R.id.tv_channel_line);
        int currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        tv_channel_line.setText(getLineName(currentLineIndex));
        findViewById(R.id.item_channel_line).setOnClickListener(v -> showChannelLineDialog());

        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        bootStartManager.updateBootStatusText(tv_boot_status);
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            boolean isChecked = !sw_boot.isChecked();
            sw_boot.setChecked(isChecked);
            bootStartManager.toggleBoot(isChecked, tv_boot_status);
        });
        findViewById(R.id.item_boot).setOnLongClickListener(v -> {
            bootStartManager.showBootStatusDialog();
            return true;
        });

        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            boolean isChecked = !sw_pip.isChecked();
            sw_pip.setChecked(isChecked);
            sp.edit().putBoolean("pip_enable", isChecked).apply();
            if (isChecked) {
                Toast.makeText(this, "画中画已开启，按Home键自动小窗播放", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "画中画已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        findViewById(R.id.item_decoder).setOnClickListener(v -> showDecoderModeDialog());
        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        findViewById(R.id.item_renderer).setOnClickListener(v -> showRendererModeDialog());
        updateRedirectSettingText();
        findViewById(R.id.item_redirect).setOnClickListener(v -> showRedirectConfigDialog());
        findViewById(R.id.item_check_update).setOnClickListener(v -> updateManager.checkUpdate());
        
        itemResolution.setOnClickListener(v -> showResolutionDialog());

        itemVersionInfo.setOnClickListener(v -> showVersionInfoDialog());
        
        initListeners();
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();

        SourceManager liveManager = new SourceManager(this, "live_history");
        if (liveManager.size() == 0) {
            liveManager.addSource("默认直播源", UrlConfig.LIVE_URL);
        }
        SourceManager epgManager = new SourceManager(this, "epg_history");
        if (epgManager.size() == 0) {
            epgManager.addSource("默认节目单", UrlConfig.EPG_URL);
        }
    }

    // 🟢 显示版本信息弹窗
    private void showVersionInfoDialog() {
        String versionName = BuildConfig.VERSION_NAME;
        int versionCode = BuildConfig.VERSION_CODE;
        String updateNotes = updateManager.getUpdateMessage();
        String userAgent = sp.getString("custom_user_agent", "");
        if (TextUtils.isEmpty(userAgent)) {
            String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
            if ("vlc".equals(uaMode)) {
                userAgent = "VLC/3.0.21 LibVLC/3.0.21";
            } else {
                userAgent = "ExoPlayer";
            }
        }
        String sdkVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        String playerVersion = "androidx.media3 1.7.1";

        String message = "版本信息: v" + versionName + " (" + versionCode + ")\n\n" +
                         "更新内容: \n" + updateNotes + "\n\n" +
                         "UA: " + userAgent + "\n\n" +
                         "SDK 版本: " + sdkVersion + "\n\n" +
                         "播放器版本: " + playerVersion;

        SpannableString spannableString = new SpannableString(message);
        spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        int startUc = message.indexOf("更新内容:");
        if (startUc != -1) {
            spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), startUc, startUc + 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        new AlertDialog.Builder(this)
                .setTitle("📱 应用详情")
                .setMessage(spannableString)
                .setPositiveButton("确定", null)
                .show();
    }

    private String getLineName(int index) {
        if (index == 0) return "主源";
        return "源" + index;
    }

    private void showDarkSingleChoiceDialog(String title, String[] items, int checkedItem, java.util.function.Consumer<Integer> onSelected) {
        ListView listView = new ListView(this);
        listView.setBackgroundColor(0xFF272B3A);
        listView.setDivider(new ColorDrawable(0x33FFFFFF));
        listView.setDividerHeight(1);
        listView.setPadding(0, 16, 0, 16);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                return view;
            }
        };
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(checkedItem, true);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            onSelected.accept(position);
        });

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setPadding(24, 24, 24, 0);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF272B3A);
        layout.addView(titleView);
        layout.addView(listView);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }

    private void showChannelLineDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(this);
        Channel currentChannel = playerManager.getCurrentChannel();
        if (currentChannel == null) {
            Toast.makeText(this, "请先播放一个频道，再切换线路", Toast.LENGTH_SHORT).show();
            return;
        }
        int currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        List<String> lineList = new ArrayList<>();
        lineList.add("主源");
        for (int i = 1; i <= currentChannel.getBackupUrls().size(); i++) {
            lineList.add("源" + i);
        }
        String[] lineArray = lineList.toArray(new String[0]);

        showDarkSingleChoiceDialog("频道线路选择", lineArray, currentLineIndex, (which) -> {
            sp.edit().putInt(KEY_CHANNEL_LINE_INDEX, which).apply();
            tv_channel_line.setText(lineArray[which]);
            sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
            Toast.makeText(this, "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
        });
    }

    private void initRedirectDefaultConfig() {
        if (!sp.contains(KEY_REDIRECT_MAX_COUNT)) {
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(KEY_REDIRECT_MAX_COUNT,5);
            editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
            editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
            editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
            editor.putBoolean(KEY_REDIRECT_IGNORE_SSL,false);
            editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, true);
            editor.putString(KEY_USER_AGENT_MODE, "exo");
            editor.apply();
        }
    }

    private void updateRedirectSettingText() {
        int max = sp.getInt(KEY_REDIRECT_MAX_COUNT,5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL,false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
        String uaLabel = "exo".equals(uaMode) ? "ExoPlayer" : "VLC";
        StringBuilder sb = new StringBuilder();
        sb.append("最大跳转：").append(max).append(" | ");
        sb.append("跨域：").append(crossDomain?"开":"关").append(" | ");
        sb.append("跨协议：").append(crossProto?"开":"关").append("\n");
        sb.append("携带请求头：").append(followHeader?"开":"关").append(" | ");
        sb.append("忽略SSL：").append(ignoreSsl?"开":"关").append(" | ");
        sb.append("授权令牌：").append(sendCookie?"开":"关").append(" | ");
        sb.append("UA：").append(uaLabel);
        tv_redirect_setting.setText(sb.toString());
    }

    private void applyFullScreen() {
        try {
            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        } catch (Exception e) {
        }
    }

    private android.util.SparseArray<TextView> itemTextViews = new android.util.SparseArray<>();

    private void initSettingsItemList() {
        settingsItemList.clear();
        itemTextViews.clear();

        settingsItemList.add(findViewById(R.id.item_boot));
        settingsItemList.add(findViewById(R.id.item_reverse));
        settingsItemList.add(findViewById(R.id.item_pip));
        settingsItemList.add(findViewById(R.id.item_channel_line));
        settingsItemList.add(findViewById(R.id.item_decoder));
        settingsItemList.add(findViewById(R.id.item_renderer));
        settingsItemList.add(findViewById(R.id.tv_screen_ratio));
        settingsItemList.add(itemResolution);
        settingsItemList.add(findViewById(R.id.item_redirect));
        settingsItemList.add(itemLiveSubscribe);
        settingsItemList.add(itemEpgSubscribe);
        settingsItemList.add(findViewById(R.id.item_check_update));
        settingsItemList.add(itemLog);
        settingsItemList.add(itemVersionInfo);

        for (int i = settingsItemList.size() - 1; i >= 0; i--) {
            if (settingsItemList.get(i) == null) {
                settingsItemList.remove(i);
            }
        }

        for (int i = 0; i < settingsItemList.size(); i++) {
            View view = settingsItemList.get(i);
            if (view instanceof TextView) {
                itemTextViews.put(i, (TextView) view);
            } else if (view instanceof ViewGroup) {
                TextView tv = findFirstTextView((ViewGroup) view);
                if (tv != null) {
                    itemTextViews.put(i, tv);
                }
            }
        }

        for (int i = 0; i < settingsItemList.size(); i++) {
            final int position = i;
            View item = settingsItemList.get(i);
            if (item != null) {
                item.setFocusableInTouchMode(true);
                item.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && remoteManager != null) {
                        int currentPos = remoteManager.getSettingsFocusPosition();
                        if (currentPos != position) {
                            remoteManager.setSettingsFocusPosition(position);
                            updateSettingsFocus();
                        }
                    }
                });
            }
        }
    }

    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.SETTINGS_MODE);
        remoteManager.setSettingsItemCount(settingsItemList.size());
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            @Override public void onPlayChannelUp() {}
            @Override public void onPlayChannelDown() {}
            @Override public void onPlayTogglePanel() {}
            @Override public void onPlayOpenSettings() {}
            @Override public boolean onPlayBack() { return false; }
            @Override public void onPanelMoveUp() {}
            @Override public void onPanelMoveDown() {}
            @Override public void onPanelMoveLeft() {}
            @Override public void onPanelMoveRight() {}
            @Override public void onPanelConfirm() {}
            @Override public boolean onPanelBack() { return false; }
            @Override public void onPanelMenu() {}
            @Override public void onPanelNumber(int number) {}
            @Override public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {}
            @Override public void onSettingsMoveUp() { updateSettingsFocus(); }
            @Override public void onSettingsMoveDown() { updateSettingsFocus(); }
            @Override public void onSettingsConfirm() { int position = remoteManager.getSettingsFocusPosition(); handleSettingsItemClick(position); }
            @Override public boolean onSettingsBack() { finish(); return true; }
            @Override public void onSettingsMenu() { finish(); }
            @Override public void onSettingsFocusChanged(int position) { updateSettingsFocus(); }
            @Override public boolean onPipBack() { return false; }
            @Override public void onRequestPlayFocus() {}
            @Override public void onChannelNumberSelected(int channelIndex) {}
            @Override public void onShowChannelNumber(String number) {}
            @Override public void onHideChannelNumber() {}
        });
        updateSettingsFocus();
    }

    private void initListeners() {
        tv_screen_ratio.setOnClickListener(v -> showRatioDialog());
        itemLiveSubscribe.setOnClickListener(v -> showSubscriptionDialog("live_history", "直播源订阅"));
        itemEpgSubscribe.setOnClickListener(v -> showSubscriptionDialog("epg_history", "节目单订阅"));
        
        itemLog.setOnClickListener(v -> {
            boolean logEnabled = sp.getBoolean("log_enable", false);
            boolean newState = !logEnabled;
            sp.edit().putBoolean("log_enable", newState).apply();
            tv_log_status.setText(newState ? "开启" : "关闭");
            Toast.makeText(SettingsActivity.this, "日志已" + (newState ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();

            MainActivity.toggleLogWindow(newState);
        });

        itemVersionInfo.setOnClickListener(v -> showVersionInfoDialog());
    }

    private void showResolutionDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(this);
        if (playerManager == null) return;
        List<String> resolutions = playerManager.getAvailableResolutions();
        if (resolutions.isEmpty()) {
            Toast.makeText(this, "当前直播源不支持清晰度切换", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String[] items = resolutions.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("选择清晰度")
                .setItems(items, (dialog, which) -> {
                    String selectedLabel = items[which];
                    int targetHeight = 0;
                    if (selectedLabel.contains("4K")) targetHeight = 2160;
                    else if (selectedLabel.contains("1080p")) targetHeight = 1080;
                    else if (selectedLabel.contains("720p")) targetHeight = 720;
                    else {
                        try {
                            targetHeight = Integer.parseInt(selectedLabel.replace("p", ""));
                        } catch (Exception ignored) {}
                    }

                    if (targetHeight > 0) {
                        playerManager.switchToResolution(targetHeight);
                        tv_resolution_status.setText(selectedLabel);
                        Toast.makeText(SettingsActivity.this, "已切换至: " + selectedLabel, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSubscriptionDialog(String spKey, String title) {
        SourceManager sourceManager = new SourceManager(this, spKey);
        List<SourceManager.SourceItem> sources = sourceManager.getAllSources();

        // 🟢【修复1】使用正确的 LayoutInflater 上下文，彻底解决 layout_width 缺少的 InflateException
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(
                new android.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
        );
        View dialogView = inflater.inflate(R.layout.dialog_subscription, null);

        ListView lvSourceList = dialogView.findViewById(R.id.lv_source_list);
        ImageView ivQrCode = dialogView.findViewById(R.id.iv_qr_code);
        TextView tvIpAddress = dialogView.findViewById(R.id.tv_ip_address);
        TextView tvDialogTitle = dialogView.findViewById(R.id.tv_dialog_title);
        LinearLayout llScanHeader = dialogView.findViewById(R.id.ll_scan_header);
        EditText etName = dialogView.findViewById(R.id.et_name);
        EditText etUrl = dialogView.findViewById(R.id.et_url);
        Button btnClear = dialogView.findViewById(R.id.btn_clear);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);
        Button btnClose = dialogView.findViewById(R.id.btn_close);

        boolean isLive = "live_history".equals(spKey);
        tvIpAddress.setText(currentWebUrl);

        if (isLive) {
            if (tvDialogTitle != null) tvDialogTitle.setText(title);
            if (llScanHeader != null) llScanHeader.setVisibility(View.VISIBLE);
            if (ivQrCode != null) ivQrCode.setVisibility(View.VISIBLE);
            
            new Thread(() -> {
                Bitmap qrBitmap = null;
                try {
                    qrBitmap = qrCodeManager.createQR(currentWebUrl, 240);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                final Bitmap finalQrBitmap = qrBitmap;
                runOnUiThread(() -> {
                    if (finalQrBitmap != null) {
                        ivQrCode.setImageBitmap(finalQrBitmap);
                    } else {
                        ivQrCode.setBackgroundColor(Color.LTGRAY);
                    }
                });
            }).start();

            ivQrCode.setOnClickListener(v -> {
                Toast.makeText(SettingsActivity.this, "已生成二维码，请扫码", Toast.LENGTH_SHORT).show();
            });
            etName.setHint("请输入名称(选填)");
            etUrl.setHint("请输入地址");
        } else {
            if (tvDialogTitle != null) tvDialogTitle.setText(title);
            if (llScanHeader != null) llScanHeader.setVisibility(View.GONE);
            if (ivQrCode != null) ivQrCode.setVisibility(View.GONE);
            etName.setHint("请输入节目单名称(选填)");
            etUrl.setHint("请输入EPG节目单地址");
        }

        int currentDefault = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
        SubscriptionAdapter adapter = new SubscriptionAdapter(this, sources);
        adapter.setSelectedPosition(currentDefault);

        adapter.setOnActionListener(new SubscriptionAdapter.OnActionListener() {
            @Override
            public void onSwitch(int position) {
                sourceManager.setDefault(position);
                sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                Toast.makeText(SettingsActivity.this, "已切换到：" + sources.get(position).name, Toast.LENGTH_SHORT).show();
                adapter.setSelectedPosition(position);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onDelete(int position) {
                // 🟢【修复2】添加边界和 index 安全检查，彻底解决 ArrayIndexOutOfBoundsException 闪退
                if (position < 0 || position >= sources.size()) {
                    return;
                }
                SourceManager.SourceItem item = sources.get(position);
                
                AlertDialog deleteDialog = new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("确认删除")
                        .setMessage("确定要删除「" + item.name + "」吗？")
                        .setPositiveButton("删除", (d, w) -> {
                            // 先找真实索引，防止 indexOfUrl 返回 -1 导致崩溃
                            int realIndex = sourceManager.indexOfUrl(item.url);
                            if (realIndex >= 0 && realIndex < sourceManager.size()) {
                                sourceManager.removeSource(realIndex);
                                sources.clear();
                                sources.addAll(sourceManager.getAllSources());
                                adapter.setSelectedPosition(sourceManager.indexOfUrl(sourceManager.getDefaultUrl()));
                                adapter.notifyDataSetChanged();
                                Toast.makeText(SettingsActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(SettingsActivity.this, "删除失败，源未找到", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .create();
                        
                if (deleteDialog.getWindow() != null) {
                    deleteDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                }
                deleteDialog.show();
                
                deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE).setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF55576A));
            }
        });

        lvSourceList.setAdapter(adapter);

        btnConfirm.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String url = etUrl.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (sourceManager.addSource(name, url)) {
                etName.setText("");
                etUrl.setText("");
                sources.clear();
                sources.addAll(sourceManager.getAllSources());
                adapter.setSelectedPosition(sourceManager.indexOfUrl(sourceManager.getDefaultUrl()));
                adapter.notifyDataSetChanged();
                Toast.makeText(this, "已添加，正在刷新...", Toast.LENGTH_SHORT).show();
                sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
            } else {
                Toast.makeText(this, "该地址已存在", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            etName.setText("");
            etUrl.setText("");
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void updateSettingsFocus() {
        if (remoteManager == null) return;
        int selectedPosition = remoteManager.getSettingsFocusPosition();
        if (selectedPosition < 0 || selectedPosition >= settingsItemList.size()) return;

        View target = settingsItemList.get(selectedPosition);
        if (target == null) return;

        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            if (i == selectedPosition) {
                setItemStyle(item, "#40A9FF", Typeface.BOLD, 0x3340A9FF);
            } else {
                setItemStyle(item, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT);
            }
        }

        if (focusUpdateRunnable != null) {
            mainHandler.removeCallbacks(focusUpdateRunnable);
        }
        focusUpdateRunnable = () -> {
            scrollToView(target);
            target.requestFocus();
        };
        mainHandler.post(focusUpdateRunnable);
    }

    private void setItemStyle(View item, String textColor, int typefaceStyle, int bgColor) {
        item.setBackgroundColor(bgColor);
        if (item instanceof TextView) {
            TextView tv = (TextView) item;
            tv.setTextColor(Color.parseColor(textColor));
            tv.setTypeface(null, typefaceStyle);
        } else {
            int index = settingsItemList.indexOf(item);
            TextView tv = null;
            if (index >= 0 && itemTextViews != null) {
                tv = itemTextViews.get(index);
            }
            if (tv == null) {
                if (item instanceof ViewGroup) {
                    tv = findFirstTextView((ViewGroup) item);
                }
            }
            if (tv != null) {
                tv.setTextColor(Color.parseColor(textColor));
                tv.setTypeface(null, typefaceStyle);
            }
        }
    }

    private TextView findFirstTextView(ViewGroup viewGroup) {
        if (viewGroup == null) return null;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                return (TextView) child;
            } else if (child instanceof ViewGroup) {
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private void scrollToView(View view) {
        if (scrollView == null || view == null) return;
        int viewTop = view.getTop();
        int viewBottom = view.getBottom();
        int scrollViewHeight = scrollView.getHeight();
        int currScroll = scrollView.getScrollY();

        if (viewTop < currScroll) {
            scrollView.scrollTo(0, Math.max(0, viewTop - 50));
        } else if (viewBottom > currScroll + scrollViewHeight) {
            scrollView.scrollTo(0, viewBottom - scrollViewHeight + 50);
        }
    }

    private void handleSettingsItemClick(int position) {
        if (position < 0 || position >= settingsItemList.size()) return;
        View item = settingsItemList.get(position);
        if (item == null) return;
        item.performClick();
    }

    private void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        String currentMode = sp.getString("screen_ratio", "全屏");
        int checkedItem = 0;
        for (int i = 0; i < ratios.length; i++) {
            if (ratios[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }
        showDarkSingleChoiceDialog("屏幕比例", ratios, checkedItem, (which) -> {
            sp.edit().putString("screen_ratio", ratios[which]).apply();
            Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
        });
    }

    private void showDecoderModeDialog() {
        final String[] modes = {"自动（推荐）", "硬解", "软解（兼容性好）", "FFmpeg 软解扩展"};
        final String[] modeValues = {"auto", "hard", "soft", "ffmpeg"};
        String currentMode = sp.getString("decoder_mode", "auto");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }
        showDarkSingleChoiceDialog("解码器选择", modes, checkedItem, (which) -> {
            String selectedMode = modeValues[which];
            sp.edit().putString("decoder_mode", selectedMode).apply();
            updateDecoderModeText(selectedMode);
            sendBroadcast(new Intent("com.tv.live.DECODER_MODE_CHANGED"));
            Toast.makeText(this, "已切换到" + modes[which] + "，正在重新加载…", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateDecoderModeText(String mode) {
        if (tv_decoder_mode == null) return;
        switch (mode) {
            case "hard": tv_decoder_mode.setText("硬解"); break;
            case "soft": tv_decoder_mode.setText("软解"); break;
            case "ffmpeg": tv_decoder_mode.setText("FFmpeg 扩展"); break;
            case "auto": default: tv_decoder_mode.setText("自动"); break;
        }
    }

    private void showRendererModeDialog() {
        final String[] modes = {"SurfaceView（默认）", "TextureView（兼容）"};
        final String[] modeValues = {"surface", "texture"};
        String currentMode = sp.getString("renderer_type", "surface");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }
        showDarkSingleChoiceDialog("渲染方式选择", modes, checkedItem, (which) -> {
            String selectedMode = modeValues[which];
            sp.edit().putString("renderer_type", selectedMode).apply();
            updateRendererModeText(selectedMode);
            sendBroadcast(new Intent("com.tv.live.RENDERER_TYPE_CHANGED"));
            Toast.makeText(this, "已切换到" + modes[which] + "，正在应用……", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateRendererModeText(String mode) {
        if (tv_renderer_type == null) return;
        switch (mode) {
            case "texture": tv_renderer_type.setText("TextureView"); break;
            case "surface": default: tv_renderer_type.setText("SurfaceView"); break;
        }
    }

    private void showRedirectConfigDialog() {
        int currentMax = sp.getInt(KEY_REDIRECT_MAX_COUNT,5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL,false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        final String[] currentUaMode = { sp.getString(KEY_USER_AGENT_MODE, "exo") };
        
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_redirect_config, null);
        EditText etMax = dialogView.findViewById(R.id.et_redirect_max);
        SwitchCompat swCrossDomain = dialogView.findViewById(R.id.sw_cross_domain);
        SwitchCompat swCrossProto = dialogView.findViewById(R.id.sw_cross_proto);
        SwitchCompat swFollowHeader = dialogView.findViewById(R.id.sw_follow_header);
        SwitchCompat swIgnoreSsl = dialogView.findViewById(R.id.sw_ignore_ssl);
        SwitchCompat swSendCookie = dialogView.findViewById(R.id.sw_send_cookie);
        LinearLayout llUserAgent = dialogView.findViewById(R.id.ll_user_agent);
        TextView tvUserAgentStatus = dialogView.findViewById(R.id.tv_user_agent_status);
        Button btnCancel = dialogView.findViewById(R.id.btn_redirect_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_redirect_save);

        tvUserAgentStatus.setText("exo".equals(currentUaMode[0]) ? "ExoPlayer默认" : "VLC播放器");
        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(currentMax));
        swCrossDomain.setChecked(crossDomain);
        swCrossProto.setChecked(crossProto);
        swFollowHeader.setChecked(followHeader);
        swIgnoreSsl.setChecked(ignoreSsl);
        swSendCookie.setChecked(sendCookie);

        llUserAgent.setOnClickListener(v -> {
            final String[] uaOptions = {"ExoPlayer默认", "VLC播放器"};
            final String[] uaValues = {"exo", "vlc"};
            int checkedItem = 0;
            for (int i = 0; i < uaValues.length; i++) {
                if (uaValues[i].equals(currentUaMode[0])) {
                    checkedItem = i;
                    break;
                }
            }
            showDarkSingleChoiceDialog("UA切换", uaOptions, checkedItem, (which) -> {
                currentUaMode[0] = uaValues[which];
                tvUserAgentStatus.setText(uaOptions[which]);
            });
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String maxStr = etMax.getText().toString().trim();
            int newMax = 5;
            if (!TextUtils.isEmpty(maxStr)) {
                try {
                    newMax = Integer.parseInt(maxStr);
                    if(newMax < 1) newMax = 1;
                    if(newMax > 20) newMax = 20;
                }catch (Exception ignored){ newMax =5; }
            }
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(KEY_REDIRECT_MAX_COUNT, newMax);
            editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN, swCrossDomain.isChecked());
            editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, swCrossProto.isChecked());
            editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, swFollowHeader.isChecked());
            editor.putBoolean(KEY_REDIRECT_IGNORE_SSL, swIgnoreSsl.isChecked());
            editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, swSendCookie.isChecked());
            editor.putString(KEY_USER_AGENT_MODE, currentUaMode[0]);
            editor.apply();
            updateRedirectSettingText();
            Toast.makeText(this, "重定向配置保存成功", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try {
                applyFullScreen();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
                layoutParams.dimAmount = 0f;
                getWindow().setAttributes(layoutParams);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            } catch (Exception e) { }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webServerManager != null) {
            webServerManager.stop();
        }
        if (updateManager != null) {
            updateManager.release();
        }
        mainHandler.removeCallbacksAndMessages(null);
        remoteManager = null;
        settingsItemList.clear();
        settingsItemList = null;
        itemTextViews.clear();
        itemTextViews = null;
    }
}
