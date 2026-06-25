package com.tv.live.manager;
import android.content.Context;
import android.graphics.Typeface;
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
 * 【职责】
 * 统一管理所有和频道面板相关的逻辑，包括：
 * 1. 分组管理（分组列表、选中状态、分组筛选）
 * 2. 频道切换（上/下切台、分组内循环、防抖、反转）
 * 3. 面板控制（显示/隐藏、EPG 展开/收起、列表点击）
 * 4. 焦点管理（手机触屏 + 电视遥控器）
 * 5. 按键处理（左右键移动焦点、OK键选中、菜单键收藏）
 *
 * 【2026-06-21 新增：收藏 + 最近观看 + 菜单键】
 * 【功能说明】
 * 1. 分组列表增加「收藏」和「最近观看」两个特殊分组
 * 2. 菜单键（KEYCODE_MENU）可以快速收藏/取消收藏当前频道
 * 3. 切换频道时自动添加到最近观看
 *
 * 【2026-06-21 新增：长按收藏（触屏模式）】
 * 【功能说明】
 * 触屏模式下，长按频道项可以收藏/取消收藏该频道。
 *
 * 【2026-06-21 新增：排查日志】
 * 【说明】
 * 在关键位置加上日志，方便排查收藏和最近观看功能的问题。
 *
 * 【2026-06-21 新增：调试日志 - 频道名对比】
 * 【说明】
 * 加上详细的频道名对比日志，找出为什么匹配不上。
 * 
 * 【2026-06-24 修改：增加焦点样式同步】
 * 【修改说明】
 * 在原来的焦点管理基础上，增加焦点样式同步功能：
 * 1. 焦点切换到哪个列表，就调用哪个列表的 setFocused(true)
 * 2. 其他列表调用 setFocused(false)
 * 3. 打开面板、切换左右面板时设置初始焦点
 * 
 * 【效果】
 * - 有焦点的列表：选中项是深蓝色背景 + 白色文字（醒目）
 * - 无焦点的列表：选中项是浅蓝色背景 + 蓝色文字（标记）
 * - 用户一眼就能看出当前焦点在哪个面板上
 * 
 * 【为什么只加这个，不改原有逻辑？】
 * 原有焦点管理逻辑（initFocusListeners + handleLeftKey/RightKey/OkKey）
 * 已经能正常工作，不需要大改。只需要在焦点变化时同步一下样式就行。
 * 
 * 【2026-06-24 修复：焦点样式同步问题】
 * 【修复的问题】
 * 1. 切换面板后多个列表同时显示深蓝色（没有清除另一个面板的焦点样式）
 * 2. 按钮焦点看不到效果（setSelected 依赖 XML selector，不一定生效）
 * 3. onBackGroupClicked 中焦点状态和实际焦点不一致
 * 4. ListView requestFocus 有时不立即触发 onFocusChange，导致样式不同步
 * 
 * 【修复方案】
 * 1. 新增 clearAllFocusStyles()：一键清除所有焦点样式
 * 2. 新增 syncFocusStyle()：统一入口，根据当前状态同步所有样式
 * 3. 按钮焦点直接改文字颜色和背景色，不依赖 setSelected
 * 4. 所有切换面板的地方都先清除再同步，确保状态一致
 * 
 * 【2026-06-24 修改：按钮焦点样式优化】
 * 【修改说明】
 * 把按钮的焦点样式从"深蓝色背景 + 白色文字"改成"浅蓝色背景 + 白色文字 + 加粗"，
 * 视觉上更柔和，不会太刺眼。
 * 
 * 【样式规范】
 * - 无焦点：白色文字 + 普通 + 透明背景
 * - 有焦点：白色文字 + 加粗 + 浅蓝色背景（0x3340A9FF）
 * 
 * 【2026-06-24 新增：自动跳过失效频道】
 * 【功能说明】
 * 按上下键切台时，如果遇到失效的直播源，自动继续切到下一个/上一个频道，
 * 直到找到能正常播放的频道为止。
 * 
 * 【触发条件】
 * 1. 必须是按上下键切台（switchUp/switchDown）
 * 2. 点击频道列表、数字选台等方式切换的，不自动跳过
 * 3. 最多连续跳过 10 个失效频道（防止无限循环）
 * 
 * 【工作流程】
 * 1. 用户按上/下键 → 记录切台方向（lastSwitchDirection）
 * 2. 标记为切台状态（isSwitchingChannel = true）
 * 3. 播放失败 → MainActivity 调用 canAutoSkip() 判断
 * 4. 可以跳过 → 调用 autoSkipFailedChannel() 继续切
 * 5. 播放成功 → 调用 onPlaySuccess() 重置状态
 */
