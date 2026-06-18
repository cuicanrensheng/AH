package com.tv.live;

import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.GroupListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.SettingsActivity;
import com.tv.live.util.CacheManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
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
 * 直播主页面Activity
 * 核心功能：直播源加载、频道切换、EPG节目单、播放器控制、手势/按键处理
 *
 * 【播放说明】
 * 直接交给ExoPlayer播放，由ExoPlayer自动跟随重定向
 * TVPlayerManager内部已设置增强版Header（虎牙/斗鱼专属Referer等）
 *
 * 【缓存机制说明】
 * 1. 进入APP时先读缓存，快速显示列表和画面（秒开）
 * 2. 后台同时从网络加载最新数据
 * 3. 网络加载完成后更新列表和缓存
 * 4. 缓存有效期24小时，过期自动失效
 * 5. 直播源和EPG都有缓存
 *
 * 【加载动画说明】
 * 进入APP时显示加载动画，避免黑屏
 * 有缓存时加载动画一闪而过，没有缓存时显示"正在加载..."
 */
public class MainActivity extends AppCompatActivity {
    // Activity单例，供其他类访问
    public static MainActivity mInstance;
    // 所有频道数据源列表（全部频道）
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前选中分组下的频道列表（筛选后的）
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放的频道索引（全局索引）
    public int currentPlayIndex = 0;
    // 当前选中的分组名称（空字符串表示显示全部频道）
    // 用于记住用户的分组筛选状态，避免播放后重置
    private String currentGroupName = "";
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

    // ================================================
    // ✅ 缓存相关成员变量
    // ================================================
    // 缓存管理器
    private CacheManager cacheManager;
    // 是否已用缓存播放过（避免缓存和网络都触发播放，重复播放）
    private boolean hasPlayedWithCache = false;

    // ================================================
    // ✅ 加载动画相关成员变量
    // ================================================
    // 加载视图（加载时显示，避免黑屏）
    private View loadingView;
    // 加载提示文字
    private TextView tv_loading_text;

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
                        // 重新加载（重置缓存标记，强制从网络加载）
                        hasPlayedWithCache = false;
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
                // 保存当前分组名称，记住用户的筛选状态
                currentGroupName = groupName;

                // 筛选该分组下的频道
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (groupName.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                // 更新频道列表显示（只显示当前分组的频道）
                channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);

                log("【分组】选中分组：" + groupName + "，频道数：" + currentGroupChannelList.size());
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

        // ================================================
        // ✅ 初始化缓存管理器和加载视图
        // ================================================
        cacheManager = CacheManager.getInstance(this);
        initLoadingView();
        showLoading("正在加载直播源...");

        // 加载直播源和EPG（带缓存机制）
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

    // ================================================
    // ✅ 加载视图相关方法
    // ================================================

    /**
     * 初始化加载视图
     * 动态添加到根布局，不需要改XML
     * 作用：进入APP加载时显示，避免黑屏
     */
    private void initLoadingView() {
        FrameLayout rootLayout = findViewById(android.R.id.content);

        // 创建加载容器（黑色半透明背景）
        FrameLayout loadingLayout = new FrameLayout(this);
        loadingLayout.setBackgroundColor(0xEE000000); // 黑色半透明
        loadingLayout.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 创建垂直布局（进度条 + 文字）
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams llParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        llParams.gravity = Gravity.CENTER;
        linearLayout.setLayoutParams(llParams);

        // 进度条
        ProgressBar progressBar = new ProgressBar(this);
        linearLayout.addView(progressBar);

        // 加载文字
        tv_loading_text = new TextView(this);
        tv_loading_text.setText("加载中...");
        tv_loading_text.setTextColor(0xFFFFFFFF); // 白色
        tv_loading_text.setTextSize(16);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.setMargins(0, 20, 0, 0);
        tv_loading_text.setLayoutParams(textParams);
        linearLayout.addView(tv_loading_text);

        loadingLayout.addView(linearLayout);
        rootLayout.addView(loadingLayout);

        loadingView = loadingLayout;

        log("【加载】加载视图初始化完成");
    }

