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
import java.util.ArrayList;
import java.util.List;

/**
 * 主播放页面
 * 修改说明：仅在PlayerView初始化处新增3行代码，永久关闭Exo原生控制栏，
 * 切台/点击屏幕不再弹出播放、暂停、进度条，频道弹窗、EPG、换台、遥控全功能保持原样
 */
public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    // 全频道原始数据源
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前选中分组内频道集合
    public List<Channel> currentGroupChannelList = new ArrayList<>();
    // 当前正在播放的频道全局下标
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

    // EPG节目单面板开关标记
    private boolean epgPanelOpen = false;
    // 原生控制器显示标记（广播保留，但配置已全局禁用，失效）
    private boolean isControllerVisible = false;
    // EPG总开关
    private boolean epg_enable;
    // 上下换台反向开关
    private boolean channel_reverse;
    // 数字选台开关
    private boolean number_channel_enable;
    // 自动更新源开关
    private boolean auto_update_source;
    // EPG日期选中下标
    private int currentSelectedDateIndex = 0;

    private SharedPreferences sp;
    // 切台信息弹窗布局控件
    private View info_bar;
    private TextView tv_channel_name, tv_tag_fhd, tv_tag_audio, tv_bitrate;
    private TextView tv_current_program_name, tv_current_time_range, tv_remaining_time;
    private android.widget.ProgressBar progress_program;
    // 切台频道号弹窗文本
    private TextView tv_channel_num;

    // 2秒后自动隐藏频道信息栏任务
    private final Runnable hideInfoBar = new Runnable() {
        @Override
        public void run() {
            info_bar.setVisibility(View.GONE);
        }
    };

    // 换台防抖时间戳、防重复切台间隔
    private long lastChannelChangeTime = 0;
    private static final long CHANNEL_COOLDOWN = 300;
    private float touchStartY = 0;
    private static final float SLIDE_THRESHOLD = 80;

    // 本地运行日志缓存集合，最多存储100条
    public static List<String> logList = new ArrayList<>();

    /**
     * 全局日志输出方法
     */
    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > 100) {
            logList.remove(logList.size() - 1);
        }
        SettingsActivity.log(msg);
    }

    /**
     * 切换播放器控制器显示广播接收器【代码完整保留，因全局禁用控制器，触发无效】
     */
    private BroadcastReceiver toggleControllerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isControllerVisible = !isControllerVisible;
            // 下方代码保留，但setUseController全局固定false，无法打开控制栏
            playerView.setUseController(isControllerVisible);
        }
    };

    /**
     * 刷新直播源+EPG数据广播接收器
     */
    private BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent)) {
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
        // 强制横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 全屏+隐藏系统导航栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(R.layout.activity_main);
        // 屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 绑定频道号弹窗控件
        tv_channel_num = findViewById(R.id.tv_channel_num);
        // 初始化频道信息弹窗控件
        initInfoBar();
        appConfig = AppConfig.getInstance(this);
        // 读取本地配置项
        loadSettings();
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        // 读取自定义直播、EPG地址
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        //==========播放器初始化【唯一修改位置：新增3行关闭Exo原生控制栏，其余不动】==========
        //绑定布局内播放器控件
        playerView = findViewById(R.id.player_view);
        /**
         * 配置1：永久关闭Exo自带底部控制面板，任何场景无法启用播放/暂停/进度条
         * 解决切台重建播放器自动弹出控制器、点击画面弹出控制栏问题
         */
        playerView.setUseController(false);
        /**
         * 配置2：设置控制器自动弹出超时为0，取消延时弹出逻辑，杜绝切台延迟弹出控制UI
         */
        playerView.setControllerShowTimeoutMs(0);
        /**
         * 配置3：清空控制器显示监听，拦截播放器内部自动唤起控制面板的回调
         */
        playerView.setControllerVisibilityListener(null);
        //====================================================================================

        // 侧边栏布局控件绑定
        panel_layout = findViewById(R.id.panel_layout);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);

        // 注册广播接收器（全部保留原有逻辑）
        registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));

        // EPG打开关闭按钮点击事件【原有逻辑完整保留】
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

        // EPG日期列表点击
        lvDate.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                currentSelectedDateIndex = position;
                if (!channelSourceList.isEmpty()) {
                    Channel curr = channelSourceList.get(currentPlayIndex);
                    epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                }
            }
        });

        // 频道分组列表点击切换分组
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                lvGroup.setItemChecked(position, true);
                lvGroup.setSelection(position);
                String groupName = groupListManager.getCurrentGroup(position);
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (groupName.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                channelListManager.setChannelsByGroup(channelSourceList, currentPlayIndex);
                if (!currentGroupChannelList.isEmpty()) {
                    Channel firstChannel = currentGroupChannelList.get(0);
                    int globalIndex = channelSourceList.indexOf(firstChannel);
                    if (globalIndex != -1) {
                        playChannel(globalIndex);
                    }
                }
            }
        });

        // 列表管理类初始化
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        dateListManager.initDate();

        panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);
        // 播放器管理类绑定播放器控件
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        // 实时码率、音画质数据回调
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                tv_tag_fhd.setText(info.quality);
                tv_tag_audio.setText(info.audio);
                tv_bitrate.setText(info.bitrate);
            }
        });

        // 画面缩放比例配置
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        // 触摸手势初始化
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
        // 读取上次播放下标
        currentPlayIndex = appConfig.getLastPlayIndex();
        log("【播放】记录上次播放索引：" + currentPlayIndex);
        // 加载频道源和EPG
        loadLiveAndEpg();
        initListViewClick();
    }

    /**
     * 初始化频道信息弹窗所有控件
     */
    private void initInfoBar() {
        info_bar = findViewById(R.id.info_bar);
        tv_channel_name = findViewById(R.id.tv_channel_name);
        tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        tv_tag_audio = findViewById(R.id.tv_bitrate);
        tv_current_program_name = findViewById(R.id.tv_current_program_name);
        tv_current_time_range = findViewById(R.id.tv_remaining_time);
        progress_program = findViewById(R.id.progress_program);
        tv_remaining_time = findViewById(R.id.tv_next_program_name);
        tv_next_program_name = findViewById(R.id.tv_next_time_range);
    }

    /**
     * 读取本地配置参数
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
     * 返回键关闭侧边栏
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
     * 加载频道直播源+EPG数据
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
     * 上一个频道
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
     * 下一个频道
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
     * 根据下标播放指定频道
     * 切台自动弹出频道名称、码率信息弹窗逻辑【完整保留】
     */
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
        String url = ch.getPlayUrl();
        log("========================================");
        log("【播放】频道名称：" + ch.getName());
        log("【播放】频道地址：" + url);
        log("【播放】当前索引：" + index);
        log("========================================");
        playerStateListener.setCurrentChannelName(ch.getName());
        mPlayerManager.playUrl(url);
        // 弹出频道号3秒自动消失
        showChannelNum(index + 1);
        // 保存本次播放下标
        appConfig.setLastPlayIndex(index);
        channelListManager.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
        // 切台弹出2秒自动隐藏频道信息栏（原逻辑完整保留）
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
    }

    /**
     * 弹出频道数字弹窗，3秒后自动隐藏
     */
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

    /**
     * 频道列表条目点击初始化
     */
    private void initListViewClick() {
        channelListManager.setOnItemClick(new ChannelListManager.OnItemClick() {
            @Override
            public void onClick(int pos) {
                playChannel(pos);
                panel_layout.setVisibility(View.GONE);
            }
        });
    }

    /**
     * 遥控按键分发
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return keyEventManager.dispatchKey(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 后台暂停播放
        if (mPlayerManager != null) {
            mPlayerManager.pausePlay();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 前台恢复播放
        if (mPlayerManager != null) {
            mPlayerManager.resumePlay();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 注销广播
        unregisterReceiver(toggleControllerReceiver);
        unregisterReceiver(refreshReceiver);
        // 释放播放器资源
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }
    }
}
