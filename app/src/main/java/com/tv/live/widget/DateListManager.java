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

public class DateListManager {
    private final ListView lvDate;
    private final Context context;
    private int selectedPosition = 0;
    private OnDateSelectedListener listener;

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

    // ✅ 已修改为要求的日期格式：今天、周一、周二、周三、周四、周五、周六、周天
    public void initDate() {
        List<String> dates = new ArrayList<>();
        // 星期映射表：Calendar.DAY_OF_WEEK 1=周天，2=周一...7=周六
        String[] weekMap = {"周天", "周一", "周二", "周三", "周四", "周五", "周六"};

        // 生成7天日期（今天 + 未来6天）
        for (int i = 0; i < 7; i++) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, i);
            int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);

            String displayName;
            if (i == 0) {
                displayName = "今天";
            } else {
                // 修复索引错误：dayOfWeek-1 对应数组正确位置
                displayName = weekMap[dayOfWeek - 1];
            }
            dates.add(displayName);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, R.layout.item_date, dates) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                // 选中项高亮蓝色，未选中白色
                tv.setTextColor(position == selectedPosition ? Color.parseColor("#40A9FF") : Color.WHITE);
                return tv;
            }
        };

        lvDate.setAdapter(adapter);
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            adapter.notifyDataSetChanged();
            // 回调日期选中事件到MainActivity
            if (listener != null) {
                listener.onDateSelected(position);
            }
        });

        // 默认选中今天
        lvDate.setItemChecked(0, true);
        lvDate.setSelection(0);
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
    }
}
