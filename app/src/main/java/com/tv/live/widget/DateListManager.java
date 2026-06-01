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
 * 日期列表管理器（今天、周一～周日）
 * 功能：TV遥控器滑动选中项自动变蓝，日期切换可正常显示节目单
 */
public class DateListManager {
    private final ListView lvDate;
    private int selectedPosition = 0;

    public DateListManager(Context context, ListView lvDate) {
        this.lvDate = lvDate;
        lvDate.setItemsCanFocus(true);
        lvDate.setOnItemSelectedListener((parent, view, pos, id) -> {
            selectedPosition = pos;
            parent.invalidateViews();
        });
    }

    /**
     * 初始化日期列表：今天 + 未来7天，自动对应星期几
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

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvDate.getContext(), android.R.layout.simple_list_item_1, dates) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                if (position == selectedPosition || view.isFocused()) {
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                } else {
                    tv.setTextColor(Color.WHITE);
                }
                return view;
            }
        };
        lvDate.setAdapter(adapter);
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        lvDate.setSelection(position);
        lvDate.invalidateViews();
    }

    public void onBackPressed() {}
}
