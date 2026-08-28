package com.tv.live.manager;

import android.content.Context;
import com.tv.live.util.LogBridge;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 频道面板控制器
 * 已恢复遥控器按键与焦点管理
 */
public class ChannelPanelController {

    private static final long CHANNEL_COOLDOWN = 300;
    private static final int MAX_AUTO_SKIP = 10;

    private MainActivity activity;
    private Context context;
    private View panelLayout;
    private ListView lvGroup;
    private ListView lvChannelList;
    private ListView lvChannelListEpg;
    private ListView lvDate;
    private ListView lvEpg;
    private TextView btnShowEpg;
    private TextView btnBackGroup;

    private View llLeftPanel;
    private View llRightPanel;
    private boolean rightPanelOpen = false;

    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private ChannelListManager channelListManagerEpg;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PanelManager panelManager;

    private List<Channel> channelSourceList = new ArrayList<>();
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    private String currentGroupName = "";
    private int currentPlayIndex = 0;
    private int currentSelectedDateIndex = 0;   // 🟢 默认选中今天：日期列表 [0=今天, 1=周三, 2=周四, 3=周五, 4=周六, 5=周日, 6=周一]

    private boolean epgPanelOpen = false;
    private boolean epgEnable = true;

    /** 🆕 已加载过的虎牙固定分组（虎牙电影/电视剧/动漫/综艺/一起看），缓存避免重复拉取 */
    private final Set<String> loadedFixedGroups = new HashSet<>();

    /** 🆕 SDK 独立线路分组预加载是否已触发（防止重复触发） */
    private boolean sdkGroupsPreloadStarted = false;
    /** 🆕 SDK 独立线路分组预加载是否正在执行中 */
    private volatile boolean sdkGroupsPreloading = false;
    /** 🆕 预加载专用单线程执行器（避免占用主线程/打断直播 UI） */
    private final ExecutorService sdkPreloadExecutor = Executors.newSingleThreadExecutor();

    private boolean mIsFirstLaunch = true;

    private boolean isReverse = false;
    private long lastChannelChangeTime = 0;

    private String lastSwitchDirection = "";
    private boolean isSwitchingChannel = false;
    private int autoSkipCount = 0;

    private OnChannelChangeListener channelChangeListener;
    private OnPanelStateListener panelStateListener;

    public interface OnChannelChangeListener {
        void onChannelChanged(Channel channel, int index);
    }

    public interface OnPanelStateListener {
        void onPanelStateChanged(boolean isOpen);
    }

    public ChannelPanelController(
            MainActivity activity,
            View panelLayout,
            View llLeftPanel,
            View llRightPanel,
            ListView lvGroup,
            ListView lvChannelList,
            ListView lvChannelListEpg,
            ListView lvDate,
            ListView lvEpg,
            TextView btnShowEpg,
            TextView btnBackGroup,
            GroupListManager groupListManager,
            ChannelListManager channelListManager,
            ChannelListManager channelListManagerEpg,
            DateListManager dateListManager,
            EpgManagerWrapper epgManagerWrapper,
            PanelManager panelManager
    ) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.panelLayout = panelLayout;
        this.llLeftPanel = llLeftPanel;
        this.llRightPanel = llRightPanel;
        this.lvGroup = lvGroup;
        this.lvChannelList = lvChannelList;
        this.lvChannelListEpg = lvChannelListEpg;
        this.lvDate = lvDate;
        this.lvEpg = lvEpg;
        this.btnShowEpg = btnShowEpg;
        this.btnBackGroup = btnBackGroup;
        this.groupListManager = groupListManager;
        this.channelListManager = channelListManager;
        this.channelListManagerEpg = channelListManagerEpg;
        this.dateListManager = dateListManager;
        this.epgManagerWrapper = epgManagerWrapper;
        this.panelManager = panelManager;
        initClickListeners();
        // 已删除 initFocusListeners()，焦点由 ListView 自身管理

