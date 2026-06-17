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
 * 直播主页面Activity
 * 核心功能：直播源加载、频道切换、EPG节目单、播放器控制、手势/按键处理
 *
 * 【手动重定向解析说明】
 * 保留手动重定向解析功能，用于处理一些ExoPlayer自动重定向搞不定的场景
 * 同时TVPlayerManager内部也已设置正确的Header（虎牙/斗鱼专属Referer）
 *
 * 【为什么保留手动重定向】
 * 1. 部分直播源地址有多层重定向，需要手动跟随才能拿到真实地址
 * 2. 部分地址有特殊的跳转逻辑（如鉴权跳转），ExoPlayer可能处理不了
 * 3. 保留详细日志，方便排查播放问题
 */
public class MainActivity extends AppCompatActivity {
    // Activity单例，供其他类访问
    public static MainActivity mInstance;
    // 所有频道数据源列表
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前选中分组下的频道列表
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放的频道索引
    public int currentPlayIndex = 0;
    // 面板布局（频道列表+EPG面板）
    private View panel_layout;
    // 播放器管理器
    public TVPlayerManager mPlayerManager;
    // 播放器视图
    private PlayerView playerView;
    // 应用配置管理
    private AppConfig appConfig;
    // 屏幕比例管理
    private ScreenRatioManager screenRatioManager;
    // 面板管理
    private PanelManager panelManager;
    // 手势管理
    private GestureManager gestureManager;
    // 按键事件管理
    private KeyEventManager keyEventManager;
    // 频道列表管理
    private ChannelListManager channelListManager;
    // 分组列表管理
    private GroupListManager groupListManager;
    // 日期列表管理（EPG日期选择）
    private DateListManager dateListManager;
    // EPG节目单管理包装类
    private EpgManagerWrapper epgManagerWrapper;
    // 播放器状态监听器
    private PlayerStateListenerImpl playerStateListener;
    // 频道切换管理
    private ChannelSwitchManager switchManager;
    // EPG面板是否展开
    private boolean epgPanelOpen = false;
    // 播放器控制器是否可见
    private boolean isControllerVisible = false;
    // EPG功能是否启用
    private boolean epg_enable;
    // 频道切换是否反向
    private boolean channel_reverse;
    // 频道号显示是否启用
    private boolean number_channel_enable;
    // 直播源是否自动更新
    private boolean auto_update_source;
    // 当前选中的日期索引（用于EPG）
    private int currentSelectedDateIndex = 0;
    // 配置存储
    private SharedPreferences sp;
    // 底部信息栏
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name, tv_next_time_range;
    private android.widget.ProgressBar progress_program;
    // 频道号显示
    private TextView tv_channel_num;

    // 隐藏信息栏的Runnable
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // 上次频道切换时间（用于冷却控制）
    private long lastChannelChangeTime = 0;
    // 频道切换冷却时间（300ms，防止快速切换）
    private static final long CHANNEL_COOLDOWN = 300;
    // 触摸起始Y坐标（用于手势判断）
    private float touchStartY = 0;
    // 滑动阈值（超过此值才触发切台）
    private static final float SLIDE_THRESHOLD = 80;

    // ================================================
    // ✅ 重定向解析相关配置
    // ================================================
    // 最大重定向跟随次数（防止死循环）
    private static final int MAX_REDIRECT_COUNT = 10;
    // 连接超时时间（毫秒）
    private static final int CONNECT_TIMEOUT = 8000;
    // 读取超时时间（毫秒）
    private static final int READ_TIMEOUT = 8000;
    // 默认User-Agent（浏览器UA，避免被识别为爬虫）
    private static final String DEF_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    // ✅ 虎牙专属配置
    private static final String HUYA_REFERER = "https://www.huya.com/";
    private static final String HUYA_ORIGIN = "https://www.huya.com";

    // ✅ 斗鱼专属配置
    private static final String DOUYU_REFERER = "https://www.douyu.com/";
    private static final String DOUYU_ORIGIN = "https://www.douyu.com";

