package com.tv.live;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import com.tv.live.manager.ChannelSwitchManager;
import com.tv.live.manager.GestureManager;
import com.tv.live.manager.KeyEventManager;
import com.tv.live.manager.PanelManager;
import com.tv.live.manager.ScreenRatioManager;
import com.tv.live.service.HttpConfigService;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public List<Channel> channelSourceList = new ArrayList<>();
    public int currentPlayIndex = 0;
    private View panel_layout;
    public TVPlayerManager mPlayerManager;
    private PlayerView playerView;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private PanelManager panelManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private HttpConfigService httpService;
    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PlayerStateListenerImpl playerStateListener;
    private ChannelSwitchManager switchManager;
    private boolean epgPanelOpen = false;
    private boolean isControllerVisible = false;
    private boolean epg_enable;
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean auto_update_source;

    private final BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(() -> {
                    loadSettings();
                    String customLive = appConfig.getCustomLiveUrl();
                    String customEpg = appConfig.getCustomEpgUrl();
                    if (customLive != null) UrlConfig.LIVE_URL = customLive;
                    if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                    loadLiveAndEpg();
                    Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(R.layout.activity_main);

        appConfig = AppConfig.getInstance(this);
        loadSettings();

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        panel_layout = findViewById(R.id.panel_layout);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROLLER"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // ========== 1. 打开节目单 → 强制刷新，无旧数据 ==========
        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) {
                Toast.makeText(this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
                return;
            }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList);
            }
        });

        // ========== 2. 点击日期 → 立刻刷新当前频道节目单 ==========
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            if (!channelSourceList.isEmpty()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList);
            }
        });

        // ========== 3. 点击分组 → 刷新对应频道列表（真正联动） ==========
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            String groupName = groupListManager.getCurrentGroup(position);
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
        });

        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();

        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // ========== 手势完全保留，没删任何一行 ==========
        gestureManager = new GestureManager(this);
        PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        keyEventManager = new KeyEventManager(this);
        httpService = HttpConfigService.getInstance();
        httpService.start();
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        loadLiveAndEpg();
        initListViewClick();
    }

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
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

    public void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList);
            }
        }));
    }

    public void playPrev() {
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    public void playNext() {
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    // ========== 4. 切换频道 → 节目单自动刷新 ==========
    // ========== 5. 点击频道 → 播放+EPG双刷新 ==========
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) return;

        playerStateListener.setCurrentChannelName(ch.getName());
        mPlayerManager.play(ch.getPlayUrl());
        appConfig.setLastPlayIndex(index);

        channelListManager.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList);
    }

    public void onReceiveConfig(String liveUrl, String epgUrl) {
        AppConfig.getInstance(this).setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        runOnUiThread(this::loadLiveAndEpg);
    }

    public void setSelectedDatePosition(int position) {}
    public void setSelectedGroupPosition(int position) {}

    public void setChannelsByGroup(int groupPosition, List<Channel> channels) {
        channelListManager.setChannels(channels, currentPlayIndex);
    }

    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> {
            switchManager.setCurrentIndex(pos);
            playChannel(pos);
            togglePanel();
        });
    }

    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        httpService.stop();
        mPlayerManager.release();
        mInstance = null;
    }
}
