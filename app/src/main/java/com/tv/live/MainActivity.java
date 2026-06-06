package com.tv.live;

import com.tv.live.manager.InfoBarManager;
import com.tv.live.manager.PlayControlManager;
import com.tv.live.manager.SettingsManager;
import com.tv.live.receiver.AppBroadcast;
import com.tv.live.util.LogUtils;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;
import com.tv.live.SettingsActivity;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.manager.*;
import java.util.ArrayList;
import java.util.List;

/**
 * APP主页Activity
 * 拆分后职责：页面生命周期、布局控件绑定、初始化各管理器、控件点击回调分发、系统按键监听
 * 所有播放/配置/日志/网络工具全部外移到对应工具&Manager
 */
public class MainActivity extends AppCompatActivity {
    // 全局单例，供其他类获取页面上下文
    public static MainActivity mInstance;

    // 全频道原始数据源
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前选中分组过滤后的频道列表
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前全局播放下标
    public int currentPlayIndex = 0;
    // 记录上次选中分组名称，切源不重置分组
    private String nowSelectGroup = "";

    // 右侧侧边栏总布局
    private View panel_layout;
    // 播放器管理类
    public TVPlayerManager mPlayerManager;
    // Exo播放画面控件
    public PlayerView playerView;
    // APP配置单例
    private AppConfig appConfig;
    // 画面比例管理器
    private ScreenRatioManager screenRatioManager;
    // 侧边栏显示隐藏管理器
    private PanelManager panelManager;
    // 手势滑动管理器
    private GestureManager gestureManager;
    // 遥控器按键管理
    private KeyEventManager keyEventManager;

    // 频道列表、分组、EPG日期、EPG节目单适配器
    private ChannelListManager channelListManager;
    private GroupListManager groupListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;

    // 播放器状态监听实例
    public PlayerStateListenerImpl playerStateListener;
    // 上下切台索引工具
    private ChannelSwitchManager switchManager;

    // EPG面板展开标记
    private boolean epgPanelOpen = false;

    // 拆分后的三大管理器
    public SettingsManager settingsManager;
    private InfoBarManager infoBarManager;
    private PlayControlManager playControlManager;

    // 右上角频道数字控件
    private TextView tv_channel_num;

    // 两个全局广播接收器
    private android.content.BroadcastReceiver toggleControllerReceiver;
    private android.content.BroadcastReceiver refreshReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogUtils.log("【主页】onCreate -> 页面创建");
        mInstance = this;

        // 页面强制横屏、全屏沉浸式
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 绑定顶部频道数字控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        // 初始化配置管理器
        appConfig = AppConfig.getInstance(this);
        settingsManager = new SettingsManager(this);

        // 读取自定义直播源&EPG地址，覆盖全局配置
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        LogUtils.log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        LogUtils.log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        // 播放器控件初始化、关闭原生控制器
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

        // 绑定页面所有ListView控件
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 初始化广播并注册
        toggleControllerReceiver = AppBroadcast.getToggleControllerReceiver(this);
        refreshReceiver = AppBroadcast.getRefreshReceiver(this);
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // 初始化信息栏管理器（传入页面根布局）
        infoBarManager = new InfoBarManager(getWindow().getDecorView());

        // 初始化四大列表适配器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager = new DateListManager(this, lvDate);
        dateListManager.initDate();

