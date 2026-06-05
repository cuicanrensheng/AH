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
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 直播主页主Activity
 * 核心逻辑：后台切出自动暂停、打开设置不暂停不断播、屏蔽exo原生控制面板
 */
public class MainActivity extends AppCompatActivity {
    // 全局单例
    public static MainActivity mInstance;
    // 全量频道数据源
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前分组下的频道集合
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放的频道下标
    public int currentPlayIndex = 0;
    // 侧边控制面板布局
    private View panel_layout;
    // 播放器管理类
    public TVPlayerManager mPlayerManager;
    // Exo播放画面控件
    private PlayerView playerView;
    // APP配置管理
    private AppConfig appConfig;
    // 画面缩放比例管理器
    private ScreenRatioManager screenRatioManager;
    // 侧边面板管理
    private PanelManager panelManager;
    // 触摸手势管理
    private GestureManager gestureManager;
    // 遥控器按键管理
    private KeyEventManager keyEventManager;
    // 频道列表管理器
    private ChannelListManager channelListManager;
    // 分组列表管理器
    private GroupListManager groupListManager;
    // 日期选择管理器（EPG用）
    private DateListManager dateListManager;
    // EPG节目单包装管理
    private EpgManagerWrapper epgManagerWrapper;
    // 播放器状态监听实现
    private PlayerStateListenerImpl playerStateListener;
    // 上下切台管理
    private ChannelSwitchManager switchManager;

    // EPG面板开关标记
    private boolean epgPanelOpen = false;
    // 原生控制器显隐标记（全局关闭）
    private boolean isControllerVisible = false;
    // EPG功能总开关
    private boolean epg_enable;
    // 上下切台反转开关
    private boolean channel_reverse;
    // 数字选台开关
    private boolean number_channel_enable;
    // 自动更新源开关
    private boolean auto_update_source;
    // 当前选中EPG日期下标
    private int currentSelectedDateIndex = 0;
    // 本地偏好存储
    private SharedPreferences sp;

    // 频道信息提示栏
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name, tv_next_time_range;
    private android.widget.ProgressBar progress_program;
    // 右上角频道数字弹窗
    private TextView tv_channel_num;

    // 网络重定向最大次数
    private static final int MAX_REDIRECT_COUNT = 10;
    // http连接超时
    private static final int CONNECT_TIMEOUT = 8000;
    // 读取超时
    private static final int READ_TIMEOUT = 8000;
    // 播放器UA标识
    private static final String DEF_UA = "ExoPlayer";
    // 请求Refer来源
    private static final String DEF_REFER = "https://www.huya.com/";

    // 自动隐藏频道信息栏任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // 切台冷却时间防重复点击
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;
    private float touchStartY = 0;
    private static final long SLIDE_THRESHOLD = 80;

    // 全局日志缓存集合
    public static List<String> logList = new ArrayList<>();

    /**
     * 全局日志打印
     */
    public static void log(String msg) {
        logList.add(0, msg);
        // 日志只保存100条，超出自动删除末尾
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }

    /**
     * 广播：切换控制器显示（原生控制器已全局禁用）
     */
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    /**
     * 广播：刷新直播源+EPG数据源
     */
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadSettings();
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
        log("【主页】onCreate -> 页面创建");

