package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.tv.live.MainActivity;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class DateListManager {
    private final ListView lvDate;
    private final Context context;
    private int selectedPosition = 0;
    private EpgManagerWrapper epgManagerWrapper;

    public DateListManager(Context context, ListView lvDate) {
        this.context = context;
        this.lvDate = lvDate;
        lvDate.setItemsCanFocus(true);

        lvDate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();

                // ========== 修复：日期切换 → 自动刷新 EPG ==========
                if (epgManagerWrapper != null && ((MainActivity) context).channelSourceList != null) {
                    int currentChannelIndex = ((MainActivity) context).currentPlayIndex;
                    epgManagerWrapper.refresh(
                            ((MainActivity) context).channelSourceList.get(currentChannelIndex),
                            ((MainActivity) context).channelSourceList,
                            pos
                    );
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // 绑定 EPG，让日期可以控制节目单
    public void setEpgManagerWrapper(EpgManagerWrapper wrapper) {
        this.epgManagerWrapper = wrapper;
    }

    public void initDate() {
        List<String> dates = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        for (int i = 0; i < 8; i++) {
            int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
            if (i == 0) {
                dates.add("今天");
            } else {
                dates.add(week[dayOfWeek % 7]);
            }
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvDate.getContext(), android.R.layout.simple_list_item_1, dates) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(14);
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

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        lvDate.setSelection(position);
    }

    public void onBackPressed() {}
}
