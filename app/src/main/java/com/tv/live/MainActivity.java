   package com.tv.live;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.ChannelSwitchManager;
import com.tv.live.manager.GestureManager;
import com.tv.live.manager.KeyEventManager;
import com.tv.live.manager.PanelManager;
import com.tv.live.manager.ScreenRatioManager;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainLog";
    // 播放器相关
    public TVPlayerManager mPlayerManager;
    private PlayerView playerView;
    // 面板相关
    private PanelManager panelManager;
    private View panelLayout;
    // 列表相关
    private ListView lvGroup;
    private ListView lvChannelList;
    private ListView lvDate;
    private ListView lvEpg;
    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    // 频道数据
    private List<Channel> channelSourceList = new ArrayList<>();
    private int currentPlayIndex = 0;
    private int currentSelectedDateIndex = 0;
    // 信息栏
    private View info_bar;
    private TextView tv_channel_name;
    private TextView tv_tag_fhd;
    private TextView tv_tag_audio;
    private TextView tv_bitrate;
    private TextView tv_current_program_name;
    private TextView tv_current_time_range;
    private ProgressBar progress_program;
    private TextView tv_remaining_time;
    private TextView tv_next_program_name;
    private TextView tv_next_time_range;
    private Runnable hideInfoBar;
    // 频道号显示
    private TextView tv_channel_num;
    // 加载动画
    private View loading_layout;
    // 手势和按键
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    // 屏幕比例
    private ScreenRatioManager screenRatioManager;
    // 频道切换
    private ChannelSwitchManager channelSwitchManager;
    // 播放状态监听器
    private PlayerStateListenerImpl playerStateListener;
    // 权限请求码
    private static final int PERMISSION_REQUEST_CODE = 1001;
    // 加载超时保护（15秒还没加载出来就提示用户）
    private static final long LOAD_TIMEOUT = 15000;
    private Handler loadTimeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable loadTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            hideLoading();
            Toast.makeText(MainActivity.this, "加载超时，请检查网络或切换频道", Toast.LENGTH_SHORT).show();
        }
    };

    // ====================================================================
    // ✅ 新增：节目进度定时更新
    // ====================================================================
    /**
     * 节目进度更新间隔（1 分钟）
     * 节目进度不需要每秒更新，每分钟更新一次就够了
     */
    private static final long PROGRAM_PROGRESS_INTERVAL = 60000;

    /**
     * 节目进度更新 Handler
     */
    private Handler programProgressHandler = new Handler(Looper.getMainLooper());

    /**
     * 节目进度更新 Runnable
     * 每分钟更新一次信息栏的节目进度和剩余时间
     */
    private final Runnable updateProgramProgressRunnable = new Runnable() {
        @Override
        public void run() {
            // 如果有正在播放的频道，就更新一下进度
            if (channelSourceList != null && !channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel channel = channelSourceList.get(currentPlayIndex);
                updateInfoBarEpg(channel);
            }
            // 继续下一次更新
            programProgressHandler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 初始化视图
        initViews();
        // 申请权限
        checkPermissions();
        // 初始化各类管理器
        initManagers();
        // 初始化监听器
        initListeners();
        // 加载直播源和EPG
        loadLiveAndEpg();
        // 进入设置后播放不暂停
        // （通过 onPause/onResume 控制，见下方）
    }
    private void initViews() {
        playerView = findViewById(R.id.player_view);
        panelLayout = findViewById(R.id.panel_layout);
        lvGroup = findViewById(R.id.lv_group);
        lvChannelList = findViewById(R.id.lv_channel_list);
        lvDate = findViewById(R.id.lv_date);
        lvEpg = findViewById(R.id.lv_epg);
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
        tv_channel_num = findViewById(R.id.tv_channel_num);
        loading_layout = findViewById(R.id.loading_layout);
        // 信息栏隐藏Runnable
        hideInfoBar = new Runnable() {
            @Override
            public void run() {
                if (info_bar != null) {
                    info_bar.setVisibility(View.GONE);
                }
            }
        };
    }
    private void checkPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "部分权限未授予，可能影响使用", Toast.LENGTH_SHORT).show();
            }
        }
    }
    private void initManagers() {
        // 播放器管理器
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        mPlayerManager.bindChannelText(tv_channel_num);
        // 面板管理器
        panelManager = new PanelManager(this, panelLayout);
        // 列表管理器
        channelListManager = new ChannelListManager(this, lvChannelList);
        groupListManager = new GroupListManager(this, lvGroup);
        dateListManager = new DateListManager(this, lvDate);
        // ✅ 先初始化 EpgManager（必须在 EpgManagerWrapper 之前）
        EpgManager.getInstance(this);
        epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        // 手势管理器
        gestureManager = new GestureManager(this, playerView);
        // 按键管理器
        keyEventManager = new KeyEventManager(this);
        // 屏幕比例管理器
        screenRatioManager = new ScreenRatioManager(this);
        // 频道切换管理器
        channelSwitchManager = ChannelSwitchManager.getInstance();
        // 播放状态监听器
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        // 直播信息更新监听器
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                if (tv_tag_fhd != null) tv_tag_fhd.setText(info.quality);
                if (tv_tag_audio != null) tv_tag_audio.setText(info.audio);
                if (tv_bitrate != null) tv_bitrate.setText(info.bitrate);
                
                // ====================================================================
                // ✅ 新增：如果信息栏正在显示，也同步更新一下
                // ====================================================================
                if (info_bar != null && info_bar.getVisibility() == View.VISIBLE) {
                    if (tv_tag_fhd != null) tv_tag_fhd.setText(info.quality);
                    if (tv_tag_audio != null) tv_tag_audio.setText(info.audio);
                    if (tv_bitrate != null) tv_bitrate.setText(info.bitrate);
                }
            }
        });
    }
    private void initListeners() {
        // 分组列表点击
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            groupListManager.setSelectedPosition(position);
            // 切换分组后，更新频道列表
            List<Channel> channels = groupListManager.getGroupChannels(position);
            channelListManager.setChannels(channels);
            // 不自动播放第一个频道，等用户手动选择
        });
        // 频道列表点击
        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            Channel ch = channelListManager.getChannel(position);
            if (ch != null) {
                // 找到这个频道在总列表中的索引
                int index = findChannelIndex(ch);
                if (index >= 0) {
                    playChannel(index);
                }
            }
            panelManager.hidePanel();
        });
        // 日期列表点击
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            dateListManager.setSelectedPosition(position);
            currentSelectedDateIndex = position;
            // 刷新EPG
            if (!channelSourceList.isEmpty() && currentPlayIndex < channelSourceList.size()) {
                Channel ch = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(ch, channelSourceList, position);
            }
        });
        // 手势监听
        gestureManager.setOnGestureListener(new GestureManager.OnGestureListener() {
            @Override
            public void onSwipeLeft() {
                playNext();
            }
            @Override
            public void onSwipeRight() {
                playPrev();
            }
            @Override
            public void onSwipeUp() {
                panelManager.showPanel();
            }
            @Override
            public void onSwipeDown() {
                panelManager.hidePanel();
            }
            @Override
            public void onSingleTap() {
                togglePanel();
            }
            @Override
            public void onDoubleTap() {
                // 双击可以切换暂停/播放
                if (mPlayerManager != null) {
                    // 直播流一般不支持暂停，这里可以留空或者做其他操作
                }
            }
        });
        // 按键监听
        keyEventManager.setOnKeyActionListener(new KeyEventManager.OnKeyActionListener() {
            @Override
            public void onChannelUp() {
                playPrev();
            }
            @Override
            public void onChannelDown() {
                playNext();
            }
            @Override
            public void onVolumeUp() {
                // 音量加
            }
            @Override
            public void onVolumeDown() {
                // 音量减
            }
            @Override
            public void onMenu() {
                togglePanel();
            }
            @Override
            public void onBack() {
                if (panelManager.isPanelShowing()) {
                    panelManager.hidePanel();
                } else {
                    finish();
                }
            }
            @Override
            public void onSettings() {
                openSettings();
            }
            @Override
            public void onNumber(int num) {
                // 数字选台
                if (channelSwitchManager != null) {
                    channelSwitchManager.inputNumber(num);
                    if (channelSwitchManager.isComplete()) {
                        int channelNum = channelSwitchManager.getChannelNumber();
                        playByChannelNumber(channelNum);
                        channelSwitchManager.reset();
                    }
                }
            }
        });
    }
    /**
     * 根据频道号播放
     */
    private void playByChannelNumber(int channelNum) {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            return;
        }
        // 找到对应频道号的频道
        for (int i = 0; i < channelSourceList.size(); i++) {
            Channel ch = channelSourceList.get(i);
            if (ch.getNumber() == channelNum) {
                playChannel(i);
                return;
            }
        }
        // 没找到，提示
        Toast.makeText(this, "没有找到频道 " + channelNum, Toast.LENGTH_SHORT).show();
    }
    /**
     * 查找频道在总列表中的索引
     */
    private int findChannelIndex(Channel channel) {
        if (channel == null || channelSourceList == null) {
            return -1;
        }
        for (int i = 0; i < channelSourceList.size(); i++) {
            if (channelSourceList.get(i).getName().equals(channel.getName())) {
                return i;
            }
        }
        return -1;
    }
    private void loadLiveAndEpg() {
        showLoading();
        // 启动加载超时保护
        loadTimeoutHandler.removeCallbacks(loadTimeoutRunnable);
        loadTimeoutHandler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT);
        
        // 先从缓存加载EPG（快速显示）
        boolean cacheLoaded = EpgManager.getInstance().loadEpgFromCache();
        if (!cacheLoaded) {
            SettingsActivity.log("【主页面】EPG缓存不存在，等待网络加载");
        }
        
        // 加载直播源
        LiveSourceLoader.load(this, new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                runOnUiThread(() -> {
                    channelSourceList.clear();
                    channelSourceList.addAll(channels);
                    // 更新分组和频道列表
                    updateGroupAndChannelList();
                    // 播放第一个频道
                    if (!channelSourceList.isEmpty()) {
                        playChannel(0);
                    }
                    // 取消加载超时
                    loadTimeoutHandler.removeCallbacks(loadTimeoutRunnable);
                    hideLoading();
                });
                // 加载EPG（后台加载）
                loadEpg();
            }
            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    // 取消加载超时
                    loadTimeoutHandler.removeCallbacks(loadTimeoutRunnable);
                    hideLoading();
                    Toast.makeText(MainActivity.this, "加载直播源失败：" + msg, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    /**
     * 加载EPG节目单
     */
    private void loadEpg() {
        // 从直播源提取EPG地址，如果没有就用默认的
        if (!channelSourceList.isEmpty()) {
            Channel first = channelSourceList.get(0);
            if (first != null && first.getPlayUrl() != null) {
                EpgManager.getInstance().loadEpgFromM3u(first.getPlayUrl(), () -> {
                    runOnUiThread(() -> {
                        // EPG加载完成，刷新当前频道的节目单
                        if (!channelSourceList.isEmpty() && currentPlayIndex < channelSourceList.size()) {
                            Channel ch = channelSourceList.get(currentPlayIndex);
                            epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
                            
                            // ====================================================================
                            // ✅ 新增：EPG加载完成后，更新信息栏的节目信息
                            // ====================================================================
                            updateInfoBarEpg(ch);
                        }
                    });
                });
            }
        }
    }
    /**
     * 更新分组和频道列表
     */
    private void updateGroupAndChannelList() {
        // 按分组整理频道
        // 这里简化处理，所有频道放一个分组
        // 实际项目中应该从直播源中提取分组信息
        List<String> groups = new ArrayList<>();
        groups.add("全部频道");
        groupListManager.setGroups(groups);
        channelListManager.setChannels(channelSourceList);
    }
    /**
     * 播放上一个频道
     */
    public void playPrev() {
        if (channelSourceList.isEmpty()) return;
        int prevIndex = currentPlayIndex - 1;
        if (prevIndex < 0) {
            prevIndex = channelSourceList.size() - 1;
        }
        playChannel(prevIndex);
    }
    /**
     * 播放下一个频道
     */
    public void playNext() {
        if (channelSourceList.isEmpty()) return;
        int nextIndex = currentPlayIndex + 1;
        if (nextIndex >= channelSourceList.size()) {
            nextIndex = 0;
        }
        playChannel(nextIndex);
    }
    /**
     * 播放指定索引的频道
     */
    public void playChannel(int index) {
        if (index < 0 || index >= channelSourceList.size()) return;
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        // 更新频道号
        mPlayerManager.setCurrentChannelNumber(ch.getNumber());
        // 更新列表选中状态
        channelListManager.setSelectedPosition(index);
        // 显示信息栏
        if (info_bar != null) {
            info_bar.setVisibility(View.VISIBLE);
            info_bar.removeCallbacks(hideInfoBar);
            info_bar.postDelayed(hideInfoBar, 2000);
            tv_channel_name.setText(ch.getName());
            TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
            tv_tag_fhd.setText(live.quality);
            tv_tag_audio.setText(live.audio);
            tv_bitrate.setText(live.bitrate);
            
            // ====================================================================
            // ✅ 新增：更新信息栏的 EPG 节目信息
            // ====================================================================
            updateInfoBarEpg(ch);
            
            // ====================================================================
            // ✅ 新增：重启节目进度定时更新
            // ====================================================================
            programProgressHandler.removeCallbacks(updateProgramProgressRunnable);
            programProgressHandler.postDelayed(updateProgramProgressRunnable, PROGRAM_PROGRESS_INTERVAL);
        }
        // ====================================================================
        // ✅ 优化：按键即加载，先播放再做UI动画
        // ====================================================================
        /**
         * 【为什么要把 playUrl 移到最前面？】
         *
         * 原来的顺序是：
         * 1. 更新 UI（选中状态、信息栏等）
         * 2. 调用 mPlayerManager.playUrl() 开始加载
         *
         * 这样会浪费 100-300ms 的时间在 UI 动画上。
         *
         * 优化后的顺序是：
         * 1. 先调用 mPlayerManager.playUrl() 开始加载
         * 2. 再更新 UI
         *
         * 这样播放器可以提前 100-300ms 开始加载数据，
         * 加上 TVPlayerManager 里的快速出画优化，
         * 整体切台速度能快 0.5-1 秒。
         */
        // 先播放（最重要的事情先做）
        mPlayerManager.playUrl(ch.getPlayUrl());
        // 再刷新EPG（可以在后台做，不影响播放）
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
    }
    /**
     * 显示频道号
     */
    private void showChannelNum(int num) {
        if (tv_channel_num == null) return;
        tv_channel_num.setText(String.valueOf(num));
        tv_channel_num.setVisibility(View.VISIBLE);
        // 3秒后隐藏
        tv_channel_num.postDelayed(() -> {
            tv_channel_num.setVisibility(View.GONE);
        }, 3000);
    }
    /**
     * 初始化列表点击事件
     */
    private void initListViewClick() {
        // 已经在 initListeners 里处理了
    }
    /**
     * 切换面板显示/隐藏
     */
    private void togglePanel() {
        if (panelManager.isPanelShowing()) {
            panelManager.hidePanel();
        } else {
            panelManager.showPanel();
        }
    }
    /**
     * 打开设置页面
     */
    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }
    /**
     * 显示加载动画
     */
    private void showLoading() {
        if (loading_layout != null) {
            loading_layout.setVisibility(View.VISIBLE);
        }
    }
    /**
     * 隐藏加载动画
     */
    private void hideLoading() {
        if (loading_layout != null) {
            loading_layout.setVisibility(View.GONE);
        }
    }
    /**
     * 接收配置变化广播
     */
    private BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 配置变化时刷新
            if (intent.getAction() != null 
                    && intent.getAction().equals("com.tv.live.CONFIG_CHANGED")) {
                // 重新加载直播源
                loadLiveAndEpg();
            }
        }
    };
    @Override
    protected void onPause() {
        super.onPause();
        // 进入设置后播放不暂停
        // 这里什么都不做，播放器继续播放
        // 如果需要暂停，可以调用 mPlayerManager.pause();
    }
    @Override
    protected void onResume() {
        super.onResume();
        // 恢复播放（如果暂停了的话）
        // mPlayerManager.resume();
    }
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 进入沉浸模式
            hideSystemUI();
        }
    }
    /**
     * 隐藏系统UI（状态栏、导航栏）
     */
    private void hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ 使用 WindowInsetsController
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.statusBars() 
                            | android.view.WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                // 旧版本使用 setSystemUiVisibility
                View decorView = getWindow().getDecorView();
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "隐藏系统UI失败", e);
        }
    }
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyEventManager != null && keyEventManager.handleKeyEvent(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // ====================================================================
        // ✅ 停止节目进度更新
        // ====================================================================
        programProgressHandler.removeCallbacks(updateProgramProgressRunnable);
        
        // 取消加载超时
        loadTimeoutHandler.removeCallbacks(loadTimeoutRunnable);
        // 释放播放器
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }
        // 注销广播
        try {
            unregisterReceiver(configReceiver);
        } catch (Exception e) {
            // 可能没注册，忽略
        }
    }

    // ====================================================================
    // ✅ 新增：信息栏 EPG 节目信息更新相关方法
    // ====================================================================

    /**
     * 更新信息栏的 EPG 节目信息
     * 切台时调用，更新当前节目、下一个节目、进度等
     *
     * @param channel 当前播放的频道
     */
    private void updateInfoBarEpg(Channel channel) {
        if (channel == null || tv_current_program_name == null) {
            return;
        }
        
        try {
            // 从 EpgManager 获取该频道的所有节目
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channel.getName());
            
            if (epgList == null || epgList.isEmpty()) {
                // 没有匹配到节目数据
                tv_current_program_name.setText("暂无节目信息");
                tv_current_time_range.setText("");
                tv_next_program_name.setText("");
                tv_next_time_range.setText("");
                if (progress_program != null) progress_program.setProgress(0);
                if (tv_remaining_time != null) tv_remaining_time.setText("");
                return;
            }
            
            // ========================================
            // 筛选今天的节目（双重兼容：今天/对应周几）
            // ========================================
            List<Channel.EpgItem> todayEpg = new ArrayList<>();
            Calendar cal = Calendar.getInstance();
            int w = cal.get(Calendar.DAY_OF_WEEK);
            String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            String todayWeekDay = weekMap[w - 1];
            
            for (Channel.EpgItem item : epgList) {
                if (item.dayName == null) continue;
                String dayName = item.dayName.trim();
                // 双重兼容：匹配 "今天" 或对应的周几
                if ("今天".equals(dayName) || todayWeekDay.equals(dayName)) {
                    todayEpg.add(item);
                }
            }
            
            if (todayEpg.isEmpty()) {
                tv_current_program_name.setText("暂无节目信息");
                tv_current_time_range.setText("");
                tv_next_program_name.setText("");
                tv_next_time_range.setText("");
                return;
            }
            
            // 按开始时间排序
            Collections.sort(todayEpg, new Comparator<Channel.EpgItem>() {
                @Override
                public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                    return o1.time.compareTo(o2.time);
                }
            });
            
            // ========================================
            // 找到当前正在播放的节目
            // ========================================
            String now = getNowTimeStr();
            Channel.EpgItem currentProgram = null;
            Channel.EpgItem nextProgram = null;
            int currentIndex = -1;
            
            for (int i = 0; i < todayEpg.size(); i++) {
                Channel.EpgItem item = todayEpg.get(i);
                String startTime = item.time;
                String endTime;
                
                // 计算结束时间（下一个节目的开始时间）
                if (i + 1 < todayEpg.size()) {
                    endTime = todayEpg.get(i + 1).time;
                } else {
                    endTime = "23:59"; // 最后一个节目默认到 23:59
                }
                
                if (isTimeInRange(now, startTime, endTime)) {
                    currentProgram = item;
                    currentIndex = i;
                    // 下一个节目
                    if (i + 1 < todayEpg.size()) {
                        nextProgram = todayEpg.get(i + 1);
                    }
                    break;
                }
            }
            
            // ========================================
            // 更新当前节目信息
            // ========================================
            if (currentProgram != null) {
                // 节目名称
                tv_current_program_name.setText(currentProgram.title);
                
                // 计算结束时间
                String endTime;
                if (currentIndex + 1 < todayEpg.size()) {
                    endTime = todayEpg.get(currentIndex + 1).time;
                } else {
                    endTime = "23:59";
                }
                
                // 时间范围
                if (tv_current_time_range != null) {
                    tv_current_time_range.setText(currentProgram.time + " - " + endTime);
                }
                
                // 计算进度和剩余时间
                long nowMillis = timeToMillis(now);
                long startMillis = timeToMillis(currentProgram.time);
                long endMillis = timeToMillis(endTime);
                
                if (endMillis > startMillis && progress_program != null) {
                    // 进度百分比
                    int progress = (int) ((nowMillis - startMillis) * 100 / (endMillis - startMillis));
                    progress_program.setProgress(progress);
                    
                    // 剩余时间
                    long remainingMillis = endMillis - nowMillis;
                    int remainingMinutes = (int) (remainingMillis / 1000 / 60);
                    if (tv_remaining_time != null) {
                        if (remainingMinutes >= 60) {
                            int hours = remainingMinutes / 60;
                            int mins = remainingMinutes % 60;
                            tv_remaining_time.setText("剩余 " + hours + "时" + mins + "分");
                        } else {
                            tv_remaining_time.setText("剩余 " + remainingMinutes + "分钟");
                        }
                    }
                }
            } else {
                // 没找到当前播放的节目
                tv_current_program_name.setText("暂无节目信息");
                if (tv_current_time_range != null) tv_current_time_range.setText("");
                if (progress_program != null) progress_program.setProgress(0);
                if (tv_remaining_time != null) tv_remaining_time.setText("");
            }
            
            // ========================================
            // 更新下一个节目信息
            // ========================================
            if (nextProgram != null && tv_next_program_name != null) {
                tv_next_program_name.setText(nextProgram.title);
                // 下一个节目的结束时间
                String nextEndTime;
                if (currentIndex + 2 < todayEpg.size()) {
                    nextEndTime = todayEpg.get(currentIndex + 2).time;
                } else {
                    nextEndTime = "23:59";
                }
                if (tv_next_time_range != null) {
                    tv_next_time_range.setText(nextProgram.time + " - " + nextEndTime);
                }
            } else {
                if (tv_next_program_name != null) tv_next_program_name.setText("");
                if (tv_next_time_range != null) tv_next_time_range.setText("");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            tv_current_program_name.setText("暂无节目信息");
            if (tv_current_time_range != null) tv_current_time_range.setText("");
            if (tv_next_program_name != null) tv_next_program_name.setText("");
            if (tv_next_time_range != null) tv_next_time_range.setText("");
        }
    }

    /**
     * 获取当前时间字符串（HH:mm 格式）
     */
    private String getNowTimeStr() {
        Calendar cal = Calendar.getInstance();
        return String.format("%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE));
    }

    /**
     * 判断时间是否在指定范围内
     *
     * @param now 当前时间（HH:mm）
     * @param start 开始时间（HH:mm）
     * @param end 结束时间（HH:mm）
     * @return 是否在范围内
     */
    private boolean isTimeInRange(String now, String start, String end) {
        try {
            if (now == null || start == null || end == null) {
                return false;
            }
            if (!now.contains(":") || !start.contains(":") || !end.contains(":")) {
                return false;
            }
            return now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把 HH:mm 格式的时间转换成当天的毫秒数
     * 用于计算节目进度和剩余时间
     *
     * @param timeStr 时间字符串（HH:mm）
     * @return 当天的毫秒数
     */
    private long timeToMillis(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            
            return cal.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }
}
