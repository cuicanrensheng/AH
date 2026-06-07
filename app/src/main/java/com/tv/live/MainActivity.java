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
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 电视直播APP 主界面
 * 功能：视频播放、频道切换、节目单(EPG)、分组管理、手势/按键控制、设置
 */
public class MainActivity extends AppCompatActivity {
    // 单例实例，方便其他类调用当前页面
    public static MainActivity mInstance;

    // 所有频道源数据（总列表）
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前分组下的频道列表
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放的频道索引
    public int currentPlayIndex = 0;

    // 控制面板根布局（频道列表+节目单）
    private View panel_layout;
    // 播放器管理器（核心播放控制）
    public TVPlayerManager mPlayerManager;
    // ExoPlayer 播放视图
    private PlayerView playerView;

    // 应用配置管理
    private AppConfig appConfig;
    // 画面比例管理器
    private ScreenRatioManager screenRatioManager;
    // 面板（悬浮窗）管理器
    private PanelManager panelManager;
    // 手势控制管理器
    private GestureManager gestureManager;
    // 按键事件管理器
    private KeyEventManager keyEventManager;

    // 频道列表管理
    private ChannelListManager channelListManager;
    // 频道分组管理（央视/卫视/地方台等）
    private GroupListManager groupListManager;
    // 节目单日期管理
    private DateListManager dateListManager;
    // 节目单（EPG）包装类
    private EpgManagerWrapper epgManagerWrapper;

    // 播放状态监听器
    private PlayerStateListenerImpl playerStateListener;
    // 频道切换管理器
    private ChannelSwitchManager switchManager;

    // EPG节目单面板是否打开
    private boolean epgPanelOpen = false;
    // 播放器控制器是否显示
    private boolean isControllerVisible = false;
    
    // Exo控制器显示标记（已全局禁用控制器，该字段仅保留兼容旧逻辑）
    private boolean isControllerVisible = false;
    
    // 设置项：是否开启EPG
    private boolean epg_enable;
    // 设置项：切台方向是否反转
    private boolean channel_reverse;
    // 设置项：是否开启数字选台
    private boolean number_channel_enable;
    // 设置项：是否自动更新源
    private boolean auto_update_source;

    // 当前选中的节目单日期索引
    private int currentSelectedDateIndex = 0;
    // 配置文件存储
    private SharedPreferences sp;

    // 顶部信息栏
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name, tv_next_time_range;
    private android.widget.ProgressBar progress_program;

        //===================== 常量定义 =====================
    // 直播链接最大重定向次数
    private static final int MAX_REDIRECT_COUNT = 10;
    // 链接连接超时
    private static final int CONNECT_TIMEOUT = 8000;
    // 链接读取超时
    private static final int READ_TIMEOUT = 8000;
    // 切台冷却防重复点击时间
    private static final long CHANNEL_COOLDOWN = 300;
    
    // 频道号数字提示
    private TextView tv_channel_num;

    // 延迟隐藏信息栏的任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // 切台防抖：上次切台时间
    private long lastChannelChangeTime = 0;
    // 切台冷却时间（防止快速连续切台）
    private static final long CHANNEL_COOLDOWN = 300;

    // 手势滑动相关
    private float touchStartY = 0;
    private static final float SLIDE_THRESHOLD = 80;

    // 本地日志列表：最新在前，最多保存100条
    public static List<String> logList = new ArrayList<>();

    /**
     * 日志记录方法
     */
    public static void log(String msg) {
        logList.add(0, msg);
        // 超过100条删除最早的
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        // 同步到设置页面日志
        SettingsActivity.log(msg);
    }

    /**
     * 广播接收器：切换播放器控制器显示/隐藏
     */
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    /**
     * 广播接收器：刷新直播源和节目单
     */
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 重新加载配置
                        loadSettings();
                        String customLive = appConfig.getCustomLiveUrl();
                        String customEpg = appConfig.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        // 重新加载数据
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

