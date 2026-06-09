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
 * 日期列表管理器（严格对齐 Group/ChannelListManager 老版规范）
 * 职责：仅数据初始化、视图渲染、选中状态同步
 * 禁止：内部添加任何点击/焦点事件，所有交互交由 MainActivity 处理
 */
public class DateListManager {
    private final ListView lvDate;
    private final Context context;
    // 全局复用数据集合（老版规范，避免重复创建）
    private final List<String> dateData = new ArrayList<>();
    // 全局复用适配器（老版核心：构造初始化，不复建）
    private ArrayAdapter<String> adapter;
    // 选中位置（UI渲染依据）
    private int selectedPosition = 0;

    public DateListManager(Context context, ListView lvDate) {
        this.context = context;
        this.lvDate = lvDate;
        lvDate.setItemsCanFocus(true);

        // 构造器中初始化适配器（老版统一写法，只执行一次）
        adapter = new ArrayAdapter<String>(context, R.layout.item_date, dateData) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                // 选中高亮 #40A9FF，未选中白色（和老版配色一致）
                tv.setTextColor(position == selectedPosition
                        ? Color.parseColor("#40A9FF")
                        : Color.WHITE);
                return tv;
            }
        };
        lvDate.setAdapter(adapter);

        // ========= 关键：彻底移除所有内部点击/焦点监听 =========
        // 所有点击逻辑统一在 MainActivity 中设置
    }

    /**
     * 初始化7天日期数据（今天+未来6天）
     * 仅更新数据、刷新适配器，不复建View/Adapter
     */
    public void initDate() {
        dateData.clear();
        String[] weekMap = {"周天", "周一", "周二", "周三", "周四", "周五", "周六"};
        // 生成7天日期
        for (int i = 0; i < 7; i++) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, i);
            int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
            String displayName = (i == 0) ? "今天" : weekMap[dayOfWeek - 1];
            dateData.add(displayName);
        }
        // 默认选中第0项（今天）
        selectedPosition = 0;
        lvDate.setSelection(0);
        lvDate.setItemChecked(0, true);
        // 仅刷新数据（老版性能写法）
        adapter.notifyDataSetChanged();
    }

    /**
     * 外部设置选中位置（供 MainActivity 调用）
     * 修复：同步变量 + 滚动列表 + 刷新UI（对齐老版 setSelectedPosition）
     */
    public void setSelectedPosition(int position) {
        // 边界防护，防止越界
        if (position < 0 || position >= dateData.size()) {
            return;
        }
        this.selectedPosition = position;
        lvDate.setSelection(position);
        lvDate.setItemChecked(position, true);
        // 刷新适配器，更新选中高亮（修复UI不同步）
        adapter.notifyDataSetChanged();
    }
}
