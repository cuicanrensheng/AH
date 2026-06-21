package com.tv.live.widget;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.R;
import com.tv.live.manager.PanelStyleManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道列表管理器
 *
 * 【职责】
 * 统一管理频道列表的显示、选中状态、点击事件等。
 *
 * 【2026-06-21 优化：接入 PanelStyleManager 统一管理样式】
 * 【修改内容】
 * 1. 样式不再硬编码在这个文件里，统一调用 PanelStyleManager
 * 2. 注册模式变化监听器，遥控器/触屏模式切换时自动刷新
 * 3. 以后改样式只改 PanelStyleManager.java 就行，不用改这个文件
 *
 * 【两种模式说明】
 * 1. 遥控器模式：
 *    - 焦点：白色文字 + 浅蓝色背景（最显眼）
 *    - 选中：蓝色文字 + 透明背景（次之，当前播放的频道）
 *    - 普通：白色文字 + 透明背景
 *
 * 2. 触屏模式：
 *    - 选中：白色文字 + 深蓝色背景（明显的选中效果）
 *    - 普通：白色文字 + 透明背景
 */
public class ChannelListManager implements PanelStyleManager.OnModeChangedListener {
    /** 频道列表 ListView */
    private final ListView lvChannelList;
    /** 当前选中位置（当前播放的频道） */
    private int selectedPosition = 0;

    // ====================================================================
    // ✅ 焦点位置变量
    // ====================================================================
    /**
     * 当前焦点位置（遥控器移动到的位置）
     *
     * 【说明】
     * 单独记录焦点位置，和选中位置分开。
     * - 遥控器上下移动 → 只改变 focusedPosition
     * - 按 OK 键确认 → 改变 selectedPosition，并同步 focusedPosition
     */
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
        // ✅ 遥控器焦点移动时只更新 focusedPosition，不更新 selectedPosition
        // ================================================================
        lvChannelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                // 只更新焦点位置，不更新选中位置
                // 【说明】遥控器上下移动时，只是移动焦点，还没确认选中
                setFocusedPosition(pos);

                // ====================================================================
                // ✅ 2026-06-21 新增：遥控器操作 → 切换到遥控器模式
                // ====================================================================
                PanelStyleManager.getInstance().setMode(PanelStyleManager.MODE_REMOTE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // ====================================================================
        // ✅ 2026-06-21 新增：注册样式变化监听器
        // ====================================================================
        PanelStyleManager.getInstance().addOnModeChangedListener(this);
    }

    // ====================================================================
    // ✅ 2026-06-21 新增：模式变化回调
    // ====================================================================
    /**
     * 模式变化回调
     *
     * @param newMode 新模式
     */
    @Override
    public void onModeChanged(int newMode) {
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    // ====================================================================
    // ✅ 焦点位置相关方法
    // ====================================================================
    /**
     * 设置焦点位置（遥控器移动时调用）
     *
     * @param position 焦点位置
     */
    public void setFocusedPosition(int position) {
        this.focusedPosition = position;
        if (lvChannelList.getAdapter() != null) {
            ((ArrayAdapter<?>) lvChannelList.getAdapter()).notifyDataSetChanged();
        }
    }

    /**
     * 获取当前焦点位置
     *
     * @return 当前焦点位置
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
                // ✅ 2026-06-21 修改：统一调用 PanelStyleManager 应用样式
                // ====================================================================
                // 【判断优先级】焦点 > 选中 > 普通
                if (position == focusedPosition) {
                    // ── 焦点状态 ──
                    PanelStyleManager.getInstance().applyFocusStyle(convertView);
                    // 序号也跟着变白色（焦点样式下序号也是白色）
                    tvIndex.setTextColor(android.graphics.Color.WHITE);
                } else if (position == selectedPosition) {
                    // ── 选中状态 ──
                    PanelStyleManager.getInstance().applySelectedStyle(convertView);
                    // 序号也跟着变蓝色（选中样式下序号也是蓝色）
                    // 【注意】触屏模式下选中是深蓝色背景 + 白色文字，序号也要白色
                    // 所以这里需要根据模式来判断序号颜色
                    if (PanelStyleManager.getInstance().isRemoteMode()) {
                        tvIndex.setTextColor(android.graphics.Color.parseColor("#40A9FF"));
                    } else {
                        tvIndex.setTextColor(android.graphics.Color.WHITE);
                    }
                } else {
                    // ── 普通状态 ──
                    PanelStyleManager.getInstance().applyNormalStyle(convertView);
                    // 序号灰色
                    tvIndex.setTextColor(android.graphics.Color.parseColor("#888888"));
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
                // ✅ 2026-06-21 修改：统一调用 PanelStyleManager 应用样式
                // ====================================================================
                if (position == focusedPosition) {
                    // ── 焦点状态 ──
                    PanelStyleManager.getInstance().applyFocusStyle(convertView);
                    tvIndex.setTextColor(android.graphics.Color.WHITE);
                } else if (position == selectedPosition) {
                    // ── 选中状态 ──
                    PanelStyleManager.getInstance().applySelectedStyle(convertView);
                    if (PanelStyleManager.getInstance().isRemoteMode()) {
                        tvIndex.setTextColor(android.graphics.Color.parseColor("#40A9FF"));
                    } else {
                        tvIndex.setTextColor(android.graphics.Color.WHITE);
                    }
                } else {
                    // ── 普通状态 ──
                    PanelStyleManager.getInstance().applyNormalStyle(convertView);
                    tvIndex.setTextColor(android.graphics.Color.parseColor("#888888"));
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
     *
     * 【说明】
     * 选中时会同步移动焦点到选中项。
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

    // ====================================================================
    // ✅ 2026-06-21 新增：资源释放
    // ====================================================================
    /**
     * 释放资源
     */
    public void release() {
        PanelStyleManager.getInstance().removeOnModeChangedListener(this);
    }

    public void onBackPressed() {}
}
