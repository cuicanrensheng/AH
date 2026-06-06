package com.tv.live;

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
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.tv.live.config.AppConfig;
import com.tv.live.manager.GestureManager;
import com.tv.live.manager.ScreenRatioManager;
import com.tv.live.manager.KeyEventManager;
import com.tv.live.manager.ChannelSwitchManager;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;

/**
 * 直播播放器主页面
 * 本次修改需求：
 * 1. 只屏蔽Exo原生控制器弹出，自定义手势（音量/亮度/快进）全部保留可用
 * 2. 修复并增加交互逻辑：
 *    - 电视：上下方向键/数字键切台、OK键选频道、菜单/帮助键/长按OK键打开设置
 *    - 手机：上下滑动切台、单击选频道、双击/长按打开设置、滑动对应方向键
 */
public class MainActivity extends AppCompatActivity {

    // 全局单例
    public static MainActivity mInstance;

    //===================== 频道数据源 =====================
    // 全量频道数据源
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前选中分组对应的频道集合
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放频道下标
    public int currentPlayIndex = 0;
    // 当前选中分组名称
    private String nowSelectGroup = "";

    //===================== 视图与各类管理类 =====================
    // 侧边频道分组面板
    private View panel_layout;
    // 播放器核心管理类
    public TVPlayerManager mPlayerManager;
    // Exo播放器画面控件
    private PlayerView playerView;
    // APP本地配置读取管理
    private AppConfig appConfig;
    // 画面缩放比例管理
    private ScreenRatioManager screenRatioManager;
    // 侧边面板开关控制
    private LivePanelManager.PanelManager panelManager;

    // 自定义手势管理：音量、亮度、快进逻辑
    private GestureManager gestureManager;
    private PlayerGestureHelper gestureHelper;

    // 遥控器按键分发管理
    private KeyEventManager keyEventManager;

    // 分组列表、频道列表、EPG日期、节目单管理
    private LivePanelManager.ChannelListManager channelListManager;
    private LivePanelManager.GroupListManager groupListManager;
    private LivePanelManager.DateListManager dateListManager;
    private LivePanelManager.EpgManagerWrapper epgManagerWrapper;

    // 播放器播放状态监听
    private PlayerStateListenerImpl playerStateListener;
    // 上下切台逻辑管理
    private ChannelSwitchManager switchManager;

    //===================== 功能开关状态 =====================
    // EPG节目单面板是否打开
    private boolean epgPanelOpen = false;
    // Exo控制器显示标记（已全局禁用控制器，该字段仅保留兼容旧逻辑）
    private boolean isControllerVisible = false;
    // EPG节目单总开关
    private boolean epg_enable;
    // 频道倒序切台开关
    private boolean channel_reverse;
    // 数字频道号弹窗开关
    private boolean number_channel_enable;
    // 自动更新直播源开关
    private boolean auto_update_source;
    // EPG选中日期下标
    private int currentSelectedDateIndex = 0;

    // 本地SP配置存储
    private SharedPreferences sp;

    //===================== 顶部播放信息栏控件 =====================
    private View info_bar;
    private TextView tv_channel_name;
    private TextView tv_tag_fhd;
    private TextView tv_tag_audio;
    private TextView tv_bitrate;
    private TextView tv_current_program_name;
    private TextView tv_current_time_range;
    private TextView tv_remaining_time;
    private TextView tv_next_program_name;
    public TextView tv_next_time_range;
    private ProgressBar progress_program;
    private TextView tv_channel_num;

    //===================== 常量定义 =====================
    // 直播链接最大重定向次数
    private static final int MAX_REDIRECT_COUNT = 10;
    // 链接连接超时
    private static final int CONNECT_TIMEOUT = 8000;
    // 链接读取超时
    private static final int READ_TIMEOUT = 8000;
    // 切台冷却防重复点击时间
    private static final long CHANNEL_COOLDOWN = 300;
    // 手机滑动切台判定阈值（像素）
    private static final int SWIPE_CHANNEL_THRESHOLD = 100;
    // 双击判定时间间隔
    private static final long DOUBLE_CLICK_TIME = 300;
    // 长按判定时间
    private static final long LONG_CLICK_TIME = 500;

    // 自动隐藏顶部信息栏任务
    private final Runnable hideInfoBar = () -> info_bar.setVisibility(View.GONE);
    // 上次切台时间戳
    private long lastChannelChangeTime = 0;

    //===================== 手机触摸交互变量 =====================
    // 触摸起始坐标
    private float touchStartX, touchStartY;
    // 上次点击时间（双击判定）
    private long lastClickTime = 0;
    // 长按计时器
    private Timer longClickTimer;
    // 长按触发标记
    private boolean isLongClickTriggered = false;

    // APP运行日志存储
    public static List<String> logList = new ArrayList<>();
    public static void log(String msg) {
        logList.add(0, msg);
        // 日志只保留最新100条
        if (logList.size() > 100) logList.remove(logList.size() - 1);
    }

