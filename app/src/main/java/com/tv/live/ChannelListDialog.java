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
            listener.onSelect(position);
            dismiss();
        });
    }

    private class ChannelAdapter extends ArrayAdapter<String> {
        public ChannelAdapter(Context context, List<String> list) {
            super(context, R.layout.item_channel, list);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = LayoutInflater.from(getContext()).inflate(R.layout.item_channel, parent, false);
            TextView tv = v.findViewById(R.id.tv_channel);
            tv.setText(getItem(position));
            return v;
        }
    }
}
