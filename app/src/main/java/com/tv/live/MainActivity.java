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
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 直播主页面Activity
 * 核心：全屏播放、频道切换、侧边面板、EPG节目单、底部信息栏、遥控器/手势控制
 * 已修改：1、永久屏蔽【正在播放：黑莓电影】悬浮白框  2、EPG从网络链接拉取数据自动填充底部节目
 */
public class MainActivity extends AppCompatActivity {

    // 页面单例
    public static MainActivity mInstance;

    // 全频道数据源
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前分组对应的频道集合
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放频道下标
    public int currentPlayIndex = 0;

    // 侧边频道/节目单总面板
    private View panel_layout;
    // ExoPlayer播放视图
    private PlayerView playerView;
    // 全局播放器管理类
    public TVPlayerManager mPlayerManager;
    // APP全局配置类
    private AppConfig appConfig;
    // 画面比例管理：原始/填充切换
    private ScreenRatioManager screenRatioManager;

    // 各类辅助管理器
    private PanelManager panelManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;

    // EPG面板开关标记
    private boolean epgPanelOpen = false;
    // 播放器原生控制器显隐标记
    private boolean isControllerVisible = false;

    // 配置项缓存
    private boolean epg_enable;         // EPG功能总开关
    private boolean channel_reverse;     // 切台方向反转
    private boolean number_channel_enable;//切台台号弹窗开关
    private boolean auto_update_source;  // 自动更新直播源

    // EPG选中日期下标
    private int currentSelectedDateIndex = 0;

    // 底部信息栏相关控件（频道名、清晰度、当前节目、下一档节目）
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name, tv_next_time_range;
    private android.widget.ProgressBar progress_program;
    // 切台中间弹出台号
    private TextView tv_channel_num;

    // 信息栏2秒后自动隐藏任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // 切台防重复点击冷却时间
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;

    // 全局日志集合
    public static List<String> logList = new ArrayList<>();

    /**
     * 全局日志打印，存入日志列表
     */
    public static void log(String msg) {
        logList.add(0, msg);
        // 日志最多保存100条，超出删除末尾
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }

    /**
     * 广播：切换播放器原生控制器显示/隐藏
     */
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    /**
     * 广播：接收设置页发来的刷新指令，重载直播源+EPG
     */
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadSettings();
                        // 读取自定义直播源、EPG链接
                        String customLive = appConfig.getCustomLiveUrl();
                        String customEpg = appConfig.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        loadLiveAndEpg();
                        Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】Activity创建");
        mInstance = this;

        // 固定横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 全屏+隐藏系统状态栏导航栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(R.layout.activity_main);
        // 屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 绑定台号控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        // 初始化底部信息栏所有控件
        initInfoBar();

        // 初始化配置管理
        appConfig = AppConfig.getInstance(this);
        loadSettings();

        // 读取本地保存的自定义源地址
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG网络地址：" + UrlConfig.EPG_URL);

        // 绑定播放器控件，关闭Exo自带控制器
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        // 绑定布局容器
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册系统广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // 初始化列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 初始化播放器并绑定播放视图
        mPlayerManager = TVPlayerManager.getInstance();
        mPlayerManager.attachPlayerView(playerView);

