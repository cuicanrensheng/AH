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
import android.widget.TextView;

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
 * 【2026-06-20 优化说明】
 * 1. 按键+手势统一管理：合并到 ChannelPanelController，反转逻辑统一管理
 * 2. 模块化拆分：MainActivity 只负责初始化和协调，具体逻辑交给各 Manager
 * 3. 封装优化：private 变量不直接暴露，通过 public 方法访问
 * 
 * 【修改记录】
 * - 修复 TVPlayerManager API 不匹配：getInstance() 加 Context、attachPlayerView() 绑定视图
 * - 修复 ScreenRatioManager 构造函数参数：2 个参数 (TVPlayerManager, AppConfig)
 * - 修复 OnChannelNumberListener 接口：3 个方法，构造函数 2 个参数
 * - 修复 OnDataLoadListener 接口：4 个方法，方法名对齐
 * - 新增 getPlayerManager() 方法：供外部类获取播放器管理器
 * - mInstance 改成 public：兼容旧代码（推荐用 getInstance()）
 */
public class MainActivity extends AppCompatActivity {

    // ====================== 单例 ======================

    /**
     * 单例实例
     * 
     * 【为什么改成 public？】
     * 原来的代码里 ChannelListActivity 等外部类直接访问 MainActivity.mInstance，
     * 但是 mInstance 是 private 的，会编译报错。
     * 
     * 【推荐用法】
     * 用 MainActivity.getInstance() 方法获取单例，这是标准的单例模式写法。
     * 改成 public 只是为了兼容旧代码，新代码请用 getInstance()。
     */
    public static MainActivity mInstance;

    /**
     * 获取单例实例
     * 
     * @return MainActivity 实例
     */
    public static MainActivity getInstance() {
        return mInstance;
    }

    // ====================== 兼容层变量 ======================

    /**
     * 所有频道数据源列表（全部频道，未筛选）
     * 
     * 【兼容说明】
     * 内部数据来自 appCoreManager，外部访问方式不变。
     * 保留 public 是为了兼容 ChannelListActivity 等旧代码的直接访问。
     */
    public List<Channel> channelSourceList = new ArrayList<>();

    /**
     * 当前正在播放的频道索引（全局索引，对应 channelSourceList）
     * 
     * 【兼容说明】
     * 内部数据来自 channelPanelController，外部访问方式不变。
     * 保留 public 是为了兼容旧代码的直接访问。
     */
    public int currentPlayIndex = 0;

    // ====================== 播放器相关 ======================

    /** 播放器视图 */
    private PlayerView playerView;

    /**
     * 播放器管理器（单例，基于 ExoPlayer 封装）
     * 
     * 【为什么是 private？】
     * 封装原则：内部变量不直接暴露给外部，
     * 外部类通过 getPlayerManager() 方法来获取。
     * 
     * 【外部怎么访问？】
     * MainActivity.getInstance().getPlayerManager()
     */
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
     * 频道面板控制器
     * 
     * 【职责】
     * 统一管理所有和频道相关的逻辑：
     * 1. 分组管理（分组列表、选中状态、分组筛选）
     * 2. 频道切换（上/下切台、分组内循环、防抖、反转）
     * 3. 面板控制（显示/隐藏、EPG 展开/收起、列表点击）
     * 4. 焦点管理（手机触屏 + 电视遥控器）
     * 5. 按键处理（统一处理所有按键，面板打开/关闭都能处理）
     * 6. 手势处理（上/下频道、OK、长按OK、菜单）
     * 
     * 【合并说明】
     * 原 KeyEventManager 和 GestureManager 的功能已全部合并到本类中。
     * 所有切台入口统一走 switchUp()/switchDown()，反转逻辑统一管理。
     */
    private ChannelPanelController channelPanelController;

    // ====================== 状态标志 ======================

    /**
     * 频道切换是否反向（上键=下一台，下键=上一台）
     * 
     * 【说明】
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
     */
    private boolean isOpeningSettings = false;

    // ====================== 防花屏相关 ======================

    /**
     * 防花屏占位图（退到后台时显示，避免看到花屏）
     * 
     * 【原理】
     * 退到后台时，TextureView 可能会出现花屏/黑屏。
     * 在销毁渲染之前，先显示一张黑色占位图，
     * 用户就看不到花屏了。
     * 
     * 【显示时机】
     * - onPause() 时显示（正常退到后台）
     * - 打开设置页面时不显示（isOpeningSettings = true）
     * 
     * 【隐藏时机】
     * - onResume() 后延迟 2000ms 隐藏（等画面渲染稳定）
     */
    private ImageView ivPlayerPlaceholder;

    // ====================== 日志相关 ======================

