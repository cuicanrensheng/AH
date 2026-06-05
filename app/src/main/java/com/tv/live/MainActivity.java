package com.tv.live;

import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.GroupListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.SettingsActivity;
import com.tv.live.config.AppConfig;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 播放器主界面
 * 保留所有原版逻辑 | 仅修复：分组刷新、日期EPG刷新、打开节目单刷新
 * 不删除任何功能 | 保留 UrlConfig | 不含投屏 | 不含分组自动播放
 */
public class MainActivity extends AppCompatActivity {

    // 单例实例
    public static MainActivity mInstance;

    // 全频道源数据
    public List<Channel> channelSourceList = new ArrayList<>();

    // 当前分组下的频道列表
    public List<Channel> currentGroupChannelList = new ArrayList<>();

    // 当前正在播放的频道索引
    public int currentPlayIndex = 0;

    // 当前选中的分组名称
    private String nowSelectGroup = "";

    // 主面板布局
    private View panel_layout;

    // 播放器核心管理类
    public TVPlayerManager mPlayerManager;

    // 播放器视图
    private PlayerView playerView;

    // 配置类
    private AppConfig appConfig;

    // 画面比例管理
    private ScreenRatioManager screenRatioManager;

    // 面板管理（左侧频道栏 + 右侧EPG）
    private PanelManager panelManager;

    // 手势操作管理
    private GestureManager gestureManager;

    // 按键事件管理
    private KeyEventManager keyEventManager;

    // 频道列表适配器管理
    private ChannelListManager channelListManager;

    // 分组列表适配器管理
    private GroupListManager groupListManager;

    // 日期选择适配器管理
    private DateListManager dateListManager;

    // 节目单(EPG)适配器管理
    private EpgManagerWrapper epgManagerWrapper;

    // 播放器状态监听器
    private PlayerStateListenerImpl playerStateListener;

    // 频道切换管理
    private ChannelSwitchManager switchManager;

    // EPG面板是否显示
    private boolean epgPanelOpen = false;

    // 播放器控制器是否显示
    private boolean isControllerVisible = false;

    // 是否启用EPG功能
    private boolean epg_enable;

    // 频道切换方向是否反转
    private boolean channel_reverse;

    // 是否显示频道号
    private boolean number_channel_enable;

    // 是否自动更新源
    private boolean auto_update_source;

    // 当前选中的日期索引
    private int currentSelectedDateIndex = 0;

    // 配置存储
    private SharedPreferences sp;

    // 顶部信息栏相关
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name;
    private android.widget.ProgressBar progress_program;
    private TextView tv_channel_num;

    // ———— 常量配置 ————
    // 最大重定向次数
    private static final int MAX_REDIRECT_COUNT = 10;
    // 连接超时
    private static final int CONNECT_TIMEOUT = 8000;
    // 读取超时
    private static final int READ_TIMEOUT = 8000;
    // 默认UA
    private static final String DEF_UA = "ExoPlayer";
    // 默认REFERER
    private static final String DEF_REFER = "https://www.huya.com/";
    // 频道切换冷却时间
    private static final long CHANNEL_COOLDOWN = 300;
    // 手势滑动阈值
    private static final long SLIDE_THRESHOLD = 80;

    // 隐藏信息栏的Runnable
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // 上一次切换频道的时间
    private long lastChannelChangeTime = 0;

    // 日志列表
    public static List<String> logList = new ArrayList<>();

