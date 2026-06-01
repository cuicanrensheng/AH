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
import android.os.Handler;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 主页面 = 播放页面
 * 负责：直播播放、频道列表、切台、节目单、设置、手势、遥控器
 */
public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance; // 单例

    // 所有频道数据源（自动从直播源获取生成）
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前分组下的频道列表
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放的频道索引
    public int currentPlayIndex = 0;

    private View panel_layout; // 频道面板
    public TVPlayerManager mPlayerManager; // 播放器核心
    private PlayerView playerView; // 播放画面控件
    private AppConfig appConfig; // 配置

    // 各种管理器
    private ScreenRatioManager screenRatioManager; // 画面比例
    private PanelManager panelManager; // 面板管理
    private GestureManager gestureManager; // 手势
    private KeyEventManager keyEventManager; // 按键

    // 列表相关
    private ChannelListManager channelListManager; // 频道列表
    private GroupListManager groupListManager; // 分组
    private DateListManager dateListManager; // 日期
    private EpgManagerWrapper epgManagerWrapper; // 节目单

    // 播放状态监听
    private PlayerStateListenerImpl playerStateListener;
    // 频道切换管理
    private ChannelSwitchManager switchManager;

    // 面板状态
    private boolean epgPanelOpen = false;
    private boolean isControllerVisible = false;

    // 设置项
    private boolean epg_enable;
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean auto_update_source;

    private int currentSelectedDateIndex = 0;
    private SharedPreferences sp;

    // 顶部信息栏
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name, tv_next_time_range;
    private android.widget.ProgressBar progress_program;

    // 右上角频道号
    private TextView tv_channel_num;

    // 延迟隐藏信息栏
    private final Runnable hideInfoBar = () -> info_bar.setVisibility(View.GONE);

    // ====================== 【BUG修复】切台防抖、防飞台变量 ======================
    private long lastChannelChangeTime = 0; // 上次切台时间
    private static final long CHANNEL_COOLDOWN = 300; // 300毫秒内只能切一次
    private float touchStartY = 0; // 触摸起点Y
    private static final float SLIDE_THRESHOLD = 80; // 滑动超过80px才切台

    // ====================== 广播接收 ======================
    // 开关控制器
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    // 刷新直播源
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
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

    // ====================== 页面创建 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        // 强制横屏、全屏、隐藏导航栏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // 亮屏

        // 绑定右上角频道号控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        initInfoBar(); // 初始化信息栏

        // 配置初始化
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 自定义直播源地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        // 播放控件初始化
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false); // 关闭系统控制器
        panel_layout = findViewById(R.id.panel_layout);

        // 列表控件绑定
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // 节目单面板开关
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
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // 日期切换
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            currentSelectedDateIndex = position;
            if (!channelSourceList.isEmpty()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // 分组切换
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
                if (globalIndex != -1) {
                    playChannel(globalIndex);
                }
            }
        });

        // 初始化各种列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();

        // 面板管理
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // ====================== 播放器初始化 ======================
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView); // 绑定画面
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener); // 状态回调

        // 直播信息更新
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

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

        // 频道切换
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        // 加载直播源 + 节目单
        loadLiveAndEpg();
        initListViewClick();
    }

    // ====================== 初始化顶部信息栏 ======================
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

    // ====================== 加载设置 ======================
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
    }

    // ====================== 返回键 ======================
    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
            playerView.requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    // ====================== 加载直播源和节目单 ======================
    public void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels); // 自动生成频道列表
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                playChannel(currentPlayIndex); // 自动播放
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        // 加载节目单
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    // ====================== 【修复】上一台（防抖+防飞） ======================
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return; // 时间不够，忽略
        lastChannelChangeTime = now;

        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    // ====================== 【修复】下一台（防抖+防飞） ======================
    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return; // 时间不够，忽略
        lastChannelChangeTime = now;

        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    // ====================== 播放指定频道（核心方法） ======================
    public void playChannel(int index) {
        // 数据为空直接返回
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 边界保护：索引不能小于0，不能大于列表最大位置
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;

        // 获取当前频道对象
        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) return;

        // 获取播放地址
        String url = ch.getPlayUrl();
        playerStateListener.setCurrentChannelName(ch.getName());

        // 开始播放
        mPlayerManager.playUrl(url);
        // 显示频道号
        showChannelNum(index + 1);
        // 保存最后播放位置
        appConfig.setLastPlayIndex(index);

        // 刷新列表与节目单
        channelListManager.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 显示信息栏，2秒后隐藏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 2000);
            tv_channel_name.setText(ch.getName());

            // 更新画质、音频、码率
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
        }
    }

    // ====================== 显示频道号，3秒后消失 ======================
    public void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler().postDelayed(() -> tv_channel_num.setVisibility(View.GONE), 3000);
    }

    // ====================== 频道列表点击 ======================
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

    // ====================== 开关频道面板 ======================
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    // ====================== 打开设置 ======================
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ====================== 接收配置更新 ======================
    public void onReceiveConfig(String liveUrl, String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        runOnUiThread(this::loadLiveAndEpg);
    }

    // ====================== 按键分发 ======================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    // ====================== 【修复】切后台 ======================
    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    // ====================== 【修复】回到前台 ======================
    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null)
            mPlayerManager.onForeground(); // 恢复播放，不黑屏
    }

    // ====================== 页面销毁 ======================
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        mPlayerManager.release(); // 释放播放器
        mInstance = null;
    }
}
