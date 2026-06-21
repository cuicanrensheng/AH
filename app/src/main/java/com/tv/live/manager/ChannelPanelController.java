package com.tv.live.manager;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.SettingsActivity;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道面板控制器
 *
 * 【职责】
 * 统一管理所有和频道面板相关的逻辑，包括：
 * 1. 分组管理（分组列表、选中状态、分组筛选）
 * 2. 频道切换（上/下切台、分组内循环、防抖、反转）
 * 3. 面板控制（显示/隐藏、EPG 展开/收起、列表点击）
 * 4. 焦点管理（手机触屏 + 电视遥控器）
 * 5. 按键处理（左右键移动焦点、OK键选中）
 *
 * 【2026-06-20 修复：换台反转功能 + 详细操作日志】
 * 【问题原因】
 * 之前反转逻辑只在 MainActivity 的 handleDirectionKey() 里，
 * 其他切台入口直接调用 playPrev()/playNext()，不考虑反转设置，
 * 导致反转在某些场景下失效，而且没有日志很难排查。
 *
 * 【解决方案】
 * 1. 在 ChannelPanelController 里统一管理反转逻辑
 * 2. 新增 switchUp() / switchDown() 带反转的统一入口
 * 3. 所有切台相关方法都加上详细的操作日志
 * 4. 日志包括：入口、反转状态、实际方向、索引变化、频道名称
 *
 * 【2026-06-20 新增：isRightPanelOpen() 方法】
 * 【为什么加这个方法？】
 * TvRemoteManager 需要知道当前是左侧面板还是右侧面板，
 * 才能正确处理左右键的列切换逻辑。
 * 所以新增这个 public 方法，供外部调用。
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

    /**
     * 是否开启换台反转
     * 默认 false = 不反转
     */
    private boolean isReverse = false;

    /**
     * 设置是否开启换台反转
     */
    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
        SettingsActivity.logOperation("【设置】反转状态同步到 ChannelPanelController：" 
                + (reverse ? "开启" : "关闭"));
    }

    /**
     * 获取当前反转状态
     */
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

    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;
        groupListManager.setGroups(channels);
        channelListManager.setChannels(channels, currentPlayIndex);
        channelListManagerEpg.setChannels(channels, currentPlayIndex);
    }
        /**
     * 分组被点击了
     *
     * 【2026-06-21 修改：支持「全部」分组】
     */
    private void onGroupClicked(int position) {
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);

        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;

        // ✅ 新增：判断是不是「全部」分组
        if (GroupListManager.GROUP_ALL.equals(groupName)) {
            // 「全部」分组：显示所有频道
            currentGroupChannelList.clear();
            currentGroupChannelList.addAll(channelSourceList);
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
            SettingsActivity.logOperation("【分组】选中「全部」分组，频道数：" 
                    + channelSourceList.size());
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

    /**
     * 播放上一个频道（分组内循环）- 底层方法
     *
     * 【2026-06-20 新增：详细操作日志】
     * 记录底层方法调用，方便追踪是从哪里触发的切台。
     */
    public void playPrev() {
        // 防抖检查
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

        // 获取当前频道和分组
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();

        // 筛选当前分组的频道
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

        // 找到当前频道在分组中的索引
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;

        // 计算上一个频道的索引（分组内循环）
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

    /**
     * 播放下一个频道（分组内循环）- 底层方法
     *
     * 【2026-06-20 新增：详细操作日志】
     */
    public void playNext() {
        // 防抖检查
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

        // 获取当前频道和分组
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();

        // 筛选当前分组的频道
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

        // 找到当前频道在分组中的索引
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;

        // 计算下一个频道的索引（分组内循环）
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

    /**
     * 按上键时调用（自动考虑反转）
     *
     * 【2026-06-20 新增：详细操作日志】
     * 记录入口、反转状态、实际方向，方便分析反转是否生效。
     */
    public void switchUp() {
        SettingsActivity.logOperation("【切台】switchUp 上键 → 反转状态：" 
                + (isReverse ? "开启" : "关闭") 
                + " → 实际方向：" + (isReverse ? "下一台" : "上一台"));
        
        if (isReverse) {
            // 反转开启：上键 = 下一台
            playNext();
        } else {
            // 反转关闭：上键 = 上一台
            playPrev();
        }
    }

    /**
     * 按下键时调用（自动考虑反转）
     *
     * 【2026-06-20 新增：详细操作日志】
     */
    public void switchDown() {
        SettingsActivity.logOperation("【切台】switchDown 下键 → 反转状态：" 
                + (isReverse ? "开启" : "关闭") 
                + " → 实际方向：" + (isReverse ? "上一台" : "下一台"));
        
        if (isReverse) {
            // 反转开启：下键 = 上一台
            playPrev();
        } else {
            // 反转关闭：下键 = 下一台
            playNext();
        }
    }
        /**
     * 播放指定索引的频道
     *
     * 【2026-06-21 修改：同步分组时处理「全部」分组的情况】
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
            // 如果当前是「全部」分组，不用切换分组，只更新频道列表
            if (!GroupListManager.GROUP_ALL.equals(currentGroupName) 
                    && !channelGroup.equals(currentGroupName)) {
                // 不是「全部」且分组不一致 → 同步切换分组
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
            // 「全部」分组 或 未选分组 → 显示全部频道
            channelListManager.setChannels(channelSourceList, index);
        } else {
            // 普通分组 → 按分组筛选
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
        // ✅ 修改：处理「全部」分组
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) 
                || currentGroupName.isEmpty() 
                || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
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

    // ====================================================================
    // ✅ 新增：右侧面板是否打开（供 TvRemoteManager 使用）
    // ====================================================================

    /**
     * 右侧面板是否打开
     *
     * @return true=右侧面板打开，false=右侧面板关闭
     *
     * 【为什么需要这个方法？】
     * TvRemoteManager 需要知道当前是左侧面板还是右侧面板，
     * 才能正确处理左右键的列切换逻辑。
     *
     * 【状态来源】
     * 直接返回成员变量 rightPanelOpen，
     * 这个变量在 onEpgButtonClicked() 和 onBackGroupClicked() 中更新。
     *
     * 【什么时候调用？】
     * MainActivity 的 syncRemoteMode() 中会调用，
     * 用来同步遥控器管理器的面板状态。
     */
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
            // 「全部」分组 → 返回全局索引
            return currentPlayIndex;
        } else {
            // 普通分组 → 找在分组中的索引
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
        lvChannelList = null;
        lvChannelListEpg = null;
        lvDate = null;
        lvEpg = null;
        btnShowEpg = null;
        btnBackGroup = null;
        channelSourceList = null;
        currentGroupChannelList = null;
        channelChangeListener = null;
        panelStateListener = null;
        groupListManager = null;
        channelListManager = null;
        channelListManagerEpg = null;
        dateListManager = null;
        epgManagerWrapper = null;
        panelManager = null;
    }
}
