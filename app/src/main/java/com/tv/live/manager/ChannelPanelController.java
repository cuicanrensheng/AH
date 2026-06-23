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
 * 【职责】
 * 统一管理所有和频道面板相关的逻辑，包括：
 * 1. 分组管理（分组列表、选中状态、分组筛选）
 * 2. 频道切换（上/下切台、分组内循环、反转）
 * 3. 面板控制（显示/隐藏、EPG 展开/收起、列表点击）
 * 4. 焦点管理（手机触屏 + 电视遥控器）
 * 5. 按键处理（左右键移动焦点、OK键选中、菜单键收藏）
 *
 * 【2026-06-21 新增：收藏 + 最近观看 + 菜单键】
 * 【2026-06-21 新增：长按收藏（触屏模式）】
 * 【2026-06-22 修改：取消切台防抖拦截】
 * 
 * 【2026-06-22 修复：特殊分组下切台循环错误】
 * 【问题原因】
 * 之前 playNext/playPrev 是用频道自身的 group 字段来筛选分组的，
 * 导致在「收藏」「最近观看」等特殊分组里切台时，会跑到频道自身的普通分组里循环，
 * 而不是在当前显示的分组列表里循环。
 * 
 * 【解决方案】
 * 1. 切台时统一使用 currentGroupChannelList（当前显示的分组列表）
 * 2. 先找到当前频道在分组列表中的索引，再计算上/下一个
 * 3. 确保在哪个分组里切台，就在哪个分组里循环
 * 
 * 【效果】
 * 「全部」→ 在全部频道里循环
 * 「收藏」→ 在收藏列表里循环
 * 「最近观看」→ 在最近观看列表里循环
 * 普通分组 → 在该分组里循环
 * 
 * 【2026-06-22 修复：初始化时当前分组状态为空】
 * 【问题原因】
 * setChannels() 里只初始化了分组列表和频道列表，
 * 但是没有初始化 currentGroupName 和 currentGroupChannelList，
 * 导致刚进入 App 还没点击过分组时，切台逻辑可能出问题。
 * 
 * 【解决方案】
 * setChannels() 末尾默认选中「全部」分组，并初始化 currentGroupChannelList。
 */
public class ChannelPanelController {
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
        channelListManager.setOnChannelLongClickListener(new ChannelListManager.OnChannelLongClickListener() {
            @Override
            public boolean onChannelLongClick(String channelName, int position) {
                SettingsActivity.logOperation("【面板】左侧长按回调触发，channelName=" + channelName);
                return handleChannelLongClick(channelName, false);
            }
        });
        channelListManagerEpg.setOnChannelLongClickListener(new ChannelListManager.OnChannelLongClickListener() {
            @Override
            public boolean onChannelLongClick(String channelName, int position) {
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
     * 【2026-06-22 修复：默认初始化当前分组为「全部」】
     * 【问题原因】
     * 之前只初始化了分组列表，没有设置当前分组状态，
     * 导致刚进入 App 还没点击分组时，currentGroupChannelList 为空，
     * 切台逻辑可能出问题。
     * 
     * 【解决方案】
     * 设置完分组列表后，默认选中「全部」分组，并初始化 currentGroupChannelList。
     */
    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;

        int favoriteCount = 0;
        int recentCount = 0;
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();
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

        groupListManager.setGroups(channels, favoriteCount, recentCount);
        channelListManager.setChannels(channels, currentPlayIndex);
        channelListManagerEpg.setChannels(channels, currentPlayIndex);

        // ✅ 新增：默认初始化当前分组为「全部」
        currentGroupName = GroupListManager.GROUP_ALL;
        currentGroupChannelList.clear();
        currentGroupChannelList.addAll(channels);
        SettingsActivity.logOperation("【分组】初始化完成，默认选中「全部」，频道数：" + channels.size());
    }

    /**
     * 分组被点击了
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
            // 「收藏」分组
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
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
            SettingsActivity.logOperation("【分组】选中「收藏」，频道数：" + currentGroupChannelList.size());
        } else if (GroupListManager.GROUP_RECENT.equals(groupName)) {
            // 「最近观看」分组
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
     * 播放上一个频道（在当前显示的分组内循环）
     * 
     * 【2026-06-22 修复：在当前分组内循环，不是按频道自身的 group 循环】
     */
    public void playPrev() {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            SettingsActivity.logOperation("【切台】playPrev 失败：频道列表为空");
            return;
        }

        // ✅ 使用当前显示的分组列表
        List<Channel> groupChannels = currentGroupChannelList;
        if (groupChannels == null || groupChannels.isEmpty()) {
            // 兜底：如果当前分组列表为空，用全部频道
            groupChannels = channelSourceList;
        }

        if (groupChannels.size() <= 1) {
            SettingsActivity.logOperation("【切台】playPrev 失败：分组内只有1个频道");
            return;
        }

        // 获取当前播放的频道
        Channel currentChannel = null;
        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            currentChannel = channelSourceList.get(currentPlayIndex);
        }
        if (currentChannel == null) {
            SettingsActivity.logOperation("【切台】playPrev 失败：当前频道为空");
            return;
        }

        // 找到当前频道在分组列表中的索引
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) {
            // 当前频道不在这个分组里（比如从普通分组切到收藏，当前频道没收藏）
            // 兜底：从第一个开始
            groupIndex = 0;
        }

