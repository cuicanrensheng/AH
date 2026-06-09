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
 * 直播应用主界面Activity
 * 核心功能：直播播放、频道列表管理、频道切换、EPG(电子节目指南)展示、手势/按键控制、直播源/EPG刷新等
 * 负责整合播放器、频道管理、EPG管理、面板控制等模块，提供完整的直播播放交互体验
 * 
 * ✅ 已修复（完全沿用老版成功思路）：
 * 1. 日期列表点击失效：所有列表点击事件统一在MainActivity直接设置，不在Manager内部处理
 * 2. 事件覆盖问题：移除DateListManager内部的OnDateSelectedListener，避免事件被覆盖
 * 3. 日期状态不同步：点击日期后同步更新DateListManager的选中状态
 * 4. 打开面板强制跳回今天：togglePanel使用当前选中的日期索引，不硬编码0
 * 5. 边界防护统一：所有点击事件先判断channelSourceList是否为空，防止空指针
 */
public class MainActivity extends AppCompatActivity {
    // 单例实例，供其他模块获取主界面上下文/调用方法
    public static MainActivity mInstance;
    // 所有直播频道数据源
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前选中分组下的频道列表
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放的频道索引
    public int currentPlayIndex = 0;
    // 控制面板根布局（频道列表/EPG面板）
    private View panel_layout;
    // 直播播放器管理类，封装播放核心逻辑
    public TVPlayerManager mPlayerManager;
    // ExoPlayer播放器视图
    private PlayerView playerView;
    // 应用配置管理类，管理自定义URL、上次播放索引等
    private AppConfig appConfig;
    // 屏幕比例管理类，控制播放画面的显示比例
    private ScreenRatioManager screenRatioManager;
    // 控制面板（频道/EPG面板）管理类
    private PanelManager panelManager;
    // 手势管理类，处理触摸手势逻辑
    private GestureManager gestureManager;
    // 按键事件管理类，处理遥控器/键盘按键事件
    private KeyEventManager keyEventManager;
    // 频道列表管理类，负责频道列表的展示与更新
    private ChannelListManager channelListManager;
    // 频道分组管理类，负责分组列表的展示与切换
    private GroupListManager groupListManager;
    // 日期列表管理类（EPG日期选择）【仅负责数据渲染，不处理点击】
    private DateListManager dateListManager;
    // EPG管理包装类，简化EPG数据展示逻辑
    private EpgManagerWrapper epgManagerWrapper;
    // 播放器状态监听器，监听播放状态变化（播放、暂停、错误等）
    private PlayerStateListenerImpl playerStateListener;
    // 频道切换管理类，处理上/下台切换逻辑
    private ChannelSwitchManager switchManager;
    // EPG面板是否展开标识
    private boolean epgPanelOpen = false;
    // 播放器控制器是否可见标识
    private boolean isControllerVisible = false;
    // EPG功能是否启用（从设置读取）
    private boolean epg_enable;
    // 频道切换方向是否反转（从设置读取）
    private boolean channel_reverse;
    // 数字键切换频道是否启用（从设置读取）
    private boolean number_channel_enable;
    // 直播源是否自动更新（从设置读取）
    private boolean auto_update_source;
    // EPG当前选中的日期索引【全局唯一状态源】
    private int currentSelectedDateIndex = 0;
    // 应用设置共享偏好存储
    private SharedPreferences sp;
    // 频道信息栏（显示频道名称、码率、当前节目等）
    private View info_bar;
    // 频道名称文本控件
    private TextView tv_channel_name;
    // 画质标签（FHD/HD等）文本控件
    private TextView tv_tag_fhd;
    // 音频编码标签文本控件
    private TextView tv_tag_audio;
    // 码率文本控件
    private TextView tv_bitrate;
    // 当前节目名称文本控件
    private TextView tv_current_program_name;
    // 当前节目时间范围文本控件
    private TextView tv_current_time_range;
    // 当前节目剩余时间文本控件
    private TextView tv_remaining_time;
    // 下一个节目名称文本控件
    private TextView tv_next_program_name;
    // 下一个节目时间范围文本控件
    private TextView tv_next_time_range;
    // 节目进度条
    private android.widget.ProgressBar progress_program;
    // 频道号显示文本控件
    private TextView tv_channel_num;

