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
 * 2. 频道切换（上/下切台、分组内循环、防抖、反转）← ✅ 合并了 ChannelSwitchManager
 * 3. 面板控制（显示/隐藏、EPG 展开/收起、列表点击）
 * 4. 焦点管理（手机触屏 + 电视遥控器）
 * 5. 按键处理（面板打开/关闭都能处理，统一入口）← ✅ 合并了 KeyEventManager
 *
 * 【合并说明】
 * 1. 原 ChannelSwitchManager 的功能已全部合并到本类中：
 *    - 频道列表管理
 *    - 当前索引管理
 *    - 切换防抖
 *    - 换台反转
 *    - switchUp() / switchDown() 统一入口
 *
 * 2. 原 KeyEventManager 的功能已全部合并到本类中：
 *    - 面板关闭时：上下键切台（带反转）、OK键打开面板、Menu键打开设置
 *    - 面板打开时：左右键移焦点、OK键选中
 *    - 所有按键统一走 dispatchKeyEvent() 入口
 *
 * 【为什么合并？】
 * 1. 统一管理：所有频道相关逻辑都在一个地方，方便维护
 * 2. 避免不同步：反转设置等不会出现两套逻辑不一致
 * 3. 减少文件：少了 ChannelSwitchManager.java 和 KeyEventManager.java
 * 4. 调用简单：MainActivity 只需要调用一个 dispatchKeyEvent() 方法
 *
 * 【五层逻辑闭环】
 * 1. 状态管理层：分组选中状态、面板显示状态、EPG 展开状态、当前播放索引、焦点位置
 * 2. 数据筛选层：按分组筛选频道列表、按日期筛选 EPG
 * 3. 状态同步层：分组切换→频道列表更新、切台→选中状态同步、面板切换→焦点转移
 * 4. 异常兜底层：空列表兜底、索引越界保护、防抖保护、焦点丢失兜底
 * 5. 交互闭环层：点击、按键、手势都触发对应状态更新
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
     * 频道列表（节目单页面，右侧面板用）
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
     * 返回分组按钮（右侧面板最左边）
     *
     * 【作用】
     * 右侧面板（节目单页面）最左边的返回按钮，点击后切回左侧面板（分组列表）。
     * 文字是竖排的"频道组"。
     */
    private TextView btnBackGroup;

    // ====================== 左右面板切换 ======================

    /**
     * 左侧面板容器（分组 + 频道列表 + 节目单按钮）
     *
     * 【结构】
     * 水平排列：分组列表 + 频道列表 + 节目单按钮
     */
    private View llLeftPanel;

    /**
     * 右侧面板容器（返回按钮 + 频道列表 + 日期 + EPG）
     *
     * 【结构】
     * 水平排列：返回按钮 + 频道列表 + 日期列表 + EPG列表
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

    /** 频道列表管理器（节目单页面，右侧面板用） */
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
     * EPG 面板是否展开（兼容旧代码，和 rightPanelOpen 保持同步）
     */
    private boolean epgPanelOpen = false;

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
     * 【为什么把反转逻辑放在这里？】
     * 所有频道切换相关的逻辑都统一在本类管理，
     * 反转是切换行为的一部分，应该放在这里统一管理。
     * 这样外部只需要调用 switchUp() 和 switchDown()，
     * 不用关心内部是加还是减。
     */
    private boolean isReverse = false;

    // ====================== 切台防抖 ======================

    /** 上次频道切换时间 */
    private long lastChannelChangeTime = 0;

    // ====================== 焦点管理 ======================

    /**
     * 当前焦点在哪个面板
     *
     * 【值说明】
     * - "left"：焦点在左侧面板
     * - "right"：焦点在右侧面板
     */
    private String currentFocusPanel = "left";

    /**
     * 当前焦点在左侧面板的哪个视图
     *
     * 【值说明】
     * - "group"：焦点在分组列表
     * - "channel"：焦点在频道列表
     * - "epgBtn"：焦点在节目单按钮
     */
    private String leftFocusView = "channel";

    /**
     * 当前焦点在右侧面板的哪个视图
     *
     * 【值说明】
     * - "backBtn"：焦点在返回按钮
     * - "channel"：焦点在频道列表
     * - "date"：焦点在日期列表
     * - "epg"：焦点在EPG列表
     */
    private String rightFocusView = "channel";

    // ====================== 回调监听器 ======================

    /** 频道切换监听器 */
    private OnChannelChangeListener channelChangeListener;

    /** 面板状态监听器 */
    private OnPanelStateListener panelStateListener;

    /**
     * 面板动作监听器（打开设置等）
     * 
     * 【作用】
     * 当用户触发某些动作（比如按 Menu 键打开设置）时，
     * 通过回调通知外部（MainActivity）去执行。
     * 
     * 【为什么用回调而不是直接引用 MainActivity？】
     * 解耦：ChannelPanelController 不需要知道 MainActivity 的存在，
     * 只需要通过回调通知外部，外部自己决定怎么处理。
     */
    private OnPanelActionListener panelActionListener;

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

    /**
     * 面板动作监听器
     * 
     * 【作用】
     * 当用户触发某些需要外部处理的动作时，通过回调通知。
     * 
     * 【目前支持的动作】
     * - onOpenSettings()：打开设置页面
     */
    public interface OnPanelActionListener {
        /**
         * 打开设置页面
         * 
         * 【触发时机】
         * 用户按 Menu 键时触发
         */
        void onOpenSettings();
    }

    // ====================== 构造函数 ======================

    /**
     * 构造函数
     *
     * @param context               上下文
     * @param panelLayout           面板根布局
     * @param llLeftPanel           左侧面板容器
     * @param llRightPanel          右侧面板容器
     * @param lvGroup               分组列表
     * @param lvChannelList         频道列表（主页面）
     * @param lvChannelListEpg      频道列表（节目单页面）
     * @param lvDate                日期列表
     * @param lvEpg                 EPG 节目列表
     * @param btnShowEpg            节目单展开按钮
     * @param btnBackGroup          返回分组按钮
     * @param groupListManager      分组列表管理器
     * @param channelListManager    频道列表管理器（主页面）
     * @param channelListManagerEpg 频道列表管理器（节目单页面）
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

        // 初始化点击事件
        initClickListeners();
        // 初始化焦点监听
        initFocusListeners();
    }

    // ====================================================================
    // 1. 初始化点击事件
    // ====================================================================

    /**
     * 初始化所有点击事件
     */
    private void initClickListeners() {
        // 分组列表点击
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onGroupClicked(position);
            }
        });

        // 主页面频道列表点击
        lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });

        // 节目单页面频道列表点击
        lvChannelListEpg.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });

        // 节目单按钮点击
        btnShowEpg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onEpgButtonClicked();
            }
        });

        // 返回分组按钮点击
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

    /**
     * 初始化所有焦点变化监听
     *
     * 【作用】
     * 当某个视图获取焦点时，记录当前焦点的位置，
     * 方便后续处理左右键时，知道该往哪个视图移焦点。
     */
    private void initFocusListeners() {
        // 分组列表焦点变化
        lvGroup.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "group";
                }
            }
        });

        // 主页面频道列表焦点变化
        lvChannelList.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                }
            }
        });

        // 节目单按钮焦点变化
        btnShowEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "left";
                    leftFocusView = "epgBtn";
                }
            }
        });

        // 节目单页面频道列表焦点变化
        lvChannelListEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "channel";
                }
            }
        });

        // 日期列表焦点变化
        lvDate.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "date";
                }
            }
        });

        // EPG列表焦点变化
        lvEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    currentFocusPanel = "right";
                    rightFocusView = "epg";
                }
            }
        });

        // 返回按钮焦点变化
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
     * @param channels 全部频道列表
     */
    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;

        // 更新分组列表
        groupListManager.setGroups(channels);

        // 更新主页面频道列表
        channelListManager.setChannels(channels, currentPlayIndex);

        // 同步更新节目单页面的频道列表
        channelListManagerEpg.setChannels(channels, currentPlayIndex);
    }

    /**
     * 分组被点击了
     *
     * @param position 分组位置
     */
    private void onGroupClicked(int position) {
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);

        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;

        // 筛选当前分组的频道
        currentGroupChannelList.clear();
        for (Channel c : channelSourceList) {
            if (groupName.equals(c.getGroup())) {
                currentGroupChannelList.add(c);
            }
        }

        // 更新主页面频道列表
        channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);

        SettingsActivity.logOperation("【分组】选中分组：" + groupName
                + "，频道数：" + currentGroupChannelList.size());
    }

    /**
     * 获取当前选中的分组名称
     */
    public String getCurrentGroupName() {
        return currentGroupName;
    }

    /**
     * 获取当前分组的频道列表
     */
    public List<Channel> getCurrentGroupChannels() {
        return currentGroupChannelList;
    }

    /**
     * 设置 EPG 功能是否启用
     */
    public void setEpgEnable(boolean enable) {
        this.epgEnable = enable;
    }

    // ====================================================================
    // ✅ 换台反转设置（合并自 ChannelSwitchManager）
    // ====================================================================

    /**
     * 设置是否开启换台反转
     *
     * @param reverse true = 开启反转，false = 关闭反转
     *
     * 【调用时机】
     * 1. App 启动时，MainActivity.loadSettings() 读取设置后调用
     * 2. 从设置页面返回时，MainActivity.onResume() 重新加载设置后调用
     */
    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
    }

    /**
     * 获取当前反转状态
     */
    public boolean isReverse() {
        return isReverse;
    }

    // ====================================================================
    // 3. 频道切换相关（核心，合并自 ChannelSwitchManager）
    // ====================================================================

    /**
     * 播放上一个频道（分组内循环）
     *
     * 【注意】这是底层方法，直接切换到上一台，不考虑反转。
     * 如果需要考虑反转，请调用 switchUp() 或 switchDown()。
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
    // ✅ 带反转的切台方法（统一入口）
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
     */
    public void switchUp() {
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
     * 【逻辑】
     * - 反转关闭：下键 = 下一台（playNext）
     * - 反转开启：下键 = 上一台（playPrev）
     */
    public void switchDown() {
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

        // 同步更新节目单页面的频道列表选中状态
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
     * 【区分左右面板的点击逻辑】
     * - 左侧面板点击频道：按分组筛选，点击后关闭面板
     * - 右侧面板点击频道：全部频道，点击后不关闭面板
     */
    private void onChannelClicked(int position) {
        if (!currentGroupChannelList.isEmpty() && position < currentGroupChannelList.size()
                && !rightPanelOpen) {
            // 左侧面板（分组筛选模式）
            Channel selectedChannel = currentGroupChannelList.get(position);
            int globalIndex = channelSourceList.indexOf(selectedChannel);
            if (globalIndex != -1) {
                SettingsActivity.logOperation("【列表】点击频道：" + selectedChannel.getName());
                playChannel(globalIndex);
                togglePanel();  // 左侧面板点击后关闭面板
            }
        } else {
            // 右侧面板（全部频道模式）
            if (position < channelSourceList.size()) {
                Channel ch = channelSourceList.get(position);
                SettingsActivity.logOperation("【列表】点击频道：" + ch.getName());
                playChannel(position);
                // 注意：这里不关闭面板，方便继续看节目单
            }
        }
    }

    /**
     * 获取当前播放的频道索引
     */
    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    /**
     * 设置当前播放的频道索引
     */
    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
    }

    /**
     * 设置数字选台的总频道数
     */
    public void setTotalChannelCount(int count) {
        // 预留方法，给 ChannelNumberManager 用
    }

    // ====================================================================
    // 4. 面板控制相关
    // ====================================================================

    /**
     * 切换面板显示/隐藏
     */
    public void togglePanel() {
        // 先更新两个频道列表的选中状态
        if (!currentGroupName.isEmpty() && !currentGroupChannelList.isEmpty()) {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        } else {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        }

        // 同步更新节目单页面的频道列表
        channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);

        boolean isOpen = isPanelOpen();
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);

        // 打开面板时自动请求焦点
        if (!isOpen) {
            panelLayout.post(new Runnable() {
                @Override
                public void run() {
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                }
            });
        }

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
     */
    public boolean isPanelOpen() {
        return panelLayout.getVisibility() == View.VISIBLE;
    }

    /**
     * 节目单按钮被点击了
     */
    private void onEpgButtonClicked() {
        if (!epgEnable) {
            SettingsActivity.logOperation("【EPG】节目单功能已关闭，无法展开");
            return;
        }

        if (!rightPanelOpen) {
            // 切换到右侧面板（节目单页面）
            llLeftPanel.setVisibility(View.GONE);
            llRightPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = true;
            epgPanelOpen = true;

            // 同步选中状态
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);

            // 自动移焦点
            llRightPanel.post(new Runnable() {
                @Override
                public void run() {
                    lvChannelListEpg.requestFocus();
                    lvChannelListEpg.setSelection(currentPlayIndex);
                }
            });

            SettingsActivity.logOperation("【面板】展开节目单面板");

            // 刷新 EPG
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        } else {
            // 切回左侧面板
            llRightPanel.setVisibility(View.GONE);
            llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;

            // 自动移焦点
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

    /**
     * 返回分组按钮被点击了
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
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                }
            });

            SettingsActivity.logOperation("【面板】返回频道分组");
        }
    }

    /**
     * EPG 面板是否展开（兼容旧代码）
     */
    public boolean isEpgPanelOpen() {
        return epgPanelOpen;
    }

    /**
     * 设置当前选中的日期索引
     */
    public void setCurrentDateIndex(int index) {
        this.currentSelectedDateIndex = index;
        panelManager.setCurrentDateIndex(index);

        // 刷新 EPG
        if (!channelSourceList.isEmpty()
                && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel curr = channelSourceList.get(currentPlayIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
        }
    }

    /**
     * 获取当前选中的日期索引
     */
    public int getCurrentSelectedDateIndex() {
        return currentSelectedDateIndex;
    }

    // ====================================================================
    // 辅助方法 - 获取频道列表选中位置
    // ====================================================================

    /**
     * 获取当前频道列表应该选中的位置
     */
    private int getChannelListSelection() {
        if (!currentGroupName.isEmpty() && !currentGroupChannelList.isEmpty()) {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
                    return i;
                }
            }
            return 0;
        } else {
            return currentPlayIndex;
        }
    }

    // ====================================================================
    // 5. 返回键处理
    // ====================================================================

    /**
     * 处理返回键
     *
     * 【分级返回逻辑】
     * 1. 如果右侧面板展开着 → 先收起右侧面板，回到分组列表
     * 2. 如果只有左侧面板打开着 → 关闭整个面板
     *
     * @return 是否处理了返回键
     */
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
    // ✅ 按键事件分发（合并了 KeyEventManager 的全部功能）
    // ====================================================================

    /**
     * 分发按键事件（统一入口，面板打开/关闭都能处理）
     *
     * 【合并说明】
     * 把原 KeyEventManager 的功能全部合并到这里，
     * 所有按键都统一走这个方法，避免两套逻辑不同步。
     *
     * 【按键逻辑总览】
     * ╔═════════════╦════════════════════════════════════════╗
     * ║   按键      ║           面板关闭时                    ║
     * ╠═════════════╬════════════════════════════════════════╣
     * ║ 上键        ║ 上一台（switchUp，自动考虑反转）       ║
     * ║ 下键        ║ 下一台（switchDown，自动考虑反转）     ║
     * ║ OK/Enter    ║ 打开面板（togglePanel）                ║
     * ║ 左键/右键   ║ 打开面板（togglePanel）                ║
     * ║ Menu        ║ 打开设置（回调 onOpenSettings）        ║
     * ╚═════════════╩════════════════════════════════════════╝
     *
     * ╔═════════════╦════════════════════════════════════════╗
     * ║   按键      ║           面板打开时                    ║
     * ╠═════════════╬════════════════════════════════════════╣
     * ║ 上键        ║ ListView 自己处理（列表内移动焦点）     ║
     * ║ 下键        ║ ListView 自己处理（列表内移动焦点）     ║
     * ║ OK/Enter    ║ 选中当前项（handleOkKey）              ║
     * ║ 左键        ║ 往左移焦点（handleLeftKey）            ║
     * ║ 右键        ║ 往右移焦点（handleRightKey）           ║
     * ║ Menu        ║ 打开设置（回调 onOpenSettings）        ║
     * ╚═════════════╩════════════════════════════════════════╝
     *
     * 【为什么合并？】
     * 1. 反转逻辑统一管理：所有切台入口都走 switchUp()/switchDown()，反转肯定生效
     * 2. 减少文件：可以删掉 KeyEventManager.java
     * 3. 调用简单：MainActivity 只需要调用这一个方法
     * 4. 避免不同步：不会出现"KeyEventManager 改了，ChannelPanelController 没改"的情况
     *
     * @param keyCode 按键码
     * @return 是否处理了按键（true=已处理）
     */
    public boolean dispatchKeyEvent(int keyCode) {
        // ================================================================
        // 1. Menu 键：任何时候都能打开设置
        // ================================================================
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (panelActionListener != null) {
                panelActionListener.onOpenSettings();
            }
            return true;
        }

        // ================================================================
        // 2. 面板打开时：处理左右键移焦点 + OK键选中
        // ================================================================
        if (isPanelOpen()) {
            switch (keyCode) {
                // 左键：往左移焦点
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return handleLeftKey();

                // 右键：往右移焦点
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return handleRightKey();

                // OK键/确认键：选中当前项
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    return handleOkKey();

                // 上下键：ListView 自己处理，我们不处理，返回 false
                // 这样 ListView 就能收到按键事件，自己移动焦点
                default:
                    return false;
            }
        }

        // ================================================================
        // 3. 面板关闭时：处理上下键切台 + OK键打开面板
        // ✅ 这部分是从 KeyEventManager 合并过来的
        // ================================================================
        else {
            switch (keyCode) {
                // 上键：上一台（自动考虑反转）
                // 【原来在 KeyEventManager 里】activity.playPrev();
                // 【现在改成】switchUp(); 内部自动判断 isReverse
                case KeyEvent.KEYCODE_DPAD_UP:
                    switchUp();
                    return true;

                // 下键：下一台（自动考虑反转）
                // 【原来在 KeyEventManager 里】activity.playNext();
                // 【现在改成】switchDown(); 内部自动判断 isReverse
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    switchDown();
                    return true;

                // OK键/确认键：打开面板
                // 【原来在 KeyEventManager 里】activity.togglePanel();
                // 【现在改成】直接调用本类的 togglePanel()
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    togglePanel();
                    return true;

                // 左键/右键：也可以打开面板（方便用户操作）
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    togglePanel();
                    return true;

                // 其他键：不处理
                default:
                    return false;
            }
        }
    }

    /**
     * 处理左键（往左移焦点）
     */
    private boolean handleLeftKey() {
        if ("left".equals(currentFocusPanel)) {
            // 左侧面板：从右往左移
            if ("epgBtn".equals(leftFocusView)) {
                lvChannelList.requestFocus();
                return true;
            } else if ("channel".equals(leftFocusView)) {
                lvGroup.requestFocus();
                return true;
            }
        } else if ("right".equals(currentFocusPanel)) {
            // 右侧面板：从右往左移
            if ("epg".equals(rightFocusView)) {
                lvDate.requestFocus();
                return true;
            } else if ("date".equals(rightFocus            } else if ("date".equals(rightFocusView)) {
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
     * 处理右键（往右移焦点）
     *
     * 【逻辑】
     * 从左往右移焦点，比如：
     * - 左侧面板：分组列表 → 频道列表 → 节目单按钮
     * - 右侧面板：返回按钮 → 频道列表 → 日期列表 → EPG列表
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
     * - 分组列表：切换分组
     * - 频道列表：切换频道
     * - 日期列表：切换日期
     * - 节目单按钮：切换到节目单页面
     * - 返回按钮：返回分组列表
     *
     * 【为什么要自己处理OK键？】
     * 因为 ListView 的 onItemClickListener 是在点击时触发的，
     * 遥控器的 OK 键不一定会触发 onItemClick，
     * 所以需要我们自己处理 OK 键，模拟点击效果。
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
                // 频道列表：选中当前频道
                int pos = lvChannelList.getSelectedItemPosition();
                if (pos >= 0) {
                    onChannelClicked(pos);
                    return true;
                }
            } else if ("epgBtn".equals(leftFocusView)) {
                // 节目单按钮：点击按钮
                onEpgButtonClicked();
                return true;
            }
        } else if ("right".equals(currentFocusPanel)) {
            // 右侧面板
            if ("backBtn".equals(rightFocusView)) {
                // 返回按钮：点击按钮
                onBackGroupClicked();
                return true;
            } else if ("channel".equals(rightFocusView)) {
                // 频道列表：选中当前频道
                int pos = lvChannelListEpg.getSelectedItemPosition();
                if (pos >= 0) {
                    onChannelClicked(pos);
                    return true;
                }
            } else if ("date".equals(rightFocusView)) {
                // 日期列表：选中当前日期
                int pos = lvDate.getSelectedItemPosition();
                if (pos >= 0) {
                    setCurrentDateIndex(pos);
                    return true;
                }
            } else if ("epg".equals(rightFocusView)) {
                // EPG列表：选中当前节目
                // 这里可以加 EPG 节目的点击逻辑
                // 比如点击回看节目，跳转到对应时间播放
                // 暂时先留空，以后有需要再加
                int pos = lvEpg.getSelectedItemPosition();
                if (pos >= 0) {
                    // TODO: EPG节目点击逻辑
                    return true;
                }
            }
        }
        return false;
    }

    // ====================================================================
    // 6. 监听器设置
    // ====================================================================

    /**
     * 设置频道切换监听器
     */
    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    /**
     * 设置面板状态监听器
     */
    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    /**
     * 设置面板动作监听器
     * 
     * 【作用】
     * 当用户触发某些需要外部处理的动作时，通过回调通知。
     * 目前支持：onOpenSettings() - 打开设置页面
     * 
     * 【为什么需要这个监听器？】
     * 合并 KeyEventManager 后，Menu 键打开设置的功能也移到了这里，
     * 但是 ChannelPanelController 不能直接启动 SettingsActivity，
     * 需要通过回调通知外部（MainActivity）来处理。
     */
    public void setOnPanelActionListener(OnPanelActionListener listener) {
        this.panelActionListener = listener;
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
        lvChannelListEpg = null;
        lvDate = null;
        lvEpg = null;
        btnShowEpg = null;
        btnBackGroup = null;
        channelSourceList = null;
        currentGroupChannelList = null;
        channelChangeListener = null;
        panelStateListener = null;
        panelActionListener = null;
        groupListManager = null;
        channelListManager = null;
        channelListManagerEpg = null;
        dateListManager = null;
        epgManagerWrapper = null;
        panelManager = null;
    }
}
