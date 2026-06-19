package com.tv.live.manager;

import android.content.Context;
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
 * 2. 频道切换（上/下切台、分组内循环、防抖）
 * 3. 面板控制（显示/隐藏、EPG 展开/收起、列表点击）
 *
 * 【拆分来源】
 * 从 MainActivity 拆分合并而来，原分散在三个地方：
 * - GroupListManager 增强：分组选中、筛选逻辑
 * - ChannelController：频道切换逻辑
 * - PanelManager 增强：面板交互、列表点击逻辑
 *
 * 【五层逻辑闭环】
 * 1. 状态管理层：分组选中状态、面板显示状态、EPG 展开状态、当前播放索引
 * 2. 数据筛选层：按分组筛选频道列表、按日期筛选 EPG
 * 3. 状态同步层：分组切换→频道列表更新、切台→选中状态同步
 * 4. 异常兜底层：空列表兜底、索引越界保护、防抖保护
 * 5. 交互闭环层：点击、按键、手势都触发对应状态更新
 *
 * 【2026-06-19 新增：左右面板切换】
 * 原三栏式布局（分组+频道+节目单按钮）改为左右面板切换模式：
 * - 左侧面板：分组列表 + 频道列表（默认显示）
 * - 右侧面板：日期列表 + EPG节目单（默认隐藏）
 * - 点击节目单按钮：左右面板切换显示
 * - 返回键：先收起右侧面板，再关闭整个面板
 */
public class ChannelPanelController {

    // ====================== 常量 ======================
    /** 频道切换冷却时间（毫秒），300ms 内不允许连续切台 */
    private static final long CHANNEL_COOLDOWN = 300;

    // ====================== 上下文与视图 ======================
    private Context context;
    /** 面板根布局 */
    private View panelLayout;
    /** 分组列表 */
    private ListView lvGroup;
    /** 频道列表 */
    private ListView lvChannelList;
    /** 日期列表 */
    private ListView lvDate;
    /** EPG 节目列表 */
    private ListView lvEpg;
    /** 节目单展开按钮 */
    private TextView btnShowEpg;

    // ====================== 新增：左右面板切换 ======================
    /**
     * 左侧面板容器（分组 + 频道列表）
     *
     * 【作用】
     * 把分组列表和频道列表放在同一个容器里，统一管理显示/隐藏，
     * 并且统一设置背景样式（半透明黑色圆角）。
     *
     * 【显示时机】
     * - 默认显示
     * - 点击节目单按钮后隐藏
     * - 再次点击节目单按钮后重新显示
     */
    private View llLeftPanel;

    /**
     * 右侧面板容器（日期 + EPG节目单）
     *
     * 【作用】
     * 把日期列表和EPG列表放在同一个容器里，统一管理显示/隐藏，
     * 并且统一设置背景样式（半透明黑色圆角）。
     *
     * 【显示时机】
     * - 默认隐藏
     * - 点击节目单按钮后显示
     * - 再次点击节目单按钮后重新隐藏
     */
    private View llRightPanel;

    /**
     * 右侧面板是否展开
     *
     * 【作用】
     * 记录当前显示的是左侧面板还是右侧面板，
     * 点击节目单按钮时根据这个状态决定切换到哪个面板。
     *
     * 【值说明】
     * - false：显示左侧面板（分组+频道），隐藏右侧面板
     * - true：显示右侧面板（日期+EPG），隐藏左侧面板
     */
    private boolean rightPanelOpen = false;

    // ====================== 子管理器 ======================
    /** 分组列表管理器 */
    private GroupListManager groupListManager;
    /** 频道列表管理器 */
    private ChannelListManager channelListManager;
    /** 日期列表管理器 */
    private DateListManager dateListManager;
    /** EPG 节目单包装器 */
    private EpgManagerWrapper epgManagerWrapper;
    /** 面板管理器 */
    private PanelManager panelManager;

    // ====================== 数据状态 ======================
    /** 全部频道列表 */
    private List<Channel> channelSourceList = new ArrayList<>();
    /** 当前分组的频道列表（筛选后） */
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    /** 当前选中的分组名称 */
    private String currentGroupName = "";
    /** 当前播放的频道索引（全局索引） */
    private int currentPlayIndex = 0;
    /** 当前选中的日期索引 */
    private int currentSelectedDateIndex = 0;

    // ====================== 面板状态 ======================
    /**
     * EPG 面板是否展开
     *
     * 【注意】
     * 这个变量是为了兼容旧代码保留的，现在和 rightPanelOpen 保持同步。
     * 因为右侧面板展开就意味着EPG面板展开了。
     * 其他地方如果调用 isEpgPanelOpen()，返回的就是这个值。
     */
    private boolean epgPanelOpen = false;
    /** EPG 功能是否启用 */
    private boolean epgEnable = true;

