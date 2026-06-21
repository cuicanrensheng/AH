package com.tv.live.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.R;
import com.tv.live.SettingsActivity;
import com.tv.live.manager.PanelStyleManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 日期列表管理器
 *
 * 【职责】
 * 统一管理日期列表的显示、选中状态、点击事件等。
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
 *    - 选中：蓝色文字 + 透明背景（次之，当前选中的日期）
 *    - 普通：白色文字 + 透明背景
 *
 * 2. 触屏模式：
 *    - 选中：白色文字 + 深蓝色背景（明显的选中效果）
 *    - 普通：白色文字 + 透明背景
 */
public class DateListManager implements PanelStyleManager.OnModeChangedListener {
    /** 日期列表 ListView */
    private final ListView lvDate;
    /** 上下文 */
    private final Context context;
    /** 当前选中位置（点击选中的日期） */
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

    /** 日期选中监听器 */
    private OnDateSelectedListener listener;
    /** 列表适配器 */
    private ArrayAdapter<String> adapter;

    /**
     * 日期选中监听器接口
     */
    public interface OnDateSelectedListener {
        void onDateSelected(int position);
    }

    public void setOnDateSelectedListener(OnDateSelectedListener listener) {
        this.listener = listener;
    }

    /**
     * 构造函数
     *
     * @param context 上下文
     * @param lvDate 日期列表 ListView
     */
    public DateListManager(Context context, ListView lvDate) {
        this.context = context;
        this.lvDate = lvDate;

        // item 不需要获取焦点，由 ListView 统一管理
        lvDate.setItemsCanFocus(false);
        lvDate.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // ================================================================
        // ✅ 遥控器焦点移动时只更新 focusedPosition，不更新 selectedPosition
        // ================================================================
        lvDate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
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
     */
    public void setFocusedPosition(int position) {
        this.focusedPosition = position;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
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

    /**
     * 初始化日期列表（8天）
     */
    public void initDate() {
        List<String> dates = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        for (int i = 0; i < 8; i++) {
            String display;
            if (i == 0) {
                display = "今天";
            } else if (i == 1) {
                display = "明天";
            } else if (i == 2) {
                display = "后天";
            } else {
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                display = week[dayOfWeek - 1];
            }
            dates.add(display);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        SettingsActivity.log("【日期列表】初始化：" + dates);

        adapter = new ArrayAdapter<String>(context, R.layout.item_date, dates) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);

                // ====================================================================
                // ✅ 2026-06-21 修改：统一调用 PanelStyleManager 应用样式
                // ====================================================================
                // 【判断优先级】焦点 > 选中 > 普通
                if (position == focusedPosition) {
                    // ── 焦点状态 ──
                    PanelStyleManager.getInstance().applyFocusStyle(tv);
                } else if (position == selectedPosition) {
                    // ── 选中状态 ──
                    PanelStyleManager.getInstance().applySelectedStyle(tv);
                } else {
                    // ── 普通状态 ──
                    PanelStyleManager.getInstance().applyNormalStyle(tv);
                }

                return tv;
            }
        };

        lvDate.setAdapter(adapter);

        // 点击事件：点击才真正选中
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            setSelectedPosition(position);
            SettingsActivity.log("【日期列表】👆 点击：位置" + position + "，" + dates.get(position));
            if (listener != null) {
                SettingsActivity.log("【日期列表】✅ 触发回调");
                listener.onDateSelected(position);
            } else {
                SettingsActivity.log("【日期列表】❌ listener为空，未触发回调");
            }
        });
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
        if (adapter != null) {
            adapter.notifyDataSetChanged();
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
}
