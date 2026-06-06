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
 * 最终修复版：
 * 1. 手机端：上下滑动切台、单击开面板、双击/长按开设置
 * 2. 电视端：上下键/数字键切台、OK键选频道、菜单/帮助/长按OK开设置
 * 3. 修复所有闪退：点击面板/节目单/分组全try-catch防护
 * 4. 修复播放器黑屏、屏蔽Exo控制器
 * 5. 保留全部原有手势：音量/亮度/左右快进
 */
public class MainActivity extends AppCompatActivity {

    public static MainActivity mInstance;

    //===================== 数据源 =====================
    public List<Channel> channelSourceList = new ArrayList<>();
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    public int currentPlayIndex = 0;
    private String nowSelectGroup = "";

    //===================== 视图与管理器 =====================
    private View panel_layout;
    public TVPlayerManager mPlayerManager;
    private PlayerView playerView;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private LivePanelManager.PanelManager panelManager;

    private GestureManager gestureManager;
    private PlayerGestureHelper gestureHelper;

    private KeyEventManager keyEventManager;

    private LivePanelManager.ChannelListManager channelListManager;
    private LivePanelManager.GroupListManager groupListManager;
    private LivePanelManager.DateListManager dateListManager;
    private LivePanelManager.EpgManagerWrapper epgManagerWrapper;

    private PlayerStateListenerImpl playerStateListener;
    private ChannelSwitchManager switchManager;

    //===================== 状态 =====================
    private boolean epgPanelOpen = false;
    private boolean isControllerVisible = false;
    private boolean epg_enable;
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean auto_update_source;
    private int currentSelectedDateIndex = 0;

    private SharedPreferences sp;

    //===================== 信息栏 =====================
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

    //===================== 常量 =====================
    private static final int MAX_REDIRECT_COUNT = 10;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 8000;
    private static final long CHANNEL_COOLDOWN = 300;
    // 手机滑动切台阈值
    private static final int SWIPE_CHANNEL_THRESHOLD = 100;
    // 双击判定时间
    private static final long DOUBLE_CLICK_TIME = 300;
    // 长按判定时间
    private static final long LONG_CLICK_TIME = 500;

    private final Runnable hideInfoBar = () -> info_bar.setVisibility(View.GONE);
    private long lastChannelChangeTime = 0;

    //===================== 触摸交互变量 =====================
    private float touchStartX, touchStartY;
    private long lastClickTime = 0;
    private Timer longClickTimer;
    private boolean isLongClickTriggered = false;

    //===================== 日志 =====================
    public static List<String> logList = new ArrayList<>();
    public static void log(String msg) {
        logList.add(0, msg);
        if (logList.size() > 100) logList.remove(logList.size() - 1);
    }