        mInstance = this;
        // 固定横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 全屏、隐藏系统状态栏导航栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(R.layout.activity_main);
        // 屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tv_channel_num = findViewById(R.id.tv_channel_num);
        initInfoBar();
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 读取自定义配置地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        playerView = findViewById(R.id.player_view);
        // 全局关闭Exo原生控制面板，不会自动弹出控制器
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        panel_layout = findViewById(R.id.panel_layout);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // EPG展开收起按钮
        btn_show_epg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!epg_enable) {
                    Toast.makeText(MainActivity.this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
                    return;
                }
                epgPanelOpen = !epgPanelOpen;
                lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
                lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
                if (epgPanelOpen && !channelSourceList.isEmpty()) {
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                }
            }
        });

        // EPG日期点击切换
        lvDate.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                currentSelectedDateIndex = position;
                if (!channelSourceList.isEmpty()) {
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                }
            }
        });

        // 分组点击筛选频道
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
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
            }
        });

        // 初始化各类列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 绑定播放器View
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        // 接收播放画质、音频信息刷新UI
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            }
        });

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();

        // 画面触摸手势监听
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        log("【播放】记录上次播放索引：" + currentPlayIndex);
        loadLiveAndEpg();
        initListViewClick();
    }

    /**
     * 初始化信息栏控件
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
     * 读取本地配置项
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);

        log("【设置】EPG开关：" + epg_enable);
        log("【设置】切台反转：" + channel_reverse);
    }

    /**
     * 返回键逻辑：先关闭侧边栏，再退出APP
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
     * 加载直播源+EPG节目单
     */
    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");

        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【直播源】加载成功，频道总数：" + channels.size());

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
                log("【直播源】加载失败：" + errorMsg);
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        log("【EPG】加载节目单：" + UrlConfig.EPG_URL);
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
     * 切上一个频道
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        log("【切台】上一台");
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

        log("【切台】下一台");
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 根据下标播放指定频道（自动处理http重定向）
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            log("【播放】频道列表为空，无法播放");
            return;
        }

        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);

        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            log("【播放】频道地址为空");
            return;
        }

        final String originalUrl = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道：" + ch.getName());
        log("【原始地址】：" + originalUrl);
        log("========================================");

        playerStateListener.setCurrentChannelName(ch.getName());

        // 子线程处理重定向解析
        new Thread(() -> {
            HttpURLConnection conn = null;
            String finalUrl = originalUrl;

            try {
                for (int step = 0; step < MAX_REDIRECT_COUNT; step++) {
                    URL urlObj = new URL(finalUrl);
                    conn = (HttpURLConnection) urlObj.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestMethod("GET");

                    // 请求头配置
                    conn.setRequestProperty("User-Agent", DEF_UA);
                    conn.setRequestProperty("Referer", DEF_REFER);
                    conn.setRequestProperty("Origin", "https://www.huya.com");
                    conn.setRequestProperty("Icy-MetaData", "1");
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setInstanceFollowRedirects(false);

                    int code = conn.getResponseCode();
                    // 301/302重定向循环解析
                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        if (loc != null) finalUrl = loc;
                        log("【重定向" + (step + 1) + "次】→ " + finalUrl);
                        conn.disconnect();
                        conn = null;
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                log("【解析失败】使用原始地址播放");
            } finally {
                if (conn != null) conn.disconnect();
            }

            String playUrl = TextUtils.isEmpty(finalUrl) ? originalUrl : finalUrl;
            log("【最终播放地址】→ " + playUrl);

            // 切回主线程开始播放
            new Handler(Looper.getMainLooper()).post(() -> {
                mPlayerManager.playUrl(playUrl);
            });
        }).start();

        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);
        channelListManager.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 弹出频道信息栏，2秒自动消失
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

    private int extractRoomId(String url) {
        try {
            if (url.contains("id=")) {
                return Integer.parseInt(url.split("id=")[1].replaceAll("[^0-9]", ""));
            }
            if (url.contains("huya.com/")) {
                return Integer.parseInt(url.split("huya.com/")[1].replaceAll("[^0-9]", ""));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * 右上角显示频道数字，3秒消失
     */
    public void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            tv_channel_num.setVisibility(View.GONE);
        }, 3000);
    }

    /**
     * 频道列表点击事件初始化
     */
    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (!currentGroupChannelList.isEmpty() && pos < currentGroupChannelList.size()) {
                    Channel selectedChannel = currentGroupChannelList.get(pos);
                    int globalIndex = channelSourceList.indexOf(selectedChannel);
                    if (globalIndex != -1) {
                        log("【列表点击】切换到全局索引：" + globalIndex);
                        playChannel(globalIndex);
                        togglePanel();
                    }
                } else {
                    playChannel(pos);
                    togglePanel();
                }
            }
        });
    }

    /**
     * 侧边栏显隐切换
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    /**
     * 打开设置页面【核心修改】
     * FLAG_ACTIVITY_NO_HISTORY：打开设置不触发主页onPause，画面保留、播放器持续播放不暂停
     */
    public void openSettings() {
        // 不做任何解绑、暂停操作，直接跳转设置
        Intent intent = new Intent(this, SettingsActivity.class);
        // 关键标记：避免启动新页面触发当前页面生命周期pause导致暂停播放
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;

        log("【远程配置】更新直播源：" + liveUrl);
        log("【远程配置】更新EPG：" + epgUrl);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadLiveAndEpg();
            }
        });
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
     * APP切后台（HOME键）：执行暂停+解绑画面
     * 【打开设置不会进这里，不会暂停播放】
     */
    @Override
    protected void onPause() {
        super.onPause();
        log("【主页】onPause -> 切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    /**
     * 从后台切回前台：恢复画面+继续播放
     */
    @Override
    protected void onResume() {
        super.onResume();
        log("【主页】onResume -> 回到前台");
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null)
            mPlayerManager.onForeground();
    }

    /**
     * Activity销毁释放资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        mPlayerManager.release();
        mInstance = null;
    }
}
