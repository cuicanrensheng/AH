package com.tv.live;

import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.GroupListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.SettingsActivity;
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
import com.tv.live.manager.*;
import com.tv.live.service.HttpConfigService;
import java.util.ArrayList;
import java.util.List;

/**
 * 主界面（电视直播全屏界面）
 * 功能：播放、换台、EPG、频道列表、手势、设置
 * 新增：播放时全局保持亮屏、阻止系统休眠
 */
public class MainActivity extends AppCompatActivity {
    // 当前实例（方便其他类调用）
    public static MainActivity mInstance;

    // 全部频道源数据
    public List<Channel> channelSourceList = new ArrayList<>();

    // 当前分组下的频道
    public List<Channel> currentGroupChannelList = new ArrayList<>();

    // 当前播放索引
    public int currentPlayIndex = 0;

    // 面板布局（频道列表、EPG）
    private View panel_layout;

    // 播放器核心管理类
    public TVPlayerManager mPlayerManager;

    // 播放器视图
    private PlayerView playerView;

    // 配置
    private AppConfig appConfig;

    // 画面比例管理器
    private ScreenRatioManager screenRatioManager;

    // 面板控制
    private PanelManager panelManager;

    // 手势控制
    private GestureManager gestureManager;

    // 按键事件
    private KeyEventManager keyEventManager;

    // 网络配置服务
    private HttpConfigService httpService;

    // 频道列表管理
    private ChannelListManager channelListManager;

    // 分组管理
    private GroupListManager groupListManager;

    // 日期管理（EPG）
    private DateListManager dateListManager;

    // EPG节目单管理
    private EpgManagerWrapper epgManagerWrapper;

    // 播放状态监听
    private PlayerStateListenerImpl playerStateListener;

    // 频道切换管理
    private ChannelSwitchManager switchManager;

    // EPG面板是否打开
    private boolean epgPanelOpen = false;

    // 是否显示控制条
    private boolean isControllerVisible = false;

    // 设置项
    private boolean epg_enable;         // EPG开关
    private boolean channel_reverse;    // 换台方向反转
    private boolean number_channel_enable; // 数字键选台
    private boolean auto_update_source; // 自动更新源

    // 当前选择的日期（EPG）
    private int currentSelectedDateIndex = 0;

    // 配置
    private SharedPreferences sp;

    // 播放器类型
    private int currentPlayerType;

    // 顶部信息条
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name, tv_next_time_range;
    private android.widget.ProgressBar progress_program;

    // 自动隐藏信息条任务
    private final Runnable hideInfoBar = () -> info_bar.setVisibility(View.GONE);

    // 广播：显示/隐藏控制条
    private final BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    // 广播：刷新直播源与EPG
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

        // 强制横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // 全屏 + 隐藏导航栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // 加载布局
        setContentView(R.layout.activity_main);

        // ======================== 【全局】保持屏幕常亮（最强休眠阻止） ========================
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 初始化顶部信息条
        initInfoBar();

        // 配置实例
        appConfig = AppConfig.getInstance(this);

        // 加载设置
        loadSettings();

        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        currentPlayerType = sp.getInt("player_engine", 0);

        // 自定义源地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        // 绑定播放器控件
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false); // 禁用默认控制条

        // 绑定面板
        panel_layout = findViewById(R.id.panel_layout);

        // 列表控件
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // EPG显示按钮
        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) { Toast.makeText(this, "节目单功能已关闭", Toast.LENGTH_SHORT).show(); return; }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // EPG日期切换
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            currentSelectedDateIndex = position;
            if (!channelSourceList.isEmpty()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // 分组点击
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            String groupName = groupListManager.getCurrentGroup(position);
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            if (!currentGroupChannelList.isEmpty()) {
                Channel firstChannel = currentGroupChannelList.get(0);
                int globalIndex = channelSourceList.indexOf(firstChannel);
                if (globalIndex != -1) playChannel(globalIndex);
            }
        });

        // 初始化各列表管理
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();

        // 面板管理
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 初始化播放器
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        // 播放状态监听
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 画面比例
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 手势
        gestureManager = new GestureManager(this);
        PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        // 按键
        keyEventManager = new KeyEventManager(this);

        // 网络配置服务
        httpService = HttpConfigService.getInstance();
        httpService.start();

        // 频道切换
        switchManager = ChannelSwitchManager.getInstance();

        // 读取上次播放记录
        currentPlayIndex = appConfig.getLastPlayIndex();

        // 加载直播源与EPG
        loadLiveAndEpg();

        // 列表点击事件
        initListViewClick();
    }

    /**
     * 初始化顶部信息条
     */
    private void initInfoBar() {
        info_bar = findViewById(R.id.info_bar);
        tv_channel_name = findViewById(R.id.tv_channel_name);
        tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        tv_tag_audio = findViewById(R.id.tv_tag_audio);
        tv_bitrate = findViewById(R.id.tv_bitrate);
        tv_current_program_name = findViewById(R.id.tv_current_program_name);
        tv_current_time_range = findViewById(R.id.tv_current_time_range);
        progress_program = findViewById(R.id.progress_program);
        tv_remaining_time = findViewById(R.id.tv_remaining_time);
        tv_next_program_name = findViewById(R.id.tv_next_program_name);
        tv_next_time_range = findViewById(R.id.tv_next_time_range);
    }

    /**
     * 加载设置
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
    }

    /**
     * 返回键逻辑
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

    /**
     * 加载直播源与EPG
     */
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
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    /**
     * 上一个频道
     */
    public void playPrev() {
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 下一个频道
     */
    public void playNext() {
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 播放指定索引频道
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) return;
        String url = ch.getPlayUrl();

        SettingsActivity.log("=== 播放 ===");
        SettingsActivity.log("频道：" + ch.getName());
        SettingsActivity.log("地址：" + url);

        playerStateListener.setCurrentChannelName(ch.getName());
        mPlayerManager.playUrl(url); // 调用播放器播放
        appConfig.setLastPlayIndex(index); // 保存最后播放记录
        channelListManager.setChannels(channelSourceList, index); // 更新列表选中状态
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex); // 刷新EPG

        // 显示信息条，2秒后自动隐藏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 2000);
            tv_channel_name.setText(ch.getName());
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
        }
    }

    /**
     * 频道列表点击
     */
    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> {
            if (!currentGroupChannelList.isEmpty() && pos < currentGroupChannelList.size()) {
                Channel selectedChannel = currentGroupChannelList.get(pos);
                int globalIndex = channelSourceList.indexOf(selectedChannel);
                if (globalIndex != -1) {
                    playChannel(globalIndex);
                    togglePanel();
                }
            } else {
                playChannel(pos);
                togglePanel();
            }
        });
    }

    /**
     * 显示/隐藏频道面板
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    /**
     * 打开设置
     */
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 接收远程配置
     */
    public void onReceiveConfig(String liveUrl, String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        runOnUiThread(this::loadLiveAndEpg);
    }

    /**
     * 按键事件
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayerManager != null) mPlayerManager.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null) mPlayerManager.resume();
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
