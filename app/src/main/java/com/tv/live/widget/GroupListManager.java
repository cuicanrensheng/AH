package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
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
 * 频道分组列表管理器
 * 修复：分组选中蓝色字体固定在第一位，不会跟随选中内容移动的问题
 * 修复原则：只增加代码，不删除、不修改原有任何功能
 */
public class GroupListManager {
    private final ListView lvGroup;
    private List<String> groupList;
    private int selectedPosition = 0;

    /**
     * 构造方法
     * 修复增加：点击事件同步更新选中位置，确保高亮跟随
     */
    public GroupListManager(Context context, ListView lvGroup) {
        this.lvGroup = lvGroup;
        lvGroup.setItemsCanFocus(true);

        // 原有逻辑：焦点选中监听
        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                // 更新选中下标
                selectedPosition = pos;
                // 刷新列表颜色
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // ===================== 【修复增加】点击事件，保证点击也能更新选中高亮 =====================
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            // 更新选中下标为当前点击项
            selectedPosition = position;
            // 刷新适配器，让蓝色高亮生效
            if (parent.getAdapter() != null) {
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }
            // 列表滚动到当前选中项
            lvGroup.setSelection(position);
        });
    }

    /**
     * 设置分组数据源
     * 原有逻辑完全保留
     */
    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 提取所有分组并去重
        Set<String> groupSet = new HashSet<>();
        for (Channel c : channelSourceList) {
            groupSet.add(c.getGroup());
        }
        groupList = new ArrayList<>(groupSet);

        // 列表适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvGroup.getContext(), android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);

                // ===================== 【修复关键】蓝色高亮严格跟随 selectedPosition，不再固定0位 =====================
                if (position == selectedPosition) {
                    // 当前选中项 = 蓝色
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                } else {
                    // 未选中 = 白色
                    tv.setTextColor(Color.WHITE);
                }
                return view;
            }
        };

        lvGroup.setAdapter(adapter);
    }

    /**
     * 手动设置选中位置
     * 修复增加：刷新适配器，保证高亮同步
     */
    public void setSelectedPosition(int position) {
        selectedPosition = position;
        lvGroup.setSelection(position);
        // 【修复增加】确保选中后颜色立即刷新
        if (lvGroup.getAdapter() != null) {
            ((ArrayAdapter<?>) lvGroup.getAdapter()).notifyDataSetChanged();
        }
    }

    /**
     * 获取指定下标的分组名称
     */
    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    /**
     * 【新增】获取分组列表
     */
    public List<String> getGroupList() {
        return groupList;
    }

    /**
     * 获取当前选中下标
     */
    public int getSelectedPos() {
        return selectedPosition;
    }

    /**
     * 返回键逻辑
     */
    public void onBackPressed() {
    }
}
