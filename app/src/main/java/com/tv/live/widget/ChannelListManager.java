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
import java.util.List;

/**
 * 频道列表管理器
 * 功能：显示频道列表，当前选中项变为蓝色
 */
public class ChannelListManager {
    private final ListView lvChannelList;
    private final Context context;
    private int selectedPosition = 0; // 当前选中位置

    public ChannelListManager(Context context, ListView lvChannelList) {
        this.context = context;
        this.lvChannelList = lvChannelList;
    }

    /**
     * 设置全部频道列表
     */
    public void setChannels(List<Channel> channelSourceList, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) {
            names.add(c.getName());
        }

        this.selectedPosition = currentPlayIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);

                // ======================
                // 核心：选中 = 蓝色，未选中 = 白色
                // ======================
                if (position == selectedPosition) {
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                } else {
                    tv.setTextColor(Color.WHITE);
                }
                return view;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(currentPlayIndex);
    }

    /**
     * 按分组筛选频道
     */
    public void setChannelsByGroup(List<Channel> channelSourceList, String group, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        List<String> names = new ArrayList<>();
        int realIndex = 0;

        for (int i = 0; i < channelSourceList.size(); i++) {
            Channel c = channelSourceList.get(i);
            if (group == null || group.isEmpty() || group.equals(c.getGroup())) {
                names.add(c.getName());
                if (i == currentPlayIndex) {
                    realIndex = names.size() - 1;
                }
            }
        }

        this.selectedPosition = realIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);

                // 统一选中变色规则
                if (position == selectedPosition) {
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                } else {
                    tv.setTextColor(Color.WHITE);
                }
                return view;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(realIndex);
    }

    /**
     * 更新选中位置并刷新列表
     */
    public void setSelectedPosition(int position) {
        this.selectedPosition = position;
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    public void onBackPressed() {}
}
