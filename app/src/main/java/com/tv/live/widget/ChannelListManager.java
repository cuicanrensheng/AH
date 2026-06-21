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
 * 频道列表管理器
 *
 * 【2026-06-21 修复：当前播放指示】
 * 【新增功能】
 * 正在播放的频道前面显示 ▶️ 播放图标，一目了然。
 *
 * 【2026-06-21 新增：支持筛选后的频道列表】
 * 【说明】
 * 新增 setFilteredChannels() 方法，用于显示收藏、最近观看等筛选后的列表。
 *
 * 【2026-06-21 新增：长按收藏/取消收藏】
 * 【说明】
 * 触屏模式下，长按频道项可以收藏/取消收藏该频道。
 */
public class ChannelListManager {
    /** 频道列表 ListView */
    private final ListView lvChannelList;
    /** 当前选中位置（遥控器焦点/点击选中） */
    private int selectedPosition = 0;
    /** 当前播放位置（正在播放的频道） */
    private int currentPlayIndex = 0;

    /** 频道点击监听器 */
    public interface OnChannelClickListener {
        void onChannelClick(int position);
    }
    private OnChannelClickListener onChannelClickListener;

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.onChannelClickListener = listener;
    }

    // ====================================================================
    // ✅ 2026-06-21 新增：长按监听器
    // ====================================================================
    /**
     * 频道长按监听器
     */
    public interface OnChannelLongClickListener {
        /**
         * 频道被长按了
         *
         * @param channelName 被长按的频道名称
         * @param position 被长按的位置
         * @return true 表示消费了事件
         */
        boolean onChannelLongClick(String channelName, int position);
    }

    private OnChannelLongClickListener onChannelLongClickListener;

    /**
     * 设置频道长按监听器
     */
    public void setOnChannelLongClickListener(OnChannelLongClickListener listener) {
        this.onChannelLongClickListener = listener;
    }

    /**
     * 构造函数
     */
    public ChannelListManager(Context context, ListView lvChannelList) {
        this.lvChannelList = lvChannelList;
        lvChannelList.setItemsCanFocus(false);

        // 点击事件
        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            if (onChannelClickListener != null) {
                onChannelClickListener.onChannelClick(position);
            }
        });

        // ✅ 新增：长按事件
        lvChannelList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
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
            }
        });

        // 遥控器焦点选中时同步更新位置
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
    public void setFilteredChannels(List<Channel> filteredChannels, String currentPlayChannelName) {
    List<String> names = new ArrayList<>();
    int playIndex = 0;
    // ✅ 加个空判断，防止空指针
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
    this.currentPlayIndex = playIndex;
    final int finalPlayIndex = playIndex;

    ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
            R.layout.item_channel, names) {
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_channel, parent, false);
            }
            TextView tvIndex = convertView.findViewById(R.id.tv_index);
            TextView tvChannel = convertView.findViewById(R.id.tv_channel);

            // ✅ 加个判断，空列表时不显示 ▶
            if (position == finalPlayIndex && names.size() > 0) {
                tvIndex.setText("▶");
            } else {
                tvIndex.setText(String.valueOf(position + 1));
            }
            tvChannel.setText(getItem(position));
            tvChannel.setTextSize(16);

            // 三种状态样式
            if (position == selectedPosition) {
                tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                tvChannel.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
                tvIndex.setTextColor(Color.parseColor("#40A9FF"));
            } else if (convertView.isFocused()) {
                tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                tvChannel.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
                tvIndex.setTextColor(Color.parseColor("#40A9FF"));
            } else {
                tvChannel.setTextColor(Color.WHITE);
                tvChannel.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
                tvIndex.setTextColor(Color.parseColor("#888888"));
            }
            return convertView;
        }
    };
    lvChannelList.setAdapter(adapter);
    // ✅ 加个判断，空列表时不设置选中位置
    if (names.size() > 0) {
        lvChannelList.setSelection(selectedPosition);
    }
}

    // ====================================================================
    // 按分组显示频道
    // ====================================================================
    /**
     * 按分组显示频道
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
        selectedPosition = realIndex;
        this.currentPlayIndex = realIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                }
                TextView tvIndex = convertView.findViewById(R.id.tv_index);
                TextView tvChannel = convertView.findViewById(R.id.tv_channel);

                // ✅ 当前播放的频道显示 ▶️ 图标
                if (position == currentPlayIndex) {
                    tvIndex.setText("▶");
                } else {
                    tvIndex.setText(String.valueOf(position + 1));
                }
                tvChannel.setText(getItem(position));
                tvChannel.setTextSize(16);

                // 三种状态样式
                if (position == selectedPosition) {
                    tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                    tvChannel.setTypeface(null, Typeface.BOLD);
                    convertView.setBackgroundColor(0x3340A9FF);
                    tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                } else if (convertView.isFocused()) {
                    tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                } else {
                    tvChannel.setTextColor(Color.WHITE);
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    tvIndex.setTextColor(Color.parseColor("#888888"));
                }
                return convertView;
            }
        };
        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    // ====================================================================
    // ✅ 2026-06-21 新增：显示筛选后的频道列表（收藏/最近观看）
    // ====================================================================
    /**
     * 显示筛选后的频道列表（用于收藏、最近观看等）
     *
     * @param filteredChannels 筛选后的频道列表
     * @param currentPlayChannelName 当前播放的频道名（用于高亮播放指示）
     *
     * 【说明】
     * 因为筛选后的列表和全局列表索引不一样，
     * 所以用频道名来匹配当前播放的频道。
     */
    public void setFilteredChannels(List<Channel> filteredChannels, String currentPlayChannelName) {
        if (filteredChannels == null || filteredChannels.isEmpty()) return;
        List<String> names = new ArrayList<>();
        int playIndex = 0;
        for (int i = 0; i < filteredChannels.size(); i++) {
            Channel c = filteredChannels.get(i);
            names.add(c.getName());
            if (currentPlayChannelName != null && currentPlayChannelName.equals(c.getName())) {
                playIndex = i;
            }
        }
        selectedPosition = playIndex;
        this.currentPlayIndex = playIndex;
        final int finalPlayIndex = playIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                }
                TextView tvIndex = convertView.findViewById(R.id.tv_index);
                TextView tvChannel = convertView.findViewById(R.id.tv_channel);

                // ✅ 当前播放的频道显示 ▶️ 图标
                if (position == finalPlayIndex) {
                    tvIndex.setText("▶");
                } else {
                    tvIndex.setText(String.valueOf(position + 1));
                }
                tvChannel.setText(getItem(position));
                tvChannel.setTextSize(16);

                // 三种状态样式
                if (position == selectedPosition) {
                    tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                    tvChannel.setTypeface(null, Typeface.BOLD);
                    convertView.setBackgroundColor(0x3340A9FF);
                    tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                } else if (convertView.isFocused()) {
                    tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                } else {
                    tvChannel.setTextColor(Color.WHITE);
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    tvIndex.setTextColor(Color.parseColor("#888888"));
                }
                return convertView;
            }
        };
        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    /**
     * 设置选中位置
     */
    public void setSelectedPosition(int position) {
        selectedPosition = position;
        lvChannelList.setSelection(position);
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    /**
     * 设置当前播放索引（切换频道时调用）
     */
    public void setCurrentPlayIndex(int playIndex) {
        this.currentPlayIndex = playIndex;
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    public void onBackPressed() {}
}
