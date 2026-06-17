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
    // ✅ 把adapter提为成员变量，方便手动刷新
    private ArrayAdapter<String> adapter;

    public GroupListManager(Context context, ListView lvGroup) {
        this.lvGroup = lvGroup;
        lvGroup.setItemsCanFocus(true);

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

    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        Set<String> groupSet = new HashSet<>();
        for (Channel c : channelSourceList) groupSet.add(c.getGroup());
        groupList = new ArrayList<>(groupSet);

        adapter = new ArrayAdapter<String>(lvGroup.getContext(), android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                if (position == selectedPosition) {
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                } else {
                    tv.setTextColor(Color.WHITE);
                }
                return view;
            }
        };
        lvGroup.setAdapter(adapter);
    }

    /**
     * ✅ 修复：设置选中位置后立即刷新adapter，确保高亮更新
     * 支持触摸点击和遥控器选择两种场景
     */
    public void setSelectedPosition(int position) {
        if (position < 0 || (groupList != null && position >= groupList.size())) {
            return;
        }
        selectedPosition = position;
        lvGroup.setSelection(position);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    public void onBackPressed() {}
}
