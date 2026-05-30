package com.tv.live.widget;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.List;

public class DateListManager {
    private final ListView lvDate;
    private final Context context;

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

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_list_item_1, dates);
        lvDate.setAdapter(adapter);
    }
}