    // ====================== 切台防抖 ======================
    /** 上次频道切换时间 */
    private long lastChannelChangeTime = 0;

    // ====================== 回调监听器 ======================
    /** 频道切换监听器 */
    private OnChannelChangeListener channelChangeListener;
    /** 面板状态监听器 */
    private OnPanelStateListener panelStateListener;

    // ====================== 接口定义 ======================
    /**
     * 频道切换监听器
     * 当用户切换频道时回调，让外部（MainActivity）去实际播放
     */
    public interface OnChannelChangeListener {
        /**
         * 频道切换了
         *
         * @param channel 选中的频道
         * @param index   全局索引
         */
        void onChannelChanged(Channel channel, int index);
    }

    /**
     * 面板状态监听器
     * 当面板显示/隐藏时回调
     */
    public interface OnPanelStateListener {
        /**
         * 面板状态变化了
         *
         * @param isOpen 是否打开
         */
        void onPanelStateChanged(boolean isOpen);
    }

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     *
     * @param context           上下文
     * @param panelLayout       面板根布局
     * @param llLeftPanel       左侧面板容器（分组 + 频道列表）【2026-06-19 新增】
     * @param llRightPanel      右侧面板容器（日期 + EPG）【2026-06-19 新增】
     * @param lvGroup           分组列表
     * @param lvChannelList     频道列表
     * @param lvDate            日期列表
     * @param lvEpg             EPG 节目列表
     * @param btnShowEpg        节目单展开按钮
     * @param groupListManager  分组列表管理器
     * @param channelListManager 频道列表管理器
     * @param dateListManager   日期列表管理器
     * @param epgManagerWrapper EPG 包装器
     * @param panelManager      面板管理器
     */
    public ChannelPanelController(
            Context context,
            View panelLayout,
            View llLeftPanel,        // 【2026-06-19 新增：左侧面板容器】
            View llRightPanel,       // 【2026-06-19 新增：右侧面板容器】
            ListView lvGroup,
            ListView lvChannelList,
            ListView lvDate,
            ListView lvEpg,
            TextView btnShowEpg,
            GroupListManager groupListManager,
            ChannelListManager channelListManager,
            DateListManager dateListManager,
            EpgManagerWrapper epgManagerWrapper,
            PanelManager panelManager
    ) {
        this.context = context.getApplicationContext();
        this.panelLayout = panelLayout;
        this.llLeftPanel = llLeftPanel;        // 【2026-06-19 新增：保存左侧面板引用】
        this.llRightPanel = llRightPanel;      // 【2026-06-19 新增：保存右侧面板引用】
        this.lvGroup = lvGroup;
        this.lvChannelList = lvChannelList;
        this.lvDate = lvDate;
        this.lvEpg = lvEpg;
        this.btnShowEpg = btnShowEpg;
        this.groupListManager = groupListManager;
        this.channelListManager = channelListManager;
        this.dateListManager = dateListManager;
        this.epgManagerWrapper = epgManagerWrapper;
        this.panelManager = panelManager;

        // 初始化点击事件
        initClickListeners();
    }

    // ====================================================================
    // 1. 初始化点击事件
    // ====================================================================

