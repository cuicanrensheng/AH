package com.tv.live.manager;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.PlayerGestureHelper;
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
 * 统一管理所有和频道相关的逻辑，包括：
 * 1. 分组管理（分组列表、选中状态、分组筛选）
 * 2. 频道切换（上/下切台、分组内循环、防抖、反转）
 * 3. 面板控制（显示/隐藏、EPG 展开/收起、列表点击）
 * 4. 焦点管理（手机触屏 + 电视遥控器）
 * 5. 按键处理（统一处理所有按键，面板打开/关闭都能处理）← ✅ 合并了 KeyEventManager
 * 6. 手势处理（上/下频道、OK、长按OK、菜单）← ✅ 合并了 GestureManager
 *
 * 【合并说明】
 * 原 KeyEventManager 和 GestureManager 的功能已全部合并到本类中：
 * - 按键事件：统一由 dispatchKeyEvent() 处理，面板打开/关闭逻辑都在一个方法里
 * - 手势识别：新增 createGestureHelper() 方法，创建手势识别器
 * - 所有切台入口（按键、手势）都统一走 switchUp()/switchDown()，反转逻辑统一管理
 *
 * 【为什么合并？】
 * 1. 按键和手势都是用户交互方式，最终都是触发频道切换/面板控制
 * 2. 合并后所有交互逻辑都在一个类里，不会出现不同步的问题
 * 3. 反转逻辑统一管理，所有入口都生效，不会出现"按键有反转、手势没反转"的问题
 * 4. 减少 Manager 数量，代码结构更清晰
 *
 * 【五层逻辑闭环】
 * 1. 状态管理层：分组选中状态、面板显示状态、EPG 展开状态、当前播放索引、焦点位置、反转状态
 * 2. 数据筛选层：按分组筛选频道列表、按日期筛选 EPG
 * 3. 状态同步层：分组切换→频道列表更新、切台→选中状态同步、面板切换→焦点转移
 * 4. 异常兜底层：空列表兜底、索引越界保护、防抖保护、焦点丢失兜底
 * 5. 交互闭环层：按键、手势、点击都触发对应状态更新
 *
 * 【2026-06-19 优化：两个完整面板切换 + 焦点管理】
 * 原左右面板切换模式（只有日期+EPG）改为两个完整面板切换：
 * - 左侧面板：分组列表 + 频道列表 + 节目单按钮（默认显示）
 * - 右侧面板：返回按钮 + 频道列表 + 日期 + EPG（默认隐藏）
 * - 两个面板都有频道列表，切换时选中状态保持同步
 * - 节目单页面也能直接切换频道，不用切回去
 *
 * 【2026-06-20 优化：按键+手势统一管理 + 反转统一入口】
 * 1. 合并 KeyEventManager：所有按键逻辑都在 dispatchKeyEvent() 里
 * 2. 合并 GestureManager：新增 createGestureHelper() 方法
 * 3. 所有切台入口统一走 switchUp()/switchDown()，反转逻辑统一管理
 * 4. 新增 OnPanelActionListener 回调，打开设置等操作回调给外部
 */
public class ChannelPanelController {

    // ====================== 常量 ======================
    /** 频道切换冷却时间（毫秒），300ms 内不允许连续切台 */
    private static final long CHANNEL_COOLDOWN = 300;
    /** 手势防抖延迟（毫秒） */
    private static final long GESTURE_DEBOUNCE_DELAY_MS = 300;

    // ====================== 上下文与视图 ======================
    private Context context;
    private View panelLayout;
    private View llLeftPanel;
    private View llRightPanel;
    private ListView lvGroup;
    private ListView lvChannelList;
    private ListView lvChannelListEpg;  // ✅ 节目单页面频道列表
    private ListView lvDate;
    private ListView lvEpg;
    private TextView btnShowEpg;        // 节目单按钮（竖排文字）
    private TextView btnBackGroup;      // 返回分组按钮（竖排文字）

    // ====================== 子管理器 ======================
    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private ChannelListManager channelListManagerEpg;  // ✅ 节目单页面频道管理器
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PanelManager panelManager;

