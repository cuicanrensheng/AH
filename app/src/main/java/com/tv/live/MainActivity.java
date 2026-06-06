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
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 直播 APP 主页面
 * 功能：播放直播、频道分组、上下切台、节目单(EPG)、手势控制、遥控器、投屏、画面缩放
 * 核心特性：后台自动暂停、屏蔽 Exo 原生控制器、分组状态持久化
 */
public class MainActivity extends AppCompatActivity {
    // 全局单例，方便其他类访问当前页面实例
    public static MainActivity mInstance;

    // 所有频道的总数据源
    public List<Channel> channelSourceList = new ArrayList<>();

    // 当前选中分组下的频道列表
    public List<Channel> currentGroupChannelList = new ArrayList<>();

    // 当前正在播放的频道索引
    public int currentPlayIndex = 0;

    // 记录当前选中的分组名称，实现切台/刷新后分组不重置
    private String nowSelectGroup = "";

    // 右侧侧边栏面板（频道列表 + EPG）
    private View panel_layout;

    // 播放器核心管理类
    public TVPlayerManager mPlayerManager;

    // ExoPlayer 播放视图
    private PlayerView playerView;

    // APP 配置管理（源地址、自定义配置）
    private AppConfig appConfig;

    // 画面比例管理（16:9、4:3、填充等）
    private ScreenRatioManager screenRatioManager;

    // 侧边面板管理类
    private PanelManager panelManager;

    // 手势控制管理（音量、亮度、快进）
    private GestureManager gestureManager;

    // 遥控器按键管理
    private KeyEventManager keyEventManager;

    // 频道列表适配器管理
    private ChannelListManager channelListManager;

    // 频道分组管理（央视/卫视/少儿等）
    private GroupListManager groupListManager;

    // EPG 日期选择管理
    private DateListManager dateListManager;

    // EPG 节目单展示管理
    private EpgManagerWrapper epgManagerWrapper;

    // 播放器状态监听（加载中、播放、错误）
    private PlayerStateListenerImpl playerStateListener;

    // 上下切台管理工具
    private ChannelSwitchManager switchManager;

    // EPG 面板是否展开
    private boolean epgPanelOpen = false;

    // Exo 原生控制器开关（已全局禁用）
    private boolean isControllerVisible = false;

    // ===================== 配置项 =====================
    private boolean epg_enable;           // EPG 节目单总开关
    private boolean channel_reverse;      // 上下切台方向反转
    private boolean number_channel_enable;// 数字选台功能开关
    private boolean auto_update_source;   // 自动更新源开关
    // ==================================================

    // 当前选中的 EPG 日期索引
    private int currentSelectedDateIndex = 0;

    // 本地配置存储
    private SharedPreferences sp;

    // ===================== 播放信息 UI =====================
    private View info_bar;                          // 频道信息悬浮条
    private TextView tv_channel_name;              // 频道名称
    private TextView tv_tag_fhd;                   // 画质标签
    private TextView tv_tag_audio;                 // 音频信息
    private TextView tv_bitrate;                   // 码率
    private TextView tv_current_program_name;      // 当前节目
    private TextView tv_current_time_range;        // 节目时间
    private TextView tv_remaining_time;           // 剩余时间
    private TextView tv_next_program_name;        // 下个节目
    private TextView tv_next_time_range;          // 下个节目时间
    private android.widget.ProgressBar progress_program; // 节目进度条
    // ========================================================

    // 右上角频道数字提示
    private TextView tv_channel_num;

    // ===================== 网络请求配置 =====================
    private static final int MAX_REDIRECT_COUNT = 10;    // 最大重定向次数
    private static final int CONNECT_TIMEOUT = 8000;    // 连接超时
    private static final int READ_TIMEOUT = 8000;       // 读取超时
    private static final String DEF_UA = "ExoPlayer";    // 默认 UA
    private static final String DEF_REFER = "https://www.huya.com/"; // 默认 Referer
    // ========================================================

    // 自动隐藏频道信息栏的任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // 切台防抖（防止快速连续切台）
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;   // 防抖间隔 300ms

