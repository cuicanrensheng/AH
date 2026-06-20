package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.*;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播主页面 Activity
 *
 * 【核心职责】
 * 1. 页面生命周期管理
 * 2. 各 Manager 的初始化和协调
 * 3. 播放器视图绑定
 * 4. 数据加载触发
 *
 * 【2026-06-20 优化：按键+手势统一管理（合并到 ChannelPanelController）】
 * 【问题原因】
 * 之前按键和手势逻辑分散在多个地方：
 * - MainActivity.handleDirectionKey()：面板关闭时的方向键
 * - KeyEventManager：其他按键
 * - GestureManager：手势识别
 * 反转逻辑也分散在多个地方，容易出现不同步的问题。
 *
 * 【解决方案】
 * 把 KeyEventManager 和 GestureManager 的功能全部合并到 ChannelPanelController 里：
 * 1. 一个 dispatchKeyEvent() 方法统一处理所有按键（面板打开/关闭都能处理）
 * 2. 新增 createGestureHelper() 方法创建手势识别器
 * 3. 所有切台入口统一走 switchUp()/switchDown()，反转逻辑统一管理
 *
 * 【效果】
 * - 反转逻辑统一管理，所有入口都生效，不会出现"按键有反转、手势没反转"的问题
 * - 代码更简洁，onKeyDown() 只需要调用一次 dispatchKeyEvent()
 * - 减少 Manager 数量，职责更清晰
 *
 * 【兼容层说明】
 * 为了兼容其他类对旧的方法和变量的调用，保留了以下兼容接口：
 * - 方法：togglePanel()、playPrev()、playNext()、playChannel(int)
 * - 变量：channelSourceList、currentPlayIndex
 * 这些接口内部都委托给对应的 Manager，外部调用方式不变。
 */
public class MainActivity extends AppCompatActivity {

    // ====================== 单例 ======================
    private static MainActivity mInstance;

    public static MainActivity getInstance() {
        return mInstance;
    }

    // ✅ 兼容层：保留旧的 public 变量，供其他类直接访问
    /**
     * 所有频道数据源列表（全部频道，未筛选）
     * 【兼容说明】内部数据来自 appCoreManager，外部访问方式不变
     */
    public List<Channel> channelSourceList = new ArrayList<>();

    /**
     * 当前正在播放的频道索引（全局索引，对应 channelSourceList）
     * 【兼容说明】内部数据来自 channelPanelController，外部访问方式不变
     */
    public int currentPlayIndex = 0;

    // ====================== 播放器相关 ======================
    /** 播放器视图 */
    private PlayerView playerView;
    /** 播放器管理器（单例，基于 ExoPlayer 封装） */
    private TVPlayerManager mPlayerManager;
    /** 播放器状态监听器 */
    private PlayerStateListenerImpl playerStateListener;

    // ====================== 管理器相关 ======================
    /** 应用配置管理（SP 封装） */
    private AppConfig appConfig;
    /** 显示管理器（全面屏+加载动画） */
    private DisplayManager displayManager;
    /** 信息展示管理器（频道号+信息栏+EPG 节目单） */
    private InfoDisplayManager infoDisplayManager;
    /** 数字选台管理器 */
    private ChannelNumberManager channelNumberManager;
    /** 屏幕比例管理器 */
    private ScreenRatioManager screenRatioManager;
    /** 应用核心管理器（数据加载 + 广播 + 生命周期） */
    private AppCoreManager appCoreManager;

    /**
     * 频道面板控制器（分组 + 频道切换 + 面板控制 + 焦点管理 + 按键 + 手势 + 反转）
     *
     * 【2026-06-20 合并说明】
     * 原 KeyEventManager 和 GestureManager 的功能已全部合并到本类中：
     * - 按键事件：统一由 dispatchKeyEvent() 处理
     * - 手势识别：由 createGestureHelper() 创建
     * - 反转逻辑：统一由 switchUp()/switchDown() 管理
     *
     * 【好处】
     * 1. 所有交互逻辑都在一个类里，不会出现不同步
     * 2. 反转逻辑统一管理，所有入口都生效
     * 3. 减少 Manager 数量，代码更简洁
     */
    private ChannelPanelController channelPanelController;

