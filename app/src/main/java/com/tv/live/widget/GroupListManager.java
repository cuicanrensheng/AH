package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
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
 * 分组列表管理器（已恢复遥控器焦点）
 */
public class GroupListManager {

    private final ListView lvGroup;
    private Context context;
    private List<String> groupDisplayList;
    private List<String> groupNameList;
    private int selectedPosition = 0;
    private ArrayAdapter<String> adapter;
    private OnGroupSelectedListener listener;

    public static final String GROUP_ALL = "全部";

    private static final int COLOR_BLUE_TEXT = 0xFF40A9FF;
    private static final int COLOR_BLUE_BG = 0x3340A9FF;
    private static final int COLOR_WHITE_TEXT = 0xFFFFFFFF;

    public interface OnGroupSelectedListener {
        void onGroupSelected(int position, String groupName);
    }

    public void setOnGroupSelectedListener(OnGroupSelectedListener listener) {
        this.listener = listener;
    }

    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;

        // ✅ 恢复焦点
        lvGroup.setItemsCanFocus(true);
        lvGroup.setFocusable(true);
        lvGroup.setFocusableInTouchMode(true);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // ✅ 恢复 OnItemSelectedListener
        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPosition = position;
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 保持当前选中
            }
        });

        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            setSelectedPosition(position);
        });
    }

    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel c : channelSourceList) {
            groupSet.add(c.getGroup());
        }
        List<String> originalGroups = new ArrayList<>(groupSet);

        groupNameList = new ArrayList<>();
        groupNameList.add(GROUP_ALL);
        groupNameList.addAll(originalGroups);

        groupDisplayList = new ArrayList<>();
        groupDisplayList.add(GROUP_ALL + " (" + channelSourceList.size() + ")");
        for (String group : originalGroups) {
            int count = 0;
            for (Channel c : channelSourceList) {
                if (group.equals(c.getGroup())) {
                    count++;
                }
            }
            groupDisplayList.add(group);
        }

        adapter = new ArrayAdapter<String>(lvGroup.getContext(), android.R.layout.simple_list_item_1, groupDisplayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    LayoutInflater inflater = LayoutInflater.from(context);
                    convertView = inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
                    TextView tv = convertView.findViewById(android.R.id.text1);
                    holder = new ViewHolder();
                    holder.tv = tv;
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (holder == null || holder.tv == null) {
                    LayoutInflater inflater = LayoutInflater.from(context);
                    convertView = inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
                    TextView tv = convertView.findViewById(android.R.id.text1);
                    holder = new ViewHolder();
                    holder.tv = tv;
                    convertView.setTag(holder);
                }

                TextView tv = holder.tv;
                if (tv == null) {
                    return convertView;
                }

                String text = groupDisplayList.get(position);
                tv.setText(text);

                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);

                // ✅ 区分“焦点”与“选中”
                boolean isFocused = (position == selectedPosition) && lvGroup.hasFocus();
                boolean isSelected = (position == selectedPosition);

                if (isFocused) {
                    // 焦点状态：蓝字 + 常规 + 透明背景
                    tv.setTextColor(COLOR_BLUE_TEXT);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                } else if (isSelected) {
                    // 选中状态：蓝字 + 加粗 + 浅蓝背景
                    tv.setTextColor(COLOR_BLUE_TEXT);
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(COLOR_BLUE_BG);
                } else {
                    // 未选中状态：白字 + 常规 + 透明背景
                    tv.setTextColor(COLOR_WHITE_TEXT);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return convertView;
            }
        };
        lvGroup.setAdapter(adapter);
        selectedPosition = 0;
        adapter.notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        if (groupDisplayList == null || adapter == null) return;
        if (position < 0 || position >= groupDisplayList.size()) return;
        if (selectedPosition == position) return;

        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
        if (listener != null) {
            listener.onGroupSelected(position, groupNameList.get(position));
        }
    }

    public String getCurrentGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return "";
        return groupNameList.get(position);
    }

    public int getGroupPosition(String groupName) {
        if (groupNameList == null || groupName == null) return 0;
        for (int i = 0; i < groupNameList.size(); i++) {
            if (groupName.equals(groupNameList.get(i))) {
                return i;
            }
        }
        return 0;
    }

    public boolean isAllGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return false;
        return GROUP_ALL.equals(groupNameList.get(position));
    }

    public boolean isSpecialGroup(int position) {
        return position == 0;
    }

    public void onBackPressed() {}

    private static class ViewHolder {
        TextView tv;
    }

    public void release() {
        if (adapter != null) {
            adapter.clear();
            adapter = null;
        }
        if (lvGroup != null) {
            lvGroup.setAdapter(null);
            lvGroup.setOnItemClickListener(null);
            lvGroup.setOnItemSelectedListener(null);
        }
        if (groupDisplayList != null) {
            groupDisplayList.clear();
            groupDisplayList = null;
        }
        if (groupNameList != null) {
            groupNameList.clear();
            groupNameList = null;
        }
        listener = null;
        context = null;
    }
}
