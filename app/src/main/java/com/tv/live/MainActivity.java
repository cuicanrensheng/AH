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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
/**
 * 直播主页面 Activity
 *
 * 【核心功能】
 * 1. 直播源加载（M3U 格式，支持缓存）
 * 2. 频道切换（分组内循环）
 * 3. EPG 节目单展示（支持日期切换）
 * 4. 播放器控制（基于 ExoPlayer）
 * 5. 手势/按键处理（遥控器、数字选台）
 * 6. 频道分组管理
 * 7. 设置页面跳转
 *
 * 【屏幕方向】
 * 使用 sensorLandscape，支持左横屏/右横屏自动旋转，
 * 但不会变成竖屏，保证观看体验。
 *
 * 【电视兼容说明】
 * 所有全面屏适配代码都加了 try-catch，
 * 即使电视不支持这些 API，也不会崩溃，只是不显示全屏效果而已。
 *
 * 【Toast 屏蔽说明】
 * 所有 Toast 提示已全部屏蔽，避免干扰观看体验。
 *
 * 【加载超时保护】
 * 15 秒还没加载完自动隐藏加载动画，避免一直卡在加载界面。
 *
 * 【进入设置不暂停】
 * 打开设置页面时，播放器继续播放，不会暂停。
 * 只有真正切到后台（按 Home 键、切到其他 APP）才会暂停。
 * 用 isOpeningSettings 标志位区分两种场景。
 *
 * 【缓存机制】
 * - 直播源缓存：24 小时过期，文件缓存
 * - EPG 缓存：24 小时过期，文件缓存
 * - 进入 APP 先读缓存快速显示，后台再刷新最新数据
 *
 * 【分组内循环切台】
 * 上/下切台只在当前分组内循环，不会切到其他分组。
 * 到分组末尾时自动回到开头（循环）。
 *
 * 【三栏式面板布局】
 * 从左到右：频道分组 → 频道列表 → 节目单按钮
 * 点击节目单按钮后展开节目单（日期 + 节目列表）
 *
 * 【信息栏显示逻辑】
 * - 打开面板时：信息栏 + 右上角（频道号+日期+时间）都显示
 * - 关闭面板时：信息栏 + 右上角都隐藏
 * - 切台时（面板未打开）：信息栏 + 频道号显示 5 秒后自动隐藏
 */
