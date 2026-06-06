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

public class DateListManager {
    private final ListView lvDate;
    private final Context context;
    private int selectedPosition = 0;
    private OnDateSelectedListener listener;
    private ArrayAdapter<String> dateAdapter;

    public interface OnDateSelectedListener {
        void onDateSelected(int position);
    }

    public void setOnDateSelectedListener(OnDateSelectedListener listener) {
        this.listener = listener;
    }

    public DateListManager(Context context, ListView lvDate) {
        this.context = context;
        this.lvDate = lvDate;
    }

    public void initDate() {
        List<String> dates = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        for (int i = 0; i < 8; i++) {
            int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
            String display = i == 0 ? "今天" : week[dayOfWeek % 7];
            dates.add(display);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        // ===================== 【编译修复】替换为系统自带布局，解决 R 不存在错误 =====================
        dateAdapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, dates) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(position == selectedPosition ? Color.parseColor("#40A9FF") : Color.WHITE);
                return tv;
            }
        };
        lvDate.setAdapter(dateAdapter);
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            dateAdapter.notifyDataSetChanged();
            if (listener != null) {
                listener.onDateSelected(position);
            }
        });
    }

    public int getSelectedPosition(){
        return selectedPosition;
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        if(dateAdapter != null){
            dateAdapter.notifyDataSetChanged();
        }
    }
}
