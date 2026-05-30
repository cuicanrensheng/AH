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
import com.tv.live.manager.GestureManager;
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

    // 配置管理
    private AppConfig appConfig;

    // 管理类
    private ScreenRatioManager screenRatioManager;
    private PanelManager panelManager;
    private GestureManager gestureManager;
    private HttpConfigService httpService;

    // UI 列表
    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;

    // 播放器状态
    private PlayerStateListenerImpl playerStateListener;

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

        // ========== 1. 初始化配置 ==========
        appConfig = AppConfig.getInstance(this);
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        // ========== 2. 视图绑定 ==========
        PlayerView playerView = findViewById(R.id.player_view);
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);

        // ========== 3. 初始化 UI 列表管理 ==========
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();

        // ========== 4. 初始化面板管理 ==========
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // ========== 5. 初始化播放器 ==========
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // ========== 6. 初始化屏幕比例 ==========
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // ========== 7. 初始化手势 ==========
        gestureManager = new GestureManager(this);
        PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        // ========== 8. 初始化 HTTP 服务 ==========
        httpService = HttpConfigService.getInstance();
        httpService.start();

        // ========== 9. 加载直播源和节目单 ==========
        currentPlayIndex = appConfig.getLastPlayIndex();
        loadLiveAndEpg();
        initListViewClick();
    }

    // ========== 加载直播源 ==========
    private void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                Toast.makeText(MainActivity.this, "直播源加载完成：" + channelSourceList.size() + "个频道", Toast.LENGTH_SHORT).show();
                groupListManager.setGroups(channelSourceList);
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        // 加载EPG节目单
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "EPG节目单加载完成", Toast.LENGTH_SHORT).show();
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList);
            }
        }));
    }

    // ========== 切台逻辑 ==========
    private void playPrev() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIndex = (currentPlayIndex - 1 + channelSourceList.size()) % channelSourceList.size();
        playChannel(newIndex);
    }

    private void playNext() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIndex = (currentPlayIndex + 1) % channelSourceList.size();
        playChannel(newIndex);
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;

        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            Toast.makeText(this, "播放地址无效", Toast.LENGTH_SHORT).show();
            return;
        }

        // 更新播放器状态监听的频道名
        playerStateListener.setCurrentChannelName(ch.getName());
        if (mPlayerManager != null) {
            mPlayerManager.play(ch.getPlayUrl());
        }

        // 保存索引
        appConfig.setLastPlayIndex(index);
        channelListManager.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList);
    }

    // ========== 面板控制 ==========
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ========== 列表点击事件 ==========
    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> {
            playChannel(pos);
            togglePanel();
        });
    }

    // ========== 遥控器按键 ==========
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                playPrev();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                playNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_MENU:
                openSettings();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        screenRatioManager.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        httpService.stop();
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }
        mInstance = null;
    }
}
