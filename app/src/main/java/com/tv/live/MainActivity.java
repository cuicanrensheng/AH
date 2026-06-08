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
import android.os.Looper;
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
 * 电视直播APP 主界面
 * 核心功能：视频播放、频道切换、EPG节目单、频道分组、手势/按键控制、系统设置
 * 修复说明：
 * 1. 移除重复成员变量定义
 * 2. 修正playChannel方法代码错位、括号不匹配问题
 * 3. 替换Lambda为传统Runnable，兼容低版本Android编译
 * 4. 补全缺失导入、修复线程切换逻辑
 * 5. 保留原有全部业务逻辑与功能
 */
public class MainActivity extends AppCompatActivity {
    // ====================== 全局单例 ======================
    // 页面单例，供其他工具类/管理器调用当前Activity实例
    public static MainActivity mInstance;

    // ====================== 频道数据集合 ======================
    // 所有频道原始总列表
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前选中分组下的频道子列表
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放的频道全局索引
    public int currentPlayIndex = 0;

    // ====================== 布局视图控件 ======================
    // 频道面板根布局（分组+频道列表+节目单）
    private View panel_layout;
    // 播放器核心管理器实例
    public TVPlayerManager mPlayerManager;
    // ExoPlayer播放画面视图
    private PlayerView playerView;

    // ====================== 功能管理器 ======================
    // 应用全局配置管理
    private AppConfig appConfig;
    // 画面显示比例管理器
    private ScreenRatioManager screenRatioManager;
    // 频道面板显示/隐藏管理器
    private PanelManager panelManager;
    // 屏幕手势触摸管理器
    private GestureManager gestureManager;
    // 遥控器/实体按键事件管理器
    private KeyEventManager keyEventManager;
    // 频道列表适配器&逻辑管理
    private ChannelListManager channelListManager;
    // 频道分组（央视/卫视/地方台）管理
    private GroupListManager groupListManager;
    // EPG节目单日期选择列表管理
    private DateListManager dateListManager;
    // EPG节目单业务包装类
    private EpgManagerWrapper epgManagerWrapper;
    // 播放器状态监听实现类
    private PlayerStateListenerImpl playerStateListener;
    // 频道上下切换逻辑管理器
    private ChannelSwitchManager switchManager;

    // ====================== 页面状态标记 ======================
    // EPG节目单面板是否展开
    private boolean epgPanelOpen = false;
    // ExoPlayer原生控制器是否显示（全局默认关闭）
    private boolean isControllerVisible = false;

    // ====================== 用户配置项 ======================
    // 节目单功能总开关
    private boolean epg_enable;
    // 切台方向是否反转（上/下频道互换）
    private boolean channel_reverse;
    // 数字选台功能开关
    private boolean number_channel_enable;
    // 直播源自动更新开关
    private boolean auto_update_source;

    // 当前选中的EPG日期索引
    private int currentSelectedDateIndex = 0;
    // 本地配置文件存储
    private SharedPreferences sp;

    // ====================== 顶部播放信息栏控件 ======================
    private View info_bar; // 信息栏根布局
    private TextView tv_channel_name;    // 频道名称
    private TextView tv_tag_fhd;         // 清晰度标签
    private TextView tv_tag_audio;       // 音轨信息
    private TextView tv_bitrate;         // 实时码率
    private TextView tv_current_program_name;  // 当前播放节目名
    private TextView tv_current_time_range;    // 当前节目时间范围
    private TextView tv_remaining_time;        // 节目剩余时长
    private TextView tv_next_program_name;     // 下一档节目名
    private TextView tv_next_time_range;       // 下一档节目时间
    private android.widget.ProgressBar progress_program; // 节目播放进度条

    // 切台时弹出的频道数字提示
    private TextView tv_channel_num;

