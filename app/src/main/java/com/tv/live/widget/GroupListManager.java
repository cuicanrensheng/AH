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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分组列表管理器
 *
 * 【职责】
 * 统一管理频道分组列表的显示、选中状态、点击事件等。
 *
 * 【2026-06-20 优化：统一高亮样式，解决多个光标问题】
 *
 * 【原来的问题】
 * 原来有三种状态：选中状态、焦点状态、未选中状态，
 * 当焦点和选中位置不在同一个项时，就会有两个项同时亮着，
 * 看起来像有多个光标，很乱。
 *
 * 【优化方案】
 * 去掉"焦点状态"的单独判断，统一成两种状态：
 * 1. 高亮状态（选中=焦点，用同一种样式）
 * 2. 普通状态
 */
public class GroupListManager {

    /** 分组列表 ListView */
    private final ListView lvGroup;

    /** 上下文 */
    private final Context context;

    /** 分组名称列表 */
    private List<String> groupList;

    /** 当前选中位置 */
    private int selectedPosition = 0;

    /** 列表适配器 */
    private ArrayAdapter<String> adapter;

    /**
     * 构造函数
     *
     * @param context 上下文
     * @param lvGroup 分组列表 ListView
     */
    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;

        // item 不需要获取焦点，由 ListView 统一管理
        lvGroup.setItemsCanFocus(false);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // 遥控器选择时同步更新选中状态
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
    }

    /**
     * 设置分组列表
     *
     * @param channelSourceList 全部频道列表
     */
    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 提取所有分组（去重）
        Set<String> groupSet = new HashSet<>();
        for (Channel c : channelSourceList) groupSet.add(c.getGroup());
        groupList = new ArrayList<>(groupSet);

        adapter = new ArrayAdapter<String>(lvGroup.getContext(),
                android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);

                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);

                // ====================================================================
                // ✅ 2026-06-20 优化：统一高亮，只有两种状态
                // ====================================================================

                if (position == selectedPosition) {
                    // ✅ 高亮状态（选中和焦点都用这一种样式）
                    // 样式：蓝色文字 + 加粗 + 浅蓝色背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(0x3340A9FF);
                } else {
                    // ✅ 普通状态
                    // 样式：白色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }

                /*
                 * ❌ 已删除：原来的焦点状态判断
                 *
                 * 原来的代码：
                 * else if (view.isFocused()) {
                 *     // 焦点状态：蓝色文字 + 稍深一点的蓝色背景
                 *     ...
                 * }
                 *
                 * 【为什么删除？】
                 * 原来有三种状态，当焦点和选中位置不在同一个项时，
                 * 就会有两个项同时亮着，看起来像有多个光标。
                 *
                 * 现在统一成两种状态，同一个列表里永远只有一个项亮着。
                 */

                return view;
            }
        };

        lvGroup.setAdapter(adapter);

        // 默认选中第一个
        selectedPosition = 0;
        adapter.notifyDataSetChanged();
    }

    /**
     * 设置选中位置，立即刷新高亮
     * 外部点击时调用这个方法
     *
     * @param position 选中位置
     */
    public void setSelectedPosition(int position) {
        if (groupList == null || adapter == null) return;
        if (position < 0 || position >= groupList.size()) return;

        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
    }

    /**
     * 获取指定位置的分组名称
     *
     * @param position 位置
     * @return 分组名称
     */
    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    public void onBackPressed() {}
}
