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
                    // 按时间自动排序（兼容你现有字段）
                    Collections.sort(epgList, (a, b) -> {
                        // 假设你的EpgItem里用的是startTime或start_time，这里用字符串比较兜底
                        return a.time.compareTo(b.time);
                    });

                    long now = System.currentTimeMillis();
                    for (Channel.EpgItem item : epgList) {
                        EpgItem ei = new EpgItem();
                        ei.dayName = item.dayName;
                        ei.time = item.time;
                        ei.title = item.title;
                        ei.playUrl = currentChannel.getPlayUrl();
                        // 这里直接用时间字符串，不依赖startTime字段
                        ei.isPast = false;
                        ei.isFuture = false;
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

    // 预约闹钟（暂时保留，后续你可以根据需要再开启）
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

    // 数据模型（和你的字段兼容）
    public static class EpgItem {
        public String dayName;
        public String time;
        public String title;
        public String playUrl;
        public boolean isPast;
        public boolean isFuture;
    }

    // 适配器（带回看/预约，不依赖startTime字段）
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

            // 这里简化处理，全部显示“回看”按钮（后续你可以再根据实际字段优化）
            holder.tv_action.setVisibility(View.VISIBLE);
            holder.tv_action.setText("回看");
            holder.tv_action.setBackgroundColor(0xFF2196F3);
            holder.tv_action.setOnClickListener(v -> {
                ((MainActivity) context).mPlayerManager.play(item.playUrl);
                Toast.makeText(context, "正在回看：" + item.title, Toast.LENGTH_SHORT).show();
            });

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
