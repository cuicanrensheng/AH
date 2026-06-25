package com.tv.live.widget;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.tv.live.Channel;
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
 * 【2026-06-24 修改：增加焦点态样式区分】
 * 【修改说明】
 * 新增 hasFocus 变量和 setFocused 方法，区分"有焦点的选中"和"无焦点的选中"。
 * 
 * 【样式规范】
 * - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
 * - 无焦点 + 选中：蓝色文字 + 透明背景
 * - 未选中：白色文字 + 透明背景
 * 
 * 【为什么要改？】
 * 原来的实现中，多个面板同时显示选中态（都是蓝色背景），
 * 用户分不清当前焦点在哪个面板上，遥控器操作时容易懵。
 * 
 * 【2026-06-24 修复：焦点移动时样式同步更新】
 * 【修复的问题】
 * 遥控器按上下键时，焦点移动了，但选中样式没变，看起来像"固定在首位"。
 * 
 * 【修复原因】
 * 原来 onItemSelected 回调中只刷新适配器，不更新 selectedPosition，
 * 导致 getView 中判断 position == selectedPosition 时一直是原来的位置，
 * 所以选中样式不会跟着焦点移动。
 * 
 * 【修复方案】
 * 在 onItemSelected 回调中加上 selectedPosition = pos，
 * 确保选中位置和焦点位置保持同步。
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
    // ====================================================================
    // ✅ 2026-06-24 新增：焦点状态
    // ====================================================================
    /**
     * 当前列表是否有焦点
     * 
     * 【作用】
     * 区分"有焦点的选中"和"无焦点的选中"：
     * - true = 当前光标在这个列表上，选中项用浅蓝色背景 + 蓝色文字 + 加粗
     * - false = 当前光标不在这个列表上，选中项用蓝色文字 + 透明背景
     * 
     * 【为什么需要？】
     * 多个面板同时显示选中态时，用户分不清当前焦点在哪个面板。
     * 有了这个状态，只有当前焦点所在的面板才会显示浅蓝色背景。
     */
    private boolean hasFocus = false;
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
        // ====================================================================
        // ✅ 2026-06-24 修复：焦点移动时更新 selectedPosition，确保样式同步
        // ====================================================================
        // 【为什么要改？】
        // 原来只刷新适配器，但不更新 selectedPosition，
        // 导致遥控器按上下键时，焦点移动了但选中样式没变，看起来像"固定在首位"。
        // 现在加上 selectedPosition = pos，样式就能跟着焦点一起移动了。
        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos; // ✅ 关键：更新选中位置
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
    // ====================================================================
    // ✅ 2026-06-24 新增：设置焦点状态
    // ====================================================================
    /**
     * 设置当前列表是否有焦点
     * 
     * @param focused true=有焦点，false=无焦点
     * 
     * 【作用】
     * 外部（ChannelPanelController）调用这个方法，告诉列表当前是否有焦点。
     * 有焦点时选中项用浅蓝色背景 + 蓝色文字 + 加粗；
     * 无焦点时选中项用蓝色文字 + 透明背景，只是标记。
     * 
     * 【调用时机】
     * - 光标切换到这个列表时：setFocused(true)
     * - 光标离开这个列表时：setFocused(false)
     */
    public void setFocused(boolean focused) {
        if (this.hasFocus == focused) return;
        this.hasFocus = focused;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
    /**
     * 获取当前是否有焦点
     */
    public boolean isFocused() {
        return hasFocus;
    }
    /**
     * 设置分组列表
     *
     * 【2026-06-21 修改】
     * 1. 最前面插入 3 个特殊分组：全部、收藏、最近观看
     * 2. 用 LinkedHashSet 保持分组顺序
     * 3. 计算每个分组的频道数量
     *
     * @param channelSourceList 全部频道列表
     * @param favoriteCount 收藏频道数量
     * @param recentCount 最近观看频道数量
     * 
     * 【2026-06-24 修改：样式区分焦点态】
     * 选中态分两种：
     * - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
     * - 无焦点 + 选中：蓝色文字 + 透明背景
     */
    public void setGroups(List<Channel> channelSourceList, int favoriteCount, int recentCount) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        // 用 LinkedHashSet 提取分组，保持出现顺序
        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel c : channelSourceList) {
            groupSet.add(c.getGroup());
        }
        List<String> originalGroups = new ArrayList<>(groupSet);
        // ✅ 新增：特殊分组放在最前面
        groupList = new ArrayList<>();
        groupList.add(GROUP_ALL);      // 1. 全部
        groupList.add(GROUP_FAVORITE); // 2. 收藏
        groupList.add(GROUP_RECENT);   // 3. 最近观看
        groupList.addAll(originalGroups); // 4. 实际分组
        // ✅ 计算每个分组的频道数量
        groupCountList = new ArrayList<>();
        groupCountList.add(channelSourceList.size()); // 全部
        groupCountList.add(favoriteCount);            // 收藏
        groupCountList.add(recentCount);              // 最近观看
        // 实际分组数量
        for (String group : originalGroups) {
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
                // ====================================================================
                // ✅ 2026-06-24 修改：三种状态样式（区分焦点态）
                // ====================================================================
                // 【样式规范】
                // - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
                // - 无焦点 + 选中：蓝色文字 + 透明背景
                // - 未选中：白色文字 + 透明背景
                if (position == selectedPosition) {
                    if (hasFocus) {
                        // ⭐ 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
                        tv.setTextColor(Color.parseColor("#40A9FF"));
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(0x3340A9FF); // 20%透明度的蓝色
                    } else {
                        // 无焦点 + 选中：蓝色文字 + 透明背景（只是标记，不抢视线）
                        tv.setTextColor(Color.parseColor("#40A9FF"));
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(Color.TRANSPARENT);
                    }
                } else {
                    // 未选中：白色文字 + 透明背景
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