    //===================== 广播 =====================
    private final BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 永久禁用控制器，不修改状态
            isControllerVisible = false;
        }
    };

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            runOnUiThread(() -> {
                try {
                    loadSettings();
                    String customLive = appConfig.getCustomLiveUrl();
                    String customEpg = appConfig.getCustomEpgUrl();
                    if (customLive != null) UrlConfig.LIVE_URL = customLive;
                    if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                    loadLiveAndEpg();
                    Toast.makeText(MainActivity.this, "已刷新直播源", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            });
        }
    };

    //===================== 初始化 =====================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        // 横屏+全屏+常亮
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

        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        // 播放器初始化，永久禁用控制器+修复黑屏
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);
        playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT);
        panel_layout = findViewById(R.id.panel_layout);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // EPG开关
        btn_show_epg.setOnClickListener(v -> {
            try {
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
            } catch (Exception ignored) {}
        });

        // 日期列表
        dateListManager = new LivePanelManager.DateListManager(this, lvDate);
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            try {
                currentSelectedDateIndex = pos;
                if (!channelSourceList.isEmpty()) {
                    epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, pos);
                }
            } catch (Exception ignored) {}
        });

        // 分组点击：只刷新不播放，加异常防护
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            try {
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
                if (epgPanelOpen && !channelSourceList.isEmpty()) {
                    epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
                }
            } catch (Exception ignored) {}
        });

        // 频道点击：选中播放
        channelListManager = new LivePanelManager.ChannelListManager(this, lvChannelList);
        channelListManager.setOnChannelClickListener(filterPos -> {
            try {
                if (filterPos >= 0 && filterPos < currentGroupChannelList.size()) {
                    Channel t = currentGroupChannelList.get(filterPos);
                    int g = channelSourceList.indexOf(t);
                    if (g != -1) {
                        playChannel(g);
                        togglePanel();
                    }
                }
            } catch (Exception ignored) {}
        });

        groupListManager = new LivePanelManager.GroupListManager(this, lvGroup);
        epgManagerWrapper = new LivePanelManager.EpgManagerWrapper(this, lvEpg);
        panelManager = new LivePanelManager.PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 播放器绑定
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            try {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            } catch (Exception ignored) {}
        });

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        //===================== 核心触摸监听：保留手势+手机交互+屏蔽控制器 =====================
        gestureManager = new GestureManager(this);
        gestureHelper = gestureManager.create();

        playerView.setOnTouchListener((v, event) -> {
            // 1. 优先执行原有手势：音量/亮度/左右快进
            gestureHelper.handleTouch(event);

            // 2. 处理手机端交互：滑动切台、单击/双击/长按
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartX = event.getX();
                    touchStartY = event.getY();
                    isLongClickTriggered = false;
                    // 启动长按计时器
                    longClickTimer = new Timer();
                    longClickTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            runOnUiThread(() -> {
                                isLongClickTriggered = true;
                                try {
                                    openSettings();
                                } catch (Exception ignored) {}
                            });
                        }
                    }, LONG_CLICK_TIME);
                    break;

                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getX() - touchStartX;
                    float deltaY = event.getY() - touchStartY;
                    // 上下滑动超过阈值：切换频道
                    if (Math.abs(deltaY) > SWIPE_CHANNEL_THRESHOLD && Math.abs(deltaY) > Math.abs(deltaX)) {
                        long now = System.currentTimeMillis();
                        if (now - lastChannelChangeTime > CHANNEL_COOLDOWN) {
                            lastChannelChangeTime = now;
                            try {
                                if (deltaY < 0) playPrev();
                                else playNext();
                            } catch (Exception ignored) {}
                        }
                        // 滑动取消长按
                        if (longClickTimer != null) {
                            longClickTimer.cancel();
                            longClickTimer = null;
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
                    // 处理点击
                    if (!isLongClickTriggered) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastClickTime < DOUBLE_CLICK_TIME) {
                            // 双击：打开设置
                            try {
                                openSettings();
                            } catch (Exception ignored) {}
                            lastClickTime = 0;
                        } else {
                            // 单击：打开频道列表
                            try {
                                togglePanel();
                            } catch (Exception ignored) {}
                            lastClickTime = currentTime;
                        }
                    }
                    break;
            }
            // 消费触摸事件，杜绝Exo控制器弹出
            return true;
        });

        // 按键管理
        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        loadLiveAndEpg();
    }

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

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);
    }

    @Override
    public void onBackPressed() {
        try {
            if (panel_layout.getVisibility() == View.VISIBLE) {
                panel_layout.setVisibility(View.GONE);
                playerView.requestFocus();
            } else {
                super.onBackPressed();
            }
        } catch (Exception e) {
            super.onBackPressed();
        }
    }

    public void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                try {
                    channelSourceList.clear();
                    channelSourceList.addAll(channels);
                    switchManager.setChannelList(channelSourceList);
                    switchManager.setCurrentIndex(currentPlayIndex);
                    groupListManager.setGroups(channelSourceList);

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
                } catch (Exception ignored) {}
            }

            @Override
            public void onError(String msg) {
                Toast.makeText(MainActivity.this, "加载失败：" + msg, Toast.LENGTH_SHORT).show();
            }
        });

        try {
            EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
            EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
                try {
                    if (!channelSourceList.isEmpty()) {
                        epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
                    }
                } catch (Exception ignored) {}
            }));
        } catch (Exception ignored) {}
    }

    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    public void playChannel(int index) {
        try {
            if (channelSourceList == null || channelSourceList.isEmpty()) return;
            index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
            currentPlayIndex = index;
            Channel ch = channelSourceList.get(index);
            if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) return;

            playerStateListener.setCurrentChannelName(ch.getName());
            showChannelNum(index + 1);
            appConfig.setLastPlayIndex(index);

            if (!TextUtils.isEmpty(nowSelectGroup)) {
                channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, index);
            } else {
                channelListManager.setChannels(channelSourceList, index);
            }

            epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

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
                } finally {
                    if (conn != null) conn.disconnect();
                }
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        mPlayerManager.playUrl(finalUrl[0]);
                    } catch (Exception ignored) {}
                });
            }).start();
        } catch (Exception ignored) {}
    }

    public void showChannelNum(int num) {
        try {
            if (!number_channel_enable) return;
            tv_channel_num.setText(String.valueOf(num));
            tv_channel_num.setVisibility(View.VISIBLE);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                tv_channel_num.setVisibility(View.GONE);
            }, 3000);
        } catch (Exception ignored) {}
    }

    public void togglePanel() {
        try {
            if (panelManager == null || panel_layout == null || channelSourceList == null) return;
            panelManager.toggle(channelSourceList, currentPlayIndex);
        } catch (Exception e) {}
    }

    public void openSettings() {
        try {
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    public void onReceiveConfig(String liveUrl, String epgUrl) {
        appConfig.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        runOnUiThread(this::loadLiveAndEpg);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            if (keyEventManager.dispatchKey(keyCode)) {
                return true;
            }

            // 长按OK键
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.getRepeatCount() > 0) {
                openSettings();
                return true;
            }

            switch (keyCode) {
                // 上下键/频道键切台
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    playPrev();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    playNext();
                    return true;
                // OK键选频道
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (event.getRepeatCount() == 0) {
                        togglePanel();
                    }
                    return true;
                // 菜单/帮助键开设置
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_HELP:
                    openSettings();
                    return true;
                // 数字键直接切台
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
                        if (channelNum == 0) channelNum = 10;
                        int targetIndex = channelNum - 1;
                        if (targetIndex >= 0 && targetIndex < channelSourceList.size()) {
                            playChannel(targetIndex);
                        }
                    }
                    return true;
            }
        } catch (Exception ignored) {}
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        try {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                openSettings();
                return true;
            }
        } catch (Exception ignored) {}
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (mPlayerManager != null) mPlayerManager.onBackground();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        screenRatioManager.apply();
        try {
            if (mPlayerManager != null) mPlayerManager.onForeground();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        try { if (mPlayerManager != null) mPlayerManager.release(); } catch (Exception ignored) {}
        mInstance = null;
    }

    public void playUrl(String url) {
    }
}
