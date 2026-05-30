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

// ============== 以下全部是【独立出去的模块】，已经和MainActivity解耦 ==============
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

    // ============== 所有独立模块的实例，MainActivity只负责调度 ==============
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

        // ========== 1. 配置管理（独立模块：config/AppConfig.java） ==========
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

        // ========== 3. UI列表管理（独立模块：widget/下的4个文件） ==========
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();

        // ========== 4. 面板控制（独立模块：manager/PanelManager.java） ==========
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // ========== 5. 播放器 ==========
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        // 播放器状态监听（独立模块：listener/PlayerStateListenerImpl.java）
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // ========== 6. 屏幕比例控制（独立模块：manager/ScreenRatioManager.java） ==========
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // ========== 7. 手势控制（独立模块：manager/GestureManager.java） ==========
        gestureManager = new GestureManager(this);
        PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        // ========== 8. 遥控器按键（独立模块：manager/KeyEventManager.java） ==========
        keyEventManager = new KeyEventManager(this);

        // ========== 9. HTTP配置服务（独立模块：service/HttpConfigService.java） ==========
        httpService = HttpConfigService.getInstance();
        httpService.start();

        // ========== 10. 切台索引管理（独立模块：manager/ChannelSwitchManager.java） ==========
        switchManager = ChannelSwitchManager.getInstance();

        // 读取上次播放索引
        currentPlayIndex = appConfig.getLastPlayIndex();
        loadLiveAndEpg();
        initListViewClick();
    }

    // ========== 直播源加载（独立模块：loader/LiveSourceLoader.java） ==========
    private void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                // 把频道列表交给独立的切台管理器
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

        // 加载EPG节目单（防崩溃逻辑在 widget/EpgManagerWrapper.java 中）
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "EPG节目单加载完成", Toast.LENGTH_SHORT).show();
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList);
            }
        }));
    }

    // ========== 切台逻辑：调用独立模块 ChannelSwitchManager ==========
    public void playPrev() {
        int idx = switchManager.prev();
        playChannel(idx);
    }

    public void playNext() {
        int idx = switchManager.next();
        playChannel(idx);
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;

        // 同步索引到独立的切台管理器
        switchManager.setCurrentIndex(index);

        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            Toast.makeText(this, "播放地址无效", Toast.LENGTH_SHORT).show();
            return;
        }

        // 更新播放器状态监听的频道名（独立模块：PlayerStateListenerImpl）
        playerStateListener.setCurrentChannelName(ch.getName());
        if (mPlayerManager != null) {
            mPlayerManager.play(ch.getPlayUrl());
        }

        // 保存索引（配置读写在独立模块：AppConfig）
        appConfig.setLastPlayIndex(index);

        // 更新频道列表和EPG（独立模块：ChannelListManager / EpgManagerWrapper）
        channelListManager.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList);
    }

    // ========== 面板控制：调用独立模块 PanelManager ==========
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
            // 点击列表时同步索引到独立的切台管理器
            switchManager.setCurrentIndex(pos);
            playChannel(pos);
            togglePanel();
        });
    }

    // ========== 遥控器按键：调用独立模块 KeyEventManager ==========
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刷新屏幕比例（独立模块：ScreenRatioManager）
        screenRatioManager.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停止HTTP服务（独立模块：HttpConfigService）
        httpService.stop();
        // 释放播放器
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }
        mInstance = null;
    }
}
