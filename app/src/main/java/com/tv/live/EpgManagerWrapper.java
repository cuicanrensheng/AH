package com.tv.live.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
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
    private static final String ACTION_ALARM = "com.tv.live.ALARM_PLAY";

    public EpgManagerWrapper(Context context, ListView listView) {
        this.context = context;
        this.listView = listView;
        adapter = new EpgAdapter(context, new ArrayList<>());
        listView.setAdapter(adapter);
        registerAlarmReceiver();
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
                item.channelName = currentChannel.getName();
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

    private void setAlarm(long triggerTime, String channelName, String playUrl, String title) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(ACTION_ALARM);
        intent.putExtra("channelName", channelName);
        intent.putExtra("playUrl", playUrl);
        intent.putExtra("title", title);

        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flag |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, (int) triggerTime, intent, flag);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        }
    }

    private void registerAlarmReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_ALARM.equals(intent.getAction())) {
                    String playUrl = intent.getStringExtra("playUrl");
                    String title = intent.getStringExtra("title");
                    MainActivity activity = (MainActivity) context;
                    activity.mPlayerManager.play(playUrl);
                    Toast.makeText(context, "【自动播放】" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_ALARM));
    }

    public static class EpgDisplayItem {
        public String time;
        public String title;
        public long startTime;
        public String playUrl;
        public String channelName;
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
                holder.tv_dayName = convertView.findViewById(R.id.tv_dayName);
                holder.tv_time = convertView.findViewById(R.id.tv_time);
                holder.tv_title = convertView.findViewById(R.id.tv_title);
                holder.tv_action = convertView.findViewById(R.id.tv_action);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            EpgDisplayItem item = getItem(pos);
            if (item == null) return convertView;

            holder.tv_time.setText(item.time);
            holder.tv_title.setText(item.title);
            holder.tv_action.setOnClickListener(null);

            if (item.isPast) {
                holder.tv_action.setText("回看");
                holder.tv_action.setBackgroundColor(0xFF2196F3);
                holder.tv_action.setOnClickListener(v -> {
                    ((MainActivity) context).mPlayerManager.play(item.playUrl);
                    Toast.makeText(context, "正在回看：" + item.title, Toast.LENGTH_SHORT).show();
                });
            } else if (item.isFuture) {
                if (bookedPrograms.contains(item.startTime)) {
                    holder.tv_action.setText("已预约");
                    holder.tv_action.setBackgroundColor(0xFF607D8B);
                    holder.tv_action.setEnabled(false);
                } else {
                    holder.tv_action.setText("预约");
                    holder.tv_action.setBackgroundColor(0xFF4CAF50);
                    holder.tv_action.setEnabled(true);
                    holder.tv_action.setOnClickListener(v -> {
                        bookedPrograms.add(item.startTime);
                        setAlarm(item.startTime, item.channelName, item.playUrl, item.title);
                        notifyDataSetChanged();
                        Toast.makeText(context, "预约成功：" + item.title, Toast.LENGTH_SHORT).show();
                    });
                }
            } else {
                holder.tv_action.setText("播放中");
                holder.tv_action.setBackgroundColor(0xFFFF9800);
                holder.tv_action.setEnabled(false);
            }
            return convertView;
        }

        class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }
    }
}
