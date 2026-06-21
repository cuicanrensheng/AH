package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 分组列表管理器
 *
 * 【2026-06-21 修改：统一三种状态高亮样式（焦点优先）】
 * 【样式规则】
 * 焦点 > 选中 > 普通
 * - 焦点：白色文字 + 蓝色背景（最显眼，遥控器停在哪里）
 * - 选中：蓝色文字 + 透明背景（当前选中的分组）
 * - 普通：白色文字 + 透明背景
 *
 * 【为什么用 focusedPosition？】
 * 因为 ListView 的焦点在 ListView 控件上，不在 item 上，
 * 所以 view.isFocused() 判断不准确，需要自己记录焦点位置。
 *
 * 【什么时候更新？】
 * - onItemSelected：更新 focusedPosition（焦点移动）
 * - onItemClick：更新 selectedPosition（点击选中）
 */
public class GroupListManager {
    private Context context;
    private ListView listView;
    private List<String> groupList = new ArrayList<>();
    private GroupAdapter adapter;

    // ====================================================================
    // ✅ 新增：焦点位置和选中位置分开记录
    // ====================================================================
    /** 当前焦点位置（遥控器移动到的位置） */
    private int focusedPosition = 0;
    /** 当前选中位置（点击选中的分组） */
    private int selectedPosition = 0;

    private OnGroupSelectedListener listener;

    public interface OnGroupSelectedListener {
        void onGroupSelected(int position, String groupName);
    }

    public GroupListManager(Context context, ListView listView) {
        this.context = context;
        this.listView = listView;
        adapter = new GroupAdapter();
        listView.setAdapter(adapter);
        initListeners();
    }

    // ====================================================================
    // 初始化监听器
    // ====================================================================
    private void initListeners() {
        // 点击事件：点击才真正选中
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                SettingsActivity.logOperation("【分组】点击选中：" + position + " - " + groupList.get(position));
                setSelectedPosition(position);
                if (listener != null) {
                    listener.onGroupSelected(position, groupList.get(position));
                }
            }
        });

        // 选中事件：只移动焦点，不选中
        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SettingsActivity.logOperation("【分组】焦点移动：" + position);
                // ✅ 只更新焦点位置，不更新选中位置
                setFocusedPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做处理
            }
        });
    }

    // ====================================================================
    // ✅ 新增：设置焦点位置
    // ====================================================================
    /**
     * 设置焦点位置（遥控器移动时调用）
     */
    public void setFocusedPosition(int position) {
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
     * 设置选中位置（点击选中时调用）
     */
    public void setSelectedPosition(int position) {
        if (position < 0 || position >= groupList.size()) return;
        this.selectedPosition = position;
        // 选中时也同步移动焦点到选中项
        this.focusedPosition = position;
        adapter.notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setGroups(List<Channel> channels) {
        groupList.clear();
        if (channels != null) {
            for (Channel c : channels) {
                String group = c.getGroup();
                if (group != null && !group.isEmpty() && !groupList.contains(group)) {
                    groupList.add(group);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    public String getCurrentGroup(int position) {
        if (position >= 0 && position < groupList.size()) {
            return groupList.get(position);
        }
        return "";
    }

    public void setOnGroupSelectedListener(OnGroupSelectedListener listener) {
        this.listener = listener;
    }

    // ====================================================================
    // 适配器
    // ====================================================================
    private class GroupAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return groupList.size();
        }

        @Override
        public Object getItem(int position) {
            return groupList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView textView;
            if (convertView == null) {
                textView = new TextView(context);
                textView.setPadding(40, 30, 40, 30);
                textView.setTextSize(18);
                // 去掉系统默认焦点高亮
                textView.setDefaultFocusHighlightEnabled(false);
            } else {
                textView = (TextView) convertView;
            }

            String groupName = groupList.get(position);
            textView.setText(groupName);

            // ====================================================================
            // ✅ 修改：统一三种状态样式（焦点优先）
            // ====================================================================
            // 判断优先级：焦点 > 选中 > 普通
            if (position == focusedPosition) {
                // ── 焦点状态：白色文字 + 蓝色背景（最显眼）──
                textView.setTextColor(Color.WHITE);
                textView.setTypeface(Typeface.DEFAULT); // 常规字重
                textView.setBackgroundColor(Color.parseColor("#40A9FF"));
            } else if (position == selectedPosition) {
                // ── 选中状态：蓝色文字 + 透明背景（次之）──
                textView.setTextColor(Color.parseColor("#40A9FF"));
                textView.setTypeface(Typeface.DEFAULT); // 常规字重
                textView.setBackgroundColor(Color.TRANSPARENT);
            } else {
                // ── 普通状态：白色文字 + 透明背景 ──
                textView.setTextColor(Color.WHITE);
                textView.setTypeface(Typeface.DEFAULT);
                textView.setBackgroundColor(Color.TRANSPARENT);
            }

            return textView;
        }
    }
}
