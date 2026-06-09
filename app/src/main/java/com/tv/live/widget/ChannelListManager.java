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
    private List<String> channelNames; // 新增：缓存频道名称列表，避免重复创建

    // 点击回调
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
        this.channelNames = new ArrayList<>(); // 初始化缓存

        // 点击事件（保留原有逻辑，增加Adapter空指针防护）
        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            if (parent.getAdapter() != null) {
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }
            if (onChannelClickListener != null) {
                onChannelClickListener.onChannelClick(position);
            }
        });

        // 焦点选中（保留原有逻辑，增加Adapter空指针防护）
        lvChannelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
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

    // 显示全部频道
    public void setChannels(List<Channel> channelSourceList, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            channelNames.clear();
            lvChannelList.setAdapter(null);
            return;
        }

        // 构建频道名称列表
        channelNames.clear();
        for (Channel c : channelSourceList) {
            if (c != null && c.getName() != null) { // 空指针防护
                channelNames.add(c.getName());
            }
        }

        // 防护：初始选中位置越界
        selectedPosition = Math.max(0, Math.min(currentPlayIndex, channelNames.size() - 1));

        // 设置Adapter（复用通用Adapter创建逻辑）
        lvChannelList.setAdapter(createChannelAdapter(channelNames));
        lvChannelList.setSelection(selectedPosition);
    }

    // 按分组显示频道
    public void setChannelsByGroup(List<Channel> channelSourceList, String group, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            channelNames.clear();
            lvChannelList.setAdapter(null);
            return;
        }

        // 按分组筛选频道
        channelNames.clear();
        int realIndex = 0;
        for (int i = 0; i < channelSourceList.size(); i++) {
            Channel c = channelSourceList.get(i);
            if (c == null) continue; // 空指针防护

            // 分组匹配逻辑（兼容group为空的情况）
            boolean isMatch = (group == null || group.isEmpty()) 
                    || group.equals(c.getGroup());
            if (isMatch) {
                channelNames.add(c.getName());
                // 匹配当前播放索引时，记录真实位置
                if (i == currentPlayIndex) {
                    realIndex = channelNames.size() - 1;
                }
            }
        }

        // 防护：realIndex越界
        selectedPosition = Math.max(0, Math.min(realIndex, channelNames.size() - 1));

        // 设置Adapter（复用通用Adapter创建逻辑）
        lvChannelList.setAdapter(createChannelAdapter(channelNames));
        lvChannelList.setSelection(selectedPosition);
    }

    // 修复：保留原有逻辑，增加空指针防护
    public void setSelectedPosition(int position) {
        if (channelNames.isEmpty()) return;
        selectedPosition = Math.max(0, Math.min(position, channelNames.size() - 1));
        lvChannelList.setSelection(selectedPosition);
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    // 新增：获取当前选中的频道名称
    public String getSelectedChannelName() {
        if (channelNames.isEmpty() || selectedPosition < 0 || selectedPosition >= channelNames.size()) {
            return "";
        }
        return channelNames.get(selectedPosition);
    }

    // 私有方法：复用Adapter创建逻辑，减少代码冗余
    private ArrayAdapter<String> createChannelAdapter(List<String> data) {
        return new ArrayAdapter<String>(lvChannelList.getContext(), android.R.layout.simple_list_item_1, data) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                // 统一颜色逻辑：默认白色，选中项高亮
                tv.setTextColor(Color.WHITE);
                if (position == selectedPosition) {
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                }
                return view;
            }
        };
    }

    public void onBackPressed() {}
}