        // 单例赋值
        mInstance = this;
        // 强制横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 全屏显示
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 隐藏导航栏+沉浸式
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        // 设置布局
        setContentView(R.layout.activity_main);
        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 频道号数字提示
        tv_channel_num = findViewById(R.id.tv_channel_num);
        // 初始化顶部信息栏
        initInfoBar();
        // 获取应用配置
        appConfig = AppConfig.getInstance(this);
        // 加载用户设置
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 使用自定义直播源地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        // 初始化播放视图
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false); // 默认隐藏控制器
        playerView.setControllerVisibilityListener(null);

        // 绑定布局
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // 显示/隐藏节目单按钮点击事件
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
                // 打开时刷新节目单
                if (epgPanelOpen && !channelSourceList.isEmpty()) {
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                }
            }
        });

        // 节目单日期点击切换
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

        // 频道分组点击切换
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                lvGroup.setItemChecked(position, true);
                lvGroup.setSelection(position);
                String groupName = groupListManager.getCurrentGroup(position);
                // 筛选当前分组频道
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (groupName.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                // 更新频道列表
                channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
                // 播放该分组第一个频道
                if (!currentGroupChannelList.isEmpty()) {
                    Channel firstChannel = currentGroupChannelList.get(0);
                    int globalIndex = channelSourceList.indexOf(firstChannel);
                    if (globalIndex != -1) {
                        playChannel(globalIndex);
                    }
                }
            }
        });

        // 初始化各列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate(); // 初始化日期列表
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 初始化播放器
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        // 直播信息更新监听（清晰度、音频、码率）
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            }
        });

        // 画面比例设置
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 手势控制
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        // 按键控制
        keyEventManager = new KeyEventManager(this);
        // 频道切换
        switchManager = ChannelSwitchManager.getInstance();
        // 读取上次播放记录
        currentPlayIndex = appConfig.getLastPlayIndex();

        log("【播放】记录上次播放索引：" + currentPlayIndex);
        // 加载直播源和节目单
        loadLiveAndEpg();
        // 初始化频道列表点击事件
        initListViewClick();
    }

    /**
     * 初始化顶部信息栏控件绑定
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
     * 加载用户设置（EPG、切台反转等）
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
     * 返回键逻辑：先关闭面板，再退出
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
     * 加载直播源 + 加载节目单(EPG)
     */
    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");

        // 加载直播源
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【直播源】加载成功，频道总数：" + channels.size());

                channelSourceList.clear();
                channelSourceList.addAll(channels);
                // 给切换管理器设置数据
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                // 刷新分组和频道列表
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                // 播放上次记录的频道
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                log("【直播源】加载失败：" + errorMsg);
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        // 加载节目单
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
     * 播放上一个频道
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
     * 播放下一个频道
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
     * 播放指定下标频道，自动处理链接301重定向
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) return;

        playerStateListener.setCurrentChannelName(ch.getName());
        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);
        
        // 索引越界保护
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);

        // 播放地址为空判断
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            log("【播放】频道地址为空");
            return;
        }

        String url = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道名称：" + ch.getName());
        log("【播放】频道地址：" + url);
        log("【播放】当前索引：" + index);
        log("========================================");

        // 设置当前频道名
        playerStateListener.setCurrentChannelName(ch.getName());
        // 开始播放
        mPlayerManager.playUrl(url);
        // 显示频道号
        showChannelNum(index + 1);
        // 保存播放记录
        appConfig.setLastPlayIndex(index);
        // 刷新频道列表选中状态
        channelListManager.setChannels(channelSourceList, index);
        // 刷新节目单
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 显示底部信息栏，2秒后自动隐藏
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
    }
    
            // 子线程处理链接重定向
        new Thread(() -> {
            java.net.HttpURLConnection conn = null;
            try {
                for (int i = 0; i < MAX_REDIRECT_COUNT; i++) {
                    java.net.URL u = new java.net.URL(finalUrl[0]);
                    conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestMethod("GET");
                    conn.setInstanceFollowRedirects(false);
                    int code = conn.getResponseCode();
                    // 301/302重定向处理
                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        if (loc != null) finalUrl[0] = loc;
                        conn.disconnect();
                        conn = null;
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (conn != null) conn.disconnect();
            }
            // 主线程开始播放
            new Handler(Looper.getMainLooper()).post(() -> {
                mPlayerManager.playUrl(finalUrl[0]);
            });
        }).start();
    }

    /**
     * 显示频道号，3秒后隐藏
     */
    public void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                tv_channel_num.setVisibility(View.GONE);
            }
        }, 3000);
    }

    /**
     * 频道列表点击事件
     */
    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                // 分组内点击
                if (!currentGroupChannelList.isEmpty() && pos < currentGroupChannelList.size()) {
                    Channel selectedChannel = currentGroupChannelList.get(pos);
                    int globalIndex = channelSourceList.indexOf(selectedChannel);
                    if (globalIndex != -1) {
                        log("【列表点击】切换到全局索引：" + globalIndex);
                        playChannel(globalIndex);
                        togglePanel(); // 切换后关闭面板
                    }
                } else {
                    // 全部频道点击
                    playChannel(pos);
                    togglePanel();
                }
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
     * 打开设置页面
     */
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 接收远程配置更新（直播源+EPG地址）
     */
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
     * 按键按下事件分发
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 页面暂停（后台）
     */
    @Override
    protected void onPause() {
        super.onPause();
        log("【主页】onPause -> 切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    /**
     * 页面恢复（前台）
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
     * 页面销毁（释放资源）
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        // 注销广播（防止内存泄漏）
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        // 释放播放器
        mPlayerManager.release();
        mInstance = null;
    }
}
