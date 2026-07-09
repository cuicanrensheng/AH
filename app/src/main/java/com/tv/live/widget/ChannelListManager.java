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
 *
 * 【2026-06-21 修复：空列表也要更新适配器】
 * 【说明】
 * 收藏/最近观看列表为空时，也要更新 ListView，
 * 不然会停留在上一个分组的内容，造成混淆。
 *
 * 【2026-06-24 修改：增加焦点态样式区分】
 * 【修改说明】
 * 新增 hasFocus 变量和 setFocused 方法，区分"有焦点的选中"和"无焦点的选中"。
 * 
 * 【样式规范】
 * - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
 * - 无焦点 + 选中：蓝色文字 + 透明背景
 * - 未选中：白色文字 + 透明背景
 */
public class ChannelListManager {
    /** 频道列表 ListView */
    private final ListView lvChannelList;
    /** 当前选中位置（遥控器焦点/点击选中） */
    private int selectedPosition = 0;
    /** 当前播放位置（正在播放的频道） */
    private int currentPlayIndex = 0;

    // 🟢【优化1】预定义颜色常量，彻底避免 getView 中反复解析字符串
    private static final int COLOR_BLUE = 0xFF40A9FF;
    private static final int COLOR_BG_BLUE = 0x3340A9FF;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_GRAY = 0xFF888888;

    /** 当前列表是否有焦点 */
    private boolean hasFocus = false;

    /** 频道点击监听器 */
    public interface OnChannelClickListener {
        void onChannelClick(int position);
    }
    private OnChannelClickListener onChannelClickListener;

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.onChannelClickListener = listener;
    }

    /** 频道长按监听器 */
    public interface OnChannelLongClickListener {
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

        // 长按事件
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

    /**
     * 设置当前列表是否有焦点
     */
    public void setFocused(boolean focused) {
        if (this.hasFocus == focused) return;
        this.hasFocus = focused;
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    public boolean isFocused() {
        return hasFocus;
    }

    // ====================================================================
    // 显示全部频道
    // ====================================================================
    public void setChannels(List<Channel> channelSourceList, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) names.add(c.getName());

        selectedPosition = currentPlayIndex;
        this.currentPlayIndex = currentPlayIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                // 🟢【核心优化】引入 ViewHolder 模式，彻底消灭卡顿
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

                // ✅ 当前播放的频道显示 ▶️ 图标
                if (position == currentPlayIndex) {
                    holder.tvIndex.setText("▶");
                } else {
                    // 🟢 虽然这里需要转字符串，但为了可读性保留。若有极度高频滑动需求，可提前转成 String 数组缓存。
                    holder.tvIndex.setText(String.valueOf(position + 1));
                }

                holder.tvChannel.setText(getItem(position));
                holder.tvChannel.setTextSize(16);

                // 🟢 直接使用预定义的静态颜色常量
                if (position == selectedPosition) {
                    if (hasFocus) {
                        holder.tvChannel.setTextColor(COLOR_BLUE);
                        holder.tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(COLOR_BG_BLUE);
                        holder.tvIndex.setTextColor(COLOR_BLUE);
                    } else {
                        holder.tvChannel.setTextColor(COLOR_BLUE);
                        holder.tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(Color.TRANSPARENT);
                        holder.tvIndex.setTextColor(COLOR_BLUE);
                    }
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

    // ====================================================================
    // 按分组显示频道
    // ====================================================================
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
                // 🟢【核心优化】引入 ViewHolder
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

                if (position == selectedPosition) {
                    if (hasFocus) {
                        holder.tvChannel.setTextColor(COLOR_BLUE);
                        holder.tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(COLOR_BG_BLUE);
                        holder.tvIndex.setTextColor(COLOR_BLUE);
                    } else {
                        holder.tvChannel.setTextColor(COLOR_BLUE);
                        holder.tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(Color.TRANSPARENT);
                        holder.tvIndex.setTextColor(COLOR_BLUE);
                    }
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

    // ====================================================================
    // ✅ 显示筛选后的频道列表
    // ====================================================================
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
        this.currentPlayIndex = playIndex;
        final int finalPlayIndex = playIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                // 🟢【核心优化】引入 ViewHolder
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

                if (position == selectedPosition) {
                    if (hasFocus) {
                        holder.tvChannel.setTextColor(COLOR_BLUE);
                        holder.tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(COLOR_BG_BLUE);
                        holder.tvIndex.setTextColor(COLOR_BLUE);
                    } else {
                        holder.tvChannel.setTextColor(COLOR_BLUE);
                        holder.tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(Color.TRANSPARENT);
                        holder.tvIndex.setTextColor(COLOR_BLUE);
                    }
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

    // 🟢【新增】静态内部类 ViewHolder，极低内存占用
    private static class ViewHolder {
        TextView tvIndex;
        TextView tvChannel;
    }
}
