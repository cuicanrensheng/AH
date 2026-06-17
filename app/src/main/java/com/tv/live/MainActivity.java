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

import com.tv.live.config.AppConfig;
import com.tv.live.manager.GestureManager;
import com.tv.live.manager.ScreenRatioManager;
import com.tv.live.manager.KeyEventManager;
import com.tv.live.manager.ChannelSwitchManager;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;

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

    private LivePanelManager.PanelManager panelManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private LivePanelManager.ChannelListManager channelListManager;
    private LivePanelManager.GroupListManager groupListManager;
    private LivePanelManager.DateListManager dateListManager;
    private LivePanelManager.EpgManagerWrapper epgManagerWrapper;
    private PlayerStateListenerImpl playerStateListener;
    private ChannelSwitchManager switchManager;

    private boolean epgPanelOpen = false;
    private boolean epg_enable;
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean auto_update_source;
    private int currentSelectedDateIndex = 0;
    private SharedPreferences sp;

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

    private static final int MAX_REDIRECT_COUNT = 20;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 8000;
    private static final String DEF_UA = "ExoPlayer";
    private static final String DEF_REFER = "https://www.huya.com/";
    private static final long CHANNEL_COOLDOWN = 300;

    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    private long lastChannelChangeTime = 0;
    public static List<String> logList = new ArrayList<>();

    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
    }

    private final BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            playerView.setUseController(isControllerVisible);
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

    private boolean isControllerVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

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

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);
        playerView.setClickable(false);
        playerView.setLongClickable(false);
        playerView.setFocusable(false);
        playerView.setFocusableInTouchMode(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        playerView.setErrorMessageProvider(null);
        playerView.setShowRewindButton(false);
        playerView.setShowFastForwardButton(false);
        playerView.setShowPreviousButton(false);
        playerView.setShowNextButton(false);
        playerView.setKeepContentOnPlayerReset(true);

        try { playerView.setControllerAutoShow(false); } catch (Exception ignored) {}
        try { playerView.setControllerHideOnTouch(false); } catch (Exception ignored) {}

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
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        dateListManager = new LivePanelManager.DateListManager(this, lvDate);
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            currentSelectedDateIndex = pos;
            if (!channelSourceList.isEmpty()) {
                Channel currentChannel = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(currentChannel, channelSourceList, pos);
            }
        });

        channelListManager = new LivePanelManager.ChannelListManager(this, lvChannelList);
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

        groupListManager = new LivePanelManager.GroupListManager(this, lvGroup);
        groupListManager.setOnGroupChangeListener(groupName -> {
            if (TextUtils.isEmpty(groupName)) return;
            int position = groupListManager.getSelectedPos();
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            nowSelectGroup = groupName;
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (nowSelectGroup.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
        });

        epgManagerWrapper = new LivePanelManager.EpgManagerWrapper(this, lvEpg);
        panelManager = new LivePanelManager.PanelManager(panel_layout, channelListManager, epgManagerWrapper, dateListManager);

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
        PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            if (panel_layout.getVisibility() == View.VISIBLE) {
                return false;
            }
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
        tv_next_time_range = findViewById(R.id.tv_next_time_range);
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

    public void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
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
                    if (groups != null && groups.size() > 0) {
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
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, Toast.LENGTH_SHORT).show();
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
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        if (!TextUtils.isEmpty(nowSelectGroup) && !currentGroupChannelList.isEmpty()) {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            int groupIndex = currentGroupChannelList.indexOf(currentChannel);
            if (groupIndex == -1) groupIndex = 0;

            int newGroupIndex;
            if (channel_reverse) {
                newGroupIndex = groupIndex + 1;
                if (newGroupIndex >= currentGroupChannelList.size()) {
                    newGroupIndex = 0;
                }
            } else {
                newGroupIndex = groupIndex - 1;
                if (newGroupIndex < 0) {
                    newGroupIndex = currentGroupChannelList.size() - 1;
                }
            }

            Channel targetChannel = currentGroupChannelList.get(newGroupIndex);
            int globalIndex = channelSourceList.indexOf(targetChannel);
            if (globalIndex != -1) {
                switchManager.setCurrentIndex(globalIndex);
                playChannel(globalIndex);
            }
            return;
        }

        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        if (!TextUtils.isEmpty(nowSelectGroup) && !currentGroupChannelList.isEmpty()) {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            int groupIndex = currentGroupChannelList.indexOf(currentChannel);
            if (groupIndex == -1) groupIndex = 0;

            int newGroupIndex;
            if (channel_reverse) {
                newGroupIndex = groupIndex - 1;
                if (newGroupIndex < 0) {
                    newGroupIndex = currentGroupChannelList.size() - 1;
                }
            } else {
                newGroupIndex = groupIndex + 1;
                if (newGroupIndex >= currentGroupChannelList.size()) {
                    newGroupIndex = 0;
                }
            }

            Channel targetChannel = currentGroupChannelList.get(newGroupIndex);
            int globalIndex = channelSourceList.indexOf(targetChannel);
            if (globalIndex != -1) {
                switchManager.setCurrentIndex(globalIndex);
                playChannel(globalIndex);
            }
            return;
        }

        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

public void playChannel(int index) {
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
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        tv_tag_fhd.setText(live.quality);
        tv_tag_audio.setText(live.audio);
        tv_bitrate.setText(live.bitrate);
    }

    final String originalUrl = ch.getPlayUrl();
    new Thread(() -> {
        java.net.HttpURLConnection conn = null;
        String finalUrl = originalUrl;
        
        SettingsActivity.log("🔗 开始解析：" + ch.getName());
        SettingsActivity.log("   原始URL：" + (originalUrl.length() > 600 ? originalUrl.substring(0, 600) + "..." : originalUrl));
        
        try {
            for (int step = 0; step < MAX_REDIRECT_COUNT; step++) {
                java.net.URL urlObj = new java.net.URL(finalUrl);
                conn = (java.net.HttpURLConnection) urlObj.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", DEF_UA);
                conn.setRequestProperty("Refer", DEF_REFER);
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                
                String shortUrl = finalUrl.length() > 600 ? finalUrl.substring(0, 600) + "..." : finalUrl;
                SettingsActivity.log("   第" + (step + 1) + "次：HTTP " + code + " → " + shortUrl);
                
                boolean hasRedirect = false;
                
                // ====================== 1. 所有HTTP 3xx 重定向 ======================
                if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308)
                    || (code >= 300 && code < 400 && code != 304 && code != 305 && code != 306)) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null) {
                        if (loc.startsWith("/")) {
                            loc = urlObj.getProtocol() + "://" + urlObj.getHost() + loc;
                        }
                        finalUrl = loc;
                        hasRedirect = true;
                        SettingsActivity.log("        HTTP重定向：" + (loc.length() > 600 ? loc.substring(0, 600) + "..." : loc));
                    }
                }
                // ====================== 2. HTTP 200，解析页面内跳转 ======================
                else if (code == 200) {
                    try {
                        java.io.InputStream is = conn.getInputStream();
                        byte[] buffer = new byte[4096];
                        int len = is.read(buffer);
                        is.close();
                        
                        if (len > 0) {
                            String content = new String(buffer, 0, len);
                            
                            // Meta Refresh 跳转
                            java.util.regex.Pattern metaPattern = java.util.regex.Pattern.compile(
                                "<meta[^>]+http-equiv\\s*=\\s*['\"]refresh['\"][^>]+content\\s*=\\s*['\"][^'\"]*url\\s*=\\s*([^'\">]+)",
                                java.util.regex.Pattern.CASE_INSENSITIVE
                            );
                            java.util.regex.Matcher metaMatcher = metaPattern.matcher(content);
                            if (metaMatcher.find()) {
                                String loc = metaMatcher.group(1).trim();
                                if (loc.startsWith("/")) {
                                    loc = urlObj.getProtocol() + "://" + urlObj.getHost() + loc;
                                }
                                finalUrl = loc;
                                hasRedirect = true;
                                SettingsActivity.log("        Meta跳转：" + (loc.length() > 600 ? loc.substring(0, 600) + "..." : loc));
                            }
                            
                            // JS 跳转
                            if (!hasRedirect) {
                                java.util.regex.Pattern jsPattern = java.util.regex.Pattern.compile(
                                    "window\\.location\\s*=\\s*['\"]([^'\"]+)['\"]",
                                    java.util.regex.Pattern.CASE_INSENSITIVE
                                );
                                java.util.regex.Matcher jsMatcher = jsPattern.matcher(content);
                                if (jsMatcher.find()) {
                                    String loc = jsMatcher.group(1).trim();
                                    if (loc.startsWith("/")) {
                                        loc = urlObj.getProtocol() + "://" + urlObj.getHost() + loc;
                                    }
                                    finalUrl = loc;
                                    hasRedirect = true;
                                    SettingsActivity.log("        JS跳转：" + (loc.length() > 600 ? loc.substring(0, 600) + "..." : loc));
                                }
                            }
                        }
                    } catch (Exception e) {}
                }
                
                conn.disconnect();
                conn = null;
                
                if (!hasRedirect) {
                    SettingsActivity.log("   ✅ 解析完成，共" + (step + 1) + "次跳转");
                    SettingsActivity.log("   最终URL：" + (finalUrl.length() > 600 ? finalUrl.substring(0, 600) + "..." : finalUrl));
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            SettingsActivity.log("   ❌ 解析异常：" + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }

        final String realPlayUrl = TextUtils.isEmpty(finalUrl) ? originalUrl : finalUrl;
        new Handler(Looper.getMainLooper()).post(() -> {
            mPlayerManager.playUrl(realPlayUrl);
        });
    }).start();
}
// ====================== 加在这里 ======================
public void refreshCurrentChannel() {
    if (channelSourceList == null || channelSourceList.isEmpty()) return;
    playChannel(currentPlayIndex);
}
// =====================================================
    public void showChannelNum(int num) {
        if (!number_channel_enable) return;
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            tv_channel_num.setVisibility(View.GONE);
        }, 3000);
    }

    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appConfig.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
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
