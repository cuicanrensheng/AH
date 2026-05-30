package com.tv.live.widget;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.tv.live.Channel;
import java.util.ArrayList;
import java.util.List;

public class ChannelListManager {
    private final ListView lvChannelList;
    private final Context context;

    public ChannelListManager(Context context, ListView lvChannelList) {
        this.context = context;
        this.lvChannelList = lvChannelList;
    }

    public void setChannels(List<Channel> channelSourceList, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) {
            names.add(c.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_list_item_1, names);
        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(currentPlayIndex);
    }
}
