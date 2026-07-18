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

public class DateListManager {
    private final ListView lvDate;
    private final Context context;
    private int selectedPosition = 0;
    private OnDateSelectedListener listener;
    private ArrayAdapter<String> adapter;

    public interface OnDateSelectedListener {
        void onDateSelected(int position);
    }

    public void setOnDateSelectedListener(OnDateSelectedListener listener) {
        this.listener = listener;
    }

    public DateListManager(Context context, ListView lvDate) {
        this.context = context;
        this.lvDate = lvDate;
        // ✅ item 不需要获取焦点
        lvDate.setItemsCanFocus(false);
        lvDate.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // ✅ 遥控器焦点选中时同步更新位置
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

                if (position == selectedPosition) {
                    // ✅ 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(0x3340A9FF);
                } else if (tv.isFocused()) {
                    // ✅ 焦点状态：蓝色文字 + 稍深一点的蓝色背景
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(0x4440A9FF);
                } else {
                    // ✅ 未选中状态：白色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.WHITE);
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
            SettingsActivity.log("【日期列表】👆 点击：位置" + position + "，" + dates.get(position));
            if (listener != null) {
                SettingsActivity.log("【日期列表】✅ 触发回调");
                listener.onDateSelected(position);
            } else {
                SettingsActivity.log("【日期列表】❌ listener为空，未触发回调");
            }
        });
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
}
