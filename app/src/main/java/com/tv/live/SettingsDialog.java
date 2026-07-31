package com.tv.live;

import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.List;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.content.ComponentName;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.GridLayout;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;

import com.tv.live.tv.TvChannelSyncManager;
import com.tv.live.util.CacheManager;
import com.tv.live.util.LogCollector;
import com.tv.live.PlaylistParser;

public class SettingsDialog extends android.app.Dialog {
    private SwitchCompat sw_boot, sw_reverse, sw_pip;
    private TextView tv_screen_ratio, tv_decoder_mode, tv_renderer_type, tv_redirect_setting, tv_boot_status;
    private TextView tv_channel_line;
    private TextView tv_resolution_status;
    private View itemResolution;
    
    private View itemExitDialog;
    private TextView tv_exit_dialog_status;

    private View itemVersionInfo;
    private TextView tv_version_short;
    
    private LinearLayout itemLiveSubscribe, itemEpgSubscribe;
    
    private View itemDebugLog;
    private TextView tv_debug_log_status;
    
    private SharedPreferences sp;
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
    private static final String KEY_DEBUG_LOG_ENABLE = "debug_log_enable";

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private int selectedItemPosition = 0;

    private long mShowTime = 0;
    private static final long IGNORE_KEY_DELAY_MS = 500;

    private boolean isDismissing = false;
    private boolean backHandled = false;

    public SettingsDialog(android.content.Context context) {
        super(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window window = getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(android.view.Gravity.FILL);
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.dimAmount = 0f;
            window.setAttributes(layoutParams);
        }

        setContentView(R.layout.activity_settings);
        
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> dismiss());
        
