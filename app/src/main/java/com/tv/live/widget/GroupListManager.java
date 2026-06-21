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
 * 【2026-06-21 优化：焦点优先样式 + 区分焦点和选中状态】
 *
 * 【三种状态说明】
 * 1. 焦点状态：白色文字 + 蓝色背景（遥控器焦点所在的项，最显眼）
 * 2. 选中状态：蓝色文字 + 透明背景（点击 OK 键后真正选中的分组）
 * 3. 未选中状态：白色文字 + 透明背景（普通项）
 *
 * 【判断优先级】
 * 焦点状态 > 选中状态 > 未选中状态
 *
 * 【交互变化】
 * - 移动焦点：只改变焦点样式，不切换分组
 * - 点击 OK 键：才真正选中，切换分组
 *
 * 【为什么用 focusedPosition 变量？】
 * 因为 ListView 的焦点在 ListView 控件上，不在 item 上，
 * 所以 view.isFocused() 判断不准确，需要自己记录焦点位置。
 */
public class GroupListManager {
    /** 分组列表 ListView */
    private final ListView lvGroup;
    /** 上下文 */
    private final Context context;
    /** 分组名称列表 */
    private List<String> groupList;
    /** 当前选中位置（点击 OK 键选中的） */
    private int selectedPosition = 0;
    // ====================================================================
    // ✅ 新增：焦点位置变量
    // ====================================================================
    /** 当前焦点位置（遥控器移动到的位置） */
    private int focusedPosition = 0;
    /** 列表适配器 */
    private ArrayAdapter<String> adapter;
    /** 分组选中监听器（供外部回调） */
    private OnGroupSelectedListener listener;

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

        // ================================================================
        // ✅ 修改：焦点移动时只更新 focusedPosition，不更新 selectedPosition
        // ================================================================
        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                // 只更新焦点位置，不更新选中位置
                setFocusedPosition(pos);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // ================================================================
        // ✅ 点击选中事件（按 OK 键时触发）
        // ================================================================
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                setSelectedPosition(position);
            }
        });
    }

    // ====================================================================
    // ✅ 新增：焦点位置相关方法
    // ====================================================================
    /**
     * 设置焦点位置（遥控器移动时调用）
     */
    public void setFocusedPosition(int position) {
        if (groupList == null || adapter == null) return;
        if (position < 0 || position >= groupList.size()) return;
        this.focusedPosition = position;
        adapter.notifyDataSetChanged();
    }

    /**
     * 获取当前焦点位置
     */
    public int getFocusedPosition() {
        return focusedPosition;
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
                // ✅ 2026-06-21 修改：统一三种状态样式（焦点优先）
                // ====================================================================
                // 判断优先级：焦点 > 选中 > 普通
                if (position == focusedPosition) {
                    // ── 焦点状态：白色文字 + 蓝色背景（最显眼）──
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.parseColor("#40A9FF"));
                } else if (position == selectedPosition) {
                    // ── 选中状态：蓝色文字 + 透明背景（次之）──
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                } else {
                    // ── 普通状态：白色文字 + 透明背景 ──
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }

                return view;
            }
        };
        lvGroup.setAdapter(adapter);
        // 默认选中第一个
        selectedPosition = 0;
        focusedPosition = 0;
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
        // 选中时也同步移动焦点到选中项
        focusedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
        // 回调通知外部，分组选中了
        if (listener != null) {
            listener.onGroupSelected(position, groupList.get(position));
        }
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
