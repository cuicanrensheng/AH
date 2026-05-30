package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

/**
 * 主界面：播放器 + 频道面板 + 节目单面板 全部逻辑
 */
public class MainActivity extends AppCompatActivity {
    // 单例实例
    public static MainActivity mInstance;

    // 全部频道源数据
    public List<Channel> channelSourceList = new ArrayList<>();

    // 当前正在播放的频道下标
    public int currentPlayIndex = 0;

    // 左侧面板（分组+频道+日期+节目单）
    private View panel_layout;

    // 播放器核心
    public TVPlayerManager mPlayerManager;
    private PlayerView playerView;

    // 配置管理
    private AppConfig appConfig;

    // 各个功能管理器
    private ScreenRatioManager screenRatioManager;    // 屏幕比例
    private PanelManager panelManager;                // 面板显示/隐藏
    private GestureManager gestureManager;            // 手势（音量/亮度）
    private KeyEventManager keyEventManager;          // 遥控器按键
    private HttpConfigService httpService;            // 网页配置后台

    // 四个列表管理器
    private ChannelListManager channelListManager;    // 频道列表
    private GroupListManager groupListManager;        // 频道分组
    private DateListManager dateListManager;          // 日期列表
    private EpgManagerWrapper epgManagerWrapper;      // 节目单列表

    // 播放状态监听
    private PlayerStateListenerImpl playerStateListener;

    // 频道切换管理（上一个/下一个）
    private ChannelSwitchManager switchManager;

    // 节目单面板是否打开
    private boolean epgPanelOpen = false;

    // 播放控制条显示状态（已废弃，但保留不崩溃）
    private boolean isControllerVisible = false;

    // ==============================================
    // 广播1：控制条开关（设置页发送）
    // ==============================================
    private final BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    // ==============================================
    // 广播2：刷新直播源 + EPG（设置页修改地址后发送）
    // ==============================================
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(() -> {
                    String customLive = appConfig.getCustomLiveUrl();
                    String customEpg = appConfig.getCustomEpgUrl();
                    if (customLive != null) UrlConfig.LIVE_URL = customLive;
                    if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                    // 重新加载频道和节目单
                    loadLiveAndEpg();
                    Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                });
            }
        }
    };

    // ==============================================
    // 创建界面
    // ==============================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        // 强制横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // 全屏 + 隐藏导航栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // 加载布局
        setContentView(R.layout.activity_main);

        // 读取用户自定义的直播源和EPG地址
        appConfig = AppConfig.getInstance(this);
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        // 绑定播放器控件
        playerView = findViewById(R.id.player_view);
        // 默认关闭系统控制条
        playerView.setUseController(false);

        // 绑定左侧面板
        panel_layout = findViewById(R.id.panel_layout);

        // 绑定四个列表
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);

        // 节目单按钮
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROLLER"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // ==============================================
        // 点击【节目单】按钮：显示/隐藏 日期+EPG
        // ==============================================
        btn_show_epg.setOnClickListener(v -> {
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);

            // 打开时立即刷新当前频道节目单，避免显示旧数据
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList);
            }
        });

        // ==============================================
        // 点击日期列表：切换日期 → 刷新节目单
        // ==============================================
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            // 记录选中的日期
            dateListManager.setSelectedPosition(position);
            // 刷新当前频道的节目单
            if (!channelSourceList.isEmpty()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList);
            }
        });

        // ==============================================
        // 点击分组：切换分组 → 刷新频道列表（核心修复）
        // ==============================================
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            groupListManager.setSelectedPosition(position);
            channelListManager.setChannelsByGroup(position, channelSourceList);
        });

        // 初始化四个列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);

        // 初始化日期列表（今天~6天后）
        dateListManager.initDate();

        // 面板管理
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 初始化播放器
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        // 播放状态监听（显示频道名、加载状态等）
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 屏幕比例
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 手势操作（手机/盒子通用）
        gestureManager = new GestureManager(this);
        PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        // 遥控器按键
        keyEventManager = new KeyEventManager(this);

        // 启动网页配置后台
        httpService = HttpConfigService.getInstance();
        httpService.start();

        // 频道切换上下台
        switchManager = ChannelSwitchManager.getInstance();

        // 读取上次播放的频道
        currentPlayIndex = appConfig.getLastPlayIndex();

        // 加载直播源 + EPG
        loadLiveAndEpg();

        // 初始化频道点击事件
        initListViewClick();
    }

    // ==============================================
    // 返回键：如果面板打开则先关闭面板
    // ==============================================
    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
            playerView.requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    // ==============================================
    // 加载直播源列表 + 加载EPG节目单
    // ==============================================
    public void loadLiveAndEpg() {
        // 加载直播频道
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);

                // 设置频道列表给切换管理器
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);

                Toast.makeText(MainActivity.this, "直播源加载完成：" + channelSourceList.size() + "个频道", Toast.LENGTH_SHORT).show();

                // 刷新分组、频道列表
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);

                // 播放当前频道
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
            // 加载完成后刷新当前频道节目单
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList);
            }
        }));
    }

    // ==============================================
    // 上一台
    // ==============================================
    public void playPrev() {
        int idx = switchManager.prev();
        playChannel(idx);
    }

    // ==============================================
    // 下一台
    // ==============================================
    public void playNext() {
        int idx = switchManager.next();
        playChannel(idx);
    }

    // ==============================================
    // 播放指定下标的频道（核心方法）
    // ==============================================
    public void playChannel(int index) {
        // 空数据直接返回
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 防止越界
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;

        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            Toast.makeText(this, "播放地址无效", Toast.LENGTH_SHORT).show();
            return;
        }

        // 设置当前频道名
        playerStateListener.setCurrentChannelName(ch.getName());

        // 开始播放
        mPlayerManager.play(ch.getPlayUrl());

        // 保存最后播放的频道
        appConfig.setLastPlayIndex(index);

        // 刷新频道列表选中状态
        channelListManager.setChannels(channelSourceList, index);

        // 同步刷新节目单（核心修复）
        epgManagerWrapper.refresh(ch, channelSourceList);
    }

    // ==============================================
    // 网页配置后台传来新的直播源/EPG地址
    // ==============================================
    public void onReceiveConfig(String liveUrl, String epgUrl) {
        AppConfig.getInstance(this).setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;

        runOnUiThread(() -> {
            Toast.makeText(this, "配置已保存，重新加载…", Toast.LENGTH_LONG).show();
            loadLiveAndEpg();
        });
    }

    // ==============================================
    // 打开/关闭 频道面板
    // ==============================================
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    // ==============================================
    // 打开设置页面
    // ==============================================
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ==============================================
    // 点击频道列表 → 播放并关闭面板
    // ==============================================
    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> {
            switchManager.setCurrentIndex(pos);
            // 播放选中频道
            playChannel(pos);
            // 同步刷新节目单
            epgManagerWrapper.refresh(channelSourceList.get(pos), channelSourceList);
            // 自动关闭面板
            togglePanel();
        });
    }

    // ==============================================
    // 遥控器按键分发
    // ==============================================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ==============================================
    // 页面恢复：重新应用屏幕比例
    // ==============================================
    @Override
    protected void onResume() {
        super.onResume();
        screenRatioManager.apply();
    }

    // ==============================================
    // 销毁：释放资源、注销广播
    // ==============================================
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