        sp = getContext().getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE);
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

        itemExitDialog = findViewById(R.id.item_exit_dialog);
        tv_exit_dialog_status = findViewById(R.id.tv_exit_dialog_status);

        itemVersionInfo = findViewById(R.id.item_version_info);
        tv_version_short = findViewById(R.id.tv_version_short);
        
        itemDebugLog = findViewById(R.id.item_debug_log);
        tv_debug_log_status = findViewById(R.id.tv_debug_log_status);
        
        bootStartManager = new BootStartManager(getContext(), sp);
        sourceDialogManager = new SourceDialogManager(getContext(), sp);
        qrCodeManager = new QRCodeManager(getContext());
        webServerManager = new WebServerManager(getContext(), WEB_SERVER_PORT);
        updateManager = new UpdateManager(getContext());
        
        itemLiveSubscribe = findViewById(R.id.item_live_subscribe);
        itemEpgSubscribe = findViewById(R.id.item_epg_subscribe);

        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        bootStartManager.updateBootStatusText(tv_boot_status);
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));

        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        updateRedirectSettingText();
        
        TVPlayerManager playerManager = TVPlayerManager.getInstance(getContext());
        Channel currentChannel = playerManager.getCurrentChannel();
        String savedRes = "";
        if (currentChannel != null) {
            String channelKey = currentChannel.getChannelId();
            if (TextUtils.isEmpty(channelKey)) {
                channelKey = currentChannel.getName();
            }
            String prefKey = "resolution_" + channelKey;
            savedRes = sp.getString(prefKey, "");
        } else {
            savedRes = sp.getString("resolution", "");
        }
        if (!savedRes.isEmpty()) {
            tv_resolution_status.setText(savedRes);
        } else {
            tv_resolution_status.setText("自动");
        }

        tv_channel_line = findViewById(R.id.tv_channel_line);
        int currentLineIndex = 0;
        if (currentChannel != null) {
            String channelKey = currentChannel.getChannelId();
            if (TextUtils.isEmpty(channelKey)) {
                channelKey = currentChannel.getName();
            }
            String prefKey = "channel_line_index_" + channelKey;
            currentLineIndex = sp.getInt(prefKey, 0);
        } else {
            currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        }
        tv_channel_line.setText(getLineName(currentLineIndex));

        boolean exitDialogEnabled = sp.getBoolean("exit_dialog_enable", false);
        tv_exit_dialog_status.setText(exitDialogEnabled ? "开启" : "关闭");

        updateDebugLogStatus();

        initSettingsItemList();

        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();

        SourceManager liveManager = new SourceManager(getContext(), "live_history");
        if (liveManager.size() == 0) {
            liveManager.addSource("默认直播源", UrlConfig.LIVE_URL);
        }
        SourceManager epgManager = new SourceManager(getContext(), "epg_history");
        if (epgManager.size() == 0) {
            epgManager.addSource("默认节目单", UrlConfig.EPG_URL);
        }
    }

    private void updateDebugLogStatus() {
        if (tv_debug_log_status == null) return;
        boolean enabled = sp.getBoolean(KEY_DEBUG_LOG_ENABLE, false);
        tv_debug_log_status.setText(enabled 
            ? getContext().getString(R.string.debug_log_status_on) 
            : getContext().getString(R.string.debug_log_status_off));
    }

    // 🟢【精简版日志弹窗】移除清空按钮，停止记录字体改为黑色
    private void showDebugLogDialog() {
        boolean currentDebugState = sp.getBoolean(KEY_DEBUG_LOG_ENABLE, false);
        String logs = LogCollector.getInstance().getAllLogs();
        if (TextUtils.isEmpty(logs)) {
            logs = getContext().getString(R.string.debug_log_empty);
        }

        // 🔴 将占位符替换为真正的 HTML 横线
        String finalLogs = logs.replace("###DIVIDER###", "<hr style=\"border:0; border-top:1px solid #D0D0D0; margin:6px 0;\">");
        String logsWithBr = finalLogs.replace("\n", "<br>");

        // 1. 主布局 (垂直)
        LinearLayout mainLayout = new LinearLayout(getContext());
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.WHITE);
        mainLayout.setElevation(dp2px(8));
        
        GradientDrawable whiteCornerDrawable = new GradientDrawable();
        whiteCornerDrawable.setColor(Color.WHITE);
        whiteCornerDrawable.setCornerRadius(dp2px(24));
        mainLayout.setBackground(whiteCornerDrawable);
        
        int pad = dp2px(20);
        mainLayout.setPadding(pad, pad, pad, dp2px(12));

        // 2. 标题
        TextView titleView = new TextView(getContext());
        titleView.setText(getContext().getString(R.string.debug_log_dialog_title));
        titleView.setTextColor(Color.BLACK);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, dp2px(12));
        mainLayout.addView(titleView);

        // 3. 日志内容 (滚动视图) - 权重自动填满剩余空间
        ScrollView scrollView = new ScrollView(getContext());
        scrollView.setScrollbarFadingEnabled(false);
        scrollView.setVerticalScrollBarEnabled(true);

        TextView msgView = new TextView(getContext());
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            msgView.setText(Html.fromHtml(logsWithBr, Html.FROM_HTML_MODE_COMPACT));
        } else {
            msgView.setText(Html.fromHtml(logsWithBr));
        }
        
        msgView.setTextColor(Color.DKGRAY);
        msgView.setTextSize(12); 
        msgView.setLineSpacing(0, 1.3f);
        msgView.setFocusable(true);
        msgView.setFocusableInTouchMode(true);

        scrollView.addView(msgView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
        );
        mainLayout.addView(scrollView, scrollLp);

        // 4. 🟢【按钮行】只保留开始/停止 和 关闭，平分宽度
        LinearLayout buttonRow = new LinearLayout(getContext());
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, dp2px(16), 0, 0);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, dp2px(50), 1f);
        int margin = dp2px(4);
        btnParams.setMargins(margin, 0, margin, 0);

        GradientDrawable buttonBg = new GradientDrawable();
        buttonBg.setColor(Color.WHITE);
        buttonBg.setStroke(dp2px(1), Color.parseColor("#D0D0D0"));
        buttonBg.setCornerRadius(dp2px(6));

        Button btnStartStop = new Button(getContext());
        btnStartStop.setText(currentDebugState ? "停止记录" : "开始记录");
        btnStartStop.setBackground(buttonBg);
        
        // 🟢【核心修改】开始记录为蓝色，停止记录为黑色（与关闭按钮颜色一致）
        btnStartStop.setTextColor(currentDebugState ? Color.BLACK : 0xFF007AFF);
        
        btnStartStop.setLayoutParams(btnParams);
        btnStartStop.setGravity(Gravity.CENTER);
        btnStartStop.setFocusable(true);

        Button btnClose = new Button(getContext());
        btnClose.setText("关闭");
        btnClose.setBackground(buttonBg);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setLayoutParams(btnParams);
        btnClose.setGravity(Gravity.CENTER);
        btnClose.setFocusable(true);

        // 5. 构建弹窗
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setView(mainLayout);
        AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            int screenWidth = getContext().getResources().getDisplayMetrics().widthPixels;
            lp.width = (int) (screenWidth * 0.85);
            lp.gravity = Gravity.CENTER;
            dialog.getWindow().setAttributes(lp);
        }

        // 6. 绑定点击事件
        btnStartStop.setOnClickListener(v -> {
            boolean newState = !currentDebugState;
            sp.edit().putBoolean(KEY_DEBUG_LOG_ENABLE, newState).apply();
            btnStartStop.setText(newState ? "停止记录" : "开始记录");
            
            // 🟢 同步文字颜色：停止时黑色，开始后蓝色
            btnStartStop.setTextColor(newState ? Color.BLACK : 0xFF007AFF);
            
            updateDebugLogStatus();

            if (!newState) {
                LogCollector.getInstance().clear();
                Toast.makeText(getContext(), "已停止记录并清空日志", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "网络日志记录已开启", Toast.LENGTH_SHORT).show();
            }
            
            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
            intent.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(intent);
        });

        btnClose.setOnClickListener(v -> {
            if (dialog != null) {
                dialog.dismiss();
            }
        });

        buttonRow.addView(btnStartStop);
        buttonRow.addView(btnClose);
        mainLayout.addView(buttonRow);

        // 7. 显示弹窗
        dialog.show();

        // 8. 自动聚焦首个按钮（适用于电视遥控器）
        mainHandler.postDelayed(() -> {
            if (btnStartStop != null) {
                btnStartStop.requestFocus();
            }
            if (msgView != null && dialog.isShowing()) {
                msgView.requestFocus();
            }
        }, 300);
    }

    // ===== 以下为原有菜单代码，保持不变 =====
    private void initSettingsItemList() {
        View itemTifSync = findViewById(R.id.item_tif_sync);
        TextView tvTifStatus = findViewById(R.id.tv_tif_status);
        updateTifStatus(tvTifStatus);

        View[] items = {
            findViewById(R.id.item_boot),
            findViewById(R.id.item_reverse),
            findViewById(R.id.item_pip),
            findViewById(R.id.item_channel_line),
            findViewById(R.id.item_resolution),
            findViewById(R.id.item_decoder),
            findViewById(R.id.item_renderer),
            findViewById(R.id.tv_screen_ratio),
            findViewById(R.id.item_redirect),
            findViewById(R.id.item_live_subscribe),
            findViewById(R.id.item_epg_subscribe),
            itemTifSync,
            findViewById(R.id.item_check_update),
            findViewById(R.id.item_version_info),
            findViewById(R.id.item_exit_dialog),
            itemDebugLog
        };

        for (View item : items) {
            item.setBackgroundResource(R.drawable.item_settings_bg);
            item.setFocusable(true);
            item.setClickable(true);
        }

        for (int i = 0; i < items.length; i++) {
            View item = items[i];
            int finalI = i;
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    if (selectedItemPosition != finalI) {
                        items[selectedItemPosition].setSelected(false);
                        items[finalI].setSelected(true);
                        selectedItemPosition = finalI;
                    }
                }
                v.setBackgroundResource(R.drawable.item_settings_bg);
            });
        }

        View.OnClickListener clickListener = v -> {
            int clickedIndex = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    clickedIndex = i;
                    break;
                }
            }
            if (clickedIndex == -1) return;

            if (clickedIndex == selectedItemPosition) {
                performItemAction(clickedIndex);
            } else {
                items[selectedItemPosition].setSelected(false);
                items[clickedIndex].setSelected(true);
                selectedItemPosition = clickedIndex;
            }
        };

        for (View item : items) {
            item.setOnClickListener(clickListener);
        }

        items[0].setSelected(true);
        
        if (scrollView != null) {
            scrollView.setFocusable(false);
            scrollView.setFocusableInTouchMode(false);
            scrollView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            scrollView.scrollTo(0, 0);
        }
        
        mainHandler.postDelayed(() -> {
            items[0].requestFocus();
            android.util.Log.d("Settings", "First item focused");
        }, 100);
    }

    @Override
    public void show() {
        mShowTime = System.currentTimeMillis();
        isDismissing = false;
        backHandled = false;

        super.show();
        mainHandler.postDelayed(() -> {
            View[] items = {
                findViewById(R.id.item_boot),
                findViewById(R.id.item_reverse),
                findViewById(R.id.item_pip),
                findViewById(R.id.item_channel_line),
                findViewById(R.id.item_resolution),
                findViewById(R.id.item_decoder),
                findViewById(R.id.item_renderer),
                findViewById(R.id.tv_screen_ratio),
                findViewById(R.id.item_redirect),
                findViewById(R.id.item_live_subscribe),
                findViewById(R.id.item_epg_subscribe),
                findViewById(R.id.item_tif_sync),
                findViewById(R.id.item_check_update),
                findViewById(R.id.item_version_info),
                findViewById(R.id.item_exit_dialog),
                itemDebugLog
            };
            if (items.length > 0 && items[0] != null) {
                items[0].requestFocus();
                android.util.Log.d("SettingsDialog", "Focus requested to first item");
            }
        }, 300);
    }

    private void performItemAction(int index) {
        switch (index) {
            case 0:
                boolean bootChecked = !sw_boot.isChecked();
                sw_boot.setChecked(bootChecked);
                bootStartManager.toggleBoot(bootChecked, tv_boot_status);
                break;
            case 1:
                boolean reverseChecked = !sw_reverse.isChecked();
                sw_reverse.setChecked(reverseChecked);
                sp.edit().putBoolean("channel_reverse", reverseChecked).apply();
                Toast.makeText(getContext(), "换台反转" + (reverseChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
                break;
            case 2:
                boolean pipChecked = !sw_pip.isChecked();
                sw_pip.setChecked(pipChecked);
                sp.edit().putBoolean("pip_enable", pipChecked).apply();
                if (pipChecked) {
                    Toast.makeText(getContext(), "画中画已开启，按Home键自动小窗播放", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "画中画已关闭", Toast.LENGTH_SHORT).show();
                }
                break;
            case 3:
                showChannelLineDialog();
                break;
            case 4:
                showResolutionDialog();
                break;
            case 5:
                showDecoderModeDialog();
                break;
            case 6:
                showRendererModeDialog();
                break;
            case 7:
                showRatioDialog();
                break;
            case 8:
                showRedirectConfigDialog();
                break;
            case 9:
                showSubscriptionDialog("live_history", "直播源订阅");
                break;
            case 10:
                showSubscriptionDialog("epg_history", "节目单订阅");
                break;
            case 11:
                syncChannelsToSystemTif();
                break;
            case 12:
                updateManager.checkUpdate();
                break;
            case 13:
                showVersionInfoDialog();
                break;
            case 14:
                boolean exitDialogEnabled = sp.getBoolean("exit_dialog_enable", false);
                boolean newState = !exitDialogEnabled;
                sp.edit().putBoolean("exit_dialog_enable", newState).apply();
                tv_exit_dialog_status.setText(newState ? "开启" : "关闭");
                Toast.makeText(getContext(), "退出弹窗已" + (newState ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                break;
            case 15:
                showDebugLogDialog();
                break;
        }
    }

    private void showVersionInfoDialog() {
        String versionName = BuildConfig.VERSION_NAME;
        int versionCode = BuildConfig.VERSION_CODE;
        String updateNotes = updateManager.getUpdateMessage();
        if (TextUtils.isEmpty(updateNotes) || "null".equalsIgnoreCase(updateNotes)) {
            updateNotes = "暂无更新内容，点击右上角检查更新可获取最新版本。";
        }
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

        StringBuilder sb = new StringBuilder();
        sb.append("版本信息: v").append(versionName).append(" (").append(versionCode).append(")\n\n");
        sb.append("UA: ").append(userAgent).append("\n\n");
        sb.append("SDK 版本: ").append(sdkVersion).append("\n\n");
        sb.append("播放器版本: ").append(playerVersion).append("\n\n");
        sb.append("━━━━━━━━━━━━━━━━━━\n");
        sb.append("更新内容:\n").append(updateNotes);

        String message = sb.toString();
        SpannableString spannableString = new SpannableString(message);

        int p;
        p = message.indexOf("版本信息");
        if (p != -1) spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), p, p + 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("UA:");
        if (p != -1) spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), p, p + 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("SDK 版本");
        if (p != -1) spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), p, p + 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("播放器版本");
        if (p != -1) spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), p, p + 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("更新内容");
        if (p != -1) spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), p, p + 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.dialog_bg_corner);
        int pad = dp2px(16);
        layout.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(getContext());
        titleView.setText("📱 应用详情");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, dp2px(8));
        layout.addView(titleView);

        ScrollView scrollView = new ScrollView(getContext());
        scrollView.setScrollbarFadingEnabled(false);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setFillViewport(true);

        TextView msgView = new TextView(getContext());
        msgView.setText(spannableString);
        msgView.setTextColor(Color.WHITE);
        msgView.setTextSize(16);
        msgView.setLineSpacing(0, 1.25f);
        msgView.setFocusable(true);
        msgView.setFocusableInTouchMode(true);

        scrollView.addView(msgView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        layout.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(380)
        ));

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        mainHandler.postDelayed(() -> {
            if (msgView != null && dialog.isShowing()) {
                msgView.requestFocus();
            }
        }, 200);
    }

    private int dp2px(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    private String getLineName(int index) {
        if (index == 0) return "主源";
        return "源" + index;
    }

    private void showNumberInputDialog(int currentValue, java.util.function.Consumer<Integer> onConfirmed) {
        LinearLayout dialogView = new LinearLayout(getContext());
        dialogView.setOrientation(LinearLayout.VERTICAL);
        dialogView.setBackgroundResource(R.drawable.dialog_bg_corner);
        dialogView.setPadding(24, 24, 24, 24);

        TextView titleView = new TextView(getContext());
        titleView.setText("请输入数字");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, 24);
        dialogView.addView(titleView);

        final TextView displayView = new TextView(getContext());
        displayView.setText(String.valueOf(currentValue));
        displayView.setTextColor(Color.WHITE);
        displayView.setTextSize(48);
        displayView.setGravity(Gravity.CENTER);
        displayView.setBackgroundColor(0xFF333545);
        displayView.setPadding(16, 16, 16, 16);
        dialogView.addView(displayView);

        LinearLayout keysLayout = new LinearLayout(getContext());
        keysLayout.setOrientation(LinearLayout.VERTICAL);
        keysLayout.setPadding(16, 24, 16, 0);

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "清空", "0", "确认"};
        final TextView[] keyViews = new TextView[keys.length];

        for (int row = 0; row < 4; row++) {
            LinearLayout rowLayout = new LinearLayout(getContext());
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                TextView keyView = new TextView(getContext());
                keyView.setText(keys[index]);
                keyView.setTextColor(Color.WHITE);
                keyView.setTextSize(20);
                keyView.setGravity(Gravity.CENTER);
                keyView.setBackgroundColor(0xFF333545);
                keyView.setPadding(32, 20, 32, 20);
                keyView.setFocusable(true);
                keyView.setFocusableInTouchMode(true);
                keyView.setTag(keys[index]);
                keyViews[index] = keyView;

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                params.setMargins(8, 8, 8, 8);
                rowLayout.addView(keyView, params);
            }
            keysLayout.addView(rowLayout);
        }
        dialogView.addView(keysLayout);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final StringBuilder inputValue = new StringBuilder(String.valueOf(currentValue));

        for (int i = 0; i < keys.length; i++) {
            final int idx = i;
            final String key = keys[i];
            TextView keyView = keyViews[i];

            keyView.setOnClickListener(v -> {
                if ("清空".equals(key)) {
                    inputValue.setLength(0);
                    displayView.setText("");
                } else if ("确认".equals(key)) {
                    int value = 0;
                    try {
                        value = Integer.parseInt(inputValue.toString());
                    } catch (NumberFormatException e) {
                        value = currentValue;
                    }
                    if (value < 1) value = 1;
                    if (value > 20) value = 20;
                    onConfirmed.accept(value);
                    dialog.dismiss();
                } else {
                    if (inputValue.length() < 2) {
                        inputValue.append(key);
                        int tempValue = Integer.parseInt(inputValue.toString());
                        if (tempValue <= 20) {
                            displayView.setText(inputValue.toString());
                        } else {
                            inputValue.setLength(inputValue.length() - 1);
                        }
                    }
                }
            });

            keyView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        if ("清空".equals(key)) {
                            inputValue.setLength(0);
                            displayView.setText("");
                        } else if ("确认".equals(key)) {
                            int value = 0;
                            try {
                                value = Integer.parseInt(inputValue.toString());
                            } catch (NumberFormatException e) {
                                value = currentValue;
                            }
                            if (value < 1) value = 1;
                            if (value > 20) value = 20;
                            onConfirmed.accept(value);
                            dialog.dismiss();
                        } else {
                            if (inputValue.length() < 2) {
                                inputValue.append(key);
                                int tempValue = Integer.parseInt(inputValue.toString());
                                if (tempValue <= 20) {
                                    displayView.setText(inputValue.toString());
                                } else {
                                    inputValue.setLength(inputValue.length() - 1);
                                }
                            }
                        }
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                        dialog.dismiss();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && idx >= 3) {
                        keyViews[idx - 3].requestFocus();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && idx < 9) {
                        keyViews[idx + 3].requestFocus();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && idx % 3 > 0) {
                        keyViews[idx - 1].requestFocus();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && idx % 3 < 2) {
                        keyViews[idx + 1].requestFocus();
                        return true;
                    }
                }
                return false;
            });

            keyView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    keyView.setBackgroundColor(0x3340A9FF);
                    keyView.setTextColor(0xFF40A9FF);
                } else {
                    keyView.setBackgroundColor(0xFF333545);
                    keyView.setTextColor(Color.WHITE);
                }
            });
        }

        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                dialog.dismiss();
                return true;
            }
            return false;
        });

        dialog.show();

        mainHandler.postDelayed(() -> {
            if (keyViews.length > 0 && dialog.isShowing()) {
                keyViews[4].requestFocus();
            }
        }, 200);
    }

    private void showCommonSelectionDialog(String title, String[] items, int checkedItem, java.util.function.Consumer<Integer> onSelected) {
        ListView listView = new ListView(getContext());
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setDivider(new ColorDrawable(0x33FFFFFF));
        listView.setDividerHeight(1);
        listView.setPadding(0, 16, 0, 16);

        final int[] pendingPos = {checkedItem};
        final AlertDialog[] dialogHolder = new AlertDialog[1];

        class CustomAdapter extends ArrayAdapter<String> {
            private int selectedPos;

            public CustomAdapter(android.content.Context context, String[] items, int initialPos) {
                super(context, android.R.layout.simple_list_item_single_choice, items);
                selectedPos = initialPos;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(16, 16, 16, 16);
                
                if (position == selectedPos) {
                    tv.setTextColor(0xFF40A9FF);
                    view.setBackgroundColor(0x3340A9FF);
                } else {
                    tv.setTextColor(Color.WHITE);
                    view.setBackgroundColor(0x00000000);
                }
                return view;
            }

            public void setSelectedPos(int pos) {
                selectedPos = pos;
                notifyDataSetChanged();
            }
        }
        
        CustomAdapter adapter = new CustomAdapter(getContext(), items, checkedItem);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(checkedItem, true);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            pendingPos[0] = position;
            adapter.setSelectedPos(position);
            listView.setItemChecked(position, true);
            onSelected.accept(position);
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });

        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (pendingPos[0] != position) {
                    pendingPos[0] = position;
                    adapter.setSelectedPos(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        listView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    int selected = listView.getSelectedItemPosition();
                    if (selected >= 0 && selected < items.length) {
                        if (pendingPos[0] == selected) {
                            onSelected.accept(selected);
                            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                        } else {
                            pendingPos[0] = selected;
                            adapter.setSelectedPos(selected);
                            listView.setItemChecked(selected, true);
                            Toast.makeText(getContext(), "再次按确认键选择", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                    return true;
                }
            }
            return false;
        });

        TextView titleView = new TextView(getContext());
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(24, 24, 24, 0);

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.dialog_bg_corner);
        layout.setPadding(24, 24, 24, 24);

        layout.addView(titleView);
        layout.addView(listView);

        dialogHolder[0] = new AlertDialog.Builder(getContext())
                .setView(layout)
                .create();
        if (dialogHolder[0].getWindow() != null) {
            dialogHolder[0].getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialogHolder[0].show();
        mainHandler.postDelayed(() -> {
            if (listView != null && dialogHolder[0] != null && dialogHolder[0].isShowing()) {
                listView.requestFocus();
            }
        }, 200);
    }

    private void showChannelLineDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(getContext());
        Channel currentChannel = playerManager.getCurrentChannel();
        if (currentChannel == null) {
            Toast.makeText(getContext(), "请先播放一个频道，再切换线路", Toast.LENGTH_SHORT).show();
            return;
        }

        String channelKey = currentChannel.getChannelId();
        if (TextUtils.isEmpty(channelKey)) {
            channelKey = currentChannel.getName();
        }
        String prefKey = "channel_line_index_" + channelKey;
        int currentLineIndex = sp.getInt(prefKey, 0);

        List<String> lineList = new ArrayList<>();
        lineList.add("主源");
        for (int i = 1; i <= currentChannel.getBackupUrls().size(); i++) {
            lineList.add("源" + i);
        }
        String[] lineArray = lineList.toArray(new String[0]);

        showCommonSelectionDialog("频道线路选择", lineArray, currentLineIndex, (which) -> {
            sp.edit().putInt(prefKey, which).apply();
            sp.edit().putInt(KEY_CHANNEL_LINE_INDEX, which).apply();
            tv_channel_line.setText(lineArray[which]);

            if (playerManager != null && currentChannel != null) {
                String playUrl;
                if (which == 0) {
                    playUrl = currentChannel.getMainPlayUrl();
                } else {
                    List<String> backups = currentChannel.getBackupUrls();
                    int backupIndex = which - 1;
                    if (backupIndex >= 0 && backupIndex < backups.size()) {
                        playUrl = backups.get(backupIndex);
                    } else {
                        playUrl = currentChannel.getMainPlayUrl();
                    }
                }
                playerManager.playUrl(playUrl, currentChannel.getName(), currentChannel);
            }
            Toast.makeText(getContext(), "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
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

    private void showResolutionDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(getContext());
        if (playerManager == null) {
            return;
        }
        List<String> resolutions = playerManager.getAvailableResolutions();
        if (resolutions.isEmpty()) {
            Toast.makeText(getContext(), "当前直播源不支持清晰度切换", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = resolutions.toArray(new String[0]);

        String savedRes = "";
        Channel currentChannel = playerManager.getCurrentChannel();
        if (currentChannel != null) {
            String channelKey = currentChannel.getChannelId();
            if (TextUtils.isEmpty(channelKey)) {
                channelKey = currentChannel.getName();
            }
            String prefKey = "resolution_" + channelKey;
            savedRes = sp.getString(prefKey, "");
        } else {
            savedRes = sp.getString("resolution", "");
        }
        String currentResLabel = savedRes.isEmpty() ? playerManager.getCurrentResolutionLabel() : savedRes;
        int initialPos = 0;
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(currentResLabel)) {
                initialPos = i;
                break;
            }
        }

        showCommonSelectionDialog("清晰度选择", items, initialPos, (which) -> {
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
                if (currentChannel != null) {
                    String channelKey = currentChannel.getChannelId();
                    if (TextUtils.isEmpty(channelKey)) {
                        channelKey = currentChannel.getName();
                    }
                    String prefKey = "resolution_" + channelKey;
                    sp.edit().putString(prefKey, selectedLabel).apply();
                } else {
                    sp.edit().putString("resolution", selectedLabel).apply();
                }
                tv_resolution_status.setText(selectedLabel);
                Toast.makeText(getContext(), "已切换至: " + selectedLabel, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showDecoderModeDialog() {
        final String[] modes = {"自动（推荐）", "硬解", "软解（兼容性好）"};
        final String[] modeValues = {"auto", "hard", "soft"};
        String currentMode = sp.getString("decoder_mode", "auto");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }

        showCommonSelectionDialog("解码器选择", modes, checkedItem, (which) -> {
            String selectedMode = modeValues[which];
            sp.edit().putString("decoder_mode", selectedMode).apply();
            updateDecoderModeText(selectedMode);
            
            Intent intent = new Intent("com.tv.live.DECODER_MODE_CHANGED");
            intent.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(intent);
            
            Toast.makeText(getContext(), "已切换到" + modes[which] + "，正在重新加载…", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateDecoderModeText(String mode) {
        if (tv_decoder_mode == null) return;
        switch (mode) {
            case "hard": tv_decoder_mode.setText("硬解"); break;
            case "soft": tv_decoder_mode.setText("软解"); break;
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

        showCommonSelectionDialog(getContext().getString(R.string.render_mode_select), modes, checkedItem, (which) -> {
            String selectedMode = modeValues[which];
            sp.edit().putString("renderer_type", selectedMode).apply();
            updateRendererModeText(selectedMode);
            
            Intent intent = new Intent("com.tv.live.RENDERER_TYPE_CHANGED");
            intent.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(intent);
            
            Toast.makeText(getContext(), "已切换到" + modes[which] + "，正在应用……", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateRendererModeText(String mode) {
        if (tv_renderer_type == null) return;
        switch (mode) {
            case "texture": tv_renderer_type.setText(getContext().getString(R.string.texture_view)); break;
            case "surface": default: tv_renderer_type.setText(getContext().getString(R.string.surface_view)); break;
        }
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

        showCommonSelectionDialog("屏幕比例", ratios, checkedItem, (which) -> {
            sp.edit().putString("screen_ratio", ratios[which]).apply();
            Toast.makeText(getContext(), "已设置", Toast.LENGTH_SHORT).show();
        });
    }

    private void showSubscriptionDialog(String spKey, String title) {
        SourceManager sourceManager = new SourceManager(getContext(), spKey);
        List<SourceManager.SourceItem> sources = sourceManager.getAllSources();

        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(
                new android.view.ContextThemeWrapper(getContext(), androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
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
                mainHandler.post(() -> {
                    if (finalQrBitmap != null) {
                        ivQrCode.setImageBitmap(finalQrBitmap);
                    } else {
                        ivQrCode.setBackgroundColor(Color.LTGRAY);
                    }
                });
            }).start();

            ivQrCode.setOnClickListener(v -> {
                Toast.makeText(getContext(), "已生成二维码，请扫码", Toast.LENGTH_SHORT).show();
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

        etName.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                lvSourceList.requestFocus();
                return true;
            }
            return false;
        });
        etUrl.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                lvSourceList.requestFocus();
                return true;
            }
            return false;
        });

        int currentDefault = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
        SubscriptionAdapter adapter = new SubscriptionAdapter(getContext(), sources);
        adapter.setSelectedPosition(currentDefault);

        adapter.setOnActionListener(new SubscriptionAdapter.OnActionListener() {
            @Override
            public void onSwitch(int position) {
                sourceManager.setDefault(position);
                
                Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                intent.setPackage(getContext().getPackageName());
                getContext().sendBroadcast(intent);
                
                Toast.makeText(getContext(), "已切换到：" + sources.get(position).name, Toast.LENGTH_SHORT).show();
                adapter.setSelectedPosition(position);
                lvSourceList.post(() -> {
                    adapter.notifyDataSetChanged();
                    lvSourceList.setSelection(position);
                    lvSourceList.requestFocus();
                });
            }

            @Override
            public void onDelete(int position) {
                if (position < 0 || position >= sources.size()) {
                    return;
                }
                SourceManager.SourceItem item = sources.get(position);
                
                AlertDialog deleteDialog = new AlertDialog.Builder(getContext())
                        .setTitle("确认删除")
                        .setMessage("确定要删除「" + item.name + "」吗？")
                        .setPositiveButton("删除", (d, w) -> {
                            int realIndex = sourceManager.indexOfUrl(item.url);
                            if (realIndex >= 0 && realIndex < sourceManager.size()) {
                                sourceManager.removeSource(realIndex);
                                sources.clear();
                                sources.addAll(sourceManager.getAllSources());
                                int newDefaultPos = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
                                adapter.setSelectedPosition(newDefaultPos);
                                lvSourceList.post(() -> {
                                    adapter.notifyDataSetChanged();
                                    if (newDefaultPos >= 0) {
                                        lvSourceList.setSelection(newDefaultPos);
                                    }
                                    lvSourceList.requestFocus();
                                });
                                Toast.makeText(getContext(), "已删除", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(getContext(), "删除失败，源未找到", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(getContext(), "地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (sourceManager.addSource(name, url)) {
                etName.setText("");
                etUrl.setText("");
                sources.clear();
                sources.addAll(sourceManager.getAllSources());
                adapter.setSelectedPosition(sourceManager.indexOfUrl(sourceManager.getDefaultUrl()));
                adapter.notifyDataSetChanged();
                Toast.makeText(getContext(), "已添加，正在刷新...", Toast.LENGTH_SHORT).show();
                
                Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                intent.setPackage(getContext().getPackageName());
                getContext().sendBroadcast(intent);
            } else {
                Toast.makeText(getContext(), "该地址已存在", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            etName.setText("");
            etUrl.setText("");
        });

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (lvSourceList.hasFocus()) {
                        int position = adapter.getSelectedPosition();
                        if (position >= 0 && position < sources.size()) {
                            sourceManager.setDefault(position);
                            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                            intent.setPackage(getContext().getPackageName());
                            getContext().sendBroadcast(intent);
                            Toast.makeText(getContext(), "已切换到：" + sources.get(position).name, Toast.LENGTH_SHORT).show();
                            adapter.setSelectedPosition(position);
                            lvSourceList.post(() -> {
                                adapter.notifyDataSetChanged();
                                lvSourceList.setSelection(position);
                                lvSourceList.requestFocus();
                            });
                        }
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dialog.dismiss();
                    return true;
                }
            }
            return false;
        });
        dialog.show();
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }
        mainHandler.postDelayed(() -> {
            if (lvSourceList != null && dialog.isShowing()) {
                lvSourceList.requestFocus();
                if (currentDefault >= 0) {
                    lvSourceList.setSelection(currentDefault);
                }
            }
        }, 200);
    }

    private void showRedirectConfigDialog() {
        int currentMax = sp.getInt(KEY_REDIRECT_MAX_COUNT,5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL,false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        final String[] currentUaMode = { sp.getString(KEY_USER_AGENT_MODE, "exo") };
        
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(
                new android.view.ContextThemeWrapper(getContext(), androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
        );
        View dialogView = inflater.inflate(R.layout.dialog_redirect_config, null);
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

        LinearLayout llMaxCount = dialogView.findViewById(R.id.ll_max_count);
        LinearLayout llCrossDomain = dialogView.findViewById(R.id.ll_cross_domain);
        LinearLayout llCrossProto = dialogView.findViewById(R.id.ll_cross_proto);
        LinearLayout llFollowHeader = dialogView.findViewById(R.id.ll_follow_header);
        LinearLayout llSendCookie = dialogView.findViewById(R.id.ll_send_cookie);
        LinearLayout llIgnoreSsl = dialogView.findViewById(R.id.ll_ignore_ssl);
        TextView tvCrossDomain = dialogView.findViewById(R.id.tv_cross_domain);
        TextView tvCrossProto = dialogView.findViewById(R.id.tv_cross_proto);
        TextView tvFollowHeader = dialogView.findViewById(R.id.tv_follow_header);
        TextView tvSendCookie = dialogView.findViewById(R.id.tv_send_cookie);
        TextView tvIgnoreSsl = dialogView.findViewById(R.id.tv_ignore_ssl);

        tvUserAgentStatus.setText("exo".equals(currentUaMode[0]) ? "ExoPlayer默认" : "VLC");
        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(currentMax));
        etMax.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_ENTER) {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(etMax.getWindowToken(), 0);
                    llCrossDomain.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(etMax.getWindowToken(), 0);
                    llMaxCount.requestFocus();
                    return true;
                }
            }
            return false;
        });
        swCrossDomain.setChecked(crossDomain);
        swCrossProto.setChecked(crossProto);
        swFollowHeader.setChecked(followHeader);
        swIgnoreSsl.setChecked(ignoreSsl);
        swSendCookie.setChecked(sendCookie);

        final int[] pendingPos = {0};
        final LinearLayout[] items = {llMaxCount, llCrossDomain, llCrossProto, llFollowHeader, llSendCookie, llIgnoreSsl, llUserAgent};
        final String[] itemNames = {"maxCount", "crossDomain", "crossProto", "followHeader", "sendCookie", "ignoreSsl", "userAgent"};

        llMaxCount.setBackgroundColor(0x3340A9FF);
        for (int i = 0; i < llMaxCount.getChildCount(); i++) {
            View child = llMaxCount.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(0xFF40A9FF);
            } else if (child instanceof EditText) {
                ((EditText) child).setTextColor(0xFF40A9FF);
            }
        }

        View.OnClickListener clickListener = v -> {
            int currentPos = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    currentPos = i;
                    break;
                }
            }

            if (pendingPos[0] == currentPos) {
                if (currentPos == 0) {
                    int currentVal = Integer.parseInt(etMax.getText().toString());
                    showNumberInputDialog(currentVal, newVal -> {
                        etMax.setText(String.valueOf(newVal));
                    });
                } else if (currentPos == 1) {
                    swCrossDomain.setChecked(!swCrossDomain.isChecked());
                } else if (currentPos == 2) {
                    swCrossProto.setChecked(!swCrossProto.isChecked());
                } else if (currentPos == 3) {
                    swFollowHeader.setChecked(!swFollowHeader.isChecked());
                } else if (currentPos == 4) {
                    swSendCookie.setChecked(!swSendCookie.isChecked());
                } else if (currentPos == 5) {
                    swIgnoreSsl.setChecked(!swIgnoreSsl.isChecked());
                } else if (currentPos == 6) {
                    final String[] uaOptions = {"ExoPlayer默认", "VLC"};
                    final String[] uaValues = {"exo", "vlc"};
                    int checkedItem = 0;
                    for (int i = 0; i < uaValues.length; i++) {
                        if (uaValues[i].equals(currentUaMode[0])) {
                            checkedItem = i;
                            break;
                        }
                    }
                    showCommonSelectionDialog("UA切换", uaOptions, checkedItem, (which) -> {
                        currentUaMode[0] = uaValues[which];
                        tvUserAgentStatus.setText(uaOptions[which]);
                    });
                }
            } else {
                for (int i = 0; i < items.length; i++) {
                    items[i].setBackgroundColor(0xFF272B3A);
                    for (int j = 0; j < items[i].getChildCount(); j++) {
                        View child = items[i].getChildAt(j);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(Color.WHITE);
                        } else if (child instanceof EditText) {
                            ((EditText) child).setTextColor(Color.WHITE);
                        }
                    }
                }
                v.setBackgroundColor(0x3340A9FF);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(0xFF40A9FF);
                    } else if (child instanceof EditText) {
                        ((EditText) child).setTextColor(0xFF40A9FF);
                    }
                }
                pendingPos[0] = currentPos;
            }
        };

        View.OnKeyListener keyListener = (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    int currentPos = -1;
                    for (int i = 0; i < items.length; i++) {
                        if (items[i] == v) {
                            currentPos = i;
                            break;
                        }
                    }
                    if (currentPos >= 0) {
                        if (currentPos == 0) {
                            int currentVal = Integer.parseInt(etMax.getText().toString());
                            showNumberInputDialog(currentVal, newVal -> {
                                etMax.setText(String.valueOf(newVal));
                            });
                        } else if (currentPos == 1) {
                            swCrossDomain.setChecked(!swCrossDomain.isChecked());
                        } else if (currentPos == 2) {
                            swCrossProto.setChecked(!swCrossProto.isChecked());
                        } else if (currentPos == 3) {
                            swFollowHeader.setChecked(!swFollowHeader.isChecked());
                        } else if (currentPos == 4) {
                            swSendCookie.setChecked(!swSendCookie.isChecked());
                        } else if (currentPos == 5) {
                            swIgnoreSsl.setChecked(!swIgnoreSsl.isChecked());
                        } else if (currentPos == 6) {
                            final String[] uaOptions = {"ExoPlayer默认", "VLC"};
                            final String[] uaValues = {"exo", "vlc"};
                            int checkedItem = 0;
                            for (int i = 0; i < uaValues.length; i++) {
                                if (uaValues[i].equals(currentUaMode[0])) {
                                    checkedItem = i;
                                    break;
                                }
                            }
                            showCommonSelectionDialog("UA切换", uaOptions, checkedItem, (which) -> {
                                currentUaMode[0] = uaValues[which];
                                tvUserAgentStatus.setText(uaOptions[which]);
                            });
                        }
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int currentPos = -1;
                    for (int i = 0; i < items.length; i++) {
                        if (items[i] == v) {
                            currentPos = i;
                            break;
                        }
                    }
                    if (currentPos >= 0 && currentPos == items.length - 1 && btnCancel != null) {
                        btnCancel.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    int currentPos = -1;
                    for (int i = 0; i < items.length; i++) {
                        if (items[i] == v) {
                            currentPos = i;
                            break;
                        }
                    }
                    if (currentPos >= 0 && currentPos == 0) {
                        return true;
                    }
                }
            }
            return false;
        };

        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            int pos = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    pos = i;
                    break;
                }
            }
            if (hasFocus && pos >= 0) {
                for (int i = 0; i < items.length; i++) {
                    items[i].setBackgroundColor(0x00000000);
                    for (int j = 0; j < items[i].getChildCount(); j++) {
                        View child = items[i].getChildAt(j);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(Color.WHITE);
                        } else if (child instanceof EditText) {
                            ((EditText) child).setTextColor(Color.WHITE);
                        }
                    }
                }
                v.setBackgroundColor(0x3340A9FF);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(0xFF40A9FF);
                    } else if (child instanceof EditText) {
                        ((EditText) child).setTextColor(0xFF40A9FF);
                    }
                }
                pendingPos[0] = pos;
            } else {
                v.setBackgroundColor(0x00000000);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(Color.WHITE);
                    } else if (child instanceof EditText) {
                        ((EditText) child).setTextColor(Color.WHITE);
                    }
                }
            }
        };

        llMaxCount.setOnClickListener(clickListener);
        llMaxCount.setOnKeyListener(keyListener);
        llMaxCount.setOnFocusChangeListener(focusListener);
        llMaxCount.setNextFocusDownId(R.id.ll_cross_domain);
        llCrossDomain.setNextFocusUpId(R.id.ll_max_count);
        llCrossDomain.setOnClickListener(clickListener);
        llCrossDomain.setOnKeyListener(keyListener);
        llCrossDomain.setOnFocusChangeListener(focusListener);
        llCrossProto.setOnClickListener(clickListener);
        llCrossProto.setOnKeyListener(keyListener);
        llCrossProto.setOnFocusChangeListener(focusListener);
        llFollowHeader.setOnClickListener(clickListener);
        llFollowHeader.setOnKeyListener(keyListener);
        llFollowHeader.setOnFocusChangeListener(focusListener);
        llSendCookie.setOnClickListener(clickListener);
        llSendCookie.setOnKeyListener(keyListener);
        llSendCookie.setOnFocusChangeListener(focusListener);
        llIgnoreSsl.setOnClickListener(clickListener);
        llIgnoreSsl.setOnKeyListener(keyListener);
        llIgnoreSsl.setOnFocusChangeListener(focusListener);
        llUserAgent.setOnClickListener(clickListener);
        llUserAgent.setOnKeyListener(keyListener);
        llUserAgent.setOnFocusChangeListener(focusListener);
        llUserAgent.setNextFocusDownId(R.id.btn_redirect_cancel);
        btnCancel.setNextFocusUpId(R.id.ll_user_agent);
        btnCancel.setNextFocusRightId(R.id.btn_redirect_save);
        btnSave.setNextFocusLeftId(R.id.btn_redirect_cancel);
        btnSave.setNextFocusUpId(R.id.ll_user_agent);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout((int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.85),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        final View finalBtnCancel = btnCancel;
        final View finalBtnSave = btnSave;
        final View finalLlUserAgent = llUserAgent;
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dialog.dismiss();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && focused.equals(finalLlUserAgent) && finalBtnCancel != null) {
                        finalBtnCancel.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && (focused.equals(finalBtnCancel) || focused.equals(finalBtnSave)) && finalLlUserAgent != null) {
                        finalLlUserAgent.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && focused.equals(finalBtnCancel) && finalBtnSave != null) {
                        finalBtnSave.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && focused.equals(finalBtnSave) && finalBtnCancel != null) {
                        finalBtnCancel.requestFocus();
                        return true;
                    }
                }
            }
            return false;
        });
        dialog.show();
        mainHandler.postDelayed(() -> {
            if (llMaxCount != null && dialog.isShowing()) {
                llMaxCount.requestFocus();
            }
        }, 200);

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnCancel.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && llUserAgent != null) {
                    llUserAgent.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return true;
                }
            }
            return false;
        });

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
            Toast.makeText(getContext(), "重定向配置保存成功", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            View[] items = {
                findViewById(R.id.item_boot),
                findViewById(R.id.item_reverse),
                findViewById(R.id.item_pip),
                findViewById(R.id.item_channel_line),
                findViewById(R.id.item_resolution),
                findViewById(R.id.item_decoder),
                findViewById(R.id.item_renderer),
                findViewById(R.id.tv_screen_ratio),
                findViewById(R.id.item_redirect),
                findViewById(R.id.item_live_subscribe),
                findViewById(R.id.item_epg_subscribe),
                findViewById(R.id.item_tif_sync),
                findViewById(R.id.item_check_update),
                findViewById(R.id.item_version_info),
                findViewById(R.id.item_exit_dialog),
                itemDebugLog
            };

            if (keyCode == KeyEvent.KEYCODE_BACK) {
                backHandled = true;
                dismiss();
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                int nextPos = selectedItemPosition + 1;
                if (nextPos < items.length && items[nextPos] != null) {
                    items[nextPos].requestFocus();
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                int prevPos = selectedItemPosition - 1;
                if (prevPos >= 0 && items[prevPos] != null) {
                    items[prevPos].requestFocus();
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (System.currentTimeMillis() - mShowTime < IGNORE_KEY_DELAY_MS) {
                    android.util.Log.d("SettingsDialog", "忽略打开后短时间内的按键，防止误触");
                    return true;
                }
                performItemAction(selectedItemPosition);
                return true;
            }
        } catch (Exception e) {
            android.util.Log.e("SettingsDialog", "onKeyDown 异常: " + e.getMessage(), e);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        try {
            return super.onKeyUp(keyCode, event);
        } catch (Exception e) {
            android.util.Log.e("SettingsDialog", "onKeyUp 异常: " + e.getMessage(), e);
            return true;
        }
    }

    @Override
    public void onBackPressed() {
        if (!isDismissing) {
            dismiss();
        }
    }

    @Override
    public void dismiss() {
        if (isDismissing) {
            android.util.Log.d("SettingsDialog", "dismiss 已在执行中，忽略重复调用");
            return;
        }
        isDismissing = true;
        try {
            if (webServerManager != null) {
                webServerManager.stop();
            }
            if (updateManager != null) {
                updateManager.release();
            }
            mainHandler.removeCallbacksAndMessages(null);

            Intent unlockIntent = new Intent("com.tv.live.UNLOCK_SETTINGS");
            unlockIntent.setPackage(getContext().getPackageName());
            try {
                getContext().sendBroadcast(unlockIntent);
            } catch (Exception e) {
                android.util.Log.e("SettingsDialog", "发送解锁广播失败: " + e.getMessage(), e);
            }

            super.dismiss();
        } catch (Exception e) {
            android.util.Log.e("SettingsDialog", "dismiss 异常: " + e.getMessage(), e);
            isDismissing = false;
        }

        try {
            MainActivity activity = MainActivity.getRunningInstance();
            if (activity != null && !activity.isFinishing()) {
                activity.refreshSettings();
            }
        } catch (Exception e) {
            android.util.Log.e("SettingsDialog", "refreshSettings 异常: " + e.getMessage(), e);
        }
    }

    private String getTifInputId() {
        try {
            TvInputManager tim = (TvInputManager) getContext().getSystemService(android.content.Context.TV_INPUT_SERVICE);
            if (tim == null) return null;
            String packageName = getContext().getPackageName();
            String serviceName = com.tv.live.tv.LiveTvInputService.class.getName();
            for (TvInputInfo info : tim.getTvInputList()) {
                String id = info.getId();
                if (id != null && id.startsWith(packageName) && id.contains(serviceName)) {
                    return id;
                }
            }
        } catch (Exception e) {
            android.util.Log.e("SettingsDialog", "获取 TIF inputId 失败", e);
        }
        return null;
    }

    private boolean isAndroidTvDevice() {
        return getContext().getPackageManager().hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_LEANBACK);
    }

    private void updateTifStatus(TextView tvTifStatus) {
        if (tvTifStatus == null) return;
        if (!isAndroidTvDevice()) {
            tvTifStatus.setText("该设备不支持");
            return;
        }
        String inputId = getTifInputId();
        if (inputId == null) {
            tvTifStatus.setText("未激活");
            return;
        }
        int count = TvChannelSyncManager.getSyncedChannelCount(getContext(), inputId);
        tvTifStatus.setText(count > 0 ? "已同步 " + count + " 个频道" : "未同步");
    }

    private void syncChannelsToSystemTif() {
        new Thread(() -> {
            try {
                if (!isAndroidTvDevice()) {
                    mainHandler.post(() -> Toast.makeText(getContext(), "该设备不支持系统直播电视，请在 Android TV 设备上使用此功能", Toast.LENGTH_LONG).show());
                    return;
                }
                String inputId = getTifInputId();
                if (inputId == null) {
                    mainHandler.post(() -> Toast.makeText(getContext(), "TIF服务未激活，请先安装并配置系统直播电视", Toast.LENGTH_LONG).show());
                    return;
                }

                CacheManager cacheManager = CacheManager.getInstance(getContext());
                String cacheContent = cacheManager.getFileCache("live_source");
                if (cacheContent == null || cacheContent.isEmpty()) {
                    mainHandler.post(() -> Toast.makeText(getContext(), "暂无频道数据，请先在主应用加载直播源", Toast.LENGTH_LONG).show());
                    return;
                }

                List<Channel> channels = PlaylistParser.parseContent(cacheContent);
                if (channels == null || channels.isEmpty()) {
                    mainHandler.post(() -> Toast.makeText(getContext(), "频道解析失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                int synced = TvChannelSyncManager.syncChannels(getContext(), inputId, channels);
                mainHandler.post(() -> {
                    Toast.makeText(getContext(), "同步完成: " + synced + "/" + channels.size() + " 个频道", Toast.LENGTH_LONG).show();
                    View itemTifSync = findViewById(R.id.item_tif_sync);
                    if (itemTifSync != null) {
                        TextView tvTifStatus = itemTifSync.findViewById(R.id.tv_tif_status);
                        updateTifStatus(tvTifStatus);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("SettingsDialog", "TIF同步异常", e);
                mainHandler.post(() -> Toast.makeText(getContext(), "同步失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
