package com.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

public class ChannelListDialog extends Dialog {
    private ListView listView;
    private List<String> channelNames;
    private OnChannelSelectListener listener;

    public interface OnChannelSelectListener {
        void onSelect(int index);
    }

    public ChannelListDialog(Context context, List<String> names, OnChannelSelectListener l) {
        super(context, android.R.style.Theme_NoTitleBar);
        channelNames = names;
        listener = l;
        initView();
    }

    private void initView() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_channel_list, null);
        setContentView(view);
        listView = view.findViewById(R.id.lv_channel);

        ChannelAdapter adapter = new ChannelAdapter(getContext(), channelNames);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view1, position, id) -> {
            if (listener != null) {
                listener.onSelect(position);
            }
            dismiss();
        });
    }

    // 🟢 核心优化：引入 ViewHolder 模式
    private class ChannelAdapter extends ArrayAdapter<String> {
        public ChannelAdapter(Context context, List<String> list) {
            super(context, R.layout.item_channel, list);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                // 1. 只有第一次创建时才 Inflate 布局
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_channel, parent, false);
                holder = new ViewHolder();
                holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                convertView.setTag(holder); // 🟢 把 Holder 存入 Tag
            } else {
                // 2. 后续直接复用，不再执行 findViewById 和 Inflate
                holder = (ViewHolder) convertView.getTag();
            }

            // 3. 更新数据
            holder.tvChannel.setText(getItem(position));

            return convertView;
        }

        // 🟢 修复：去掉 static，改为普通内部类，解决编译报错
        private class ViewHolder {
            TextView tvChannel;
        }
    }
}
