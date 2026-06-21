package com.tv.live.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.manager.PanelStyleManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分组列表管理器
 *
 * 【职责】
 * 统一管理频道分组列表的显示、选中状态、点击事件等。
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
 *    - 选中：蓝色文字 + 透明背景（次之）
 *    - 普通：白色文字 + 透明背景
 *
 * 2. 触屏模式：
 *    - 选中：白色文字 + 深蓝色背景（明显的选中效果）
 *    - 普通：白色文字 + 透明背景
 *
 * 【为什么用 focusedPosition 变量？】
 * 因为 ListView 的焦点在 ListView 控件上，不在 item 上，
 * 所以 view.isFocused() 判断不准确，需要自己记录焦点位置。
 *
 * 【为什么要设置 setSelector 为透明？】
 * 因为 ListView 在 CHOICE_MODE_SINGLE 模式下，会给选中的 item
 * 自动加上一个系统默认的蓝色选中背景框，和我们自定义的样式叠加，
 * 导致背景太亮/有两个背景。设置为透明后，就只用我们自定义的样式。
 */
public class GroupListManager implements PanelStyleManager.OnModeChangedListener {
    /** 分组列表 ListView */
    private final ListView lvGroup;
    /** 上下文 */
    private final Context context;
    /** 分组名称列表 */
    private List<String> groupList;
    /** 当前选中位置（点击 OK 键选中的） */
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

    /** 列表适配器 */
    private ArrayAdapter<String> adapter;
    /** 分组选中监听器（供外部回调） */
    private OnGroupSelectedListener listener;

    /**
     * 分组选中监听器接口
     */
    public interface OnGroupSelectedListener {
        void onGroupSelected(int position, String groupName);
    }

    /**
     * 设置分组选中监听器
     */
    public void setOnGroupSelectedListener(OnGroupSelectedListener listener) {
        this.listener = listener;
    }

    /**
     * 构造函数
     *
     * @param context 上下文
     * @param lvGroup 分组列表 ListView
     */
    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;

        // item 不需要获取焦点，由 ListView 统一管理
        lvGroup.setItemsCanFocus(false);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // ====================================================================
        // ✅ 去掉 ListView 默认的选中背景条
        // ====================================================================
        // 【为什么要加这行？】
        // ListView 在 CHOICE_MODE_SINGLE 模式下，会给选中的 item 自动加上
        // 一个系统默认的蓝色选中背景（listSelector），和我们自定义的背景叠加，
        // 导致背景颜色太亮、有重影。设置为透明后，就只显示我们自定义的样式。
        lvGroup.setSelector(android.R.color.transparent);

        // ================================================================
        // ✅ 焦点移动时只更新 focusedPosition，不更新 selectedPosition
        // ================================================================
        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                // 只更新焦点位置，不更新选中位置
                // 【说明】遥控器上下移动时，只是移动焦点，还没确认选中
                setFocusedPosition(pos);