public class ChannelPanelController {
    // ====================== 常量 ======================
    /** 频道切换冷却时间（毫秒），300ms 内不允许连续切台 */
    private static final long CHANNEL_COOLDOWN = 300;
    // ====================================================================
    // ✅ 2026-06-24 新增：自动跳过失效频道 - 最大跳过次数
    // ====================================================================
    /**
     * 最大自动跳过次数
     * 
     * 【为什么是 10 次？】
     * 10 个频道都失效的概率很低，
     * 超过 10 个说明可能是网络问题，
     * 这时候应该停下来让用户检查，而不是一直切。
     */
    private static final int MAX_AUTO_SKIP = 10;
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
    // ====================================================================
    // ✅ 2026-06-24 新增：自动跳过失效频道 - 成员变量
    // ====================================================================
    /**
     * 最后一次切台方向
     * "up" = 向上切台（按上键）
     * "down" = 向下切台（按下键）
     * "" = 未知（比如点击频道列表、数字选台等）
     * 
     * 【作用】
     * 播放失败时，根据这个方向决定继续向上还是向下切。
     * 
     * 【为什么需要记录方向？】
     * 因为用户按上键切台遇到失效源，应该继续向上切，
     * 而不是向下切，不然方向就乱了。
     */
    private String lastSwitchDirection = "";

    /**
     * 是否正在切台（刚切完还没播放成功）
     * 
     * 【作用】
     * 只有切台时遇到失效源才自动跳过，
     * 正常播放中突然失效的不自动跳过。
     * 
     * 【为什么需要这个标记？】
     * 如果正常播放了 10 分钟，然后网络波动导致播放失败，
     * 这时候不应该自动切台，用户可能还想看这个频道。
     * 只有刚切过去就播不出来的，才算是"失效源"。
     */
    private boolean isSwitchingChannel = false;

