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

public class GroupListManager {
    private final ListView lvGroup;
    private List<String> groupList;
    private int selectedPosition = 0;

    // 新增：分组点击回调接口（参考ChannelListManager的点击回调设计）
    public interface OnGroupClickListener {
        void onGroupClick(int position);
    }
    private OnGroupClickListener onGroupClickListener;

    // 新增：设置分组点击回调
    public void setOnGroupClickListener(OnGroupClickListener listener) {
        this.onGroupClickListener = listener;
    }

    public GroupListManager(Context context, ListView lvGroup) {
        this.lvGroup = lvGroup;
        lvGroup.setItemsCanFocus(true);

        // 新增：分组点击事件（参考ChannelListManager的点击逻辑）
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            // 防护：Adapter为空时不执行刷新
            if (parent.getAdapter() != null) {
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }
            if (onGroupClickListener != null) {
                onGroupClickListener.onGroupClick(position);
            }
        });

        // 焦点选中事件（保留原有逻辑，增加Adapter空指针防护）
        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (parent.getAdapter() != null) {
                    ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // 优化：新增初始选中位置参数，适配初始选中需求（参考ChannelListManager的currentPlayIndex）
    public void setGroups(List<Channel> channelSourceList) {
        setGroups(channelSourceList, 0);
    }

    // 重载：支持指定初始选中位置
    public void setGroups(List<Channel> channelSourceList, int initSelectedPos) {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            groupList = new ArrayList<>(); // 避免后续空指针
            return;
        }

        Set<String> groupSet = new HashSet<>();
        for (Channel c : channelSourceList) {
            // 防护：Channel或group为空时跳过
            if (c != null && c.getGroup() != null) {
                groupSet.add(c.getGroup());
            }
        }
        groupList = new ArrayList<>(groupSet);

        // 防护：初始选中位置越界
        selectedPosition = Math.max(0, Math.min(initSelectedPos, groupList.size() - 1));

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvGroup.getContext(), android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                // 统一颜色设置逻辑（参考ChannelListManager）
                tv.setTextColor(Color.WHITE);
                if (position == selectedPosition) {
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                }
                return view;
            }
        };
        lvGroup.setAdapter(adapter);
        lvGroup.setSelection(selectedPosition); // 初始选中定位
    }

    // 修复：添加Adapter空指针防护，刷新UI（原有逻辑缺少notifyDataSetChanged）
    public void setSelectedPosition(int position) {
        if (groupList == null || groupList.isEmpty()) return;
        // 防护：位置越界
        selectedPosition = Math.max(0, Math.min(position, groupList.size() - 1));
        lvGroup.setSelection(selectedPosition);
        if (lvGroup.getAdapter() != null) {
            ((ArrayAdapter<?>) lvGroup.getAdapter()).notifyDataSetChanged();
        }
    }

    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    // 新增：获取当前选中的分组
    public String getSelectedGroup() {
        return getCurrentGroup(selectedPosition);
    }

    // 新增：获取分组列表（对外暴露）
    public List<String> getGroupList() {
        return new ArrayList<>(groupList); // 返回副本，避免外部修改
    }

    public void onBackPressed() {}
}
