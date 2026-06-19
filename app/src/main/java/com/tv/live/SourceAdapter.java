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
 *
 * 【显示内容】
 * - 左侧：序号（tv_index）
 * - 中间：源名称 + URL（tv_setting_item）
 * - 右侧：删除按钮（btn_delete）
 *
 * 【三种状态】
 * 1. 选中状态：蓝色文字 + 浅蓝色背景
 * 2. 焦点状态：蓝色文字 + 稍深蓝色背景
 * 3. 未选中状态：白色文字 + 透明背景
 *
 * 【为什么拆分？】
 * 适配器类通常独立成文件，这是 Android 开发惯例。
 * 方便其他地方复用这个适配器，也方便维护。
 */
public class SourceAdapter extends ArrayAdapter<SourceManager.SourceItem> {

    // ====================== 成员变量 ======================
    /** 上下文 */
    private final Context context;
    /** 数据列表 */
    private final List<SourceManager.SourceItem> items;
    /** 当前选中的位置 */
    private int selectedPosition = -1;
    /** 删除按钮点击回调 */
    private OnDeleteClickListener onDeleteClickListener;

    // ====================== 回调接口 ======================
    /**
     * 删除按钮点击回调接口
     */
    public interface OnDeleteClickListener {
        void onDelete(int position);
    }

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     * @param context 上下文
     * @param items 数据列表
     */
    public SourceAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, R.layout.item_settings, items);
        this.context = context;
        this.items = items;
    }

    // ====================== 公开方法 ======================
    /**
     * 设置删除按钮点击监听器
     */
    public void setOnDeleteClickListener(OnDeleteClickListener listener) {
        this.onDeleteClickListener = listener;
    }

    /**
     * 设置选中位置
     */
    public void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    /**
     * 获取选中位置
     */
    public int getSelectedPosition() {
        return selectedPosition;
    }

    // ====================== getView 核心方法 ======================
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_settings, parent, false);
        }

        SourceManager.SourceItem item = items.get(position);

        TextView tv = convertView.findViewById(R.id.tv_setting_item);
        TextView indexTv = convertView.findViewById(R.id.tv_index);
        Button deleteBtn = convertView.findViewById(R.id.btn_delete);

        // ===== 序号显示 =====
        indexTv.setText((position + 1) + ". ");
        indexTv.setTextSize(13);

        // ===== 构建显示文本：名称 + URL（两行） =====
        StringBuilder displayText = new StringBuilder();
        displayText.append(item.name);

        // 默认源标记
        if (item.isDefault) {
            displayText.append("  ⭐");
        }

        // 第二行：URL
        displayText.append("\n");
        displayText.append(item.url);

        // 自动更新状态
        if (!item.autoUpdate) {
            displayText.append("  🔕");
        }

        tv.setText(displayText.toString());
        tv.setTextSize(14);
        tv.setLineSpacing(4, 1);
        tv.setSingleLine(false);  // 允许两行显示
        tv.setEllipsize(null);    // 去掉省略号，因为要显示两行

        // ===== 删除按钮点击事件 =====
        final int pos = position;
        deleteBtn.setOnClickListener(v -> {
            if (onDeleteClickListener != null) {
                onDeleteClickListener.onDelete(pos);
            }
        });

        // 确保按钮可点击（防止布局里设为 false）
        deleteBtn.setClickable(true);
        deleteBtn.setFocusable(false);  // 不抢焦点，不影响列表项选中

        // ===== 选中/焦点/未选中 三种状态 =====
        if (position == selectedPosition) {
            // ✅ 选中状态：蓝色文字 + 浅蓝色背景
            tv.setTextColor(Color.parseColor("#40A9FF"));
            indexTv.setTextColor(Color.parseColor("#40A9FF"));
            convertView.setBackgroundColor(0x3340A9FF);
        } else if (convertView.isFocused()) {
            // ✅ 焦点状态：蓝色文字 + 稍深蓝色背景
            tv.setTextColor(Color.parseColor("#40A9FF"));
            indexTv.setTextColor(Color.parseColor("#40A9FF"));
            convertView.setBackgroundColor(0x4440A9FF);
        } else {
            // ✅ 未选中状态：白色文字 + 透明背景
            tv.setTextColor(Color.WHITE);
            indexTv.setTextColor(Color.WHITE);
            convertView.setBackgroundColor(Color.TRANSPARENT);
        }

        return convertView;
    }

}
