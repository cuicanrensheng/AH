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
 *
 * 【全面屏适配说明 - 增强版】
 * 1. 支持刘海屏/挖孔屏全屏显示，内容延伸到摄像头区域
 * 2. Android 12+ 使用 ALWAYS 模式，允许内容延伸到所有边的刘海
 * 3. Android 11+ 使用 WindowInsetsController（官方推荐方式）
 * 4. Android 10 及以下 使用 setSystemUiVisibility 兼容方式
 * 5. onWindowFocusChanged 确保沉浸式不失效
 * 6. onResume 切后台回来后恢复全屏
 *
 * 【注意事项】
 * WindowInsetsController 必须在 setContentView 之后调用，
 * 因为 DecorView 在 setContentView 时才创建，之前调用会返回 null 导致崩溃。
 *
 * 【电视兼容说明】
 * 所有全面屏适配代码都加了 try-catch，
 * 即使电视不支持这些 API，也不会崩溃，只是不显示全屏效果而已。
 *
 * 【新增功能】
 * 1. ✅ 换台反转：上下键切台方向可反转
 * 2. ✅ 数字选台：按数字键直接跳转到对应频道
 * 3. ✅ 操作日志：所有操作记录到SettingsActivity
 * 4. ✅ 全面屏适配：刘海屏/挖孔屏全屏覆盖（增强版）
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

    // ================================================
    // ✅ 数字选台相关成员变量（新增）
    // ================================================
    // 数字输入缓冲区（记录用户输入的频道号数字）
    private StringBuilder channelNumInput = new StringBuilder();
    // 数字选台超时确认的Handler
    private Handler channelNumHandler = new Handler(Looper.getMainLooper());
    // 数字选台超时时间（2秒没输入就自动确认）
    private static final long CHANNEL_NUM_TIMEOUT = 2000;

    // 隐藏信息栏的Runnable
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // ================================================
    // ✅ 数字选台超时自动确认的Runnable（新增）
    // ================================================
    private final Runnable channelNumConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            confirmChannelNum();
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
                        SettingsActivity.logOperation("【系统】自动刷新直播源/EPG");
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
        SettingsActivity.logOperation("【系统】APP启动");
        mInstance = this;

        // 设置横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // ================================================
        // ✅ 全面屏全屏适配（刘海屏/挖孔屏）- 增强版（电视兼容版）
        // ================================================
        //
        // 【电视兼容说明】
        // 所有全面屏适配代码都加了 try-catch，
        // 即使电视不支持这些 API，也不会崩溃，只是不显示全屏效果而已。
        //
        // 【为什么之前的 SHORT_EDGES 没生效？】
        // SHORT_EDGES 模式只允许内容延伸到"物理短边"的刘海区域。
        // 大多数手机的摄像头在顶部（物理长边的一端），横屏时摄像头就到了长边位置，
        // 所以 SHORT_EDGES 模式不会让内容延伸到挖孔区域。
        //
        // 【增强版方案】
        // 1. Android 12+（API 31+）：使用 ALWAYS 模式，允许内容延伸到所有边的刘海
        // 2. Android 11+（API 30+）：使用 WindowInsetsController（官方推荐方式）
        // 3. Android 10 及以下：使用旧的 setSystemUiVisibility 方式
        // 4. 添加 onWindowFocusChanged 确保沉浸式不失效
        // 5. onResume 中重新设置，切后台回来后保持全屏
        //
        // 【重要：调用顺序】
        // - layoutInDisplayCutoutMode：可以在 setContentView 之前设置（Window 属性）
        // - setSystemUiVisibility（旧方式）：可以在 setContentView 之前设置
        // - WindowInsetsController（新方式）：必须在 setContentView 之后设置！
        //   因为 DecorView 在 setContentView 时才创建，之前调用会返回 null 导致崩溃
        // ================================================

        // ────────────────────────────────────────────────
        // 第一部分：setContentView 之前可以设置的
        // ────────────────────────────────────────────────
        try {
            // 1. 刘海屏适配（让内容延伸到刘海/挖孔区域）
            //    这是 Window 的属性，不需要 DecorView，可以提前设置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // ✅ Android 12+（API 31+）：使用 ALWAYS 模式
                    // 允许内容延伸到所有边的刘海区域，横屏时摄像头区域也能显示内容
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                } else {
                    // Android 9-11（API 28-30）：使用 SHORT_EDGES 模式
                    // 虽然横屏时效果有限，但这是官方推荐的安全模式
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                getWindow().setAttributes(lp);
            }

            // 2. 全屏标志（兼容旧版本 Android）
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            );

            // 3. Android 10 及以下的沉浸式（旧方式）
            //    旧的 setSystemUiVisibility 方式可以在 setContentView 之前设置
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN       // 布局延伸到状态栏后面
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION  // 布局延伸到导航栏后面
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE          // 保持布局稳定
                                | View.SYSTEM_UI_FLAG_FULLSCREEN              // 隐藏状态栏
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION         // 隐藏导航栏
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY        // 沉浸式粘性模式
                );
            }
        } catch (Exception e) {
            // ✅ 全面屏适配失败不影响正常使用，忽略即可
            // 某些电视的 ROM 对这些 API 支持不好，会抛出异常
            e.printStackTrace();
            log("【适配】全面屏适配（第一部分）失败：" + e.getMessage());
        }

        // 加载布局
        setContentView(R.layout.activity_main);

        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // ────────────────────────────────────────────────
        // 第二部分：setContentView 之后才能设置的
        // ────────────────────────────────────────────────
        try {
            // 4. ✅ Android 11+ 的 WindowInsetsController（新方式）
            //    重要：必须在 setContentView 之后调用！
            //    因为 DecorView 在 setContentView 时才创建，之前调用 getInsetsController() 会返回 null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 获取 WindowInsetsController（Android 11+ 官方推荐的沉浸式控制方式）
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                // 加上 null 检查，防止某些特殊情况返回 null
                if (controller != null) {
                    // 隐藏状态栏和导航栏
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    // 设置沉浸式粘性模式：从边缘滑动时临时显示系统栏
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
                // 让布局不考虑系统栏，真正延伸到系统栏后面
                // 这个很重要！没有这个的话，布局还是会避开系统栏
                getWindow().setDecorFitsSystemWindows(false);
            }
        } catch (Exception e) {
            // ✅ 全面屏适配失败不影响正常使用，忽略即可
            e.printStackTrace();
            log("【适配】全面屏适配（第二部分）失败：" + e.getMessage());
        }

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
                // 记录操作日志
                SettingsActivity.logOperation("【EPG】" + (epgPanelOpen ? "展开" : "收起") + "节目单");
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
                // 记录操作日志
                SettingsActivity.logOperation("【分组】选中分组：" + groupName + "，频道数：" + currentGroupChannelList.size());
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
        log("【设置】数字选台：" + number_channel_enable);
        log("【设置】自动更新源：" + auto_update_source);
    }

    /**
     * 返回键处理
     * 面板显示时先关闭面板，否则执行默认返回
     */
    @Override
    public void onBackPressed() {
        // 如果正在输入数字选台，按返回键取消输入
        if (channelNumInput.length() > 0) {
            channelNumInput.setLength(0);
            channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
            tv_channel_num.setVisibility(View.GONE);
            SettingsActivity.logOperation("【数字选台】取消输入");
            return;
        }
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
            playerView.requestFocus();
            SettingsActivity.logOperation("【面板】关闭频道面板");
        } else {
            super.onBackPressed();
        }
    }

    // ================================================
    // ✅ 数字选台相关方法（新增）
    // ================================================

    /**
     * ✅ 处理数字键（数字选台）
     *
     * 【使用方法】
     * 按数字键 0-9 输入频道号，2秒后自动确认并切换
     * 例如：按 1 → 按 2 → 等待2秒 → 切换到第12频道
     *
     * @param keyCode 按键码
     * @return 是否处理了该按键
     */
    private boolean handleNumberKey(int keyCode) {
        // 如果数字选台功能关闭，不处理
        if (!number_channel_enable) return false;
        // 转换按键码为数字
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
            default: return false; // 不是数字键，不处理
        }
        // 追加数字到输入缓冲区
        channelNumInput.append(num);
        // 显示当前输入的频道号
        tv_channel_num.setText(channelNumInput.toString());
        tv_channel_num.setVisibility(View.VISIBLE);
        // 重置超时计时器
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumHandler.postDelayed(channelNumConfirmRunnable, CHANNEL_NUM_TIMEOUT);
        // 记录操作日志
        SettingsActivity.logOperation("【数字选台】输入：" + channelNumInput);
        return true;
    }

    /**
     * ✅ 确认频道号并切换
     * 数字输入超时后自动调用，或者按确定键调用
     */
    private void confirmChannelNum() {
        if (channelNumInput.length() == 0) return;
        try {
            int channelNum = Integer.parseInt(channelNumInput.toString());
            // 检查频道号是否有效
            if (channelNum >= 1 && channelNum <= channelSourceList.size()) {
                // 频道号转索引（频道号从1开始，索引从0开始）
                int index = channelNum - 1;
                SettingsActivity.logOperation("【数字选台】切换到第 " + channelNum + " 频道");
                playChannel(index);
            } else {
                SettingsActivity.logOperation("【数字选台】频道号不存在：" + channelNum);
                Toast.makeText(this, "频道号不存在", Toast.LENGTH_SHORT).show();
            }
        } catch (NumberFormatException e) {
            // 忽略解析错误
        }
        // 清空输入缓冲区
        channelNumInput.setLength(0);
        // 延迟1秒后隐藏频道号显示
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                tv_channel_num.setVisibility(View.GONE);
            }
        }, 1000);
    }

    // ================================================
    // ✅ 方向键处理（支持换台反转 - 新增）
    // ================================================

    /**
     * ✅ 处理方向键（支持换台反转）
     *
     * 【反转逻辑】
     * - 正常模式：上键 = 上一台，下键 = 下一台
     * - 反转模式：上键 = 下一台，下键 = 上一台
     *
     * @param keyCode 按键码
     * @return 是否处理了该按键
     */
    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (channel_reverse) {
                    // 反转模式：上键 = 下一台
                    playNext();
                } else {
                    // 正常模式：上键 = 上一台
                    playPrev();
                }
                SettingsActivity.logOperation("切台】上键 → " + (channel_reverse ? "下一台" : "上一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channel_reverse) {
                    // 反转模式：下键 = 上一台
                    playPrev();
                } else {
                    // 正常模式：下键 = 下一台
                    playNext();
                }
                SettingsActivity.logOperation("【切台】下键 → " + (channel_reverse ? "上一台" : "下一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 确定键：如果正在输入数字选台，就确认
                if (channelNumInput.length() > 0) {
                    channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
                    confirmChannelNum();
                    return true;
                }
                // 否则打开/关闭面板
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 左右键：打开/关闭面板
                togglePanel();
                return true;
            default:
                return false;
        }
    }

    /**
     * ✅ 按键事件处理
     *
     * 【处理顺序】
     * 1. 数字选台（数字键 0-9）
     * 2. 方向键（支持换台反转）
     * 3. 其他按键交给 KeyEventManager
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 第一步：先处理数字选台
        if (handleNumberKey(keyCode)) return true;
        // 第二步：处理方向键（支持换台反转）
        if (handleDirectionKey(keyCode)) return true;
        // 第三步：其他按键交给按键事件管理器
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
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
        // 传入 Context 初始化 EpgManager
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
                        SettingsActivity.logOperation("【列表】点击频道：" + selectedChannel.getName());
                        playChannel(globalIndex);
                        togglePanel();
                    }
                } else {
                    // 非分组模式下：直接按索引播放
                    Channel ch = channelSourceList.get(pos);
                    SettingsActivity.logOperation("【列表】点击频道：" + ch.getName());
                    playChannel(pos);
                    togglePanel();
                }
            }
        });
    }

    /**
     * 切换面板显示/隐藏
     *
     * 【已修改】打开面板前先恢复分组筛选状态
     * 之前的问题：每次打开面板都传全部频道，导致分组筛选丢失
     * 现在的修复：打开面板前先根据当前分组设置频道列表
     */
    public void togglePanel() {
        // 打开面板前，先根据当前分组设置频道列表
        // 确保面板打开后显示的是用户之前选中的分组，而不是全部频道
        if (!TextUtils.isEmpty(currentGroupName) && !currentGroupChannelList.isEmpty()) {
            // 分组筛选模式下：保持分组筛选
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        } else {
            // 非分组模式：显示全部频道
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        }
        // 切换面板显示/隐藏
        boolean isOpen = panel_layout.getVisibility() == View.VISIBLE;
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
        // 记录操作日志
        SettingsActivity.logOperation("【面板】" + (isOpen ? "关闭" : "打开") + "频道面板");
    }

    /**
     * 打开设置页面
     */
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
        SettingsActivity.logOperation("【系统】打开设置页面");
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
     * Activity暂停：播放器切后台
     */
    @Override
    protected void onPause() {
        super.onPause();
        log("【主页】onPause -> 切到后台");
        SettingsActivity.logOperation("【系统】APP切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    /**
     * ✅ Activity恢复：播放器切前台，重新加载设置，恢复沉浸式全屏
     *
     * 【为什么要在 onResume 里重新设置？】
     * 切后台再回来，系统可能会重置沉浸式状态，
     * 所以需要在 onResume 里重新设置一遍，确保全屏不失效。
     */
    @Override
    protected void onResume() {
        super.onResume();
        log("【主页】onResume -> 回到前台");
        SettingsActivity.logOperation("【系统】APP回到前台");
        loadSettings(); // 重新加载设置（可能在设置页面改了）
        screenRatioManager.apply();

        // ✅ 恢复时重新设置沉浸式全屏（防止切后台后失效）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+：WindowInsetsController 方式
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
            } else {
                // Android 10 及以下：旧方式
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
            // ✅ 全面屏适配失败不影响正常使用，忽略即可
            e.printStackTrace();
            log("【适配】onResume 恢复全屏失败：" + e.getMessage());
        }

        if (mPlayerManager != null)
            mPlayerManager.onForeground();
    }

    /**
     * ✅ 窗口焦点变化时，重新设置沉浸式全屏
     *
     * 【为什么要加这个方法？】
     * 页面加载完成后、软键盘弹出后、对话框关闭后，
     * 系统可能会重置沉浸式状态，导致全屏失效。
     * 在 onWindowFocusChanged 里重新设置一遍，确保始终全屏。
     *
     * 这是 Android 官方推荐的做法，能最大程度保证沉浸式不失效。
     *
     * @param hasFocus 当前窗口是否获得焦点
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // ✅ 获得焦点时，重新设置沉浸式全屏
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+：WindowInsetsController 方式
                    android.view.WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) {
                        controller.hide(android.view.WindowInsets.Type.systemBars());
                        controller.setSystemBarsBehavior(
                                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        );
                    }
                } else {
                    // Android 10 及以下：旧方式
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                   
