package com.tv.live;

import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import com.tv.live.util.LifecycleLogger;
import com.tv.live.util.LogUtils;
import com.tv.live.widget.*;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public final List<Channel> channelSourceList = new ArrayList<>();
    public final List<Channel> currentGroupChannelList = new ArrayList<>();
    public int currentPlayIndex = 0;
    private String nowSelectGroup = "";
    private int currentSelectedDateIndex = 0;

    private View panel_layout;
    public PlayerView playerView;
    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private KeyEventManager keyEventManager;
    private ChannelSwitchManager switchManager;

    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    public PlayerStateListenerImpl playerStateListener;

    // 拆分后核心管理器
    public SettingsManager settingsManager;
    private InfoBarManager infoBarManager;
    private PlayControlManager playControlManager;
    private BroadcastManager broadcastManager;
    private ViewClickManager viewClickManager;
    private WidgetInitManager widgetInitManager;
    private PlayerInitManager playerInitManager;
    private PanelManager panelManager;

    private TextView tv_channel_num;
    private boolean epgPanelOpen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LifecycleLogger.onCreate();
        mInstance = this;

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 初始化控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        panel_layout = findViewById(R.id.panel_layout);
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 配置初始化
        appConfig = AppConfig.getInstance(this);
        settingsManager = new SettingsManager(this);
        applyCustomUrls();

        // 组件初始化
        broadcastManager = new BroadcastManager(this);
        broadcastManager.register();

        infoBarManager = new InfoBarManager(getWindow().getDecorView());
        widgetInitManager = new WidgetInitManager(this);

        channelListManager = widgetInitManager.createChannelListManager(lvChannelList);
        groupListManager = widgetInitManager.createGroupListManager(lvGroup);
        epgManagerWrapper = widgetInitManager.createEpgManagerWrapper(lvEpg);
        dateListManager = widgetInitManager.createDateListManager(lvDate);

        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);
        playerStateListener = new PlayerStateListenerImpl(this);

        playerInitManager = new PlayerInitManager(this, playerView, appConfig);
        playerInitManager.init(playerStateListener);

        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        playControlManager = new PlayControlManager(this, mPlayerManager, switchManager, appConfig,
                tv_channel_num, infoBarManager, epgManagerWrapper, settingsManager, currentSelectedDateIndex);

        viewClickManager = new ViewClickManager(this, channelSourceList, currentGroupChannelList,
                playControlManager, panelManager, epgManagerWrapper, groupListManager, dateListManager, channelListManager);

        viewClickManager.bindDateClick(lvDate);
        viewClickManager.bindGroupClick(lvGroup);
        viewClickManager.bindChannelClick();

        btn_show_epg.setOnClickListener(v -> {
            if (!settingsManager.epg_enable) {
                android.widget.Toast.makeText(this, "节目单功能已关闭", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                currentSelectedDateIndex = dateListManager.getSelectedPosition();
                playControlManager.setCurrentDateIndex(currentSelectedDateIndex);
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        loadLiveAndEpg();
    }

    private void applyCustomUrls() {
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        LogUtils.log("【配置】直播源：" + UrlConfig.LIVE_URL);
        LogUtils.log("【配置】EPG：" + UrlConfig.EPG_URL);
    }

    public void loadLiveAndEpg() { /* 不变 */ }
    public void playPrev() { /* 不变 */ }
    public void playNext() { /* 不变 */ }
    public void togglePanel() { /* 不变 */ }
    public void openSettings() { /* 不变 */ }
    public void onReceiveConfig(String live, String epg) { /* 不变 */ }

    @Override
    protected void onResume() {
        super.onResume();
        LifecycleLogger.onResume();
        settingsManager.reloadConfig();
        screenRatioManager.apply();
        if (mPlayerManager != null) mPlayerManager.onForeground();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LifecycleLogger.onPause();
        if (mPlayerManager != null) mPlayerManager.onBackground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LifecycleLogger.onDestroy();
        broadcastManager.unregister();
        if (mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }

    // 提供外部访问方法
    public void setCurrentSelectedDateIndex(int index) { this.currentSelectedDateIndex = index; }
    public int getCurrentPlayIndex() { return currentPlayIndex; }
    public void setNowSelectGroup(String group) { this.nowSelectGroup = group; }
    public void setPlayerManager(TVPlayerManager manager) { this.mPlayerManager = manager; }
    public void setScreenRatioManager(ScreenRatioManager manager) { this.screenRatioManager = manager; }
}