    // ====================== 数据相关 ======================
    /** 所有频道数据源（全部频道，未筛选） */
    private List<Channel> channelSourceList = new ArrayList<>();
    /** 当前分组的频道列表 */
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    /** 当前分组名称 */
    private String currentGroupName = "";
    /** 当前播放的频道索引（全局索引，对应 channelSourceList） */
    private int currentPlayIndex = 0;
    /** EPG 功能是否启用 */
    private boolean epgEnable = true;

    // ====================================================================
    // ✅ 频道切换相关（合并自 ChannelSwitchManager）
    // ====================================================================
    /**
     * 是否开启换台反转
     * 默认 false = 不反转
     *
     * 【反转说明】
     * 反转关闭（默认）：
     *   - 上键 = 上一台（prev）= 索引-1
     *   - 下键 = 下一台（next）= 索引+1
     *
     * 反转开启：
     *   - 上键 = 下一台（next）= 索引+1
     *   - 下键 = 上一台（prev）= 索引-1
     *
     * 【统一管理说明】
     * 所有切台入口（按键、手势、数字选台等）都统一走 switchUp()/switchDown()，
     * 内部自动考虑反转设置，不会出现不同步的问题。
     */
    private boolean isReverse = false;

    /** 上次频道切换时间（防抖用） */
    private long lastChannelChangeTime = 0;

    // ====================== 面板状态 ======================
    /** 面板是否打开 */
    private boolean isOpen = true;
    /** 右侧面板（节目单页面）是否展开 */
    private boolean rightPanelOpen = false;

    // ====================== 焦点管理 ======================
    /**
     * 当前焦点在哪个面板
     * "left" = 左侧面板（分组/频道列表）
     * "right" = 右侧面板（频道列表/日期/EPG）
     */
    private String currentFocusPanel = "left";

    /**
     * 当前焦点在左侧面板的哪个视图
     * "group" = 分组列表
     * "channel" = 频道列表
     * "epgBtn" = 节目单按钮
     */
    private String leftFocusView = "channel";

    /**
     * 当前焦点在右侧面板的哪个视图
     * "backBtn" = 返回按钮
     * "channel" = 频道列表
     * "date" = 日期列表
     * "epg" = EPG列表
     */
    private String rightFocusView = "channel";

    // ====================================================================
    // ✅ 手势相关（合并自 GestureManager）
    // ====================================================================
    /** 手势是否被锁定（防抖用） */
    private boolean isGestureLocked = false;
    /** 手势 Handler */
    private android.os.Handler gestureHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // ====================== 回调监听器 ======================
    /** 频道变化监听器 */
    private OnChannelChangeListener channelChangeListener;
    /** 面板动作监听器（打开设置等） */
    private OnPanelActionListener panelActionListener;

    // ====================== 接口定义 ======================
    /**
     * 频道变化监听器
     * 当频道切换时回调
     */
    public interface OnChannelChangeListener {
        /**
         * 频道变化了
         * @param channel 新的频道
         * @param index   新的索引
         */
        void onChannelChanged(Channel channel, int index);

        /**
         * 频道被选中（但还没播放，比如在列表里移动焦点）
         * @param channelIndex 选中的频道索引
         */
        void onChannelSelected(int channelIndex);
    }

