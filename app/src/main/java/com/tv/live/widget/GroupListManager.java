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
import com.tv.live.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 频道分组列表管理器
 *
 * 【功能说明】
 * 管理左侧第一栏的频道分组列表，负责：
 * 1. 从频道列表中提取所有分组名称
 * 2. 显示分组列表
 * 3. 处理分组选中/焦点高亮
 * 4. 回调分组选中事件
 *
 * 【高亮规则】
 * - 选中状态：蓝色文字 + 加粗 + 浅蓝色背景（#3340A9FF）
 * - 焦点状态：蓝色文字 + 常规 + 稍深蓝色背景（#4440A9FF）
 * - 未选中状态：白色文字 + 常规 + 透明背景
 *
 * 【新增内容】
 * 1. OnGroupSelectedListener 接口 - 分组选中回调
 * 2. setOnGroupSelectedListener() - 设置监听器
 * 3. setOnItemClickListener - 处理分组点击事件
 */
public class GroupListManager {

    // 绑定的 ListView 控件
    private final ListView lvGroup;
    // 上下文
    private final Context context;
    // 分组名称列表
    private List<String> groupList;
    // 当前选中的分组位置
    private int selectedPosition = 0;
    // 列表适配器
    private ArrayAdapter<String> adapter;

    // ====================================================================
    // ✅ 新增：分组选中监听器
    // ====================================================================
    /**
     * 分组选中事件监听器
     *
     * 【使用场景】
     * 当用户点击某个分组时触发回调，
     * 外部（MainActivity）收到回调后更新右侧频道列表。
     */
    private OnGroupSelectedListener groupListener;

    /**
     * 分组选中监听器接口
     */
    public interface OnGroupSelectedListener {
        /**
         * 分组被选中时回调
         *
         * @param groupName     选中的分组名称
         * @param groupChannels 该分组下的所有频道列表
         */
        void onGroupSelected(String groupName, List<Channel> groupChannels);
    }

    /**
     * 设置分组选中监听器
     *
     * @param listener 监听器实例
     */
    public void setOnGroupSelectedListener(OnGroupSelectedListener listener) {
        this.groupListener = listener;
    }

    /**
     * 构造函数
     *
     * @param context 上下文
     * @param lvGroup 分组列表 ListView 控件
     */
    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;

        // ✅ item 不需要获取焦点，由 ListView 统一管理焦点
        // 【为什么要设为 false？】
        // 如果 item 可以获取焦点，遥控器焦点会停在 item 内部的控件上，
        // 导致 ListView 的选中状态和焦点状态不同步，高亮显示异常。
        // 设为 false 后，焦点由 ListView 统一管理，选中和焦点状态一致。
        lvGroup.setItemsCanFocus(false);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // 遥控器选择时同步更新选中状态
        // 【为什么需要这个？】
        // 遥控器上下移动时，ListView 会回调 onItemSelected，
        // 这时候需要同步更新 selectedPosition，并重绘列表项，
        // 否则焦点移动了但高亮没跟上，视觉上不一致。
        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 什么都不做
            }
        });
    }

    /**
     * 设置分组数据（从频道列表中提取分组）
     *
     * 【处理逻辑】
     * 1. 遍历所有频道，用 HashSet 去重提取分组名称
     * 2. 创建适配器，设置自定义样式（选中高亮、焦点高亮）
     * 3. 默认选中第一个分组
     *
     * @param channelSourceList 完整的频道列表
     */
    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 用 HashSet 去重提取所有分组名称
        Set<String> groupSet = new HashSet<>();
        for (Channel c : channelSourceList) {
            groupSet.add(c.getGroup());
        }
        groupList = new ArrayList<>(groupSet);

        // 创建适配器，自定义 getView 实现高亮效果
        adapter = new ArrayAdapter<String>(lvGroup.getContext(), android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);

                // 设置文字大小和内边距
                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);

                // 根据状态设置不同的样式
                if (position == selectedPosition) {
                    // ✅ 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                    // 【为什么选中和焦点要分开？】
                    // 选中状态是"当前正在播放的分组"，焦点状态是"遥控器当前指向的分组"
                    // 两者可能不一样（比如焦点移到其他分组，但还没按确认键）
                    // 用不同的样式区分，用户能清楚知道哪个是当前的，哪个是指向的
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(0x3340A9FF);  // 20% 透明度的蓝色
                } else if (view.isFocused()) {
                    // ✅ 焦点状态：蓝色文字 + 常规 + 稍深一点的蓝色背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(0x4440A9FF);  // 27% 透明度的蓝色
                } else {
                    // ✅ 未选中状态：白色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }

                return tv;
            }
        };

        lvGroup.setAdapter(adapter);

        // 默认选中第一个
        selectedPosition = 0;
        adapter.notifyDataSetChanged();

        // ====================================================================
        // ✅ 新增：分组点击事件处理
        // ====================================================================
        /**
         * 【为什么要在这里设置点击事件？】
         *
         * 原来的设计是外部（MainActivity）直接给 ListView 设置 setOnItemClickListener，
         * 但这样会导致：
         * 1. 点击事件和选中状态不同步（点击了但 selectedPosition 没更新）
         * 2. 外部需要自己处理分组数据筛选，逻辑分散
         *
         * 改成在 GroupListManager 内部处理点击事件：
         * 1. 点击时自动更新 selectedPosition 并重绘
         * 2. 自动筛选该分组下的频道列表
         * 3. 通过回调把结果传给外部
         *
         * 这样外部（MainActivity）只需要关心"选中了哪个分组"，
         * 不需要关心分组数据怎么来的、怎么筛选，职责更清晰。
         */
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 1. 更新选中位置
                selectedPosition = position;
                adapter.notifyDataSetChanged();

                // 2. 如果设置了监听器，就回调
                if (groupListener != null && channelSourceList != null) {
                    // 获取分组名称
                    String groupName = groupList.get(position);

                    // 筛选该分组下的所有频道
                    List<Channel> groupChannels = new ArrayList<>();
                    for (Channel c : channelSourceList) {
                        if (groupName.equals(c.getGroup())) {
                            groupChannels.add(c);
                        }
                    }

                    // 回调给外部（MainActivity）
                    groupListener.onGroupSelected(groupName, groupChannels);
                }
            }
        });
    }

    /**
     * 设置选中位置（外部调用，立即刷新高亮）
     *
     * 【使用场景】
     * 外部需要主动切换选中的分组时调用，
     * 比如从设置页面回来，恢复上次选中的分组。
     *
     * @param position 要选中的分组索引
     */
    public void setSelectedPosition(int position) {
        if (groupList == null || adapter == null) return;
        if (position < 0 || position >= groupList.size()) return;

        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
    }

    /**
     * 获取当前分组名称
     *
     * @param position 分组索引
     * @return 分组名称，越界则返回空字符串
     */
    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    /**
     * 返回键处理（预留方法，目前什么都不做）
     */
    public void onBackPressed() {
        // 预留：如果需要分组列表的返回键逻辑，可以在这里加
    }
}
