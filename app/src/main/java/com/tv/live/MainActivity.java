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

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public List<Channel> channelSourceList = new ArrayList<>();
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    public int currentPlayIndex = 0;
    private View panel_layout;
    public TVPlayerManager mPlayerManager;
    private PlayerView playerView;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private PanelManager panelManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
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
    private SharedPreferences sp;
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private TextView tv_next_program_name, tv_next_time_range;
    private android.widget.ProgressBar progress_program;
    private TextView tv_channel_num;
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;
    private float touchStartY = 0;
    private static final float SLIDE_THRESHOLD = 80;

    // ================================================
    // ✅ 重定向解析相关配置
    // ================================================
    private static final int MAX_REDIRECT_COUNT = 10;    // 最大重定向次数
    private static final int CONNECT_TIMEOUT = 8000;     // 连接超时（8秒）
    private static final int READ_TIMEOUT = 8000;        // 读取超时（8秒）
    private static final String DEF_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final String HUYA_REFERER = "https://www.huya.com/";

    // 本地日志：最新在前，最多100条
    public static List<String> logList = new ArrayList<>();
    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }

    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
        }
    };

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
                        loadLiveAndEpg();
                        Toast.makeText(MainActivity.this, "已刷新直播源/EPG", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("【主页】onCreate -> 页面创建");

        mInstance = this;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tv_channel_num = findViewById(R.id.tv_channel_num);
        initInfoBar();
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        panel_layout = findViewById(R.id.panel_layout);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

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
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                }
            }
        });

        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 点击时同步更新分组管理器的选中位置，确保高亮跟着变
                groupListManager.setSelectedPosition(position);

                lvGroup.setItemChecked(position, true);
                lvGroup.setSelection(position);
                String groupName = groupListManager.getCurrentGroup(position);
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (groupName.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
                // 已去掉：点击分组后自动播放第一个频道
            }
        });

        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();

        // 先创建 PanelManager 实例，再注册回调，避免空指针
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 注册日期选中回调，点击日期刷新EPG节目单
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;
            // 同步日期状态到面板管理器，下次打开面板保留选中日期
            panelManager.setCurrentDateIndex(pos);

            // 补全完整索引合法性校验，避免极端场景数组越界崩溃
            if (channelSourceList != null
                    && !channelSourceList.isEmpty()
                    && currentPlayIndex >= 0
                    && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            }
        });

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();

        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        log("【播放】记录上次播放索引：" + currentPlayIndex);
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

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        auto_update_source = sp.getBoolean("auto_update_source", true);

        log("【设置】EPG开关：" + epg_enable);
        log("【设置】切台反转：" + channel_reverse);
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
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                log("【直播源】加载失败：" + errorMsg);
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

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
     * 上一台：在当前频道所属分组内循环切换
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
     * 下一台：在当前频道所属分组内循环切换
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

    // ================================================
    // ✅ 重定向解析核心方法
    // 支持301/302，最多跟随10次，自动处理虎牙等特殊站点
    // ================================================
    private String resolveRedirectUrl(String originalUrl) {
        if (TextUtils.isEmpty(originalUrl)) {
            log("【重定向】原始地址为空，跳过解析");
            return originalUrl;
        }

        log("【重定向】开始解析，原始地址：" + originalUrl);

        HttpURLConnection conn = null;
        String currentUrl = originalUrl;
        int redirectCount = 0;

        try {
            for (int step = 0; step < MAX_REDIRECT_COUNT; step++) {
                URL urlObj = new URL(currentUrl);
                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", DEF_UA);
                conn.setInstanceFollowRedirects(false); // 手动处理重定向

                // ✅ 虎牙特殊处理：加上虎牙Referer
                if (currentUrl.contains("huya.com") || currentUrl.contains("huya.cn")) {
                    conn.setRequestProperty("Referer", HUYA_REFERER);
                    log("【重定向】检测到虎牙地址，已添加Referer：" + HUYA_REFERER);
                }

                int code = conn.getResponseCode();
                log("【重定向】第" + (step + 1) + "次请求，状态码：" + code + "，地址：" + currentUrl);

                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (TextUtils.isEmpty(location)) {
                        log("【重定向】Location为空，终止重定向");
                        break;
                    }

                    // 处理相对路径
                    if (location.startsWith("/")) {
                        String baseUrl = urlObj.getProtocol() + "://" + urlObj.getHost();
                        location = baseUrl + location;
                    }

                    redirectCount++;
                    log("【重定向】第" + redirectCount + "次重定向：" + location);
                    currentUrl = location;
                    conn.disconnect();
                    conn = null;
                } else {
                    log("【重定向】非重定向状态码，解析完成，最终地址：" + currentUrl);
                    break;
                }
            }

            if (redirectCount >= MAX_REDIRECT_COUNT) {
                log("【重定向】⚠️ 达到最大重定向次数(" + MAX_REDIRECT_COUNT + "次)，停止跟随");
            }

        } catch (Exception e) {
            log("【重定向】❌ 解析异常：" + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }

        log("【重定向】解析结束，共重定向" + redirectCount + "次，最终地址：" + currentUrl);
        return currentUrl;
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

        final String originalUrl = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道名称：" + ch.getName());
        log("【播放】原始地址：" + originalUrl);
        log("【播放】当前索引：" + index);
        log("========================================");

        playerStateListener.setCurrentChannelName(ch.getName());
        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);
        channelListManager.setChannels(channelSourceList, index);
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

        // ✅ 异步解析重定向，拿到真实地址后再播放
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String realUrl = resolveRedirectUrl(originalUrl);
                // 切回主线程播放
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        log("【播放】开始播放真实地址：" + realUrl);
                        mPlayerManager.playUrl(realUrl);
                    }
                });
            }
        }).start();
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
                        playChannel(globalIndex);
                        togglePanel();
                    }
                } else {
                    playChannel(pos);
                    togglePanel();
                }
            }
        });
    }

    public void togglePanel() {
        // 传入日期列表管理器，用于面板打开时同步高亮
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
    }

    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        log("【主页】onPause -> 切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        log("【主页】onResume -> 回到前台");
        loadSettings();
        screenRatioManager.apply();
        if (mPlayerManager != null)
            mPlayerManager.onForeground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        mPlayerManager.release();
        mInstance = null;
    }
}
