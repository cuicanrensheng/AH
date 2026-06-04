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

import java.util.List;

/**
 * 主页面：播放器 + 频道列表 + 节目单 + 全局控制
 * 已修复：点击切台 / 手势滑动 / 遥控器按键 / 打开设置不闪退
 */
public class MainActivity extends AppCompatActivity {
    // 全局实例
    public static MainActivity mInstance;

    // 频道数据
    public List<Channel> channelSourceList;       // 全部频道
    public List<Channel> currentGroupChannelList;  // 当前分组频道
    public int currentPlayIndex = 0;               // 当前播放位

    // 布局
    private View panel_layout;                     // 频道面板
    private PlayerView playerView;                 // 播放器
    private View info_bar;                         // 顶部信息条

    // 控件
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_channel_num;

    // 管理器
    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private PanelManager panelManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private ChannelSwitchManager switchManager;

    // 设置
    private boolean epgPanelOpen = false;
    private boolean epg_enable;
    private boolean channel_reverse;
    private int currentSelectedDateIndex = 0;

    // 自动隐藏信息条
    private final Runnable hideInfoBar = () -> info_bar.setVisibility(View.GONE);

    // 切台防抖
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;

    // 刷新广播
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
                    Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        // 全屏 + 横屏 + 常亮
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // 初始化控件
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        panel_layout = findViewById(R.id.panel_layout);
        info_bar = findViewById(R.id.info_bar);

        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        tv_channel_name = findViewById(R.id.tv_channel_name);
        tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        tv_tag_audio = findViewById(R.id.tv_tag_audio);
        tv_bitrate = findViewById(R.id.tv_bitrate);
        tv_channel_num = findViewById(R.id.tv_channel_num);

        // 配置
        appConfig = AppConfig.getInstance(this);
        loadSettings();

        // 列表初始化
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // ==============================
        // ✅ 修复：点击频道列表切台
        // ==============================
        channelListManager.setOnChannelClickListener(position -> {
            if (channelSourceList != null && position >= 0 && position < channelSourceList.size()) {
                playChannel(position);
                panel_layout.setVisibility(View.GONE); // 选台自动关闭面板
            }
        });

        // 日期切换节目单
        dateListManager.setOnDateSelectedListener(position -> {
            currentSelectedDateIndex = position;
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        // 显示/隐藏节目单
        btn_show_epg.setOnClickListener(v -> {
            if (!epg_enable) {
                Toast.makeText(this, "节目单已关闭", Toast.LENGTH_SHORT).show();
                return;
            }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            if (epgPanelOpen && channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        });

        // 分组切换
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            String groupName = groupListManager.getCurrentGroup(position);
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            if (!currentGroupChannelList.isEmpty()) {
                playChannel(channelSourceList.indexOf(currentGroupChannelList.get(0)));
            }
        });

        // 播放器初始化
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            tv_tag_fhd.setText(info.quality);
            tv_tag_audio.setText(info.audio);
            tv_bitrate.setText(info.bitrate);
        });

        // 手势（手机滑动/点击/长按）
        gestureManager = new GestureManager(this);
        PlayerGestureHelper helper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            helper.handleTouch(event);
            return true;
        });

        // 遥控器按键
        keyEventManager = new KeyEventManager(this);
        switchManager = ChannelSwitchManager.getInstance();
        currentPlayIndex = appConfig.getLastPlayIndex();

        // 加载直播源
        loadLiveAndEpg();
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));
    }

    /**
     * 读取设置
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
    }

    /**
     * 加载直播 + EPG
     */
    public void loadLiveAndEpg() {
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                channelSourceList = channels;
                currentGroupChannelList = channels;
                switchManager.setChannelList(channelSourceList);
                groupListManager.setGroups(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                playChannel(currentPlayIndex);
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(MainActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
            }
        });

        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (channelSourceList != null && !channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    /**
     * 播放指定频道
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (TextUtils.isEmpty(ch.getPlayUrl())) return;

        mPlayerManager.playUrl(ch.getPlayUrl());
        showChannelNum(index + 1);
        appConfig.setLastPlayIndex(index);
        channelListManager.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        info_bar.setVisibility(View.VISIBLE);
        info_bar.removeCallbacks(hideInfoBar);
        info_bar.postDelayed(hideInfoBar, 2000);
        tv_channel_name.setText(ch.getName());
    }

    /**
     * 上一频道
     */
    public void playPrev() {
        if (System.currentTimeMillis() - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = System.currentTimeMillis();
        int idx = channel_reverse ? switchManager.next() : switchManager.prev();
        playChannel(idx);
    }

    /**
     * 下一频道
     */
    public void playNext() {
        if (System.currentTimeMillis() - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = System.currentTimeMillis();
        int idx = channel_reverse ? switchManager.prev() : switchManager.next();
        playChannel(idx);
    }

    /**
     * 显示频道号
     */
    private void showChannelNum(int num) {
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        new Handler().postDelayed(() -> tv_channel_num.setVisibility(View.GONE), 3000);
    }

    // ==============================
    // 公共方法：手势/按键调用
    // ==============================

    /**
     * 显示/隐藏频道面板
     */
    public void togglePanel() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
        } else {
            panel_layout.setVisibility(View.VISIBLE);
        }
    }

    /**
     * ✅ 修复：打开设置（不闪退）
     */
    public void openSettings() {
        try {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "设置无法打开", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 数字键选台
     */
    public void selectChannelByNumber(int num) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int index = Math.max(0, Math.min(num, channelSourceList.size() - 1));
        playChannel(index);
    }

    /**
     * 遥控器按键分发
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (keyEventManager.dispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 返回键关闭面板
     */
    @Override
    public void onBackPressed() {
        if (panel_layout.getVisibility() == View.VISIBLE) {
            panel_layout.setVisibility(View.GONE);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPlayerManager.onBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        mPlayerManager.onForeground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(refreshReceiver);
        mPlayerManager.release();
    }
}