    // ====================== 全局常量定义 ======================
    // 直播链接最大重定向次数（301/302）
    private static final int MAX_REDIRECT_COUNT = 10;
    // 网络连接超时时间 8秒
    private static final int CONNECT_TIMEOUT = 8000;
    // 网络读取超时时间 8秒
    private static final int READ_TIMEOUT = 8000;
    // 切台防抖冷却时间（防止连续快速按键切台）
    private static final long CHANNEL_COOLDOWN = 300;
    // 手势滑动判定阈值（像素）
    private static final float SLIDE_THRESHOLD = 80;

    // ====================== 延迟执行任务 ======================
    // 自动隐藏顶部信息栏任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // 记录上一次切台时间，用于防抖
    private long lastChannelChangeTime = 0;
    // 手势滑动起始Y坐标
    private float touchStartY = 0;

    // ====================== 全局日志管理 ======================
    // 本地日志列表：新日志在前，最大存储100条
    public static List<String> logList = new ArrayList<>();

    /**
     * 统一日志输出方法
     * @param msg 日志内容
     */
    public static void log(String msg) {
        // 新日志插入头部
        logList.add(0, msg);
        // 超过100条则删除末尾旧日志
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        // 同步日志到设置页面
        SettingsActivity.log(msg);
    }

    // ====================== 广播接收器 ======================
    /**
     * 广播：切换播放器原生控制器显示/隐藏
     */
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    /**
     * 广播：刷新直播源与EPG节目单
     */
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 重新加载用户配置
                        loadSettings();
                        // 读取自定义直播源/EPG地址
                        String customLive = appConfig.getCustomLiveUrl();
                        String customEpg = appConfig.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        // 重新加载数据并刷新界面
                        loadLiveAndEpg();
                        Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    };

    // ====================== Activity 生命周期 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】onCreate -> 页面创建");

        // 初始化单例
        mInstance = this;
        // 强制页面横屏（电视/盒子专用）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 设置全屏标志
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 沉浸式状态栏，隐藏系统导航栏、状态栏
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        // 加载主布局
        setContentView(R.layout.activity_main);
        // 保持屏幕常亮，防止播放时息屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 绑定频道数字提示控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        // 初始化顶部信息栏所有控件
        initInfoBar();
        // 获取全局配置实例
        appConfig = AppConfig.getInstance(this);
        // 加载本地用户设置
        loadSettings();
        // 初始化本地SP对象
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 应用自定义直播源、EPG地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        // 初始化播放视图，关闭Exo原生控制器
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        // 绑定面板布局及所有列表控件
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册全局广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        // 节目单展开/收起按钮点击事件
btn_show_epg.setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View v) {
        // 校验EPG总开关
        if (!epg_enable) {
            Toast.makeText(MainActivity.this, "节目单功能已关闭", Toast.LENGTH_SHORT).show();
            return;
        }
        // 切换面板状态
        epgPanelOpen = !epgPanelOpen;
        lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
        lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);

        if (epgPanelOpen && !channelSourceList.isEmpty()) {
            // 打开节目单 → 默认回到【今天】
            currentSelectedDateIndex = 0;

            // 日志
            android.util.Log.d("EPG_DEBUG", "打开节目单 → 强制设置日期索引: " + currentSelectedDateIndex);

            // 刷新今天的节目单
            Channel curr = channelSourceList.get(currentPlayIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
        }
    }
});

// 日期列表点击：切换节目单日期
lvDate.setOnItemClickListener(new AdapterView.OnItemClickListener() {
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        // 日志：看有没有触发点击
        android.util.Log.d("EPG_DEBUG", "=====================================");
        android.util.Log.d("EPG_DEBUG", "日期被点击了 → 位置: " + position);

        // 更新选中日期
        currentSelectedDateIndex = position;
        android.util.Log.d("EPG_DEBUG", "本地日期索引已更新为: " + currentSelectedDateIndex);

        // 【关键】同步给 PanelManager
        panelManager.setSelectedDateIndex(position);
        android.util.Log.d("EPG_DEBUG", "同步到 PanelManager: " + position);

        // 刷新对应日期节目单
        if (!channelSourceList.isEmpty()) {
            Channel curr = channelSourceList.get(currentPlayIndex);
            android.util.Log.d("EPG_DEBUG", "调用刷新节目单，传入日期: " + currentSelectedDateIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
        }
    }
});
        // 频道分组点击切换
lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        
        // 获取选中分组名称
        String groupName = groupListManager.getCurrentGroup(position);
        
        // 筛选当前分组下的频道
        currentGroupChannelList.clear();
        for (Channel c : channelSourceList) {
            if (groupName.equals(c.getGroup())) {
                currentGroupChannelList.add(c);
            }
        }

        // 刷新频道列表UI
        channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);

        // ====================== 修复核心 ======================
        // 自动让列表滚动到【当前正在播放的频道】
        // 让光标/选中条 自动定位到当前播放频道
        if (!currentGroupChannelList.isEmpty()) {
            // 获取当前播放频道在【分组列表】中的位置
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            int posInGroup = currentGroupChannelList.indexOf(currentChannel);

            // 如果找到，自动滚动 + 选中
            if (posInGroup != -1) {
                // 让 ListView 选中该项（光标移动过去）
                lvChannelList.setItemChecked(posInGroup, true);
                // 让列表自动滚动到该项位置
                lvChannelList.setSelection(posInGroup);
            }
        }
    }
});

        // 初始化所有列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate(); // 初始化日期数据
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);
        // 初始化面板内的日期索引，和页面当前选中日期保持一致
         panelManager.setSelectedDateIndex(currentSelectedDateIndex);

        // 初始化播放器
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        // 播放器实时信息回调（清晰度、音轨、码率）
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            }
        });

        // 初始化画面比例并应用
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 初始化手势控制
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        // 初始化按键管理、频道切换管理
        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        // 读取上次退出时播放的频道索引
        currentPlayIndex = appConfig.getLastPlayIndex();

        log("【播放】记录上次播放索引：" + currentPlayIndex);
        // 加载直播源 + EPG节目单
        loadLiveAndEpg();
        // 初始化频道列表点击事件
        initListViewClick();
    }

    /**
     * 初始化顶部播放信息栏 所有控件绑定
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
     * 从SP加载用户配置项
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
     * 返回键逻辑：优先关闭频道面板，再退出页面
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
     * 加载直播源数据 + 加载EPG节目单数据
     */
    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");

        // 异步加载直播源
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【直播源】加载成功，频道总数：" + channels.size());

                // 更新全局频道列表
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                // 给切换管理器设置频道数据
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                // 刷新分组、频道列表UI
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                // 播放上次记录的频道
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                log("【直播源】加载失败：" + errorMsg);
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        // 加载EPG节目单
        log("【EPG】加载节目单：" + UrlConfig.EPG_URL);
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(new Runnable() {
            @Override
            public void run() {
                // 切回主线程刷新UI
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!channelSourceList.isEmpty()) {
                            epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex),
                                    channelSourceList, currentSelectedDateIndex);
                        }
                    }
                });
            }
        });
    }

    /**
 * 播放上一个频道
 * 限制：只在【当前选中分组】内循环切换，不会跨分组
 */
