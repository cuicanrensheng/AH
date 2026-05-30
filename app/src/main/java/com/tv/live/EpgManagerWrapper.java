package com.tv.live.widget;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EpgManagerWrapper {
    private final Context context;
    private final ListView listView;
    private EpgAdapter adapter;
    private final Set<Long> bookedPrograms = new HashSet<>();

    public EpgManagerWrapper(Context context, ListView listView) {
        this.context = context;
        this.listView = listView;
        adapter = new EpgAdapter(context, new ArrayList<>());
        listView.setAdapter(adapter);
    }

    public void refresh(Channel currentChannel, List<Channel> channelSourceList) {
        if (currentChannel == null) {
            showEmpty();
            return;
        }
        new Thread(() -> {
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(currentChannel.getName());
            if (epgList == null) epgList = new ArrayList<>();

            Collections.sort(epgList, Comparator.comparingLong(o -> o.startTime));

            List<EpgDisplayItem> items = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Channel.EpgItem e : epgList) {
                EpgDisplayItem item = new EpgDisplayItem();
                item.time = e.time;
                item.title = e.title;
                item.startTime = e.startTime;
                item.playUrl = currentChannel.getPlayUrl();
                item.isPast = e.startTime < now;
                item.isFuture = e.startTime > now;
                items.add(item);
            }
            listView.post(() -> {
                adapter.clear();
                adapter.addAll(items);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void showEmpty() {
        List<EpgDisplayItem> empty = new ArrayList<>();
        EpgDisplayItem item = new EpgDisplayItem();
        item.title = "暂无节目单";
        empty.add(item);
        listView.post(() -> {
            adapter.clear();
            adapter.addAll(empty);
            adapter.notifyDataSetChanged();
        });
    }

    public static class EpgDisplayItem {
        public String time;
        public String title;
        public long startTime;
        public String playUrl;
        public boolean isPast;
        public boolean isFuture;
    }

    private class EpgAdapter extends ArrayAdapter<EpgDisplayItem> {
        private final LayoutInflater inflater;

        public EpgAdapter(Context ctx, List<EpgDisplayItem> list) {
            super(ctx, R.layout.item_epg, list);
            inflater = LayoutInflater.from(ctx);
        }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_epg, parent, false);
                holder = new ViewHolder();
                holder.tvTime = convertView.findViewById(R.id.tv_epg_time);
                holder.tvTitle = convertView.findViewById(R.id.tv_epg_title);
                holder.btnAction = convertView.findViewById(R.id.btn_epg_action);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            EpgDisplayItem item = getItem(pos);
            if (item == null) return convertView;

            holder.tvTime.setText(item.time);
            holder.tvTitle.setText(item.title);
            holder.btnAction.setOnClickListener(null);
            holder.btnAction.setVisibility(View.VISIBLE);

            if (item.isPast) {
                holder.btnAction.setText("回看");
                holder.btnAction.setOnClickListener(v -> {
                    ((MainActivity) context).mPlayerManager.play(item.playUrl);
                    Toast.makeText(context, "正在回看：" + item.title, Toast.LENGTH_SHORT).show();
                });
            } else if (item.isFuture) {
                if (bookedPrograms.contains(item.startTime)) {
                    holder.btnAction.setText("已预约");
                    holder.btnAction.setEnabled(false);
                } else {
                    holder.btnAction.setText("预约");
                    holder.btnAction.setEnabled(true);
                    holder.btnAction.setOnClickListener(v -> {
                        bookedPrograms.add(item.startTime);
                        notifyDataSetChanged();
                        Toast.makeText(context, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                    });
                }
            } else {
                holder.btnAction.setVisibility(View.GONE);
            }
            return convertView;
        }

        class ViewHolder {
            TextView tvTime;
            TextView tvTitle;
            Button btnAction;
        }
    }
}
