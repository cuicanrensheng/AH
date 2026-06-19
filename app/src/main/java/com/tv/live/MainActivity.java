    package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.ChannelSwitchManager;
import com.tv.live.manager.GestureManager;
import com.tv.live.manager.InfoBarManager;
import com.tv.live.manager.KeyEventManager;
import com.tv.live.manager.PanelManager;
import com.tv.live.manager.ScreenRatioManager;
import com.tv.live.util.CacheManager;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 主页面 Activity
 *
 * 【主要功能】
 * 1. 播放器控制（播放、暂停、切台等）
 * 2. 频道面板管理（分组、频道列表、节目单）
 * 3. EPG 节目单展示
 * 4. 信息栏管理（底部 + 右下角，双布局）
 * 5. 按键和手势处理
 * 6. 直播源和 EPG 加载
 * 7. 缓存管理
 * 8. 全面屏适配
 *
 * 【代码结构】
 * 为了避免 MainActivity 太庞大，很多功能都拆分成了独立的 Manager 类：
 * - TVPlayerManager：播放器管理
 * - PanelManager：频道面板管理
 * - InfoBarManager：信息栏管理（双布局）
 * - ChannelSwitchManager：频道切换管理
 * - GestureManager：手势管理
 * - KeyEventManager：按键事件管理
 * - ScreenRatioManager：屏幕比例管理
 * - EpgManager：EPG 数据管理
 * - EpgManagerWrapper：EPG UI 包装
 * - GroupListManager：分组列表管理
 * - ChannelListManager：频道列表管理
 * - DateListManager：日期列表管理
 * - WebServerManager：网页后台管理
 * - SourceManager：多源管理
 * - CacheManager：缓存管理
 */
public class MainActivity extends AppCompatActivity {

    // ====================== 常量定义 ======================

    /** 切台冷却时间（毫秒），防止快速连续切台 */
    private static final long CHANNEL_COOLDOWN = 300;
    /** 数字选台输入超时时间（毫秒） */
    private static final long CHANNEL_NUM_TIMEOUT = 3000;

    // ====================== 单例相关 ======================

    /** MainActivity 单例 */
    private static MainActivity mInstance;
    /** 播放器管理器 */
    private TVPlayerManager mPlayerManager;
    /** 播放状态监听器 */
    private PlayerStateListenerImpl playerStateListener;

    // ====================== 管理器相关 ======================

    /** 频道切换管理器 */
    private ChannelSwitchManager switchManager;
    /** 手势管理器 */
    private GestureManager gestureManager;
    /** 按键事件管理器 */
    private KeyEventManager keyEventManager;
    /** 屏幕比例管理器 */
    private ScreenRatioManager screenRatioManager;
    /** 面板管理器 */
    private PanelManager panelManager;
    /** 信息栏管理器（双布局版：底部 + 右下角） */
    private InfoBarManager infoBarManager;

    // ====================== 列表管理相关 ======================

    /** 分组列表管理器 */
    private GroupListManager groupListManager;
    /** 频道列表管理器 */
    private ChannelListManager channelListManager;
    /** 日期列表管理器 */
    private DateListManager dateListManager;
    /** EPG 管理器包装（UI 层） */
    private EpgManagerWrapper epgManagerWrapper;

    // ====================== 数据相关 ======================

    /** 应用配置 */
    private AppConfig appConfig;
    /** 缓存管理器 */
    private CacheManager cacheManager;
    /** 频道源列表 */
    private List<Channel> channelSourceList = new ArrayList<>();
    /** 当前播放索引 */
    private int currentPlayIndex = 0;
    /** 当前选中的分组名称 */
    private String currentGroupName = "";
    /** 当前分组的频道列表 */
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    /** 当前选中的日期索引 */
    private int currentSelectedDateIndex = 0;
    /** 是否已用缓存播放过（防止重复播放） */
    private boolean hasPlayedWithCache = false;
    /** 上次切台时间（用于冷却） */
    private long lastChannelChangeTime = 0;

    // ====================== 设置相关 ======================