public void playPrev() {
    // 切台防抖：防止快速连续点击
    long now = System.currentTimeMillis();
    if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
    lastChannelChangeTime = now;

    log("【切台】上一台（当前分组内循环）");

    // 如果当前分组没有频道，直接退出
    if (currentGroupChannelList == null || currentGroupChannelList.isEmpty()) return;

    // 获取当前频道在【分组列表】中的位置
    int currentPosInGroup = -1;
    for (int i = 0; i < currentGroupChannelList.size(); i++) {
        if (currentGroupChannelList.get(i).getPlayUrl().equals(channelSourceList.get(currentPlayIndex).getPlayUrl())) {
            currentPosInGroup = i;
            break;
        }
    }

    // 找不到就默认从第0个开始
    if (currentPosInGroup == -1) {
        currentPosInGroup = 0;
    }

    // 计算上一个位置（循环：到顶后跳转到最后一个）
    int prevPos = currentPosInGroup - 1;
    if (prevPos < 0) {
        prevPos = currentGroupChannelList.size() - 1;
    }

    // 获取目标频道并切换
    Channel targetChannel = currentGroupChannelList.get(prevPos);
    int globalIndex = channelSourceList.indexOf(targetChannel);
    if (globalIndex == -1) {
        // 全局找不到，强制遍历匹配
        for (int i = 0; i < channelSourceList.size(); i++) {
            if (channelSourceList.get(i).getPlayUrl().equals(targetChannel.getPlayUrl())) {
                globalIndex = i;
                break;
            }
        }
    }

    if (globalIndex != -1) {
        playChannel(globalIndex);
    }
}
    /**
 * 播放下一个频道
 * 限制：只在【当前选中分组】内循环切换，不会跨分组
 */
public void playNext() {
    // 切台防抖：防止快速连续点击
    long now = System.currentTimeMillis();
    if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
    lastChannelChangeTime = now;

    log("【切台】下一台（当前分组内循环）");

    // 如果当前分组没有频道，直接退出
    if (currentGroupChannelList == null || currentGroupChannelList.isEmpty()) return;

    // 获取当前频道在【分组列表】中的位置（通过地址匹配，更稳定）
    int currentPosInGroup = -1;
    for (int i = 0; i < currentGroupChannelList.size(); i++) {
        if (currentGroupChannelList.get(i).getPlayUrl().equals(channelSourceList.get(currentPlayIndex).getPlayUrl())) {
            currentPosInGroup = i;
            break;
        }
    }

    // 找不到就默认从第0个开始
    if (currentPosInGroup == -1) {
        currentPosInGroup = 0;
    }

    // 计算下一个位置（循环：到底后跳转到第一个）
    int nextPos = currentPosInGroup + 1;
    if (nextPos >= currentGroupChannelList.size()) {
        nextPos = 0;
    }

    // 获取目标频道并切换
    Channel targetChannel = currentGroupChannelList.get(nextPos);
    int globalIndex = channelSourceList.indexOf(targetChannel);
    if (globalIndex == -1) {
        // 全局找不到，强制遍历匹配
        for (int i = 0; i < channelSourceList.size(); i++) {
            if (channelSourceList.get(i).getPlayUrl().equals(targetChannel.getPlayUrl())) {
                globalIndex = i;
                break;
            }
        }
    }

    if (globalIndex != -1) {
        playChannel(globalIndex);
    }
}
    
    /**
     * 播放指定索引频道
     * 内部自动处理直播链接 301/302 重定向，最大重试10次
     * @param index 频道全局索引
     */
    public void playChannel(int index) {
        // 列表为空直接返回
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            log("【播放】频道列表为空，无法播放");
            return;
        }

        // 索引边界保护，防止越界
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);

        // 播放地址为空判断
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            log("【播放】频道地址为空");
            return;
        }

        String originalUrl = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道名称：" + ch.getName());
        log("【播放】原始地址：" + originalUrl);
        log("【播放】当前索引：" + index);
        log("========================================");

        // 更新播放器状态监听的频道名
        playerStateListener.setCurrentChannelName(ch.getName());
        // 弹出频道数字提示
        showChannelNum(index + 1);
        // 保存当前播放索引到配置
        appConfig.setLastPlayIndex(index);
        // 刷新频道列表选中状态
        channelListManager.setChannels(channelSourceList, index);
        // 刷新对应频道节目单
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 显示顶部信息栏，3秒后自动隐藏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 3000);
            tv_channel_name.setText(ch.getName());
            // 刷新播放信息
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
        }

        // 使用数组存储最终播放地址（子线程修改）
        final String[] finalUrl = {originalUrl};
        // 子线程处理网络重定向（不能在主线程做网络请求）
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.HttpURLConnection conn = null;
                try {
                    // 循环处理重定向
                    for (int i = 0; i < MAX_REDIRECT_COUNT; i++) {
                        java.net.URL u = new java.net.URL(finalUrl[0]);
                        conn = (java.net.HttpURLConnection) u.openConnection();
                        // 设置超时
                        conn.setConnectTimeout(CONNECT_TIMEOUT);
                        conn.setReadTimeout(READ_TIMEOUT);
                        conn.setRequestMethod("GET");
                        // 关闭系统自动重定向，手动处理
                        conn.setInstanceFollowRedirects(false);

                        int code = conn.getResponseCode();
                        // 301/302 永久/临时重定向
                        if (code == 301 || code == 302) {
                            String loc = conn.getHeaderField("Location");
                            if (loc != null) {
                                finalUrl[0] = loc;
                                log("【重定向】新地址：" + loc);
                            }
                            conn.disconnect();
                            conn = null;
                        } else {
                            // 非重定向链接，跳出循环
                            break;
                        }
                    }
                } catch (Exception e) {
                    // 网络异常捕获
                    e.printStackTrace();
                } finally {
                    // 关闭连接，释放资源
                    if (conn != null) conn.disconnect();
                }

                // 切回主线程调用播放器播放最终地址
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        mPlayerManager.playUrl(finalUrl[0]);
                    }
                });
            }
        }).start();
    }

    /**
     * 显示频道数字提示，3秒后自动消失
     * @param num 频道号
     */
    public void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        // 延迟隐藏
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                tv_channel_num.setVisibility(View.GONE);
            }
        }, 3000);
    }

    /**
 * 初始化频道列表点击事件
 * 限制：只能点击切换当前分组内的频道，不会跳到其他分组
 */