public class MainActivity extends AppCompatActivity {
    // ====================== 单例与数据 ======================
    /** Activity 单例，供其他类访问 */
    public static MainActivity mInstance;
    /** 所有频道数据源列表（全部频道，未筛选） */
    public List<Channel> channelSourceList = new ArrayList<>();
    /** 当前选中分组下的频道列表（筛选后的） */
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    /** 当前正在播放的频道索引（全局索引，对应 channelSourceList） */
    public int currentPlayIndex = 0;
    /**
     * 当前选中的分组名称
     * 空字符串表示显示全部频道（无分组筛选）
     */
    private String currentGroupName = "";
    // ====================== 视图相关 ======================
    /** 面板布局（频道列表 + EPG 面板的整体容器） */
    private View panel_layout;
    /** 播放器视图（ExoPlayer 的 PlayerView） */
    private PlayerView playerView;
    /** 频道号显示（数字选台时弹出） */
    private TextView tv_channel_num;
    /** 底部信息栏（频道名、画质、节目信息等） */
    private View info_bar;
    private TextView tv_channel_name;      // 频道名称
    private TextView tv_tag_fhd;          // 画质标签（FHD/HD 等）
    private TextView tv_tag_audio;        // 音频信息
    private TextView tv_bitrate;          // 码率
    private TextView tv_current_program_name;   // 当前节目名称
    private TextView tv_current_time_range;     // 当前节目时间范围
    private android.widget.ProgressBar progress_program; // 节目进度条
    private TextView tv_remaining_time;         // 剩余时间
    private TextView tv_next_program_name;      // 下一个节目名称
    private TextView tv_next_time_range;        // 下一个节目时间范围
    // ====================== 管理器相关 ======================
    /** 播放器管理器（单例，基于 ExoPlayer 封装） */
    public TVPlayerManager mPlayerManager;
    /** 应用配置管理（SP 封装） */
    private AppConfig appConfig;
    /** 屏幕比例管理（全屏/填充/原始） */
    private ScreenRatioManager screenRatioManager;
    /** 面板管理（控制频道面板、节目单的显示隐藏） */
    private PanelManager panelManager;
    /** 手势管理（滑动、点击等手势处理） */
    private GestureManager gestureManager;
    /** 按键事件管理（遥控器按键分发） */
    private KeyEventManager keyEventManager;
    /** 频道列表管理（右侧频道列表） */
    private ChannelListManager channelListManager;
    /** 分组列表管理（左侧分组列表） */
    private GroupListManager groupListManager;
    /** 日期列表管理（EPG 日期选择，8 天） */
    private DateListManager dateListManager;
    /** EPG 节目单管理包装类（数据筛选 + UI 刷新） */
    private EpgManagerWrapper epgManagerWrapper;
    /** 播放器状态监听器（空实现，不弹 Toast） */
    private PlayerStateListenerImpl playerStateListener;
    /** 频道切换管理（单例） */
    private ChannelSwitchManager switchManager;
    // ====================== 状态标志 ======================
    /** EPG 面板是否展开 */
    private boolean epgPanelOpen = false;
    /** 播放器控制器是否可见（已屏蔽原生控制器，保留备用） */
    private boolean isControllerVisible = false;
    /** EPG 功能是否启用 */
    private boolean epg_enable;
    /** 频道切换是否反向（上键=下一台，下键=上一台） */
    private boolean channel_reverse;
    /** 数字选台是否启用 */
    private boolean number_channel_enable;
    /** 直播源是否自动更新 */
    private boolean auto_update_source;
    /** 当前选中的日期索引（用于 EPG 节目单） */
    private int currentSelectedDateIndex = 0;
    /** 配置存储（SharedPreferences） */
    private SharedPreferences sp;
    // ====================== 缓存与加载 ======================
    /** 缓存管理器（文件缓存 + SP 缓存） */
    private CacheManager cacheManager;
    /** 是否已用缓存播放过（防止网络加载完成后重复播放） */
    private boolean hasPlayedWithCache = false;
    /** 加载视图（黑色半透明背景 + 进度条 + 文字） */
    private View loadingView;
    /** 加载文字提示 */
    private TextView tv_loading_text;
    // ====================== 数字选台相关 ======================
    /** 数字选台输入缓冲 */
    private StringBuilder channelNumInput = new StringBuilder();
    /** 数字选台超时 Handler */
    private Handler channelNumHandler = new Handler(Looper.getMainLooper());
    /** 数字选台超时时间（毫秒），2 秒没输入就自动确认 */
    private static final long CHANNEL_NUM_TIMEOUT = 2000;
    // ====================== 进入设置不暂停 ======================
    /**
     * 是否正在打开设置页面
     *
     * 【作用】
     * 用于区分 onPause 时是"去设置页面"还是"真的切后台"。
     * - true：打开设置页面，不暂停播放
     * - false：真的切后台，正常暂停
     *
     * 【为什么需要这个？】
     * 打开 SettingsActivity 时，MainActivity 会走 onPause 生命周期。
     * 如果 onPause 里直接暂停播放器，进入设置就会暂停播放。
     * 用这个标志位判断，只有真正切后台才暂停。
     */
    private boolean isOpeningSettings = false;
    // ====================== 其他 ======================
    /** 隐藏信息栏的 Runnable（延迟 5 秒隐藏） */
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };
    /** 数字选台超时自动确认的 Runnable */
    private final Runnable channelNumConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            confirmChannelNum();
        }
    };
    /** 上次频道切换时间（用于防抖动） */
    private long lastChannelChangeTime = 0;
    /** 频道切换冷却时间（毫秒），300ms 内不允许连续切台 */
    private static final long CHANNEL_COOLDOWN = 300;
    /** 触摸起始 Y 坐标（手势判断用） */
    private float touchStartY = 0;
    /** 滑动阈值（像素），超过才算滑动 */
    private static final float SLIDE_THRESHOLD = 80;
    // ====================================================================
    // ✅ 新增：节目进度定时更新
    // ====================================================================
    /**
     * 节目进度更新间隔（1 分钟）
     * 节目进度不需要每秒更新，每分钟更新一次就够了
     */
    private static final long PROGRAM_PROGRESS_INTERVAL = 60000;
    /**
     * 节目进度更新 Handler
     */
    private Handler programProgressHandler = new Handler(Looper.getMainLooper());
    /**
     * 节目进度更新 Runnable
     * 每分钟更新一次信息栏的节目进度和剩余时间
     */
    private final Runnable updateProgramProgressRunnable = new Runnable() {
        @Override
        public void run() {
            // 如果有正在播放的频道，就更新一下进度
            if (channelSourceList != null && !channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel channel = channelSourceList.get(currentPlayIndex);
                updateInfoBarEpg(channel);
            }
            // 继续下一次更新
            programProgressHandler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    };
    // ====================================================================
    // ✅ 新增：右上角时间更新
    // ====================================================================
    /**
     * 右上角时间更新 Handler
     */
    private Handler timeHandler = new Handler(Looper.getMainLooper());
    /**
     * 右上角时间更新 Runnable
     * 每秒更新一次日期和时间
     */
    private final Runnable timeUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateTopRightTime();
            timeHandler.postDelayed(this, 1000);
        }
    };
    // ====================== 日志相关 ======================
    /** 本地日志列表（保留最近 100 条） */
    public static List<String> logList = new ArrayList<>();
    /**
     * 记录日志
     * 同时保存到本地列表和 SettingsActivity 的全局日志
     *
     * @param msg 日志内容
     */
    public static void log(String msg) {
        logList.add(0, msg);
        // 只保留最近 100 条，防止内存溢出
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        // 同步到 SettingsActivity 的全局日志
        SettingsActivity.log(msg);
    }
    // ====================== 广播接收器 ======================
    /** 切换播放器控制器的广播接收器（备用，当前已屏蔽原生控制器） */
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };
    /**
     * 刷新直播源/EPG 的广播接收器
     *
     * 【触发场景】
     * 1. 设置页面切换了直播源/EPG 地址
     * 2. 自动更新源的定时任务触发
     * 3. 网页后台修改了配置
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
                        // 应用自定义直播源/EPG 地址
                        String customLive = appConfig.getCustomLiveUrl();
                        String customEpg = appConfig.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        // 重置缓存播放标志，让新数据重新播放
                        hasPlayedWithCache = false;
                        // 重新加载直播源和 EPG
                        loadLiveAndEpg();
                        SettingsActivity.logOperation("【系统】自动刷新直播源/EPG");
                    }
                });
            }
        }
    };
    // ====================== onCreate 生命周期 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");
        mInstance = this;
        // ===== 自动旋转横屏 =====
        // sensorLandscape：支持左横屏/右横屏自动旋转，但不会变成竖屏
        // 比固定 LANDSCAPE 更灵活，适合手机/平板使用
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        // ================================================
        // ✅ 全面屏适配（第一部分）- 加 try-catch 确保电视不崩溃
        // ================================================
        try {
            // 1. 刘海屏适配（Android P 及以上）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+：always 模式，所有边都允许布局到刘海区域
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                } else {
                    // Android 9-11：shortEdges 模式，只在短边（上下）允许布局到刘海区域
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                getWindow().setAttributes(lp);
            }
            // 2. 全屏标志
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
            // 3. Android 10 及以下的沉浸式（旧方式）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
        } catch (Exception e) {
            // ✅ 全面屏适配失败不影响正常使用
            e.printStackTrace();
            log("【适配】全面屏适配（第一部分）失败：" + e.getMessage());
        }
        // 加载布局
        setContentView(R.layout.activity_main);
        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // ================================================
        // ✅ 全面屏适配（第二部分）- 加 try-catch 确保电视不崩溃
        // ================================================
        try {
            // 4. Android 11+ 的 WindowInsetsController（新方式）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    // 隐藏系统栏（状态栏 + 导航栏）
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    // 临时显示行为：滑动显示，过一会自动隐藏
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
                // 让内容布局到系统栏下面（沉浸式）
                getWindow().setDecorFitsSystemWindows(false);
            }
        } catch (Exception e) {
            // ✅ 全面屏适配失败不影响正常使用
            e.printStackTrace();
            log("【适配】全面屏适配（第二部分）失败：" + e.getMessage());
        }
        // 绑定频道号显示
        tv_channel_num = findViewById(R.id.tv_channel_num);
        // 初始化底部信息栏
        initInfoBar();
        // 初始化配置
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        // 应用自定义直播源/EPG 地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);
        // 绑定播放器视图
        playerView = findViewById(R.id.player_view);
        // 屏蔽原生控制器（防止弹出"播放异常"等文字）
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);
        // 绑定面板布局
        panel_layout = findViewById(R.id.panel_layout);
        // 绑定各类 ListView
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);
        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));
        // ====================================================================
        // ✅ 修改：EPG 展开按钮点击事件（使用 PanelManager 管理）
        // ====================================================================
        btn_show_epg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!epg_enable) {
                    // EPG 功能已关闭
                    SettingsActivity.logOperation("【EPG】节目单功能已关闭，无法展开");
                    return;
                }
                // 切换 EPG 面板展开/收起（通过 PanelManager 管理）
                panelManager.toggleEpgPanel();
                SettingsActivity.logOperation("【EPG】" + (panelManager.isEpgExpanded() ? "展开" : "收起") + "节目单");
                // 如果展开了，刷新当前频道的节目单
                if (panelManager.isEpgExpanded() && !channelSourceList.isEmpty()) {
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                }
            }
        });
        // ===== 分组列表点击事件 =====
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 更新分组选中高亮
                groupListManager.setSelectedPosition(position);
                lvGroup.setItemChecked(position, true);
                lvGroup.setSelection(position);
                // 保存当前分组名称（用于分组筛选状态保持）
                String groupName = groupListManager.getCurrentGroup(position);
                currentGroupName = groupName;
                // 筛选当前分组的频道
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (groupName.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                // 更新频道列表（按分组筛选）
                channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
                SettingsActivity.logOperation("【分组】选中分组：" + groupName
                        + "，频道数：" + currentGroupChannelList.size());
                log("【分组】选中分组：" + groupName + "，频道数：" + currentGroupChannelList.size());
            }
        });
        // ===== 初始化各类管理器 =====
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
         // ✅ 先初始化 EpgManager（必须在 EpgManagerWrapper 之前）
        EpgManager.getInstance(this);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);
        // 日期选中监听
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;
            panelManager.setCurrentDateIndex(pos);
            if (channelSourceList != null && !channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });
        // ===== 初始化播放器 =====
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        // 直播信息更新监听（画质、音频、码率）
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
                
                // ====================================================================
                // ✅ 新增：如果信息栏正在显示，也同步更新一下
                // ====================================================================
                if (info_bar != null && info_bar.getVisibility() == View.VISIBLE) {
                    tv_tag_fhd.setText(info.quality);
                    tv_tag_audio.setText(info.audio);
                    tv_bitrate.setText(info.bitrate);
                }
            }
        });
        // 屏幕比例管理
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        // 手势管理
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        // 播放器触摸事件
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
        // 初始化缓存和加载视图
        cacheManager = CacheManager.getInstance(this);
        initLoadingView();
        showLoading("正在加载直播源...");
        // 加载直播源和 EPG
        loadLiveAndEpg();
        initListViewClick();
    }
    // ====================== 底部信息栏初始化 ======================
    /**
     * 初始化底部信息栏的各个控件
     * 显示频道名称、画质、当前节目、下一个节目等信息
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
    // ====================================================================
    // ✅ 新增：更新右上角日期和时间
    // ====================================================================
    /**
     * 更新右上角的日期和实时时间
     * 打开面板时每秒调用一次
     */
    private void updateTopRightTime() {
        try {
            Calendar cal = Calendar.getInstance();
            // 日期：06/19
            String dateStr = String.format("%02d/%02d",
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH));
            // 星期：周五
            int w = cal.get(Calendar.DAY_OF_WEEK);
            String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            String weekStr = weekMap[w - 1];
            // 时间：09:57:15
            String timeStr = String.format("%02d:%02d:%02d",
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    cal.get(Calendar.SECOND));
            
            TextView tvDate = findViewById(R.id.tv_date);
            TextView tvTime = findViewById(R.id.tv_time);
            if (tvDate != null) {
                tvDate.setText(dateStr + " " + weekStr);
            }
            if (tvTime != null) {
                tvTime.setText(timeStr);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // ====================== 加载视图相关 ======================
    /**
     * 初始化加载视图
     * 动态添加到根布局，不需要改 XML
     *
     * 【结构】
     * - 黑色半透明背景
     * - 圆形进度条
     * - 加载文字提示
     */
    private void initLoadingView() {
        FrameLayout rootLayout = findViewById(android.R.id.content);
        // 加载容器（黑色半透明背景）
        FrameLayout loadingLayout = new FrameLayout(this);
        loadingLayout.setBackgroundColor(0xEE000000);
        loadingLayout.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        // 垂直布局（进度条 + 文字）
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams llParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        llParams.gravity = Gravity.CENTER;
        linearLayout.setLayoutParams(llParams);
        // 圆形进度条
        ProgressBar progressBar = new ProgressBar(this);
        linearLayout.addView(progressBar);
        // 加载文字
        tv_loading_text = new TextView(this);
        tv_loading_text.setText("加载中...");
        tv_loading_text.setTextColor(0xFFFFFFFF);
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
     * 显示加载动画
     *
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
     * 隐藏加载动画
     */
    private void hideLoading() {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
    }
    // ====================== 设置加载 ======================
    /**
     * 从 SharedPreferences 加载各项设置
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
        log("【设置】EPG开关：" + epg_enable);
        log("【设置】切台反转：" + channel_reverse);
        log("【设置】数字选台：" + number_channel_enable);
        log("【设置】自动更新源：" + auto_update_source);
    }
    // ====================== 返回键处理 ======================
    @Override
    public void onBackPressed() {
        // 如果正在输入数字选台，先取消输入
        if (channelNumInput.length() > 0) {
            channelNumInput.setLength(0);
            channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
            tv_channel_num.setVisibility(View.GONE);
            SettingsActivity.logOperation("【数字选台】取消输入");
            return;
        }
        // 如果节目单展开着，先收起节目单
        if (panelManager != null && panelManager.isEpgExpanded()) {
            panelManager.toggleEpgPanel();
            SettingsActivity.logOperation("【EPG】返回键收起节目单");
            return;
        }
        // 如果面板打开着，先关闭面板
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
            playerView.requestFocus();
            SettingsActivity.logOperation("【面板】关闭频道面板");
            // 关闭面板时隐藏信息栏和右上角
            info_bar.setVisibility(View.GONE);
            View topRight = findViewById(R.id.ll_top_right);
            if (topRight != null) topRight.setVisibility(View.GONE);
            timeHandler.removeCallbacks(timeUpdateRunnable);
            return;
        }
        // 否则正常返回
        super.onBackPressed();
    }
    // ====================== 数字选台 ======================
    /**
     * 处理数字按键（数字选台功能）
     *
     * @param keyCode 按键码
     * @return 是否处理了该按键
     */
    private boolean handleNumberKey(int keyCode) {
        if (!number_channel_enable) return false;
        int num = -1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_0: num = 0; break;
            case KeyEvent.KEYCODE_1: num = 1; break;
            case KeyEvent.KEYCODE_2: num = 2; break;
            case KeyEvent.KEYCODE_3: num = 3; break;
            case KeyEvent.KEYCODE_4: num = 4; break;
            case KeyEvent.KEYCODE_5: num = 5; break;
            case KeyEvent.KEYCODE_6: num = 6; break;
            case KeyEvent.KEYCODE_7: num = 7; break;
            case KeyEvent.KEYCODE_8: num = 8; break;
            case KeyEvent.KEYCODE_9: num = 9; break;
            default: return false;
        }
        // 追加到输入缓冲
        channelNumInput.append(num);
        tv_channel_num.setText(channelNumInput.toString());
        tv_channel_num.setVisibility(View.VISIBLE);
        // 重置超时计时器
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumHandler.postDelayed(channelNumConfirmRunnable, CHANNEL_NUM_TIMEOUT);
        SettingsActivity.logOperation("【数字选台】输入：" + channelNumInput);
        return true;
    }
    /**
     * 确认数字选台（超时或按确认键时调用）
     */
    private void confirmChannelNum() {
        if (channelNumInput.length() == 0) return;
        try {
            int channelNum = Integer.parseInt(channelNumInput.toString());
            if (channelNum >= 1 && channelNum <= channelSourceList.size()) {
                int index = channelNum - 1;
                SettingsActivity.logOperation("【数字选台】切换到第 " + channelNum + " 频道");
                playChannel(index);
            } else {
                SettingsActivity.logOperation("【数字选台】频道号不存在：" + channelNum);
            }
        } catch (NumberFormatException e) {
            // 忽略解析错误
        }
        // 清空输入
        channelNumInput.setLength(0);
        // 延迟 5 秒隐藏频道号显示（和信息栏保持一致）
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                tv_channel_num.setVisibility(View.GONE);
            }
        }, 5000);
    }
    // ====================== 方向键处理 ======================
    /**
     * 处理方向按键
     *
     * 【按键映射】
     * - 上/下键：切换频道（受换台反转影响）
     * - 确认/OK键：切换面板显示（或确认数字选台）
     * - 左/右键：切换面板显示
     *
     * @param keyCode 按键码
     * @return 是否处理了该按键
     */
    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                // 上键
                if (channel_reverse) {
                    playNext();  // 反转：上键 = 下一台
                } else {
                    playPrev();  // 正常：上键 = 上一台
                }
                SettingsActivity.logOperation("【切台】上键 → "
                        + (channel_reverse ? "下一台" : "上一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 下键
                if (channel_reverse) {
                    playPrev();  // 反转：下键 = 上一台
                } else {
                    playNext();  // 正常：下键 = 下一台
                }
                SettingsActivity.logOperation("【切台】下键 → "
                        + (channel_reverse ? "上一台" : "下一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 确认键
                if (channelNumInput.length() > 0) {
                    // 如果正在输入数字选台，确认输入
                    channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
                    confirmChannelNum();
                    return true;
                }
                // 否则切换面板显示
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 左右键：切换面板显示
                togglePanel();
                return true;
            default:
                return false;
        }
    }
    // ====================== 按键分发 ======================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 优先处理数字选台
        if (handleNumberKey(keyCode)) return true;
        // 然后处理方向键
        if (handleDirectionKey(keyCode)) return true;
        // 最后交给按键事件管理器
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }
    // ====================== 直播源 & EPG 加载 ======================
    /**
     * 加载直播源和 EPG 节目单
     *
     * 【加载策略】
     * 1. 先读缓存，快速显示（秒开）
     * 2. 后台网络加载最新数据
     * 3. 网络加载完成后更新列表
     * 4. 有缓存时不重复播放（防止闪烁）
     *
     * 【超时保护】
     * 15 秒还没加载完自动隐藏加载动画，避免一直卡在加载界面。
     */
    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");
        // ================================================
        // ✅ 新加：加载超时保护（15 秒）
        // 防止网络异常时一直卡在加载界面
        // ================================================
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (loadingView != null && loadingView.getVisibility() == View.VISIBLE) {
                    log("【加载】超时，自动隐藏加载动画");
                    if (channelSourceList.isEmpty()) {
                        // 频道列表为空，显示错误提示
                        if (tv_loading_text != null) {
                            tv_loading_text.setText("加载失败，请检查网络或稍后重试");
                        }
                        hideLoading();
                        SettingsActivity.logOperation("【加载】直播源加载超时");
                    } else {
                        // 有缓存数据，直接隐藏加载动画
                        hideLoading();
                    }
                }
            }
        }, 15000);
        // ===== 第一步：先读缓存，快速显示 =====
        String cacheContent = cacheManager.getFileCache("live_source");
        if (cacheContent != null && !cacheContent.isEmpty()) {
            log("【缓存】找到直播源缓存，快速显示");
            List<Channel> cacheChannels = parseLiveSource(cacheContent);
            if (cacheChannels != null && !cacheChannels.isEmpty()) {
                // 更新频道列表
                channelSourceList.clear();
                channelSourceList.addAll(cacheChannels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                // 用缓存播放一次（防止重复播放）
                if (!hasPlayedWithCache) {
                    playChannel(currentPlayIndex);
                    hasPlayedWithCache = true;
                }
                hideLoading();
                log("【缓存】直播源缓存加载完成，频道数：" + cacheChannels.size());
                // 同时加载 EPG 缓存
                loadEpgCache();
            }
        }
        // ===== 第二步：后台网络加载最新数据 =====
        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【网络】直播源加载成功，频道总数：" + channels.size());
                // 更新频道列表
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                // 如果还没用缓存播放过，就播放
                if (!hasPlayedWithCache) {
                    playChannel(currentPlayIndex);
                    hasPlayedWithCache = true;
                }
                hideLoading();
                log("【网络】直播源列表已更新");
                // 加载最新 EPG
                loadEpg();
            }
            @Override
            public void onError(String errorMsg) {
                log("【网络】直播源加载失败：" + errorMsg);
                if (channelSourceList.isEmpty()) {
                    // 没有缓存，加载失败
                    hideLoading();
                    SettingsActivity.logOperation("【加载】直播源加载失败：" + errorMsg);
                                      } else {
                    // 有缓存，继续用缓存
                    log("【缓存】使用缓存数据继续播放");
                    hideLoading();
                }
                // 尝试加载 EPG 缓存
                loadEpgCache();
            }
        });
    }
    /**
     * 从缓存加载 EPG 节目单
     */
    private void loadEpgCache() {
        if (!epg_enable) return;
        log("【EPG】尝试从缓存加载...");
        if (!channelSourceList.isEmpty()) {
            epgManagerWrapper.refresh(
                    channelSourceList.get(currentPlayIndex),
                    channelSourceList,
                    currentSelectedDateIndex);
            
            // ====================================================================
            // ✅ 新增：EPG 缓存加载完成后，更新信息栏的节目信息
            // ====================================================================
            Channel curr = channelSourceList.get(currentPlayIndex);
            updateInfoBarEpg(curr);
        }
    }
    /**
     * 从网络加载 EPG 节目单
     */
    private void loadEpg() {
        if (!epg_enable) return;
        log("【EPG】开始加载节目单...");
        EpgManager.getInstance(this).setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance(this).loadEpg(new Runnable() {
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
                            
                            // ====================================================================
                            // ✅ 新增：EPG 加载完成后，更新信息栏的节目信息
                            // ====================================================================
                            Channel curr = channelSourceList.get(currentPlayIndex);
                            updateInfoBarEpg(curr);
                        }
                    }
                });
            }
        });
    }
    /**
     * 解析直播源内容（M3U 格式）
     *
     * @param content M3U 文件内容
     * @return 解析后的频道列表
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
                // #EXTINF 行：包含频道名称、分组、logo 等信息
                int commaIndex = line.indexOf(",");
                if (commaIndex > 0 && commaIndex < line.length() - 1) {
                    currentName = line.substring(commaIndex + 1).trim();
                }
                // 提取分组名称
                int groupIndex = line.indexOf("group-title=\"");
                if (groupIndex > 0) {
                    int groupEnd = line.indexOf("\"", groupIndex + 13);
                    if (groupEnd > groupIndex) {
                        currentGroup = line.substring(groupIndex + 13, groupEnd);
                    }
                }
                // 提取 tvg-id
                int tvgIndex = line.indexOf("tvg-id=\"");
                if (tvgIndex > 0) {
                    int tvgEnd = line.indexOf("\"", tvgIndex + 8);
                    if (tvgEnd > tvgIndex) {
                        currentTvgId = line.substring(tvgIndex + 8, tvgEnd);
                    }
                }
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                // 播放地址行
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
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        log("【切台】上一台");
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) return;
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;
        int prevGroupIndex = (groupIndex - 1 + groupChannels.size()) % groupChannels.size();
        Channel prevChannel = groupChannels.get(prevGroupIndex);
        int globalIndex = channelSourceList.indexOf(prevChannel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
    }
    /**
     * 播放下一个频道（分组内循环）
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        log("【切台】下一台");
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) return;
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;
        int nextGroupIndex = (groupIndex + 1) % groupChannels.size();
        Channel nextChannel = groupChannels.get(nextGroupIndex);
        int globalIndex = channelSourceList.indexOf(nextChannel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
    }
    /**
     * 播放指定索引的频道
     *
     * @param index 频道在 channelSourceList 中的全局索引
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
        final String playUrl = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道名称：" + ch.getName());
        log("【播放】播放地址：" + playUrl);
        log("【播放】当前索引：" + index);
        log("========================================");
        playerStateListener.setCurrentChannelName(ch.getName());
        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);
        if (!TextUtils.isEmpty(currentGroupName) && !currentGroupChannelList.isEmpty()) {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        } else {
            channelListManager.setChannels(channelSourceList, index);
        }
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
        
        // ====================================================================
        // ✅ 优化：按键即加载，先播放再做 UI 动画
        // ====================================================================
        /**
         * 【为什么要把 playUrl 移到最前面？】
         *
         * 原来的顺序是：
         * 1. 更新 UI（选中状态、信息栏等）
         * 2. 调用 mPlayerManager.playUrl() 开始加载
         *
         * 这样会浪费 100-300ms 的时间在 UI 动画上。
         *
         * 优化后的顺序是：
         * 1. 先调用 mPlayerManager.playUrl() 开始加载
         * 2. 再更新 UI
         *
         * 这样播放器可以提前 100-300ms 开始加载数据，
         * 加上 TVPlayerManager 里的快速出画优化，
         * 整体切台速度能快 0.5-1 秒。
         */
        // 先播放（最重要的事情先做）
        mPlayerManager.playUrl(playUrl);
        
        // ====================================================================
        // ✅ 修改：信息栏显示逻辑
        // ====================================================================
        /**
         * 【显示逻辑说明】
         *
         * 场景 1：面板已打开
         *   - 信息栏已经显示（打开面板时显示的）
         *   - 只需要更新内容，不需要改变显示状态
         *
         * 场景 2：面板未打开（直接按上下键切台）
         *   - 显示信息栏
         *   - 5 秒后自动隐藏
         */
        if (info_bar != null) {
            // 如果面板没打开，才需要显示信息栏（打开面板时已经显示了）
            if (panel_layout.getVisibility() != View.VISIBLE) {
                info_bar.setVisibility(View.VISIBLE);
                info_bar.removeCallbacks(hideInfoBar);
                // ✅ 5 秒后自动隐藏
                info_bar.postDelayed(hideInfoBar, 5000);
            }
            // 更新信息栏内容
            tv_channel_name.setText(ch.getName());
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
            
            // 更新信息栏的 EPG 节目信息
            updateInfoBarEpg(ch);
            
            // 重启节目进度定时更新
            programProgressHandler.removeCallbacks(updateProgramProgressRunnable);
            programProgressHandler.postDelayed(updateProgramProgressRunnable, PROGRAM_PROGRESS_INTERVAL);
        }
    }
    /**
     * 显示频道号（右上角弹出）
     *
     * @param num 频道号
     */
    public void showChannelNum(int num) {
        // ====================================================================
        // ✅ 修改：频道号显示逻辑
        // ====================================================================
        /**
         * 【显示逻辑说明】
         *
         * 场景 1：面板已打开
         *   - 右上角已经显示频道号+日期+时间（打开面板时显示的）
         *   - 只需要更新频道号数字，不需要改变显示状态
         *
         * 场景 2：面板未打开（直接按上下键切台）
         *   - 显示单独的频道号
         *   - 5 秒后自动隐藏
         */
        if (panel_layout.getVisibility() == View.VISIBLE) {
            // 面板已打开，右上角已经有频道号了，只更新数字
            tv_channel_num.setText(String.valueOf(num));
        } else {
            // 面板未打开，显示单独的频道号，5 秒后隐藏
            tv_channel_num.setText(String.valueOf(num));
            tv_channel_num.setVisibility(View.VISIBLE);
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    tv_channel_num.setVisibility(View.GONE);
                }
            }, 5000);  // ✅ 5 秒后自动隐藏
        }
    }
    /**
     * 初始化频道列表的点击事件
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
                        SettingsActivity.logOperation("【列表】点击频道：" + selectedChannel.getName());
                        playChannel(globalIndex);
                        // 点击频道后不关闭面板，方便继续浏览
                    }
                } else {
                    Channel ch = channelSourceList.get(pos);
                    SettingsActivity.logOperation("【列表】点击频道：" + ch.getName());
                    playChannel(pos);
                    // 点击频道后不关闭面板，方便继续浏览
                }
            }
        });
    }
    /**
     * 切换频道面板显示/隐藏
     * 打开面板时：显示信息栏 + 右上角频道号和时间
     * 关闭面板时：隐藏信息栏 + 右上角频道号和时间
     */
    public void togglePanel() {
        if (!TextUtils.isEmpty(currentGroupName) && !currentGroupChannelList.isEmpty()) {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        } else {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
            }
    boolean isOpen = panel_layout.getVisibility() == View.VISIBLE;
    panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
    
    // ====================================================================
    // ✅ 打开面板时显示信息栏和右上角，关闭时隐藏
    // ====================================================================
    View topRight = findViewById(R.id.ll_top_right);
    if (!isOpen) {
        // ========================================
        // 打开面板
        // ========================================
        // 1. 显示底部信息栏
        info_bar.setVisibility(View.VISIBLE);
        // 移除之前的自动隐藏任务（打开面板后信息栏一直显示）
        info_bar.removeCallbacks(hideInfoBar);
        
        // 2. 显示右上角（频道号 + 日期 + 时间）
        if (topRight != null) {
            topRight.setVisibility(View.VISIBLE);
        }
        
        // 3. 更新右上角频道号
        tv_channel_num.setText(String.valueOf(currentPlayIndex + 1));
        
        // 4. 开始时间更新（每秒刷新一次）
        timeHandler.removeCallbacks(timeUpdateRunnable);
        timeHandler.post(timeUpdateRunnable);
        
        // 5. 更新信息栏内容
        if (!channelSourceList.isEmpty() 
                && currentPlayIndex >= 0 
                && currentPlayIndex < channelSourceList.size()) {
            Channel ch = channelSourceList.get(currentPlayIndex);
            // 频道名称
            tv_channel_name.setText(ch.getName());
            // 画质、音频、码率
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
            // EPG 节目信息
            updateInfoBarEpg(ch);
        }
    } else {
        // ========================================
        // 关闭面板
        // ========================================
        // 1. 隐藏底部信息栏
        info_bar.setVisibility(View.GONE);
        
        // 2. 隐藏右上角
        if (topRight != null) {
            topRight.setVisibility(View.GONE);
        }
        
        // 3. 停止时间更新
        timeHandler.removeCallbacks(timeUpdateRunnable);
    }
    
    SettingsActivity.logOperation("【面板】" + (isOpen ? "关闭" : "打开") + "频道面板");
}
        /**
     * 打开设置页面
     *
     * 【进入设置不暂停】
     * 打开设置前设置 isOpeningSettings = true，
     * 这样 onPause 时就不会暂停播放器。
     */
    public void openSettings() {
        isOpeningSettings = true;
        startActivity(new Intent(this, SettingsActivity.class));
        SettingsActivity.logOperation("【系统】打开设置页面");
    }
    /**
     * 接收远程配置（网页后台下发）
     *
     * @param liveUrl 直播源地址
     * @param epgUrl EPG 地址
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        log("【远程配置】更新直播源：" + liveUrl);
        log("【远程配置】更新EPG：" + epgUrl);
        SettingsActivity.logOperation("【远程配置】更新直播源/EPG地址");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                hasPlayedWithCache = false;
                loadLiveAndEpg();
            }
        });
    }
    /**
     * onPause：页面暂停
     *
     * 【进入设置不暂停】
     * 如果 isOpeningSettings 为 true，说明是打开设置页面，
     * 不暂停播放器，直接返回。
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (isOpeningSettings) {
            log("【主页】onPause -> 打开设置页面，继续播放");
            return;
        }
        log("【主页】onPause -> 切到后台");
        SettingsActivity.logOperation("【系统】APP切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }
    /**
     * onResume：页面恢复
     *
     * 【进入设置不暂停】
     * 如果 isOpeningSettings 为 true，说明是从设置页面回来，
     * 重置标志位即可，不需要调用 onForeground。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (isOpeningSettings) {
            isOpeningSettings = false;
            log("【主页】onResume -> 从设置页面回来");
        } else {
            log("【主页】onResume -> 回到前台");
            SettingsActivity.logOperation("【系统】APP回到前台");
            if (mPlayerManager != null)
                mPlayerManager.onForeground();
        }
        loadSettings();
        screenRatioManager.apply();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                   );
            }
        } catch (Exception e) {
            e.printStackTrace();
            log("【适配】onResume 恢复全屏失败：" + e.getMessage());
        }
    }
    /**
     * onWindowFocusChanged：窗口焦点变化
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.view.WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) {
                        controller.hide(android.view.WindowInsets.Type.systemBars());
                        controller.setSystemBarsBehavior(
                                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        );
                    }
                } else {
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    );
                }
            } catch (Exception e) {
                e.printStackTrace();
                log("【适配】onWindowFocusChanged 恢复全屏失败：" + e.getMessage());
            }
        }
    }
    /**
     * onDestroy：页面销毁
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        SettingsActivity.logOperation("【系统】APP退出");
        
        // 停止节目进度更新
        programProgressHandler.removeCallbacks(updateProgramProgressRunnable);
        // ====================================================================
        // ✅ 新增：停止右上角时间更新
        // ====================================================================
        timeHandler.removeCallbacks(timeUpdateRunnable);
        
        if (channelNumHandler != null) {
            channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        }
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        mPlayerManager.release();
        mInstance = null;
    }
    // ====================================================================
    // ✅ 新增：信息栏 EPG 节目信息更新相关方法
    // ====================================================================
    /**
     * 更新信息栏的 EPG 节目信息
     * 切台时调用，更新当前节目、下一个节目、进度等
     *
     * @param channel 当前播放的频道
     */
    private void updateInfoBarEpg(Channel channel) {
        if (channel == null || tv_current_program_name == null) {
            return;
        }
        
        try {
            // 从 EpgManager 获取该频道的所有节目
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channel.getName());
            
            if (epgList == null || epgList.isEmpty()) {
                // 没有匹配到节目数据
                tv_current_program_name.setText("暂无节目信息");
                if (tv_current_time_range != null) tv_current_time_range.setText("");
                if (tv_next_program_name != null) tv_next_program_name.setText("");
                if (tv_next_time_range != null) tv_next_time_range.setText("");
                if (progress_program != null) progress_program.setProgress(0);
                if (tv_remaining_time != null) tv_remaining_time.setText("");
                return;
            }
            
            // ========================================
            // 筛选今天的节目（双重兼容：今天/对应周几）
            // ========================================
            List<Channel.EpgItem> todayEpg = new ArrayList<>();
            Calendar cal = Calendar.getInstance();
            int w = cal.get(Calendar.DAY_OF_WEEK);
            String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            String todayWeekDay = weekMap[w - 1];
            
            for (Channel.EpgItem item : epgList) {
                if (item.dayName == null) continue;
                String dayName = item.dayName.trim();
                // 双重兼容：匹配 "今天" 或对应的周几
                if ("今天".equals(dayName) || todayWeekDay.equals(dayName)) {
                    todayEpg.add(item);
                }
            }
            
            if (todayEpg.isEmpty()) {
                tv_current_program_name.setText("暂无节目信息");
                if (tv_current_time_range != null) tv_current_time_range.setText("");
                if (tv_next_program_name != null) tv_next_program_name.setText("");
                if (tv_next_time_range != null) tv_next_time_range.setText("");
                return;
            }
            
            // 按开始时间排序
            Collections.sort(todayEpg, new Comparator<Channel.EpgItem>() {
                @Override
                public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                    return o1.time.compareTo(o2.time);
                }
            });
            
            // ========================================
            // 找到当前正在播放的节目
            // ========================================
            String now = getNowTimeStr();
            Channel.EpgItem currentProgram = null;
            Channel.EpgItem nextProgram = null;
            int currentIndex = -1;
            
            for (int i = 0; i < todayEpg.size(); i++) {
                Channel.EpgItem item = todayEpg.get(i);
                String startTime = item.time;
                String endTime;
                
                // 计算结束时间（下一个节目的开始时间）
                if (i + 1 < todayEpg.size()) {
                    endTime = todayEpg.get(i + 1).time;
                } else {
                    endTime = "23:59"; // 最后一个节目默认到 23:59
                }
                
                if (isTimeInRange(now, startTime, endTime)) {
                    currentProgram = item;
                    currentIndex = i;
                    // 下一个节目
                    if (i + 1 < todayEpg.size()) {
                        nextProgram = todayEpg.get(i + 1);
                    }
                    break;
                }
            }
            
            // ========================================
            // 更新当前节目信息
            // ========================================
            if (currentProgram != null) {
                // 节目名称
                tv_current_program_name.setText(currentProgram.title);
                
                // 计算结束时间
                String endTime;
                if (currentIndex + 1 < todayEpg.size()) {
                    endTime = todayEpg.get(currentIndex + 1).time;
                } else {
                    endTime = "23:59";
                }
                
                // 时间范围
                if (tv_current_time_range != null) {
                    tv_current_time_range.setText(currentProgram.time + " - " + endTime);
                }
                
                // 计算进度和剩余时间
                long nowMillis = timeToMillis(now);
                long startMillis = timeToMillis(currentProgram.time);
                long endMillis = timeToMillis(endTime);
                
                if (endMillis > startMillis && progress_program != null) {
                    // 进度百分比
                    int progress = (int) ((nowMillis - startMillis) * 100 / (endMillis - startMillis));
                    progress_program.setProgress(progress);
                    
                    // 剩余时间
                    long remainingMillis = endMillis - nowMillis;
                    int remainingMinutes = (int) (remainingMillis / 1000 / 60);
                    if (tv_remaining_time != null) {
                        if (remainingMinutes >= 60) {
                            int hours = remainingMinutes / 60;
                            int mins = remainingMinutes % 60;
                            tv_remaining_time.setText("剩余 " + hours + "时" + mins + "分");
                        } else {
                            tv_remaining_time.setText("剩余 " + remainingMinutes + "分钟");
                        }
                    }
                }
            } else {
                // 没找到当前播放的节目
                tv_current_program_name.setText("暂无节目信息");
                if (tv_current_time_range != null) tv_current_time_range.setText("");
                if (progress_program != null) progress_program.setProgress(0);
                if (tv_remaining_time != null) tv_remaining_time.setText("");
            }
            
            // ========================================
            // 更新下一个节目信息
            // ========================================
            if (nextProgram != null && tv_next_program_name != null) {
                tv_next_program_name.setText(nextProgram.title);
                // 下一个节目的结束时间
                String nextEndTime;
                if (currentIndex + 2 < todayEpg.size()) {
                    nextEndTime = todayEpg.get(currentIndex + 2).time;
                } else {
                    nextEndTime = "23:59";
                }
                if (tv_next_time_range != null) {
                    tv_next_time_range.setText(nextProgram.time + " - " + nextEndTime);
                }
            } else {
                if (tv_next_program_name != null) tv_next_program_name.setText("");
                if (tv_next_time_range != null) tv_next_time_range.setText("");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            tv_current_program_name.setText("暂无节目信息");
            if (tv_current_time_range != null) tv_current_time_range.setText("");
            if (tv_next_program_name != null) tv_next_program_name.setText("");
            if (tv_next_time_range != null) tv_next_time_range.setText("");
        }
    }
    /**
     * 获取当前时间字符串（HH:mm 格式）
     */
    private String getNowTimeStr() {
        Calendar cal = Calendar.getInstance();
        return String.format("%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE));
    }
    /**
     * 判断时间是否在指定范围内
     *
     * @param now 当前时间（HH:mm）
     * @param start 开始时间（HH:mm）
     * @param end 结束时间（HH:mm）
     * @return 是否在范围内
     */
    private boolean isTimeInRange(String now, String start, String end) {
        try {
            if (now == null || start == null || end == null) {
                return false;
            }
            if (!now.contains(":") || !start.contains(":") || !end.contains(":")) {
                return false;
            }
            return now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }
    /**
     * 把 HH:mm 格式的时间转换成当天的毫秒数
     * 用于计算节目进度和剩余时间
     *
     * @param timeStr 时间字符串（HH:mm）
     * @return 当天的毫秒数
     */
    private long timeToMillis(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            
            return cal.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }
}
    