    /** EPG 节目单开关 */
    private boolean epg_enable = true;
    /** 换台反转开关 */
    private boolean channel_reverse = false;
    /** 数字选台开关 */
    private boolean num_channel_enable = true;
    /** 是否正在打开设置页面（用于不暂停播放） */
    private boolean isOpeningSettings = false;

    // ====================== 数字选台相关 ======================

    /** 数字选台输入缓冲 */
    private StringBuilder channelNumInput = new StringBuilder();
    /** 数字选台 Handler */
    private Handler channelNumHandler = new Handler(Looper.getMainLooper());
    /** 数字选台确认任务 */
    private final Runnable channelNumConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            confirmChannelNum();
        }
    };

    // ====================== 右上角时间相关 ======================

    /** 右上角时间更新 Handler */
    private Handler timeHandler = new Handler(Looper.getMainLooper());
    /** 右上角时间更新任务 */
    private final Runnable timeUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateTopRightTime();
            timeHandler.postDelayed(this, 1000);
        }
    };

    // ====================== 视图相关 ======================

    /** 面板根布局 */
    private View panel_layout;
    /** 频道号显示（右上角） */
    private TextView tv_channel_num;
    /** 加载动画视图 */
    private View loadingView;
    /** 加载文字 */
    private TextView tv_loading_text;

    // ====================== 广播相关 ======================

    /** 切换控制器广播接收器 */
    private BroadcastReceiver toggleControllerReceiver;
    /** 刷新广播接收器 */
    private BroadcastReceiver refreshReceiver;

    // ====================== 生命周期 ======================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mInstance = this;

        // 初始化配置和缓存
        appConfig = AppConfig.getInstance(this);
        cacheManager = new CacheManager(this);

        // 加载设置
        loadSettings();

        // 初始化播放器
        initPlayer();

        // 初始化各类管理器
        initManagers();

        // 初始化列表点击事件
        initListViewClick();

        // 初始化信息栏
        initInfoBar();

        // 初始化广播
        initReceivers();

        // 加载直播源和 EPG
        loadLiveAndEpg();

        // 全面屏适配
        initFullScreen();
    }

    // ====================== 播放器初始化 ======================

    /**
     * 初始化播放器
     *
     * 【初始化内容】
     * 1. 获取 TVPlayerManager 单例
     * 2. 设置播放状态监听器
     * 3. 设置直播信息更新监听器（画质、音频、码率实时更新）
     */
    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        playerStateListener = new PlayerStateListenerImpl();
        mPlayerManager.addListener(playerStateListener);

        // 直播信息更新监听（画质、音频、码率实时更新）
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                // 两个信息栏都更新实时信息
                if (infoBarManager != null) {
                    // 底部信息栏显示中就更新
                    if (infoBarManager.isBottomShowing()) {
                        infoBarManager.updateBottomLiveInfo(info);
                    }
                    // 右下角信息栏显示中也更新
                    if (infoBarManager.isCornerShowing()) {
                        infoBarManager.updateCornerLiveInfo(info);
                    }
                }
            }
        });
    }

    // ====================== 管理器初始化 ======================

    /**
     * 初始化各类管理器
     *
     * 【初始化顺序说明】
     * 1. 先绑定 ListView 控件
     * 2. 再创建各个 Manager
     * 3. 最后设置点击事件（依赖 Manager 实例）
     *
     * 【重要修复】节目单按钮点击事件必须在 panelManager 初始化之后设置
     * 否则点击时 panelManager 还是 null，会空指针崩溃
     */
    private void initManagers() {
        // 绑定各类 ListView
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 面板根布局
        panel_layout = findViewById(R.id.panel_layout);

        // 频道号显示
        tv_channel_num = findViewById(R.id.tv_channel_num);

        // 加载视图
        loadingView = findViewById(R.id.loading_view);
        tv_loading_text = findViewById(R.id.tv_loading_text);

        // 频道切换管理器
        switchManager = ChannelSwitchManager.getInstance();

        // 手势管理器
        gestureManager = new GestureManager(this);

        // 按键事件管理器
        keyEventManager = new KeyEventManager(this);

        // 屏幕比例管理器
        screenRatioManager = new ScreenRatioManager(this);

        // ===== 初始化各类列表管理器 =====
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);

        // ✅ 先初始化 EpgManager（必须在 EpgManagerWrapper 之前）
        EpgManager.getInstance(this);

        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);

        // 初始化日期列表
        dateListManager.initDate();

        // 面板管理器
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // ====================================================================
        // ✅ 修复：EPG 展开按钮点击事件（移到 panelManager 初始化之后）
        // 【为什么要移到后面？】
        // 原来点击事件在 panelManager 初始化之前设置，
        // 点击时 panelManager 还是 null，导致空指针崩溃。
        // 移到初始化之后，就不会有这个问题了。
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
        groupListManager.setOnGroupClickListener(new GroupListManager.OnGroupClickListener() {
            @Override
            public void onGroupClick(String groupName, List<Channel> groupChannels) {
                currentGroupName = groupName;
                currentGroupChannelList = groupChannels;
                channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
                SettingsActivity.logOperation("【分组】选择分组：" + groupName
                        + "（" + groupChannels.size() + "个频道）");
            }
        });

        // ===== 日期列表点击事件 =====
        dateListManager.setOnDateClickListener(new DateListManager.OnDateClickListener() {
            @Override
            public void onDateClick(int position, String dateStr) {
                currentSelectedDateIndex = position;
                if (!channelSourceList.isEmpty()) {
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    epgManagerWrapper.refresh(curr, channelSourceList, position);
                }
                SettingsActivity.logOperation("【日期】选择日期：" + dateStr);
            }
        });
    }

    // ====================== 信息栏初始化 ======================

    /**
     * 初始化信息栏管理器
     *
     * 【为什么用 InfoBarManager？】
     * 信息栏相关的逻辑很多（显示、隐藏、内容更新、进度更新等），
     * 拆分成独立的 Manager 类，MainActivity 更清爽，维护更方便。
     *
     * 【双布局说明】
     * - 底部信息栏：切台时显示，5 秒后自动隐藏
     * - 右下角信息栏：打开面板时显示，一直显示
     *
     * @param infoBarManager 信息栏管理器
     */
    private void initInfoBar() {
        // 创建信息栏管理器
        infoBarManager = new InfoBarManager(this, getWindow().getDecorView());
        // 设置频道列表引用
        infoBarManager.setChannelList(channelSourceList, currentPlayIndex);
        log("【信息栏】信息栏管理器初始化完成");
    }

    // ====================== 列表点击事件初始化 ======================

    /**
     * 初始化频道列表的点击事件
     *
     * 【点击行为】
     * 点击频道后切换到该频道播放，不关闭面板，方便继续浏览
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

    // ====================== 广播初始化 ======================

    /**
     * 初始化广播接收器
     */
    private void initReceivers() {
        // 切换控制器广播
        toggleControllerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                togglePanel();
            }
        };
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROLLER"));

        // 刷新广播
        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                hasPlayedWithCache = false;
                loadLiveAndEpg();
            }
        };
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH"));
    }

    // ====================== 全面屏适配 ======================

    /**
     * 初始化全面屏适配
     *
     * 【适配方案】
     * Android 11+（API 30+）使用 WindowInsetsController
     * Android 11 以下使用 setSystemUiVisibility
     * 都加了 try-catch，防止某些电视设备不支持导致崩溃
     */
    private void initFullScreen() {
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
            log("【适配】全屏适配失败：" + e.getMessage());
        }
    }

    // ====================== 设置加载 ======================

    /**
     * 加载设置项
     */
    private void loadSettings() {
        epg_enable = appConfig.isEpgEnable();
        channel_reverse = appConfig.isChannelReverse();
        num_channel_enable = appConfig.isNumChannelEnable();
        currentPlayIndex = appConfig.getLastPlayIndex();
        // 加载自定义 URL
        String customLiveUrl = appConfig.getCustomLiveUrl();
        if (!TextUtils.isEmpty(customLiveUrl)) {
            UrlConfig.LIVE_URL = customLiveUrl;
        }
        String customEpgUrl = appConfig.getCustomEpgUrl();
        if (!TextUtils.isEmpty(customEpgUrl)) {
            UrlConfig.EPG_URL = customEpgUrl;
        }
    }

    // ====================== 右上角时间更新 ======================

    /**
     * 更新右上角的日期和时间
     *
     * 【显示格式】
     * 日期：MM/dd 周X（如：06/19 周五）
     * 时间：HH:mm:ss（如：11:28:25）
     */
    private void updateTopRightTime() {
        Calendar cal = Calendar.getInstance();
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int w = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String weekDay = weekMap[w - 1];

        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);

        String dateStr = String.format("%02d/%02d %s", month, day, weekDay);
        String timeStr = String.format("%02d:%02d:%02d", hour, minute, second);

        TextView tvDate = findViewById(R.id.tv_date);
        TextView tvTime = findViewById(R.id.tv_time);
        if (tvDate != null) tvDate.setText(dateStr);
        if (tvTime != null) tvTime.setText(timeStr);
    }

    // ====================== 数字选台相关 ======================

    /**
     * 处理数字按键（数字选台功能）
     *
     * @param keyCode 按键码
     * @return 是否处理了该按键
     */
    private boolean handleNumberKey(int keyCode) {
        if (!num_channel_enable) return false;

        int num;
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
                // 同步给信息栏管理器
                infoBarManager.setChannelList(channelSourceList, currentPlayIndex);
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
                // 同步给信息栏管理器
                infoBarManager.setChannelList(channelSourceList, currentPlayIndex);
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
            // ✅ 修改：EPG 缓存加载完成后，通过 InfoBarManager 更新信息栏节目信息
            // ====================================================================
            Channel curr = channelSourceList.get(currentPlayIndex);
            if (infoBarManager != null) {
                // 底部信息栏显示中就更新
                if (infoBarManager.isBottomShowing()) {
                    infoBarManager.updateBottomEpgInfo(curr);
                }
                // 右下角信息栏显示中也更新
                if (infoBarManager.isCornerShowing()) {
                    infoBarManager.updateCornerEpgInfo(curr);
                }
            }
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
                            // ✅ 修改：EPG 加载完成后，通过 InfoBarManager 更新信息栏节目信息
                            // ====================================================================
                            Channel curr = channelSourceList.get(currentPlayIndex);
                            if (infoBarManager != null) {
                                // 底部信息栏显示中就更新
                                if (infoBarManager.isBottomShowing()) {
                                    infoBarManager.updateBottomEpgInfo(curr);
                                }
                                // 右下角信息栏显示中也更新
                                if (infoBarManager.isCornerShowing()) {
                                    infoBarManager.updateCornerEpgInfo(curr);
                                }
                            }
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

    // ====================== 频道切换 ======================

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
     * 【优化说明】
     * 1. 按键即加载：先调用 playUrl 开始加载，再做 UI 更新，节省 100-300ms
     * 2. 信息栏双布局：面板打开时更新右下角，面板关闭时显示底部
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
        // ✅ 修改：信息栏显示逻辑（双布局版，使用 InfoBarManager）
        // ====================================================================
        /**
         * 【显示逻辑说明】
         *
         * 场景 1：面板已打开
         *   - 右下角信息栏已经显示（打开面板时显示的）
         *   - 只需要更新右下角信息栏的内容，不需要改变显示状态
         *
         * 场景 2：面板未打开（直接按上下键切台）
         *   - 显示底部信息栏（完整版）
         *   - 5 秒后自动隐藏
         */
        if (infoBarManager != null) {
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            infoBarManager.setCurrentPlayIndex(index);

            if (panel_layout.getVisibility() == View.VISIBLE) {
                // 面板已打开，更新右下角信息栏内容
                infoBarManager.updateCornerInfo(ch, live);
            } else {
                // 面板未打开，显示底部信息栏（5秒后自动隐藏）
                infoBarManager.showBottom(ch, live);
            }
        }
    }

    /**
     * 显示频道号（右上角弹出）
     *
     * 【显示逻辑说明】
     *
     * 场景 1：面板已打开
     *   - 右上角已经显示频道号+日期+时间（打开面板时显示的）
     *   - 只需要更新频道号数字，不需要改变显示状态
     *
     * 场景 2：面板未打开（直接按上下键切台）
     *   - 显示单独的频道号
     *   - 5 秒后自动隐藏
     *
     * @param num 频道号
     */
    public void showChannelNum(int num) {
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

    // ====================== 面板切换 ======================

    /**
     * 切换频道面板显示/隐藏
     *
     * 【显示逻辑】
     * 打开面板时：
     *   - 隐藏底部信息栏
     *   - 显示右下角信息栏（完整版，一直显示）
     *   - 显示右上角（频道号 + 日期 + 时间）
     *   - 开始时间更新
     * 关闭面板时：
     *   - 隐藏右下角信息栏
     *   - 隐藏右上角
     *   - 停止时间更新
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
        // ✅ 信息栏显示逻辑（双布局版）
        // ====================================================================
        View topRight = findViewById(R.id.ll_top_right);
        if (!isOpen) {
            // ========================================
            // 打开面板
            // ========================================
            // 1. 隐藏底部信息栏（切台用的那个）
            infoBarManager.hideBottom();

            // 2. 显示右下角信息栏（完整版）
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0
                    && currentPlayIndex < channelSourceList.size()) {
                Channel ch = channelSourceList.get(currentPlayIndex);
                TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
                infoBarManager.showCorner(ch, live);
                infoBarManager.setCurrentPlayIndex(currentPlayIndex);
            }

            // 3. 显示右上角（频道号 + 日期 + 时间）
            if (topRight != null) {
                topRight.setVisibility(View.VISIBLE);
            }
            // ✅ 修复：确保频道号可见（tv_channel_num 不在 ll_top_right 里面）
            tv_channel_num.setText(String.valueOf(currentPlayIndex + 1));
            tv_channel_num.setVisibility(View.VISIBLE);

            // 4. 开始时间更新（每秒刷新一次）
            timeHandler.removeCallbacks(timeUpdateRunnable);
            timeHandler.post(timeUpdateRunnable);

        } else {
            // ========================================
            // 关闭面板
            // ========================================
            // 1. 隐藏右下角信息栏
            infoBarManager.hideCorner();

            // 2. 隐藏右上角
            if (topRight != null) {
                topRight.setVisibility(View.GONE);
            }
            // 同时隐藏频道号
            tv_channel_num.setVisibility(View.GONE);

            // 3. 停止时间更新
            timeHandler.removeCallbacks(timeUpdateRunnable);
        }

        SettingsActivity.logOperation("【面板】" + (isOpen ? "关闭" : "打开") + "频道面板");
    }

    // ====================== 设置页面 ======================

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

    // ====================== 生命周期 ======================

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
     *
     * 【清理内容】
     * 1. 停止信息栏节目进度更新
     * 2. 停止右上角时间更新
     * 3. 停止数字选台计时器
     * 4. 注销广播接收器
     * 5. 释放播放器
     * 6. 清空单例引用
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        SettingsActivity.logOperation("【系统】APP退出");

        // ====================================================================
        // ✅ 修改：停止信息栏的节目进度更新并释放资源
        // ====================================================================
        if (infoBarManager != null) {
            infoBarManager.stopProgramProgressUpdate();
            infoBarManager.release();
        }

        // 停止右上角时间更新
        timeHandler.removeCallbacks(timeUpdateRunnable);

        // 停止数字选台计时器
        if (channelNumHandler != null) {
            channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        }

        // 注销广播
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}

        // 释放播放器
        mPlayerManager.release();
        mInstance = null;
    }

    // ====================== 工具方法 ======================

    /**
     * 隐藏加载动画
     */
    private void hideLoading() {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
    }

    /**
     * 打印日志（同时输出到 Logcat 和 SettingsActivity 日志）
     *
     * @param msg 日志内容
     */
    private void log(String msg) {
        android.util.Log.d("MainActivity", msg);
        try {
            SettingsActivity.log(msg);
        } catch (Exception e) {
            // 忽略日志输出错误
        }
    }

    // ====================== 单例获取 ======================

    /**
     * 获取 MainActivity 单例
     *
     * 【使用场景】
     * 其他组件需要访问 MainActivity 时调用，
     * 比如网页后台下发配置时需要调用 onReceiveConfig()
     *
     * @return MainActivity 实例，可能为 null（Activity 已销毁时）
     */
    public static MainActivity getInstance() {
        return mInstance;
    }
}
                
