package com.tv.live.widget;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.tv.live.Channel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GroupListManager {
    private final ListView lvGroup;
    private final Context context;

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

        List<String> groupList = new ArrayList<>(groupSet);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_list_item_1, groupList);
        lvGroup.setAdapter(adapter);
        // 供外部调用，触发返回
     public void onBackPressed() {
         if (listener != null) {
             listener.onBack();
        }   
    }
}
