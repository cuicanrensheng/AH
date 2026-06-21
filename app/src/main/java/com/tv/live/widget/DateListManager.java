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
 * 【2026-06-21 新增：显示具体日期】
 * 【功能说明】
 * 日期列表显示两行：
 * - 第一行：星期几（今天/明天/后天/周一）
 * - 第二行：具体日期（6/21）
 *
 * 【说明】
 * 因为 item_date.xml 是单个 TextView，所以用换行符 \n 显示两行。
 * 如果布局是两个 TextView，可以分别设置。
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
     * 初始化日期列表（8天）
     *
     * 【2026-06-21 修改：显示具体日期】
     * 显示格式：
     * - 今天
     *   6/21
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

            // ✅ 新增：具体日期（月/日）
            int month = cal.get(Calendar.MONTH) + 1; // 月份从 0 开始
            int day = cal.get(Calendar.DAY_OF_MONTH);
            String dateStr = month + "/" + day;

            // 两行显示：星期 + 具体日期
            dateDisplayList.add(weekStr + "\n" + dateStr);

            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        SettingsActivity.logOperation("【日期列表】初始化：" + dateDisplayList);

        adapter = new ArrayAdapter<String>(context, R.layout.item_date, dateDisplayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                // 支持两行显示
                tv.setSingleLine(false);
                tv.setMaxLines(2);
                tv.setTextSize(14);
                tv.setGravity(android.view.Gravity.CENTER);

                // 三种状态样式
                if (position == selectedPosition) {
                    // 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(0x3340A9FF);
                } else if (tv.isFocused()) {
                    // 焦点状态：蓝色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                } else {
                    // 未选中状态：白色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return tv;
            }
        };
        lvDate.setAdapter(adapter);

        // 点击事件
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            adapter.notifyDataSetChanged();
            SettingsActivity.logOperation("【日期列表】👆 点击：位置" + position + "，" + dateDisplayList.get(position));
            if (listener != null) {
                SettingsActivity.logOperation("【日期列表】✅ 触发回调");
                listener.onDateSelected(position);
            } else {
                SettingsActivity.logOperation("【日期列表】❌ listener为空，未触发回调");
            }
        });
    }

    /**
     * 设置选中位置
     */
    public void setSelectedPosition(int position) {
        selectedPosition = position;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
}