    /**
     * 显示加载视图
     * @param text 加载提示文字
     */
    private void showLoading(String text) {
        if (loadingView != null) {
            loadingView.setVisibility(View.VISIBLE);
        }
        if (tv_loading_text != null && text != null) {
            tv_loading_text.setText(text);
        }
    }

    /**
     * 隐藏加载视图
     */
    private void hideLoading() {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
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

    // ================================================
    // ✅ 加载直播源和EPG（带缓存机制 - 核心方法）
    // ================================================

    /**
     * 加载直播源和EPG数据（带缓存）
     *
     * 【缓存策略 - 先缓存后网络】
     * 1. 先读本地缓存，如果有就直接用缓存的数据快速显示（秒开）
     * 2. 同时后台从网络加载最新数据
     * 3. 网络加载成功后，更新列表和缓存
     * 4. EPG不阻塞播放，直播源加载完就开始播放
     *
     * 【避免重复播放】
     * 用 hasPlayedWithCache 标记，缓存播放过了，网络加载完就不再重复播放
     * 只更新列表，不重新播放
     */
    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");

        // ================================================
        // 第一步：先读缓存，快速显示
        // ================================================
        String cacheContent = cacheManager.getFileCache("live_source");
        if (cacheContent != null && !cacheContent.isEmpty()) {
            log("【缓存】找到直播源缓存，快速显示");

            // 用缓存解析并显示
            List<Channel> cacheChannels = parseLiveSource(cacheContent);
            if (cacheChannels != null && !cacheChannels.isEmpty()) {
                channelSourceList.clear();
                channelSourceList.addAll(cacheChannels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);

                // 有缓存就先播放（秒出画面）
                if (!hasPlayedWithCache) {
                    playChannel(currentPlayIndex);
                    hasPlayedWithCache = true;
                }

                hideLoading();
                log("【缓存】直播源缓存加载完成，频道数：" + cacheChannels.size());

                // 同时尝试加载EPG缓存
                loadEpgCache();
            }
        }

        // ================================================
        // 第二步：后台从网络加载最新数据
        // ================================================
        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【网络】直播源加载成功，频道总数：" + channels.size());

                // 更新列表（用最新数据替换缓存数据）
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);

                // 如果之前没播放过（没有缓存），现在播放
                if (!hasPlayedWithCache) {
                    playChannel(currentPlayIndex);
                    hasPlayedWithCache = true;
                }

                hideLoading();
                log("【网络】直播源列表已更新");