        // 侧边栏管理器
        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 播放器初始化
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 播放器实时码率画质回调
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
        });

        // 画面比例、手势、遥控器、切台工具初始化
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

        // 读取上次播放频道下标
        currentPlayIndex = appConfig.getLastPlayIndex();
        LogUtils.log("【播放】记录上次播放索引：" + currentPlayIndex);

        // 初始化播放控制器
        playControlManager = new PlayControlManager(this, mPlayerManager, switchManager, appConfig,
                tv_channel_num, infoBarManager, epgManagerWrapper, settingsManager, currentSelectedDateIndex);

        // ====== 控件点击事件绑定 ======
        // EPG展开/收起按钮
        btn_show_epg.setOnClickListener(v -> {
            if (!settingsManager.epg_enable) {
                android.widget.Toast.makeText(MainActivity.this, "节目单功能已关闭", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            epgPanelOpen = !epgPanelOpen;
            lvDate.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            lvEpg.setVisibility(epgPanelOpen ? View.VISIBLE : View.GONE);
            if (epgPanelOpen && !channelSourceList.isEmpty()) {
                currentSelectedDateIndex = dateListManager.getSelectedPosition();
                playControlManager.setCurrentDateIndex(currentSelectedDateIndex);
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // EPG日期切换
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            currentSelectedDateIndex = position;
            playControlManager.setCurrentDateIndex(currentSelectedDateIndex);
            if (!channelSourceList.isEmpty()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        });

        // 分组点击筛选频道
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            nowSelectGroup = groupListManager.getCurrentGroup(position);
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (nowSelectGroup.equals(c.getGroup())) currentGroupChannelList.add(c);
            }
            channelListManager.setChannelsByGroup(channelSourceList, nowSelectGroup, currentPlayIndex);
        });

        // 频道列表点击播放
        channelListManager.setOnChannelClickListener(filterPos -> {
            if(filterPos >=0 && filterPos < currentGroupChannelList.size()){
                Channel target = currentGroupChannelList.get(filterPos);
                int global = channelSourceList.indexOf(target);
                if(global != -1){
                    playControlManager.playChannel(global, channelSourceList);
                    togglePanel();
                }
            }
        });

        // 加载直播源+EPG
        loadLiveAndEpg();
    }

    /**
     * 加载全频道源与EPG节目数据
     */
    public void loadLiveAndEpg() {
        LogUtils.log("【直播源】开始加载直播源...");
        LiveSourceLoader.getInstance(this).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                LogUtils.log("【直播源】加载成功，频道总数：" + channels.size());
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                switchManager.setChannelList(channelSourceList);
                switchManager.setCurrentIndex(currentPlayIndex);
                groupListManager.setGroups(channelSourceList);

                // 还原上次分组
                if(!TextUtils.isEmpty(nowSelectGroup)){
                    currentGroupChannelList.clear();
                    for(Channel ch:channelSourceList){
                        if(ch.getGroup().equals(nowSelectGroup)) currentGroupChannelList.add(ch);
                    }
                    channelListManager.setChannelsByGroup(channelSourceList,nowSelectGroup,currentPlayIndex);
                }else{
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
                // 自动播放上次频道
                playControlManager.playChannel(currentPlayIndex, channelSourceList);
            }

            @Override
            public void onError(String errorMsg) {
                LogUtils.log("【直播源】加载失败：" + errorMsg);
                android.widget.Toast.makeText(MainActivity.this, "加载失败：" + errorMsg, android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        // 异步加载EPG
        LogUtils.log("【EPG】加载节目单：" + UrlConfig.EPG_URL);
        EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
            if (!channelSourceList.isEmpty()) {
                epgManagerWrapper.refresh(channelSourceList.get(currentPlayIndex), channelSourceList, currentSelectedDateIndex);
            }
        }));
    }

    /**
     * 上一个频道（遥控器调用）
     */
    public void playPrev() {
        currentPlayIndex = playControlManager.playPrev(channelSourceList, currentPlayIndex);
    }

    /**
     * 下一个频道（遥控器调用）
     */
    public void playNext() {
        currentPlayIndex = playControlManager.playNext(channelSourceList, currentPlayIndex);
    }

    /**
     * 切换侧边栏显示隐藏
     */
    public void togglePanel() {
        panelManager.toggle(channelSourceList, currentPlayIndex);
    }

    /**
     * 跳转设置页面
     */
    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    /**
     * 远程配置更新回调
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppConfig config = AppConfig.getInstance(this);
        config.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        LogUtils.log("【远程配置】更新直播源：" + liveUrl);
        LogUtils.log("【远程配置】更新EPG：" + epgUrl);
        runOnUiThread(this::loadLiveAndEpg);
    }

    /**
     * 返回键：优先关闭侧边栏，再退出
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
     * 遥控器按键分发
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager.dispatchKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    /**
     * APP切后台暂停播放
     */
    @Override
    protected void onPause() {
        super.onPause();
        LogUtils.log("【主页】onPause -> 切到后台");
        if (mPlayerManager != null) mPlayerManager.onBackground();
    }

    /**
     * APP切前台恢复播放+重载配置
     */
    @Override
    protected void onResume() {
        super.onResume();
        LogUtils.log("【主页】onResume -> 回到前台");
        settingsManager.reloadConfig();
        screenRatioManager.apply();
        if (mPlayerManager != null) mPlayerManager.onForeground();
    }

    /**
     * 页面销毁：注销广播、释放播放器资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtils.log("【主页】onDestroy -> 页面销毁");
        try { unregisterReceiver(toggleControllerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        if(mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }

    // 当前选中EPG日期下标
    private int currentSelectedDateIndex = 0;
}