    // 通用Header配置
    private static final String DEF_ACCEPT = "*/*";
    private static final String DEF_ACCEPT_LANG = "zh-CN,zh;q=0.9,en;q=0.8";

    // 本地日志列表（最新在前，最多100条）
    public static List<String> logList = new ArrayList<>();

    /**
     * 添加日志
     * 同时保存到本地列表和SettingsActivity的全局日志
     * @param msg 日志内容
     */
    public static void log(String msg) {
        logList.add(0, msg);
        // 限制日志数量，最多100条
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        // 同步到SettingsActivity的日志系统
        SettingsActivity.log(msg);
    }

    // 切换播放器控制器的广播接收器
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    // 刷新直播源/EPG的广播接收器
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadSettings();
                        // 应用自定义直播源/EPG地址
                        String customLive = appConfig.getCustomLiveUrl();
                        String customEpg = appConfig.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        // 重新加载
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
        // 设置横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 全屏（隐藏状态栏）
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 沉浸式导航栏
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        // 加载布局
        setContentView(R.layout.activity_main);
        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 绑定频道号显示
        tv_channel_num = findViewById(R.id.tv_channel_num);
        // 初始化信息栏
        initInfoBar();
        // 初始化配置
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 应用自定义直播源/EPG地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        // 绑定播放器视图
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false); // 默认隐藏原生控制器
        playerView.setControllerVisibilityListener(null);

        // 绑定面板布局
        panel_layout = findViewById(R.id.panel_layout);

        // 绑定各类ListView
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // EPG展开按钮点击事件
        btn_show_epg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!epg_enable) {
                    Toast.makeText(MainActivity.this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 切换EPG面板显示状态
                epgPanelOpen = !epgPanelOpen;
                lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
                lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
                // 展开时刷新当前频道的节目单
                if (epgPanelOpen && !channelSourceList.isEmpty()) {
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                }
            }
        });

        // 分组列表点击事件
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 更新分组选中状态
                groupListManager.setSelectedPosition(position);
                lvGroup.setItemChecked(position, true);
                lvGroup.setSelection(position);

                // 获取选中的分组名称
                String groupName = groupListManager.getCurrentGroup(position);
                // 筛选该分组下的频道
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (groupName.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                // 更新频道列表显示
                channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            }
        });

        // 初始化各类管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();

        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 日期选中监听：切换日期时刷新EPG
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;
            panelManager.setCurrentDateIndex(pos);
            if (channelSourceList != null
                    && !channelSourceList.isEmpty()
                    && currentPlayIndex >= 0
                    && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // 初始化播放器
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 直播信息更新监听
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            }
        });

        // 屏幕比例管理
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 手势管理
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();

        // 播放器触摸事件（处理手势）
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        // 按键事件管理
        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        // 恢复上次播放的频道索引
        currentPlayIndex = appConfig.getLastPlayIndex();

        log("【播放】记录上次播放索引：" + currentPlayIndex);
        // 加载直播源和EPG
        loadLiveAndEpg();
        // 初始化列表点击事件
        initListViewClick();
    }

    /**
     * 初始化信息栏UI组件
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
     * 加载应用设置
     * 从SharedPreferences读取各项配置
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
     * 返回键处理
     * 面板显示时先关闭面板，否则执行默认返回
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
     * 加载直播源和EPG数据
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
                // 更新频道切换管理器
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                // 更新分组列表
                groupListManager.setGroups(channelSourceList);
                // 更新频道列表
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                // 播放当前频道
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                log("【直播源】加载失败：" + errorMsg);
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        // 加载EPG节目单
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
     * 播放上一个频道（分组内循环）
     * 只在当前分组内切换，不会切到其他分组
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        // 冷却时间内不处理
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        log("【切台】上一台");

        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 获取当前频道和所属分组
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();

        // 收集当前分组的所有频道
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }

        // 分组内只有1个频道时不切换
        if (groupChannels.size() <= 1) return;

        // 找到当前频道在分组内的位置
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }

        if (groupIndex == -1) return;

        // 计算上一个频道的位置（取模实现循环）
        int prevGroupIndex = (groupIndex - 1 + groupChannels.size()) % groupChannels.size();
        Channel prevChannel = groupChannels.get(prevGroupIndex);

        // 找到全局索引并播放
        int globalIndex = channelSourceList.indexOf(prevChannel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
    }

    /**
     * 播放下一个频道（分组内循环）
     * 只在当前分组内切换，不会切到其他分组
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        // 冷却时间内不处理
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        log("【切台】下一台");

        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 获取当前频道和所属分组
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();

        // 收集当前分组的所有频道
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }

        // 分组内只有1个频道时不切换
        if (groupChannels.size() <= 1) return;

        // 找到当前频道在分组内的位置
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }

        if (groupIndex == -1) return;

        // 计算下一个频道的位置（取模实现循环）
        int nextGroupIndex = (groupIndex + 1) % groupChannels.size();
        Channel nextChannel = groupChannels.get(nextGroupIndex);

        // 找到全局索引并播放
        int globalIndex = channelSourceList.indexOf(nextChannel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
    }

    // ================================================
    // ✅ 重定向解析核心方法（虎牙+斗鱼双平台适配）
    // ================================================

    /**
     * 手动解析URL重定向
     * 跟随HTTP重定向（301/302/303/307/308），获取最终的真实播放地址
     *
     * 【功能特点】
     * 1. 最多跟随10次重定向，防止死循环
     * 2. 支持301/302/303/307/308等所有重定向状态码
     * 3. 自动处理相对路径的Location
     * 4. 智能识别虎牙/斗鱼，添加专属Header（Referer、Origin等）
     * 5. 403错误时自动切换Header重试
     * 6. 500错误时自动重试2次
     * 7. 详细的全过程日志，方便排查问题
     *
     * 【为什么保留手动重定向】
     * 虽然ExoPlayer本身支持自动重定向，但部分场景下手动解析更可靠：
     * 1. 部分直播源有多层重定向，需要手动跟随才能拿到真实地址
     * 2. 部分地址有特殊的鉴权跳转逻辑
     * 3. 可以记录详细的重定向过程日志
     *
     * @param originalUrl 原始地址
     * @return 解析后的最终地址
     */
    private String resolveRedirectUrl(String originalUrl) {
        // 参数校验
        if (TextUtils.isEmpty(originalUrl)) {
            log("【重定向】原始地址为空，跳过解析");
            return originalUrl;
        }

        log("【重定向】开始解析，原始地址：" + originalUrl);

        // ✅ 判断平台类型
        boolean isHuya = originalUrl.contains("huya.com") || originalUrl.contains("huya.cn");
        boolean isDouyu = originalUrl.contains("douyu.com") || originalUrl.contains("douyucdn.cn");
        String platform = "通用";
        if (isHuya) platform = "虎牙";
        else if (isDouyu) platform = "斗鱼";

        log("【重定向】✅ 检测到平台：" + platform + "直播，启用专属适配");

        HttpURLConnection conn = null;
        String currentUrl = originalUrl;
        int redirectCount = 0;     // 重定向次数
        int retry403Count = 0;     // 403重试次数
        int retry500Count = 0;     // 500重试次数
        int totalSteps = 0;        // 总请求步数
        int maxTotalSteps = MAX_REDIRECT_COUNT + 5; // 总步数上限（防止死循环）

        try {
            while (totalSteps < maxTotalSteps) {
                totalSteps++;
                URL urlObj = new URL(currentUrl);
                conn = (HttpURLConnection) urlObj.openConnection();

                // 设置基础参数
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", DEF_UA);
                conn.setInstanceFollowRedirects(false); // 手动处理重定向，不自动跟随

                String host = urlObj.getHost();

                // ✅ 虎牙专属Header
                if (host.contains("huya.com") || host.contains("huya.cn")) {
                    conn.setRequestProperty("Referer", HUYA_REFERER);
                    conn.setRequestProperty("Origin", HUYA_ORIGIN);
                    conn.setRequestProperty("Accept", DEF_ACCEPT);
                    conn.setRequestProperty("Accept-Language", DEF_ACCEPT_LANG);
                    conn.setRequestProperty("Connection", "keep-alive");
                    log("【重定向】虎牙专属Header已添加");
                }
                // ✅ 斗鱼专属Header
                else if (host.contains("douyu.com") || host.contains("douyucdn.cn")) {
                    conn.setRequestProperty("Referer", DOUYU_REFERER);
                    conn.setRequestProperty("Origin", DOUYU_ORIGIN);
                    conn.setRequestProperty("Accept", DEF_ACCEPT);
                    conn.setRequestProperty("Accept-Language", DEF_ACCEPT_LANG);
                    conn.setRequestProperty("Connection", "keep-alive");
                    log("【重定向】斗鱼专属Header已添加");
                }

                // ✅ 403重试模式：添加更完整的Header
                if (retry403Count > 0) {
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                    conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
                    conn.setRequestProperty("Connection", "keep-alive");

                    // 按平台匹配Referer/Origin
                    if (host.contains("huya.com") || host.contains("huya.cn")) {
                        conn.setRequestProperty("Referer", HUYA_REFERER);
                        conn.setRequestProperty("Origin", HUYA_ORIGIN);
                    } else if (host.contains("douyu.com") || host.contains("douyucdn.cn")) {
                        conn.setRequestProperty("Referer", DOUYU_REFERER);
                        conn.setRequestProperty("Origin", DOUYU_ORIGIN);
                    } else {
                        conn.setRequestProperty("Referer", urlObj.getProtocol() + "://" + host + "/");
                        conn.setRequestProperty("Origin", urlObj.getProtocol() + "://" + host);
                    }
                    log("【重定向】403重试模式，已添加完整Header");
                }

                // 获取响应状态码
                int code = conn.getResponseCode();
                log("【重定向】第" + totalSteps + "次请求，状态码：" + code + "，地址：" + currentUrl);

                // ✅ 处理重定向（301/302/303/307/308）
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (TextUtils.isEmpty(location)) {
                        log("【重定向】Location为空，终止重定向");
                        break;
                    }

                    // 处理相对路径（以/开头的地址）
                    if (location.startsWith("/")) {
                        String baseUrl = urlObj.getProtocol() + "://" + host;
                        location = baseUrl + location;
                    }

                    redirectCount++;
                    log("【重定向】第" + redirectCount + "次重定向：" + location);
                    currentUrl = location;
                    // 关闭连接，准备下一次请求
                    conn.disconnect();
                    conn = null;
                    // 重置重试计数
                    retry403Count = 0;
                    retry500Count = 0;
                    continue;
                }

                // ✅ 处理403禁止访问
                if (code == 403 && retry403Count == 0) {
                    retry403Count++;
                    log("【重定向】⚠️ 403禁止访问，尝试添加完整Header重试");
                    conn.disconnect();
                    conn = null;
                    continue;
                }

                // ✅ 处理500服务器内部错误
                if (code == 500 && retry500Count < 2) {
                    retry500Count++;
                    log("【重定向】⚠️ 500服务器内部错误，第" + retry500Count + "次重试");
                    conn.disconnect();
                    conn = null;
                    // 等待一段时间再重试（间隔递增：300ms → 500ms）
                    try { Thread.sleep(300 + retry500Count * 200); } catch (InterruptedException ignored) {}
                    continue;
                }

                // 正常状态码（200-299），解析完成
                if (code >= 200 && code < 300) {
                    log("【重定向】✅ 解析成功，最终地址：" + currentUrl);
                    break;
                }

                // 其他错误状态码，记录日志但继续用当前地址交给播放器
                log("【重定向】⚠️ 状态码：" + code + "，继续使用当前地址交给播放器");
                break;
            }

            // 达到最大重定向次数
            if (redirectCount >= MAX_REDIRECT_COUNT) {
                log("【重定向】⚠️ 达到最大重定向次数(" + MAX_REDIRECT_COUNT + "次)，停止跟随");
            }

        } catch (Exception e) {
            log("【重定向】❌ 解析异常：" + e.getMessage());
            e.printStackTrace();
        } finally {
            // 确保连接关闭
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }

        // 输出解析结果汇总
        log("【重定向】解析结束：重定向" + redirectCount + "次，403重试" + retry403Count + "次，500重试" + retry500Count + "次");
        log("【重定向】最终地址：" + currentUrl);
        return currentUrl;
    }

    /**
     * 播放指定索引的频道
     *
     * 【流程】
     * 1. 参数校验和边界处理
     * 2. 更新UI状态（频道号、信息栏、列表选中、EPG等）
     * 3. 异步解析重定向地址
     * 4. 拿到真实地址后交给播放器播放
     *
     * 【关于手动重定向】
     * 保留手动重定向解析，用于处理复杂的跳转场景
     * 同时TVPlayerManager内部也已设置正确的Header，确保播放稳定
     *
     * @param index 频道在全局列表中的索引
     */
    public void playChannel(int index) {
        // 频道列表为空时直接返回
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            log("【播放】频道列表为空，无法播放");
            return;
        }

        // 索引边界校验
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);

        // 频道或播放地址为空时返回
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            log("【播放】频道地址为空");
            return;
        }

        final String originalUrl = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道名称：" + ch.getName());
        log("【播放】原始地址：" + originalUrl);
        log("【播放】当前索引：" + index);
        log("========================================");

        // 更新播放器状态监听器的当前频道名
        playerStateListener.setCurrentChannelName(ch.getName());
        // 显示频道号
        showChannelNum(index + 1);
        // 保存上次播放的频道索引
        appConfig.setLastPlayIndex(index);
        // 更新频道列表选中状态
        channelListManager.setChannels(channelSourceList, index);
        // 刷新EPG节目单
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 更新信息栏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 2000);
            tv_channel_name.setText(ch.getName());
            // 更新直播信息（画质、音频、码率）
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
        }

        // ✅ 异步解析重定向，拿到真实地址后再播放
        // 在子线程中执行网络请求，避免阻塞主线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 解析重定向，获取最终的真实播放地址
                final String realUrl = resolveRedirectUrl(originalUrl);
                // 切回主线程播放
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        log("【播放】开始播放真实地址：" + realUrl);
                        // 交给播放器播放
                        // 注意：TVPlayerManager内部会自动设置正确的Header（虎牙/斗鱼专属Referer）
                        mPlayerManager.playUrl(realUrl);
                    }
                });
            }
        }).start();
    }

    /**
     * 显示频道号（延迟3秒自动隐藏）
     * @param num 频道号
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
     * 初始化频道列表点击事件
     * 点击频道列表项时播放对应频道并关闭面板
     */
    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (!currentGroupChannelList.isEmpty() && pos < currentGroupChannelList.size()) {
                    // 分组模式下：从分组频道列表中找到对应频道
                    Channel selectedChannel = currentGroupChannelList.get(pos);
                    int globalIndex = channelSourceList.indexOf(selectedChannel);
                    if (globalIndex != -1) {
                        log("【列表点击】切换到全局索引：" + globalIndex);
                        playChannel(globalIndex);
                        togglePanel();
                    }
                } else {
                    // 非分组模式下：直接按索引播放
                    playChannel(pos);
                    togglePanel();
                }
            }
        });
    }

    /**
     * 切换面板显示/隐藏
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
    }

    /**
     * 打开设置页面
     */
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 接收远程配置更新（自定义直播源/EPG地址）
     * @param liveUrl 自定义直播源地址
     * @param epgUrl 自定义EPG地址
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
     * 按键事件处理
     * 优先交给按键事件管理器处理
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Activity暂停：播放器切后台
     */
    @Override
    protected void onPause() {
        super.onPause();
        log("【主页】onPause -> 切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    /**
     * Activity恢复：播放器切前台，重新加载设置
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
     * Activity销毁：释放资源，注销广播
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
