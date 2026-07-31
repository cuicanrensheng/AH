package com.tv.live;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

public class SubscriptionAdapter extends ArrayAdapter<SourceManager.SourceItem> {

    private int selectedPosition = -1;
    private OnActionListener actionListener;

    // 🟢【核心修复】直接将原生的默认地址写死，避免因为 UrlConfig 被动态覆盖导致保护失效
    private static final String PROTECTED_LIVE_URL = "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u";
    private static final String PROTECTED_EPG_URL = "https://e.erw.cc/all.xml.gz";

    // 🟢 颜色常量优化，避免重复解析
    private static final int COLOR_SELECTED = 0xFF40A9FF;
    private static final int COLOR_SELECTED_BG = 0x3340A9FF;
    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_NORMAL_BG = 0x333545;

    public interface OnActionListener {
        void onSwitch(int position);
        void onDelete(int position);
    }

    public SubscriptionAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, 0, items);
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setOnActionListener(OnActionListener listener) {
        this.actionListener = listener;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_subscription_list, parent, false);
            holder = new ViewHolder();
            holder.tvCheck = convertView.findViewById(R.id.tv_check);
            holder.tvUrl = convertView.findViewById(R.id.tv_url);
            holder.btnCopy = convertView.findViewById(R.id.btn_copy);
            holder.btnDelete = convertView.findViewById(R.id.btn_delete);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        SourceManager.SourceItem item = getItem(position);
        if (item == null) return convertView;

        String displayText = item.name;
        if (item.url != null && !item.url.isEmpty()) {
            displayText += "\n" + item.url;
        } else {
            displayText += "\n(未找到链接地址)";
        }
        holder.tvUrl.setText(displayText);

        // =================================================================
        // 🛡️ 核心修复：只要匹配到硬编码的原生地址，无论如何都保护（隐藏删除按钮）
        // =================================================================
        boolean isProtected = item.url != null && !item.url.isEmpty() &&
                (item.url.equals(PROTECTED_LIVE_URL) || item.url.equals(PROTECTED_EPG_URL));

        if (isProtected) {
            holder.btnDelete.setVisibility(View.GONE);
        } else {
            holder.btnDelete.setVisibility(View.VISIBLE);
        }

        boolean isSelected = (position == selectedPosition);
        if (isSelected) {
            holder.tvCheck.setVisibility(View.VISIBLE);
            holder.tvUrl.setTextColor(COLOR_SELECTED);
            convertView.setBackgroundColor(COLOR_SELECTED_BG);
        } else {
            holder.tvCheck.setVisibility(View.GONE);
            holder.tvUrl.setTextColor(COLOR_NORMAL);
            convertView.setBackgroundColor(COLOR_NORMAL_BG);
        }

        // 视图点击/复制/删除事件绑定
        holder.btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
            android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onDelete(position);
            }
        });

        convertView.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onSwitch(position);
            }
        });

        return convertView;
    }

    private static class ViewHolder {
        TextView tvCheck;
        TextView tvUrl;
        Button btnCopy;
        Button btnDelete;
    }
}