    /**
     * 隐藏频道信息栏的Runnable
     * 用于延迟隐藏info_bar，避免一直显示遮挡画面
     */
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // 上次切换频道的时间戳（用于切台防抖）
    private long lastChannelChangeTime = 0;
    // 切台冷却时间（防抖，单位：毫秒）
    private static final long CHANNEL_COOLDOWN = 300;
    // 触摸起始Y坐标（用于手势滑动判断）
    private float touchStartY = 0;
    // 滑动阈值（超过该值判定为有效滑动）
    private static final float SLIDE_THRESHOLD = 80;

    // 本地日志列表：最新日志在前，最多保留100条
    public static List<String> logList = new ArrayList<>();

    /**
     * 添加日志到本地列表，并同步到设置页面
     * @param msg 日志内容
     */
    public static void log(String msg) {
        logList.add(0, msg);
        // 限制日志数量，最多保留100条
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        // 同步日志到设置页面
        SettingsActivity.log(msg);
    }

    /**
     * 切换播放器控制器可见性的广播接收器
     * 接收"com.tv.live.TOGGLE_CONTROL"广播，切换播放器原生控制器的显示/隐藏
     */
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    /**
     * 刷新直播源/EPG的广播接收器
     * 接收"com.tv.live.REFRESH_LIVE_AND_EPG"广播，重新加载直播源和EPG数据
     */
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 重新加载设置
                        loadSettings();
                        // 更新自定义直播源/EPG URL
                        String customLive = appConfig.getCustomLiveUrl();
                        String customEpg = appConfig.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        // 重新加载直播源和EPG
                        loadLiveAndEpg();
                        Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    };

    /**
     * Activity创建生命周期方法
     * 初始化界面、配置、播放器、各类管理器、广播接收器、视图事件等
     * ✅ 核心修改：所有列表点击事件统一在MainActivity直接设置
     * @param savedInstanceState 保存的实例状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】onCreate -> 页面创建");

        // 初始化单例
        mInstance = this;
        // 设置屏幕为横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 设置全屏标志
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 隐藏系统状态栏和导航栏，沉浸式显示
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        // 设置布局
        setContentView(R.layout.activity_main);
        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 绑定频道号显示控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        // 初始化频道信息栏
        initInfoBar();
        // 获取应用配置实例
        appConfig = AppConfig.getInstance(this);
        // 加载应用设置
        loadSettings();
        // 获取设置共享偏好
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 加载自定义直播源/EPG URL
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        // 打印配置日志
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        // 绑定播放器视图
        playerView = findViewById(R.id.player_view);
        // 初始隐藏播放器原生控制器
        playerView.setUseController(false);
        // 清空控制器可见性监听器
        playerView.setControllerVisibilityListener(null);

        // 绑定控制面板布局
        panel_layout = findViewById(R.id.panel_layout);

        // 绑定各类列表视图
        ListView lvGroup = findViewById(R.id.lv_group);          // 频道分组列表
        ListView lvChannelList = findViewById(R.id.lv_channel_list); // 频道列表
        ListView lvDate = findViewById(R.id.lv_date);            // EPG日期列表
        ListView lvEpg = findViewById(R.id.lv_epg);              // EPG节目列表
        TextView btn_show_epg = findViewById(R.id.btn_show_epg); // 显示/隐藏EPG按钮

        // 注册广播接收器
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // ===================== EPG按钮点击事件：切换EPG面板显示/隐藏 =====================
        btn_show_epg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // EPG功能禁用时提示
                if (!epg_enable) {
                    Toast.makeText(MainActivity.this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 切换EPG面板状态
                epgPanelOpen = !epgPanelOpen;
                lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
                lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
                // EPG面板打开且频道列表非空时，刷新EPG数据
                if (epgPanelOpen && !channelSourceList.isEmpty()) {
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    // ✅ 使用当前选中的日期索引，不硬编码0
                    epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                }
            }
        });

        // ===================== 日期列表点击事件【完全老版写法，统一在MainActivity处理】 =====================
        // ✅ 核心修复：直接给lvDate设置OnItemClickListener，不在DateListManager内部处理
        // ✅ 彻底解决事件被覆盖/拦截的问题，和频道/分组列表处理逻辑完全一致
        lvDate.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 老版标准边界判断：先判断列表是否为空
                if (channelSourceList == null || channelSourceList.isEmpty()) return;

                // 更新全局日期索引状态
                currentSelectedDateIndex = position;
                // 同步更新DateListManager的UI选中状态
                dateListManager.setSelectedPosition(position);
                // 刷新当前频道对应日期的节目单
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);

                // 打印调试日志
                log("【EPG】选中日期索引：" + position);
            }
        });

        // ===================== 频道分组列表点击事件：切换分组并播放该分组第一个频道 =====================
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 设置分组选中状态
                lvGroup.setItemChecked(position, true);
                lvGroup.setSelection(position);
                // 获取选中分组名称
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

        // 初始化各类管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        // 初始化EPG日期列表（仅生成数据和适配器，不处理点击）
        dateListManager.initDate();
        // 初始化控制面板管理器
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 初始化播放器管理器
        mPlayerManager = TVPlayerManager.getInstance(this);
        // 绑定播放器视图
        mPlayerManager.attachPlayerView(playerView);
        // 初始化播放器状态监听器
        playerStateListener = new PlayerStateListenerImpl(this);
        // 设置播放器状态监听器
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        // 设置直播信息更新监听器（画质、音频、码率）
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            }
        });

        // 初始化屏幕比例管理器并应用
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        // 初始化手势管理器
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();

        // 播放器视图触摸事件：交给手势助手处理
        // ✅ 事件隔离：仅处理播放器区域的触摸，不影响左侧面板的点击
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        // 初始化按键事件管理器
        keyEventManager = new KeyEventManager(this);
        // 初始化频道切换管理器
        switchManager = ChannelSwitchManager.getInstance();
        // 获取上次播放的频道索引
        currentPlayIndex = appConfig.getLastPlayIndex();

        // 打印上次播放索引日志
        log("【播放】记录上次播放索引：" + currentPlayIndex);
        // 加载直播源和EPG数据
        loadLiveAndEpg();
        // 初始化频道列表点击事件
        initListViewClick();
    }

    /**
     * 初始化频道信息栏控件
     * 绑定info_bar下的所有子控件，用于显示频道和节目相关信息
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
     * 从SharedPreferences读取EPG开关、切台反转、数字键切台、自动更新源等配置
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);          // EPG功能开关，默认开启
        channel_reverse = sp.getBoolean("channel_reverse", false); // 切台反转，默认关闭
        number_channel_enable = sp.getBoolean("number_channel_enable", true); // 数字键切台，默认开启
        auto_update_source = sp.getBoolean("auto_update_source", true); // 自动更新源，默认开启

        // 打印设置日志
        log("【设置】EPG开关：" + epg_enable);
        log("【设置】切台反转：" + channel_reverse);
    }

    /**
     * 返回键事件处理
     * 控制面板显示时隐藏面板，否则执行默认返回逻辑
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
     * 1. 加载直播源并更新频道列表
     * 2. 加载EPG数据并刷新节目列表
     */
    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");

        // 加载直播源
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【直播源】加载成功，频道总数：" + channels.size());

                // 更新频道数据源
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                // 更新频道切换管理器的频道列表
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                // 更新分组列表
                groupListManager.setGroups(channelSourceList);
                // 更新频道列表显示
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                // 播放当前索引的频道
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                log("【直播源】加载失败：" + errorMsg);
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        // 加载EPG数据
        log("【EPG】加载节目单：" + UrlConfig.EPG_URL);
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 频道列表非空时刷新EPG
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
     * 带防抖处理（300ms内不重复切台），切台方向受channel_reverse配置影响
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        // 防抖：冷却时间内不处理
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        log("【切台】上一台");
        // 根据反转配置选择切换方向
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 播放下一个频道
     * 带防抖处理（300ms内不重复切台），切台方向受channel_reverse配置影响
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        // 防抖：冷却时间内不处理
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        log("【切台】下一台");
        // 根据反转配置选择切换方向
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 播放指定索引的频道
     * @param index 频道在channelSourceList中的索引
     */
    public void playChannel(int index) {
        // 频道列表为空时返回
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            log("【播放】频道列表为空，无法播放");
            return;
        }

        // 索引边界校验（防止越界）
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);

        // 频道/播放地址为空时返回
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            log("【播放】频道地址为空");
            return;
        }

        // 获取播放地址并打印播放日志
        String url = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道名称：" + ch.getName());
        log("【播放】频道地址：" + url);
        log("【播放】当前索引：" + index);
        log("========================================");

        // 设置当前播放频道名称（用于状态监听）
        playerStateListener.setCurrentChannelName(ch.getName());
        // 播放指定URL
        mPlayerManager.playUrl(url);
        // 显示频道号
        showChannelNum(index + 1);
        // 保存本次播放索引（下次启动恢复）
        appConfig.setLastPlayIndex(index);
        // 更新频道列表选中状态
        channelListManager.setChannels(channelSourceList, index);
        // 刷新当前频道的EPG数据
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 更新频道信息栏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            // 移除之前的隐藏任务（避免提前隐藏）
            info_bar.removeCallbacks(hideInfoBar);
            // 2秒后隐藏信息栏
            info_bar.postDelayed(hideInfoBar, 2000);
            // 设置频道名称
            tv_channel_name.setText(ch.getName());
            // 设置直播信息（画质、音频、码率）
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
        }
    }

    /**
     * 显示频道号并延迟隐藏
     * @param num 频道号（索引+1）
     */
    public void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        // 3秒后隐藏频道号
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                tv_channel_num.setVisibility(View.GONE);
            }
        }, 3000);
    }

    /**
     * 初始化频道列表点击事件
     * 点击频道列表项切换到对应频道，并切换控制面板状态
     */
    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                // 当前分组频道列表非空时，按分组内索引查找全局索引
                if (!currentGroupChannelList.isEmpty() && pos < currentGroupChannelList.size()) {
                    Channel selectedChannel = currentGroupChannelList.get(pos);
                    int globalIndex = channelSourceList.indexOf(selectedChannel);
                    if (globalIndex != -1) {
                        log("【列表点击】切换到全局索引：" + globalIndex);
                        playChannel(globalIndex);
                        togglePanel();
                    }
                } else {
                    // 直接按全局索引播放
                    playChannel(pos);
                    togglePanel();
                }
            }
        });
    }

    /**
     * 切换控制面板（频道/EPG面板）的显示/隐藏状态
     * ✅ 修复：打开面板时使用当前选中的日期索引，不强制跳回今天
     */
    public void togglePanel() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
            playerView.requestFocus();
        } else {
            panel_layout.setVisibility(View.VISIBLE);
            // 老版标准：打开面板时刷新频道列表和EPG
            if (channelSourceList != null && !channelSourceList.isEmpty()) {
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                // ✅ 使用当前选中的日期索引，不硬编码0
                epgManagerWrapper.refresh(
                        channelSourceList.get(currentPlayIndex),
                        channelSourceList,
                        currentSelectedDateIndex
                );
            }
        }
    }

    /**
     * 打开设置页面
     */
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 接收远程配置更新（直播源/EPG URL）
     * @param liveUrl 新的直播源URL
     * @param epgUrl 新的EPG URL
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        // 保存自定义URL
        config.setCustomUrls(liveUrl, epgUrl);
        // 更新全局URL配置
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;

        // 打印远程配置日志
        log("【远程配置】更新直播源：" + liveUrl);
        log("【远程配置】更新EPG：" + epgUrl);
        // 主线程重新加载直播源和EPG
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadLiveAndEpg();
            }
        });
    }

    /**
     * 按键按下事件处理
     * 交给按键事件管理器处理，处理完成则拦截事件，否则执行默认逻辑
     * @param keyCode 按键码
     * @param event 按键事件
     * @return true=事件已处理，false=执行默认逻辑
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode);
    }

    /**
     * Activity暂停生命周期方法
     * 应用切到后台时，通知播放器进入后台状态
     */
    @Override
    protected void onPause() {
        super.onPause();
        log("【主页】onPause -> 切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    /**
     * Activity恢复生命周期方法
     * 应用回到前台时，重新加载设置、应用屏幕比例、通知播放器进入前台状态
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
     * Activity销毁生命周期方法
     * 释放资源：注销广播接收器、释放播放器、清空单例
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        // 注销广播接收器（捕获异常避免重复注销）
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        // 释放播放器资源
        mPlayerManager.release();
        // 清空单例
        mInstance = null;
    }
}
