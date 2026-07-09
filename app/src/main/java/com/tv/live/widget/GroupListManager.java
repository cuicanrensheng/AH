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
 * 【2026-07-04 修改：移除收藏和最近观看功能】
 * 删除了 GROUP_FAVORITE 和 GROUP_RECENT 的添加逻辑，
 * 分组列表现在只显示【全部】和【实际存在的频道分组】。
 *
 * 【样式规范】
 * - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
 * - 无焦点 + 选中：蓝色文字 + 透明背景
 * - 未选中：白色文字 + 透明背景
 */
public class GroupListManager {

    /** 分组列表 ListView */
    private final ListView lvGroup;
    /** 上下文 */
    private final Context context;
    /** 分组显示名称列表（已预拼接好数量） */
    private List<String> groupDisplayList;
    /** 分组原始名称列表 */
    private List<String> groupNameList;
    /** 当前选中位置 */
    private int selectedPosition = 0;
    /** 列表适配器 */
    private ArrayAdapter<String> adapter;
    /** 分组选中监听器（供外部回调） */
    private OnGroupSelectedListener listener;

    /**
     * 当前列表是否有焦点
     * - true = 当前光标在这个列表上，选中项用浅蓝色背景 + 蓝色文字 + 加粗
     * - false = 当前光标不在这个列表上，选中项用蓝色文字 + 透明背景
     */
    private boolean hasFocus = false;

    /** 特殊分组：全部频道 */
    public static final String GROUP_ALL = "全部";

    // 🟢【优化】预定义颜色常量，彻底规避 Color.parseColor 的重复解析
    private static final int COLOR_BLUE_TEXT = 0xFF40A9FF;
    private static final int COLOR_BLUE_BG = 0x3340A9FF;
    private static final int COLOR_WHITE_TEXT = 0xFFFFFFFF;

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

        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
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
     * 设置当前列表是否有焦点
     * 🟢【优化】避免重复触发 notifyDataSetChanged，降低渲染压力
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
     * 🟢【优化】预先拼接好显示文字，防止 getView 中反复进行字符串创建
     *
     * @param channelSourceList 全部频道列表
     */
    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 用 LinkedHashSet 提取分组，保持出现顺序
        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel c : channelSourceList) {
            groupSet.add(c.getGroup());
        }
        List<String> originalGroups = new ArrayList<>(groupSet);

        // ✅ 只保留【全部】和【实际分组】
        groupNameList = new ArrayList<>();
        groupNameList.add(GROUP_ALL);
        groupNameList.addAll(originalGroups);

        // 🟢 提前构建好显示文本列表，避免 getView 中反复拼接字符串
        groupDisplayList = new ArrayList<>();
        // 1. 全部 (总数)
        groupDisplayList.add(GROUP_ALL + " (" + channelSourceList.size() + ")");
        // 2. 实际分组
        for (String group : originalGroups) {
            int count = 0;
            for (Channel c : channelSourceList) {
                if (group.equals(c.getGroup())) {
                    count++;
                }
            }
            groupDisplayList.add(group);
        }

        adapter = new ArrayAdapter<String>(lvGroup.getContext(),
                android.R.layout.simple_list_item_1, groupDisplayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);
                
                // 🟢 直接使用预生成的显示文字，不需要拼接
                String displayText = groupDisplayList.get(position);
                tv.setText(displayText);

                // 三种状态样式（区分焦点态）
                if (position == selectedPosition) {
                    if (hasFocus) {
                        // 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
                        tv.setTextColor(COLOR_BLUE_TEXT);
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(COLOR_BLUE_BG); 
                    } else {
                        // 无焦点 + 选中：蓝色文字 + 透明背景（只是标记，不抢视线）
                        tv.setTextColor(COLOR_BLUE_TEXT);
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(Color.TRANSPARENT);
                    }
                } else {
                    // 未选中：白色文字 + 透明背景
                    tv.setTextColor(COLOR_WHITE_TEXT);
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
     * 设置选中位置，立即刷新高亮
     * 🟢【优化】增加位置相同判断，防止重复点击触发无效的全量刷新
     */
    public void setSelectedPosition(int position) {
        if (groupDisplayList == null || adapter == null) return;
        if (position < 0 || position >= groupDisplayList.size()) return;
        // 🟢 如果已经选中当前位置，无需重复刷新
        if (selectedPosition == position) return; 

        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
        if (listener != null) {
            listener.onGroupSelected(position, groupNameList.get(position));
        }
    }

    /**
     * 获取指定位置的分组原始名称
     */
    public String getCurrentGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return "";
        return groupNameList.get(position);
    }

    /**
     * 根据原始分组名获取位置
     */
    public int getGroupPosition(String groupName) {
        if (groupNameList == null || groupName == null) return 0;
        for (int i = 0; i < groupNameList.size(); i++) {
            if (groupName.equals(groupNameList.get(i))) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 判断是不是「全部」分组
     */
    public boolean isAllGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return false;
        return GROUP_ALL.equals(groupNameList.get(position));
    }

    /**
     * 判断是不是特殊分组（全部）
     */
    public boolean isSpecialGroup(int position) {
        return position == 0; // 现在只有第 0 项（全部）是特殊的
    }

    public void onBackPressed() {}
}