    // ====================== 状态标志 ======================
    /**
     * 频道切换是否反向（上键=下一台，下键=上一台）
     *
     * 【2026-06-20 优化说明】
     * 保留这个变量是为了：
     * 1. 兼容旧代码直接访问这个变量
     * 2. 日志输出时用
     *
     * 实际的反转逻辑已经统一由 ChannelPanelController 管理，
     * loadSettings() 时会同步到 channelPanelController.setReverse()。
     */
    private boolean channel_reverse;

    /** 数字选台是否启用 */
    private boolean number_channel_enable;

    // ✅ 新增：打开设置页面的标志位
    /**
     * 是否正在打开设置页面
     *
     * 【作用】
     * 打开设置页面时，MainActivity 会走 onPause() 生命周期，
     * 但这时候不应该显示占位图，因为设置页面是透明主题，
     * 用户需要看到后面的播放画面。
     *
     * 【true = 正在打开设置，不显示占位图】
     * 【false = 正常退到后台，显示占位图防花屏】
     *
     * 【设置时机】
     * - openSettings() 中设为 true（打开设置前）
     * - onResume() 中重置为 false（从设置页面回来后）
     *
     * 【为什么需要这个标志位？】
     * 如果没有这个标志位，打开设置页面时：
     * 1. MainActivity.onPause() 被调用
     * 2. 显示黑色占位图
     * 3. 设置页面透明背景透过来 → 看到的是黑色占位图，不是播放画面
     */
    private boolean isOpeningSettings = false;

    // ====================== 防花屏相关 ======================
    /** 防花屏占位图（退到后台时显示，避免看到花屏） */
    private ImageView ivPlayerPlaceholder;

    // ====================== 日志相关 ======================
    /** 本地日志列表（保留最近 100 条，供其他类访问） */
    public static List<String> logList = new ArrayList<>();

    // ====================== 首次打开自动隐藏相关 ======================
    /**
     * 首次打开 app 后，频道面板 3 秒自动隐藏的 Handler
     *
     * 【为什么需要？】
     * 用户希望首次打开 app 时，频道面板显示 3 秒后自动隐藏，
     * 让用户能先看到频道列表，然后自动进入全屏播放状态。
     */
    private Handler mPanelAutoHideHandler = new Handler(Looper.getMainLooper());

    /**
     * 自动隐藏面板的 Runnable
     *
     * 【作用】
     * 延迟 3 秒后调用 channelPanelController.hidePanel() 隐藏面板。
     * 如果用户有操作（按键、点击等），就取消这个 Runnable。
     *
     * 【为什么是 3 秒？】
     * 给用户足够的时间看一眼频道列表，然后自动进入全屏播放。
     * 只有首次打开 app 时才会 post 这个 Runnable。
     * 用户手动打开面板时不会自动隐藏。
     */
    private Runnable mPanelAutoHideRunnable = new Runnable() {
        @Override
        public void run() {
            // 3 秒后自动隐藏频道面板
            if (channelPanelController != null) {
                channelPanelController.hidePanel();
            }
        }
    };

    /**
     * 是否是首次打开 app
     *
     * 【作用】
     * 标记是否是首次打开 app，只有首次打开时才自动隐藏面板。
     * 用户手动打开面板时不自动隐藏。
     *
     * 【true = 首次打开，3 秒后自动隐藏】
     * 【false = 非首次，不自动隐藏】
     *
     * 【为什么只在首次打开时自动隐藏？】
     * 因为用户手动打开面板时，说明用户想操作，不应该自动隐藏。
     * 只有首次打开 app 时，面板是默认显示的，才需要自动隐藏。
     */
    private boolean mIsFirstLaunch = true;

    // ====================== onCreate 生命周期 ======================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");

        mInstance = this;

        // ===== 自动旋转横屏 =====
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // 全面屏适配
        displayManager = new DisplayManager(this);
        displayManager.applyFullScreen();

        // 加载布局
        setContentView(R.layout.activity_main);

        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 信息展示管理器初始化
        initInfoDisplayManager();

        // 初始化配置
        appConfig = AppConfig.getInstance(this);
        loadSettings();

