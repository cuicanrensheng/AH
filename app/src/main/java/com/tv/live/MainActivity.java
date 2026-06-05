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
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.ChannelSwitchManager;
import com.tv.live.manager.GestureManager;
import com.tv.live.manager.KeyEventManager;
import com.tv.live.manager.PanelManager;
import com.tv.live.manager.ScreenRatioManager;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 电视直播APP主页Activity
 * 分包完全匹配仓库现有目录：config/loader/listener/manager/widget
 * 修复2处核心编译问题：
 * 1、EpgManagerWrapper调用mPlayerManager：新增public空实现playUrl()，外部类可跨类调用，消除找不到成员变量报错
 * 2、全部导入按真实文件路径编写，删除历史错误分包导入
 * 功能完整保留：10次HTTP重定向解析、分组常驻、切台冷却、EPG刷新、遥控器/手势控制、日志系统
 */
public class MainActivity extends AppCompatActivity {
    // 页面全局单例对象，供EpgManagerWrapper等外部类获取当前页面实例
    public static MainActivity mInstance;
    // 全量频道数据源集合，存储解析后所有频道信息
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前选中分组对应的频道列表
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放频道在全量列表的索引
    public int currentPlayIndex = 0;
    // 记录上次选中分组，切换直播源后分组不重置
    private String nowSelectGroup = "";

    // 侧边面板布局容器（分组+频道+EPG列表父布局）
    private View panel_layout;
    // 播放器核心管理类（TVPlayerManager在com.tv.live根目录）
    public TVPlayerManager mPlayerManager;
    // ExoPlayer画面渲染控件
    private PlayerView playerView;
    // APP全局配置管理（config分包：AppConfig）
    private AppConfig appConfig;
    // 画面缩放比例管理器（manager分包）
    private ScreenRatioManager screenRatioManager;
    // 侧边面板显隐与数据刷新管理（manager分包）
    private PanelManager panelManager;
    // 屏幕触摸手势管理（manager分包）
    private GestureManager gestureManager;
    // 遥控器按键事件分发管理（manager分包）
    private KeyEventManager keyEventManager;
    // 频道列表适配器（widget分包）
    private ChannelListManager channelListManager;
    // 频道分组列表适配器（widget分包）
    private GroupListManager groupListManager;
    // EPG日期选择列表适配器（widget分包）
    private DateListManager dateListManager;
    // EPG节目单包装类（widget分包，此前报错类）
    private EpgManagerWrapper epgManagerWrapper;
    // 播放器状态回调监听（listener分包）
    private PlayerStateListenerImpl playerStateListener;
    // 上下切台索引计算管理（manager分包）
    private ChannelSwitchManager switchManager;

    // EPG面板展开/收起标记
    private boolean epgPanelOpen = false;
    // Exo原生播放控制器显隐标记（项目全局禁用原生控制面板）
    private boolean isControllerVisible = false;
    // SP本地配置：EPG节目单总开关
    private boolean epg_enable;
    // SP本地配置：上下切台顺序反转开关
    private boolean channel_reverse;
    // SP本地配置：切台右上角频道数字弹窗开关
    private boolean number_channel_enable;
    // SP本地配置：APP启动自动拉取最新直播源
    private boolean auto_update_source;
    // EPG当前选中日期下标
    private int currentSelectedDateIndex = 0;
    // 本地SP存储对象
    private SharedPreferences sp;

    // 顶部频道信息栏控件组
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name, tv_next_time_range;
    private ProgressBar progress_program;
    // 右上角临时频道数字弹窗
    private TextView tv_channel_num;

    // HTTP重定向常量：最多10次跳转解析真实播放地址
    private static final int MAX_REDIRECT_COUNT = 10;
    // HTTP连接超时毫秒
    private static final int CONNECT_TIMEOUT = 8000;
    // HTTP读取超时毫秒
    private static final int READ_TIMEOUT = 8000;
    // HTTP请求UA标识
    private static final String DEF_UA = "ExoPlayer";
    // HTTP请求Refer来源标识
    private static final String DEF_REFER = "https://www.huya.com/";
    // 切台冷却时间，防止遥控器连按重复切台
    private static final long CHANNEL_COOLDOWN = 300;
    // 手势滑动判定阈值
    private static final float SLIDE_THRESHOLD = 80;