        // 🟢 严格遵守：只对虎牙频道生效的 EPG 刷新回调（避免第一次进入虎牙频道时，
        //    异步解析还没完成，EPG面板显示空白"获取不了历史节目单"）
        try {
            com.tv.live.EpgManager.getInstance().registerHuyaEpgReadyListener(
                    new com.tv.live.EpgManager.OnHuyaEpgReadyListener() {
                @Override
                public void onHuyaEpgReady(com.tv.live.Channel readyChannel) {
                    if (readyChannel == null) return;
                    if (panelLayout == null) return;
                    if (channelSourceList == null || currentPlayIndex < 0
                            || currentPlayIndex >= channelSourceList.size()) return;
                    com.tv.live.Channel nowPlaying = channelSourceList.get(currentPlayIndex);
                    if (nowPlaying == null) return;
                    // 只在当前正在播放的频道正好就是这个 readyChannel 时才刷新面板
                    boolean sameChannel = (readyChannel.getHuyaRoomId() > 0
                            && readyChannel.getHuyaRoomId() == nowPlaying.getHuyaRoomId())
                            || (readyChannel.getChannelId() != null
                            && readyChannel.getChannelId().equals(nowPlaying.getChannelId()))
                            || (readyChannel.getName() != null
                            && readyChannel.getName().equals(nowPlaying.getName()));
                    if (!sameChannel) return;
                    panelLayout.post(() -> {
                        try {
                            if (epgManagerWrapper != null) {
                                epgManagerWrapper.refresh(nowPlaying, channelSourceList, currentSelectedDateIndex);
                                LogBridge.d("ChannelPanel", "🟢【虎牙EPG刷新】解析完成，已刷新右侧面板："
                                        + (nowPlaying.getName() == null ? "" : nowPlaying.getName().substring(0, Math.min(20, nowPlaying.getName().length()))));
                            }
                        } catch (Exception ignored) {}
                    });
                }
            });
        } catch (Exception ignored) {}
    }

    public void updatePanelBackground(int resId) {
        if (llLeftPanel != null) llLeftPanel.setBackgroundResource(resId);
        if (llRightPanel != null) llRightPanel.setBackgroundResource(resId);
    }

    private void initClickListeners() {
        lvGroup.setOnItemClickListener((parent, view, position, id) -> onGroupClicked(position));
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));
        lvChannelListEpg.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));

        channelListManager.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, false));
        channelListManagerEpg.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, true));

        btnShowEpg.setOnClickListener(v -> onEpgButtonClicked());
        btnShowEpg.setOnFocusChangeListener((v, hasFocus) -> {
            btnShowEpg.setTextColor(hasFocus ? 0xFF40A9FF : 0xFFFFFFFF);
            btnShowEpg.setBackgroundColor(hasFocus ? 0x3340A9FF : 0x00000000);
        });
        btnShowEpg.setOnKeyListener((v, keyCode, event) -> {
            LogBridge.d("ChannelPanel", "btnShowEpg onKey keyCode:" + keyCode + ", action:" + event.getAction());
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    onEpgButtonClicked();
                    return true;
                }
            }
            return false;
        });

        btnBackGroup.setOnClickListener(v -> onBackGroupClicked());
        btnBackGroup.setOnFocusChangeListener((v, hasFocus) -> {
            btnBackGroup.setTextColor(hasFocus ? 0xFF40A9FF : 0xFFFFFFFF);
            btnBackGroup.setBackgroundColor(hasFocus ? 0x3340A9FF : 0x00000000);
        });
        btnBackGroup.setOnKeyListener((v, keyCode, event) -> {
            LogBridge.d("ChannelPanel", "btnBackGroup onKey keyCode:" + keyCode + ", action:" + event.getAction());
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    onBackGroupClicked();
                    return true;
                }
            }
            return false;
        });
    }

    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;
        LogBridge.d("ChannelPanel", "setChannels: 原始频道数=" + channels.size());
        // 🆕 注册虎牙一起看5大固定分组（虎牙电影/电视剧/动漫/综艺/一起看），排在用户自己分组之后
        groupListManager.setFixedGroups(HuyaTogetherWatchGroupManager.GROUP_NAMES);
        // 🆕 注册 HTTP API 线路 8 组预置普通分组（怀旧老片/外国电影/影视热播/海外追剧/剧集追剧/海外动漫/动漫动画/综艺娱乐），
        // 启动即固定进分组列表，HTTP API 加载完成后只更新频道、不再新增分组、不再刷新分组列表
        groupListManager.setPresetGroups(HuyaTogetherWatchManager.HTTP_API_GROUP_NAMES);
        loadedFixedGroups.clear();
        groupListManager.setGroups(this.channelSourceList);
        // 🔧 修复：切源后重置分组状态为"全部"，否则 currentGroupName 残留旧源组名，
        // 导致 togglePanel() 走 else 分支用旧组名过滤新源 → 频道列表为空
        currentGroupName = GroupListManager.GROUP_ALL;
        currentGroupChannelList.clear();
        currentGroupChannelList.addAll(this.channelSourceList);
        channelListManager.setChannels(this.channelSourceList, currentPlayIndex);
        channelListManagerEpg.setChannels(this.channelSourceList, currentPlayIndex);
        // 异步加载虎牙一起看频道（关键词子分类：电影_喜剧/电影_动作/剧集_古装等），加载完成后追加并刷新分组
        loadHuyaTogetherWatchChannels();
        // 🆕 SDK 独立线路：启动即后台预加载虎牙 5 大固定分组，点击时直接缓存命中，无需等待
        preloadSdkFixedGroups();
    }

    /**
     * 🆕 SDK 独立线路后台预加载：一次性拉取虎牙 5 大固定分组（电影/电视剧/动漫/综艺/一起看）。
     * SDK 未就绪时注册就绪回调，等初始化完成后自动补发；已就绪则直接触发。
     * 预加载成功后将 5 个分组全部写入 loadedFixedGroups 缓存并合并到频道列表，
     * 用户点击任意固定分组时走本地过滤（秒开），不再触发 SDK 网络请求。
     */
    private void preloadSdkFixedGroups() {
        if (sdkGroupsPreloadStarted) {
            // 切换源后 setChannels 会 clear loadedFixedGroups，此时重新补预加载
            if (loadedFixedGroups.isEmpty() && !sdkGroupsPreloading) {
                sdkPreloadExecutor.execute(() -> doPreloadSdkFixedGroups());
            }
            return;
        }
        sdkGroupsPreloadStarted = true;
        // 🆕 预加载整体在专用子线程执行：SDK 就绪检查、就绪等待、触发 fetchAllGroups 均不占用主线程
        sdkPreloadExecutor.execute(new Runnable() {
            @Override public void run() {
                if (HuyaSDKParser.isSDKAvailable()) {
                    doPreloadSdkFixedGroups();
                } else {
                    HuyaSDKParser.addInitReadyListener(new Runnable() {
                        @Override public void run() {
                            sdkPreloadExecutor.execute(() -> doPreloadSdkFixedGroups());
                        }
                    });
                }
            }
        });
    }

    private void doPreloadSdkFixedGroups() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (sdkGroupsPreloading) return;
        sdkGroupsPreloading = true;
        LogBridge.d("ChannelPanel", "🚀 SDK 独立线路后台预加载虎牙 5 大固定分组…");
        HuyaTogetherWatchGroupManager.getInstance().fetchAllGroups(
                new HuyaTogetherWatchGroupManager.OnGroupsListener() {
            @Override
            public void onSuccess(List<HuyaTogetherWatchGroupManager.Group> groups) {
                sdkGroupsPreloading = false;
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                if (groups == null || groups.isEmpty()) {
                    LogBridge.w("ChannelPanel", "SDK 固定分组预加载: 返回空");
                    return;
                }
                long t0 = System.currentTimeMillis();
                // 与 loadFixedGroupChannels 相同的双重去重逻辑（channelId / huyaRoomId）
                HashSet<String> existIds = new HashSet<>();
                HashSet<Integer> existRooms = new HashSet<>();
                for (Channel c : channelSourceList) {
                    if (c.getChannelId() != null) existIds.add(c.getChannelId());
                    if (c.getHuyaRoomId() > 0) existRooms.add(c.getHuyaRoomId());
                }
                int totalAdded = 0;
                java.util.ArrayList<Integer> twRoomIds = new java.util.ArrayList<>();
                for (HuyaTogetherWatchGroupManager.Group g : groups) {
                    if (g == null || g.name == null || g.channels == null) continue;
                    int added = 0;
                    for (Channel c : g.channels) {
                        if (c == null) continue;
                        if (c.getHuyaRoomId() > 0 && existRooms.contains(c.getHuyaRoomId())) continue;
                        if (c.getChannelId() != null && existIds.contains(c.getChannelId())) continue;
                        channelSourceList.add(c);
                        if (c.getChannelId() != null) existIds.add(c.getChannelId());
                        if (c.getHuyaRoomId() > 0) {
                            existRooms.add(c.getHuyaRoomId());
                            twRoomIds.add(c.getHuyaRoomId());
                        }
                        added++;
                    }
                    loadedFixedGroups.add(g.name);
                    LogBridge.d("ChannelPanel", "SDK 分组[" + g.name + "]预加载完成，新增 " + added + " 个频道");
                    totalAdded += added;
                }
                LogBridge.d("ChannelPanel", "✅ SDK 5 大固定分组预加载完成，共新增 " + totalAdded
                        + " 个频道，总计 " + channelSourceList.size()
                        + "，耗时 " + (System.currentTimeMillis() - t0) + "ms");
                // 后台并行预解析直播源，缩短起播时间
                if (!twRoomIds.isEmpty()) {
                    HuyaSDKParser.preloadRooms(twRoomIds);
                }
                // 刷新分组与频道列表（分组已固定，只更新计数）
                groupListManager.setGroups(channelSourceList, false);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            }

            @Override
            public void onError(String errMsg) {
                sdkGroupsPreloading = false;
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                LogBridge.w("ChannelPanel", "SDK 固定分组预加载失败: " + errMsg
                        + "（用户点击分组时仍会走单组拉取）");
            }
        });
    }

    /**
     * 异步加载虎牙一起看频道并追加到频道列表
     * 使用 HuyaTogetherWatchManager 获取丰富的关键词子分类房间
     */
    private void loadHuyaTogetherWatchChannels() {
        HuyaTogetherWatchManager.getInstance().fetchTogetherWatchChannels(
                new HuyaTogetherWatchManager.OnChannelsFetchedListener() {
            @Override
            public void onSuccess(List<Channel> channels) {
                if (channels == null || channels.isEmpty()) {
                    LogBridge.d("ChannelPanel", "虎牙一起看: 未获取到频道");
                    return;
                }
                panelLayout.post(() -> {
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                    // 避免重复添加（按 channelId 去重）
                    java.util.HashSet<String> existIds = new java.util.HashSet<>();
                    for (Channel c : channelSourceList) {
                        if (c.getChannelId() != null) existIds.add(c.getChannelId());
                    }
                    int added = 0;
                    java.util.ArrayList<Integer> twRoomIds = new java.util.ArrayList<>();
                    for (Channel c : channels) {
                        if (c.getChannelId() != null && !existIds.contains(c.getChannelId())) {
                            channelSourceList.add(c);
                            existIds.add(c.getChannelId());
                            added++;
                            // 🟢 并行加载：虎牙一起看频道加入列表后，立即触发预解析（与直播源显示并行）
                            if (c.getHuyaRoomId() > 0) twRoomIds.add(c.getHuyaRoomId());
                        }
                    }
                    LogBridge.d("ChannelPanel", "虎牙一起看加载完成，新增 " + added + " 个频道，总计 " + channelSourceList.size());
                    if (!twRoomIds.isEmpty()) {
                        LogBridge.d("ChannelPanel", "🟢【虎牙预解析】(一起看) 收集到 " + twRoomIds.size() + " 个房间，开始后台并行预解析");
                        com.tv.live.util.HuyaSDKParser.preloadRooms(twRoomIds);
                    }
                    // 刷新分组与频道列表（分组已固定，只更新计数）
                    groupListManager.setGroups(channelSourceList, false);
                    // 🔧 同步重置分组状态（与 setChannels 一致）
                    currentGroupName = GroupListManager.GROUP_ALL;
                    currentGroupChannelList.clear();
                    currentGroupChannelList.addAll(channelSourceList);
                    channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
                });
            }

            @Override
            public void onFailed(String errorMsg) {
                LogBridge.d("ChannelPanel", "加载虎牙一起看失败: " + errorMsg);
            }
        });
    }

    /**
     * 🆕 点击虎牙一起看固定分组（虎牙电影/电视剧/动漫/综艺/一起看）：
     * 频道唯一来源为 SDK 内部一起看列表（HuyaTogetherWatchGroupManager 独立路线）。
     * 首次点击异步拉取 → 按 channelId/huyaRoomId 去重追加到 channelSourceList（标记 isHuyaSdkTogetherWatch）；
     * 再次点击走本地过滤（缓存命中）。
     */
    private void loadFixedGroupChannels(final String groupName) {
        if (channelSourceList == null) return;
        // 缓存命中：直接本地过滤
        if (loadedFixedGroups.contains(groupName)) {
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(GroupListManager.getNormalizedGroup(c))) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            return;
        }
        // 加载中占位：清空右侧列表
        currentGroupChannelList.clear();
        channelListManager.setFilteredChannels(new ArrayList<Channel>(), null);
        // Toast.makeText(context, "正在加载「" + groupName + "」…", Toast.LENGTH_SHORT).show();

        HuyaTogetherWatchGroupManager.getInstance().fetchGroup(groupName,
                new HuyaTogetherWatchGroupManager.OnChannelsListener() {
                    @Override
                    public void onSuccess(List<Channel> channels) {
                        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                        loadedFixedGroups.add(groupName);
                        if (channels == null || channels.isEmpty()) {
                            LogBridge.w("ChannelPanel", "虎牙固定分组[" + groupName + "]无频道");
                            // 🔇 屏蔽"暂无频道"弹窗
                            // Toast.makeText(context, "「" + groupName + "」暂无频道", Toast.LENGTH_SHORT).show();
                            groupListManager.setGroups(channelSourceList, false);
                            return;
                        }
                        // 按 channelId / huyaRoomId 双重去重追加，避免与关键词路线重复
                        HashSet<String> existIds = new HashSet<>();
                        HashSet<Integer> existRooms = new HashSet<>();
                        for (Channel c : channelSourceList) {
                            if (c.getChannelId() != null) existIds.add(c.getChannelId());
                            if (c.getHuyaRoomId() > 0) existRooms.add(c.getHuyaRoomId());
                        }
                        int added = 0;
                        java.util.ArrayList<Integer> twRoomIds = new java.util.ArrayList<>();
                        for (Channel c : channels) {
                            if (c == null) continue;
                            if (c.getHuyaRoomId() > 0 && existRooms.contains(c.getHuyaRoomId())) continue;
                            if (c.getChannelId() != null && existIds.contains(c.getChannelId())) continue;
                            channelSourceList.add(c);
                            if (c.getChannelId() != null) existIds.add(c.getChannelId());
                            if (c.getHuyaRoomId() > 0) {
                                existRooms.add(c.getHuyaRoomId());
                                twRoomIds.add(c.getHuyaRoomId());
                            }
                            added++;
                        }
                        LogBridge.d("ChannelPanel", "虎牙固定分组[" + groupName + "]加载完成，新增 " + added + " 个频道，总计 " + channelSourceList.size());
                        // 后台并行预解析直播源，缩短起播时间
                        if (!twRoomIds.isEmpty()) {
                            com.tv.live.util.HuyaSDKParser.preloadRooms(twRoomIds);
                        }
                        // 刷新分组（分组已固定，固定分组频道不生成普通分组，仅"全部"计数更新）
                        groupListManager.setGroups(channelSourceList, false);
                        // 当前分组过滤显示
                        currentGroupChannelList.clear();
                        for (Channel c : channelSourceList) {
                            if (groupName.equals(GroupListManager.getNormalizedGroup(c))) {
                                currentGroupChannelList.add(c);
                            }
                        }
                        channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
                        lvChannelList.setSelection(0);
                    }

                    @Override
                    public void onError(String errMsg) {
                        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                        LogBridge.w("ChannelPanel", "虎牙固定分组[" + groupName + "]加载失败: " + errMsg);
                        // 🔇 屏蔽"加载失败/正在获取中"弹窗
                        // Toast.makeText(context, "「" + groupName + "」加载失败: " + errMsg, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void onGroupClicked(int position) {
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;
        // 🆕 虎牙一起看固定分组（虎牙电影/电视剧/动漫/综艺/一起看）：SDK 独立路线，异步拉取 + 缓存
        if (groupListManager.isFixedGroup(position)) {
            loadFixedGroupChannels(groupName);
            return;
        }
        if (GroupListManager.GROUP_ALL.equals(groupName)) {
            currentGroupChannelList.clear();
            currentGroupChannelList.addAll(channelSourceList);
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        } else {
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(GroupListManager.getNormalizedGroup(c))) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
        }

        if (channelListManager != null && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            int targetPos = -1;
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
                    targetPos = i;
                    break;
                }
            }
            if (targetPos >= 0) {
                lvChannelList.setSelection(targetPos);
            } else {
                lvChannelList.setSelection(0);
            }
        }
    }

    public String getCurrentGroupName() {
        return currentGroupName;
    }

    public List<Channel> getCurrentGroupChannels() {
        return currentGroupChannelList;
    }

    public void setEpgEnable(boolean enable) {
        this.epgEnable = enable;
    }

    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            return;
        }
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            return;
        }
        
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
            currentPlayIndex = channelSourceList.size() - 1;
            LogBridge.w("ChannelPanelController", "playPrev: currentPlayIndex 越界，已重置为最后一个有效索引 " + currentPlayIndex);
        }

        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = GroupListManager.getNormalizedGroup(currentChannel);
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(GroupListManager.getNormalizedGroup(c))) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) {
            return;
        }
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
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            return;
        }
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            return;
        }
        
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
            currentPlayIndex = channelSourceList.size() - 1;
            LogBridge.w("ChannelPanelController", "playNext: currentPlayIndex 越界，已重置为最后一个有效索引 " + currentPlayIndex);
        }

        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = GroupListManager.getNormalizedGroup(currentChannel);
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(GroupListManager.getNormalizedGroup(c))) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) {
            return;
        }
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

    public void switchUp() {
        lastSwitchDirection = "up";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) {
            playNext();
        } else {
            playPrev();
        }
    }

    public void switchDown() {
        lastSwitchDirection = "down";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) {
            playPrev();
        } else {
            playNext();
        }
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null) return;
        // 🟢 虎牙分组合并：用归一化后的组名，避免跳频道时切换到"电影_热门"等未出现在分组列表里的子组
        String channelGroup = GroupListManager.getNormalizedGroup(ch);
        if (channelGroup != null && !channelGroup.isEmpty()) {
            if (!channelGroup.equals(currentGroupName)) {
                currentGroupName = channelGroup;
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (channelGroup.equals(GroupListManager.getNormalizedGroup(c))) {
                        currentGroupChannelList.add(c);
                    }
                }
                int groupPos = groupListManager.getGroupPosition(channelGroup);
                groupListManager.setSelectedPosition(groupPos);
            }
        }
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, index);
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        }
        channelListManagerEpg.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 已移除所有 setFocusable / requestFocus，让 ListView 自身管理焦点

        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
        }
    }

    private boolean handleChannelLongClick(String channelName, boolean isRightPanel) {
        return false;
    }

    private void onChannelClicked(int position) {
        Channel selectedChannel = null;

        if (!currentGroupChannelList.isEmpty() && position < currentGroupChannelList.size()
                && !rightPanelOpen) {
            selectedChannel = currentGroupChannelList.get(position);
        } else if (position < channelSourceList.size()) {
            selectedChannel = channelSourceList.get(position);
        }

        if (selectedChannel == null) return;

        // 正常播放流程（虎牙一起看频道的 mainPlayUrl 为 huya://room/ 协议，由 TVPlayerManager 识别并解析）
        int globalIndex = channelSourceList.indexOf(selectedChannel);
        if (globalIndex != -1) {
            lastSwitchDirection = "";
            isSwitchingChannel = false;
            autoSkipCount = 0;
            playChannel(globalIndex);
            togglePanel();
        }
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
    }

    public void togglePanel() {
        boolean willOpen = !isPanelOpen();

        if (willOpen) {
            // 确保 OK 键只打开左侧面板，关闭右侧面板
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.GONE);
            }
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = false;
            epgPanelOpen = false;

            if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                    || currentGroupName.isEmpty()
                    || currentGroupChannelList.isEmpty()) {
                currentGroupName = GroupListManager.GROUP_ALL;
                currentGroupChannelList.clear();
                currentGroupChannelList.addAll(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
            } else {
                channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
            }
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
        }

        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);

        panelLayout.postDelayed(() -> {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                LogBridge.d("ChannelPanelController", "togglePanel postDelayed: Activity已销毁，取消操作");
                return;
            }

            if (isPanelOpen()) {
                // ✅ 打开面板时，主动把焦点交给频道列表
                lvChannelList.requestFocus();
                // 滚动到当前播放频道
                lvChannelList.setSelection(getChannelListSelection());
            } else {
                // ❌ 移除 clearFocus，让焦点自然回到播放器
                // panelLayout.clearFocus();
            }
        }, 100);

        if (panelStateListener != null) {
            panelStateListener.onPanelStateChanged(willOpen);
        }
    }

    public void showPanel() {
        if (!isPanelOpen()) {
            togglePanel();
        }
    }

    public void hidePanel() {
        if (isPanelOpen()) {
            togglePanel();
        }
    }

    public boolean isPanelOpen() {
        return panelLayout.getVisibility() == View.VISIBLE;
    }

    public void handleFirstLaunch() {
        mIsFirstLaunch = false;
    }

    public boolean isFirstLaunch() {
        return mIsFirstLaunch;
    }

    public boolean isRightPanelOpen() {
        return rightPanelOpen;
    }

    private void onEpgButtonClicked() {
        if (!epgEnable) {
            return;
        }
        if (!rightPanelOpen) {
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.GONE);
            }
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = true;
            epgPanelOpen = true;
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
            panelLayout.post(() -> {
                lvChannelListEpg.requestFocus();
                LogBridge.d("ChannelPanel", "Right panel opened, focus on channel list");
            });
        } else {
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.GONE);
            }
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = false;
            epgPanelOpen = false;
            // ✅ 切回左侧时，把焦点交给频道列表
            panelLayout.post(() -> lvChannelList.requestFocus());
        }
    }

    public boolean backFromRightPanel() {
        if (rightPanelOpen) {
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            panelLayout.post(() -> {
                if (lvChannelList != null) lvChannelList.requestFocus();
            });
            return true;
        }
        return false;
    }

    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            // ✅ 切回左侧时，把焦点交给频道列表
            panelLayout.post(() -> lvChannelList.requestFocus());
        }
    }

    public boolean isEpgPanelOpen() {
        return epgPanelOpen;
    }

    public void setCurrentDateIndex(int index) {
        this.currentSelectedDateIndex = index;
        panelManager.setCurrentDateIndex(index);
        if (!channelSourceList.isEmpty()
                && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel curr = channelSourceList.get(currentPlayIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
        }
    }

    public int getCurrentSelectedDateIndex() {
        return currentSelectedDateIndex;
    }

    private int getChannelListSelection() {
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            return currentPlayIndex;
        } else {
            if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
                return 0;
            }
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
                    return i;
                }
            }
            return 0;
        }
    }

    public void onPlaySuccess() {
        isSwitchingChannel = false;
        autoSkipCount = 0;
    }

    public boolean canAutoSkip() {
        return isSwitchingChannel
                && !"".equals(lastSwitchDirection)
                && autoSkipCount < MAX_AUTO_SKIP;
    }

    public boolean autoSkipFailedChannel() {
        if (!canAutoSkip()) {
            return false;
        }
        autoSkipCount++;
        if ("up".equals(lastSwitchDirection)) {
            if (isReverse) {
                playNext();
            } else {
                playPrev();
            }
        } else if ("down".equals(lastSwitchDirection)) {
            if (isReverse) {
                playPrev();
            } else {
                playNext();
            }
        }
        return true;
    }

    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
    }

    public boolean isReverse() {
        return isReverse;
    }

    // dispatchKeyEvent 已删除，现在由 MainActivity 转发

    public void clearPanelFocus() {
        // ❌ 不再清除焦点，避免遥控器断连
        // if (panelLayout != null) {
        //     panelLayout.clearFocus();
        // }
    }

    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    public void release() {
        LogBridge.d("ChannelPanelController", "release: 级联清理所有组件引用");

        loadedFixedGroups.clear();
        if (groupListManager != null) {
            groupListManager.release();
            groupListManager = null;
        }
        if (channelListManager != null) {
            channelListManager.release();
            channelListManager = null;
        }
        if (channelListManagerEpg != null) {
            channelListManagerEpg.release();
            channelListManagerEpg = null;
        }
        if (dateListManager != null) {
            dateListManager.release();
            dateListManager = null;
        }
        if (epgManagerWrapper != null) {
            epgManagerWrapper.release();
            epgManagerWrapper = null;
        }
        if (panelManager != null) {
            panelManager = null;
        }

        channelChangeListener = null;
        panelStateListener = null;

        if (channelSourceList != null) {
            channelSourceList.clear();
            channelSourceList = null;
        }
        if (currentGroupChannelList != null) {
            currentGroupChannelList.clear();
            currentGroupChannelList = null;
        }

        if (lvGroup != null) {
            lvGroup.setAdapter(null);
            lvGroup.setOnItemClickListener(null);
        }
        if (lvChannelList != null) {
            lvChannelList.setAdapter(null);
            lvChannelList.setOnItemClickListener(null);
        }
        if (lvChannelListEpg != null) {
            lvChannelListEpg.setAdapter(null);
            lvChannelListEpg.setOnItemClickListener(null);
        }
        if (lvDate != null) {
            lvDate.setAdapter(null);
            lvDate.setOnItemClickListener(null);
        }
        if (lvEpg != null) {
            lvEpg.setAdapter(null);
            lvEpg.setOnItemClickListener(null);
        }
        if (btnShowEpg != null) {
            btnShowEpg.setOnClickListener(null);
        }
        if (btnBackGroup != null) {
            btnBackGroup.setOnClickListener(null);
        }

        this.activity = null;
        this.context = null;
    }
}