        // 应用自定义直播源/EPG 地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        // ====================================================================
        // ✅ 绑定播放器视图 + 占位图
        // ====================================================================
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        // 绑定防花屏占位图
        ivPlayerPlaceholder = findViewById(R.id.iv_player_placeholder);

        // 频道面板控制器初始化
        initChannelPanelController();

        // ====================================================================
        // ✅ 新增：首次打开时，3 秒后自动隐藏面板
        // ====================================================================
        //
        // 【需求来源】
        // 用户希望首次打开 app 时，频道面板显示 3 秒后自动隐藏，
        // 让用户能先看到频道列表，然后自动进入全屏播放状态。
        //
        // 【实现方式】
        // 1. 用 Handler postDelayed 延迟 3 秒
        // 2. 延迟时间到了就调用 channelPanelController.hidePanel() 隐藏面板
        // 3. 如果用户有操作（按键、点击等），就取消自动隐藏
        //
        // 【为什么只在首次打开时自动隐藏？】
        // 因为用户手动打开面板时，说明用户想操作，不应该自动隐藏。
        // 只有首次打开 app 时，面板是默认显示的，才需要自动隐藏。
        //
        // 【为什么放在这里？】
        // 因为 initChannelPanelController() 刚执行完，面板已经初始化完成，
        // 这时候启动自动隐藏计时最合适。
        if (mIsFirstLaunch) {
            // 延迟 3 秒后自动隐藏面板
            mPanelAutoHideHandler.postDelayed(mPanelAutoHideRunnable, 3000);
            // 标记为非首次，下次手动打开时不自动隐藏
            mIsFirstLaunch = false;
        }

