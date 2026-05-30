package com.tv.live;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        mInstance = this;
        setContentView(R.layout.activity_main);

        appConfig = AppConfig.getInstance(this);
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        PlayerView playerView = findViewById(R.id.player_view);
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);

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

        gestureManager = new GestureManager(this);
        PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        httpService = HttpConfigService.getInstance();
        httpService.start();

        currentPlayIndex = appConfig.getLastPlayIndex();
        loadLiveAndEpg();
        initListViewClick();
    }

    private void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                Toast.makeText(MainActivity.this, "直播源加载完成：" + channelSourceList.size() + "个频道", Toast.LENGTH_SHORT).show();
                groupListManager.setGroups(channelSourceList);
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "EPG节目单加载完成", Toast.LENGTH_SHORT).show();
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList);
            }
        }));
    }

    // ===================== 切台逻辑已经独立 =====================
    public void playPrev() {
        int idx = switchManager.prev();
        playChannel(idx);
    }

    public void playNext() {
        int idx = switchManager.next();
        playChannel(idx);
    }
    // ==========================================================

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        switchManager.setCurrentIndex(index);

        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            Toast.makeText(this, "播放地址无效", Toast.LENGTH_SHORT).show();
            return;
        }

        playerStateListener.setCurrentChannelName(ch.getName());
        mPlayerManager.play(ch.getPlayUrl());
        appConfig.setLastPlayIndex(index);
        channelListManager.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList);
    }

    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> {
            switchManager.setCurrentIndex(pos);
            playChannel(pos);
            togglePanel();
        });
    }

    // ===================== 遥控器逻辑已经独立 =====================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    // ==========================================================

    @Override
    protected void onResume() {
        super.onResume();
        screenRatioManager.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        httpService.stop();
        mPlayerManager.release();
        mInstance = null;
    }
}
