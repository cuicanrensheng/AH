package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

public class SettingsDialog extends android.app.Dialog {
    static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";

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

    private SharedPreferences sp;
    private ScrollView scrollView;

    private BootStartManager bootStartManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;

    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private long mShowTime = 0;
    private static final long IGNORE_KEY_DELAY_MS = 500;

    private boolean isDismissing = false;
    private boolean backHandled = false;

    private final Context context;

    // Sub-managers
    private SettingsItemManager itemManager;
    private SettingsDialogHelper dialogHelper;
    private SettingsSubscriptionManager subscriptionManager;
    private SettingsRedirectManager redirectManager;

    public SettingsDialog(Context context) {
        super(context);
        this.context = context;
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

        sp = getContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // Initialize sub-managers
        redirectManager = new SettingsRedirectManager(getContext(), sp, mainHandler);
        redirectManager.initRedirectDefaultConfig();

        dialogHelper = new SettingsDialogHelper(getContext(), sp, mainHandler);

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

        bootStartManager = new BootStartManager(getContext(), sp);
        sourceDialogManager = new SourceDialogManager(getContext(), sp);
        qrCodeManager = new QRCodeManager(getContext());
        webServerManager = new WebServerManager(getContext(), WEB_SERVER_PORT);

        itemLiveSubscribe = findViewById(R.id.item_live_subscribe);
        itemEpgSubscribe = findViewById(R.id.item_epg_subscribe);

        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        bootStartManager.updateBootStatusText(tv_boot_status);
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));

        String decoderMode = sp.getString("decoder_mode", "auto");
        SettingsDialogHelper.updateDecoderModeText(tv_decoder_mode, decoderMode);
        String rendererMode = sp.getString("renderer_type", "surface");
        SettingsDialogHelper.updateRendererModeText(tv_renderer_type, rendererMode);
        redirectManager.updateRedirectSettingText(tv_redirect_setting);

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
        tv_channel_line.setText(dialogHelper.getLineName(currentLineIndex));

        boolean exitDialogEnabled = sp.getBoolean("exit_dialog_enable", false);
        tv_exit_dialog_status.setText(exitDialogEnabled ? "开启" : "关闭");

        // Initialize subscription manager
        subscriptionManager = new SettingsSubscriptionManager(getContext(), qrCodeManager, mainHandler, "");

        initSettingsItemList();

        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();

        // Re-initialize subscription manager with the actual web URL
        subscriptionManager = new SettingsSubscriptionManager(getContext(), qrCodeManager, mainHandler, currentWebUrl);

        SourceManager liveManager = new SourceManager(getContext(), "live_history");
        if (liveManager.size() == 0) {
            liveManager.addSource("默认直播源", UrlConfig.LIVE_URL);
            liveManager.addSource("备用直播源", UrlConfig.LIVE_URL_2);
            int defaultLiveIdx = liveManager.indexOfUrl(UrlConfig.LIVE_URL);
            if (defaultLiveIdx > 0) {
                liveManager.moveToTop(defaultLiveIdx);
            }
        }
        SourceManager epgManager = new SourceManager(getContext(), "epg_history");
        if (epgManager.size() == 0) {
            epgManager.addSource("默认节目单", UrlConfig.EPG_URL);
            epgManager.addSource("备用节目单", UrlConfig.EPG_URL_2);
            int defaultEpgIdx = epgManager.indexOfUrl(UrlConfig.EPG_URL);
            if (defaultEpgIdx > 0) {
                epgManager.moveToTop(defaultEpgIdx);
            }
        }
    }

    private void initSettingsItemList() {
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
                findViewById(R.id.item_version_info),
                findViewById(R.id.item_exit_dialog),
        };

        itemManager = new SettingsItemManager(this, mainHandler);
        itemManager.setItems(items);
        itemManager.initSettingsItemList(scrollView);
    }

    @Override
    public void show() {
        mShowTime = System.currentTimeMillis();
        isDismissing = false;
        backHandled = false;

        super.show();
        mainHandler.postDelayed(() -> {
            if (itemManager != null) {
                itemManager.requestFocusFirstItem();
            }
        }, 300);
    }

    void performItemAction(int index) {
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
                dialogHelper.showDecoderModeDialog(tv_decoder_mode);
                break;
            case 6:
                dialogHelper.showRendererModeDialog(tv_renderer_type);
                break;
            case 7:
                dialogHelper.showRatioDialog();
                break;
            case 8:
                redirectManager.showRedirectConfigDialog();
                break;
            case 9:
                showSubscriptionDialog("live_history", "直播源订阅");
                break;
            case 10:
                showSubscriptionDialog("epg_history", "节目单订阅");
                break;
            case 11:
                dialogHelper.showVersionInfoDialog();
                break;
            case 12:
                boolean exitDialogEnabled = sp.getBoolean("exit_dialog_enable", false);
                boolean newState = !exitDialogEnabled;
                sp.edit().putBoolean("exit_dialog_enable", newState).apply();
                tv_exit_dialog_status.setText(newState ? "开启" : "关闭");
                Toast.makeText(getContext(), "退出弹窗已" + (newState ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private void showChannelLineDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(getContext());
        Channel currentChannel = playerManager.getCurrentChannel();

        dialogHelper.showChannelLineDialog(playerManager, currentChannel, tv_channel_line, (which) -> {
            // Additional handling if needed
        });
    }

    private void showResolutionDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(getContext());
        Channel currentChannel = playerManager.getCurrentChannel();

        dialogHelper.showResolutionDialog(playerManager, currentChannel, tv_resolution_status, (label) -> {
            // Additional handling if needed
        });
    }

    private void showSubscriptionDialog(String spKey, String title) {
        if (subscriptionManager != null) {
            subscriptionManager.showSubscriptionDialog(spKey, title);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                backHandled = true;
                dismiss();
                return true;
            }

            if (itemManager != null) {
                boolean handled = itemManager.handleKeyDown(keyCode, event, mShowTime, IGNORE_KEY_DELAY_MS);
                if (handled) return true;
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
}