package com.tv.live;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

/**
 * 多源列表适配器
 */
public class SourceAdapter extends ArrayAdapter<SourceManager.SourceItem> {
    private final Context context;
    private int selectedPosition = -1;
    private OnDeleteClickListener onDeleteClickListener;

    // 🟢 优化：将颜色提取为常量，避免在 getView 中反复解析字符串
    private static final int COLOR_FOCUS = Color.parseColor("#40A9FF");
    private static final int COLOR_FOCUS_BG = 0x3340A9FF;
    private static final int COLOR_HOVER_BG = 0x4440A9FF;

    public interface OnDeleteClickListener {
        void onDelete(int position);
    }

    public SourceAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, R.layout.item_settings, items);
        this.context = context;
    }

    public void setOnDeleteClickListener(OnDeleteClickListener listener) {
        this.onDeleteClickListener = listener;
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        // 🟢【核心修复】引入 ViewHolder 模式，彻底消除重复 findViewById
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_settings, parent, false);
            holder = new ViewHolder();
            holder.tv = convertView.findViewById(R.id.tv_setting_item);
            holder.indexTv = convertView.findViewById(R.id.tv_index);
            holder.deleteBtn = convertView.findViewById(R.id.btn_delete);

            // 🟢 这些样式属性只需在创建时设置一次
            holder.tv.setTextSize(14);
            holder.tv.setLineSpacing(4, 1);
            holder.tv.setSingleLine(false);
            holder.tv.setEllipsize(null);

            // 绑定通用的删除按钮点击事件
            holder.deleteBtn.setOnClickListener(v -> {
                if (onDeleteClickListener != null) {
                    // 通过 Tag 获取当前绑定的位置
                    Object tag = v.getTag();
                    if (tag instanceof Integer) {
                        onDeleteClickListener.onDelete((Integer) tag);
                    }
                }
            });

            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        SourceManager.SourceItem item = getItem(position);
        // 🟢 优化：增加空指针保护，防止在列表数据刷新时偶发崩溃
        if (item == null) {
            return convertView;
        }

        holder.indexTv.setText((position + 1) + ". ");

        StringBuilder displayText = new StringBuilder();
        displayText.append(item.name);
        if (item.isDefault) displayText.append("  ⭐");
        displayText.append("\n").append(item.url);
        if (!item.autoUpdate) displayText.append("  🔕");

        holder.tv.setText(displayText.toString());
        // 🟢 将当前行的位置存入删除按钮的 Tag，供点击事件回调使用
        holder.deleteBtn.setTag(position);
        holder.deleteBtn.setClickable(true);
        holder.deleteBtn.setFocusable(false);

        // 设置选中/高亮/普通三种状态的样式
        if (position == selectedPosition) {
            holder.tv.setTextColor(COLOR_FOCUS);
            holder.indexTv.setTextColor(COLOR_FOCUS);
            convertView.setBackgroundColor(COLOR_FOCUS_BG);
        } else if (convertView.isFocused()) {
            holder.tv.setTextColor(COLOR_FOCUS);
            holder.indexTv.setTextColor(COLOR_FOCUS);
            convertView.setBackgroundColor(COLOR_HOVER_BG);
        } else {
            holder.tv.setTextColor(Color.WHITE);
            holder.indexTv.setTextColor(Color.WHITE);
            convertView.setBackgroundColor(Color.TRANSPARENT);
        }

        return convertView;
    }

    // 🟢 静态内部类 ViewHolder，不持有外部适配器引用，防止内存泄漏
    private static class ViewHolder {
        TextView tv;
        TextView indexTv;
        Button deleteBtn;
    }
}
