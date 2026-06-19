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
 * 【2026-06-19 优化：两个完整面板切换】
 * 原左右面板切换模式（只有日期+EPG）改为两个完整面板切换：
 * - 左侧面板：分组列表 + 频道列表 + 节目单按钮（默认显示）
 * - 右侧面板：返回按钮 + 频道列表 + 日期 + EPG（默认隐藏）
 * - 两个面板都有频道列表，切换时选中状态保持同步
 * - 节目单页面也能直接切换频道，不用切回去
 * - 点击节目单不关闭面板，方便连续查看多个频道的节目单
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
    /** 频道列表（主页面，左侧面板用） */
    private ListView lvChannelList;
    /**
     * ✅ 新增：频道列表（节目单页面，右侧面板用）
     *
     * 【作用】
     * 右侧面板（节目单页面）也有一个频道列表，用户在看节目单时
     * 可以直接切换频道，不用切回左侧面板。
     *
     * 【数据同步】
     * 和左侧面板的频道列表数据完全同步，切台时两边一起更新选中状态。
     */
    private ListView lvChannelListEpg;
    /** 日期列表 */
    private ListView lvDate;
    /** EPG 节目列表 */
    private ListView lvEpg;
    /** 节目单展开按钮（左侧面板最右边） */
    private TextView btnShowEpg;
    /**
     * ✅ 新增：返回分组按钮（右侧面板最左边）
     *
     * 【作用】
     * 右侧面板（节目单页面）最左边的返回按钮，点击后切回左侧面板（分组列表）。
     * 文字是竖排的"频道组"。
     *
     * 【为什么需要两个按钮？】
     * 因为两个面板是独立切换的，每个面板有自己的边缘按钮，
     * 切换面板时按钮跟着面板一起显示/隐藏，位置也跟着变。
     */
    private TextView btnBackGroup;

    // ====================== 左右面板切换 ======================
    /**
     * 左侧面板容器（分组 + 频道列表 + 节目单按钮）
     *
     * 【结构】
     * 水平排列：分组列表 + 频道列表 + 节目单按钮
     *
     * 【显示时机】
     * - 默认显示
     * - 点击"频道组"返回按钮后显示
     */
    private View llLeftPanel;
    /**
     * 右侧面板容器（返回按钮 + 频道列表 + 日期 + EPG）
     *
     * 【结构】
     * 水平排列：返回按钮 + 频道列表 + 日期列表 + EPG列表
     *
     * 【显示时机】
     * - 默认隐藏
     * - 点击"节目单"按钮后显示
     *
     * 【为什么右侧面板也有频道列表？】
     * 用户在看节目单的时候，经常需要切换频道看不同频道的节目单，
     * 如果每次都要切回左侧面板选频道，再切回右侧面板看节目单，
     * 操作太繁琐。所以右侧面板也放一个频道列表，直接切换，体验更好。
     */
    private View llRightPanel;
    /**
     * 右侧面板是否展开
     *
     * 【值说明】
     * - false：显示左侧面板（分组+频道+节目单按钮），隐藏右侧面板
     * - true：显示右侧面板（返回+频道+日期+EPG），隐藏左侧面板
     */
    private boolean rightPanelOpen = false;

    // ====================== 子管理器 ======================
    /** 分组列表管理器 */
    private GroupListManager groupListManager;
    /** 频道列表管理器（主页面，左侧面板用） */
    private ChannelListManager channelListManager;
    /**
     * ✅ 新增：频道列表管理器（节目单页面，右侧面板用）
     *
     * 【作用】
     * 管理右侧面板的频道列表，和左侧面板的频道列表管理器是两个独立的实例，
     * 分别管理各自的 ListView，但数据保持同步。
     *
     * 【为什么需要两个管理器？】
     * 因为有两个 ListView，每个 ListView 需要自己的 Adapter 和选中状态管理。
     * 两个管理器的数据来源相同，切台时同时更新两边的选中状态。
     */
    private ChannelListManager channelListManagerEpg;
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
     * @param context               上下文
     * @param panelLayout           面板根布局
     * @param llLeftPanel           左侧面板容器（分组 + 频道 + 节目单按钮）
     * @param llRightPanel          右侧面板容器（返回 + 频道 + 日期 + EPG）
     * @param lvGroup               分组列表
     * @param lvChannelList         频道列表（主页面，左侧面板用）
     * @param lvChannelListEpg      ✅ 新增：频道列表（节目单页面，右侧面板用）
     * @param lvDate                日期列表
     * @param lvEpg                 EPG 节目列表
     * @param btnShowEpg            节目单展开按钮（左侧面板最右边）
     * @param btnBackGroup          ✅ 新增：返回分组按钮（右侧面板最左边）
     * @param groupListManager      分组列表管理器
     * @param channelListManager    频道列表管理器（主页面）
     * @param channelListManagerEpg ✅ 新增：频道列表管理器（节目单页面）
     * @param dateListManager       日期列表管理器
     * @param epgManagerWrapper     EPG 包装器
     * @param panelManager          面板管理器
     */
    public ChannelPanelController(
            Context context,
            View panelLayout,
            View llLeftPanel,
            View llRightPanel,
            ListView lvGroup,
            ListView lvChannelList,
            ListView lvChannelListEpg,        // ✅ 新增
            ListView lvDate,
            ListView lvEpg,
            TextView btnShowEpg,
            TextView btnBackGroup,             // ✅ 新增
            GroupListManager groupListManager,
            ChannelListManager channelListManager,
            ChannelListManager channelListManagerEpg,  // ✅ 新增
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
        this.lvChannelListEpg = lvChannelListEpg;        // ✅ 新增：保存节目单页面频道列表引用
        this.lvDate = lvDate;
        this.lvEpg = lvEpg;
        this.btnShowEpg = btnShowEpg;
        this.btnBackGroup = btnBackGroup;                 // ✅ 新增：保存返回按钮引用
        this.groupListManager = groupListManager;
        this.channelListManager = channelListManager;
        this.channelListManagerEpg = channelListManagerEpg;  // ✅ 新增：保存节目单页面频道管理器
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
     * 包括：分组列表点击、两个频道列表点击、节目单按钮、返回按钮
     */
    private void initClickListeners() {
        // ===== 分组列表点击事件 =====
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onGroupClicked(position);
            }
        });

        // ===== 主页面频道列表点击事件（左侧面板） =====
        lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });

        // ================================================================
        // ✅ 新增：节目单页面频道列表点击事件（右侧面板）
        // ================================================================
        // 【为什么需要单独的点击事件？】
        // 因为有两个频道列表，分别在左右两个面板里，
        // 每个 ListView 都需要自己的点击监听器。
        // 但是两个列表的点击逻辑是一样的，都调用 onChannelClicked()。
        lvChannelListEpg.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });

        // ===== 节目单按钮点击事件 =====
        btnShowEpg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onEpgButtonClicked();
            }
        });

        // ================================================================
        // ✅ 新增：返回分组按钮点击事件
        // ================================================================
        // 【作用】
        // 点击右侧面板最左边的"频道组"按钮，切回左侧面板（分组列表）。
        // 和节目单按钮的作用相反，一个是展开节目单，一个是返回分组。
        btnBackGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackGroupClicked();
            }
        });
    }

    // ====================================================================
    // 2. 分组管理相关
    // ====================================================================
    /**
     * 设置频道列表（同时更新分组和两个频道列表）
     *
     * @param channels 全部频道列表
     */
    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;

        // 更新分组列表
        groupListManager.setGroups(channels);

        // 更新主页面频道列表（全部频道）
        channelListManager.setChannels(channels, currentPlayIndex);

        // ================================================================
        // ✅ 新增：同步更新节目单页面的频道列表
        // ================================================================
        // 【为什么要同步更新？】
        // 因为有两个频道列表，数据必须保持一致。
        // 节目单页面的频道列表永远显示全部频道，不按分组筛选，
        // 因为右侧面板没有分组列表，只有全部频道。
        channelListManagerEpg.setChannels(channels, currentPlayIndex);
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

        // 更新主页面频道列表（按分组筛选）
        channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);

        // ================================================================
        // ✅ 说明：节目单页面的频道列表不筛选分组
        // ================================================================
        // 【为什么不筛选？】
        // 因为右侧面板（节目单页面）没有分组列表，用户无法切换分组，
        // 所以节目单页面的频道列表一直显示全部频道，方便用户查看任意频道的节目单。
        // 如果用户想看某个分组的频道，可以切回左侧面板筛选。

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

        // 更新主页面频道列表的选中状态
        if (!currentGroupName.isEmpty() && !currentGroupChannelList.isEmpty()) {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        } else {
            channelListManager.setChannels(channelSourceList, index);
        }

        // ================================================================
        // ✅ 新增：同步更新节目单页面的频道列表选中状态
        // ================================================================
        // 【为什么要同步？】
        // 因为有两个频道列表，切台时两边都要更新选中高亮，
        // 这样用户切换面板时，选中状态是一致的，不会出现错乱。
        //
        // 【节目单页面的频道列表永远是全部频道】
        // 因为右侧面板没有分组筛选，所以直接用全部频道数据，
        // 不需要调用 setChannelsByGroup。
        channelListManagerEpg.setChannels(channelSourceList, index);

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
     *
     * 【2026-06-19 修改：区分左右面板的点击逻辑】
     * - 左侧面板点击频道：按分组筛选，点击后关闭面板
     * - 右侧面板点击频道：全部频道，点击后不关闭面板（方便继续看节目单）
     */
    private void onChannelClicked(int position) {
        if (!currentGroupChannelList.isEmpty() && position < currentGroupChannelList.size()
                && !rightPanelOpen) {
            // ============================================================
            // 左侧面板（分组筛选模式）
            // ============================================================
            // 左侧面板有分组列表，频道列表是按分组筛选后的，
            // 所以需要把分组内的位置转换成全局索引。
            Channel selectedChannel = currentGroupChannelList.get(position);
            int globalIndex = channelSourceList.indexOf(selectedChannel);
            if (globalIndex != -1) {
                SettingsActivity.logOperation("【列表】点击频道：" + selectedChannel.getName());
                playChannel(globalIndex);
                togglePanel();  // 左侧面板点击频道后关闭面板，开始播放
            }
        } else {
            // ============================================================
            // ✅ 右侧面板（全部频道模式）
            // ============================================================
            // 【为什么不关闭面板？】
            // 用户在节目单页面，经常需要连续看多个频道的节目单，
            // 如果每次点击频道都关闭面板，体验不好。
            // 所以节目单页面点击频道只切换频道和刷新EPG，不关闭面板。
            if (position < channelSourceList.size()) {
                Channel ch = channelSourceList.get(position);
                SettingsActivity.logOperation("【列表】点击频道：" + ch.getName());
                playChannel(position);
                // 注意：这里不调用 togglePanel()，面板保持打开
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
        // 先更新主页面频道列表选中状态
        if (!currentGroupName.isEmpty() && !currentGroupChannelList.isEmpty()) {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        } else {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        }

        // ================================================================
        // ✅ 新增：同步更新节目单页面的频道列表
        // ================================================================
        // 【为什么要在这里更新？】
        // 打开面板前，确保两个频道列表的选中状态都是最新的，
        // 这样用户打开面板时，选中高亮是正确的。
        channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);

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
     * 【2026-06-19 修改：两个完整面板切换】
     * 原逻辑：左右面板切换（只有日期+EPG在右侧）
     * 新逻辑：两个完整面板切换（都有频道列表）
     *
     * 【交互逻辑】
     * 点击后切换到右侧面板（节目单页面）：
     * - 隐藏左侧面板（分组 + 频道 + 节目单按钮）
     * - 显示右侧面板（返回 + 频道 + 日期 + EPG）
     * - 同步节目单页面的频道列表选中状态
     * - 刷新当前频道的 EPG 数据
     */
    private void onEpgButtonClicked() {
        if (!epgEnable) {
            // EPG 功能已关闭
            SettingsActivity.logOperation("【EPG】节目单功能已关闭，无法展开");
            return;
        }

        if (!rightPanelOpen) {
            // ============================================================
            // ✅ 状态：左侧面板显示 → 切换到右侧面板（节目单页面）
            // ============================================================
            // 隐藏左侧面板
            llLeftPanel.setVisibility(View.GONE);
            // 显示右侧面板
            llRightPanel.setVisibility(View.VISIBLE);
            // 更新状态标记
            rightPanelOpen = true;
            epgPanelOpen = true;

            // 同步节目单页面的频道列表选中状态
            // （确保切换过去时，选中的频道是正确的）
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);

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
            // 状态：右侧面板显示 → 切换回左侧面板
            // ============================================================
            llRightPanel.setVisibility(View.GONE);
            llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            SettingsActivity.logOperation("【面板】收起节目单面板");
        }
    }

    // ====================================================================
    // ✅ 新增：返回分组按钮被点击了
    // ====================================================================
    /**
     * 返回分组按钮被点击了
     *
     * 【作用】
     * 从节目单页面（右侧面板）返回到分组列表页面（左侧面板）。
     *
     * 【和 onEpgButtonClicked 的区别】
     * - onEpgButtonClicked：节目单按钮的点击，是双向切换（点一下展开，再点一下收起）
     * - onBackGroupClicked：返回按钮的点击，只负责从右侧切回左侧
     *
     * 【为什么不直接复用 onEpgButtonClicked？】
     * 虽然功能上可以复用，但是语义上不一样：
     * - 节目单按钮 = "展开/收起节目单"
     * - 返回按钮 = "返回分组列表"
     * 分开写逻辑更清晰，以后改起来也方便。
     */
    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            // 隐藏右侧面板（节目单页面）
            llRightPanel.setVisibility(View.GONE);
            // 显示左侧面板（分组列表）
            llLeftPanel.setVisibility(View.VISIBLE);
            // 更新状态标记
            rightPanelOpen = false;
            epgPanelOpen = false;

            SettingsActivity.logOperation("【面板】返回频道分组");
        }
    }

    /**
     * EPG 面板是否展开
     *
     * @return 是否展开
     *
     * 【注意】
     * 这个方法是为了兼容旧代码保留的，现在和 rightPanelOpen 保持同步。
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
     * 原逻辑：复用节目单按钮的切换逻辑
     * 新逻辑：调用专门的返回分组方法
     *
     * 【交互逻辑】
     * 1. 如果右侧面板（节目单）展开着 → 先收起节目单，回到分组列表
     * 2. 如果只有左侧面板打开着 → 关闭整个面板
     *
     * 【为什么要分级返回？】
     * 更符合电视端的交互习惯，用户不会因为误按返回键就直接退出整个面板，
     * 而是一步一步返回，体验更好。
     *
     * @return 是否处理了返回键（true=已处理，不退出）
     */
    public boolean handleBackPressed() {
        if (isPanelOpen()) {
            // 如果右侧面板展开着，先收起右侧面板（回到分组列表）
            if (rightPanelOpen) {
                // ✅ 修改：调用专门的返回分组方法，而不是复用节目单按钮逻辑
                // 语义更清晰，以后改返回逻辑不会影响节目单按钮
                onBackGroupClicked();
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
        llLeftPanel = null;
        llRightPanel = null;
        lvGroup = null;
        lvChannelList = null;
        lvChannelListEpg = null;    // ✅ 新增：释放节目单页面频道列表引用
        lvDate = null;
        lvEpg = null;
        btnShowEpg = null;
        btnBackGroup = null;        // ✅ 新增：释放返回按钮引用
        channelSourceList = null;
        currentGroupChannelList = null;
        channelChangeListener = null;
        panelStateListener = null;
    }
}
