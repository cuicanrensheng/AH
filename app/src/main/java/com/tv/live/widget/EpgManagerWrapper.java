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
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.MainActivity;
import com.tv.live.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<Long> bookedSet = new HashSet<>();
    private static final String ACTION_ALARM = "com.tv.live.ALARM_PLAY";

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        registerAlarmReceiver();
    }

    public void refresh(Channel currentChannel, List<Channel> channelSourceList) {
        if (currentChannel == null) {
            showEmpty();
            return;
        }
        new Thread(() -> {
            try {
                List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(currentChannel.getName());
                List<EpgItem> data = new ArrayList<>();

                if (epgList != null && !epgList.isEmpty()) {
                    // 按时间自动排序
                    Collections.sort(epgList, Comparator.comparingLong(o -> o.startTime));

                    long now = System.currentTimeMillis();
                    for (Channel.EpgItem item : epgList) {
                        EpgItem ei = new EpgItem();
                        ei.dayName = item.dayName;
                        ei.time = item.time;
                        ei.title = item.title;
                        ei.startTime = item.startTime;
                        ei.playUrl = currentChannel.getPlayUrl();
                        ei.isPast = item.startTime < now;
                        ei.isFuture = item.startTime > now;
                        data.add(ei);
                    }
                } else {
                    EpgItem empty = new EpgItem();
                    empty.title = "暂无节目单";
                    data.add(empty);
                }
                updateUi(data);

            } catch (Exception e) {
                List<EpgItem> data = new ArrayList<>();
                EpgItem err = new EpgItem();
                err.title = "暂无节目单";
                data.add(err);
                updateUi(data);
            }
        }).start();
    }

    private void showEmpty() {
        List<EpgItem> data = new ArrayList<>();
        EpgItem item = new EpgItem();
        item.title = "暂无节目单";
        data.add(item);
        updateUi(data);
    }

    private void updateUi(List<EpgItem> data) {
        lvEpg.post(() -> {
            if (adapter == null) {
                adapter = new EpgAdapter(context, data);
                lvEpg.setAdapter(adapter);
            } else {
                adapter.clear();
                adapter.addAll(data);
                adapter.notifyDataSetChanged();
            }
        });
    }

    // 预约闹钟
    private void setAlarm(long triggerTime, String title, String playUrl) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(ACTION_ALARM);
        intent.putExtra("title", title);
        intent.putExtra("playUrl", playUrl);

        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flag |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pi = PendingIntent.getBroadcast(context, (int) triggerTime, intent, flag);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pi);
        }
    }

    // 闹钟广播
    private void registerAlarmReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_ALARM.equals(intent.getAction())) {
                    String url = intent.getStringExtra("playUrl");
                    String title = intent.getStringExtra("title");
                    ((MainActivity) context).mPlayerManager.play(url);
                    Toast.makeText(context, "自动播放：" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_ALARM));
    }

    public void onBackPressed() {}

    // ==============================================
    // 数据模型
    public static class EpgItem {
        public String dayName;
        public String time;
        public String title;
        public long startTime;
        public String playUrl;
        public boolean isPast;
        public boolean isFuture;
    }

    // ==============================================
    // 适配器（带回看/预约）
    private class EpgAdapter extends ArrayAdapter<EpgItem> {
        private final LayoutInflater inflater;

        public EpgAdapter(Context ctx, List<EpgItem> list) {
            super(ctx, R.layout.item_epg, list);
            inflater = LayoutInflater.from(ctx);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
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

            EpgItem item = getItem(position);
            if (item == null) return convertView;

            holder.tv_dayName.setText(item.dayName == null ? "" : item.dayName);
            holder.tv_time.setText(item.time == null ? "" : item.time);
            holder.tv_title.setText(item.title);

            if (item.title.equals("暂无节目单")) {
                holder.tv_action.setVisibility(View.GONE);
                return convertView;
            }

            // 回看 / 预约 / 播放中
            if (item.isPast) {
                holder.tv_action.setVisibility(View.VISIBLE);
                holder.tv_action.setText("回看");
                holder.tv_action.setBackgroundColor(0xFF2196F3);
                holder.tv_action.setOnClickListener(v -> {
                    ((MainActivity) context).mPlayerManager.play(item.playUrl);
                    Toast.makeText(context, "正在回看：" + item.title, Toast.LENGTH_SHORT).show();
                });
            } else if (item.isFuture) {
                holder.tv_action.setVisibility(View.VISIBLE);
                if (bookedSet.contains(item.startTime)) {
                    holder.tv_action.setText("已预约");
                    holder.tv_action.setBackgroundColor(0xFF607D8B);
                    holder.tv_action.setEnabled(false);
                } else {
                    holder.tv_action.setText("预约");
                    holder.tv_action.setBackgroundColor(0xFF4CAF50);
                    holder.tv_action.setEnabled(true);
                    holder.tv_action.setOnClickListener(v -> {
                        bookedSet.add(item.startTime);
                        setAlarm(item.startTime, item.title, item.playUrl);
                        notifyDataSetChanged();
                        Toast.makeText(context, "预约成功：" + item.title, Toast.LENGTH_SHORT).show();
                    });
                }
            } else {
                holder.tv_action.setVisibility(View.VISIBLE);
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
