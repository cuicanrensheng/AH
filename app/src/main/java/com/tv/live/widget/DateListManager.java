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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 日期列表管理器
 *
 * 【2026-06-21 新增：显示具体日期】
 * 【功能说明】
 * 日期列表显示两行：
 * - 第一行：星期几（今天/明天/后天/周一）
 * - 第二行：具体日期（6/21）
 */
public class DateListManager {
    /** 日期列表 ListView */
    private final ListView lvDate;
    /** 上下文 */
    private final Context context;
    /** 当前选中位置 */
    private int selectedPosition = 0;
    /** 日期选中监听器 */
    private OnDateSelectedListener listener;
    /** 列表适配器 */
    private ArrayAdapter<String> adapter;
    /** 显示的日期文本列表 */
    private List<String> dateDisplayList;

    // 🟢【优化1】预定义颜色常量，彻底避免 Color.parseColor 重复解析
    private static final int COLOR_BLUE = 0xFF40A9FF;
    private static final int COLOR_BG_BLUE = 0x3340A9FF;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    /**
     * 当前列表是否有焦点
     * - true = 当前光标在这个列表上，选中项用浅蓝色背景 + 蓝色文字 + 加粗
     * - false = 当前光标不在这个列表上，选中项用蓝色文字 + 透明背景
     */
    private boolean hasFocus = false;

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
     */
    public DateListManager(Context context, ListView lvDate) {
        this.context = context;
        this.lvDate = lvDate;
        lvDate.setItemsCanFocus(false);
        lvDate.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // 遥控器焦点选中时同步更新位置
        lvDate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
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
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * 获取当前是否有焦点
     */
    public boolean isFocused() {
        return hasFocus;
    }

    /**
     * 初始化日期列表（8天）
     */
    public void initDate() {
        dateDisplayList = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        for (int i = 0; i < 8; i++) {
            String weekStr;
            if (i == 0) {
                weekStr = "今天";
            } else if (i == 1) {
                weekStr = "明天";
            } else if (i == 2) {
                weekStr = "后天";
            } else {
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                weekStr = week[dayOfWeek - 1];
            }

            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DAY_OF_MONTH);
            String dateStr = month + "/" + day;

            dateDisplayList.add(weekStr + "\n" + dateStr);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        adapter = new ArrayAdapter<String>(context, R.layout.item_date, dateDisplayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);

                tv.setSingleLine(false);
                tv.setMaxLines(2);
                tv.setTextSize(14);
                tv.setGravity(android.view.Gravity.CENTER);

                // 🟢 直接使用预定义颜色常量
                if (position == selectedPosition) {
                    if (hasFocus) {
                        tv.setTextColor(COLOR_BLUE);
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(COLOR_BG_BLUE);
                    } else {
                        tv.setTextColor(COLOR_BLUE);
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(Color.TRANSPARENT);
                    }
                } else {
                    tv.setTextColor(COLOR_WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return tv;
            }
        };

        lvDate.setAdapter(adapter);

        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            adapter.notifyDataSetChanged();
            if (listener != null) {
                listener.onDateSelected(position);
            }
        });
    }

    /**
     * 设置选中位置
     */
    public void setSelectedPosition(int position) {
        if (dateDisplayList == null || adapter == null) return;
        if (position < 0 || position >= dateDisplayList.size()) return;
        
        // 🟢【优化2】如果已经是选中位置，直接跳过，防止全量无效刷新
        if (this.selectedPosition == position) return;

        selectedPosition = position;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
}