    /**
     * 输出日志
     */
    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
    }

    /**
     * 广播：控制显示/隐藏播放器控制器
     */
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(false);
            playerView.hideController();
        }
    };

    /**
     * 广播：刷新直播源和EPG
     */
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(() -> {
                    loadSettings();
                    String customLive = appConfig.getCustomLiveUrl();
                    String customEpg = appConfig.getCustomEpgUrl();

                    // ———— 保留 UrlConfig 赋值 ————
                    if (customLive != null) {
                        UrlConfig.LIVE_URL = customLive;
                    }
                    if (customEpg != null) {
                        UrlConfig.EPG_URL = customEpg;
                    }

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

        // 全屏 + 保持亮屏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 沉浸式状态栏
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_main);

        // 初始化控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        initInfoBar();

        // 初始化配置
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 同步自定义地址到 UrlConfig
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) {
            UrlConfig.LIVE_URL = customLive;
        }
        if (customEpg != null) {
            UrlConfig.EPG_URL = customEpg;
        }

        // 初始化播放器
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);
        playerView.setControllerAutoShow(false);
        playerView.hideController();

        // 控制器自动隐藏
        playerView.setControllerVisibilityListener(visibility -> {
            if (visibility == View.VISIBLE) {
                playerView.hideController();
            }
        });

        // 初始化布局
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // ———— 节目单按钮点击 ————
        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) {
                Toast.makeText(MainActivity.this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
                return;
            }

            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);

            // ———— 修复点1：打开EPG时强制刷新节目单 ————
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                currentSelectedDateIndex = dateListManager.getSelectedPosition();
                epgManagerWrapper = new EpgManagerWrapper(MainActivity.this, lvEpg);
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        // ———— 日期选择初始化 ————
        dateListManager = new DateListManager(this, lvDate);
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;

            // ———— 修复点2：切换日期强制刷新节目单 ————
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper = new EpgManagerWrapper(MainActivity.this, lvEpg);
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, pos);
            }
        });

        // ———— 分组点击切换 ————
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            nowSelectGroup = groupListManager.getCurrentGroup(position);

            // 刷新当前分组频道列表
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (nowSelectGroup.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }

            // ———— 修复点3：切换分组强制刷新频道列表 ————
            channelListManager = new ChannelListManager(MainActivity.this, lvChannelList);
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
        });

        // ———— 频道列表点击 ————
        channelListManager = new ChannelListManager(this, lvChannelList);
        channelListManager.setOnChannelClickListener(filterPos -> {
            if (filterPos >= 0 && filterPos < currentGroupChannelList.size()) {
                Channel ch = currentGroupChannelList.get(filterPos);
                int globalIdx = channelSourceList.indexOf(ch);
                if (globalIdx != -1) {
                    playChannel(globalIdx);
                    togglePanel();
                }
            }
        });

        // 初始化各管理器
        groupListManager = new GroupListManager(this, lvGroup);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 播放器初始化
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.getQuality());
            tv_tag_audio.setText(info.getAudioTrack());
            tv_bitrate.setText(info.getBitrate());
        });

        // 画面比例
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 手势
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        // 按键
        keyEventManager = new KeyEventManager(this);

        // 频道切换
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        // 加载直播源
        loadLiveAndEpg();
    }

    /**
     * 初始化顶部信息栏
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
    }

    /**
     * 加载配置
     */
    private void loadSettings() {
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
    }

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
     * 加载直播源 + EPG
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

                // 初始化分组
                if (!TextUtils.isEmpty(nowSelectGroup)) {
                    currentGroupChannelList.clear();
                    for (Channel ch : channelSourceList) {
                        if (ch.getGroup().equals(nowSelectGroup)) {
                            currentGroupChannelList.add(ch);
                        }
                    }
                    channelListManager = new ChannelListManager(MainActivity.this, findViewById(R.id.lv_channel_list));
                    channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                } else {
                    List<String> groups = groupListManager.getGroupList();
                    if (groups != null && groups.size() > 0) {
                        nowSelectGroup = groups.get(0);
                        currentGroupChannelList.clear();
                        for (Channel ch : channelSourceList) {
                            if (ch.getGroup().equals(nowSelectGroup)) {
                                currentGroupChannelList.add(ch);
                            }
                        }
                        channelListManager = new ChannelListManager(MainActivity.this, findViewById(R.id.lv_channel_list));
                        channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                    } else {
                        channelListManager = new ChannelListManager(MainActivity.this, findViewById(R.id.lv_channel_list));
                        channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    }
                }

                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String err) {
                Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show();
            }
        });

        // 加载EPG
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper = new EpgManagerWrapper(MainActivity.this, findViewById(R.id.lv_epg));
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    /**
     * 上一个频道
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 下一个频道
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 播放指定索引频道
     */
    public void playChannel(int index) {
        if (channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;

        Channel ch = channelSourceList.get(index);
        if (TextUtils.isEmpty(ch.getPlayUrl())) return;

        final String originalUrl = ch.getPlayUrl();

        // 子线程处理重定向
        new Thread(() -> {
            String realUrl = originalUrl;
            HttpURLConnection conn = null;

            try {
                for (int i = 0; i < MAX_REDIRECT_COUNT; i++) {
                    URL url = new URL(realUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", DEF_UA);
                    conn.setInstanceFollowRedirects(false);

                    int code = conn.getResponseCode();
                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        if (loc != null) realUrl = loc;
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (conn != null) conn.disconnect();
            }

            // 切主线程播放
            String finalPlayUrl = TextUtils.isEmpty(realUrl) ? originalUrl : realUrl;
            new Handler(Looper.getMainLooper()).post(() -> {
                mPlayerManager.playUrl(finalPlayUrl);
            });
        }).start();

        // 显示频道号
        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);

        // 刷新频道列表选中状态
        if (!TextUtils.isEmpty(nowSelectGroup)) {
            channelListManager = new ChannelListManager(MainActivity.this, findViewById(R.id.lv_channel_list));
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, index);
        } else {
            channelListManager = new ChannelListManager(MainActivity.this, findViewById(R.id.lv_channel_list));
            channelListManager.setChannels(channelSourceList, index);
        }

        // 刷新节目单
        epgManagerWrapper = new EpgManagerWrapper(MainActivity.this, findViewById(R.id.lv_epg));
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 更新信息栏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 2000);

            tv_channel_name.setText(ch.getName());
            TVPlayerManager.LiveInfo info = mPlayerManager.getLiveInfo();
            if (info != null) {
                tv_tag_fhd.setText(info.getQuality());
                tv_tag_audio.setText(info.getAudioTrack());
                tv_bitrate.setText(info.getBitrate());
            }
        }
    }

    /**
     * 显示频道号
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
     * 开关左侧面板
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    /**
     * 打开设置
     */
    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    /**
     * 接收配置更新
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appConfig.setCustomUrls(liveUrl, epgUrl);

        // 保留 UrlConfig
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;

        runOnUiThread(this::loadLiveAndEpg);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatch(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayerManager != null) {
            mPlayerManager.onBackground();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null) {
            mPlayerManager.onForeground();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(toggleControllerReceiver);
            unregisterReceiver(refreshReceiver);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (mPlayerManager != null) {
            mPlayerManager.release();
        }

        mInstance = null;
    }
}
