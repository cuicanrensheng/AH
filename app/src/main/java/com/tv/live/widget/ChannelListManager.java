package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道列表管理器（已恢复遥控器焦点）
 */
public class ChannelListManager {
    private final ListView lvChannelList;
    private int selectedPosition = 0;
    private int focusedPosition = -1;
    private int currentPlayIndex = 0;

    private static final int COLOR_BLUE = 0xFF40A9FF;
    private static final int COLOR_BG_BLUE = 0x3340A9FF;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_GRAY = 0xFF888888;

    public interface OnChannelClickListener {
        void onChannelClick(int position);
    }
    private OnChannelClickListener onChannelClickListener;

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.onChannelClickListener = listener;
    }

    public interface OnChannelLongClickListener {
        boolean onChannelLongClick(String channelName, int position);
    }
    private OnChannelLongClickListener onChannelLongClickListener;

    public void setOnChannelLongClickListener(OnChannelLongClickListener listener) {
        this.onChannelLongClickListener = listener;
    }

    public ChannelListManager(Context context, ListView lvChannelList) {
        this.lvChannelList = lvChannelList;

        // ✅ 恢复焦点，支持遥控器方向键移动
        lvChannelList.setItemsCanFocus(true);
        lvChannelList.setFocusable(true);
        lvChannelList.setFocusableInTouchMode(true);

        // ✅ 恢复 OnItemSelectedListener，同步焦点位置
        lvChannelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPosition = position;
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 无焦点时保持当前选中
            }
        });

        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            if (onChannelClickListener != null) {
                onChannelClickListener.onChannelClick(position);
            }
        });

        lvChannelList.setOnItemLongClickListener((parent, view, position, id) -> {
            if (onChannelLongClickListener != null) {
                String channelName = null;
                if (parent.getAdapter() != null && position < parent.getAdapter().getCount()) {
                    Object item = parent.getAdapter().getItem(position);
                    if (item != null) {
                        channelName = item.toString();
                    }
                }
                return onChannelLongClickListener.onChannelLongClick(channelName, position);
            }
            return false;
        });
    }

    public void setChannels(List<Channel> channelSourceList, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) names.add(c.getName());

        selectedPosition = currentPlayIndex;
        focusedPosition = currentPlayIndex;
        this.currentPlayIndex = currentPlayIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                    holder = new ViewHolder();
                    holder.tvIndex = convertView.findViewById(R.id.tv_index);
                    holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (position == currentPlayIndex) {
                    holder.tvIndex.setText("▶");
                } else {
                    holder.tvIndex.setText(String.valueOf(position + 1));
                }

                holder.tvChannel.setText(getItem(position));
                holder.tvChannel.setTextSize(16);

                boolean isSelected = (position == selectedPosition);
                android.util.Log.d("ChannelList", "getView pos:" + position + ", selectedPos:" + selectedPosition + ", isSelected:" + isSelected);

                if (isSelected) {
                    holder.tvChannel.setTextColor(COLOR_BLUE);
                    holder.tvChannel.setTypeface(null, Typeface.BOLD);
                    convertView.setBackgroundColor(COLOR_BG_BLUE);
                    holder.tvIndex.setTextColor(COLOR_BLUE);
                } else {
                    holder.tvChannel.setTextColor(COLOR_WHITE);
                    holder.tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    holder.tvIndex.setTextColor(COLOR_GRAY);
                }

                return convertView;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

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
        focusedPosition = realIndex;
        this.currentPlayIndex = realIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                    holder = new ViewHolder();
                    holder.tvIndex = convertView.findViewById(R.id.tv_index);
                    holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (position == currentPlayIndex) {
                    holder.tvIndex.setText("▶");
                } else {
                    holder.tvIndex.setText(String.valueOf(position + 1));
                }

                holder.tvChannel.setText(getItem(position));
                holder.tvChannel.setTextSize(16);

                boolean isSelected = (position == selectedPosition);
                android.util.Log.d("ChannelList", "getView(byGroup) pos:" + position + ", selectedPos:" + selectedPosition + ", isSelected:" + isSelected);

                if (isSelected) {
                    holder.tvChannel.setTextColor(COLOR_BLUE);
                    holder.tvChannel.setTypeface(null, Typeface.BOLD);
                    convertView.setBackgroundColor(COLOR_BG_BLUE);
                    holder.tvIndex.setTextColor(COLOR_BLUE);
                } else {
                    holder.tvChannel.setTextColor(COLOR_WHITE);
                    holder.tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    holder.tvIndex.setTextColor(COLOR_GRAY);
                }

                return convertView;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    public void setFilteredChannels(List<Channel> filteredChannels, String currentPlayChannelName) {
        List<String> names = new ArrayList<>();
        int playIndex = 0;

        if (filteredChannels != null) {
            for (int i = 0; i < filteredChannels.size(); i++) {
                Channel c = filteredChannels.get(i);
                names.add(c.getName());
                if (currentPlayChannelName != null && currentPlayChannelName.equals(c.getName())) {
                    playIndex = i;
                }
            }
        }

        selectedPosition = playIndex;
        focusedPosition = playIndex;
        this.currentPlayIndex = playIndex;
        final int finalPlayIndex = playIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                    holder = new ViewHolder();
                    holder.tvIndex = convertView.findViewById(R.id.tv_index);
                    holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (position == finalPlayIndex && names.size() > 0) {
                    holder.tvIndex.setText("▶");
                } else {
                    holder.tvIndex.setText(String.valueOf(position + 1));
                }

                holder.tvChannel.setText(getItem(position));
                holder.tvChannel.setTextSize(16);

                boolean isSelected = (position == selectedPosition);
                android.util.Log.d("ChannelList", "getView(byGroup) pos:" + position + ", selectedPos:" + selectedPosition + ", isSelected:" + isSelected);

                if (isSelected) {
                    holder.tvChannel.setTextColor(COLOR_BLUE);
                    holder.tvChannel.setTypeface(null, Typeface.BOLD);
                    convertView.setBackgroundColor(COLOR_BG_BLUE);
                    holder.tvIndex.setTextColor(COLOR_BLUE);
                } else {
                    holder.tvChannel.setTextColor(COLOR_WHITE);
                    holder.tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    holder.tvIndex.setTextColor(COLOR_GRAY);
                }

                return convertView;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    private static class ViewHolder {
        TextView tvIndex;
        TextView tvChannel;
    }

    public void release() {
        if (lvChannelList != null) {
            lvChannelList.setAdapter(null);
            lvChannelList.setOnItemClickListener(null);
            lvChannelList.setOnItemSelectedListener(null);
        }
        onChannelClickListener = null;
        onChannelLongClickListener = null;
    }
}