                // ================================================
                // 第三步：后台加载EPG（不阻塞播放）
                // ================================================
                loadEpg();
            }

            @Override
            public void onError(String errorMsg) {
                log("【网络】直播源加载失败：" + errorMsg);

                // 如果有缓存，就用缓存，不提示错误
                if (channelSourceList.isEmpty()) {
                    hideLoading();
                    Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
                } else {
                    log("【缓存】使用缓存数据继续播放");
                    hideLoading();
                }

                // 即使直播源加载失败，也试试加载EPG缓存
                loadEpgCache();
            }
        });
    }

    /**
     * 从缓存加载EPG（快速显示）
     */
    private void loadEpgCache() {
        if (!epg_enable) return;

        log("【EPG】尝试从缓存加载...");
        // EPG的缓存加载在 EpgManager 里实现
        // 这里只触发刷新UI
        if (!channelSourceList.isEmpty()) {
            epgManagerWrapper.refresh(
                    channelSourceList.get(currentPlayIndex),
                    channelSourceList,
                    currentSelectedDateIndex);
        }
    }

    /**
     * 从网络加载EPG（后台刷新）
     */
    private void loadEpg() {
        if (!epg_enable) return;

        log("【EPG】开始加载节目单...");
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        log("【EPG】最新节目单加载完成");
                        if (!channelSourceList.isEmpty()) {
                            epgManagerWrapper.refresh(
                                    channelSourceList.get(currentPlayIndex),
                                    channelSourceList,
                                    currentSelectedDateIndex);
                        }
                    }
                });
            }
        });
    }

    /**
     * 解析M3U直播源文本
     *
     * 【注意】
     * 这里是一个通用的M3U解析实现，用于快速解析缓存
     * 网络加载时还是用 PlaylistParser.parse 做正式解析
     * 如果你的M3U格式特殊，可以根据实际情况调整
     *
     * @param content M3U文本内容
     * @return 频道列表
     */
    private List<Channel> parseLiveSource(String content) {
        List<Channel> channels = new ArrayList<>();
        if (TextUtils.isEmpty(content)) {
            return channels;
        }

        String[] lines = content.split("\n");
        String currentName = "";
        String currentGroup = "";
        String currentLogo = "";
        String currentTvgId = "";

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("#EXTINF:")) {
                // 解析频道信息
                // 格式示例：#EXTINF:-1 tvg-id="xxx" tvg-logo="xxx" group-title="xxx",频道名称
                int commaIndex = line.indexOf(",");
                if (commaIndex > 0 && commaIndex < line.length() - 1) {
                    currentName = line.substring(commaIndex + 1).trim();
                }
                // 解析group-title（分组名称）
                int groupIndex = line.indexOf("group-title=\"");
                if (groupIndex > 0) {
                    int groupEnd = line.indexOf("\"", groupIndex + 13);
                    if (groupEnd > groupIndex) {
                        currentGroup = line.substring(groupIndex + 13, groupEnd);
                    }
                }
                // 解析tvg-id（EPG频道ID）
                int tvgIndex = line.indexOf("tvg-id=\"");
                if (tvgIndex > 0) {
                    int tvgEnd = line.indexOf("\"", tvgIndex + 8);
                    if (tvgEnd > tvgIndex) {
                        currentTvgId = line.substring(tvgIndex + 8, tvgEnd);
                    }
                }
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                // 播放地址
                String playUrl = line;
                if (!TextUtils.isEmpty(currentName) && !TextUtils.isEmpty(playUrl)) {
                    channels.add(new Channel(currentName, playUrl, currentGroup, currentTvgId));
                }
                // 重置，准备下一个频道
                currentName = "";
                currentGroup = "";
                currentLogo = "";
                currentTvgId = "";
            }
        }

        log("【缓存】解析完成，共 " + channels.size() + " 个频道");
        return channels;
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

    /**
     * 播放指定索引的频道
     * 直接交给ExoPlayer播放，由ExoPlayer自动跟随重定向
     *
     * 【已修改】保持分组筛选状态
     * 之前的问题：播放频道时调用 setChannels(全部频道)，导致分组筛选丢失
     * 现在的修复：判断当前是否有分组筛选，有就保持分组筛选状态
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

        final String playUrl = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道名称：" + ch.getName());
        log("【播放】播放地址：" + playUrl);
        log("【播放】当前索引：" + index);
        log("【播放】交给ExoPlayer自动跟随重定向");
        log("========================================");

        // 更新播放器状态监听器的当前频道名
        playerStateListener.setCurrentChannelName(ch.getName());
        // 显示频道号
        showChannelNum(index + 1);
        // 保存上次播放的频道索引
        appConfig.setLastPlayIndex(index);

        // 更新频道列表选中状态（保持分组筛选）
        if (!TextUtils.isEmpty(currentGroupName) && !currentGroupChannelList.isEmpty()) {
            // 分组筛选模式下：保持分组筛选，只显示当前分组的频道
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        } else {
            // 非分组模式：显示全部频道
            channelListManager.setChannels(channelSourceList, index);
        }

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

        // 直接交给播放器播放
        mPlayerManager.playUrl(playUrl);
    }
 }
    /**
     * 显示频道号（延迟3秒自动隐藏）
     * @param num 频道号
     */
