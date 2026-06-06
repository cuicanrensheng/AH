package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
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
    private final Context context;
    private List<String> groupList;
    private int selectedPosition = 0;

    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;
    }

    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        Set<String> groupSet = new HashSet<>();
        for (Channel c : channelSourceList) {
            groupSet.add(c.getGroup());
        }
        groupList = new ArrayList<>(groupSet);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);

                // 选中蓝色，未选中白色
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

    // 设置选中项
    public void setSelectedPosition(int position) {
        this.selectedPosition = position;
        notifyDataSetChanged();
    }

    // 刷新列表
    private void notifyDataSetChanged() {
        if (lvGroup.getAdapter() != null) {
            ((ArrayAdapter<?>) lvGroup.getAdapter()).notifyDataSetChanged();
        }
    }

    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    public void onBackPressed() {}
}
