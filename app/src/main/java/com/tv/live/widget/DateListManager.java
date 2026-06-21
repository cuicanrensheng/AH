package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.SettingsActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 日期列表管理器
 *
 * 【2026-06-21 修改：统一三种状态高亮样式（焦点优先）】
 * 【样式规则】
 * 焦点 > 选中 > 普通
 * - 焦点：白色文字 + 蓝色背景（最显眼，遥控器停在哪里）
 * - 选中：蓝色文字 + 透明背景（当前选中的日期）
 * - 普通：白色文字 + 透明背景
 */
public class DateListManager {
    private Context context;
    private ListView listView;
    private List<DateItem> dateList = new ArrayList<>();
    private DateAdapter adapter;

    // ====================================================================
    // ✅ 新增：焦点位置和选中位置分开记录
    // ====================================================================
    /** 当前焦点位置（遥控器移动到的位置） */
    private int focusedPosition = 0;
    /** 当前选中位置（点击选中的日期） */
    private int selectedPosition = 0;

    private OnDateSelectedListener listener;

    public interface OnDateSelectedListener {
        void onDateSelected(int position, String date);
    }

    public static class DateItem {
        public String weekDay;
        public String date;

        public DateItem(String weekDay, String date) {
            this.weekDay = weekDay;
            this.date = date;
        }
    }

    public DateListManager(Context context, ListView listView) {
        this.context = context;
        this.listView = listView;
        adapter = new DateAdapter();
        listView.setAdapter(adapter);
        initListeners();
    }

    private void initListeners() {
        // 点击事件：点击才真正选中
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                SettingsActivity.logOperation("【日期】点击选中：" + position + " - " + dateList.get(position).date);
                setSelectedPosition(position);
                if (listener != null) {
                    listener.onDateSelected(position, dateList.get(position).date);
                }
            }
        });

        // 选中事件：只移动焦点，不选中
        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SettingsActivity.logOperation("【日期】焦点移动：" + position);
                // ✅ 只更新焦点位置，不更新选中位置
                setFocusedPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做处理
            }
        });
    }

    // ====================================================================
    // ✅ 新增：设置焦点位置
    // ====================================================================
    public void setFocusedPosition(int position) {
        if (position < 0 || position >= dateList.size()) return;
        this.focusedPosition = position;
        adapter.notifyDataSetChanged();
    }

    public int getFocusedPosition() {
        return focusedPosition;
    }

    public void setSelectedPosition(int position) {
        if (position < 0 || position >= dateList.size()) return;
        this.selectedPosition = position;
        // 选中时也同步移动焦点到选中项
        this.focusedPosition = position;
        adapter.notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void initDate() {
        dateList.clear();
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat weekFormat = new SimpleDateFormat("EEEE", Locale.CHINA);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd", Locale.CHINA);

        String[] weekDays = {"今天", "明天", "后天", "周四", "周五", "周六", "周日", "周一"};

        for (int i = 0; i < 8; i++) {
            String weekDay;
            if (i < 3) {
                weekDay = weekDays[i];
            } else {
                weekDay = weekFormat.format(calendar.getTime());
            }
            String date = dateFormat.format(calendar.getTime());
            dateList.add(new DateItem(weekDay, date));
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }

        // 默认选中今天（第0个）
        this.selectedPosition = 0;
        this.focusedPosition = 0;
        adapter.notifyDataSetChanged();
    }

    public void setOnDateSelectedListener(OnDateSelectedListener listener) {
        this.listener = listener;
    }

    // ====================================================================
    // 适配器
    // ====================================================================
    private class DateAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return dateList.size();
        }

        @Override
        public Object getItem(int position) {
            return dateList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView textView;
            if (convertView == null) {
                textView = new TextView(context);
                textView.setPadding(30, 20, 30, 20);
                textView.setTextSize(16);
                // 去掉系统默认焦点高亮
                textView.setDefaultFocusHighlightEnabled(false);
            } else {
                textView = (TextView) convertView;
            }

            DateItem item = dateList.get(position);
            String displayText = item.weekDay + "\n" + item.date;
            textView.setText(displayText);
            textView.setLineSpacing(0, 1.2f);

            // ====================================================================
            // ✅ 修改：统一三种状态样式（焦点优先）
            // ====================================================================
            // 判断优先级：焦点 > 选中 > 普通
            if (position == focusedPosition) {
                // ── 焦点状态：白色文字 + 蓝色背景（最显眼）──
                textView.setTextColor(Color.WHITE);
                textView.setTypeface(Typeface.DEFAULT);
                textView.setBackgroundColor(Color.parseColor("#40A9FF"));
            } else if (position == selectedPosition) {
                // ── 选中状态：蓝色文字 + 透明背景（次之）──
                textView.setTextColor(Color.parseColor("#40A9FF"));
                textView.setTypeface(Typeface.DEFAULT);
                textView.setBackgroundColor(Color.TRANSPARENT);
            } else {
                // ── 普通状态：白色文字 + 透明背景 ──
                textView.setTextColor(Color.WHITE);
                textView.setTypeface(Typeface.DEFAULT);
                textView.setBackgroundColor(Color.TRANSPARENT);
            }

            return textView;
        }
    }
}
