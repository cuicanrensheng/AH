package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class DateListManager {
    private final ListView lvDate;
    private int selectedPosition = 0;

    public DateListManager(Context context, ListView lvDate) {
        this.lvDate = lvDate;
        lvDate.setItemsCanFocus(true);

        lvDate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                parent.invalidateViews();
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
            if (i == 0) dates.add("今天");
            else dates.add(week[cal.get(Calendar.DAY_OF_WEEK) % 7]);
            cal.add(Calendar.DAY_OF_YEAR, 1);
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
