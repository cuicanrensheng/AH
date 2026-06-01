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
 * 功能：TV遥控器滑动选中项自动变蓝
 */
public class ChannelListManager {
    private final ListView lvChannelList;
    private int selectedPosition = 0;

    public ChannelListManager(Context context, ListView lvChannelList) {
        this.lvChannelList = lvChannelList;
        // TV遥控器必加：允许item获取焦点
        lvChannelList.setItemsCanFocus(true);
        // 监听遥控器选中事件，更新选中位置并刷新颜色
        lvChannelList.setOnItemSelectedListener((parent, view, pos, id) -> {
            selectedPosition = pos;
            parent.invalidateViews();
        });
    }

    /**
     * 设置全频道列表
     */
    public void setChannels(List<Channel> channelSourceList, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) names.add(c.getName());
        selectedPosition = currentPlayIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(), android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                // 选中/焦点项变蓝，未选中白色
                if (position == selectedPosition || view.isFocused()) {
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
                if (i == currentPlayIndex) realIndex = names.size() - 1;
            }
        }
        selectedPosition = realIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(), android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                if (position == selectedPosition || view.isFocused()) {
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

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        lvChannelList.setSelection(position);
        lvChannelList.invalidateViews();
    }

    public void onBackPressed() {}
}
