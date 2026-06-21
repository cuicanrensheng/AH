package com.tv.live.manager;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.SettingsActivity;
import com.tv.live.config.AppConfig;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道面板控制器
 *
 * 【2026-06-21 新增：收藏 + 最近观看 + 菜单键】
 * 【功能说明】
 * 1. 分组列表增加「收藏」和「最近观看」两个特殊分组
 * 2. 菜单键（KEYCODE_MENU）可以快速收藏/取消收藏当前频道
 * 3. 切换频道时自动添加到最近观看
 */
public class ChannelPanelController {
    // ====================== 常量 ======================
    /** 频道切换冷却时间（毫秒），300ms 内不允许连续切台 */
    private static final long CHANNEL_COOLDOWN = 300;
    // ====================== 上下文与视图 ======================
    private Context context;
    private View panelLayout;
    private ListView lvGroup;
    private ListView lvChannelList;
    private ListView lvChannelListEpg;
    private ListView lvDate;
    private ListView lvEpg;
    private TextView btnShowEpg;
    private TextView btnBackGroup;
    // ====================== 左右面板切换 ======================
    private View llLeftPanel;
    private View llRightPanel;
    private boolean rightPanelOpen = false;
    // ====================== 子管理器 ======================
    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private ChannelListManager channelListManagerEpg;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PanelManager panelManager;
    // ====================== 数据状态 ======================
    private List<Channel> channelSourceList = new ArrayList<>();
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    private String currentGroupName = "";
    private int currentPlayIndex = 0;
    private int currentSelectedDateIndex = 0;
    // ====================== 面板状态 ======================
    private boolean epgPanelOpen = false;
    private boolean epgEnable = true;
    // ====================================================================
    // 换台反转相关
    // ====================================================================
    private boolean isReverse = false;

    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
        SettingsActivity.logOperation("【设置】反转状态同步到 ChannelPanelController：" 
                + (reverse ? "开启" : "关闭"));
    }

    public boolean isReverse() {
        return isReverse;
    }
    // ====================== 切台防抖 ======================
    private long lastChannelChangeTime = 0;
    // ====================== 焦点管理 ======================
    private String currentFocusPanel = "left";
    private String leftFocusView = "channel";
    private String rightFocusView = "channel";
    // ====================== 回调监听器 ======================
    private OnChannelChangeListener channelChangeListener;
    private OnPanelStateListener panelStateListener;
    // ====================== 接口定义 ======================
    public interface OnChannelChangeListener {
        void onChannelChanged(Channel channel, int index);
    }
    public interface OnPanelStateListener {
        void onPanelStateChanged(boolean isOpen);
    }
    // ====================== 构造函数 ======================
    public ChannelPanelController(
            Context context,
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
        this.context = context.getApplicationContext();
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
        initFocusListeners();
    }
    // ====================================================================
    // 1. 初始化点击事件
    // ====================================================================
    private void initClickListeners() {
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onGroupClicked(position);
            }
        });
        lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });
        lvChannelListEpg.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });
        btnShowEpg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onEpgButtonClicked();
            }
        });
        btnBackGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackGroupClicked();
            }
        });
    }
    // ====================================================================
    // 初始化焦点变化监听
    // ====================================================================
    private void initFocusListeners() {
        lvGroup.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "group";
                }
            }
        });
        lvChannelList.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                }
            }
        });
        btnShowEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "epgBtn";
                }
            }
        });
        lvChannelListEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "channel";
                }
            }
        });
        lvDate.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "date";
                }
            }
        });
        lvEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "epg";
                }
            }
        });
        btnBackGroup.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "backBtn";
                }
            }
        });
    }
    // ====================================================================
    // 2. 分组管理相关
    // ====================================================================
    /**
     * 设置频道列表
     *
     * 【2026-06-21 修改：初始化时获取收藏和最近观看数量】
     */
    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;

        // ✅ 新增：获取收藏和最近观看的数量
        int favoriteCount = 0;
        int recentCount = 0;
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();
            // 计算实际存在的频道数量
            for (String name : favorites) {
                for (Channel c : channels) {
                    if (name.equals(c.getName())) {
                        favoriteCount++;
                        break;
                    }
                }
            }
            for (String name : recent) {
                for (Channel c : channels) {
                    if (name.equals(c.getName())) {
                        recentCount++;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }

        // ✅ 修改：传入收藏和最近观看数量
        groupListManager.setGroups(channels, favoriteCount, recentCount);
        channelListManager.setChannels(channels, currentPlayIndex);
        channelListManagerEpg.setChannels(channels, currentPlayIndex);
    }

    /**
     * 分组被点击了
     *
     * 【2026-06-21 修改：支持「全部」「收藏」「最近观看」三个特殊分组】
     */
    private void onGroupClicked(int position) {
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);

        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;

        if (GroupListManager.GROUP_ALL.equals(groupName)) {
            // 「全部」分组：显示所有频道
            currentGroupChannelList.clear();
            currentGroupChannelList.addAll(channelSourceList);
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
            SettingsActivity.logOperation("【分组】选中「全部」，频道数：" + channelSourceList.size());
        } else if (GroupListManager.GROUP_FAVORITE.equals(groupName)) {
            // ✅ 新增：「收藏」分组
            currentGroupChannelList.clear();
            try {
                AppConfig appConfig = AppConfig.getInstance(context);
                List<String> favorites = appConfig.getFavoriteChannels();
                for (String name : favorites) {
                    for (Channel c : channelSourceList) {
                        if (name.equals(c.getName())) {
                            currentGroupChannelList.add(c);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略
            }
            // 用筛选后的列表刷新
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
            SettingsActivity.logOperation("【分组】选中「收藏」，频道数：" + currentGroupChannelList.size());
        } else if (GroupListManager.GROUP_RECENT.equals(groupName)) {
            // ✅ 新增：「最近观看」分组
            currentGroupChannelList.clear();
            try {
                AppConfig appConfig = AppConfig.getInstance(context);
                List<String> recent = appConfig.getRecentChannels();
                for (String name : recent) {
                    for (Channel c : channelSourceList) {
                        if (name.equals(c.getName())) {
                            currentGroupChannelList.add(c);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略
            }
            // 用筛选后的列表刷新
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
            SettingsActivity.logOperation("【分组】选中「最近观看」，频道数：" + currentGroupChannelList.size());
        } else {
            // 普通分组：按分组筛选
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            SettingsActivity.logOperation("【分组】选中分组：" + groupName
                    + "，频道数：" + currentGroupChannelList.size());
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
    // ====================================================================
    // 3. 频道切换相关（核心）
    // ====================================================================
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            SettingsActivity.logOperation("【切台】playPrev 防抖拦截，距离上次：" 
                    + (now - lastChannelChangeTime) + "ms");
            return;
        }
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            SettingsActivity.logOperation("【切台】playPrev 失败：频道列表为空");
            return;
        }
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) {
            SettingsActivity.logOperation("【切台】playPrev 失败：分组内只有1个频道");
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
            SettingsActivity.logOperation("【切台】playPrev 上一台 → " 
                    + currentPlayIndex + " → " + globalIndex 
                    + "（" + prevChannel.getName() + "）");
            playChannel(globalIndex);
        }
    }

    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            SettingsActivity.logOperation("【切台】playNext 防抖拦截，距离上次：" 
                    + (now - lastChannelChangeTime) + "ms");
            return;
        }
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            SettingsActivity.logOperation("【切台】playNext 失败：频道列表为空");
            return;
        }
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) {
            SettingsActivity.logOperation("【切台】playNext 失败：分组内只有1个频道");
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
            SettingsActivity.logOperation("【切台】playNext 下一台 → " 
                    + currentPlayIndex + " → " + globalIndex 
                    + "（" + nextChannel.getName() + "）");
            playChannel(globalIndex);
        }
    }
    // ====================================================================
    // 带反转的切台方法（统一入口）
    // ====================================================================
    public void switchUp() {
        SettingsActivity.logOperation("【切台】switchUp 上键 → 反转状态：" 
                + (isReverse ? "开启" : "关闭") 
                + " → 实际方向：" + (isReverse ? "下一台" : "上一台"));
        if (isReverse) {
            playNext();
        } else {
            playPrev();
        }
    }

    public void switchDown() {
        SettingsActivity.logOperation("【切台】switchDown 下键 → 反转状态：" 
                + (isReverse ? "开启" : "关闭") 
                + " → 实际方向：" + (isReverse ? "上一台" : "下一台"));
        if (isReverse) {
            playPrev();
        } else {
            playNext();
        }
    }

    /**
     * 播放指定索引的频道
     *
     * 【2026-06-21 修改：同步分组时处理特殊分组的情况】
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null) return;

        // 切换频道后同步分组选中状态
        String channelGroup = ch.getGroup();
        if (channelGroup != null && !channelGroup.isEmpty()) {
            // 如果当前是特殊分组（全部/收藏/最近观看），不用切换分组
            boolean isSpecialGroup = GroupListManager.GROUP_ALL.equals(currentGroupName)
                    || GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                    || GroupListManager.GROUP_RECENT.equals(currentGroupName)
                    || currentGroupName.isEmpty();
            if (!isSpecialGroup && !channelGroup.equals(currentGroupName)) {
                // 不是特殊分组且分组不一致 → 同步切换分组
                currentGroupName = channelGroup;
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (channelGroup.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                int groupPos = groupListManager.getGroupPosition(channelGroup);
                groupListManager.setSelectedPosition(groupPos);
            }
        }

        // 更新主页面频道列表的选中状态
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) 
                || currentGroupName.isEmpty() 
                || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, index);
        } else if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                || GroupListManager.GROUP_RECENT.equals(currentGroupName)) {
            // ✅ 新增：特殊分组用筛选后的列表
            channelListManager.setFilteredChannels(currentGroupChannelList, ch.getName());
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        }

        // 同步更新节目单页面的频道列表选中状态
        channelListManagerEpg.setChannels(channelSourceList, index);

        // 刷新 EPG
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 回调给外部（MainActivity）去实际播放
        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
        }

        // ✅ 新增：添加到最近观看
        addToRecent(ch.getName());
    }

    /**
     * 添加到最近观看
     *
     * 【说明】
     * 切换频道时自动调用，添加到最近观看列表，
     * 并更新分组列表的数量显示。
     */
    private void addToRecent(String channelName) {
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            appConfig.addRecentChannel(channelName);
            // 更新分组列表的数量
            int favoriteCount = 0;
            int recentCount = 0;
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();
            for (String name : favorites) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) {
                        favoriteCount++;
                        break;
                    }
                }
            }
            for (String name : recent) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) {
                        recentCount++;
                        break;
                    }
                }
            }
            groupListManager.updateSpecialGroupCount(favoriteCount, recentCount);
        } catch (Exception e) {
            // 忽略错误
        }
    }

    /**
     * ✅ 2026-06-21 新增：切换当前频道的收藏状态（菜单键调用）
     *
     * @return 操作后的状态（true=已收藏，false=已取消）
     */
    public boolean toggleCurrentFavorite() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return false;
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) return false;

        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        if (currentChannel == null) return false;

        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            boolean isFavorite = appConfig.toggleFavorite(currentChannel.getName());

            // 更新分组列表的数量
            int favoriteCount = 0;
            int recentCount = 0;
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();
            for (String name : favorites) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) {
                        favoriteCount++;
                        break;
                    }
                }
            }
            for (String name : recent) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) {
                        recentCount++;
                        break;
                    }
                }
            }
            groupListManager.updateSpecialGroupCount(favoriteCount, recentCount);

            // 如果当前在「收藏」分组，刷新列表
            if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)) {
                // 重新筛选收藏列表
                currentGroupChannelList.clear();
                for (String name : favorites) {
                    for (Channel c : channelSourceList) {
                        if (name.equals(c.getName())) {
                            currentGroupChannelList.add(c);
                            break;
                        }
                    }
                }
                channelListManager.setFilteredChannels(currentGroupChannelList, currentChannel.getName());
            }

            SettingsActivity.logOperation("【收藏】" + (isFavorite ? "添加" : "取消") 
                    + "收藏：" + currentChannel.getName());
            return isFavorite;
        } catch (Exception e) {
            SettingsActivity.logOperation("【收藏】操作失败：" + e.getMessage());
            return false;
        }
    }

    private void onChannelClicked(int position) {
        if (!currentGroupChannelList.isEmpty() && position < currentGroupChannelList.size()
                && !rightPanelOpen) {
            // 左侧面板（分组筛选模式）
            Channel selectedChannel = currentGroupChannelList.get(position);
            int globalIndex = channelSourceList.indexOf(selectedChannel);
            if (globalIndex != -1) {
                SettingsActivity.logOperation("【列表】点击频道：" + selectedChannel.getName());
                playChannel(globalIndex);
                togglePanel();
            }
        } else {
            // 右侧面板（全部频道模式）
            if (position < channelSourceList.size()) {
                Channel ch = channelSourceList.get(position);
                SettingsActivity.logOperation("【列表】点击频道：" + ch.getName());
                playChannel(position);
            }
        }
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
    }

    public void setTotalChannelCount(int count) {
        // 预留方法
    }
    // ====================================================================
    // 4. 面板控制相关
    // ====================================================================
    public void togglePanel() {
        // 处理特殊分组
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) 
                || currentGroupName.isEmpty() 
                || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        } else if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                || GroupListManager.GROUP_RECENT.equals(currentGroupName)) {
            // ✅ 新增：特殊分组
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        }
        channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
        boolean isOpen = isPanelOpen();
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
        if (!isOpen) {
            panelLayout.post(new Runnable() {
                @Override
                public void run() {
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                }
            });
        }
        if (panelStateListener != null) {
            panelStateListener.onPanelStateChanged(!isOpen);
        }
        SettingsActivity.logOperation("【面板】" + (isOpen ? "关闭" : "打开") + "频道面板");
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

    public boolean isRightPanelOpen() {
        return rightPanelOpen;
    }

    private void onEpgButtonClicked() {
        if (!epgEnable) {
            SettingsActivity.logOperation("【EPG】节目单功能已关闭，无法展开");
            return;
        }
        if (!rightPanelOpen) {
            llLeftPanel.setVisibility(View.GONE);
            llRightPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = true;
            epgPanelOpen = true;
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            llRightPanel.post(new Runnable() {
                @Override
                public void run() {
                    lvChannelListEpg.requestFocus();
                    lvChannelListEpg.setSelection(currentPlayIndex);
                }
            });
            SettingsActivity.logOperation("【面板】展开节目单面板");
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        } else {
            llRightPanel.setVisibility(View.GONE);
            llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            llLeftPanel.post(new Runnable() {
                @Override
                public void run() {
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                }
            });
            SettingsActivity.logOperation("【面板】收起节目单面板");
        }
    }

    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            llRightPanel.setVisibility(View.GONE);
            llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            llLeftPanel.post(new Runnable() {
                @Override
                public void run() {
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                }
            });
            SettingsActivity.logOperation("【面板】返回频道分组");
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
        } else if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                || GroupListManager.GROUP_RECENT.equals(currentGroupName)) {
            // ✅ 新增：特殊分组，找在筛选列表中的索引
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannelName)) {
                    return i;
                }
            }
            return 0;
        } else {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
                    return i;
                }
            }
            return 0;
        }
    }
    // ====================================================================
    // 5. 返回键处理
    // ====================================================================
    public boolean handleBackPressed() {
        if (isPanelOpen()) {
            if (rightPanelOpen) {
                onBackGroupClicked();
                return true;
            }
            hidePanel();
            return true;
        }
        return false;
    }
    // ====================================================================
    // 按键事件分发
    // ====================================================================
    public boolean dispatchKeyEvent(int keyCode) {
        if (!isPanelOpen()) {
            return false;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return handleLeftKey();
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return handleRightKey();
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return handleOkKey();
            // ✅ 新增：菜单键（收藏/取消收藏）
            case KeyEvent.KEYCODE_MENU:
                toggleCurrentFavorite();
                return true;
            default:
                return false;
        }
    }

    private boolean handleLeftKey() {
        if ("left".equals(currentFocusPanel)) {
            if ("epgBtn".equals(leftFocusView)) {
                lvChannelList.requestFocus();
                return true;
            } else if ("channel".equals(leftFocusView)) {
                lvGroup.requestFocus();
                return true;
            }
        } else if ("right".equals(currentFocusPanel)) {
            if ("epg".equals(rightFocusView)) {
                lvDate.requestFocus();
                return true;
            } else if ("date".equals(rightFocusView)) {
                lvChannelListEpg.requestFocus();
                return true;
            } else if ("channel".equals(rightFocusView)) {
                btnBackGroup.requestFocus();
                return true;
            }
        }
        return false;
    }

    private boolean handleRightKey() {
        if ("left".equals(currentFocusPanel)) {
            if ("group".equals(leftFocusView)) {
                lvChannelList.requestFocus();
                return true;
            } else if ("channel".equals(leftFocusView)) {
                btnShowEpg.requestFocus();
                return true;
            }
        } else if ("right".equals(currentFocusPanel)) {
            if ("backBtn".equals(rightFocusView)) {
                lvChannelListEpg.requestFocus();
                return true;
            } else if ("channel".equals(rightFocusView)) {
                lvDate.requestFocus();
                return true;
            } else if ("date".equals(rightFocusView)) {
                lvEpg.requestFocus();
                return true;
            }
        }
        return false;
    }

    private boolean handleOkKey() {
        if ("left".equals(currentFocusPanel)) {
            if ("group".equals(leftFocusView)) {
                int pos = lvGroup.getSelectedItemPosition();
                if (pos >= 0) {
                    onGroupClicked(pos);
                    return true;
                }
            } else if ("channel".equals(leftFocusView)) {
                int pos = lvChannelList.getSelectedItemPosition();
                if (pos >= 0) {
                    onChannelClicked(pos);
                    return true;
                }
            } else if ("epgBtn".equals(leftFocusView)) {
                onEpgButtonClicked();
                return true;
            }
        } else if ("right".equals(currentFocusPanel)) {
            if ("backBtn".equals(rightFocusView)) {
                onBackGroupClicked();
                return true;
            } else if ("channel".equals(rightFocusView)) {
                int pos = lvChannelListEpg.getSelectedItemPosition();
                if (pos >= 0) {
                    onChannelClicked(pos);
                    return true;
                }
            } else if ("date".equals(rightFocusView)) {
                int pos = lvDate.getSelectedItemPosition();
                if (pos >= 0) {
                    setCurrentDateIndex(pos);
                    return true;
                }
            } else if ("epg".equals(rightFocusView)) {
                int pos = lvEpg.getSelectedItemPosition();
                if (pos >= 0) {
                    return true;
                }
            }
        }
        return false;
    }
    // ====================================================================
    // 6. 监听器设置
    // ====================================================================
    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }
    // ====================================================================
    // 7. 资源释放
    // ====================================================================
    public void release() {
        context = null;
        panelLayout = null;
        llLeftPanel = null;
        llRightPanel = null;
        lvGroup = null;
        lvChannelList
