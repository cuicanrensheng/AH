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
    private String nowSelectGroup = "";
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
    private TextView tv_next_program_name;
    private android.widget.ProgressBar progress_program;
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

    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) logList.remove(logList.size() - 1);
    }

    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(false);
            playerView.hideController();
        }
    };

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
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
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

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);
        playerView.setControllerAutoShow(false);
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

        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

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
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        dateListManager = new DateListManager(this, lvDate);
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, pos);
            }
        });

        // ===================== 【修复：分组点击只刷新，不自动播放】 =====================
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            nowSelectGroup = groupListManager.getCurrentGroup(position);
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (nowSelectGroup.equals(c.getGroup()))
                    currentGroupChannelList.add(c);
            }
            // 只刷新列表，不跳台
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
        });
        // ==============================================================================

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
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

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
        progress_program = findViewById(R.id.progress_program);
        tv_remaining_time = findViewById(R.id.tv_remaining_time);
        tv_next_program_name = findViewById(R.id.tv_next_program_name);
    }

    private void loadSettings() {
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
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
                    channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                }else{
                    List<String> groups = groupListManager.getGroupList();
                    if(groups != null && groups.size() > 0){
                        nowSelectGroup = groups.get(0);
                        currentGroupChannelList.clear();
                        for(Channel ch : channelSourceList)
                            if(ch.getGroup().equals(nowSelectGroup))
                                currentGroupChannelList.add(ch);
                        channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
                    }else {
                        channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    }
                }
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String err) {
                Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show();
            }
        });

        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

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

    public void playChannel(int index) {
        if(channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if(TextUtils.isEmpty(ch.getPlayUrl())) return;
        final String original = ch.getPlayUrl();

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
            }finally{
                if(conn != null) conn.disconnect();
            }
            String finalPlay = TextUtils.isEmpty(realUrl) ? original : realUrl;
            new Handler(Looper.getMainLooper()).post(()-> mPlayerManager.playUrl(finalPlay));
        }).start();

        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);

        if(!TextUtils.isEmpty(nowSelectGroup)) {
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, index);
        } else {
            channelListManager.setChannels(channelSourceList, index);
        }

        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

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
        try {
            unregisterReceiver(toggleControllerReceiver);
            unregisterReceiver(refreshReceiver);
        } catch (Exception e) {}
        if(mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }
}
