package com.tv.live;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import com.tv.live.util.LifecycleLogger;
import com.tv.live.util.LogUtils;
import com.tv.live.widget.*;
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
    public ChannelSwitchManager switchManager;

    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    public PlayerStateListenerImpl playerStateListener;

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

        tv_channel_num = findViewById(R.id.tv_channel_num);
        panel_layout = findViewById(R.id.panel_layout);
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        appConfig = AppConfig.getInstance(this);
        settingsManager = new SettingsManager(this);
        applyCustomUrls();

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

        // ✅ 修复：删除 switchManager 参数
        playControlManager = new PlayControlManager(this, mPlayerManager, appConfig,
                tv_channel_num, infoBarManager, epgManagerWrapper, settingsManager, currentSelectedDateIndex);

        viewClickManager = new ViewClickManager(this, channelSourceList, currentGroupChannelList,
                playControlManager, panelManager, epgManagerWrapper, groupListManager, dateListManager, channelListManager);
        viewClickManager.bindDateClick(lvDate);
        viewClickManager.bindGroupClick(lvGroup);
        viewClickManager.bindChannelClick();

        btn_show_epg.setOnClickListener(v -> {
            if (!settingsManager.epg_enable) {
                Toast.makeText(this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
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

    public void loadLiveAndEpg() {
        LogUtils.log("【直播源】开始加载直播源...");
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                LogUtils.log("【直播源】加载成功，频道总数：" + channels.size());
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);

                if(!TextUtils.isEmpty(nowSelectGroup)){
                    currentGroupChannelList.clear();
                    for(Channel ch:channelSourceList){
                        if(ch.getGroup().equals(nowSelectGroup)) currentGroupChannelList.add(ch);
                    }
                    channelListManager.setChannelsByGroup(channelSourceList,nowSelectGroup,currentPlayIndex);
                }else{
                    List<String> groups = groupListManager.getGroupList();
                    if(groups != null && groups.size()>0){
                        nowSelectGroup = groups.get(0);
                        currentGroupChannelList.clear();
                        for(Channel ch:channelSourceList){
                            if(ch.getGroup().equals(nowSelectGroup)) currentGroupChannelList.add(ch);
                        }
                        channelListManager.setChannelsByGroup(channelSourceList,nowSelectGroup,currentPlayIndex);
                    }else {
                        channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    }
                }
                playControlManager.playChannel(currentPlayIndex, channelSourceList);
            }

            @Override
            public void onError(String errorMsg) {
                LogUtils.log("【直播源】加载失败：" + errorMsg);
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        LogUtils.log("【EPG】加载节目单：" + UrlConfig.EPG_URL);
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    public void playPrev() {
        currentPlayIndex = playControlManager.playPrev(channelSourceList, currentPlayIndex);
    }

    public void playNext() {
        currentPlayIndex = playControlManager.playNext(channelSourceList, currentPlayIndex);
    }

    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        LogUtils.log("【远程配置】更新直播源：" + liveUrl);
        LogUtils.log("【远程配置】更新EPG：" + epgUrl);
        runOnUiThread(this::loadLiveAndEpg);
    }

    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
            playerView.requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LifecycleLogger.onPause();
        if (mPlayerManager != null) mPlayerManager.onBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LifecycleLogger.onResume();
        settingsManager.reloadConfig();
        screenRatioManager.apply();
        if (mPlayerManager != null) mPlayerManager.onForeground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LifecycleLogger.onDestroy();
        broadcastManager.unregister();
        if(mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }

    public void setCurrentSelectedDateIndex(int index) { this.currentSelectedDateIndex = index; }
    public int getCurrentPlayIndex() { return currentPlayIndex; }
    public void setNowSelectGroup(String group) { this.nowSelectGroup = group; }
    public void setPlayerManager(TVPlayerManager manager) { this.mPlayerManager = manager; }
    public void setScreenRatioManager(ScreenRatioManager manager) { this.screenRatioManager = manager; }
}
