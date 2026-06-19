         package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.ChannelSwitchManager;
import com.tv.live.manager.GestureManager;
import com.tv.live.manager.InfoBarManager;
import com.tv.live.manager.KeyEventManager;
import com.tv.live.manager.PanelManager;
import com.tv.live.manager.ScreenRatioManager;
import com.tv.live.util.CacheManager;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final long CHANNEL_COOLDOWN = 300;
    private static final long CHANNEL_NUM_TIMEOUT = 3000;

    // ✅ 修复：改成 public，供外部类访问
    public static MainActivity mInstance;
    public TVPlayerManager mPlayerManager;
    public List<Channel> channelSourceList = new ArrayList<>();
    public int currentPlayIndex = 0;

    private PlayerStateListenerImpl playerStateListener;
    private ChannelSwitchManager switchManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private ScreenRatioManager screenRatioManager;
    private PanelManager panelManager;
    private InfoBarManager infoBarManager;
    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private AppConfig appConfig;
    private CacheManager cacheManager;

    private String currentGroupName = "";
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    private int currentSelectedDateIndex = 0;
    private boolean hasPlayedWithCache = false;
    private long lastChannelChangeTime = 0;

    private boolean epg_enable = true;
    private boolean channel_reverse = false;
    private boolean num_channel_enable = true;
    private boolean isOpeningSettings = false;

    private StringBuilder channelNumInput = new StringBuilder();
    private Handler channelNumHandler = new Handler(Looper.getMainLooper());
    private final Runnable channelNumConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            confirmChannelNum();
        }
    };

    private Handler timeHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateTopRightTime();
            timeHandler.postDelayed(this, 1000);
        }
    };

    private View panel_layout;
    private TextView tv_channel_num;
    private View loadingView;
    private TextView tv_loading_text;

    private BroadcastReceiver toggleControllerReceiver;
    private BroadcastReceiver refreshReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mInstance = this;

        appConfig = AppConfig.getInstance(this);

        // ✅ 修复：CacheManager 单例模式
        cacheManager = CacheManager.getInstance(this);

        loadSettings();
        initPlayer();
        initManagers();
        initListViewClick();
        initInfoBar();
        initReceivers();
        loadLiveAndEpg();
        initFullScreen();
    }

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);

        // ✅ 修复：PlayerStateListenerImpl 需要 Context 参数
        playerStateListener = new PlayerStateListenerImpl(this);

        // ✅ 修复：方法名是 setOnPlayStateListener，不是 addListener
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                if (infoBarManager != null) {
                    if (infoBarManager.isBottomShowing()) {
                        infoBarManager.updateBottomLiveInfo(info);
                    }
                    if (infoBarManager.isCornerShowing()) {
                        infoBarManager.updateCornerLiveInfo(info);
                    }
                }
            }
        });
    }

    private void initManagers() {
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        panel_layout = findViewById(R.id.panel_layout);
        tv_channel_num = findViewById(R.id.tv_channel_num);

        // ✅ 修复：如果布局里没有就注释掉
        loadingView = findViewById(R.id.loading_view);
        tv_loading_text = findViewById(R.id.tv_loading_text);

        switchManager = ChannelSwitchManager.getInstance();
        gestureManager = new GestureManager(this);
        keyEventManager = new KeyEventManager(this);

        // ✅ 修复：ScreenRatioManager 需要两个参数
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);

        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);

        EpgManager.getInstance(this);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);

        dateListManager.initDate();

        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        btn_show_epg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!epg_enable) {
                    SettingsActivity.logOperation("【EPG】节目单功能已关闭，无法展开");
                    return;
                }
                panelManager.toggleEpgPanel();
                SettingsActivity.logOperation("【EPG】" + (panelManager.isEpgExpanded() ? "展开" : "收起") + "节目单");
                if (panelManager.isEpgExpanded() && !channelSourceList.isEmpty()) {
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                }
            }
        });

        // ✅ 修复：接口名是 OnGroupSelectedListener
        groupListManager.setOnGroupSelectedListener(new GroupListManager.OnGroupSelectedListener() {
            @Override
            public void onGroupSelected(String groupName, List<Channel> groupChannels) {
                currentGroupName = groupName;
                currentGroupChannelList = groupChannels;
                channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
                SettingsActivity.logOperation("【分组】选择分组：" + groupName
                        + "（" + groupChannels.size() + "个频道）");
            }
        });

        // ✅ 修复：接口名是 OnDateSelectedListener，方法是 onDateSelected
        dateListManager.setOnDateSelectedListener(new DateListManager.OnDateSelectedListener() {
            @Override
            public void onDateSelected(int position) {
                currentSelectedDateIndex = position;
                if (!channelSourceList.isEmpty()) {
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    epgManagerWrapper.refresh(curr, channelSourceList, position);
                }
                SettingsActivity.logOperation("【日期】选择日期：位置" + position);
            }
        });
    }

    private void initInfoBar() {
        infoBarManager = new InfoBarManager(this, getWindow().getDecorView());
        infoBarManager.setChannelList(channelSourceList, currentPlayIndex);
        log("【信息栏】信息栏管理器初始化完成");
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
                        SettingsActivity.logOperation("【列表】点击频道：" + selectedChannel.getName());
                        playChannel(globalIndex);
                    }
                } else {
                    Channel ch = channelSourceList.get(pos);
                    SettingsActivity.logOperation("【列表】点击频道：" + ch.getName());
                    playChannel(pos);
                }
            }
        });
    }

    private void initReceivers() {
        toggleControllerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                togglePanel();
            }
        };
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROLLER"));

        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                hasPlayedWithCache = false;
                loadLiveAndEpg();
            }
        };
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH"));
    }

    private void initFullScreen() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
            log("【适配】全屏适配失败：" + e.getMessage());
        }
    }

    private void loadSettings() {
        // ✅ 修复：AppConfig 里已经加上这两个方法了
        epg_enable = appConfig.isEpgEnable();
        channel_reverse = appConfig.isChannelReverse();
        num_channel_enable = appConfig.isNumChannelEnable();
        currentPlayIndex = appConfig.getLastPlayIndex();

        String customLiveUrl = appConfig.getCustomLiveUrl();
        if (!TextUtils.isEmpty(customLiveUrl)) {
            UrlConfig.LIVE_URL = customLiveUrl;
        }
        String customEpgUrl = appConfig.getCustomEpgUrl();
        if (!TextUtils.isEmpty(customEpgUrl)) {
            UrlConfig.EPG_URL = customEpgUrl;
        }
    }

    private void updateTopRightTime() {
        Calendar cal = Calendar.getInstance();
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int w = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String weekDay = weekMap[w - 1];

        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);

        String dateStr = String.format("%02d/%02d %s", month, day, weekDay);
        String timeStr = String.format("%02d:%02d:%02d", hour, minute, second);

        TextView tvDate = findViewById(R.id.tv_date);
        TextView tvTime = findViewById(R.id.tv_time);
        if (tvDate != null) tvDate.setText(dateStr);
        if (tvTime != null) tvTime.setText(timeStr);
    }

    private boolean handleNumberKey(int keyCode) {
        if (!num_channel_enable) return false;
        int num;
        switch (keyCode) {
            case KeyEvent.KEYCODE_0: num = 0; break;
            case KeyEvent.KEYCODE_1: num = 1; break;
            case KeyEvent.KEYCODE_2: num = 2; break;
            case KeyEvent.KEYCODE_3: num = 3; break;
            case KeyEvent.KEYCODE_4: num = 4; break;
            case KeyEvent.KEYCODE_5: num = 5; break;
            case KeyEvent.KEYCODE_6: num = 6; break;
            case KeyEvent.KEYCODE_7: num = 7; break;
            case KeyEvent.KEYCODE_8: num = 8; break;
            case KeyEvent.KEYCODE_9: num = 9; break;
            default: return false;
        }
        channelNumInput.append(num);
        tv_channel_num.setText(channelNumInput.toString());
        tv_channel_num.setVisibility(View.VISIBLE);
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumHandler.postDelayed(channelNumConfirmRunnable, CHANNEL_NUM_TIMEOUT);
        SettingsActivity.logOperation("【数字选台】输入：" + channelNumInput);
        return true;
    }

    private void confirmChannelNum() {
        if (channelNumInput.length() == 0) return;
        try {
            int channelNum = Integer.parseInt(channelNumInput.toString());
            if (channelNum >= 1 && channelNum <= channelSourceList.size()) {
                int index = channelNum - 1;
                SettingsActivity.logOperation("【数字选台】切换到第 " + channelNum + " 频道");
                // ✅ 修复：去掉多余的 playChannel
                playChannel(index);
            } else {
                SettingsActivity.logOperation("【数字选台】频道号不存在：" + channelNum);
            }
        } catch (NumberFormatException e) {
            // 忽略
        }
        channelNumInput.setLength(0);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                tv_channel_num.setVisibility(View.GONE);
            }
        }, 5000);
    }

    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (channel_reverse) {
                    playNext();
                } else {
                    playPrev();
                }
                SettingsActivity.logOperation("【切台】上键 → "
                        + (channel_reverse ? "下一台" : "上一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channel_reverse) {
                    playPrev();
                } else {
                    playNext();
                }
                SettingsActivity.logOperation("【切台】下键 → "
                        + (channel_reverse ? "上一台" : "下一台"));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (channelNumInput.length() > 0) {
                    channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
                    confirmChannelNum();
                    return true;
                }
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                togglePanel();
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleNumberKey(keyCode)) return true;
        if (handleDirectionKey(keyCode)) return true;
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (loadingView != null && loadingView.getVisibility() == View.VISIBLE) {
                    log("【加载】超时，自动隐藏加载动画");
                    if (channelSourceList.isEmpty()) {
                        if (tv_loading_text != null) {
                            tv_loading_text.setText("加载失败，请检查网络或稍后重试");
                        }
                        hideLoading();
                        SettingsActivity.logOperation("【加载】直播源加载超时");
                    } else {
                        hideLoading();
                    }
                }
            }
        }, 15000);

        String cacheContent = cacheManager.getFileCache("live_source");
        if (cacheContent != null && !cacheContent.isEmpty()) {
            log("【缓存】找到直播源缓存，快速显示");
            List<Channel> cacheChannels = parseLiveSource(cacheContent);
            if (cacheChannels != null && !cacheChannels.isEmpty()) {
                channelSourceList.clear();
                channelSourceList.addAll(cacheChannels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                infoBarManager.setChannelList(channelSourceList, currentPlayIndex);
                if (!hasPlayedWithCache) {
                    playChannel(currentPlayIndex);
                    hasPlayedWithCache = true;
                }
                hideLoading();
                log("【缓存】直播源缓存加载完成，频道数：" + cacheChannels.size());
                loadEpgCache();
            }
        }

        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                log("【网络】直播源加载成功，频道总数：" + channels.size());
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                infoBarManager.setChannelList(channelSourceList, currentPlayIndex);
                if (!hasPlayedWithCache) {
                    playChannel(currentPlayIndex);
                    hasPlayedWithCache = true;
                }
                hideLoading();
                log("【网络】直播源列表已更新");
                loadEpg();
            }
            @Override
            public void onError(String errorMsg) {
                log("【网络】直播源加载失败：" + errorMsg);
                if (channelSourceList.isEmpty()) {
                    hideLoading();
                    SettingsActivity.logOperation("【加载】直播源加载失败：" + errorMsg);
                } else {
                    log("【缓存】使用缓存数据继续播放");
                    hideLoading();
                }
                loadEpgCache();
            }
        });
    }

    private void loadEpgCache() {
        if (!epg_enable) return;
        log("【EPG】尝试从缓存加载...");
        if (!channelSourceList.isEmpty()) {
            epgManagerWrapper.refresh(
                    channelSourceList.get(currentPlayIndex),
                    channelSourceList,
                    currentSelectedDateIndex);

            Channel curr = channelSourceList.get(currentPlayIndex);
            if (infoBarManager != null) {
                if (infoBarManager.isBottomShowing()) {
                    infoBarManager.updateBottomEpgInfo(curr);
                }
                if (infoBarManager.isCornerShowing()) {
                    infoBarManager.updateCornerEpgInfo(curr);
                }
            }
        }
    }

    private void loadEpg() {
        if (!epg_enable) return;
        log("【EPG】开始加载节目单...");
        EpgManager.getInstance(this).setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance(this).loadEpg(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        log("【EPG】最新节目单加载完成");
                        if (!channelSourceList.isEmpty()) {
                            epgManagerWrapper.refresh(
                                    channelSourceList.get(currentPlayIndex),
                                    channelSourceList,
                                    currentSelectedDateIndex);

                            Channel curr = channelSourceList.get(currentPlayIndex);
                            if (infoBarManager != null) {
                                if (infoBarManager.isBottomShowing()) {
                                    infoBarManager.updateBottomEpgInfo(curr);
                                }
                                if (infoBarManager.isCornerShowing()) {
                                    infoBarManager.updateCornerEpgInfo(curr);
                                }
                            }
                        }
                    }
                });
            }
        });
    }

    private List<Channel> parseLiveSource(String content) {
        List<Channel> channels = new ArrayList<>();
        if (TextUtils.isEmpty(content)) {
            return channels;
        }
        String[] lines = content.split("\n");
        String currentName = "";
        String currentGroup = "";
        String currentLogo = "";
        String currentTvgId = "";
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("#EXTINF:")) {
                int commaIndex = line.indexOf(",");
                if (commaIndex > 0 && commaIndex < line.length() - 1) {
                    currentName = line.substring(commaIndex + 1).trim();
                }
                int groupIndex = line.indexOf("group-title=\"");
                if (groupIndex > 0) {
                    int groupEnd = line.indexOf("\"", groupIndex + 13);
                    if (groupEnd > groupIndex) {
                        currentGroup = line.substring(groupIndex + 13, groupEnd);
                    }
                }
                int tvgIndex = line.indexOf("tvg-id=\"");
                if (tvgIndex > 0) {
                    int tvgEnd = line.indexOf("\"", tvgIndex + 8);
                    if (tvgEnd > tvgIndex) {
                        currentTvgId = line.substring(tvgIndex + 8, tvgEnd);
                    }
                }
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                String playUrl = line;
                if (!TextUtils.isEmpty(currentName) && !TextUtils.isEmpty(playUrl)) {
                    channels.add(new Channel(currentName, playUrl, currentGroup, currentTvgId));
                }
                currentName = "";
                currentGroup = "";
                currentLogo = "";
                currentTvgId = "";
            }
        }
        log("【缓存】解析完成，共 " + channels.size() + " 个频道");
        return channels;
    }

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
        final String playUrl = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道名称：" + ch.getName());
        log("【播放】播放地址：" + playUrl);
        log("【播放】当前索引：" + index);
        log("========================================");
        playerStateListener.setCurrentChannelName(ch.getName());
        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);
        if (!TextUtils.isEmpty(currentGroupName) && !currentGroupChannelList.isEmpty()) {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        } else {
            channelListManager.setChannels(channelSourceList, index);
        }
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        mPlayerManager.playUrl(playUrl);

        if (infoBarManager != null) {
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            infoBarManager.setCurrentPlayIndex(index);
            if (panel_layout.getVisibility() == View.VISIBLE) {
                infoBarManager.updateCornerInfo(ch, live);
            } else {
                infoBarManager.showBottom(ch, live);
            }
        }
    }

    public void showChannelNum(int num) {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            tv_channel_num.setText(String.valueOf(num));
        } else {
            tv_channel_num.setText(String.valueOf(num));
            tv_channel_num.setVisibility(View.VISIBLE);
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    tv_channel_num.setVisibility(View.GONE);
                }
            }, 5000);
        }
    }

    public void togglePanel() {
        if (!TextUtils.isEmpty(currentGroupName) && !currentGroupChannelList.isEmpty()) {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        } else {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        }
        boolean isOpen = panel_layout.getVisibility() == View.VISIBLE;
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);

        View topRight = findViewById(R.id.ll_top_right);
        if (!isOpen) {
            infoBarManager.hideBottom();
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0
                    && currentPlayIndex < channelSourceList.size()) {
                Channel ch = channelSourceList.get(currentPlayIndex);
                TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
                infoBarManager.showCorner(ch, live);
                infoBarManager.setCurrentPlayIndex(currentPlayIndex);
            }
            if (topRight != null) {
                topRight.setVisibility(View.VISIBLE);
            }
            tv_channel_num.setText(String.valueOf(currentPlayIndex + 1));
            timeHandler.removeCallbacks(timeUpdateRunnable);
            timeHandler.post(timeUpdateRunnable);
        } else {
            infoBarManager.hideCorner();
            if (topRight != null) {
                topRight.setVisibility(View.GONE);
            }
            timeHandler.removeCallbacks(timeUpdateRunnable);
        }
        SettingsActivity.logOperation("【面板】" + (isOpen ? "关闭" : "打开") + "频道面板");
    }

    public void openSettings() {
        isOpeningSettings = true;
        startActivity(new Intent(this, SettingsActivity.class));
        SettingsActivity.logOperation("【系统】打开设置页面");
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        log("【远程配置】更新直播源：" + liveUrl);
        log("【远程配置】更新EPG：" + epgUrl);
        SettingsActivity.logOperation("【远程配置】更新直播源/EPG地址");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                hasPlayedWithCache = false;
                loadLiveAndEpg();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isOpeningSettings) {
            log("【主页】onPause -> 打开设置页面，继续播放");
            return;
        }
        log("【主页】onPause -> 切到后台");
        SettingsActivity.logOperation("【系统】APP切到后台");
        if (mPlayerManager != null)
            mPlayerManager.onBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isOpeningSettings) {
            isOpeningSettings = false;
            log("【主页】onResume -> 从设置页面回来");
        } else {
            log("【主页】onResume -> 回到前台");
            SettingsActivity.logOperation("【系统】APP回到前台");
            if (mPlayerManager != null)
                mPlayerManager.onForeground();
        }
        loadSettings();
        screenRatioManager.apply();
        initFullScreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            initFullScreen();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("【主页】onDestroy -> 页面销毁");
        SettingsActivity.logOperation("【系统】APP退出");

        if (infoBarManager != null) {
            infoBarManager.stopProgramProgressUpdate();
            infoBarManager.release();
        }

        timeHandler.removeCallbacks(timeUpdateRunnable);
        if (channelNumHandler != null) {
            channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        }

        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}

        mPlayerManager.release();
        mInstance = null;
    }

    private void hideLoading() {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
    }

    private void log(String msg) {
        android.util.Log.d("MainActivity", msg);
        try {
            SettingsActivity.log(msg);
        } catch (Exception e) {
            // 忽略
        }
    }

    public static MainActivity getInstance() {
        return mInstance;
    }
}
