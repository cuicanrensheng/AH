package com.tv.live.widget;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 日期列表管理器（今天、周一、周二...）
 * 功能：日期选中项变为蓝色
 */
public class DateListManager {
    private final ListView lvDate;
    private final Context context;
    private int selectedPosition = 0;

    public DateListManager(Context context, ListView lvDate) {
        this.context = context;
        this.lvDate = lvDate;
    }

    /**
     * 初始化日期：今天 + 未来7天正确星期
     */
    public void initDate() {
        List<String> dates = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int todayWeek = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekName = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        dates.add("今天");
        for (int i = 1; i <= 7; i++) {
            int idx = (todayWeek + i - 1) % 7;
            dates.add(weekName[idx]);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, dates) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);

                // 选中蓝色，未选中白色
                if (position == selectedPosition) {
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                } else {
                    tv.setTextColor(Color.WHITE);
                }
                return view;
            }
        };

        lvDate.setAdapter(adapter);
    }

    /**
     * 设置选中日期
     */
    public void setSelectedPosition(int position) {
        this.selectedPosition = position;
        if (lvDate.getAdapter() != null) {
            ((ArrayAdapter<?>) lvDate.getAdapter()).notifyDataSetChanged();
        }
    }

    public void onBackPressed() {}
}
