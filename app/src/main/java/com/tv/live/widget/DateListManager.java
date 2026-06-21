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

import com.tv.live.R;
import com.tv.live.SettingsActivity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 日期列表管理器
 *
 * 【职责】
 * 统一管理日期列表的显示、选中状态、点击事件等。
 *
 * 【2026-06-21 优化：焦点优先样式 + 区分焦点和选中状态】
 *
 * 【三种状态说明】
 * 1. 焦点状态：白色文字 + 蓝色背景（遥控器焦点所在的项，最显眼）
 * 2. 选中状态：蓝色文字 + 透明背景（当前选中的日期）
 * 3. 未选中状态：白色文字 + 透明背景（普通项）
 *
 * 【判断优先级】
 * 焦点状态 > 选中状态 > 未选中状态
 */
public class DateListManager {
    /** 日期列表 ListView */
    private final ListView lvDate;
    /** 上下文 */
    private final Context context;
    /** 当前选中位置（点击选中的日期） */
    private int selectedPosition = 0;
    // ====================================================================
    // ✅ 新增：焦点位置变量
    // ====================================================================
    /** 当前焦点位置（遥控器移动到的位置） */
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
        // ✅ 修改：遥控器焦点移动时只更新 focusedPosition，不更新 selectedPosition
        // ================================================================
        lvDate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
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
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * 获取当前焦点位置
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
                // ✅ 2026-06-21 修改：统一三种状态样式（焦点优先）
                // ====================================================================
                // 判断优先级：焦点 > 选中 > 普通
                if (position == focusedPosition) {
                    // ── 焦点状态：白色文字 + 蓝色背景（最显眼）──
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.parseColor("#40A9FF"));
                } else if (position == selectedPosition) {
                    // ── 选中状态：蓝色文字 + 透明背景（次之）──
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                } else {
                    // ── 普通状态：白色文字 + 透明背景 ──
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
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
     */
    public void setSelectedPosition(int position) {
        selectedPosition = position;
        // 选中时也同步移动焦点到选中项
        focusedPosition = position;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
}
