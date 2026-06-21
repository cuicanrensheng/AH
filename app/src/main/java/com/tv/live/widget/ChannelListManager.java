package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.R;
import com.tv.live.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道列表管理器
 *
 * 【2026-06-21 修改：统一三种状态高亮样式（焦点优先）】
 * 【样式规则】
 * 焦点 > 选中 > 普通
 * - 焦点：白色文字 + 蓝色背景（最显眼，遥控器停在哪里）
 * - 选中：蓝色文字 + 透明背景（当前播放的频道）
 * - 普通：白色文字 + 透明背景
 */
public class ChannelListManager {
    private Context context;
    private ListView listView;
    private List<Channel> channelList = new ArrayList<>();
    private ChannelAdapter adapter;

    // ====================================================================
    // ✅ 新增：焦点位置和选中位置分开记录
    // ====================================================================
    /** 当前焦点位置（遥控器移动到的位置） */
    private int focusedPosition = 0;
    /** 当前选中位置（当前播放的频道） */
    private int selectedPosition = 0;

    private OnChannelClickListener listener;

    public interface OnChannelClickListener {
        void onChannelClick(int position, Channel channel);
    }

    public ChannelListManager(Context context, ListView listView) {
        this.context = context;
        this.listView = listView;
        adapter = new ChannelAdapter();
        listView.setAdapter(adapter);
        initListeners();
    }

    private void initListeners() {
        // 点击事件：点击才真正选中
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < channelList.size()) {
                    SettingsActivity.logOperation("【频道】点击选中：" + position + " - " + channelList.get(position).getName());
                    setSelectedPosition(position);
                    if (listener != null) {
                        listener.onChannelClick(position, channelList.get(position));
                    }
                }
            }
        });

        // 选中事件：只移动焦点，不选中
        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SettingsActivity.logOperation("【频道】焦点移动：" + position);
                // ✅ 只更新焦点位置，不更新选中位置
                setFocusedPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做处理
            }
        });
    }

    // ====================================================================
    // ✅ 新增：设置焦点位置
    // ====================================================================
    public void setFocusedPosition(int position) {
        if (position < 0 || position >= channelList.size()) return;
        this.focusedPosition = position;
        adapter.notifyDataSetChanged();
    }

    public int getFocusedPosition() {
        return focusedPosition;
    }

    public void setSelectedPosition(int position) {
        if (position < 0 || position >= channelList.size()) return;
        this.selectedPosition = position;
        // 选中时也同步移动焦点到选中项
        this.focusedPosition = position;
        adapter.notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setChannels(List<Channel> channels, int currentIndex) {
        channelList.clear();
        if (channels != null) {
            channelList.addAll(channels);
        }
        this.selectedPosition = Math.max(0, Math.min(currentIndex, channelList.size() - 1));
        this.focusedPosition = this.selectedPosition;
        adapter.notifyDataSetChanged();
    }

    public void setChannelsByGroup(List<Channel> allChannels, String groupName, int currentPlayIndex) {
        channelList.clear();
        if (allChannels != null && groupName != null) {
            for (Channel c : allChannels) {
                if (groupName.equals(c.getGroup())) {
                    channelList.add(c);
                }
            }
        }
        // 找到当前播放频道在分组中的索引
        int groupIndex = 0;
        if (!channelList.isEmpty()) {
            Channel currentChannel = null;
            if (currentPlayIndex >= 0 && currentPlayIndex < allChannels.size()) {
                currentChannel = allChannels.get(currentPlayIndex);
            }
            if (currentChannel != null) {
                for (int i = 0; i < channelList.size(); i++) {
                    if (channelList.get(i).getName().equals(currentChannel.getName())) {
                        groupIndex = i;
                        break;
                    }
                }
            }
        }
        this.selectedPosition = groupIndex;
        this.focusedPosition = groupIndex;
        adapter.notifyDataSetChanged();
    }

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.listener = listener;
    }

    // ====================================================================
    // 适配器
    // ====================================================================
    private class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return channelList.size();
        }

        @Override
        public Object getItem(int position) {
            return channelList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_channel, parent, false);
                holder = new ViewHolder();
                holder.tvIndex = convertView.findViewById(R.id.tv_index);
                holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                // 去掉系统默认焦点高亮
                convertView.setDefaultFocusHighlightEnabled(false);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            Channel channel = channelList.get(position);
            holder.tvIndex.setText(String.valueOf(position + 1));
            holder.tvChannel.setText(channel.getName());

            // ====================================================================
            // ✅ 修改：统一三种状态样式（焦点优先）
            // ====================================================================
            // 判断优先级：焦点 > 选中 > 普通
            if (position == focusedPosition) {
                // ── 焦点状态：白色文字 + 蓝色背景（最显眼）──
                holder.tvChannel.setTextColor(Color.WHITE);
                holder.tvIndex.setTextColor(Color.WHITE);
                holder.tvChannel.setTypeface(Typeface.DEFAULT);
                holder.tvIndex.setTypeface(Typeface.DEFAULT);
                convertView.setBackgroundColor(Color.parseColor("#40A9FF"));
            } else if (position == selectedPosition) {
                // ── 选中状态：蓝色文字 + 透明背景（次之）──
                holder.tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                holder.tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                holder.tvChannel.setTypeface(Typeface.DEFAULT);
                holder.tvIndex.setTypeface(Typeface.DEFAULT);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            } else {
                // ── 普通状态：白色文字 + 透明背景 ──
                holder.tvChannel.setTextColor(Color.WHITE);
                holder.tvIndex.setTextColor(Color.WHITE);
                holder.tvChannel.setTypeface(Typeface.DEFAULT);
                holder.tvIndex.setTypeface(Typeface.DEFAULT);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            return convertView;
        }

        class ViewHolder {
            TextView tvIndex;
            TextView tvChannel;
        }
    }
}
