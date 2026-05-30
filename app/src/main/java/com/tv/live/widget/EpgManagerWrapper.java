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
    private final Set<String> bookedSet = new HashSet<>();
    private static final String ACTION_ALARM = "com.tv.live.ALARM_PLAY";

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        registerAlarmReceiver();
    }

    // 保留你原来的方法，不影响任何现有调用
public void refresh(Channel currentChannel, List<Channel> channelSourceList) {
    // 默认刷新"今天"的节目单，兼容旧逻辑
    refresh(currentChannel, channelSourceList, 0);
}

// 新增：带日期筛选的重载方法，用于实现日期联动
public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
    if (currentChannel == null) {
        showEmpty();
        return;
    }
    new Thread(() -> {
        try {
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(currentChannel.getName());
            List<Channel.EpgItem> data = new ArrayList<>();
            if (epgList != null && !epgList.isEmpty()) {
                // 根据日期索引筛选节目单
                String[] dayNames = {"今天", "周一", "周二", "周三", "周四", "周五", "周六"};
                String targetDay = (dateIndex >= 0 && dateIndex < dayNames.length) ? dayNames[dateIndex] : "今天";

                for (Channel.EpgItem item : epgList) {
                    if (targetDay.equals(item.dayName)) {
                        data.add(item);
                    }
                }
                // 按时间排序
                Collections.sort(data, Comparator.comparing(o -> o.time));
            }
            updateUi(currentChannel, data);
        } catch (Exception e) {
            updateUi(currentChannel, new ArrayList<>());
        }
    }).start();
}

    private void showEmpty() {
        updateUi(null, new ArrayList<>());
    }

    private void updateUi(Channel channel, List<Channel.EpgItem> list) {
        lvEpg.post(() -> {
            if (adapter == null) {
                adapter = new EpgAdapter(context, channel, list);
                lvEpg.setAdapter(adapter);
            } else {
                adapter.setData(channel, list);
            }
        });
    }

    public void clearEpg() {
        updateUi(null, new ArrayList<>());
    }

    private void setAlarm(Context ctx, long reqCode, String title, String playUrl) {
        AlarmManager alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(ACTION_ALARM);
        intent.putExtra("title", title);
        intent.putExtra("playUrl", playUrl);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flag |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, (int) reqCode, intent, flag);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pi);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pi);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void registerAlarmReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_ALARM.equals(intent.getAction())) {
                    String url = intent.getStringExtra("playUrl");
                    String title = intent.getStringExtra("title");
                    MainActivity act = (MainActivity) context;
                    act.mPlayerManager.play(url);
                    Toast.makeText(context, "【自动播放】" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_ALARM));
    }

    public void onBackPressed() {}

    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final LayoutInflater inflater;
        private Channel currentChannel;
        private List<Channel.EpgItem> items = new ArrayList<>();

        public EpgAdapter(Context ctx, Channel channel, List<Channel.EpgItem> list) {
            super(ctx, R.layout.item_epg, list);
            inflater = LayoutInflater.from(ctx);
            currentChannel = channel;
            items = list;
        }

        public void setData(Channel channel, List<Channel.EpgItem> list) {
            currentChannel = channel;
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
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

            Channel.EpgItem item = items.get(position);
            holder.tv_dayName.setText(item.dayName);
            holder.tv_time.setText(item.time);
            holder.tv_title.setText(item.title);

            String key = currentChannel != null ? currentChannel.getName() + "_" + position : "";
            if (item.isPlaying) {
                holder.tv_action.setText("播放中");
                holder.tv_action.setBackgroundColor(0xFFFF9800);
                holder.tv_action.setEnabled(false);
            } else {
                if (bookedSet.contains(key)) {
                    holder.tv_action.setText("已预约");
                    holder.tv_action.setBackgroundColor(0xFF607D8B);
                    holder.tv_action.setEnabled(false);
                } else {
                    holder.tv_action.setText("预约");
                    holder.tv_action.setBackgroundColor(0xFF4CAF50);
                    holder.tv_action.setEnabled(true);
                    holder.tv_action.setOnClickListener(v -> {
                        bookedSet.add(key);
                        setAlarm(context, position, item.title, currentChannel.getPlayUrl());
                        notifyDataSetChanged();
                        Toast.makeText(context, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                    });
                }
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
