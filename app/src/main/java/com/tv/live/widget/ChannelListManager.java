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
        lvChannelList.setItemsCanFocus(true);

        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            ((ArrayAdapter<?>)parent.getAdapter()).notifyDataSetChanged();
            if (onChannelClickListener != null) {
                onChannelClickListener.onChannelClick(position);
            }
        });

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
                tv.setTextColor(Color.WHITE);
                if (position == selectedPosition) {
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                }
                return view;
            }
        };
        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    // 【核心修复】分组切换 → 正确刷新右侧频道列表
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
                tv.setTextColor(Color.WHITE);
                if (position == selectedPosition) {
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                }
                return view;
            }
        };
        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
        adapter.notifyDataSetChanged();
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
