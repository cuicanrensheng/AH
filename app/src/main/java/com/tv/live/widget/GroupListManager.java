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

    public GroupListManager(Context context, ListView lvGroup) {
        this.lvGroup = lvGroup;
        lvGroup.setItemsCanFocus(true);

        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 【修复增加】点击分组时，强制刷新列表 + 高亮
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            if (parent.getAdapter() != null) {
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }
            lvGroup.setSelection(position);
        });
    }

    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        Set<String> groupSet = new HashSet<>();
        for (Channel c : channelSourceList) groupSet.add(c.getGroup());
        groupList = new ArrayList<>(groupSet);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvGroup.getContext(), android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                // 高亮跟随选中，不固定第一位
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

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        lvGroup.setSelection(position);
        if(lvGroup.getAdapter() != null){
            ((ArrayAdapter<?>) lvGroup.getAdapter()).notifyDataSetChanged();
        }
    }

    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    public List<String> getGroupList(){
        return groupList;
    }

    public int getSelectedPos(){
        return selectedPosition;
    }

    public void onBackPressed() {}
}
