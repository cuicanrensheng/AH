package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.tv.live.R;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 日期列表管理类
 * 命名规则与 EpgManager 完全一致，确保节目单能正确匹配
 */
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
    }

    /**
     * 初始化日期列表
     * 命名规则：0=今天、1=明天、2=后天、3+=周几，与EPG数据源完全统一
     */
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
                // 修复索引：Calendar.DAY_OF_WEEK 周日=1，减1对应数组下标0
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                display = week[dayOfWeek - 1];
            }
            dates.add(display);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        adapter = new ArrayAdapter<String>(context, R.layout.item_date, dates) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(position == selectedPosition ? Color.parseColor("#40A9FF") : Color.WHITE);
                return tv;
            }
        };

        lvDate.setAdapter(adapter);
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            adapter.notifyDataSetChanged();
            if (listener != null) {
                listener.onDateSelected(position);
            }
        });
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        // 同步刷新UI，保证选中状态一致
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
}
