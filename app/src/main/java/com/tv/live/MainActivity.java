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
 * 【电视兼容说明】
 * 所有全面屏适配代码都加了 try-catch，
 * 即使电视不支持这些 API，也不会崩溃，只是不显示全屏效果而已。
 *
 * 【Toast屏蔽说明】
 * 所有 Toast 提示已全部屏蔽，避免干扰观看。
 *
 * 【加载超时保护】
 * 15秒还没加载完自动隐藏加载动画，避免一直卡在加载界面。
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

    // 缓存管理器
    private CacheManager cacheManager;
    // 是否已用缓存播放过
    private boolean hasPlayedWithCache = false;

    // 加载视图
    private View loadingView;
    private TextView tv_loading_text;

    // 数字选台相关
    private StringBuilder channelNumInput = new StringBuilder();
    private Handler channelNumHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_NUM_TIMEOUT = 2000;

    // 隐藏信息栏的Runnable
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // 数字选台超时自动确认的Runnable
    private final Runnable channelNumConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            confirmChannelNum();
        }
    };

    // 上次频道切换时间
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;
    private float touchStartY = 0;
    private static final float SLIDE_THRESHOLD = 80;

    // 本地日志列表
    public static List<String> logList = new ArrayList<>();

    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
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
                        String customLive = appConfig.getCustomLiveUrl();
                        String customEpg = appConfig.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        hasPlayedWithCache = false;
                        loadLiveAndEpg();
                        SettingsActivity.logOperation("【系统】自动刷新直播源/EPG");
                        // ✅ 已屏蔽 Toast
                        // Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
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
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // ================================================
        // ✅ 全面屏适配（第一部分）- 加 try-catch 确保电视不崩溃
        // ================================================
        try {
            // 1. 刘海屏适配
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                } else {
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // ================================================
        // ✅ 全面屏适配（第二部分）- 加 try-catch 确保电视不崩溃
        // ================================================
        try {
            // 4. Android 11+ 的 WindowInsetsController
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
                getWindow().setDecorFitsSystemWindows(false);
            }
        } catch (Exception e) {
            // ✅ 全面屏适配失败不影响正常使用
            e.printStackTrace();
            log("【适配】全面屏适配（第二部分）失败：" + e.getMessage());
        }

        // 绑定频道号显示
        tv_channel_num = findViewById(R.id.tv_channel_num);
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
        playerView.setUseController(false);
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
                    // ✅ 已屏蔽 Toast
                    // Toast.makeText(MainActivity.this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
                    SettingsActivity.logOperation("【EPG】节目单功能已关闭，无法展开");
                    return;
                }
                epgPanelOpen = !epgPanelOpen;
                lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
                lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
                SettingsActivity.logOperation("【EPG】" + (epgPanelOpen ? "展开" : "收起") + "节目单");
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
                groupListManager.setSelectedPosition(position);
                lvGroup.setItemChecked(position, true);
                lvGroup.setSelection(position);
                String groupName = groupListManager.getCurrentGroup(position);
                currentGroupName = groupName;
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (groupName.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
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
        currentPlayIndex = appConfig.getLastPlayIndex();
        log("【播放】记录上次播放索引：" + currentPlayIndex);

        // 初始化缓存和加载视图
        cacheManager = CacheManager.getInstance(this);
        initLoadingView();
        showLoading("正在加载直播源...");

        // 加载直播源和EPG
        loadLiveAndEpg();
        initListViewClick();
    }

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

    private void initLoadingView() {
        FrameLayout rootLayout = findViewById(android.R.id.content);
        FrameLayout loadingLayout = new FrameLayout(this);
        loadingLayout.setBackgroundColor(0xEE000000);
        loadingLayout.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams llParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        llParams.gravity = Gravity.CENTER;
        linearLayout.setLayoutParams(llParams);
        ProgressBar progressBar = new ProgressBar(this);
        linearLayout.addView(progressBar);
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

    private void showLoading(String text) {
        if (loadingView != null) {
            loadingView.setVisibility(View.VISIBLE);
        }
        if (tv_loading_text != null && text != null) {
            tv_loading_text.setText(text);
        }
    }

    private void hideLoading() {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
    }

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

    @Override
    public void onBackPressed() {
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
        channelNumInput.append(num);
        tv_channel_num.setText(channelNumInput.toString());
        tv_channel_num.setVisibility(View.VISIBLE);
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumHandler.postDelayed(channelNumConfirmRunnable, CHANNEL_NUM_TIMEOUT);
        SettingsActivity.logOperation("【数字选台】输入：" + channelNumInput);
        return true;
    }

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
                // ✅ 已屏蔽 Toast
                // Toast.makeText(this, "频道号不存在", Toast.LENGTH_SHORT).show();
            }
        } catch (NumberFormatException e) {
        }
        channelNumInput.setLength(0);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                tv_channel_num.setVisibility(View.GONE);
            }
        }, 1000);
    }

    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (channel_reverse) {
                    playNext();
                } else {
                    playPrev();
                }
                SettingsActivity.logOperation("【切台】上键 → " + (channel_reverse ? "下一台" : "上一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channel_reverse) {
                    playPrev();
                } else {
                    playNext();
                }
                SettingsActivity.logOperation("【切台】下键 → " + (channel_reverse ? "上一台" : "下一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (channelNumInput.length() > 0) {
                    channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
                    confirmChannelNum();
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleNumberKey(keyCode)) return true;
        if (handleDirectionKey(keyCode)) return true;
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");

        // ================================================
        // ✅ 新加：加载超时保护（15秒）
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
                        // 保留加载视图，但把进度条去掉（只显示文字）
                        // 或者直接隐藏，让用户看到黑屏自己按菜单键
                        hideLoading();
                        SettingsActivity.logOperation("【加载】直播源加载超时");
                    } else {
                        // 有缓存数据，直接隐藏加载动画
                        hideLoading();
                    }
                }
            }
        }, 15000);

        // 先读缓存
        String cacheContent = cacheManager.getFileCache("live_source");
        if (cacheContent != null && !cacheContent.isEmpty()) {
            log("【缓存】找到直播源缓存，快速显示");
            List<Channel> cacheChannels = parseLiveSource(cacheContent);
            if (cacheChannels != null && !cacheChannels.isEmpty()) {
                channelSourceList.clear();
                channelSourceList.addAll(cacheChannels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                if (!hasPlayedWithCache) {
                    playChannel(currentPlayIndex);
                    hasPlayedWithCache = true;
                }
                hideLoading();
                log("【缓存】直播源缓存加载完成，频道数：" + cacheChannels.size());
                loadEpgCache();
            }
        }

        // 后台网络加载最新数据
        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【网络】直播源加载成功，频道总数：" + channels.size());
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                if (!hasPlayedWithCache) {
                    playChannel(currentPlayIndex);
                    hasPlayedWithCache = true;
                }
                hideLoading();
                log("【网络】直播源列表已更新");
                loadEpg();
            }

            @Override
            public void onError(String errorMsg) {
                log("【网络】直播源加载失败：" + errorMsg);
                if (channelSourceList.isEmpty()) {
                    hideLoading();
                    // ✅ 已屏蔽 Toast
                    // Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
                    SettingsActivity.logOperation("【加载】直播源加载失败：" + errorMsg);
                } else {
                    log("【缓存】使用缓存数据继续播放");
                    hideLoading();
                }
                loadEpgCache();
            }
        });
    }

    private void loadEpgCache() {
        if (!epg_enable) return;
        log("【EPG】尝试从缓存加载...");
        if (!channelSourceList.isEmpty()) {
            epgManagerWrapper.refresh(
                    channelSourceList.get(currentPlayIndex),
                    channelSourceList,
                    currentSelectedDateIndex);
        }
    }

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
                        }
                    }
                });
            }
        });
    }

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
                int commaIndex = line.indexOf(",");
                if (commaIndex > 0 && commaIndex < line.length() - 1) {
                    currentName = line.substring(commaIndex + 1).trim();
                }
                int groupIndex = line.indexOf("group-title=\"");
                if (groupIndex > 0) {
                    int groupEnd = line.indexOf("\"", groupIndex + 13);
                    if (groupEnd > groupIndex) {
                        currentGroup = line.substring(groupIndex + 13, groupEnd);
                    }
                }
                int tvgIndex = line.indexOf("tvg-id=\"");
                if (tvgIndex > 0) {
                    int tvgEnd = line.indexOf("\"", tvgIndex + 8);
                    if (tvgEnd > tvgIndex) {
                        currentTvgId = line.substring(tvgIndex + 8, tvgEnd);
                    }
                }
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                String playUrl = line;
                if (!TextUtils.isEmpty(currentName) && !TextUtils.isEmpty(playUrl)) {
                    channels.add(new Channel(currentName, playUrl, currentGroup, currentTvgId));
                }
                currentName = "";
                currentGroup = "";
                currentLogo = "";
                currentTvgId = "";
            }
        }
        log("【缓存】解析完成，共 " + channels.size() + " 个频道");
        return channels;
    }

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
        log("【播放】交给ExoPlayer自动跟随重定向");
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
        mPlayerManager.playUrl(playUrl);
    }

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
                        togglePanel();
                    }
                } else {
                    Channel ch = channelSourceList.get(pos);
                    SettingsActivity.logOperation("【列表】点击频道：" + ch.getName());
                    playChannel(pos);
                    togglePanel();
                }
            }
        });
    }

    public void togglePanel() {
        if (!TextUtils.isEmpty(currentGroupName) && !currentGroupChannelList.isEmpty()) {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        } else {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        }
        boolean isOpen = panel_layout.getVisibility() == View.VISIBLE;
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
        SettingsActivity.logOperation("【面板】" + (isOpen ? "关闭" : "打开") + "频道面板");
    }

    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
        SettingsActivity.logOperation("【系统】打开设置页面");
    }

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

    @Override
    protected void onPause() {
        super.onPause();
        log("【主页】onPause -> 切到后台");
        SettingsActivity.logOperation("【系统】APP切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    // ✅ onResume 加 try-catch
    @Override
    protected void onResume() {
        super.onResume();
        log("【主页】onResume -> 回到前台");
        SettingsActivity.logOperation("【系统】APP回到前台");
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

        if (mPlayerManager != null)
            mPlayerManager.onForeground();
    }

    // ✅ onWindowFocusChanged 加 try-catch
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        SettingsActivity.logOperation("【系统】APP退出");
        if (channelNumHandler != null) {
            channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        }
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        mPlayerManager.release();
        mInstance = null;
    }
}