        // 播放器初始化
        initPlayer();

        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);

        // 数字选台管理器初始化
        initChannelNumberManager();

        // 屏幕比例管理器初始化
        screenRatioManager = new ScreenRatioManager(this, mPlayerManager, appConfig);

        // 应用核心管理器初始化
        initAppCoreManager();

        // 加载直播源和 EPG
        appCoreManager.loadLiveAndEpg();
    }

    // ====================================================================
    // 信息展示管理器初始化
    // ====================================================================

    private void initInfoDisplayManager() {
        TextView tv_channel_num = findViewById(R.id.tv_channel_num);
        View info_bar = findViewById(R.id.info_bar);
        TextView tv_channel_name = findViewById(R.id.tv_channel_name);
        TextView tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        TextView tv_tag_audio = findViewById(R.id.tv_tag_audio);
        TextView tv_bitrate = findViewById(R.id.tv_bitrate);
        TextView tv_current_program_name = findViewById(R.id.tv_current_program_name);
        TextView tv_current_time_range = findViewById(R.id.tv_current_time_range);
        ProgressBar progress_program = findViewById(R.id.progress_program);
        TextView tv_remaining_time = findViewById(R.id.tv_remaining_time);
        TextView tv_next_program_name = findViewById(R.id.tv_next_program_name);
        TextView tv_next_time_range = findViewById(R.id.tv_next_time_range);

        infoDisplayManager = new InfoDisplayManager(
                this,
                tv_channel_num,
                info_bar,
                tv_channel_name,
                tv_tag_fhd,
                tv_tag_audio,
                tv_bitrate,
                tv_current_program_name,
                tv_current_time_range,
                progress_program,
                tv_remaining_time,
                tv_next_program_name,
                tv_next_time_range
        );
    }

    // ====================================================================
    // 频道面板控制器初始化
    // ====================================================================

    /**
     * 初始化频道面板控制器
     *
     * 【两个完整面板切换】
     * 原左右面板切换模式（只有日期+EPG）改为两个完整面板切换：
     * - 左侧面板：分组 + 频道列表 + 节目单按钮
     * - 右侧面板：返回按钮 + 频道列表 + 日期 + EPG
     *
     * 【2026-06-20 合并说明】
     * 原 KeyEventManager 和 GestureManager 的功能已全部合并到 ChannelPanelController：
     * 1. 按键事件：统一由 dispatchKeyEvent() 处理
     * 2. 手势识别：由 createGestureHelper() 创建
     * 3. 反转逻辑：统一由 switchUp()/switchDown() 管理
     *
     * 【新增内容】
     * 1. 新增节目单页面的频道列表（lv_channel_list_epg）
     * 2. 新增返回分组按钮（btn_back_group）
     * 3. 新增节目单页面的频道列表管理器（channelListManagerEpg）
     * 4. ChannelPanelController 构造函数新增 3 个参数
     */
    private void initChannelPanelController() {
        // ===== 面板根布局 =====
        View panel_layout = findViewById(R.id.panel_layout);

        // ================================================================
        // 左右面板容器
        // ================================================================
        View ll_left_panel = findViewById(R.id.ll_left_panel);
        View ll_right_panel = findViewById(R.id.ll_right_panel);

        // ================================================================
        // 列表控件
        // ================================================================
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);

        // ================================================================
        // ✅ 新增：节目单页面的频道列表
        // ================================================================
        // 【作用】
        // 右侧面板（节目单页面）也有一个频道列表，用户在看节目单时
        // 可以直接切换频道，不用切回左侧面板。
        //
        // 【布局 ID】
        // lv_channel_list_epg：节目单页面的频道列表，在 ll_right_panel 里面
        ListView lvChannelListEpg = findViewById(R.id.lv_channel_list_epg);

        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);

        // ================================================================
        // 按钮控件
        // ================================================================
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // ================================================================
        // ✅ 新增：返回分组按钮
        // ================================================================
        // 【作用】
        // 右侧面板（节目单页面）最左边的返回按钮，点击后切回左侧面板。
        // 文字是竖排的"频道组"。
        //
        // 【布局 ID】
        // btn_back_group：返回按钮，在 ll_right_panel 最左边
        TextView btn_back_group = findViewById(R.id.btn_back_group);

        // ================================================================
        // 子管理器初始化
        // ================================================================
        EpgManager.getInstance(this);

        // 主页面频道列表管理器（左侧面板用）
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);

        // ================================================================
        // ✅ 新增：节目单页面频道列表管理器
        // ================================================================
        // 【作用】
        // 管理右侧面板的频道列表，和左侧面板的频道列表管理器是两个独立的实例，
        // 分别管理各自的 ListView，但数据保持同步。
        //
        // 【为什么需要两个管理器？】
        // 因为有两个 ListView，每个 ListView 需要自己的 Adapter 和选中状态管理。
        // 两个管理器的数据来源相同，切台时同时更新两边的选中状态。
        ChannelListManager channelListManagerEpg = new ChannelListManager(this, lvChannelListEpg);

        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        DateListManager dateListManager = new DateListManager(this, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 日期列表初始化
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            channelPanelController.setCurrentDateIndex(pos);
        });

        // ================================================================
        // 创建频道面板控制器
        // ================================================================
        channelPanelController = new ChannelPanelController(
                this,
                panel_layout,
                ll_left_panel,
                ll_right_panel,
                lvGroup,
                lvChannelList,
                lvChannelListEpg,        // ✅ 新增：节目单页面频道列表
                lvDate,
                lvEpg,
                btn_show_epg,
                btn_back_group,          // ✅ 新增：返回分组按钮
                groupListManager,
                channelListManager,
                channelListManagerEpg,   // ✅ 新增：节目单页面频道管理器
                dateListManager,
                panelManager
        );

        // 设置频道切换监听器
        channelPanelController.setOnChannelChangeListener(new ChannelPanelController.OnChannelChangeListener() {
            @Override
            public void onChannelChanged(Channel channel, int index) {
                playChannel(channel, index);
            }

            @Override
            public void onChannelSelected(int channelIndex) {
                channelPanelController.playChannel(channelIndex);
            }
        });

        // ====================================================================
        // ✅ 新增：设置面板动作监听器（打开设置等操作）
        // ====================================================================
        //
        // 【为什么需要这个监听器？】
        // 合并 KeyEventManager 和 GestureManager 后，
        // 打开设置的逻辑也移到了 ChannelPanelController 里，
        // 但 ChannelPanelController 不应该直接依赖 MainActivity 的 openSettings() 方法，
        // 所以用回调接口解耦。
        //
        // 【回调时机】
        // 1. 面板关闭时按菜单键 → 回调 onOpenSettings()
        // 2. 手势长按 OK → 回调 onOpenSettings()
        // 3. 手势菜单键 → 回调 onOpenSettings()
        channelPanelController.setOnPanelActionListener(new ChannelPanelController.OnPanelActionListener() {
            @Override
            public void onOpenSettings() {
                openSettings();
            }
        });
    }

    // ====================================================================
    // 播放器初始化
    // ====================================================================

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance();
        mPlayerManager.init(this);

        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setPlayerStateListener(playerStateListener);

        playerView.setPlayer(mPlayerManager.getPlayer());
    }

    // ====================================================================
    // 数字选台管理器初始化
    // ====================================================================

    private void initChannelNumberManager() {
        channelNumberManager = new ChannelNumberManager(this, new ChannelNumberManager.OnChannelNumberListener() {
            @Override
            public void onChannelNumberConfirmed(int channelNum) {
                // 数字选台确认后，播放对应频道
                if (channelPanelController != null) {
                    channelPanelController.playChannel(channelNum - 1);  // 频道号从 1 开始，索引从 0 开始
                }
            }
        });
    }

    // ====================================================================
    // 应用核心管理器初始化
    // ====================================================================

    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);

        // 设置数据加载监听器
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveLoaded(List<Channel> channels) {
                // 直播源加载完成
                channelSourceList.clear();
                channelSourceList.addAll(channels);

                // 更新频道面板
                channelPanelController.setChannels(channels);

                // 如果有上次播放的位置，直接播放
                int lastIndex = appConfig.getLastPlayIndex();
                if (lastIndex >= 0 && lastIndex < channels.size()) {
                    currentPlayIndex = lastIndex;
                    channelPanelController.setCurrentPlayIndex(lastIndex);
                    playChannel(channels.get(lastIndex), lastIndex);
                } else if (!channels.isEmpty()) {
                    // 没有上次播放记录，播放第一个频道
                    currentPlayIndex = 0;
                    playChannel(channels.get(0), 0);
                }
            }

            @Override
            public void onEpgLoaded() {
                // EPG 加载完成
                log("【主页】EPG 加载完成");
            }

            @Override
            public void onLoadError(String error) {
                // 加载失败
                log("【主页】加载失败：" + error);
            }
        });
    }

    // ====================================================================
    // 加载设置
    // ====================================================================

    /**
     * 加载设置
     *
     * 【从 SP 读取的设置】
     * 1. EPG 开关
     * 2. 换台反转
     * 3. 数字选台
     * 4. 自动更新源
     *
     * 【2026-06-20 优化：同步反转设置到 ChannelPanelController】
     * 【问题原因】
     * 之前反转逻辑只在 MainActivity 的 handleDirectionKey() 里，
     * ChannelPanelController 不知道反转状态，容易出现不同步的问题。
     *
     * 【解决方案】
     * 统一由 ChannelPanelController 管理反转，
     * loadSettings() 时把反转设置同步过去。
     *
     * 【效果】
     * 所有地方调用 channelPanelController.switchUp()/switchDown() 时，
     * 都会自动考虑反转设置，不会出现不同步的问题。
     *
     * 【调用时机】
     * 1. App 启动时，onCreate() 里调用 loadSettings()
     * 2. 从设置页面返回时，onResume() 里调用 loadSettings()
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);

        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        }

        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);

            // ====================================================================
            // ✅ 新增：同步反转设置到 ChannelPanelController
            // ====================================================================
            //
            // 【为什么要同步？】
            // 反转逻辑统一由 ChannelPanelController 管理，
            // 需要把设置同步过去，这样所有切台入口（按键、手势）都生效。
            //
            // 【同步后效果】
            // 所有地方调用 channelPanelController.switchUp()/switchDown() 时，
            // 都会自动考虑反转设置，不会出现不同步的问题。
            //
            // 【调用时机】
            // 1. App 启动时，onCreate() 里调用 loadSettings()
            // 2. 从设置页面返回时，onResume() 里调用 loadSettings()
            channelPanelController.setReverse(channel_reverse);
        }

        log("【设置】EPG开关：" + epg_enable);
        log("【设置】切台反转：" + channel_reverse);
        log("【设置】数字选台：" + number_channel_enable);
        log("【设置】自动更新源：" + auto_update_source);
    }

    // ====================================================================
    // 播放相关
    // ====================================================================

    // ✅ 兼容层：旧的 playChannel(int) 方法，供其他类调用
    /**
     * 播放指定索引的频道（兼容旧接口）
     */
    public void playChannel(int index) {
        if (channelSourceList == null || index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    /**
     * 播放指定频道
     *
     * @param channel 频道
     * @param index   全局索引
     */
    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;

        // ✅ 同步到兼容变量
        currentPlayIndex = index;

        log("========================================");
        log("【播放】频道名称：" + channel.getName());
        log("【播放】播放地址：" + channel.getPlayUrl());
        log("【播放】当前索引：" + index);
        log("========================================");

        playerStateListener.setCurrentChannelName(channel.getName());

        // 先播放（最重要的事情先做）
        mPlayerManager.playUrl(channel.getPlayUrl());

        // 显示信息栏
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);

        // ✅ 显示频道号（从 1 开始，用户看到的是 1、2、3...）
        infoDisplayManager.showChannelNum(index + 1);

        // 保存上次播放索引
        appConfig.setLastPlayIndex(index);
    }

    // ====================================================================
    // ✅ 兼容层：旧的 togglePanel() 方法，供其他类调用
    // ====================================================================

    /**
     * 切换频道面板显示/隐藏（兼容旧接口）
     */
    public void togglePanel() {
        if (channelPanelController != null) {
            channelPanelController.togglePanel();
        }
    }

    // ====================================================================
    // ✅ 兼容层：旧的 playPrev() 方法，供 GestureManager 等调用
    // ====================================================================

    /**
     * 播放上一个频道（兼容旧接口）
     *
     * 【注意】这是底层方法，直接切换到上一台，不考虑反转。
     * 如果需要考虑反转，请调用 channelPanelController.switchUp()。
     */
    public void playPrev() {
        if (channelPanelController != null) {
            channelPanelController.playPrev();
        }
    }

    // ====================================================================
    // ✅ 兼容层：旧的 playNext() 方法，供 GestureManager 等调用
    // ====================================================================

    /**
     * 播放下一个频道（兼容旧接口）
     *
     * 【注意】这是底层方法，直接切换到下一台，不考虑反转。
     * 如果需要考虑反转，请调用 channelPanelController.switchDown()。
     */
    public void playNext() {
        if (channelPanelController != null) {
            channelPanelController.playNext();
        }
    }

    // ====================================================================
    // ✅ 新增：判断是否开启反转（供其他类调用）
    // ====================================================================

    /**
     * 换台反转是否开启
     *
     * @return true = 已开启反转
     *
     * 【为什么新增这个方法？】
     * 合并后，其他类如果需要知道反转状态，可以调用这个方法，
     * 而不是直接访问 channel_reverse 变量。
     */
    public boolean isChannelReverse() {
        return channel_reverse;
    }

    // ====================================================================
    // 打开设置页面
    // ====================================================================

    /**
     * 打开设置页面
     *
     * 【2026-06-20 修改：设置 isOpeningSettings 标志位】
     * 【问题原因】
     * 打开设置页面时，MainActivity 会走 onPause()，然后显示黑色占位图。
     * 但设置页面是透明主题，背景透过来就会看到黑色占位图，看不到播放画面。
     *
     * 【解决方案】
     * 打开设置页面之前，先设置 isOpeningSettings = true，
     * onPause() 中判断如果是打开设置，就不显示占位图。
     *
     * 【效果】
     * - 按 Home 键退到后台 → 显示占位图，防花屏 ✅
     * - 打开设置页面 → 不显示占位图，能看到播放画面 ✅
     */
    private void openSettings() {
        isOpeningSettings = true;
        log("【设置】打开设置页面");
        SettingsActivity.logOperation("【设置】打开设置页面");

        appCoreManager.beforeOpenSettings();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ====================== 返回键处理 ======================

    @Override
    public void onBackPressed() {
        // 第一步：数字选台取消输入
        if (channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            return;
        }

        // 第二步：面板关闭
        if (channelPanelController != null && channelPanelController.handleBackPressed()) {
            playerView.requestFocus();
            return;
        }

        // 都没处理，正常退出
        super.onBackPressed();
    }

    // ====================== 方向键处理（保留兼容层） ======================

    /**
     * 处理方向键（兼容旧接口，实际功能已合并到 ChannelPanelController）
     *
     * 【2026-06-20 说明】
     * 功能已合并到 ChannelPanelController.dispatchKeyEvent()，
     * 保留这个方法是为了兼容其他类可能的调用。
     *
     * @param keyCode 按键码
     * @return 是否处理了按键
     */
    private boolean handleDirectionKey(int keyCode) {
        if (channelPanelController == null) return false;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                // ✅ 统一走 ChannelPanelController 的方法，自动考虑反转
                channelPanelController.switchUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // ✅ 统一走 ChannelPanelController 的方法，自动考虑反转
                channelPanelController.switchDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (channelNumberManager.isInputting()) {
                    channelNumberManager.confirmChannelNum();
                    return true;
                }
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                togglePanel();
                return true;
            default:
                return false;
        }
    }

    // ====================== 按键分发 ======================

    /**
     * 按键事件分发
     *
     * 【2026-06-20 优化：简化按键分发逻辑】
     * 【问题原因】
     * 之前按键逻辑分散在多个地方：
     * 1. ChannelNumberManager - 数字键
     * 2. ChannelPanelController.dispatchKeyEvent() - 面板打开时的左右键、OK键
     * 3. handleDirectionKey() - 面板关闭时的方向键
     * 4. KeyEventManager.dispatchKey() - 其他按键
     *
     * 【解决方案】
     * 把 KeyEventManager 的功能合并到 ChannelPanelController 里，
     * 一个 dispatchKeyEvent() 方法统一处理所有按键（面板打开/关闭都能处理）。
     *
     * 【按键分发优先级】
     * 1. 数字选台（ChannelNumberManager）- 数字键 0-9
     * 2. 频道面板（ChannelPanelController）- 所有其他按键（统一处理）
     *
     * 【好处】
     * 1. 代码更简洁，onKeyDown() 逻辑更清晰
     * 2. 反转逻辑统一管理，所有入口都生效
     * 3. 减少 Manager 数量
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ====================================================================
        // ✅ 新增：用户有按键操作时，取消自动隐藏
        // ====================================================================
        //
        // 【为什么要取消？】
        // 如果用户在 3 秒内按了键，说明用户想操作面板，
        // 这时候不应该自动隐藏，应该保持面板显示。
        //
        // 【什么时候取消？】
        // 用户按任何键都取消，包括：
        // - 上下左右键（切换频道/分组）
        // - OK 键（选中频道）
        // - 数字键（数字选台）
        // - 其他按键
        // 只要用户有任何操作，就取消自动隐藏。
        //
        // 【为什么放在最前面？】
        // 因为不管是数字选台、频道面板、还是方向键处理，
        // 只要用户按了键，就说明用户在操作，都应该取消自动隐藏。
        cancelPanelAutoHide();

        // 1. 先处理数字选台
        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        // ====================================================================
        // ✅ 优化：统一交给 ChannelPanelController 处理所有按键
        // ====================================================================
        //
        // 【为什么可以统一处理？】
        // 因为 KeyEventManager 的功能已经合并到 ChannelPanelController 里了，
        // dispatchKeyEvent() 方法内部会判断面板是打开还是关闭，
        // 然后执行不同的逻辑：
        // - 面板打开时：处理左右键、OK键（在面板内移动焦点、选中项）
        // - 面板关闭时：处理上下键切台、OK键开面板、菜单键开设置
        //
        // 【好处】
        // 1. 代码更简洁，只需要调用一次 dispatchKeyEvent()
        // 2. 反转逻辑统一管理，所有入口都生效
        // 3. 减少了 handleDirectionKey() 和 keyEventManager 的调用
        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // 其他按键交给系统处理
        return super.onKeyDown(keyCode, event);
    }

    // ====================================================================
    // 取消面板自动隐藏
    // ====================================================================

    /**
     * 取消面板自动隐藏
     *
     * 【作用】
     * 用户有任何操作（按键、点击等）时，取消 3 秒自动隐藏。
     *
     * 【什么时候调用？】
     * - 用户按任何键时（onKeyDown 最前面调用）
     * - 用户点击屏幕时
     * - 用户手动打开面板时
     *
     * 【为什么需要？】
     * 如果用户在 3 秒内有操作，说明用户想操作面板，
     * 这时候不应该自动隐藏，应该保持面板显示。
     */
    private void cancelPanelAutoHide() {
        if (mPanelAutoHideHandler != null && mPanelAutoHideRunnable != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        }
    }

    // ====================== 触摸事件 ======================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 用户有触摸操作时，也取消自动隐藏
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            cancelPanelAutoHide();
        }
        return super.onTouchEvent(event);
    }

    // ====================== onResume 生命周期 ======================

    @Override
    protected void onResume() {
        super.onResume();

        // 【2026-06-20 修改：重置打开设置的标志位】
        // 从设置页面回来后，重置标志位。
        isOpeningSettings = false;
        log("【设置】从设置页面返回，重置标志位");

        boolean resumed = appCoreManager.onResume();

        // ====================================================================
        // ✅ 修复：每次 onResume 都重新加载设置和应用屏幕比例
        // ====================================================================
        //
        // 【问题原因】
        // 原来的代码只有 resumed == true 时才重新加载设置，
        // 但从设置页面返回时，appCoreManager.onResume() 可能返回 false，
        // 导致设置没有重新加载，改了换台反转/屏幕比例不生效。
        //
        // 【修复方案】
        // 把 loadSettings() 和 screenRatioManager.apply() 移到 if (resumed) 外面，
        // 确保每次 onResume 都重新加载设置和应用屏幕比例。
        //
        // 【效果】
        // 从设置页面返回后，改了的设置立即生效，不会出现"改了但没生效"的问题。
        loadSettings();
        screenRatioManager.apply();

        displayManager.reapplyFullScreen();

        // 延迟 2000ms 隐藏占位图
        // 【为什么延迟 2000ms？】
        // 等 Surface 完全创建好、第一帧渲染出来再隐藏，过渡更平滑。
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                hidePlayerPlaceholder();
            }
        }, 2000);
    }

    // ====================== onPause 生命周期 ======================

    @Override
    protected void onPause() {
        // ====================================================================
        // ✅ 修复：先显示占位图，再执行 super.onPause()
        // ====================================================================
        //
        // 【问题原因】
        // 原来的代码是先 super.onPause()，再显示占位图。
        // 但 super.onPause() 执行后，Surface 就开始销毁了，
        // 然后才显示占位图，那一瞬间还是会看到花屏。
        //
        // 【修复方案】
        // 把显示占位图的代码移到 super.onPause() 前面，
        // 先显示占位图，再让 Surface 销毁，这样就不会看到花屏了。
        //
        // 【注意】
        // 如果是打开设置页面，不显示占位图，因为设置页面是透明主题，
        // 用户需要看到后面的播放画面。

        if (!isOpeningSettings) {
            showPlayerPlaceholder();
        } else {
            log("【防花屏】打开设置页面，不显示占位图");
        }

        super.onPause();

        appCoreManager.onPause();
    }

    // ====================== 防花屏占位图相关 ======================

    /**
     * 显示播放器占位图
     *
     * 【作用】
     * 退到后台时显示黑色占位图，避免 Surface 销毁过程中看到花屏。
     */
    private void showPlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.VISIBLE);
            log("【防花屏】显示占位图");
        }
    }

    /**
     * 隐藏播放器占位图
     *
     * 【作用】
     * 回到前台时，等第一帧渲染出来后隐藏占位图，过渡更平滑。
     */
    private void hidePlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.GONE);
            log("【防花屏】隐藏占位图");
        }
    }

    // ====================== onDestroy 生命周期 ======================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        SettingsActivity.logOperation("【系统】APP退出");

        // 取消自动隐藏
        cancelPanelAutoHide();
        mPanelAutoHideHandler = null;

        // 释放资源
        if (channelPanelController != null) {
            channelPanelController.release();
        }

        if (appCoreManager != null) {
            appCoreManager.release();
        }

        if (mPlayerManager != null) {
            mPlayerManager.release();
        }

        mInstance = null;
    }

    // ====================== 日志方法 ======================

    /**
     * 记录日志
     * @param msg 日志内容
     */
    private void log(String msg) {
        logList.add(0, msg);
        // 只保留最近 100 条
        if (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        // 同步到 SettingsActivity 的全局日志
        SettingsActivity.log(msg);
    }
}