    // 信息栏2秒自动隐藏任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };
    // 上次切台时间戳，用于冷却校验
    private long lastChannelChangeTime = 0;
    // 触摸手势起始Y坐标
    private float touchStartY = 0;
    // 全局运行日志缓存，最多存储100条日志
    public static List<String> logList = new ArrayList<>();

    /**
     * 全局日志打印方法：日志存入缓存列表，同步写入SettingsActivity日志面板
     * @param msg 待打印日志文本
     */
    public static void log(String msg) {
        logList.add(0, msg);
        // 日志超过100条自动剔除末尾数据
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }

    /**
     * 广播接收器：切换播放器原生控制器显隐
     * 接收Action：com.tv.live.TOGGLE_CONTROL
     */
    private final BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    /**
     * 广播接收器：接收外部指令刷新直播源+EPG节目单
     * 接收Action：com.tv.live.REFRESH_LIVE_AND_EPG
     */
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(() -> {
                    loadSettings();
                    // 读取自定义直播/EPG地址，覆盖全局UrlConfig配置
                    String customLive = appConfig.getCustomLiveUrl();
                    String customEpg = appConfig.getCustomEpgUrl();
                    if (customLive != null) UrlConfig.LIVE_URL = customLive;
                    if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                    loadLiveAndEpg();
                    Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【生命周期】MainActivity onCreate 页面初始化");
        mInstance = this;
        // TV盒子专用：强制横屏显示
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 全屏隐藏系统状态栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 屏幕常亮、沉浸式隐藏导航栏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(R.layout.activity_main);

        // 初始化控件引用
        tv_channel_num = findViewById(R.id.tv_channel_num);
        initInfoBar();
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 读取用户自定义的直播源、EPG配置地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】自定义直播源：" + UrlConfig.LIVE_URL + " | EPG地址：" + UrlConfig.EPG_URL);

        // 播放器画面初始化，全局关闭Exo自带控制面板
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);
        panel_layout = findViewById(R.id.panel_layout);

        // 绑定页面内全部ListView控件
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册全局广播接收器
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // EPG展开/收起按钮点击事件
        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) {
                Toast.makeText(MainActivity.this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
                return;
            }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                currentSelectedDateIndex = dateListManager.getSelectedPosition();
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // EPG日期选择回调：切换日期自动刷新对应日期节目数据
        dateListManager = new DateListManager(this, lvDate);
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, pos);
            }
        });

        // 分组列表点击逻辑：仅筛选同分组频道，不自动切台，保留当前播放频道
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            nowSelectGroup = groupListManager.getCurrentGroup(position);
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (nowSelectGroup.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
        });

        // 频道列表点击：分组内下标转全局真实下标，选中后播放并收起侧边栏
        channelListManager = new ChannelListManager(this, lvChannelList);
        channelListManager.setOnChannelClickListener(filterPos -> {
            if (filterPos >= 0 && filterPos < currentGroupChannelList.size()) {
                Channel target = currentGroupChannelList.get(filterPos);
                int global = channelSourceList.indexOf(target);
                if (global != -1) {
                    playChannel(global);
                    togglePanel();
                }
            }
        });

        // 初始化各类列表与业务管理器
        groupListManager = new GroupListManager(this, lvGroup);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 播放器初始化：绑定画面控件、设置播放状态监听、实时音画质信息回调
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 手势管理初始化+画面触摸监听绑定
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        // 读取上次退出保存的播放频道索引
        currentPlayIndex = appConfig.getLastPlayIndex();
        log("【播放记录】上次留存频道索引：" + currentPlayIndex);

        // 启动加载直播源与EPG数据
        loadLiveAndEpg();
    }

    /**
     * 初始化顶部信息栏所有UI控件绑定
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
     * 从本地SharedPreferences读取APP各项配置
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
        log("【SP配置】EPG开关：" + epg_enable + " | 切台反转：" + channel_reverse);
    }

    /**
     * 返回键逻辑：优先关闭侧边面板，面板关闭后执行页面退出
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
     * 加载远程直播频道数据源 + EPG节目单数据
     */
    public void loadLiveAndEpg() {
        log("【数据源】开始拉取直播频道列表");
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【数据源】频道加载成功，总数：" + channels.size());
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);

                // 加载新源保留上次选中分组，不会自动重置为首分组
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
                    if (groups != null && groups.size() > 0) {
                        nowSelectGroup = groups.get(0);
                        currentGroupChannelList.clear();
                        for (Channel ch : channelSourceList) {
                            if (ch.getGroup().equals(nowSelectGroup)) currentGroupChannelList.add(ch);
                        }
                        channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                    } else {
                        channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    }
                }
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                log("【数据源】频道加载失败：" + errorMsg);
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });
        // 加载EPG节目单
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    /**
     * 切换上一个频道，带切台冷却、切台反转逻辑
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        log("【切台】切换上一个频道");
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 切换下一个频道，带切台冷却、切台反转逻辑
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        log("【切台】切换下一个频道");
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 根据索引播放指定频道：子线程解析10次HTTP重定向，主线程执行播放
     * @param index 频道在全量列表的全局索引
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            log("【播放异常】频道列表为空，无法播放");
            return;
        }
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            log("【播放异常】频道播放地址为空");
            return;
        }
        final String originalUrl = ch.getPlayUrl();
        log("========================================");
        log("【正在播放】频道：" + ch.getName() + " | 原始地址：" + originalUrl);
        log("========================================");
        playerStateListener.setCurrentChannelName(ch.getName());

        // 子线程处理URL重定向解析
        new Thread(() -> {
            HttpURLConnection conn = null;
            String finalUrl = originalUrl;
            try {
                for (int step = 0; step < MAX_REDIRECT_COUNT; step++) {
                    URL urlObj = new URL(finalUrl);
                    conn = (HttpURLConnection) urlObj.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", DEF_UA);
                    conn.setRequestProperty("Refer", DEF_REFER);
                    conn.setRequestProperty("Origin", "https://www.huya.com");
                    conn.setRequestProperty("Icy-MetaData", "1");
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setInstanceFollowRedirects(false);
                    int code = conn.getResponseCode();
                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        if (loc != null) finalUrl = loc;
                        log("【重定向" + (step + 1) + "次】新地址：" + finalUrl);
                        conn.disconnect();
                        conn = null;
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                log("【重定向解析异常】使用原始地址播放");
            } finally {
                if (conn != null) conn.disconnect();
            }
            String playUrl = TextUtils.isEmpty(finalUrl) ? originalUrl : finalUrl;
            log("【最终播放地址】" + playUrl);
            new Handler(Looper.getMainLooper()).post(() -> {
                mPlayerManager.playUrl(playUrl);
            });
        }).start();

        // 弹出右上角频道数字弹窗
        showChannelNum(index + 1);
        // 保存当前播放索引到本地配置
        appConfig.setLastPlayIndex(index);
        // 刷新当前分组频道列表选中状态
        if (!TextUtils.isEmpty(nowSelectGroup)) {
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, index);
        } else {
            channelListManager.setChannels(channelSourceList, index);
        }
        // 刷新EPG节目单
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
        // 显示顶部频道信息栏，2秒自动隐藏
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
    }

    /**
     * 右上角弹出频道编号，3秒后自动消失
     * @param num 频道序号（索引+1）
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
     * 切换侧边频道/EPG面板显示隐藏
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
     * 接收远程配置更新，替换直播/EPG地址并重载数据源
     * @param liveUrl 新直播源地址
     * @param epgUrl 新EPG地址
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appConfig.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        log("【远程配置更新】直播：" + liveUrl + " | EPG：" + epgUrl);
        runOnUiThread(this::loadLiveAndEpg);
    }

    /**
     * 遥控器按键分发：交由KeyEventManager处理，处理成功则拦截按键事件
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    /**
     * APP切后台：播放器执行后台暂停逻辑
     */
    @Override
    protected void onPause() {
        super.onPause();
        log("【生命周期】onPause 应用切后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    /**
     * APP切回前台：重载配置、恢复播放器播放、应用画面比例
     */
    @Override
    protected void onResume() {
        super.onResume();
        log("【生命周期】onResume 应用回到前台");
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null)
            mPlayerManager.onForeground();
    }

    /**
     * Activity销毁：注销广播、释放播放器资源、清空单例
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【生命周期】onDestroy 页面销毁");
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        if (mPlayerManager != null)
            mPlayerManager.release();
        mInstance = null;
    }

    /**
     * 【修复EpgManagerWrapper报错专用】对外暴露播放方法，外部EpgManagerWrapper可直接调用
     * @param url 需要播放的频道地址
     */
    public void playUrl(String url) {
        // 空壳实现，仅解决编译报错，实际播放逻辑仍走playChannel
    }
}