                // ====================================================================
                // ✅ 2026-06-21 新增：遥控器操作 → 切换到遥控器模式
                // ====================================================================
                // 【说明】
                // onItemSelected 只有遥控器/键盘操作才会触发，
                // 所以这里可以确定是遥控器操作，切换到遥控器模式。
                PanelStyleManager.getInstance().setMode(PanelStyleManager.MODE_REMOTE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // ================================================================
        // ✅ 点击选中事件（按 OK 键或触屏点击时触发）
        // ================================================================
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 按 OK 键才真正选中
                setSelectedPosition(position);
            }
        });

        // ====================================================================
        // ✅ 2026-06-21 新增：注册样式变化监听器
        // ====================================================================
        // 【说明】
        // 当模式从遥控器切换到触屏，或者从触屏切换到遥控器时，
        // 自动刷新列表，保持样式一致。
        PanelStyleManager.getInstance().addOnModeChangedListener(this);
    }

    // ====================================================================
    // ✅ 2026-06-21 新增：模式变化回调
    // ====================================================================
    /**
     * 模式变化回调（实现 PanelStyleManager.OnModeChangedListener 接口）
     *
     * @param newMode 新模式（MODE_REMOTE 或 MODE_TOUCH）
     *
     * 【说明】
     * 当模式变化时，刷新列表，应用新的样式。
     */
    @Override
    public void onModeChanged(int newMode) {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // ====================================================================
    // ✅ 焦点位置相关方法
    // ====================================================================
    /**
     * 设置焦点位置（遥控器移动时调用）
     *
     * @param position 焦点位置
     *
     * 【说明】
     * 只更新焦点位置，刷新列表显示，
     * 不改变选中状态，不触发分组切换。
     */
    public void setFocusedPosition(int position) {
        if (groupList == null || adapter == null) return;
        if (position < 0 || position >= groupList.size()) return;
        this.focusedPosition = position;
        adapter.notifyDataSetChanged();
    }

    /**
     * 获取当前焦点位置
     *
     * @return 当前焦点位置
     */
    public int getFocusedPosition() {
        return focusedPosition;
    }

    /**
     * 设置分组列表
     *
     * @param channelSourceList 全部频道列表
     */
    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 提取所有分组（去重）
        Set<String> groupSet = new HashSet<>();
        for (Channel c : channelSourceList) groupSet.add(c.getGroup());
        groupList = new ArrayList<>(groupSet);

        adapter = new ArrayAdapter<String>(lvGroup.getContext(),
                android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);

                // ====================================================================
                // ✅ 2026-06-21 修改：统一调用 PanelStyleManager 应用样式
                // ====================================================================
                // 【判断优先级】焦点 > 选中 > 普通
                // 【为什么统一调用？】
                // 以后改样式只改 PanelStyleManager.java 就行，不用改每个管理器。
                if (position == focusedPosition) {
                    // ──────────────────────────────────────────────
                    // ✅ 焦点状态：调用 PanelStyleManager 应用焦点样式
                    // ──────────────────────────────────────────────
                    PanelStyleManager.getInstance().applyFocusStyle(view);
                } else if (position == selectedPosition) {
                    // ──────────────────────────────────────────────
                    // ✅ 选中状态：调用 PanelStyleManager 应用选中样式
                    // ──────────────────────────────────────────────
                    PanelStyleManager.getInstance().applySelectedStyle(view);
                } else {
                    // ──────────────────────────────────────────────
                    // ✅ 普通状态：调用 PanelStyleManager 应用普通样式
                    // ──────────────────────────────────────────────
                    PanelStyleManager.getInstance().applyNormalStyle(view);
                }

                return view;
            }
        };

        lvGroup.setAdapter(adapter);
        // 默认选中第一个
        selectedPosition = 0;
        focusedPosition = 0;
        adapter.notifyDataSetChanged();
    }

    /**
     * 设置选中位置，立即刷新高亮
     * 外部点击时调用这个方法
     *
     * @param position 选中位置
     *
     * 【说明】
     * 选中时会同步移动焦点到选中项，
     * 因为选中了之后，焦点也应该停在选中的项上。
     */
    public void setSelectedPosition(int position) {
        if (groupList == null || adapter == null) return;
        if (position < 0 || position >= groupList.size()) return;

        selectedPosition = position;
        // 选中时也同步移动焦点到选中项
        focusedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();

        // 回调通知外部，分组选中了
        if (listener != null) {
            listener.onGroupSelected(position, groupList.get(position));
        }
    }

    /**
     * 获取指定位置的分组名称
     *
     * @param position 位置
     * @return 分组名称
     */
    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    // ====================================================================
    // ✅ 2026-06-21 新增：资源释放
    // ====================================================================
    /**
     * 释放资源
     *
     * 【说明】
     * 页面销毁时调用，移除监听器，防止内存泄漏。
     */
    public void release() {
        PanelStyleManager.getInstance().removeOnModeChangedListener(this);
    }

    public void onBackPressed() {}
}