    /**
     * 已自动跳过的次数
     * 
     * 【作用】
     * 防止无限循环（比如所有频道都失效了）。
     * 
     * 【什么时候重置？】
     * 播放成功时调用 onPlaySuccess() 重置为 0。
     */
    private int autoSkipCount = 0;
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
        // ✅ 2026-06-21 新增：长按收藏（左侧频道列表）【加了日志】
        channelListManager.setOnChannelLongClickListener(new ChannelListManager.OnChannelLongClickListener() {
            @Override
            public boolean onChannelLongClick(String channelName, int position) {
                // ✅ 日志：确认回调触发了
                SettingsActivity.logOperation("【面板】左侧长按回调触发，channelName=" + channelName);
                return handleChannelLongClick(channelName, false);
            }
        });
        // ✅ 2026-06-21 新增：长按收藏（右侧节目单页面的频道列表）【加了日志】
        channelListManagerEpg.setOnChannelLongClickListener(new ChannelListManager.OnChannelLongClickListener() {
            @Override
            public boolean onChannelLongClick(String channelName, int position) {
                // ✅ 日志：确认回调触发了
                SettingsActivity.logOperation("【面板】右侧长按回调触发，channelName=" + channelName);
                return handleChannelLongClick(channelName, true);
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
    // 
    // 【2026-06-24 修改：改用 syncFocusStyle() 统一同步】
    // 【修改说明】
    // 原来每个 onFocusChange 里都要写一大段 setFocused，代码重复且容易漏。
    // 现在改成只更新状态变量，然后调用 syncFocusStyle() 统一同步，
    // 确保所有列表和按钮的样式都一致，不会出现"多个面板同时高亮"的问题。
    // 
    // 【为什么不在 onFocusChange 里直接设置？】
    // 1. ListView 的 requestFocus() 有时不会立即触发 onFocusChange
    // 2. 切换面板时需要批量设置，统一入口更清晰
    // 3. 按钮的 setSelected() 依赖 XML 里的 selector，不一定生效
    // ====================================================================
    private void initFocusListeners() {
        lvGroup.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "group";
                    syncFocusStyle();
                }
            }
        });
        lvChannelList.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
                }
            }
        });
        btnShowEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "epgBtn";
                    syncFocusStyle();
                }
            }
        });
        lvChannelListEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "channel";
                    syncFocusStyle();
                }
            }
        });
        lvDate.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "date";
                    syncFocusStyle();
                }
            }
        });
        lvEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "epg";
                    syncFocusStyle();
                }
            }
        });
        btnBackGroup.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "backBtn";
                    syncFocusStyle();
                }
            }
        });
    }
    // ====================================================================
    // ✅ 2026-06-24 新增：清除所有焦点样式
    // ====================================================================
    /**
     * 清除所有列表和按钮的焦点样式
     * 
     * 【作用】
     * 切换面板、切换焦点区域前，先把所有样式都清掉，
     * 避免出现"多个面板同时显示深蓝色"的问题。
     * 
     * 【为什么需要这个方法？】
     * 原来的实现中，每次只设置当前焦点的列表为 true，
     * 但有时候另一个面板的列表没有被设置为 false，
     * 就会出现两个列表同时高亮的情况。
     * 现在先全部清除，再设置当前焦点，确保只有一个高亮。
     * 
     * 【2026-06-24 修改：加上文字样式恢复】
     * 【为什么要加 setTypeface？】
     * 有焦点时文字会加粗，失去焦点时要恢复成普通样式，
     * 不然文字会一直保持加粗状态。
     */
    private void clearAllFocusStyles() {
        // 所有列表都设为无焦点
        groupListManager.setFocused(false);
        channelListManager.setFocused(false);
        channelListManagerEpg.setFocused(false);
        dateListManager.setFocused(false);
        // ✅ 2026-06-24 修改：所有按钮都恢复普通样式（白色文字 + 普通 + 透明背景）
        btnShowEpg.setTextColor(0xFFFFFFFF);
        btnShowEpg.setTypeface(null, Typeface.NORMAL); // 恢复普通
        btnShowEpg.setBackgroundColor(0x00000000);
        btnBackGroup.setTextColor(0xFFFFFFFF);
        btnBackGroup.setTypeface(null, Typeface.NORMAL); // 恢复普通
        btnBackGroup.setBackgroundColor(0x00000000);
    }
    // ====================================================================
    // ✅ 2026-06-24 新增：同步焦点样式（统一入口）
    // ====================================================================
    /**
     * 根据 currentFocusPanel 和 leftFocusView / rightFocusView，
     * 同步所有列表和按钮的焦点样式。
     * 
     * 【作用】
     * 确保焦点状态和视觉样式一致，避免出现"焦点移动了但样式没变"的情况。
     * 
     * 【调用时机】
     * 1. onFocusChange 回调中（焦点变化时）
     * 2. 打开面板时（设置初始焦点）
     * 3. 切换左右面板时（切换后同步样式）
     * 4. 按键移动焦点后（兜底，确保样式同步）
     * 
     * 【按钮焦点怎么处理？】
     * 直接改变文字颜色和背景色，不依赖 setSelected，确保一定能看到效果。
     * 因为 setSelected() 需要 XML 里的 selector 定义 state_selected 才会生效，
     * 很多时候按钮的背景没有定义这个状态，就会看不到效果。
     * 
     * 【2026-06-24 修改：按钮焦点样式优化】
     * 【修改说明】
     * 把按钮的焦点样式从"深蓝色背景 + 白色文字"改成"浅蓝色背景 + 白色文字 + 加粗"，
     * 视觉上更柔和，不会太刺眼。
     */
    private void syncFocusStyle() {
        // 先清除所有焦点样式
        clearAllFocusStyles();
        // 根据当前焦点位置设置对应的样式
        if ("left".equals(currentFocusPanel)) {
            if ("group".equals(leftFocusView)) {
                // 焦点在分组列表
                groupListManager.setFocused(true);
            } else if ("channel".equals(leftFocusView)) {
                // 焦点在左频道列表
                channelListManager.setFocused(true);
            } else if ("epgBtn".equals(leftFocusView)) {
                // ✅ 2026-06-24 修改：焦点在EPG按钮：浅蓝色背景 + 白色文字 + 加粗
                // 【为什么改？】
                // 原来的深蓝色背景（0xFF40A9FF）太亮了，太刺眼。
                // 改成浅蓝色背景 + 白色文字 + 加粗，视觉上更柔和。
                btnShowEpg.setTextColor(0xFFFFFFFF); // 白色文字
                btnShowEpg.setTypeface(null, Typeface.BOLD); // 加粗
                btnShowEpg.setBackgroundColor(0x3340A9FF); // 浅蓝色背景（20%透明度）
            }
        } else if ("right".equals(currentFocusPanel)) {
            if ("channel".equals(rightFocusView)) {
                // 焦点在右频道列表
                channelListManagerEpg.setFocused(true);
            } else if ("date".equals(rightFocusView)) {
                // 焦点在日期列表
                dateListManager.setFocused(true);
            } else if ("backBtn".equals(rightFocusView)) {
                // ✅ 2026-06-24 修改：焦点在返回按钮：浅蓝色背景 + 白色文字 + 加粗
                btnBackGroup.setTextColor(0xFFFFFFFF); // 白色文字
                btnBackGroup.setTypeface(null, Typeface.BOLD); // 加粗
                btnBackGroup.setBackgroundColor(0x3340A9FF); // 浅蓝色背景（20%透明度）
            }
            // epg 暂时不处理样式
        }
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
    /**
     * 播放上一个频道（分组内循环）- 底层方法
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
     * 【2026-06-24 修改：记录切台方向和切台状态】
     * 【修改说明】
     * 加上 lastSwitchDirection 和 isSwitchingChannel 标记，
     * 播放失败时可以根据方向自动继续切。
     * 
     * 【为什么要在这里设置，而不是在 playPrev/playNext 里？】
     * 因为 playPrev/playNext 是底层方法，自动跳过时也会调用它们。
     * 如果在 playPrev/playNext 里设置，自动跳过时又会重置状态，
     * 导致 autoSkipCount 被清零，就会无限循环了。
     * 
     * 所以只有用户手动按上下键（switchUp/switchDown）时，
     * 才设置切台状态和重置跳过计数。
     */
    public void switchUp() {
        lastSwitchDirection = "up";
        isSwitchingChannel = true;
        autoSkipCount = 0; // 用户手动切台，重置跳过计数
        
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
     * 【2026-06-24 修改：记录切台方向和切台状态】
     * 【修改说明】
     * 加上 lastSwitchDirection 和 isSwitchingChannel 标记，
     * 播放失败时可以根据方向自动继续切。
     * 
     * 【为什么要在这里设置，而不是在 playPrev/playNext 里？】
     * 因为 playPrev/playNext 是底层方法，自动跳过时也会调用它们。
     * 如果在 playPrev/playNext 里设置，自动跳过时又会重置状态，
     * 导致 autoSkipCount 被清零，就会无限循环了。
     * 
     * 所以只有用户手动按上下键（switchUp/switchDown）时，
     * 才设置切台状态和重置跳过计数。
     */
    public void switchDown() {
        lastSwitchDirection = "down";
        isSwitchingChannel = true;
        autoSkipCount = 0; // 用户手动切台，重置跳过计数
        
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
    // ====================================================================
    // ✅ 2026-06-21 新增：添加到最近观看【加了调试日志】
    // ====================================================================
    /**
     * 添加到最近观看
     */
    private void addToRecent(String channelName) {
        // ✅ 日志 1：确认方法被调用
        SettingsActivity.logOperation("【最近观看】addToRecent 被调用，channelName=" + channelName);
        
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            appConfig.addRecentChannel(channelName);
            
            // ✅ 日志 2：添加成功
            List<String> recent = appConfig.getRecentChannels();
            SettingsActivity.logOperation("【最近观看】添加成功，当前最近观看数量=" + recent.size());
            
            // 更新分组列表的数量
            int favoriteCount = 0;
            int recentCount = 0;
            List<String> favorites = appConfig.getFavoriteChannels();
            // ✅ 新增：调试日志 - 看看为什么匹配不上
            SettingsActivity.logOperation("【最近-调试】recent.size=" + recent.size() 
                    + ", channelSourceList.size=" + channelSourceList.size());
            if (recent.size() > 0 && channelSourceList.size() > 0) {
                String firstRecent = recent.get(0);
                String firstChannel = channelSourceList.get(0).getName();
                SettingsActivity.logOperation("【最近-调试】第一个最近名：[" + firstRecent + "]");
                SettingsActivity.logOperation("【最近-调试】第一个源频道名：[" + firstChannel + "]");
                SettingsActivity.logOperation("【最近-调试】是否相等：" + firstRecent.equals(firstChannel));
                SettingsActivity.logOperation("【最近-调试】最近名长度：" + firstRecent.length() 
                        + ", 源频道名长度：" + firstChannel.length());
            }
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
            
            // ✅ 日志 3：数量更新完成
            SettingsActivity.logOperation("【最近观看】分组数量更新完成，收藏=" + favoriteCount 
                    + ", 最近观看=" + recentCount);
            
        } catch (Exception e) {
            // ✅ 日志 4：异常
            SettingsActivity.logOperation("【最近观看】添加失败，异常=" + e.getMessage());
        }
    }
    // ====================================================================
    // ✅ 2026-06-21 新增：长按收藏处理【加了调试日志】
    // ====================================================================
    /**
     * 处理频道长按事件（触屏模式收藏）
     *
     * @param channelName 被长按的频道名称
     * @param isRightPanel 是否是右侧面板
     * @return true 表示消费了事件
     */
    private boolean handleChannelLongClick(String channelName, boolean isRightPanel) {
        // ✅ 日志 1：确认方法被调用
        SettingsActivity.logOperation("【收藏】handleChannelLongClick 被调用，channelName=" 
                + channelName + ", isRightPanel=" + isRightPanel);
        
        if (channelName == null || channelName.isEmpty()) {
            SettingsActivity.logOperation("【收藏】handleChannelLongClick 失败：频道名为空");
            return false;
        }
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            boolean isFavorite = appConfig.toggleFavorite(channelName);
            
            // ✅ 日志 2：收藏操作结果
            SettingsActivity.logOperation("【收藏】长按操作结果=" + (isFavorite ? "已收藏" : "已取消"));
            
            // 更新分组列表的数量
            int favoriteCount = 0;
            int recentCount = 0;
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();
            // ✅ 新增：调试日志 - 看看为什么匹配不上
            SettingsActivity.logOperation("【收藏-调试】favorites.size=" + favorites.size() 
                    + ", channelSourceList.size=" + channelSourceList.size());
            if (favorites.size() > 0 && channelSourceList.size() > 0) {
                String firstFav = favorites.get(0);
                String firstChannel = channelSourceList.get(0).getName();
                SettingsActivity.logOperation("【收藏-调试】第一个收藏名：[" + firstFav + "]");
                SettingsActivity.logOperation("【收藏-调试】第一个源频道名：[" + firstChannel + "]");
                SettingsActivity.logOperation("【收藏-调试】是否相等：" + firstFav.equals(firstChannel));
                SettingsActivity.logOperation("【收藏-调试】收藏名长度：" + firstFav.length() 
                        + ", 源频道名长度：" + firstChannel.length());
            }
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
            
            // ✅ 日志 3：数量更新完成
            SettingsActivity.logOperation("【收藏】分组数量更新完成，收藏=" + favoriteCount 
                    + ", 最近观看=" + recentCount);
            
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
                if (!isRightPanel) {
                    channelListManager.setFilteredChannels(currentGroupChannelList, channelName);
                } else {
                    channelListManagerEpg.setFilteredChannels(currentGroupChannelList, channelName);
                }
                SettingsActivity.logOperation("【收藏】在收藏分组，已刷新频道列表");
            }
            SettingsActivity.logOperation("【收藏】长按" + (isFavorite ? "添加" : "取消")
                    + "收藏：" + channelName);
            return true;
        } catch (Exception e) {
            // ✅ 日志 4：异常
            SettingsActivity.logOperation("【收藏】长按操作失败，异常=" + e.getMessage());
            return false;
        }
    }
    /**
     * 切换当前频道的收藏状态（菜单键调用）
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
                // ✅ 2026-06-24 修改：点击频道列表，清除切台状态
                // 因为是用户主动选的，就算播不出来也不自动跳过
                lastSwitchDirection = "";
                isSwitchingChannel = false;
                autoSkipCount = 0;
                playChannel(globalIndex);
                togglePanel();
            }
        } else {
            // 右侧面板（全部频道模式）
            if (position < channelSourceList.size()) {
                Channel ch = channelSourceList.get(position);
                SettingsActivity.logOperation("【列表】点击频道：" + ch.getName());
                // ✅ 2026-06-24 修改：点击频道列表，清除切台状态
                // 因为是用户主动选的，就算播不出来也不自动跳过
                lastSwitchDirection = "";
                isSwitchingChannel = false;
                autoSkipCount = 0;
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
    /**
     * 切换面板显示/隐藏
     * 
     * 【2026-06-24 修复：用 syncFocusStyle() 统一设置初始焦点】
     * 【修复说明】
     * 原来直接调用各个 setFocused，容易漏设或者状态不一致。
     * 现在先清除所有样式，再用 syncFocusStyle() 统一同步，确保只有一个高亮。
     */
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
                    // 先清除所有焦点样式
                    clearAllFocusStyles();
                    // 设置初始焦点到左频道列表
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
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
    // 右侧面板是否打开
    // ====================================================================
    public boolean isRightPanelOpen() {
        return rightPanelOpen;
    }
    /**
     * EPG按钮被点击了（展开/收起节目单面板）
     * 
     * 【2026-06-24 修复：切换面板时先清除所有焦点样式】
     * 【修复说明】
     * 原来切换面板时只设置当前面板的焦点样式，
     * 另一个面板的样式没有清除，导致两个面板同时高亮。
     * 现在先调用 clearAllFocusStyles() 全部清除，再设置当前面板的样式。
     */
    private void onEpgButtonClicked() {
        if (!epgEnable) {
            SettingsActivity.logOperation("【EPG】节目单功能已关闭，无法展开");
            return;
        }
        if (!rightPanelOpen) {
            // 展开节目单面板
            llLeftPanel.setVisibility(View.GONE);
            llRightPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = true;
            epgPanelOpen = true;
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            llRightPanel.post(new Runnable() {
                @Override
                public void run() {
                    // 先清除所有焦点样式（包括左面板的）
                    clearAllFocusStyles();
                    // 设置初始焦点到右频道列表
                    currentFocusPanel = "right";
                    rightFocusView = "channel";
                    syncFocusStyle();
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
            // 收起节目单面板
            llRightPanel.setVisibility(View.GONE);
            llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            llLeftPanel.post(new Runnable() {
                @Override
                public void run() {
                    // 先清除所有焦点样式（包括右面板的）
                    clearAllFocusStyles();
                    // 设置焦点到左频道列表
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                }
            });
            SettingsActivity.logOperation("【面板】收起节目单面板");
        }
    }
    /**
     * 返回分组按钮被点击了
     * 
     * 【2026-06-24 修复：焦点状态和实际焦点一致】
     * 【修复的问题】
     * 原来请求了 lvChannelList 的焦点，但 leftFocusView 却设置成了 "epgBtn"，
     * 导致状态不一致，样式也不对。
     * 
     * 【修复方案】
     * 1. 返回后焦点统一在左频道列表（和收起节目单保持一致）
     * 2. 先清除所有样式，再用 syncFocusStyle() 同步
     * 3. 确保请求的焦点和设置的状态一致
     */
    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            llRightPanel.setVisibility(View.GONE);
            llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            llLeftPanel.post(new Runnable() {
                @Override
                public void run() {
                    // 先清除所有焦点样式（包括右面板的）
                    clearAllFocusStyles();
                    // 返回后，焦点在左频道列表（和收起节目单保持一致）
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
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
    // ✅ 2026-06-24 新增：自动跳过失效频道相关方法
    // ====================================================================
    /**
     * 播放成功回调
     * 
     * 【作用】
     * 播放成功时调用，重置切台标志和自动跳过计数。
     * 说明当前频道是有效的，不需要再自动跳过了。
     * 
     * 【调用时机】
     * MainActivity 监听到 onPlayReady 时调用。
     * 
     * 【为什么需要重置？】
     * 1. isSwitchingChannel = false：标记为正常播放状态，
     *    之后如果播放失败（比如网络波动），就不自动跳过了。
     * 2. autoSkipCount = 0：重置跳过计数，
     *    下次用户再切台时重新计数。
     */
    public void onPlaySuccess() {
        isSwitchingChannel = false;
        autoSkipCount = 0;
    }

    /**
     * 是否可以自动跳过失效频道
     * 
     * @return true = 可以自动跳过
     *         false = 不能自动跳过
     *         
     * 【判断条件】
     * 1. 必须是切台状态（刚切完还没播放成功）
     * 2. 有明确的切台方向（up/down）
     * 3. 未达到最大自动跳过次数
     * 
     * 【为什么需要这三个条件？】
     * 1. isSwitchingChannel：只有切台时遇到失效源才跳过，
     *    正常播放中突然失效的不跳过（用户可能还想看）。
     * 2. lastSwitchDirection：知道往哪个方向继续切，
     *    如果是点击频道列表切换的，方向为空，就不跳过。
     * 3. autoSkipCount < MAX_AUTO_SKIP：防止无限循环，
     *    比如所有频道都失效了，就停下来让用户检查。
     */
    public boolean canAutoSkip() {
        return isSwitchingChannel 
                && !"".equals(lastSwitchDirection) 
                && autoSkipCount < MAX_AUTO_SKIP;
    }

    /**
     * 自动跳过失效频道
     * 
     * 【作用】
     * 切台时遇到失效直播源，根据切台方向自动继续切到下一个/上一个频道。
     * 
     * 【逻辑】
     * 1. 判断是否可以自动跳过（canAutoSkip）
     * 2. 自动跳过计数 +1
     * 3. 根据 lastSwitchDirection 和 isReverse 决定继续向上还是向下切
     * 4. 调用 playPrev() / playNext() 继续切台
     * 
     * 【为什么调用 playPrev/playNext 而不是 switchUp/switchDown？】
     * switchUp/switchDown 会重新设置 isSwitchingChannel = true，
     * 还会重置 autoSkipCount = 0，这样就会无限循环了。
     * playPrev/playNext 是底层切台方法，只负责切台，不改变状态标记，
     * 正好适合自动跳过的场景。
     * 
     * 【反转开关怎么处理？】
     * 自动跳过时也要考虑反转状态，保持和用户切台时的方向一致。
     * 比如用户按下键（反转开启时实际是上一台），
     * 那自动跳过时也应该继续往上切，而不是往下切。
     * 
     * @return true = 已经自动切换了
     *         false = 不能继续跳过
     */
    public boolean autoSkipFailedChannel() {
        if (!canAutoSkip()) {
            SettingsActivity.logOperation("【切台】自动跳过失败，已跳过 " 
                    + autoSkipCount + " 个，达到上限或不是切台状态");
            return false;
        }
        
        autoSkipCount++;
        SettingsActivity.logOperation("【切台】自动跳过失效频道（第" 
                + autoSkipCount + "次），方向：" + lastSwitchDirection
                + "，反转：" + (isReverse ? "开启" : "关闭"));
        
        if ("up".equals(lastSwitchDirection)) {
            // 向上切台时遇到失效 → 继续向上切
            if (isReverse) {
                // 反转开启：上 = 下一台
                playNext();
            } else {
                // 反转关闭：上 = 上一台
                playPrev();
            }
        } else if ("down".equals(lastSwitchDirection)) {
            // 向下切台时遇到失效 → 继续向下切
            if (isReverse) {
                // 反转开启：下 = 上一台
                playPrev();
            } else {
                // 反转关闭：下 = 下一台
                playNext();
            }
        } else {
            return false;
        }
        
        return true;
    }
    // ====================================================================
    // 6. 按键事件分发
    // ====================================================================
    /**
     * 分发按键事件
     * 
     * 【作用】
     * MainActivity 把遥控器按键传进来，这里统一处理。
     * 只有面板打开时才处理，返回 true 表示消费了事件。
     * 
     * @param keyCode 按键码（KeyEvent.KEYCODE_xxx）
     * @return true 表示消费了事件，false 表示没消费
     */
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
    // 7. 监听器设置
    // ====================================================================
    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }
    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }
    // ====================================================================
    // 8. 资源释放
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
