package com.tv.live.widget;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.tv.live.Channel;
import com.tv.live.config.AppConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
/**
 * 分组列表管理器
 *
 * 【2026-06-21 新增：「收藏」和「最近观看」分组】
 * 【特殊分组顺序】
 * 1. 全部
 * 2. 收藏
 * 3. 最近观看
 * 4. 实际分组（按直播源顺序）
 * 
 * 【2026-06-23 新增：分类管理支持】
 * 1. 支持隐藏/显示指定分组
 * 2. 支持自定义分组顺序
 * 3. 特殊分组（全部、收藏、最近观看）固定在最前面，不参与隐藏和排序
 */
public class GroupListManager {
    /** 分组列表 ListView */
    private final ListView lvGroup;
    /** 上下文 */
    private final Context context;
    /** 分组名称列表 */
    private List<String> groupList;
    /** 每个分组的频道数量 */
    private List<Integer> groupCountList;
    /** 当前选中位置 */
    private int selectedPosition = 0;
    /** 列表适配器 */
    private ArrayAdapter<String> adapter;
    /** 分组选中监听器（供外部回调） */
    private OnGroupSelectedListener listener;
    /** 特殊分组：全部频道 */
    public static final String GROUP_ALL = "全部";
    /** 特殊分组：收藏频道 */
    public static final String GROUP_FAVORITE = "收藏";
    /** 特殊分组：最近观看 */
    public static final String GROUP_RECENT = "最近观看";
    /**
     * 分组选中监听器接口
     */
    public interface OnGroupSelectedListener {
        void onGroupSelected(int position, String groupName);
    }
    /**
     * 设置分组选中监听器
     */
    public void setOnGroupSelectedListener(OnGroupSelectedListener listener) {
        this.listener = listener;
    }
    /**
     * 构造函数
     */
    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;
        lvGroup.setItemsCanFocus(false);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        // 焦点移动时只刷新样式，不更新选中位置
        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        // 点击选中事件
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                setSelectedPosition(position);
            }
        });
    }
    /**
     * 设置分组列表
     *
     * 【2026-06-21 修改】
     * 1. 最前面插入 3 个特殊分组：全部、收藏、最近观看
     * 2. 用 LinkedHashSet 保持分组顺序
     * 3. 计算每个分组的频道数量
     * 
     * 【2026-06-23 修改：增加分类管理支持】
     * 1. 从 AppConfig 读取隐藏分组列表，过滤掉隐藏的分组
     * 2. 从 AppConfig 读取自定义分组顺序，按顺序排列
     * 3. 特殊分组（前3个）固定在最前面，不参与隐藏和排序
     *
     * @param channelSourceList 全部频道列表
     * @param favoriteCount 收藏频道数量
     * @param recentCount 最近观看频道数量
     */
    public void setGroups(List<Channel> channelSourceList, int favoriteCount, int recentCount) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        
        // 用 LinkedHashSet 提取分组，保持出现顺序
        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel c : channelSourceList) {
            groupSet.add(c.getGroup());
        }
        List<String> originalGroups = new ArrayList<>(groupSet);
        
        // ✅ 新增：保存所有分组到 AppConfig（供设置页面管理使用）
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            appConfig.setAllGroups(originalGroups);
        } catch (Exception e) {
            // 忽略保存失败
        }
        
        // ✅ 新增：应用自定义分组顺序
        List<String> orderedGroups = applyGroupOrder(originalGroups);
        
        // ✅ 新增：过滤掉隐藏的分组
        List<String> visibleGroups = filterHiddenGroups(orderedGroups);
        
        // ✅ 特殊分组放在最前面（固定3个，不参与隐藏和排序）
        groupList = new ArrayList<>();
        groupList.add(GROUP_ALL);      // 1. 全部
        groupList.add(GROUP_FAVORITE); // 2. 收藏
        groupList.add(GROUP_RECENT);   // 3. 最近观看
        groupList.addAll(visibleGroups); // 4. 实际分组（已过滤+已排序）
        
        // ✅ 计算每个分组的频道数量
        groupCountList = new ArrayList<>();
        groupCountList.add(channelSourceList.size()); // 全部
        groupCountList.add(favoriteCount);            // 收藏
        groupCountList.add(recentCount);              // 最近观看
        
        // 实际分组数量
        for (String group : visibleGroups) {
            int count = 0;
            for (Channel c : channelSourceList) {
                if (group.equals(c.getGroup())) {
                    count++;
                }
            }
            groupCountList.add(count);
        }
        
        adapter = new ArrayAdapter<String>(lvGroup.getContext(),
                android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);
                // 显示分组名 + 频道数量，比如「全部 (128)」「收藏 (5)」
                String groupName = groupList.get(position);
                int count = groupCountList.get(position);
                tv.setText(groupName + " (" + count + ")");
                // 三种状态样式
                if (position == selectedPosition) {
                    // 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(0x3340A9FF);
                } else if (view.isFocused()) {
                    // 焦点状态：蓝色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                } else {
                    // 未选中状态：白色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return view;
            }
        };
        lvGroup.setAdapter(adapter);
        // 默认选中「全部」
        selectedPosition = 0;
        adapter.notifyDataSetChanged();
    }
    /**
     * 应用自定义分组顺序
     * 
     * 【说明】
     * 如果用户设置了自定义顺序，就按用户设置的顺序排列；
     * 否则保持原来的顺序。
     * 
     * @param originalGroups 原始分组列表
     * @return 排序后的分组列表
     */
    private List<String> applyGroupOrder(List<String> originalGroups) {
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            List<String> customOrder = appConfig.getGroupOrder();
            
            // 如果没有自定义顺序，直接返回原列表
            if (customOrder == null || customOrder.isEmpty()) {
                return new ArrayList<>(originalGroups);
            }
            
            // 按自定义顺序排列
            List<String> ordered = new ArrayList<>();
            // 先加自定义顺序里有的分组
            for (String group : customOrder) {
                if (originalGroups.contains(group)) {
                    ordered.add(group);
                }
            }
            // 再加自定义顺序里没有的分组（新增的分组）
            for (String group : originalGroups) {
                if (!ordered.contains(group)) {
                    ordered.add(group);
                }
            }
            return ordered;
        } catch (Exception e) {
            // 出错则返回原列表
            return new ArrayList<>(originalGroups);
        }
    }
    /**
     * 过滤掉隐藏的分组
     * 
     * 【说明】
     * 从 AppConfig 读取隐藏分组列表，把隐藏的分组从列表中移除。
     * 
     * @param groups 原始分组列表
     * @return 过滤后的可见分组列表
     */
    private List<String> filterHiddenGroups(List<String> groups) {
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            List<String> hiddenGroups = appConfig.getHiddenGroups();
            
            // 如果没有隐藏的分组，直接返回原列表
            if (hiddenGroups == null || hiddenGroups.isEmpty()) {
                return new ArrayList<>(groups);
            }
            
            // 过滤掉隐藏的分组
            List<String> visible = new ArrayList<>();
            for (String group : groups) {
                if (!hiddenGroups.contains(group)) {
                    visible.add(group);
                }
            }
            return visible;
        } catch (Exception e) {
            // 出错则返回原列表
            return new ArrayList<>(groups);
        }
    }
    /**
     * 更新收藏和最近观看的数量（收藏/取消收藏时调用）
     *
     * @param favoriteCount 新的收藏数量
     * @param recentCount 新的最近观看数量
     */
    public void updateSpecialGroupCount(int favoriteCount, int recentCount) {
        if (groupCountList == null || groupCountList.size() < 3) return;
        groupCountList.set(1, favoriteCount); // 收藏
        groupCountList.set(2, recentCount);   // 最近观看
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
    /**
     * 设置选中位置，立即刷新高亮
     */
    public void setSelectedPosition(int position) {
        if (groupList == null || adapter == null) return;
        if (position < 0 || position >= groupList.size()) return;
        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
        if (listener != null) {
            listener.onGroupSelected(position, groupList.get(position));
        }
    }
    /**
     * 获取指定位置的分组名称
     */
    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }
    /**
     * 根据分组名获取位置
     */
    public int getGroupPosition(String groupName) {
        if (groupList == null || groupName == null) return 0;
        for (int i = 0; i < groupList.size(); i++) {
            if (groupName.equals(groupList.get(i))) {
                return i;
            }
        }
        return 0;
    }
    /**
     * 判断是不是「全部」分组
     */
    public boolean isAllGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return false;
        return GROUP_ALL.equals(groupList.get(position));
    }
    /**
     * 判断是不是特殊分组（全部、收藏、最近观看）
     */
    public boolean isSpecialGroup(int position) {
        return position < 3; // 前 3 个是特殊分组
    }
    public void onBackPressed() {}
}
