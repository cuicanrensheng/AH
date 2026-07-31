package com.tv.live;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import java.util.List;

public class EpgAdapter extends BaseAdapter {
    private Context ctx;
    private List<EpgProgram> list;

    public EpgAdapter(Context ctx, List<EpgProgram> list) {
        this.ctx = ctx;
        this.list = list;
    }

    @Override
    public int getCount() { return list.size(); }

    @Override
    public Object getItem(int position) { return list.get(position); }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        // 🟢 1. 只有第一次创建视图时才 inflate 并绑定 id
        if (convertView == null) {
            convertView = LayoutInflater.from(ctx).inflate(R.layout.item_epg, parent, false);
            holder = new ViewHolder();
            holder.tvTime = convertView.findViewById(R.id.tv_time);
            holder.tvTitle = convertView.findViewById(R.id.tv_title);
            convertView.setTag(holder);
        } else {
            // 2. 复用视图时，直接从 Tag 拿出缓存的 ViewHolder
            holder = (ViewHolder) convertView.getTag();
        }

        // 3. 绑定数据
        EpgProgram p = list.get(position);
        holder.tvTime.setText(p.getTime());
        holder.tvTitle.setText(p.getTitle());

        return convertView;
    }

    // 🟢 静态内部类，避免内存泄漏，同时避免频繁 findViewById
    private static class ViewHolder {
        TextView tvTime;
        TextView tvTitle;
    }
}
