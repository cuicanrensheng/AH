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
import com.tv.live.SettingsActivity;

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
 * 【2026-06-21 新增：排查日志】
 * 【说明】
 * 在关键位置加上日志，方便排查收藏和最近观看功能的问题。
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

    // ====================================================================
    // ✅ 2026-06-24 新增：焦点状态
    // ====================================================================
    /**
     * 当前列表是否有焦点
     * 
     * 【作用】
     * 区分"有焦点的选中"和"无焦点的选中"：
     * - true = 当前光标在这个列表上，选中项用浅蓝色背景 + 蓝色文字 + 加粗
     * - false = 当前光标不在这个列表上，选中项用蓝色文字 + 透明背景
     */
    private boolean hasFocus = false;

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
        // ✅ 加日志：确认监听器被设置了
        SettingsActivity.logOperation("【列表】setOnChannelLongClickListener 被调用，listener=" 
                + (listener != null ? "已设置" : "null"));
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

        // ✅ 新增：长按事件（加了日志）
        lvChannelList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                // ✅ 日志 1：确认长按事件触发了
                SettingsActivity.logOperation("【列表】长按事件触发，position=" + position 
                        + ", listener=" + (onChannelLongClickListener != null ? "已设置" : "未设置"));
                
                if (onChannelLongClickListener != null) {
                    String channelName = null;
                    if (parent.getAdapter() != null && position < parent.getAdapter().getCount()) {
                        Object item = parent.getAdapter().getItem(position);
                        if (item != null) {
                            channelName = item.toString();
                        }
                    }
                    // ✅ 日志 2：回调前
                    SettingsActivity.logOperation("【列表】长按回调，channelName=" + channelName);
                    boolean result = onChannelLongClickListener.onChannelLongClick(channelName, position);
                    // ✅ 日志 3：回调后
                    SettingsActivity.logOperation("【列表】长按回调结果=" + result);
                    return result;
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

    // ====================================================================
    // ✅ 2026-06-24 新增：设置焦点状态
    // ====================================================================
    /**
     * 设置当前列表是否有焦点
     * 
     * @param focused true=有焦点，false=无焦点
     */
    public void setFocused(boolean focused) {
        if (this.hasFocus == focused) return;
        this.hasFocus = focused;
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    /**
     * 获取当前是否有焦点
     */
    public boolean isFocused() {
        return hasFocus;
    }

    // ====================================================================
    // 显示全部频道
    // ====================================================================
    /**
     * 设置全部频道列表
     * 
     * 【2026-06-24 修改：样式区分焦点态】
     * 选中态分两种：
     * - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
     * - 无焦点 + 选中：蓝色文字 + 透明背景
     */
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

                // ====================================================================
                // ✅ 2026-06-24 修改：三种状态样式（区分焦点态）
                // ====================================================================
                // 【样式规范】
                // - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
                // - 无焦点 + 选中：蓝色文字 + 透明背景
                // - 未选中：白色文字 + 透明背景
                if (position == selectedPosition) {
                    if (hasFocus) {
                        // ⭐ 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
                        tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                        tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(0x3340A9FF); // 20%透明度的蓝色
                        tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                    } else {
                        // 无焦点 + 选中：蓝色文字 + 透明背景（只是标记，不抢视线）
                        tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                        tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(Color.TRANSPARENT);
                        tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                    }
                } else {
                    // 未选中：白色文字 + 透明背景
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
    // 按分组显示频道
    // ====================================================================
    /**
     * 按分组显示频道
     * 
     * 【2026-06-24 修改：样式区分焦点态】
     * 选中态分两种：
     * - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
     * - 无焦点 + 选中：蓝色文字 + 透明背景
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

                // ====================================================================
                // ✅ 2026-06-24 修改：三种状态样式（区分焦点态）
                // ====================================================================
                // 【样式规范】
                // - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
                // - 无焦点 + 选中：蓝色文字 + 透明背景
                // - 未选中：白色文字 + 透明背景
                if (position == selectedPosition) {
                    if (hasFocus) {
                        // ⭐ 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
                        tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                        tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(0x3340A9FF); // 20%透明度的蓝色
                        tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                    } else {
                        // 无焦点 + 选中：蓝色文字 + 透明背景（只是标记，不抢视线）
                        tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                        tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(Color.TRANSPARENT);
                        tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                    }
                } else {
                    // 未选中：白色文字 + 透明背景
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
    // ✅ 2026-06-21 修复：显示筛选后的频道列表（收藏/最近观看）
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
     *
     * 【2026-06-21 修复】
     * 即使列表为空也要更新适配器，不然会停留在上一个分组的内容。
     * 
     * 【2026-06-24 修改：样式区分焦点态】
     * 选中态分两种：
     * - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
     * - 无焦点 + 选中：蓝色文字 + 透明背景
     */
    public void setFilteredChannels(List<Channel> filteredChannels, String currentPlayChannelName) {
        // ✅ 日志 4：确认方法被调用了
        SettingsActivity.logOperation("【列表】setFilteredChannels 被调用，列表大小=" 
                + (filteredChannels == null ? "null" : filteredChannels.size())
                + ", 当前频道=" + currentPlayChannelName);
        
        // ✅ 修复：去掉了空列表直接 return 的判断，空列表也要更新
        List<String> names = new ArrayList<>();
        int playIndex = 0;

        // ✅ 加空判断，防止空指针
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

                // ✅ 加判断：列表不为空时才显示 ▶
                if (position == finalPlayIndex && names.size() > 0) {
                    tvIndex.setText("▶");
                } else {
                    tvIndex.setText(String.valueOf(position + 1));
                }

                tvChannel.setText(getItem(position));
                tvChannel.setTextSize(16);

                // ====================================================================
                // ✅ 2026-06-24 修改：三种状态样式（区分焦点态）
                // ====================================================================
                // 【样式规范】
                // - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
                // - 无焦点 + 选中：蓝色文字 + 透明背景
                // - 未选中：白色文字 + 透明背景
                if (position == selectedPosition) {
                    if (hasFocus) {
                        // ⭐ 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
                        tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                        tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(0x3340A9FF); // 20%透明度的蓝色
                        tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                    } else {
                        // 无焦点 + 选中：蓝色文字 + 透明背景（只是标记，不抢视线）
                        tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                        tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(Color.TRANSPARENT);
                        tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                    }
                } else {
                    // 未选中：白色文字 + 透明背景
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
}
