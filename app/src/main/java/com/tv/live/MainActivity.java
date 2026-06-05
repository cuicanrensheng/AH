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
import com.tv.live.manager.TVPlayerManager;
import com.tv.live.service.HttpConfigService;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public List<Channel> channelSourceList = new ArrayList<>();
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    public int currentPlayIndex = 0;
    private String nowSelectGroup = ""; // 新增：记录当前选中分组
    private View panel_layout;
    public TVPlayerManager mPlayerManager;
    private PlayerView playerView;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private PanelManager panelManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private HttpConfigService httpService;
    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PlayerStateListenerImpl playerStateListener;
    private ChannelSwitchManager switchManager;
    private boolean epgPanelOpen = false;
    private boolean isControllerVisible = false;
    private boolean epg_enable;
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean auto_update_source;
    private int currentSelectedDateIndex = 0;

    // 投屏相关（保留）
    private CastManager castManager;

    // 新增：屏幕常亮/信息栏/冷却等核心变量
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name;
    private ProgressBar progress_program;
    private TextView tv_channel_num;
    private static final int MAX_REDIRECT_COUNT = 10;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 8000;
    private static final String DEF_UA = "ExoPlayer";
    private static final String DEF_REFER = "https://www.huya.com/";
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() { info_bar.setVisibility(View.GONE); }
    };
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;
    private float touchStartY = 0;
    private static final long SLIDE_THRESHOLD = 80;
    public static List<String> logList = new ArrayList<>();

    // 新增：日志收集
    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) logList.remove(logList.size() - 1);
    }

    private final BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 修复：强制关闭系统控制器（老版本逻辑）
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(false);
            playerView.hideController();
        }
    };

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
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
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(R.layout.activity_main);

        // 修复1：添加屏幕常亮标记
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        appConfig = AppConfig.getInstance(this);
        loadSettings();

        // 初始化信息栏（修复2）
        initInfoBar();

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        playerView = findViewById(R.id.player_view);
        // 修复3：强制关闭系统控制器（老版本逻辑）
        playerView.setUseController(false);
        playerView.hideController();
        playerView.setControllerVisibilityListener(visibility -> {
            if (visibility == View.VISIBLE) playerView.hideController();
        });

        panel_layout = findViewById(R.id.panel_layout);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 投屏初始化（保留）
        castManager = CastManager.getInstance(this);
        View btn_cast = findViewById(R.id.btn_cast);
        if (btn_cast != null) {
            btn_cast.setOnClickListener(v -> {
                if (castManager.isCasting()) {
                    castManager.disconnect();
                    Toast.makeText(this, "已断开投屏", Toast.LENGTH_SHORT).show();
                } else {
                    castManager.openCastPicker();
                    Toast.makeText(this, "请选择投屏设备", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 修复4：修正广播Action（老版本是TOGGLE_CONTROL）
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // 修复5：EPG点击时重建EpgManagerWrapper保证刷新
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
                // 重建EpgManagerWrapper保证刷新
                epgManagerWrapper = new EpgManagerWrapper(MainActivity.this, lvEpg);
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        // 修复6：日期选择时重建EpgManagerWrapper
        dateListManager = new DateListManager(this, lvDate);
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper = new EpgManagerWrapper(MainActivity.this, lvEpg);
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, pos);
            }
        });

        // 修复7：分组选择时重建ChannelListManager保证刷新
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            nowSelectGroup = groupListManager.getCurrentGroup(position);
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (nowSelectGroup.equals(c.getGroup()))
                    currentGroupChannelList.add(c);
            }
            // 重建ChannelListManager
            channelListManager = new ChannelListManager(MainActivity.this, lvChannelList);
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
        });

        // 初始化列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        channelListManager.setOnChannelClickListener(filterPos -> {
            if (filterPos >= 0 && filterPos < currentGroupChannelList.size()) {
                Channel ch = currentGroupChannelList.get(filterPos);
                int globalIdx = channelSourceList.indexOf(ch);
                if (globalIdx != -1) {
                    playChannel(globalIdx);
                    togglePanel();
                }
            }
        });

        groupListManager = new GroupListManager(this, lvGroup);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        // 修复8：添加直播信息更新监听
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 修复9：手势触摸处理（保留滑动阈值）
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            // 记录触摸起始坐标
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchStartY = event.getY();
            }
            gestureHelper.handleTouch(event);
            return true;
        });

        keyEventManager = new KeyEventManager(this);
        httpService = HttpConfigService.getInstance();
        httpService.start();
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        loadLiveAndEpg();
    }

    // 新增：初始化信息栏
    private void initInfoBar() {
        info_bar = findViewById(R.id.info_bar);
        tv_channel_num = findViewById(R.id.tv_channel_num);
        tv_channel_name = findViewById(R.id.tv_channel_name);
        tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        tv_tag_audio = findViewById(R.id.tv_tag_audio);
        tv_bitrate = findViewById(R.id.tv_bitrate);
        tv_current_program_name = findViewById(R.id.tv_current_program_name);
        tv_current_time_range = findViewById(R.id.tv_current_time_range);
        progress_program = findViewById(R.id.progress_program);
        tv_remaining_time = findViewById(R.id.tv_remaining_time);
        tv_next_program_name = findViewById(R.id.tv_next_program_name);
    }

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
    }

    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
            playerView.requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    // 修复10：loadLiveAndEpg中完善分组逻辑，重建列表管理器
    public void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);

                if(!TextUtils.isEmpty(nowSelectGroup)){
                    currentGroupChannelList.clear();
                    for(Channel ch : channelSourceList){
                        if(ch.getGroup().equals(nowSelectGroup))
                            currentGroupChannelList.add(ch);
                    }
                    channelListManager = new ChannelListManager(MainActivity.this, findViewById(R.id.lv_channel_list));
                    channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                }else{
                    List<String> groups = groupListManager.getGroupList();
                    if(groups != null && groups.size() > 0){
                        nowSelectGroup = groups.get(0);
                        currentGroupChannelList.clear();
                        for(Channel ch : channelSourceList)
                            if(ch.getGroup().equals(nowSelectGroup))
                                currentGroupChannelList.add(ch);
                        channelListManager = new ChannelListManager(MainActivity.this, findViewById(R.id.lv_channel_list));
                        channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                    }else {
                        channelListManager = new ChannelListManager(MainActivity.this, findViewById(R.id.lv_channel_list));
                        channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    }
                }
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper = new EpgManagerWrapper(MainActivity.this, findViewById(R.id.lv_epg));
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    // 修复11：频道切换添加冷却逻辑
    public void playPrev() {
        long now = System.currentTimeMillis();
        if(now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    public void playNext() {
        long now = System.currentTimeMillis();
        if(now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    // 修复12：完善playChannel逻辑，添加URL重定向解析、信息栏显示、列表重建
    public void playChannel(int index) {
        if(channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if(TextUtils.isEmpty(ch.getPlayUrl())) return;
        final String original = ch.getPlayUrl();

        // 解析URL重定向（获取真实播放地址）
        new Thread(()->{
            String realUrl = original;
            HttpURLConnection conn = null;
            try{
                for(int i=0;i<MAX_REDIRECT_COUNT;i++){
                    URL u = new URL(realUrl);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", DEF_UA);
                    conn.setRequestProperty("Referer", DEF_REFER);
                    conn.setInstanceFollowRedirects(false);
                    int code = conn.getResponseCode();
                    if(code == 301 || code == 302){
                        String loc = conn.getHeaderField("Location");
                        if(loc != null) realUrl = loc;
                        conn.disconnect(); conn = null;
                    }else break;
                }
            }catch(Exception e){
                log("URL重定向解析失败：" + e.getMessage());
            }finally{
                if(conn != null) conn.disconnect();
            }
            String finalPlay = TextUtils.isEmpty(realUrl) ? original : realUrl;
            new Handler(Looper.getMainLooper()).post(()-> mPlayerManager.playUrl(finalPlay));
        }).start();

        // 显示频道号
        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);

        // 重建频道列表管理器
        if(!TextUtils.isEmpty(nowSelectGroup)) {
            channelListManager = new ChannelListManager(MainActivity.this, findViewById(R.id.lv_channel_list));
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, index);
        } else {
            channelListManager = new ChannelListManager(MainActivity.this, findViewById(R.id.lv_channel_list));
            channelListManager.setChannels(channelSourceList, index);
        }

        // 重建EPG管理器
        epgManagerWrapper = new EpgManagerWrapper(MainActivity.this, findViewById(R.id.lv_epg));
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 显示信息栏
        if(info_bar != null){
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 2000);
            tv_channel_name.setText(ch.getName());
            TVPlayerManager.LiveInfo info = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        }
    }

    // 新增：显示频道号
    public void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(()-> tv_channel_num.setVisibility(View.GONE), 3000);
    }

    public void togglePanel(){
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    public void openSettings(){
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl){
        appConfig.setCustomUrls(liveUrl, epgUrl);
        if(liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if(epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        runOnUiThread(this::loadLiveAndEpg);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    // 修复13：添加onPause/onResume的播放器状态处理
    @Override
    protected void onPause() {
        super.onPause();
        if(mPlayerManager != null) mPlayerManager.onBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
        if(mPlayerManager != null) mPlayerManager.onForeground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        httpService.stop();
        mPlayerManager.release();
        if (castManager != null) {
            castManager.release();
        }
        mInstance = null;
    }
}
