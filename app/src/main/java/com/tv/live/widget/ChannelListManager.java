package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.tv.live.Channel;
import java.util.ArrayList;
import java.util.List;

public class ChannelListManager {
    private final ListView lvChannelList;
    private int selectedPosition = 0;

    public interface OnChannelClickListener {
        void onChannelClick(int position);
    }
    private OnChannelClickListener onChannelClickListener;

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.onChannelClickListener = listener;
    }

    public ChannelListManager(Context context, ListView lvChannelList) {
        this.lvChannelList = lvChannelList;
        // ✅ 改成 false，item 不需要获取焦点
        lvChannelList.setItemsCanFocus(false);

        // 点击事件
        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            ((ArrayAdapter<?>)parent.getAdapter()).notifyDataSetChanged();
            if (onChannelClickListener != null) {
                onChannelClickListener.onChannelClick(position);
            }
        });

        // ✅ 遥控器焦点选中时同步更新位置
        lvChannelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // 显示全部频道
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
                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);

                if (position == selectedPosition) {
                    // ✅ 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(0x3340A9FF);
                } else if (view.isFocused()) {
                    // ✅ 焦点状态：蓝色文字 + 稍深一点的蓝色背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(0x4440A9FF);
                } else {
                    // ✅ 未选中状态：白色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return view;
            }
        };
        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    // 按分组显示频道
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
        selectedPosition = realIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(), android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);

                if (position == selectedPosition) {
                    // ✅ 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(0x3340A9FF);
                } else if (view.isFocused()) {
                    // ✅ 焦点状态：蓝色文字 + 稍深一点的蓝色背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(0x4440A9FF);
                } else {
                    // ✅ 未选中状态：白色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return view;
            }
        };
        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        lvChannelList.setSelection(position);
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    public void onBackPressed() {}
}
