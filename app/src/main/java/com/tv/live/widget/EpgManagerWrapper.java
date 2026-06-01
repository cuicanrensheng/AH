package com.tv.live.widget;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
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
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 节目单管理器
 * 功能：日期切换正常显示、当前播放节目置顶、回看/预约按钮、遥控器选中变蓝
 */
public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private int selectedPosition = 0;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        lvEpg.setItemsCanFocus(true);
        lvEpg.setOnItemSelectedListener((parent, view, pos, id) -> {
            selectedPosition = pos;
            parent.invalidateViews();
        });
        registerReminderReceiver();
    }

    /**
     * 刷新节目单
     * @param dateIndex 日期偏移：0=今天，1=明天...
     */
    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) return;

        new Thread(() -> {
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(currentChannel.getName());
            List<Channel.EpgItem> data = new ArrayList<>();

            if (epgList != null && !epgList.isEmpty()) {
                // 计算目标日期
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, dateIndex);
                int week = cal.get(Calendar.DAY_OF_WEEK);
                String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                String targetDay = dateIndex == 0 ? "今天" : weekMap[week % 7];

                // 筛选对应日期的节目
                for (Channel.EpgItem item : epgList) {
                    if (targetDay.equals(item.dayName)) data.add(item);
                }

                // 按时间排序
                Collections.sort(data, Comparator.comparing(o -> o.time));

                // 标记正在播放的节目
                String nowTime = getCurrentTime();
                Channel.EpgItem playingItem = null;
                for (int i = 0; i < data.size(); i++) {
                    Channel.EpgItem curr = data.get(i);
                    Channel.EpgItem next = (i + 1 < data.size()) ? data.get(i + 1) : null;
                    curr.isPlaying = false;
                    if (next != null && curr.time.compareTo(nowTime) <= 0 && nowTime.compareTo(next.time) < 0) {
                        curr.isPlaying = true;
                        playingItem = curr;
                    }
                }

                // 当前播放节目置顶
                if (playingItem != null) {
                    data.remove(playingItem);
                    data.add(0, playingItem);
                }
            }

            ((MainActivity) context).runOnUiThread(() -> {
                if (adapter == null) {
                    adapter = new EpgAdapter(context, currentChannel, data);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(currentChannel, data);
                }
                lvEpg.setSelection(0);
            });
        }).start();
    }

    private String getCurrentTime() {
        return String.format("%02d:%02d",
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE));
    }

    /**
     * 注册预约提醒广播接收器
     */
    private void registerReminderReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REMINDER.equals(intent.getAction())) {
                    String title = intent.getStringExtra("epg_title");
                    Toast.makeText(context, "⏰ 节目提醒：" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_REMINDER));
    }

    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final LayoutInflater inflater;
        private Channel currentChannel;
        private List<Channel.EpgItem> items;

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

            // 选中/播放中变蓝
            if (position == selectedPosition || item.isPlaying) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
            } else {
                holder.tv_dayName.setTextColor(Color.WHITE);
                holder.tv_time.setTextColor(Color.LTGRAY);
                holder.tv_title.setTextColor(Color.WHITE);
            }

            // 按钮逻辑：播放中/回看/预约/已预约
            String key = currentChannel.getName() + "_" + position;
            boolean isPast = item.time.compareTo(getCurrentTime()) < 0;

            if (item.isPlaying) {
                holder.tv_action.setText("播放中");
                holder.tv_action.setBackgroundColor(0xFFFF9800);
                holder.tv_action.setEnabled(false);
            } else if (isPast) {
                holder.tv_action.setText("回看");
                holder.tv_action.setBackgroundColor(0xFF607D8B);
                holder.tv_action.setOnClickListener(v -> {
                    ((MainActivity) context).mPlayerManager.playUrl(currentChannel.getPlayUrl());
                    Toast.makeText(context, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                });
            } else {
                if (bookedSet.contains(key)) {
                    holder.tv_action.setText("已预约");
                    holder.tv_action.setBackgroundColor(0xFF607D8B);
                    holder.tv_action.setOnClickListener(v -> {
                        bookedSet.remove(key);
                        Toast.makeText(context, "已取消预约", Toast.LENGTH_SHORT).show();
                        notifyDataSetChanged();
                    });
                } else {
                    holder.tv_action.setText("预约");
                    holder.tv_action.setBackgroundColor(0xFF4CAF50);
                    holder.tv_action.setOnClickListener(v -> {
                        bookedSet.add(key);
                        Toast.makeText(context, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                        notifyDataSetChanged();
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