    /** 本地日志列表（保留最近 100 条，供其他类访问） */
    public static List<String> logList = new ArrayList<>();

    // ====================== 首次打开自动隐藏相关 ======================

    /** 自动隐藏 Handler */
    private Handler mPanelAutoHideHandler = new Handler(Looper.getMainLooper());

    /** 自动隐藏 Runnable */
    private Runnable mPanelAutoHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelPanelController != null) {
                channelPanelController.hidePanel();
            }
        }
    };

    /** 是否是首次打开（用于 3 秒自动隐藏） */
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

        // 绑定播放器视图 + 占位图
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        ivPlayerPlaceholder = findViewById(R.id.iv_player_placeholder);

        // 频道面板控制器初始化
        initChannelPanelController();

        // 首次打开时，3 秒后自动隐藏面板
        if (mIsFirstLaunch) {
            mPanelAutoHideHandler.postDelayed(mPanelAutoHideRunnable, 3000);
            mIsFirstLaunch = false;
        }

        // 播放器初始化
        initPlayer();

        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);

        // 数字选台管理器初始化
        initChannelNumberManager();

        // ====================================================================
        // ✅ 修复：ScreenRatioManager 构造函数参数
        // 【错误原因】原来传了 3 个参数，实际只需要 2 个 (TVPlayerManager, AppConfig)
        // ====================================================================
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);

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

    private void initChannelPanelController() {
        View panel_layout = findViewById(R.id.panel_layout);
        View ll_left_panel = findViewById(R.id.ll_left_panel);
        View ll_right_panel = findViewById(R.id.ll_right_panel);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvChannelListEpg = findViewById(R.id.lv_channel_list_epg);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);

        TextView btn_show_epg = findViewById(R.id.btn_show_epg);
        TextView btn_back_group = findViewById(R.id.btn_back_group);

        EpgManager.getInstance(this);

        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
        ChannelListManager channelListManagerEpg = new ChannelListManager(this, lvChannelListEpg);
        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        DateListManager dateListManager = new DateListManager(this, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            channelPanelController.setCurrentDateIndex(pos);
        });

        channelPanelController = new ChannelPanelController(
                this,
                panel_layout,
                ll_left_panel,
                ll_right_panel,
                lvGroup,
                lvChannelList,
                lvChannelListEpg,
                lvDate,
                lvEpg,
                btn_show_epg,
                btn_back_group,
                groupListManager,
                channelListManager,
                channelListManagerEpg,
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

        // 设置面板动作监听器（打开设置等操作）
        channelPanelController.setOnPanelActionListener(new ChannelPanelController.OnPanelActionListener() {
            @Override
            public void onOpenSettings() {
                openSettings();
            }
        });
    }

    // ====================================================================
    // ✅ 修复：播放器初始化（适配 TVPlayerManager 实际 API）
    // ====================================================================

    /**
     * 初始化播放器
     * 
     * 【修改说明】
     * 1. getInstance() 需要传 Context 参数
     * 2. 去掉 init() 调用（构造函数里已经初始化了）
     * 3. 方法名：setOnPlayStateListener()，不是 setPlayerStateListener()
     * 4. 绑定视图：attachPlayerView()，不是 playerView.setPlayer()
     * 
     * 【为什么这么改？】
     * TVPlayerManager 的实际 API 是这样的：
     * - getInstance(Context ctx)：单例，需要传 Context
     * - setOnPlayStateListener(OnPlayStateListener l)：设置播放状态监听器
     * - attachPlayerView(PlayerView view)：绑定播放器视图
     * - playUrl(String url)：播放
     * - getLiveInfo()：获取直播信息
     * - setScaleMode(ScaleMode mode)：设置缩放模式
     * - release()：释放资源
     */
    private void initPlayer() {
        // 1. 获取播放器单例（需要传 Context）
        mPlayerManager = TVPlayerManager.getInstance(this);

        // 2. 设置播放状态监听器
        // 【注意】方法名是 setOnPlayStateListener，不是 setPlayerStateListener
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 3. 绑定播放器视图
        // 【注意】方法名是 attachPlayerView，不是 playerView.setPlayer()
        mPlayerManager.attachPlayerView(playerView);
    }

    // ====================================================================
    // ✅ 修复：数字选台管理器初始化
    // ====================================================================

    /**
     * 初始化数字选台管理器
     * 
     * 【修改说明】
     * 1. 构造函数参数：(OnChannelNumberListener listener, boolean enable)
     *    第一个参数是监听器，第二个是是否启用，没有 Context 参数
     * 2. 接口有 3 个方法：
     *    - onChannelSelected(int channelIndex)：选中频道时回调
     *    - showChannelNumber(String number)：显示频道号（输入过程中）
     *    - hideChannelNumber()：隐藏频道号
     * 3. showChannelNumber 传的是 String，InfoDisplayManager 要的是 int，需要转换
     * 
     * 【为什么这么改？】
     * ChannelNumberManager 的实际构造函数和接口方法是这样的，
     * 之前的代码参数不对、方法不全，导致编译报错。
     */
    private void initChannelNumberManager() {
        channelNumberManager = new ChannelNumberManager(
                new ChannelNumberManager.OnChannelNumberListener() {
                    
                    /**
                     * 选中频道时回调
                     * 
                     * @param channelIndex 频道索引（从 0 开始）
                     */
                    @Override
                    public void onChannelSelected(int channelIndex) {
                        // 数字选台确认后，播放对应频道
                        if (channelPanelController != null) {
                            channelPanelController.playChannel(channelIndex);
                        }
                    }
                    
                    /**
                     * 显示频道号（输入过程中实时更新）
                     * 
                     * @param number 当前输入的数字字符串
                     * 
                     * 【为什么要转成 int？】
                     * ChannelNumberManager 传的是 String（比如 "1"、"12"），
                     * 因为输入过程中用户可能只输入了一位数，还没输完。
                     * 
                     * InfoDisplayManager 的 showChannelNum() 参数是 int，
                     * 所以这里把 String 转成 int 再调用。
                     */
                    @Override
                    public void showChannelNumber(String number) {
                        if (infoDisplayManager != null) {
                            try {
                                int num = Integer.parseInt(number);
                                infoDisplayManager.showChannelNum(num);
                            } catch (NumberFormatException e) {
                                // 数字解析失败就忽略
                            }
                        }
                    }
                    
                    /**
                     * 隐藏频道号
                     */
                    @Override
                    public void hideChannelNumber() {
                        if (infoDisplayManager != null) {
                            infoDisplayManager.hideChannelNum();
                        }
                    }
                },
                number_channel_enable  // 第二个参数：是否启用数字选台
        );
    }

    // ====================================================================
    // ✅ 修复：应用核心管理器初始化
    // ====================================================================

    /**
     * 初始化应用核心管理器
     * 
     * 【修改说明】
     * OnDataLoadListener 接口有 4 个方法：
     * 1. onLiveSourceLoaded(List<Channel> channels, boolean fromCache)：直播源加载成功
     * 2. onLiveSourceFailed(String errorMsg)：直播源加载失败
     * 3. onEpgLoaded()：EPG 加载完成
     * 4. onLoadTimeout(boolean hasData)：加载超时
     * 
     * 【为什么这么改？】
     * 之前的代码方法名不对、方法不全，导致编译报错。
     * 现在和 AppCoreManager 里的接口定义完全对齐。
     */
    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);

        // 设置数据加载监听器
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            
            /**
             * 直播源加载成功
             * 
             * @param channels 频道列表
             * @param fromCache 是否来自缓存（true=缓存，false=网络）
             */
            @Override
            public void onLiveSourceLoaded(List<Channel> channels, boolean fromCache) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);

                channelPanelController.setChannels(channels);
                
                // 更新数字选台的总频道数
                if (channelNumberManager != null) {
                    channelNumberManager.setTotalChannelCount(channels.size());
                }

                // 如果是第一次加载，就播放（不管是缓存还是网络）
                if (!appCoreManager.hasPlayedWithCache()) {
                    int lastIndex = appConfig.getLastPlayIndex();
                    if (lastIndex >= 0 && lastIndex < channels.size()) {
                        currentPlayIndex = lastIndex;
                        channelPanelController.setCurrentPlayIndex(lastIndex);
                        playChannel(channels.get(lastIndex), lastIndex);
                    } else if (!channels.isEmpty()) {
                        currentPlayIndex = 0;
                        playChannel(channels.get(0), 0);
                    }
                    appCoreManager.setHasPlayedWithCache(true);
                }
            }
            
            /**
             * 直播源加载失败
             * 
             * @param errorMsg 错误信息
             */
            @Override
            public void onLiveSourceFailed(String errorMsg) {
                log("【主页】直播源加载失败：" + errorMsg);
                SettingsActivity.logOperation("【错误】直播源加载失败：" + errorMsg);
            }
            
            /**
             * EPG 加载完成
             */
            @Override
            public void onEpgLoaded() {
                log("【主页】EPG 加载完成");
            }
            
            /**
             * 加载超时
             * 
             * @param hasData 是否有缓存数据（true=有，false=没有）
             */
            @Override
            public void onLoadTimeout(boolean hasData) {
                if (hasData) {
                    log("【主页】加载超时，但有缓存数据");
                } else {
                    log("【主页】加载超时，无数据");
                    SettingsActivity.logOperation("【错误】加载超时，无数据");
                }
            }
        });
    }

    // ====================================================================
    // 加载设置
    // ====================================================================

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
            // ✅ 同步反转设置到 ChannelPanelController
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

    /**
     * 播放指定索引的频道（兼容旧接口）
     * 
     * @param index 频道索引
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

        currentPlayIndex = index;

        log("========================================");
        log("【播放】频道名称：" + channel.getName());
        log("【播放】播放地址：" + channel.getPlayUrl());
        log("【播放】当前索引：" + index);
        log("========================================");

        playerStateListener.setCurrentChannelName(channel.getName());

        mPlayerManager.playUrl(channel.getPlayUrl());

        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);

        infoDisplayManager.showChannelNum(index + 1);

        appConfig.setLastPlayIndex(index);
    }

    // ====================================================================
    // 兼容层方法
    // ====================================================================

    /**
     * 切换面板显示/隐藏（兼容旧接口）
     */
    public void togglePanel() {
        if (channelPanelController != null) {
            channelPanelController.togglePanel();
        }
    }

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
    // ✅ 新增：获取播放器管理器（供外部类访问）
    // ====================================================================

    /**
     * 获取播放器管理器
     * 
     * @return TVPlayerManager 实例
     * 
     * 【为什么要新增这个方法？】
     * 原来的代码里，EpgManagerWrapper 等外部类直接访问 MainActivity.mPlayerManager，
     * 但是 mPlayerManager 是 private 的，外部不能直接访问，会编译报错。
     * 
     * 【解决方案】
     * 新增一个 public 的 getPlayerManager() 方法，
     * 外部类通过这个方法来获取播放器管理器。
     * 
     * 【好处】
     * 1. 符合封装原则：private 变量不直接暴露给外部
     * 2. 更安全：以后可以在方法里加 null 判断等保护逻辑
     * 3. 更灵活：以后 mPlayerManager 的实现变了，只需要改这个方法
     * 
     * 【调用示例】
     * MainActivity.getInstance().getPlayerManager().playUrl(url);
     */
    public TVPlayerManager getPlayerManager() {
        return mPlayerManager;
    }

    /**
     * 换台反转是否开启
     * 
     * @return true = 已开启反转
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
     * 【为什么要设置 isOpeningSettings = true？】
     * 打开设置页面时，MainActivity 会走 onPause() 生命周期，
     * 但设置页面是透明主题，用户需要看到后面的播放画面。
     * 如果不设置这个标志位，onPause() 会显示占位图，挡住播放画面。
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
     * @param keyCode 按键码
     * @return 是否处理了按键
     */
    private boolean handleDirectionKey(int keyCode) {
        if (channelPanelController == null) return false;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                // 统一走 ChannelPanelController 的方法，自动考虑反转
                channelPanelController.switchUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 统一走 ChannelPanelController 的方法，自动考虑反转
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 用户有按键操作时，取消自动隐藏
        cancelPanelAutoHide();

        // 1. 先处理数字选台
        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        // 2. 统一交给 ChannelPanelController 处理所有按键
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

        isOpeningSettings = false;
        log("【设置】从设置页面返回，重置标志位");

        boolean resumed = appCoreManager.onResume();

        // 每次 onResume 都重新加载设置和应用屏幕比例
        // 【为什么？】
        // 从设置页面返回后，用户可能改了换台反转、屏幕比例等设置，
        // 需要重新加载才能生效。
        loadSettings();
        screenRatioManager.apply();

        displayManager.reapplyFullScreen();

        // 延迟 2000ms 隐藏占位图
        // 【为什么延迟？】
        // 回到前台后，画面需要一点时间渲染，
        // 延迟 2 秒再隐藏占位图，避免看到黑屏/花屏。
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
        // 先显示占位图，再执行 super.onPause()
        // 【为什么要在 super 之前？】
        // 退到后台时，TextureView 的画面可能会花掉，
        // 在销毁渲染之前先显示占位图，用户就看不到花屏了。
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
     * 显示防花屏占位图
     */
    private void showPlayerPlaceholder() {
        if (ivPlayerPlaceholder != null) {
            ivPlayerPlaceholder.setVisibility(View.VISIBLE);
            log("【防花屏】显示占位图");
        }
    }

    /**
     * 隐藏防花屏占位图
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

        cancelPanelAutoHide();
        mPanelAutoHideHandler = null;

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
     * 
     * @param msg 日志内容
     */
    private void log(String msg) {
        logList.add(0, msg);
        if (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }
}