        // 播放器信息回调（清晰度、音轨信息）
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            }
        });

        // 初始化画面比例管理器
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        //====================【关键修改1：永久屏蔽“正在播放：黑莓电影”悬浮白框】====================
        // 直接隐藏弹窗控件，全程不再显示，不影响底部info_bar节目信息栏
        View playTipView = findViewById(R.id.layout_playing_tip);
        if (playTipView != null) {
            playTipView.setVisibility(View.GONE);
        }
        //====================================================================================

        // 初始化触摸手势
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        // 遥控器按键管理
        keyEventManager = new KeyEventManager(this);

        // 读取上次播放频道下标
        currentPlayIndex = appConfig.getLastPlayIndex();
        log("【播放记录】上次播放频道：" + currentPlayIndex);

        // 加载直播源和EPG数据
        loadLiveAndEpg();

        // 频道列表点击事件
        initListViewClick();
    }

    /**
     * 绑定底部信息栏控件：频道名、清晰度、当前节目、下一档节目
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
     * 读取本地设置项
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
        log("【设置】EPG开关:" + epg_enable + " | 切台反转:" + channel_reverse);
    }

    /**
     * 返回键：优先关闭侧边面板，再退出页面
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
     * 加载直播源 + EPG节目（EPG自动从UrlConfig.EPG_URL网络地址获取）
     */
    public void loadLiveAndEpg() {
        log("【直播源】开始拉取频道列表");
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【直播源】加载成功，总频道数：" + channels.size());
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                log("【直播源】加载失败：" + errorMsg);
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        log("【EPG】从网络地址加载节目：" + UrlConfig.EPG_URL);
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!channelSourceList.isEmpty()) {
                            epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
                        }
                    }
                });
            }
        });
    }

    /**
     * 上一个频道
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        int idx = channel_reverse ? ChannelSwitchManager.getInstance().next() : ChannelSwitchManager.getInstance().prev();
        playChannel(idx);
    }

    /**
     * 下一个频道
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        int idx = channel_reverse ? ChannelSwitchManager.getInstance().prev() : ChannelSwitchManager.getInstance().next();
        playChannel(idx);
    }

    /**
     * 播放指定下标频道【核心方法：切台+联网EPG+填充底部节目栏】
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        // 下标边界容错
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) return;

        // 播放器播放当前频道链接
        mPlayerManager.playUrl(ch.getPlayUrl());

        // 弹出台号
        showChannelNum(index + 1);

        // 保存当前播放下标到本地配置
        appConfig.setLastPlayIndex(index);

        // 刷新频道列表选中态
        channelListManager.setChannels(channelSourceList, index);

        //====================【关键修改2：EPG网络加载完毕自动填充底部节目信息】====================
        // 调用EPG刷新，内部自动访问配置的EPG网络链接
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
        // EPG网络请求完成回调，主线程更新UI
        epgManagerWrapper.setEpgLoadFinishCallback(() -> {
            runOnUiThread(() -> {
                // 获取当前节目、下一档节目实体
                var currEpg = epgManagerWrapper.getCurrentItem();
                var nextEpg = epgManagerWrapper.getNextItem();

                // 填充当前节目、时间段、剩余时长、进度条
                if (currEpg != null) {
                    tv_current_program_name.setText(currEpg.programName);
                    tv_current_time_range.setText(currEpg.timeStr);
                    tv_remaining_time.setText(epgManagerWrapper.getLeftTime());
                    progress_program.setProgress(epgManagerWrapper.getProgress());
                } else {
                    tv_current_program_name.setText("暂无节目信息");
                    tv_current_time_range.setText("--");
                    tv_remaining_time.setText("");
                    progress_program.setProgress(0);
                }

                // 填充下一档节目信息
                if (nextEpg != null) {
                    tv_next_program_name.setText(nextEpg.programName);
                    tv_next_time_range.setText(nextEpg.timeStr);
                } else {
                    tv_next_program_name.setText("暂无");
                    tv_next_time_range.setText("");
                }
            });
        });
        //=========================================================================================

        // 弹出底部信息栏（频道名、清晰度、码率，2秒自动消失）
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
     * 切台显示频道数字，3秒自动消失
     */
    public void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler().postDelayed(() -> tv_channel_num.setVisibility(View.GONE), 3000);
    }

    /**
     * 频道列表Item点击事件
     */
    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> {
            int globalIndex;
            if (!currentGroupChannelList.isEmpty() && pos < currentGroupChannelList.size()) {
                Channel selectedChannel = currentGroupChannelList.get(pos);
                globalIndex = channelSourceList.indexOf(selectedChannel);
            } else {
                globalIndex = pos;
            }
            if (globalIndex >= 0) playChannel(globalIndex);
            togglePanel();
        });
    }

    /**
     * 侧边频道面板显隐切换
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    /**
     * 跳转设置页面
     */
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 设置页回调，更新自定义直播源/EPG链接并刷新
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        runOnUiThread(this::loadLiveAndEpg);
    }

    /**
     * 遥控器按键分发
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    /**
     * APP切后台：暂停播放
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayerManager != null) mPlayerManager.onBackground();
    }

    /**
     * APP切前台：恢复播放、重载画面比例配置
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null) mPlayerManager.onForeground();
    }

    /**
     * 页面销毁：注销广播、释放播放器资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        mPlayerManager.release();
        mInstance = null;
    }
}
