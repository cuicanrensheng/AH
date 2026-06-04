package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
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
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import com.tv.live.widget.*;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public List<Channel> channelSourceList;
    public List<Channel> currentGroupChannelList;
    public int currentPlayIndex = 0;

    private View panel_layout;
    private PlayerView playerView;
    private View info_bar;

    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_channel_num;

    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private PanelManager panelManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private ChannelSwitchManager switchManager;
    private ScreenRatioManager screenRatioManager;

    private boolean epgPanelOpen = false;
    private boolean epg_enable;
    private boolean channel_reverse;
    private int currentSelectedDateIndex = 0;

    private final Runnable hideInfoBar = () -> info_bar.setVisibility(View.GONE);
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(() -> {
                    loadSettings();
                    screenRatioManager.apply();
                    String customLive = appConfig.getCustomLiveUrl();
                    String customEpg = appConfig.getCustomEpgUrl();
                    if (customLive != null) UrlConfig.LIVE_URL = customLive;
                    if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                    loadLiveAndEpg();
                    Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        panel_layout = findViewById(R.id.panel_layout);
        info_bar = findViewById(R.id.info_bar);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        tv_channel_name = findViewById(R.id.tv_channel_name);
        tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        tv_tag_audio = findViewById(R.id.tv_tag_audio);
        tv_bitrate = findViewById(R.id.tv_bitrate);
        tv_channel_num = findViewById(R.id.tv_channel_num);

        appConfig = AppConfig.getInstance(this);
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);

        loadSettings();
        screenRatioManager.apply();

        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        channelListManager.setOnChannelClickListener(position -> {
            if (currentGroupChannelList != null && position < currentGroupChannelList.size()) {
                int realIndex = channelSourceList.indexOf(currentGroupChannelList.get(position));
                if (realIndex != -1) {
                    playChannel(realIndex);
                    panel_layout.setVisibility(View.GONE);
                }
            }
        });

        dateListManager.setOnDateSelectedListener(position -> {
            currentSelectedDateIndex = position;
            if (channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) {
                Toast.makeText(this, "节目单已关闭", Toast.LENGTH_SHORT).show();
                return;
            }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            if (epgPanelOpen && channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            String groupName = groupListManager.getCurrentGroup(position);
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            if (!currentGroupChannelList.isEmpty()) {
                int firstIndex = channelSourceList.indexOf(currentGroupChannelList.get(0));
                if (firstIndex != -1) playChannel(firstIndex);
            }
        });

        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

        gestureManager = new GestureManager(this);
        PlayerGestureHelper helper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            helper.handleTouch(event);
            return true;
        });

        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        currentGroupChannelList = new ArrayList<>();
        loadLiveAndEpg();
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));
    }

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
    }

    public void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList = channels;
                currentGroupChannelList.clear();
                currentGroupChannelList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
            }
        });

        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    public void playChannel(int index) {
        if (channelSourceList == null || index < 0 || index >= channelSourceList.size()) return;
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (TextUtils.isEmpty(ch.getPlayUrl())) return;

        mPlayerManager.playUrl(ch.getPlayUrl());
        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);
        channelListManager.setSelectedPosition(currentGroupChannelList.indexOf(ch));
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        info_bar.setVisibility(View.VISIBLE);
        info_bar.removeCallbacks(hideInfoBar);
        info_bar.postDelayed(hideInfoBar, 2000);
        tv_channel_name.setText(ch.getName());
    }

    public void playPrev() {
        if (System.currentTimeMillis() - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = System.currentTimeMillis();
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    public void playNext() {
        if (System.currentTimeMillis() - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = System.currentTimeMillis();
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    private void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler().postDelayed(() -> tv_channel_num.setVisibility(View.GONE), 3000);
    }

    public void togglePanel() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
        } else {
            panel_layout.setVisibility(View.VISIBLE);
        }
    }

    public void openSettings() {
        try {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "设置无法打开", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (keyEventManager.dispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPlayerManager.onBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
        mPlayerManager.onForeground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(refreshReceiver);
        mPlayerManager.release();
    }
}
