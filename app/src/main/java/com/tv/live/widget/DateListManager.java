package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class DateListManager {
    private final ListView lvDate;
    private final Context context;
    private int selectedPosition = 0;

    public DateListManager(Context context, ListView lvDate) {
        this.context = context;
        this.lvDate = lvDate;
    }

    public void initDate() {
        List<String> dates = new ArrayList<>();
        dates.add("今天");
        dates.add("周一");
        dates.add("周二");
        dates.add("周三");
        dates.add("周四");
        dates.add("周五");
        dates.add("周六");
        dates.add("周日");

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

    // 设置选中位置
    public void setSelectedPosition(int position) {
        this.selectedPosition = position;
        if (lvDate.getAdapter() != null) {
            ((ArrayAdapter<?>) lvDate.getAdapter()).notifyDataSetChanged();
        }
    }

    public void onBackPressed() {}
}
