package com.tv.live;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
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

/**
 * 主界面：播放器 + 频道列表 + EPG + 手势/按键控制
 * 已修复：手势失效、按键失效、编译报错、方法找不到
 */
public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;

    // 频道数据列表
    public final List<Channel> channelSourceList = new ArrayList<>();
    public final List<Channel> currentGroupChannelList = new ArrayList<>();

    // 当前播放索引与分类
    public int currentPlayIndex = 0;
    private String nowSelectGroup = "";
    private int currentSelectedDateIndex = 0;

    // 布局控件
    private View panel_layout;                // 侧边控制面板
    public PlayerView playerView;             // 播放器视图
    private TextView tv_channel_num;          // 频道号显示

    // 管理器
    public TVPlayerManager mPlayerManager;    // 播放器管理
    private AppConfig appConfig;              // 配置管理
    private ScreenRatioManager screenRatioManager; // 比例管理
    private KeyEventManager keyEventManager;  // 遥控器按键管理
    private GestureManager gestureManager;    // 手势管理
    public ChannelSwitchManager switchManager;// 频道切换管理

    // 列表适配器
    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;

    // 辅助功能
    public PlayerStateListenerImpl playerStateListener;
    public com.tv.live.manager.SettingsManager settingsManager;
    private InfoBarManager infoBarManager;
    private PlayControlManager playControlManager;
    private BroadcastManager broadcastManager;
    private ViewClickManager viewClickManager;
    private WidgetInitManager widgetInitManager;

    // EPG面板开关
    private boolean epgPanelOpen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LifecycleLogger.onCreate();
        mInstance = this;

        // 强制横屏 + 全屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 查找控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        panel_layout = findViewById(R.id.panel_layout);
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false); // 关闭系统默认控制器

        // 列表控件
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 初始化配置
        appConfig = AppConfig.getInstance(this);
        settingsManager = com.tv.live.manager.SettingsManager.getInstance(this);
        applyCustomUrls();

        // 注册广播
        broadcastManager = new BroadcastManager(this);
        broadcastManager.register();

        // 初始化信息条
        infoBarManager = new InfoBarManager(getWindow().getDecorView());
        widgetInitManager = new WidgetInitManager(this);

        // 初始化所有列表管理器
        channelListManager = widgetInitManager.createChannelListManager(lvChannelList);
        groupListManager = widgetInitManager.createGroupListManager(lvGroup);
        epgManagerWrapper = widgetInitManager.createEpgManagerWrapper(lvEpg);
        dateListManager = widgetInitManager.createDateListManager(lvDate);

        // 播放器状态回调
        playerStateListener = new PlayerStateListenerImpl(this);

        // ========== 初始化播放器核心 ==========
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 画面比例
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // ========== 初始化 按键 + 手势 ==========
        keyEventManager = new KeyEventManager(this);
        gestureManager = new GestureManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        // ========== 绑定手势（已修复：正确方法名） ==========
        playerView.setOnTouchListener((v, event) -> {
            gestureManager.handleTouch(event); // 修复：正确手势方法
            return true;
        });

        // 初始化播放控制
        playControlManager = new PlayControlManager(
                this, mPlayerManager, appConfig,
                tv_channel_num, infoBarManager, epgManagerWrapper,
                settingsManager, currentSelectedDateIndex
        );

        // 初始化点击事件
        viewClickManager = new ViewClickManager(
                this, channelSourceList, currentGroupChannelList,
                playControlManager, epgManagerWrapper,
                groupListManager, dateListManager, channelListManager
        );

        // 绑定各列表点击
        viewClickManager.bindDateClick(lvDate);
        viewClickManager.bindGroupClick(lvGroup);
        viewClickManager.bindChannelClick(lvChannelList); // 修复：传入ListView

        // EPG显示开关
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

        // 加载频道 + EPG
        loadLiveAndEpg();
    }

    /**
     * 应用用户自定义地址（直播源、EPG源）
     */
    private void applyCustomUrls() {
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        LogUtils.log("【配置】直播源：" + UrlConfig.LIVE_URL);
        LogUtils.log("【配置】EPG：" + UrlConfig.EPG_URL);
    }

    /**
     * 加载频道列表与EPG数据
     */
    public void loadLiveAndEpg() {
        LogUtils.log("【直播源】开始加载直播源...");
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                LogUtils.log("【直播源】加载成功，频道总数：" + channels.size());
                channelSourceList.clear();
                channelSourceList.addAll(channels);

                switchManager.setChannelListSize(channelSourceList.size());
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);

                // 加载分类下的频道
                if (!TextUtils.isEmpty(nowSelectGroup)) {
                    currentGroupChannelList.clear();
                    for (Channel ch : channelSourceList) {
                        if (ch.getGroup().equals(nowSelectGroup)) {
                            currentGroupChannelList.add(ch);
                        }
                    }
                    channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                } else {
                    // 默认加载全部
                    List<String> groups = groupListManager.getGroupList();
                    if (groups != null && groups.size() > 0) {
                        nowSelectGroup = groups.get(0);
                        currentGroupChannelList.clear();
                        for (Channel ch : channelSourceList) {
                            if (ch.getGroup().equals(nowSelectGroup)) {
                                currentGroupChannelList.add(ch);
                            }
                        }
                        channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                    } else {
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

        // 加载EPG
        LogUtils.log("【EPG】加载节目单：" + UrlConfig.EPG_URL);
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    /**
     * 上一个频道
     */
    public void playPrev() {
        currentPlayIndex = playControlManager.playPrev(channelSourceList, currentPlayIndex);
    }

    /**
     * 下一个频道
     */
    public void playNext() {
        currentPlayIndex = playControlManager.playNext(channelSourceList, currentPlayIndex);
    }

    /**
     * 打开设置界面
     */
    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    /**
     * 远程配置更新后自动重新加载
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        LogUtils.log("【远程配置】更新直播源：" + liveUrl);
        LogUtils.log("【远程配置】更新EPG：" + epgUrl);
        runOnUiThread(this::loadLiveAndEpg);
    }

    /**
     * 返回键：优先关闭面板
     */
    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
            playerView.requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    // ========== ✅ 核心修复：手势/按键 开关面板 ==========
    public void togglePanel() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
        } else {
            panel_layout.setVisibility(View.VISIBLE);
        }
        playerView.requestFocus();
    }

    /**
     * 遥控器按键监听（已修复：正确调用 dispatchKey）
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) {
            return true;
        }
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
        if (mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }

    // ========== 以下为外部调用接口 ==========
    public void setCurrentSelectedDateIndex(int index) {
        this.currentSelectedDateIndex = index;
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public void setNowSelectGroup(String group) {
        this.nowSelectGroup = group;
    }

    public void playChannel(int position) {
        playControlManager.playChannel(position, channelSourceList);
    }
}
