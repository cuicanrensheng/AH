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
 * 【职责】
 * 统一管理频道列表的显示、选中状态、点击事件等。
 *
 * 【2026-06-21 优化：焦点优先样式 + 区分焦点和选中状态】
 *
 * 【三种状态说明】
 * 1. 焦点状态：白色文字 + 蓝色背景（遥控器焦点所在的项，最显眼）
 * 2. 选中状态：蓝色文字 + 透明背景（当前播放的频道）
 * 3. 未选中状态：白色文字 + 透明背景（普通项）
 *
 * 【判断优先级】
 * 焦点状态 > 选中状态 > 未选中状态
 */
public class ChannelListManager {
    /** 频道列表 ListView */
    private final ListView lvChannelList;
    /** 当前选中位置（当前播放的频道） */
    private int selectedPosition = 0;
    // ====================================================================
    // ✅ 新增：焦点位置变量
    // ====================================================================
    /** 当前焦点位置（遥控器移动到的位置） */
    private int focusedPosition = 0;
    /** 频道点击监听器 */
    public interface OnChannelClickListener {
        void onChannelClick(int position);
    }
    private OnChannelClickListener onChannelClickListener;

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.onChannelClickListener = listener;
    }

    /**
     * 构造函数
     *
     * @param context 上下文
     * @param lvChannelList 频道列表 ListView
     */
    public ChannelListManager(Context context, ListView lvChannelList) {
        this.lvChannelList = lvChannelList;
        // item 不需要获取焦点，由 ListView 统一管理
        lvChannelList.setItemsCanFocus(false);

        // 点击事件：点击才真正选中
        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            setSelectedPosition(position);
            if (onChannelClickListener != null) {
                onChannelClickListener.onChannelClick(position);
            }
        });

        // ================================================================
        // ✅ 修改：遥控器焦点移动时只更新 focusedPosition，不更新 selectedPosition
        // ================================================================
        lvChannelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                // 只更新焦点位置，不更新选中位置
                setFocusedPosition(pos);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // ====================================================================
    // ✅ 新增：焦点位置相关方法
    // ====================================================================
    /**
     * 设置焦点位置（遥控器移动时调用）
     */
    public void setFocusedPosition(int position) {
        this.focusedPosition = position;
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    /**
     * 获取当前焦点位置
     */
    public int getFocusedPosition() {
        return focusedPosition;
    }

    // ====================================================================
    // 显示全部频道
    // ====================================================================
    /**
     * 设置全部频道列表
     *
     * @param channelSourceList 全部频道列表
     * @param currentPlayIndex 当前播放索引
     */
    public void setChannels(List<Channel> channelSourceList, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) names.add(c.getName());
        selectedPosition = currentPlayIndex;
        focusedPosition = currentPlayIndex;

        // 使用自定义布局，显示序号 + 频道名
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                // 使用自定义布局
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                }
                // 找到序号和频道名称两个 TextView
                TextView tvIndex = convertView.findViewById(R.id.tv_index);
                TextView tvChannel = convertView.findViewById(R.id.tv_channel);
                // 设置序号（从 1 开始）
                tvIndex.setText(String.valueOf(position + 1));
                // 设置频道名称
                tvChannel.setText(getItem(position));
                tvChannel.setTextSize(16);

                // ====================================================================
                // ✅ 2026-06-21 修改：统一三种状态样式（焦点优先）
                // ====================================================================
                // 判断优先级：焦点 > 选中 > 普通
                if (position == focusedPosition) {
                    // ── 焦点状态：白色文字 + 蓝色背景（最显眼）──
                    tvChannel.setTextColor(Color.WHITE);
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.parseColor("#40A9FF"));
                    // 序号也跟着变白色
                    tvIndex.setTextColor(Color.WHITE);
                } else if (position == selectedPosition) {
                    // ── 选中状态：蓝色文字 + 透明背景（次之）──
                    tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    // 序号也跟着变蓝色
                    tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                } else {
                    // ── 普通状态：白色文字 + 透明背景 ──
                    tvChannel.setTextColor(Color.WHITE);
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    // 序号灰色
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
     * @param channelSourceList 全部频道列表
     * @param group 分组名称
     * @param currentPlayIndex 当前播放索引
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
        focusedPosition = realIndex;

        // 使用自定义布局，显示序号 + 频道名
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                // 使用自定义布局
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                }
                // 找到序号和频道名称两个 TextView
                TextView tvIndex = convertView.findViewById(R.id.tv_index);
                TextView tvChannel = convertView.findViewById(R.id.tv_channel);
                // 设置序号（从 1 开始）
                tvIndex.setText(String.valueOf(position + 1));
                // 设置频道名称
                tvChannel.setText(getItem(position));
                tvChannel.setTextSize(16);

                // ====================================================================
                // ✅ 2026-06-21 修改：统一三种状态样式（焦点优先）
                // ====================================================================
                if (position == focusedPosition) {
                    // ── 焦点状态：白色文字 +浅蓝色背景
                    tvChannel.setTextColor(Color.WHITE);
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(0x3340A9FF);
                    tvIndex.setTextColor(Color.WHITE);
                } else if (position == selectedPosition) {
                    // ── 选中状态：蓝色文字 + 透明背景（次之）──
                    tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                } else {
                    // ── 普通状态：白色文字 + 透明背景 ──
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
     *
     * @param position 选中位置
     */
    public void setSelectedPosition(int position) {
        selectedPosition = position;
        // 选中时也同步移动焦点到选中项
        focusedPosition = position;
        lvChannelList.setSelection(position);
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    public void onBackPressed() {}
}