private void initListViewClick() {
    ListView lvChannelList = findViewById(R.id.lv_channel_list);
    lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
            // 只从当前分组的列表中获取频道（安全隔离）
            if (!currentGroupChannelList.isEmpty() && pos < currentGroupChannelList.size()) {
                Channel selectedChannel = currentGroupChannelList.get(pos);
                int globalIndex = channelSourceList.indexOf(selectedChannel);

                if (globalIndex != -1) {
                    log("【列表点击】切换到当前分组频道：" + globalIndex);
                    playChannel(globalIndex);
                    togglePanel(); // 切台后关闭面板
                }
            }
        }
    });
}
    
    /**
     * 切换频道面板 显示/隐藏
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    /**
     * 打开设置页面
     */
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 接收远程配置更新直播源、EPG地址
     * @param liveUrl 新直播源地址
     * @param epgUrl 新EPG地址
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;

        log("【远程配置】更新直播源：" + liveUrl);
        log("【远程配置】更新EPG：" + epgUrl);
        // 主线程刷新数据
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadLiveAndEpg();
            }
        });
    }

    /**
     * 按键按下事件分发
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 交由按键管理器处理
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 页面切后台
     */
    @Override
    protected void onPause() {
        super.onPause();
        log("【主页】onPause -> 切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    /**
     * 页面回到前台
     */
    @Override
    protected void onResume() {
        super.onResume();
        log("【主页】onResume -> 回到前台");
        // 重新加载配置、刷新画面比例
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null)
            mPlayerManager.onForeground();
    }

    /**
     * 页面销毁，释放所有资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        // 注销广播，防止内存泄漏
        try {
            unregisterReceiver(toggleControllerReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(refreshReceiver);
        } catch (Exception ignored) {}
        // 释放播放器资源
        mPlayerManager.release();
        // 清空单例
        mInstance = null;
    }
}
