package com.tv.live;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import com.tv.live.widget.*;
import java.util.ArrayList;
import java.util.List;
/**
 * 电视直播主界面
 * 功能：播放、切台、EPG节目单、预约、回看、开播提醒、底部信息栏
 * 已优化：移除“正在播放”弹窗、EPG从UrlConfig.EPG_URL链接加载、全功能正常
 */
public class MainActivity extends AppCompatActivity {
    // 当前页面实例
    public static MainActivity mInstance;
    // 频道数据源
    public List<Channel> channelSourceList = new ArrayList<>();
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    public int currentPlayIndex = 0;
    // 界面控件
    private View panel_layout;
    private PlayerView playerView;
    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    // 功能管理器
    private PanelManager panelManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    // 状态标记
    private boolean epgPanelOpen = false;
    private boolean isControllerVisible = false;
    private boolean epg_enable;
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean auto_update_source;
    private int currentSelectedDateIndex = 0;
    // 底部信息栏控件
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name, tv_next_time_range;
    private android.widget.ProgressBar progress_program;
    // 切台显示的台号
    private TextView tv_channel_num;
    // 底部信息栏自动隐藏
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };
    // 防快速切台
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;
    // 日志
    public static List<String> logList = new ArrayList<>();
    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }
    // 广播：切换播放器控制器
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };
    // 广播：刷新直播源 + EPG
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                runOnUiThread(() -> {
                    loadSettings();
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
        mInstance = this;
        // 强制横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 全屏 + 隐藏状态栏导航栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        // 加载布局
        setContentView(R.layout.activity_main);
        // 屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 绑定台号控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        // 初始化底部信息栏
        initInfoBar();
        // 配置管理
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        // 读取自定义直播源/EPG地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        // 绑定播放器
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false); // 关闭系统自带控制栏
        playerView.setControllerVisibilityListener(null);
        // 绑定侧边栏控件
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);
        // 注册广播
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));
        // 初始化列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);
        //注入全局上下文
        TVPlayerManager.setAppContext(getApplicationContext());
        // 初始化播放器
        mPlayerManager = TVPlayerManager.getInstance();
        if(mPlayerManager != null){
            mPlayerManager.attachPlayerView(playerView);
            // 清晰度/音轨/码率回调
            mPlayerManager.setOnLiveInfoUpdateListener(info -> {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            });
        }
        // 画面比例设置
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        // 手势控制（音量/亮度/快进快退）
        gestureManager = new GestureManager(this);
        PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });
        // 遥控器按键管理
        keyEventManager = new KeyEventManager(this);
        // 读取上次播放频道
        currentPlayIndex = appConfig.getLastPlayIndex();
        // 加载频道数据 + EPG
        loadLiveAndEpg();
        // 频道列表点击事件
        initListViewClick();
    }
    /**
     * 初始化底部信息栏（频道名、清晰度、节目信息等）
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
        // 显示节目相关区域
        tv_current_program_name.setVisibility(View.VISIBLE);
        tv_current_time_range.setVisibility(View.VISIBLE);
        progress_program.setVisibility(View.VISIBLE);
        tv_remaining_time.setVisibility(View.VISIBLE);
        tv_next_program_name.setVisibility(View.VISIBLE);
        tv_next_time_range.setVisibility(View.VISIBLE);
    }
    /**
     * 读取设置
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
    }
    /**
     * 返回键逻辑
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
     * 加载直播源 + 加载EPG（从UrlConfig.EPG_URL链接获取）
     */
    public void loadLiveAndEpg() {
        // 加载频道列表
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                playChannel(currentPlayIndex);
            }
            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });
        // 加载EPG节目单（网络链接）
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }
    /**
     * 上一台
     */
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        int idx = channel_reverse ? ChannelSwitchManager.getInstance().next() : ChannelSwitchManager.getInstance().prev();
        playChannel(idx);
    }
    /**
     * 下一台
     */
    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        int idx = channel_reverse ? ChannelSwitchManager.getInstance().prev() : ChannelSwitchManager.getInstance().next();
        playChannel(idx);
    }
    /**
     * 播放指定频道（核心方法）
     */
    public void playChannel(int index) {
        if(mPlayerManager == null) return;
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        // 防止越界
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) return;
        // 开始播放
        mPlayerManager.playUrl(ch.getPlayUrl());
        // 显示台号
        showChannelNum(index + 1);
        // 保存播放记录
        appConfig.setLastPlayIndex(index);
        // 刷新频道列表选中状态
        channelListManager.setChannels(channelSourceList, index);
        // 刷新EPG节目单（从网络链接加载）
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
        // 显示底部信息栏（2秒后自动隐藏）
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 2000);
            // 频道基本信息
            tv_channel_name.setText(ch.getName());
            // 节目信息（EPG加载后会自动更新）
            tv_current_program_name.setText("正在播放");
            tv_current_time_range.setText("");
            tv_remaining_time.setText("");
            progress_program.setProgress(0);
            tv_next_program_name.setText("");
            tv_next_time_range.setText("");
        }
    }
    /**
     * 切台显示台号，3秒后消失
     */
    public void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler().postDelayed(() -> tv_channel_num.setVisibility(View.GONE), 3000);
    }
    /**
     * 频道列表点击事件
     */
    private void initListViewClick() {
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> {
            int globalIndex;
            if (!currentGroupChannelList.isEmpty() && pos < currentGroupChannelList.size()) {
                Channel selectedChannel = currentGroupChannelList.get(pos);
                globalIndex = channelSourceList.indexOf(selectedChannel);
            } else {
                globalIndex = pos;
            }
            if (globalIndex >= 0) playChannel(globalIndex);
            togglePanel();
        });
    }
    /**
     * 显示/隐藏侧边频道面板
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
     * 设置页回调：更新直播源/EPG地址
     */
    public void onReceiveConfig(String liveUrl, String epgUrl) {
        appConfig.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        runOnUiThread(this::loadLiveAndEpg);
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
     * 后台暂停播放
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayerManager != null) mPlayerManager.onBackground();
    }
    /**
     * 前台恢复播放
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null) mPlayerManager.onForeground();
    }
    /**
     * 销毁页面，释放资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        if(mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }
}