    /**
     * 面板动作监听器
     * 当需要执行面板之外的操作时回调给外部
     */
    public interface OnPanelActionListener {
        /**
         * 请求打开设置页面
         */
        void onOpenSettings();
    }

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     *
     * @param context                上下文
     * @param panelLayout            面板根布局
     * @param llLeftPanel            左侧面板容器
     * @param llRightPanel           右侧面板容器
     * @param lvGroup                分组列表
     * @param lvChannelList          主面板频道列表
     * @param lvChannelListEpg       节目单页面频道列表
     * @param lvDate                 日期列表
     * @param lvEpg                  EPG列表
     * @param btnShowEpg             节目单按钮（竖排文字）
     * @param btnBackGroup           返回分组按钮（竖排文字）
     * @param groupListManager       分组列表管理器
     * @param channelListManager     主面板频道列表管理器
     * @param channelListManagerEpg  节目单页面频道列表管理器
     * @param dateListManager        日期列表管理器
     * @param panelManager           面板管理器
     */
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
            PanelManager panelManager
    ) {
        this.context = context;
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
        this.panelManager = panelManager;

        // 初始化焦点变化监听
        initFocusListeners();

        // 初始化点击事件
        initClickListeners();
    }

    // ====================================================================
    // 初始化相关
    // ====================================================================

    /**
     * 初始化焦点变化监听器
     *
     * 【作用】
     * 监听各个视图的焦点变化，更新 currentFocusPanel 和 leftFocusView/rightFocusView。
     * 这样处理左右键时，知道当前焦点在哪个视图，决定下一个焦点移到哪。
     *
     * 【为什么需要自己监听焦点变化？】
     * 因为 ListView 自己会处理上下键，但是左右键需要我们自己处理，
     * 我们需要知道当前焦点在哪个视图，才能决定按左右键后焦点移到哪。
     */
    private void initFocusListeners() {
        // 分组列表焦点变化
        lvGroup.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "left";
                leftFocusView = "group";
            }
        });

        // 主面板频道列表焦点变化
        lvChannelList.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "left";
                leftFocusView = "channel";
            }
        });

        // 节目单按钮焦点变化
        btnShowEpg.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "left";
                leftFocusView = "epgBtn";
            }
        });

        // 返回分组按钮焦点变化
        btnBackGroup.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "right";
                rightFocusView = "backBtn";
            }
        });

        // 节目单页面频道列表焦点变化
        lvChannelListEpg.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "right";
                rightFocusView = "channel";
            }
        });

        // 日期列表焦点变化
        lvDate.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "right";
                rightFocusView = "date";
            }
        });

        // EPG列表焦点变化
        lvEpg.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "right";
                rightFocusView = "epg";
            }
        });
    }

    /**
     * 初始化点击事件监听器
     */
    private void initClickListeners() {
        // 分组列表点击
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            onGroupClicked(position);
        });

        // 主面板频道列表点击
        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            onChannelClicked(position);
        });

        // 节目单按钮点击
        btnShowEpg.setOnClickListener(v -> {
            onEpgButtonClicked();
        });

        // 返回分组按钮点击
        btnBackGroup.setOnClickListener(v -> {
            onBackGroupClicked();
        });

        // 节目单页面频道列表点击
        lvChannelListEpg.setOnItemClickListener((parent, view, position, id) -> {
            onChannelClickedEpg(position);
        });
    }

    // ====================================================================
    // 数据设置相关
    // ====================================================================

    /**
     * 设置频道数据源
     *
     * @param channels 所有频道列表
     */
    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;

        // 更新分组列表
        groupListManager.setGroups(channels);

        // 默认选中第一个分组
        if (!channels.isEmpty()) {
            String firstGroup = channels.get(0).getGroup();
            if (firstGroup != null && !firstGroup.isEmpty()) {
                currentGroupName = firstGroup;
                // 筛选当前分组的频道
                currentGroupChannelList.clear();
                for (Channel c : channels) {
                    if (firstGroup.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                // 更新主面板频道列表
                channelListManager.setChannelsByGroup(channels, firstGroup, currentPlayIndex);
            }
        }

        // ✅ 更新节目单页面频道列表（不筛选分组，显示全部频道）
        // 【为什么不筛选？】
        // 节目单页面主要是看节目单，频道列表只是用来快速切换频道，
        // 如果也按分组筛选，用户切换分组还要切回左侧面板，操作太繁琐。
        // 所以右侧面板放全部频道，直接切换，体验更好。
        channelListManagerEpg.setChannels(channels, currentPlayIndex);
    }

    /**
     * 设置当前播放索引
     *
     * @param index 全局索引
     */
    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
        // 同步到两个频道列表管理器
        channelListManager.setSelectedPosition(index);
        channelListManagerEpg.setSelectedPosition(index);
    }

    /**
     * 获取当前播放索引
     *
     * @return 当前播放的全局索引
     */
    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    /**
     * 设置 EPG 功能是否启用
     *
     * @param enable 是否启用
     */
    public void setEpgEnable(boolean enable) {
        this.epgEnable = enable;
    }

    /**
     * 设置当前选中的日期索引
     *
     * @param dateIndex 日期索引（0=今天，1=明天...）
     */
    public void setCurrentDateIndex(int dateIndex) {
        // TODO: 刷新 EPG 数据
    }

    /**
     * 获取当前分组的频道列表
     *
     * @return 当前分组的频道列表
     */
    public List<Channel> getCurrentGroupChannels() {
        return currentGroupChannelList;
    }

    // ====================================================================
    // ✅ 换台反转设置（统一管理）
    // ====================================================================

    /**
     * 设置是否开启换台反转
     *
     * @param reverse true = 开启反转，false = 关闭反转
     *
     * 【调用时机】
     * 1. App 启动时，MainActivity.loadSettings() 读取设置后调用
     * 2. 从设置页面返回时，MainActivity.onResume() 重新加载设置后调用
     *
     * 【为什么需要这个方法？】
     * 反转状态保存在本类内部，
     * 外部（MainActivity）读取设置后，需要通过这个方法同步进来。
     *
     * 【同步后效果】
     * 所有切台入口（按键、手势、数字选台等）调用 switchUp()/switchDown() 时，
     * 都会自动考虑反转设置，不会出现不同步的问题。
     */
    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
    }

    /**
     * 获取当前反转状态
     *
     * @return true = 已开启反转
     */
    public boolean isReverse() {
        return isReverse;
    }

    // ====================================================================
    // 分组点击处理
    // ====================================================================

    /**
     * 分组被点击了
     *
     * @param position 分组在分组列表中的位置
     */
    private void onGroupClicked(int position) {
        // 更新分组选中高亮
        groupListManager.setSelectedPosition(position);
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

        SettingsActivity.logOperation("【分组】选中分组：" + groupName
                + "，频道数：" + currentGroupChannelList.size());
    }

    // ====================================================================
    // 频道点击处理
    // ====================================================================

    /**
     * 主面板频道被点击了
     *
     * @param position 频道在当前分组列表中的位置
     */
    private void onChannelClicked(int position) {
        // 转换成全局索引
        if (position < 0 || position >= currentGroupChannelList.size()) return;
        Channel channel = currentGroupChannelList.get(position);
        int globalIndex = channelSourceList.indexOf(channel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
    }

    /**
     * 节目单页面频道被点击了
     *
     * @param position 频道在全部频道列表中的位置
     */
    private void onChannelClickedEpg(int position) {
        // 节目单页面的频道列表是全部频道，直接用全局索引
        playChannel(position);
    }

    // ====================================================================
    // 节目单按钮/返回按钮点击处理
    // ====================================================================

    /**
     * 节目单按钮被点击了
     * 展开右侧面板（节目单页面）
     */
    private void onEpgButtonClicked() {
        if (!rightPanelOpen) {
            // 展开右侧面板
            llLeftPanel.setVisibility(View.GONE);
            llRightPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = true;
            currentFocusPanel = "right";
            rightFocusView = "channel";

            // 等布局绘制完成后再请求焦点，确保成功
            panelLayout.post(() -> {
                lvChannelListEpg.requestFocus();
                // 把选中位置滚动到可见区域
                lvChannelListEpg.setSelection(currentPlayIndex);
            });

            SettingsActivity.logOperation("【面板】展开节目单页面");
        }
    }

    /**
     * 返回分组按钮被点击了
     * 收起右侧面板，回到左侧面板
     */
    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            // 收起右侧面板，回到左侧面板
            llRightPanel.setVisibility(View.GONE);
            llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            currentFocusPanel = "left";
            leftFocusView = "channel";

            // 等布局绘制完成后再请求焦点，确保成功
            panelLayout.post(() -> {
                lvChannelList.requestFocus();
                // 把选中位置滚动到可见区域
                lvChannelList.setSelection(getChannelListSelection());
            });

            SettingsActivity.logOperation("【面板】返回分组列表");
        }
    }

    /**
     * 获取当前频道在列表中的选中位置
     *
     * @return 选中位置
     */
    private int getChannelListSelection() {
        // TODO: 根据当前播放索引计算在分组列表中的位置
        return 0;
    }

    // ====================================================================
    // ✅ 频道切换相关（核心，统一管理）
    // ====================================================================

    /**
     * 播放上一个频道（分组内循环）
     *
     * 【注意】这是底层方法，直接切换到上一台，不考虑反转。
     * 如果需要考虑反转，请调用 switchUp() 或 switchDown()。
     *
     * 【逻辑说明】
     * 1. 防抖检查：300ms 内不允许连续切台
     * 2. 获取当前频道所在的分组
     * 3. 筛选出当前分组的所有频道
     * 4. 找到当前频道在分组中的索引
     * 5. 计算上一个频道的索引（分组内循环）
     * 6. 转换成全局索引，调用 playChannel() 播放
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
     *
     * 【注意】这是底层方法，直接切换到下一台，不考虑反转。
     * 如果需要考虑反转，请调用 switchUp() 或 switchDown()。
     *
     * 【逻辑说明】
     * 1. 防抖检查：300ms 内不允许连续切台
     * 2. 获取当前频道所在的分组
     * 3. 筛选出当前分组的所有频道
     * 4. 找到当前频道在分组中的索引
     * 5. 计算下一个频道的索引（分组内循环）
     * 6. 转换成全局索引，调用 playChannel() 播放
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

    // ====================================================================
    // ✅ 带反转的切台方法（统一入口，所有切台都走这里）
    // ====================================================================

    /**
     * 按上键时调用（自动考虑反转）
     *
     * 【逻辑】
     * - 反转关闭：上键 = 上一台（playPrev）
     * - 反转开启：上键 = 下一台（playNext）
     *
     * 【为什么新增这个方法？】
     * 原来的 playPrev() 和 playNext() 是固定的，prev 就是上一台。
     * 现在新增 switchUp() 和 switchDown()，内部自动考虑反转设置，
     * 外部调用时不用关心反转逻辑，直接调用这两个方法就行。
     *
     * 【好处】
     * 1. 反转逻辑统一管理，不会出现不同步
     * 2. 外部调用简单，不用每次都判断 isReverse
     * 3. 以后修改反转逻辑，只需要改这一个地方
     * 4. 所有切台入口（按键、手势、数字选台）都走这里，反转都生效
     *
     * 【调用示例】
     * - 按键上键 → 调用 switchUp()
     * - 手势上滑 → 调用 switchUp()
     * - 数字选台确认 → 调用 playChannel()（直接指定索引，不需要反转）
     */
    public void switchUp() {
        if (isReverse) {
            // 反转开启：上键 = 下一台
            playNext();
            SettingsActivity.logOperation("【切台】上键 → 下一台（反转已开启）");
        } else {
            // 反转关闭：上键 = 上一台
            playPrev();
            SettingsActivity.logOperation("【切台】上键 → 上一台");
        }
    }

    /**
     * 按下键时调用（自动考虑反转）
     *
     * 【逻辑】
     * - 反转关闭：下键 = 下一台（playNext）
     * - 反转开启：下键 = 上一台（playPrev）
     */
    public void switchDown() {
        if (isReverse) {
            // 反转开启：下键 = 上一台
            playPrev();
            SettingsActivity.logOperation("【切台】下键 → 上一台（反转已开启）");
        } else {
            // 反转关闭：下键 = 下一台
            playNext();
            SettingsActivity.logOperation("【切台】下键 → 下一台");
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

        Channel ch = channelSourceList.get(index);
        currentPlayIndex = index;

        // 更新两个频道列表的选中状态
        channelListManager.setSelectedPosition(index);
        channelListManagerEpg.setSelectedPosition(index);

        // 回调频道变化
        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
        }
    }

    // ====================================================================
    // 面板控制相关
    // ====================================================================

    /**
     * 切换面板显示/隐藏
     */
    public void togglePanel() {
        if (isOpen) {
            hidePanel();
        } else {
            showPanel();
        }
    }

    /**
     * 显示面板
     */
    public void showPanel() {
        if (!isOpen) {
            panelLayout.setVisibility(View.VISIBLE);
            isOpen = true;

            // 默认焦点在左侧面板的频道列表
            currentFocusPanel = "left";
            leftFocusView = "channel";

            // 等布局绘制完成后再请求焦点，确保成功
            panelLayout.post(() -> {
                lvChannelList.requestFocus();
                // 把选中位置滚动到可见区域
                lvChannelList.setSelection(getChannelListSelection());
            });

            // 回调状态变化
            SettingsActivity.logOperation("【面板】显示频道面板");
        }
    }

    /**
     * 隐藏面板
     */
    public void hidePanel() {
        if (isOpen) {
            panelLayout.setVisibility(View.GONE);
            isOpen = false;
            SettingsActivity.logOperation("【面板】隐藏频道面板");
        }
    }

    /**
     * 面板是否打开
     *
     * @return true = 打开，false = 关闭
     */
    public boolean isPanelOpen() {
        return isOpen;
    }

    /**
     * 返回键被按下了
     *
     * @return 是否处理了返回键（true=已处理，不退出）
     *
     * 【逻辑】
     * 1. 如果右侧面板（节目单）展开着 → 先收起节目单，回到分组列表
     * 2. 如果只有左侧面板打开着 → 关闭整个面板
     * 3. 如果面板都关闭了 → 返回 false，让外部（MainActivity）处理
     */
    public boolean handleBackPressed() {
        if (rightPanelOpen) {
            // 如果右侧面板展开着，先收起右侧面板（回到分组列表）
            onBackGroupClicked();
            return true;
        }

        if (isOpen) {
            // 如果面板打开着，关闭面板
            hidePanel();
            return true;
        }

        return false;  // 没处理，让外部（MainActivity）处理
    }

    // ====================================================================
    // ✅ 按键事件分发（统一处理所有按键，合并自 KeyEventManager）
    // ====================================================================

    /**
     * 分发按键事件
     *
     * 【统一处理说明】
     * 不管面板是打开还是关闭，都调用这一个方法，内部自动判断处理逻辑。
     * 合并了原 KeyEventManager 的功能（面板关闭时的按键处理）。
     *
     * 【按键逻辑】
     * 面板打开时：
     * - 左键：往左移焦点
     * - 右键：往右移焦点
     * - OK键：选中当前焦点的项
     * - 上下键：不处理，由 ListView 自己处理（在列表内移动焦点）
     *
     * 面板关闭时：
     * - 上键：上一台（自动考虑反转）
     * - 下键：下一台（自动考虑反转）
     * - OK键/左键/右键：打开面板
     * - 菜单键：打开设置（回调给外部）
     *
     * @param keyCode 按键码
     * @return 是否处理了该按键（true=已处理，不再往下分发）
     */
    public boolean dispatchKeyEvent(int keyCode) {
        if (isPanelOpen()) {
            // ================================================================
            // 面板打开时：处理左右键、OK键（在面板内移动焦点、选中项）
            // ================================================================
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return handleLeftKey();
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return handleRightKey();
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    return handleOkKey();
                default:
                    // 上下键等不处理，让 ListView 自己处理
                    return false;
            }
        } else {
            // ================================================================
            // 面板关闭时：处理上下键切台、OK键开面板、菜单键开设置
            // ================================================================
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    // ✅ 上键切台（自动考虑反转）
                    switchUp();
                    return true;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // ✅ 下键切台（自动考虑反转）
                    switchDown();
                    return true;

                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // OK键/左右键：打开面板
                    showPanel();
                    return true;

                case KeyEvent.KEYCODE_MENU:
                    // 菜单键：打开设置（回调给外部）
                    if (panelActionListener != null) {
                        panelActionListener.onOpenSettings();
                    }
                    return true;

                default:
                    return false;
            }
        }
    }

    /**
     * 处理左键（从右往左移焦点）
     *
     * 【逻辑】
     * 从右往左移焦点，比如：
     * - 左侧面板：节目单按钮 → 频道列表 → 分组列表
     * - 右侧面板：EPG列表 → 日期列表 → 频道列表 → 返回按钮
     *
     * 【为什么要自己处理左键？】
     * 因为 ListView 自己只会处理上下键，不会处理左右键，
     * 我们需要自己管理焦点在不同视图之间的移动。
     *
     * @return 是否处理了按键
     */
    private boolean handleLeftKey() {
        if ("left".equals(currentFocusPanel)) {
            // 左侧面板：从右往左移
            if ("epgBtn".equals(leftFocusView)) {
                // 节目单按钮 → 频道列表
                lvChannelList.requestFocus();
                return true;
            } else if ("channel".equals(leftFocusView)) {
                // 频道列表 → 分组列表
                lvGroup.requestFocus();
                return true;
            }
        } else if ("right".equals(currentFocusPanel)) {
            // 右侧面板：从右往左移
            if ("epg".equals(rightFocusView)) {
                // EPG列表 → 日期列表
                lvDate.requestFocus();
                return true;
            } else if ("date".equals(rightFocusView)) {
                // 日期列表 → 频道列表
                lvChannelListEpg.requestFocus();
                return true;
            } else if ("channel".equals(rightFocusView)) {
                // 频道列表 → 返回按钮
                btnBackGroup.requestFocus();
                return true;
            }
        }
        return false;
    }

    /**
     * 处理右键（从左往右移焦点）
     *
     * 【逻辑】
     * 从左往右移焦点，比如：
     * - 左侧面板：分组列表 → 频道列表 → 节目单按钮
     * - 右侧面板：返回按钮 → 频道列表 → 日期列表 → EPG列表
     *
     * @return 是否处理了按键
     */
    private boolean handleRightKey() {
        if ("left".equals(currentFocusPanel)) {
            // 左侧面板：从左往右移
            if ("group".equals(leftFocusView)) {
                // 分组列表 → 频道列表
                lvChannelList.requestFocus();
                return true;
            } else if ("channel".equals(leftFocusView)) {
                // 频道列表 → 节目单按钮
                btnShowEpg.requestFocus();
                return true;
            }
        } else if ("right".equals(currentFocusPanel)) {
            // 右侧面板：从左往右移
            if ("backBtn".equals(rightFocusView)) {
                // 返回按钮 → 频道列表
                lvChannelListEpg.requestFocus();
                return true;
            } else if ("channel".equals(rightFocusView)) {
                // 频道列表 → 日期列表
                lvDate.requestFocus();
                return true;
            } else if ("date".equals(rightFocusView)) {
                // 日期列表 → EPG列表
                lvEpg.requestFocus();
                return true;
            }
        }
        return false;
    }

    /**
     * 处理OK键（选中当前项）
     *
     * 【逻辑】
     * 根据当前焦点在哪个视图，执行对应的选中操作：
     * - 分组列表：选中分组，刷新频道列表
     * - 频道列表：播放选中的频道
     * - 节目单按钮：展开节目单页面
     * - 返回按钮：返回分组列表
     * - 日期列表：切换日期，刷新 EPG
     * - EPG列表：播放选中的节目对应的频道
     *
     * 【为什么要自己处理OK键？】
     * 因为 ListView 的 onItemClickListener 是在点击时触发，
     * 遥控器 OK 键也会触发，但有时候焦点和选中位置不同步，
     * 我们自己处理可以确保 OK 键选中的是当前焦点的项。
     *
     * @return 是否处理了按键
     */
    private boolean handleOkKey() {
        if ("left".equals(currentFocusPanel)) {
            // 左侧面板
            if ("group".equals(leftFocusView)) {
                // 分组列表：选中当前分组
                int pos = lvGroup.getSelectedItemPosition();
                if (pos >= 0) {
                    onGroupClicked(pos);
                    return true;
                }
            } else if ("channel".equals(leftFocusView)) {
                // 频道列表：播放选中的频道
                int pos = lvChannelList.getSelectedItemPosition();
                if (pos >= 0) {
                    onChannelClicked(pos);
                    return true;
                }
            } else if ("epgBtn".equals(leftFocusView)) {
                // 节目单按钮：展开节目单页面
                onEpgButtonClicked();
                return true;
            }
        } else if ("right".equals(currentFocusPanel)) {
            // 右侧面板
            if ("backBtn".equals(rightFocusView)) {
                // 返回按钮：返回分组列表
                onBackGroupClicked();
                return true;
            } else if ("channel".equals(rightFocusView)) {
                // 频道列表：播放选中的频道
                int pos = lvChannelListEpg.getSelectedItemPosition();
                if (pos >= 0) {
                    onChannelClickedEpg(pos);
                    return true;
                }
            } else if ("date".equals(rightFocusView)) {
                // 日期列表：切换日期
                int pos = lvDate.getSelectedItemPosition();
                if (pos >= 0) {
                    setCurrentDateIndex(pos);
                    return true;
                }
            }
            // EPG列表：暂不处理 OK 键
        }
        return false;
    }

    // ====================================================================
    // ✅ 手势相关（合并自 GestureManager）
    // ====================================================================

    /**
     * 创建手势识别器
     *
     * 【合并说明】
     * 原 GestureManager 的功能，合并到本类中。
     * 创建一个 PlayerGestureHelper，设置手势回调。
     *
     * 【手势回调说明】
     * - onOk：点击 → 切换面板显示/隐藏
     * - onLongOk：长按 → 打开设置
     * - onMenu：菜单键 → 打开设置
     * - onPrevChannel：上滑 → 上一个频道（自动考虑反转）
     * - onNextChannel：下滑 → 下一个频道（自动考虑反转）
     *
     * 【为什么合并？】
     * 手势和按键都是用户交互方式，最终都是触发频道切换/面板控制，
     * 合并后所有交互逻辑都在一个类里，反转逻辑统一管理，不会出现不同步。
     *
     * @return PlayerGestureHelper 实例，设置给播放器视图
     */
    public PlayerGestureHelper createGestureHelper() {
        return new PlayerGestureHelper(context, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                // 点击：切换面板显示/隐藏
                togglePanel();
            }

            @Override
            public void onLongOk() {
                // 长按：打开设置
                if (panelActionListener != null) {
                    panelActionListener.onOpenSettings();
                }
            }

            @Override
            public void onMenu() {
                // 菜单键：打开设置
                if (panelActionListener != null) {
                    panelActionListener.onOpenSettings();
                }
            }

            @Override
            public void onPrevChannel() {
                // 上滑：上一个频道（自动考虑反转）
                // 【防抖】300ms 内不允许连续触发
                if (!isGestureLocked) {
                    isGestureLocked = true;
                    switchUp();  // ✅ 统一走 switchUp()，自动考虑反转
                    gestureHandler.postDelayed(() -> isGestureLocked = false, GESTURE_DEBOUNCE_DELAY_MS);
                }
            }

            @Override
            public void onNextChannel() {
                // 下滑：下一个频道（自动考虑反转）
                // 【防抖】300ms 内不允许连续触发
                if (!isGestureLocked) {
                    isGestureLocked = true;
                    switchDown();  // ✅ 统一走 switchDown()，自动考虑反转
                    gestureHandler.postDelayed(() -> isGestureLocked = false, GESTURE_DEBOUNCE_DELAY_MS);
                }
            }
        });
    }

    // ====================================================================
    // 监听器设置
    // ====================================================================

    /**
     * 设置频道变化监听器
     *
     * @param listener 监听器
     */
    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    /**
     * 设置面板动作监听器
     *
     * @param listener 监听器
     */
    public void setOnPanelActionListener(OnPanelActionListener listener) {
        this.panelActionListener = listener;
    }

    // ====================================================================
    // 资源释放
    // ====================================================================

    /**
     * 释放资源
     * Activity onDestroy 时调用
     */
    public void release() {
        context = null;
        channelChangeListener = null;
        panelActionListener = null;
        if (gestureHandler != null) {
            gestureHandler.removeCallbacksAndMessages(null);
            gestureHandler = null;
        }
    }
}
