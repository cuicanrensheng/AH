package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;

import com.tv.live.config.AppConfig;
import com.tv.live.manager.GestureManager;
import com.tv.live.manager.ScreenRatioManager;
import com.tv.live.manager.KeyEventManager;
import com.tv.live.manager.ChannelSwitchManager;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;

/**
 * ================================================
 * 主页面Activity（配套黑屏修复版）
 * 优化点：
 * 1. 开启缓冲显示，避免纯黑屏无反馈
 * 2. 缩短URL解析超时，减少加载等待
 * 3. 优化播放时序，确保视图绘制完成再加载
 * ================================================
 */
public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;

    // ====================== 频道数据 ======================
    public List<Channel> channelSourceList = new ArrayList<>();      // 全量频道列表
    public List<Channel> currentGroupChannelList = new ArrayList<>();// 当前分组频道列表
    public int currentPlayIndex = 0;                                // 当前播放频道下标
    private String nowSelectGroup = "";                             // 当前选中分组名

    private View panel_layout;                  // 频道列表面板
    public TVPlayerManager mPlayerManager;      // 播放器管理单例
    private PlayerView playerView;              // 播放视图
    private AppConfig appConfig;                // 配置管理
    private ScreenRatioManager screenRatioManager; // 画面比例管理

    private LivePanelManager.PanelManager panelManager;                // 面板总管理
    private GestureManager gestureManager;                            // 手势操作管理
    private KeyEventManager keyEventManager;                          // 遥控器按键管理
    private LivePanelManager.ChannelListManager channelListManager;   // 频道列表管理
    private LivePanelManager.GroupListManager groupListManager;       // 分组列表管理
    private LivePanelManager.DateListManager dateListManager;         // EPG日期管理
    private LivePanelManager.EpgManagerWrapper epgManagerWrapper;     // EPG节目单管理
    private PlayerStateListenerImpl playerStateListener;              // 播放状态监听实现
    private ChannelSwitchManager switchManager;                       // 频道切换管理

    private boolean epgPanelOpen = false;        // EPG面板是否展开
    private boolean epg_enable;                   // EPG功能开关
    private boolean channel_reverse;              // 切台方向反转
    private boolean number_channel_enable;        // 频道号显示开关
    private boolean auto_update_source;           // 自动更新源开关
    private int currentSelectedDateIndex = 0;     // 当前选中EPG日期下标
    private SharedPreferences sp;                 // 配置存储

    // ====================== 顶部信息栏控件 ======================
    private View info_bar;
    private TextView tv_channel_name;
    private TextView tv_tag_fhd;
    private TextView tv_tag_audio;
    private TextView tv_bitrate;
    private TextView tv_current_program_name;
    private TextView tv_current_time_range;
    private TextView tv_remaining_time;
    private TextView tv_next_program_name;
    public TextView tv_next_time_range;
    private ProgressBar progress_program;
    private TextView tv_channel_num;

    // ====================== 常量配置（已优化）======================
    // ✅ 优化：减少重定向次数、缩短超时，减少URL解析等待时间
    private static final int MAX_REDIRECT_COUNT = 5;   // 最大重定向次数
    private static final int CONNECT_TIMEOUT = 3000;   // 连接超时3秒
    private static final int READ_TIMEOUT = 3000;      // 读取超时3秒
    private static final String DEF_UA = "ExoPlayer";
    private static final String DEF_REFER = "https://www.huya.com/";
    private static final long CHANNEL_COOLDOWN = 300;   // 切台冷却300ms

    // 隐藏信息栏延时任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    private long lastChannelChangeTime = 0;       // 上次切台时间戳
    public static List<String> logList = new ArrayList<>(); // 全局日志列表

    /**
     * 全局日志记录（倒序存储，最多100条）
     */
    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
    }

    // ====================== 广播接收器 ======================
    // 切换播放器原生控制器显示/隐藏
    private final BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    // 刷新直播源和EPG广播
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

    private boolean isControllerVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        // 窗口配置：横屏、全屏、常亮、沉浸式状态栏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        setContentView(R.layout.activity_main);

        tv_channel_num = findViewById(R.id.tv_channel_num);
        initInfoBar();
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 加载自定义直播源/EPG地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        // ====================== PlayerView 渲染配置（黑屏优化）======================
        playerView = findViewById(R.id.player_view);
        // ✅ 优化：播放时显示缓冲转圈，用户能看到加载状态，不会误以为黑屏
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);

        // 彻底屏蔽原生控制器
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);
        playerView.setClickable(false);
        playerView.setLongClickable(false);
        playerView.setFocusable(false);
        playerView.setFocusableInTouchMode(false);
        playerView.setErrorMessageProvider(null);
        playerView.setShowRewindButton(false);
        playerView.setShowFastForwardButton(false);
        playerView.setShowPreviousButton(false);
        playerView.setShowNextButton(false);
        playerView.setKeepContentOnPlayerReset(true);

        // 低版本API兼容
        try { playerView.setControllerAutoShow(false); } catch (Exception ignored) {}
        try { playerView.setControllerHideOnTouch(false); } catch (Exception ignored) {}

        // ====================== 面板控件初始化 ======================
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // EPG展开/收起按钮
        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) {
                Toast.makeText(MainActivity.this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
                return;
            }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                currentSelectedDateIndex = dateListManager.getSelectedPosition();
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // 日期列表初始化
        dateListManager = new LivePanelManager.DateListManager(this, lvDate);
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;
            if (!channelSourceList.isEmpty()) {
                Channel currentChannel = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(currentChannel, channelSourceList, pos);
            }
        });

        // 频道列表初始化
        channelListManager = new LivePanelManager.ChannelListManager(this, lvChannelList);
        channelListManager.setOnChannelClickListener(filterPos -> {
            if (filterPos >= 0 && filterPos < currentGroupChannelList.size()) {
                Channel target = currentGroupChannelList.get(filterPos);
                int global = channelSourceList.indexOf(target);
                if (global != -1) {
                    playChannel(global);
                    togglePanel();
                }
            }
        });

        // 分组列表初始化
        groupListManager = new LivePanelManager.GroupListManager(this, lvGroup);
        groupListManager.setOnGroupChangeListener(groupName -> {
            if (TextUtils.isEmpty(groupName)) return;
            int position = groupListManager.getSelectedPos();
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            nowSelectGroup = groupName;
            // 过滤当前分组的频道
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (nowSelectGroup.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
        });

        epgManagerWrapper = new LivePanelManager.EpgManagerWrapper(this, lvEpg);
        panelManager = new LivePanelManager.PanelManager(panel_layout, channelListManager, epgManagerWrapper, dateListManager);

        // 初始化播放器并绑定视图
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

        // 画面比例管理
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 手势操作管理
        gestureManager = new GestureManager(this);
        PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            if (panel_layout.getVisibility() == View.VISIBLE) {
                return false;
            }
            gestureHelper.handleTouch(event);
            return true;
        });

        // 按键管理
        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        // ✅ 优化：等View绘制完成再加载播放，避免抢时序导致黑屏
        playerView.post(() -> {
            loadLiveAndEpg();
        });
    }

    /**
     * 初始化顶部信息栏控件
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
     * 加载用户配置项
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
    }

    /**
     * 返回键处理：先关面板，再退出
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
     * 加载直播源列表和EPG节目单
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

                // 恢复上次选中的分组
                if (!TextUtils.isEmpty(nowSelectGroup)) {
                    currentGroupChannelList.clear();
                    for (Channel ch : channelSourceList) {
                        if (ch.getGroup().equals(nowSelectGroup)) {
                            currentGroupChannelList.add(ch);
                        }
                    }
                    channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                } else {
                    // 默认选中第一个分组
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
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    /**
     * 切上一个频道
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        // 分组内切台
        if (!TextUtils.isEmpty(nowSelectGroup) && !currentGroupChannelList.isEmpty()) {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            int groupIndex = currentGroupChannelList.indexOf(currentChannel);
            if (groupIndex == -1) groupIndex = 0;

            int newGroupIndex;
            if (channel_reverse) {
                newGroupIndex = groupIndex + 1;
                if (newGroupIndex >= currentGroupChannelList.size()) {
                    newGroupIndex = 0;
                }
            } else {
                newGroupIndex = groupIndex - 1;
                if (newGroupIndex < 0) {
                    newGroupIndex = currentGroupChannelList.size() - 1;
                }
            }

            Channel targetChannel = currentGroupChannelList.get(newGroupIndex);
            int globalIndex = channelSourceList.indexOf(targetChannel);
            if (globalIndex != -1) {
                switchManager.setCurrentIndex(globalIndex);
                playChannel(globalIndex);
            }
            return;
        }

        // 全局切台
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 切下一个频道
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        // 分组内切台
        if (!TextUtils.isEmpty(nowSelectGroup) && !currentGroupChannelList.isEmpty()) {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            int groupIndex = currentGroupChannelList.indexOf(currentChannel);
            if (groupIndex == -1) groupIndex = 0;

            int newGroupIndex;
            if (channel_reverse) {
                newGroupIndex = groupIndex - 1;
                if (newGroupIndex < 0) {
                    newGroupIndex = currentGroupChannelList.size() - 1;
                }
            } else {
                newGroupIndex = groupIndex + 1;
                if (newGroupIndex >= currentGroupChannelList.size()) {
                    newGroupIndex = 0;
                }
            }

            Channel targetChannel = currentGroupChannelList.get(newGroupIndex);
            int globalIndex = channelSourceList.indexOf(targetChannel);
            if (globalIndex != -1) {
                switchManager.setCurrentIndex(globalIndex);
                playChannel(globalIndex);
            }
            return;
        }

        // 全局切台
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 播放指定频道（含URL重定向解析）
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        // 边界保护
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) return;

        playerStateListener.setCurrentChannelName(ch.getName());
        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);

        // 更新列表选中状态
        if (!TextUtils.isEmpty(nowSelectGroup)) {
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, index);
        } else {
            channelListManager.setChannels(channelSourceList, index);
        }

        // 刷新EPG
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 更新信息栏，2秒后自动隐藏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 3000);
            tv_channel_name.setText(ch.getName());
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
        }

        // URL重定向解析（子线程执行）
        final String originalUrl = ch.getPlayUrl();
        new Thread(() -> {
            java.net.HttpURLConnection conn = null;
            String finalUrl = originalUrl;

            SettingsActivity.log("🔗 开始解析：" + ch.getName());
            SettingsActivity.log("   原始URL：" + (originalUrl.length() > 600 ? originalUrl.substring(0, 600) + "..." : originalUrl));

            try {
                for (int step = 0; step < MAX_REDIRECT_COUNT; step++) {
                    java.net.URL urlObj = new java.net.URL(finalUrl);
                    conn = (java.net.HttpURLConnection) urlObj.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", DEF_UA);
                    conn.setRequestProperty("Referer", DEF_REFER);
                    conn.setInstanceFollowRedirects(false);
                    int code = conn.getResponseCode();

                    String shortUrl = finalUrl.length() > 600 ? finalUrl.substring(0, 600) + "..." : finalUrl;
                    SettingsActivity.log("   第" + (step + 1) + "次：HTTP " + code + " → " + shortUrl);

                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        if (loc != null) {
                            finalUrl = loc;
                            SettingsActivity.log("        重定向到：" + (loc.length() > 600 ? loc.substring(0, 600) + "..." : loc));
                        }
                        conn.disconnect();
                        conn = null;
                    } else {
                        SettingsActivity.log("   ✅ 解析完成，共" + (step + 1) + "次跳转");
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                SettingsActivity.log("   ❌ 解析异常：" + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }

            final String realPlayUrl = TextUtils.isEmpty(finalUrl) ? originalUrl : finalUrl;
            new Handler(Looper.getMainLooper()).post(() -> {
                mPlayerManager.playUrl(realPlayUrl);
            });
        }).start();
    }

    /**
     * 显示频道号，3秒自动隐藏
     */
    public void showChannelNum(int num) {
        if (!number_channel_enable) return;
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            tv_channel_num.setVisibility(View.GONE);
        }, 3000);
    }

    /**
     * 切换频道面板显示/隐藏
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    /**
     * 打开设置页面
     */
    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    /**
     * 接收配置更新（自定义源/EPG）
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appConfig.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        runOnUiThread(this::loadLiveAndEpg);
    }

    /**
     * 遥控器按键事件分发
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayerManager != null) mPlayerManager.onBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null) mPlayerManager.onForeground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 注销广播
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        // 释放播放器
        if (mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }

    public void playUrl(String url) {
    }
}