        // 计算上一个频道的索引（分组内循环）
        int prevGroupIndex = (groupIndex - 1 + groupChannels.size()) % groupChannels.size();
        Channel prevChannel = groupChannels.get(prevGroupIndex);

        // 找到全局索引
        int globalIndex = findChannelGlobalIndex(prevChannel);
        if (globalIndex != -1) {
            SettingsActivity.logOperation("【切台】playPrev 上一台 → " 
                    + currentPlayIndex + " → " + globalIndex 
                    + "（" + prevChannel.getName() + "）"
                    + "，分组：" + currentGroupName);
            playChannel(globalIndex);
        }
    }

    /**
     * 播放下一个频道（在当前显示的分组内循环）
     * 
     * 【2026-06-22 修复：在当前分组内循环，不是按频道自身的 group 循环】
     */
    public void playNext() {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            SettingsActivity.logOperation("【切台】playNext 失败：频道列表为空");
            return;
        }

        // ✅ 使用当前显示的分组列表
        List<Channel> groupChannels = currentGroupChannelList;
        if (groupChannels == null || groupChannels.isEmpty()) {
            // 兜底：如果当前分组列表为空，用全部频道
            groupChannels = channelSourceList;
        }

        if (groupChannels.size() <= 1) {
            SettingsActivity.logOperation("【切台】playNext 失败：分组内只有1个频道");
            return;
        }

        // 获取当前播放的频道
        Channel currentChannel = null;
        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            currentChannel = channelSourceList.get(currentPlayIndex);
        }
        if (currentChannel == null) {
            SettingsActivity.logOperation("【切台】playNext 失败：当前频道为空");
            return;
        }

        // 找到当前频道在分组列表中的索引
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) {
            // 当前频道不在这个分组里，兜底：从第一个开始
            groupIndex = 0;
        }

        // 计算下一个频道的索引（分组内循环）
        int nextGroupIndex = (groupIndex + 1) % groupChannels.size();
        Channel nextChannel = groupChannels.get(nextGroupIndex);

        // 找到全局索引
        int globalIndex = findChannelGlobalIndex(nextChannel);
        if (globalIndex != -1) {
            SettingsActivity.logOperation("【切台】playNext 下一台 → " 
                    + currentPlayIndex + " → " + globalIndex 
                    + "（" + nextChannel.getName() + "）"
                    + "，分组：" + currentGroupName);
            playChannel(globalIndex);
        }
    }

    /**
     * ✅ 辅助方法：根据频道对象找到全局索引
     */
    private int findChannelGlobalIndex(Channel channel) {
        if (channel == null || channelSourceList == null) return -1;
        for (int i = 0; i < channelSourceList.size(); i++) {
            if (channelSourceList.get(i).getName().equals(channel.getName())) {
                return i;
            }
        }
        return -1;
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
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null) return;

        // 切换频道后同步分组选中状态
        // 注意：特殊分组（全部/收藏/最近观看）下切台，不自动切换分组
        String channelGroup = ch.getGroup();
        if (channelGroup != null && !channelGroup.isEmpty()) {
            boolean isSpecialGroup = GroupListManager.GROUP_ALL.equals(currentGroupName)
                    || GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                    || GroupListManager.GROUP_RECENT.equals(currentGroupName)
                    || currentGroupName.isEmpty();
            if (!isSpecialGroup && !channelGroup.equals(currentGroupName)) {
                // 普通分组下切台，且分组不一致 → 同步切换分组
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

        // 添加到最近观看
        addToRecent(ch.getName());
    }
    // ====================================================================
    // 最近观看
    // ====================================================================
    private void addToRecent(String channelName) {
        SettingsActivity.logOperation("【最近观看】addToRecent 被调用，channelName=" + channelName);
        
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            appConfig.addRecentChannel(channelName);
            
            List<String> recent = appConfig.getRecentChannels();
            SettingsActivity.logOperation("【最近观看】添加成功，当前最近观看数量=" + recent.size());
            
            int favoriteCount = 0;
            int recentCount = 0;
            List<String> favorites = appConfig.getFavoriteChannels();
            
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
            
            SettingsActivity.logOperation("【最近观看】分组数量更新完成，收藏=" + favoriteCount 
                    + ", 最近观看=" + recentCount);
            
        } catch (Exception e) {
            SettingsActivity.logOperation("【最近观看】添加失败，异常=" + e.getMessage());
        }
    }
    // ====================================================================
    // 长按收藏
    // ====================================================================
    private boolean handleChannelLongClick(String channelName, boolean isRightPanel) {
        SettingsActivity.logOperation("【收藏】handleChannelLongClick 被调用，channelName=" 
                + channelName + ", isRightPanel=" + isRightPanel);
        
        if (channelName == null || channelName.isEmpty()) {
            SettingsActivity.logOperation("【收藏】handleChannelLongClick 失败：频道名为空");
            return false;
        }
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            boolean isFavorite = appConfig.toggleFavorite(channelName);
            
            SettingsActivity.logOperation("【收藏】长按操作结果=" + (isFavorite ? "已收藏" : "已取消"));
            
            int favoriteCount = 0;
            int recentCount = 0;
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();
            
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
            
            SettingsActivity.logOperation("【收藏】分组数量更新完成，收藏=" + favoriteCount 
                    + ", 最近观看=" + recentCount);
            
            // 如果当前在「收藏」分组，刷新列表
            if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)) {
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
            SettingsActivity.logOperation("【收藏】长按操作失败，异常=" + e.getMessage());
            return false;
        }
    }

    /**
     * 切换当前频道的收藏状态（菜单键调用）
     */
    public boolean toggleCurrentFavorite() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return false;
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) return false;
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        if (currentChannel == null) return false;
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            boolean isFavorite = appConfig.toggleFavorite(currentChannel.getName());
            
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
            int globalIndex = findChannelGlobalIndex(selectedChannel);
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
        lvChannelList = null;
        lvChannelListEpg = null;
        lvDate = null;
        lvEpg = null;
        btnShowEpg = null;
        btnBackGroup = null;
        groupListManager = null;
        channelListManager = null;
        channelListManagerEpg = null;
        dateListManager = null;
        epgManagerWrapper = null;
        panelManager = null;
        channelSourceList = null;
        currentGroupChannelList = null;
        currentGroupName = null;
        channelChangeListener = null;
        panelStateListener = null;
    }
}