    //===================== 全局广播接收器 =====================
    // 控制器切换广播（已失效，全局关闭Exo控制器）
    private final BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    // 刷新直播源、EPG源广播
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            runOnUiThread(() -> {
                loadSettings();
                String customLive = appConfig.getCustomLiveUrl();
                String customEpg = appConfig.getCustomEpgUrl();
                if (customLive != null) UrlConfig.LIVE_URL = customLive;
                if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                loadLiveAndEpg();
                Toast.makeText(MainActivity.this, "已刷新直播源", Toast.LENGTH_SHORT).show();
            });
        }
    };

    //===================== Activity初始化入口 =====================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        // 强制横屏、全屏、保持屏幕常亮、隐藏系统导航状态栏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        setContentView(R.layout.activity_main);

        tv_channel_num = findViewById(R.id.tv_channel_num);
        initInfoBar();

        // 初始化配置类、读取本地配置
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 读取自定义直播/EPG地址，覆盖默认地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        // 绑定播放器控件，全局关闭Exo原生控制器
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        panel_layout = findViewById(R.id.panel_layout);

        // 绑定列表控件
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // EPG节目单开关按钮点击
        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) {
                Toast.makeText(this, "节目单已关闭", Toast.LENGTH_SHORT).show();
                return;
            }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                currentSelectedDateIndex = dateListManager.getSelectedPosition();
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        // EPG日期列表初始化与选中回调
        dateListManager = new LivePanelManager.DateListManager(this, lvDate);
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        //===================== 分组点击逻辑：仅刷新频道列表，不自动播放 =====================
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            nowSelectGroup = groupListManager.getCurrentGroup(position);

            // 筛选当前分组频道
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (nowSelectGroup.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            // 刷新频道列表UI
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
            // 刷新EPG
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        //===================== 频道点击：选中后才播放 =====================
        channelListManager = new LivePanelManager.ChannelListManager(this, lvChannelList);
        channelListManager.setOnChannelClickListener(filterPos -> {
            if (filterPos >= 0 && filterPos < currentGroupChannelList.size()) {
                Channel t = currentGroupChannelList.get(filterPos);
                int g = channelSourceList.indexOf(t);
                if (g != -1) {
                    playChannel(g);
                    togglePanel();
                }
            }
        });

        groupListManager = new LivePanelManager.GroupListManager(this, lvGroup);
        epgManagerWrapper = new LivePanelManager.EpgManagerWrapper(this, lvEpg);
        panelManager = new LivePanelManager.PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 播放器初始化绑定
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

        // 应用画面比例配置
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        //===================== 【核心修改区块：屏蔽Exo控制器、保留全部自定义手势】 =====================
        // 初始化自定义手势类：亮度/音量/左右快进全部启用
        gestureManager = new GestureManager(this);
        gestureHelper = gestureManager.create();

        /**
         * 1.gestureHelper.handleTouch(event)：执行自定义手势逻辑，滑动调节音量、亮度、进退正常生效
         * 2.新增手机触摸交互逻辑：滑动切台、单击/双击/长按事件
         * 3.保留原有手势逻辑，仅增加交互判断
         */
        playerView.setOnTouchListener((v, event) -> {
            // 先执行原有自定义手势（音量/亮度/快进）
            gestureHelper.handleTouch(event);

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // 记录触摸起始坐标
                    touchStartX = event.getX();
                    touchStartY = event.getY();
                    // 重置长按标记
                    isLongClickTriggered = false;
                    // 启动长按计时器
                    longClickTimer = new Timer();
                    longClickTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            runOnUiThread(() -> {
                                isLongClickTriggered = true;
                                // 长按触发：打开设置页面
                                openSettings();
                            });
                        }
                    }, LONG_CLICK_TIME);
                    break;

                case MotionEvent.ACTION_MOVE:
                    // 计算滑动距离
                    float deltaX = event.getX() - touchStartX;
                    float deltaY = event.getY() - touchStartY;

                    // 上下滑动超过阈值：切换频道
                    if (Math.abs(deltaY) > SWIPE_CHANNEL_THRESHOLD && Math.abs(deltaY) > Math.abs(deltaX)) {
                        long now = System.currentTimeMillis();
                        if (now - lastChannelChangeTime > CHANNEL_COOLDOWN) {
                            lastChannelChangeTime = now;
                            if (deltaY < 0) {
                                // 向上滑动：上一个频道
                                playPrev();
                            } else {
                                // 向下滑动：下一个频道
                                playNext();
                            }
                            // 取消长按计时器
                            if (longClickTimer != null) {
                                longClickTimer.cancel();
                                longClickTimer = null;
                            }
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // 取消长按计时器
                    if (longClickTimer != null) {
                        longClickTimer.cancel();
                        longClickTimer = null;
                    }

                    // 未触发长按：处理点击/双击
                    if (!isLongClickTriggered) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastClickTime < DOUBLE_CLICK_TIME) {
                            // 双击：打开设置页面（对应电视菜单/帮助键）
                            openSettings();
                            lastClickTime = 0;
                        } else {
                            // 单击：切换频道列表面板（对应电视OK键）
                            togglePanel();
                            lastClickTime = currentTime;
                        }
                    }
                    break;
            }

            // 消费触摸事件，防止传递给Exo控制器
            return true;
        });

        // 遥控器按键管理初始化
        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        // 读取上次播放下标
        currentPlayIndex = appConfig.getLastPlayIndex();

        // 加载直播源和EPG数据
        loadLiveAndEpg();
    }

    /**
     * 初始化顶部播放信息栏控件
     */
    private void initInfoBar() {
        info_bar = findViewById(R.id.info_bar);
        tv_channel_name = findViewById(R.id.tv_channel_name);
        tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        tv_tag_audio = findViewById(R.id.tv_tag_audio);
        tv_bitrate = findViewById(R.id.tv_bitrate);
        tv_current_program_name = findViewById(R.id.tv_current_program_name);
        tv_current_time_range = findViewById(R.id.tv_current_time_range);
        tv_remaining_time = findViewById(R.id.tv_remaining_time);
        tv_next_program_name = findViewById(R.id.tv_next_program_name);
        tv_next_time_range = findViewById(R.id.tv_next_time_range);
        progress_program = findViewById(R.id.progress_program);
    }

    /**
     * 读取本地SP功能配置
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
    }

    /**
     * 返回键逻辑：面板打开则关闭面板，否则退出页面
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
     * 加载直播频道列表+EPG节目数据
     */
    public void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);

                // 分组缓存存在则筛选分组频道
                if (!TextUtils.isEmpty(nowSelectGroup)) {
                    currentGroupChannelList.clear();
                    for (Channel ch : channelSourceList) {
                        if (ch.getGroup().equals(nowSelectGroup)) {
                            currentGroupChannelList.add(ch);
                        }
                    }
                    channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                } else {
                    List<String> groups = groupListManager.getGroupList();
                    if (groups != null && !groups.isEmpty()) {
                        nowSelectGroup = groups.get(0);
                        currentGroupChannelList.clear();
                        for (Channel ch : channelSourceList) {
                            if (ch.getGroup().equals(nowSelectGroup)) {
                                currentGroupChannelList.add(ch);
                            }
                        }
                        channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                    } else {
                        channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    }
                }
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String msg) {
                Toast.makeText(MainActivity.this, "加载失败：" + msg, Toast.LENGTH_SHORT).show();
            }
        });

        // 加载EPG数据源
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    /**
     * 上一个频道
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 下一个频道
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
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

        // 刷新频道列表选中状态
        if (!TextUtils.isEmpty(nowSelectGroup)) {
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, index);
        } else {
            channelListManager.setChannels(channelSourceList, index);
        }

        // 刷新当前频道EPG
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 显示顶部播放信息栏，2秒自动隐藏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 2000);
            tv_channel_name.setText(ch.getName());
            TVPlayerManager.LiveInfo info = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        }

        final String url = ch.getPlayUrl();
        final String[] finalUrl = {url};

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
     * 展示频道数字编号，3秒后消失
     */
    public void showChannelNum(int num) {
        if (!number_channel_enable) return;
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            tv_channel_num.setVisibility(View.GONE);
        }, 3000);
    }

    /**
     * 开关侧边频道面板
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    /**
     * 跳转设置页面
     */
    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    /**
     * 外部接收自定义直播/EPG地址，刷新源
     */
    public void onReceiveConfig(String liveUrl, String epgUrl) {
        appConfig.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        runOnUiThread(this::loadLiveAndEpg);
    }

    /**
     * 遥控器按键分发回调，修复方法名dispatch→dispatchKey
     * 新增：支持帮助键、长按OK键打开设置，数字键切台
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 优先处理按键管理器逻辑
        if (keyEventManager.dispatchKey(keyCode)) {
            return true;
        }

        // 处理长按OK键
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.getRepeatCount() > 0) {
            // 长按OK键：打开设置
            openSettings();
            return true;
        }

        switch (keyCode) {
            // 电视上下方向键/频道键：切换频道
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                playPrev();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                playNext();
                return true;
            // 电视OK键：打开/关闭频道列表
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 短按OK键：切换面板
                if (event.getRepeatCount() == 0) {
                    togglePanel();
                }
                return true;
            // 电视菜单/帮助键：打开设置
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_HELP:
                openSettings();
                return true;
            // 数字键1-9：直接切换对应编号频道
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                long now = System.currentTimeMillis();
                if (now - lastChannelChangeTime > CHANNEL_COOLDOWN) {
                    lastChannelChangeTime = now;
                    int channelNum = keyCode - KeyEvent.KEYCODE_0;
                    // 数字0对应第10频道（可根据需求调整）
                    if (channelNum == 0) channelNum = 10;
                    // 切换到对应编号频道（下标=编号-1）
                    int targetIndex = channelNum - 1;
                    if (targetIndex >= 0 && targetIndex < channelSourceList.size()) {
                        playChannel(targetIndex);
                    }
                }
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 处理遥控器长按事件（补充）
     */
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            // 长按OK键：打开设置
            openSettings();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayerManager != null) mPlayerManager.onBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null) mPlayerManager.onForeground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        if (mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }

    public void playUrl(String url) {
    }
}