    // 手势滑动相关变量
    private float touchStartY = 0;
    private static final long SLIDE_THRESHOLD = 80;

    // 日志缓存列表（最多保存100条）
    public static List<String> logList = new ArrayList<>();

    /**
     * 全局日志方法，输出到设置页面日志面板
     */
    public static void log(String msg) {
        logList.add(0, msg);
        // 限制日志数量
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }

    /**
     * 广播：控制 Exo 原生控制器显示/隐藏（已禁用）
     */
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

    /**
     * 广播：刷新直播源 + EPG 节目单
     */
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadSettings();
                        // 获取自定义源地址
                        String customLive = appConfig.getCustomLiveUrl();
                        String customEpg = appConfig.getCustomEpgUrl();
                        if (customLive != null) UrlConfig.LIVE_URL = customLive;
                        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                        // 重新加载数据
                        loadLiveAndEpg();
                        Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    };

    /**
     * 页面创建
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】onCreate -> 页面创建");
        mInstance = this;

        // 强制横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // 设置全屏 + 隐藏系统栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        setContentView(R.layout.activity_main);

        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 初始化控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        initInfoBar();

        // 初始化配置
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        // 读取自定义直播源/EPG地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        // 初始化播放器，禁用原生控制器
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        // 绑定布局
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // EPG 展开/收起按钮点击
        btn_show_epg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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
            }
        });
        
         // EPG 日期点击：切换日期刷新节目单
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            currentSelectedDateIndex = position;
            if (!channelSourceList.isEmpty()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // 分组点击：切换分组，不自动播放第一个频道
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                lvGroup.setItemChecked(position, true);
                lvGroup.setSelection(position);
                // 保存当前分组
                nowSelectGroup = groupListManager.getCurrentGroup(position);
                // 筛选当前分组的频道
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (nowSelectGroup.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                // 刷新频道列表
                channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
            }
        });

        // 频道列表点击：播放选中频道
        channelListManager = new ChannelListManager(this, lvChannelList);
        channelListManager.setOnChannelClickListener(filterPos->{
            if(filterPos >=0 && filterPos < currentGroupChannelList.size()){
                Channel target = currentGroupChannelList.get(filterPos);
                int global = channelSourceList.indexOf(target);
                if(global != -1){
                    playChannel(global);
                    togglePanel();
                }
            }
        });

        // 初始化各管理器（修复初始化顺序）
        groupListManager = new GroupListManager(this, lvGroup);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager = new DateListManager(this, lvDate);
        dateListManager.initDate();
        
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 播放器初始化
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 实时更新画质、码率信息
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            }
        });

        // 画面比例设置
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 手势控制
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        // 遥控器、切台管理
        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex(); // 读取上次播放记录
        log("【播放】记录上次播放索引：" + currentPlayIndex);

        // 加载直播源和节目单
        loadLiveAndEpg();
    }

    /**
     * 初始化频道信息悬浮栏
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
     * 读取本地配置（开关状态）
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
     * 返回键逻辑：先关闭侧边栏，再退出应用
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
     * 加载直播频道源 + EPG节目单
     */
    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【直播源】加载成功，频道总数：" + channels.size());
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);

                // 保持上次选中的分组，不自动重置
                if(!TextUtils.isEmpty(nowSelectGroup)){
                    currentGroupChannelList.clear();
                    for(Channel ch:channelSourceList){
                        if(ch.getGroup().equals(nowSelectGroup)){
                            currentGroupChannelList.add(ch);
                        }
                    }
                    channelListManager.setChannelsByGroup(channelSourceList,nowSelectGroup,currentPlayIndex);
                }else{
                    // 第一次进入，默认选中第一个分组
                    List<String> groups = groupListManager.getGroupList();
                    if(groups != null && groups.size()>0){
                        nowSelectGroup = groups.get(0);
                        currentGroupChannelList.clear();
                        for(Channel ch:channelSourceList){
                            if(ch.getGroup().equals(nowSelectGroup)) currentGroupChannelList.add(ch);
                        }
                        channelListManager.setChannelsByGroup(channelSourceList,nowSelectGroup,currentPlayIndex);
                    }else {
                        channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    }
                }
                // 播放上次记录的频道
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                log("【直播源】加载失败：" + errorMsg);
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        // 加载 EPG 节目单
        log("【EPG】加载节目单：" + UrlConfig.EPG_URL);
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!channelSourceList.isEmpty()) {
                            epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
                        }
                    }
                });
            }
        });
    }

    /**
     * 播放上一个频道
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        log("【切台】上一台");
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 播放下一个频道
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        log("【切台】下一台");
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 播放指定索引的频道
     * 自动处理重定向、播放、UI刷新
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            log("【播放】频道列表为空，无法播放");
            return;
        }
        // 防止索引越界
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;

        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
            log("【播放】频道地址为空");
            return;
        }

        final String originalUrl = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道：" + ch.getName());
        log("【原始地址】：" + originalUrl);
        log("========================================");

        // 设置当前频道名称
        playerStateListener.setCurrentChannelName(ch.getName());

        // 子线程处理 301/302 重定向
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
                    conn.setRequestProperty("Referer", DEF_REFER);
                    conn.setRequestProperty("Origin", "https://www.huya.com");
                    conn.setRequestProperty("Icy-MetaData", "1");
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setInstanceFollowRedirects(false);

                    int code = conn.getResponseCode();
                    if (code == 301 || code == 302) {
                        String loc = conn.getHeaderField("Location");
                        if (loc != null) finalUrl = loc;
                        log("【重定向" + (step + 1) + "次】→ " + finalUrl);
                        conn.disconnect();
                        conn = null;
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                log("【解析失败】使用原始地址播放");
            } finally {
                if (conn != null) conn.disconnect();
            }

            // 最终播放地址
            String playUrl = TextUtils.isEmpty(finalUrl) ? originalUrl : finalUrl;
            log("【最终播放地址】→ " + playUrl);

            // 切回主线程播放
            new Handler(Looper.getMainLooper()).post(() -> {
                mPlayerManager.playUrl(playUrl);
            });
        }).start();

        // 显示频道号
        showChannelNum(index + 1);
        // 保存播放记录
        appConfig.setLastPlayIndex(index);

        // 切台后保持当前分组，不跳回全部频道
        if(!TextUtils.isEmpty(nowSelectGroup)){
            channelListManager.setChannelsByGroup(channelSourceList,nowSelectGroup,index);
        }else{
            channelListManager.setChannels(channelSourceList, index);
        }

        // 刷新节目单
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 显示频道信息条，4秒后自动隐藏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 4000);
            tv_channel_name.setText(ch.getName());

            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
        }
    }

    /**
     * 提取房间ID（备用工具方法）
     */
    private int extractRoomId(String url) {
        try {
            if (url.contains("id=")) {
                return Integer.parseInt(url.split("id=")[1].replaceAll("[^0-9]", ""));
            }
            if (url.contains("huya.com/")) {
                return Integer.parseInt(url.split("huya.com/")[1].replaceAll("[^0-9]", ""));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * 显示右上角频道号，4秒后消失
     */
    public void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            tv_channel_num.setVisibility(View.GONE);
        }, 4000);
    }

    /**
     * 切换侧边栏显示/隐藏
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    /**
     * 打开设置页面
     */
    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    /**
     * 接收远程配置更新（更新源地址）
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        log("【远程配置】更新直播源：" + liveUrl);
        log("【远程配置】更新EPG：" + epgUrl);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadLiveAndEpg();
            }
        });
    }

    /**
     * 遥控器按键分发
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 切后台：暂停播放
     */
    @Override
    protected void onPause() {
        super.onPause();
        log("【主页】onPause -> 切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    /**
     * 回到前台：恢复播放
     */
    @Override
    protected void onResume() {
        super.onResume();
        log("【主页】onResume -> 回到前台");
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null)
            mPlayerManager.onForeground();
    }

    /**
     * 页面销毁：释放所有资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        // 注销广播
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        // 释放播放器
        mPlayerManager.release();
        mInstance = null;
    }
}
