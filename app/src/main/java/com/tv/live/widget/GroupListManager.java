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