    /**
     * 初始化所有点击事件
     * 包括：分组列表点击、频道列表点击、EPG 展开按钮点击
     */
    private void initClickListeners() {
        // ===== 分组列表点击事件 =====
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onGroupClicked(position);
            }
        });

        // ===== 频道列表点击事件 =====
        lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });

        // ===== EPG 展开按钮点击事件 =====
        btnShowEpg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onEpgButtonClicked();
            }
        });
    }

    // ====================================================================
    // 2. 分组管理相关
    // ====================================================================

    /**
     * 设置频道列表（同时更新分组）
     *
     * @param channels 全部频道列表
     */
    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;
        // 更新分组列表
        groupListManager.setGroups(channels);
        // 更新频道列表（全部频道）
        channelListManager.setChannels(channels, currentPlayIndex);
    }

    /**
     * 分组被点击了
     *
     * @param position 分组位置
     */
    private void onGroupClicked(int position) {
        // 更新分组选中高亮
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);

        // 保存当前分组名称
        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;

        // 筛选当前分组的频道
        currentGroupChannelList.clear();
        for (Channel c : channelSourceList) {
            if (groupName.equals(c.getGroup())) {
                currentGroupChannelList.add(c);
            }
        }

        // 更新频道列表（按分组筛选）
        channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);

        SettingsActivity.logOperation("【分组】选中分组：" + groupName
                + "，频道数：" + currentGroupChannelList.size());
    }

    /**
     * 获取当前选中的分组名称
     *
     * @return 分组名称
     */
    public String getCurrentGroupName() {
        return currentGroupName;
    }

    /**
     * 获取当前分组的频道列表
     *
     * @return 当前分组的频道列表
     */
    public List<Channel> getCurrentGroupChannels() {
        return currentGroupChannelList;
    }

    /**
     * 设置 EPG 功能是否启用
     *
     * @param enable 是否启用
     */
    public void setEpgEnable(boolean enable) {
        this.epgEnable = enable;
    }

    // ====================================================================
    // 3. 频道切换相关
    // ====================================================================

    /**
     * 播放上一个频道（分组内循环）
     */
    public void playPrev() {
        // 防抖检查
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        if (channelSourceList == null || channelSourceList.isEmpty()) return;

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

        if (groupChannels.size() <= 1) return;

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
            playChannel(globalIndex);
        }
    }

    /**
     * 播放下一个频道（分组内循环）
     */
    public void playNext() {
        // 防抖检查
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        if (channelSourceList == null || channelSourceList.isEmpty()) return;

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

        if (groupChannels.size() <= 1) return;

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
            playChannel(globalIndex);
        }
    }

    /**
     * 播放指定索引的频道
     *
     * @param index 全局索引
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 索引越界保护
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;

        Channel ch = channelSourceList.get(index);
        if (ch == null) return;

        // 更新频道列表的选中状态
        if (!currentGroupName.isEmpty() && !currentGroupChannelList.isEmpty()) {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        } else {
            channelListManager.setChannels(channelSourceList, index);
        }

        // 刷新 EPG
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 回调给外部（MainActivity）去实际播放
        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
        }
    }

    /**
     * 频道列表被点击了
     *
     * @param position 点击的位置
     */
    private void onChannelClicked(int position) {
        if (!currentGroupChannelList.isEmpty() && position < currentGroupChannelList.size()) {
            // 分组筛选模式
            Channel selectedChannel = currentGroupChannelList.get(position);
            int globalIndex = channelSourceList.indexOf(selectedChannel);
            if (globalIndex != -1) {
                SettingsActivity.logOperation("【列表】点击频道：" + selectedChannel.getName());
                playChannel(globalIndex);
                togglePanel();  // 点击后关闭面板
            }
        } else {
            // 全部频道模式
            if (position < channelSourceList.size()) {
                Channel ch = channelSourceList.get(position);
                SettingsActivity.logOperation("【列表】点击频道：" + ch.getName());
                playChannel(position);
                togglePanel();  // 点击后关闭面板
            }
        }
    }

    /**
     * 获取当前播放的频道索引
     *
     * @return 全局索引
     */
    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    /**
     * 设置当前播放的频道索引
     *
     * @param index 全局索引
     */
    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
    }

    /**
     * 设置数字选台的总频道数
     *
     * @param count 总频道数
     */
    public void setTotalChannelCount(int count) {
        // 这个方法主要是给 ChannelNumberManager 用的
        // 如果需要可以在这里加逻辑
    }

    // ====================================================================
    // 4. 面板控制相关
    // ====================================================================

    /**
     * 切换面板显示/隐藏
     */
    public void togglePanel() {
        // 先更新频道列表选中状态
        if (!currentGroupName.isEmpty() && !currentGroupChannelList.isEmpty()) {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        } else {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        }

        boolean isOpen = isPanelOpen();
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);

        // 回调状态变化
        if (panelStateListener != null) {
            panelStateListener.onPanelStateChanged(!isOpen);
        }

        SettingsActivity.logOperation("【面板】" + (isOpen ? "关闭" : "打开") + "频道面板");
    }

    /**
     * 显示面板
     */
    public void showPanel() {
        if (!isPanelOpen()) {
            togglePanel();
        }
    }

    /**
     * 隐藏面板
     */
    public void hidePanel() {
        if (isPanelOpen()) {
            togglePanel();
        }
    }

    /**
     * 面板是否打开
     *
     * @return 是否打开
     */
    public boolean isPanelOpen() {
        return panelLayout.getVisibility() == View.VISIBLE;
    }

    /**
     * 节目单按钮被点击了
     *
     * 【2026-06-19 修改：左右面板切换模式】
     * 原逻辑：单独控制日期列表和EPG列表的显示/隐藏
     * 新逻辑：切换左右两个面板容器的显示/隐藏
     *
     * 【交互逻辑】
     * 点击后切换左右面板：
     * - 左侧面板显示时 → 收起左侧，展开右侧（日期+EPG）
     * - 右侧面板显示时 → 收起右侧，展开左侧（分组+频道）
     *
     * 【为什么要改成左右面板切换？】
     * 1. 视觉上更统一：左右两个面板各自有完整的背景和圆角
     * 2. 交互更清晰：点击节目单按钮就是切换到节目单页面
     * 3. 更节省空间：不需要同时显示分组和日期
     * 4. 更符合电视端的交互习惯
     */
    private void onEpgButtonClicked() {
        if (!epgEnable) {
            // EPG 功能已关闭
            SettingsActivity.logOperation("【EPG】节目单功能已关闭，无法展开");
            return;
        }

        if (!rightPanelOpen) {
            // ============================================================
            // ✅ 状态：左侧面板显示 → 切换到右侧面板
            // ============================================================

            // 隐藏左侧面板（分组 + 频道列表）
            llLeftPanel.setVisibility(View.GONE);
            // 显示右侧面板（日期 + EPG节目单）
            llRightPanel.setVisibility(View.VISIBLE);
            // 更新状态标记
            rightPanelOpen = true;
            // 同步更新 epgPanelOpen，保持旧接口兼容
            epgPanelOpen = true;

            SettingsActivity.logOperation("【面板】展开节目单面板");

            // 【为什么要在这里刷新EPG？】
            // 因为右侧面板刚显示出来，EPG数据可能还没加载，
            // 或者是之前的数据已经过期了，需要刷新一下当前频道的节目单。
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        } else {
            // ============================================================
            // ✅ 状态：右侧面板显示 → 切换回左侧面板
            // ============================================================

            // 隐藏右侧面板（日期 + EPG节目单）
            llRightPanel.setVisibility(View.GONE);
            // 显示左侧面板（分组 + 频道列表）
            llLeftPanel.setVisibility(View.VISIBLE);
            // 更新状态标记
            rightPanelOpen = false;
            // 同步更新 epgPanelOpen，保持旧接口兼容
            epgPanelOpen = false;

            SettingsActivity.logOperation("【面板】收起节目单面板");
        }
    }

    /**
     * EPG 面板是否展开
     *
     * @return 是否展开
     *
     * 【注意】
     * 这个方法是为了兼容旧代码保留的，现在和 rightPanelOpen 保持同步。
     * 因为右侧面板展开就意味着EPG面板展开了。
     */
    public boolean isEpgPanelOpen() {
        return epgPanelOpen;
    }

    /**
     * 设置当前选中的日期索引
     *
     * @param index 日期索引
     */
    public void setCurrentDateIndex(int index) {
        this.currentSelectedDateIndex = index;
        panelManager.setCurrentDateIndex(index);
        // 如果有数据，刷新 EPG
        if (!channelSourceList.isEmpty()
                && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel curr = channelSourceList.get(currentPlayIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
        }
    }

    /**
     * 获取当前选中的日期索引
     *
     * @return 日期索引
     */
    public int getCurrentSelectedDateIndex() {
        return currentSelectedDateIndex;
    }

    // ====================================================================
    // 5. 返回键处理
    // ====================================================================

    /**
     * 处理返回键
     *
     * 【2026-06-19 优化：分级返回逻辑】
     * 原逻辑：只要面板打开，按返回键就直接关闭整个面板
     * 新逻辑：先收起右侧面板，再按一次才关闭整个面板
     *
     * 【交互逻辑】
     * 1. 如果右侧面板（节目单）展开着 → 先收起节目单，回到频道列表
     * 2. 如果只有左侧面板（频道列表）打开着 → 关闭整个面板
     *
     * 【为什么要分级返回？】
     * 更符合电视端的交互习惯，用户不会因为误按返回键就直接退出整个面板，
     * 而是一步一步返回，体验更好。
     *
     * @return 是否处理了返回键（true=已处理，不退出）
     */
    public boolean handleBackPressed() {
        if (isPanelOpen()) {
            // 如果右侧面板展开着，先收起右侧面板（回到频道列表）
            if (rightPanelOpen) {
                onEpgButtonClicked();  // 复用节目单按钮的切换逻辑
                return true;
            }
            // 否则关闭整个面板
            hidePanel();
            return true;
        }
        return false;  // 没处理，让外部（MainActivity）处理
    }

    // ====================================================================
    // 6. 监听器设置
    // ====================================================================

    /**
     * 设置频道切换监听器
     *
     * @param listener 监听器
     */
    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    /**
     * 设置面板状态监听器
     *
     * @param listener 监听器
     */
    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    // ====================================================================
    // 7. 资源释放
    // ====================================================================

    /**
     * 释放资源
     * Activity onDestroy 时调用
     */
    public void release() {
        // 清空引用，避免内存泄漏
        context = null;
        panelLayout = null;
        llLeftPanel = null;          // 【2026-06-19 新增：释放左侧面板引用】
        llRightPanel = null;         // 【2026-06-19 新增：释放右侧面板引用】
        lvGroup = null;
        lvChannelList = null;
        lvDate = null;
        lvEpg = null;
        btnShowEpg = null;
        channelSourceList = null;
        currentGroupChannelList = null;
        channelChangeListener = null;
        panelStateListener = null;
    }
}
