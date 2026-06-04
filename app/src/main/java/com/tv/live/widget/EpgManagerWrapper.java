package com.tv.live.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.MainActivity;
import com.tv.live.R;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private int selectedPosition = 0;
    private int playingIndex = -1;
    private int selectDayIndex = 0;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        lvEpg.setItemsCanFocus(true);

        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (parent.getAdapter() != null) {
                    ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        registerReminderReceiver();
    }

    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) return;
        playingIndex = -1;
        selectDayIndex = dateIndex;

        new Thread(() -> {
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(currentChannel.getName());
            List<Channel.EpgItem> data = new ArrayList<>();

            if (epgList != null && !epgList.isEmpty()) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, dateIndex);
                int w = cal.get(Calendar.DAY_OF_WEEK);
                String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                String targetDay = dateIndex == 0 ? "今天" : weekMap[w % 7];

                for (Channel.EpgItem item : epgList) {
                    if (targetDay.equals(item.dayName)) {
                        data.add(item);
                    }
                }

                Collections.sort(data, Comparator.comparing(o -> o.time));
                String now = getNow();
                Channel.EpgItem playing = null;

                // 自动填充空endTime：下一档开播作为结束，最后一档+60分钟兜底
                for (int i = 0; i < data.size(); i++) {
                    Channel.EpgItem curr = data.get(i);
                    if (TextUtils.isEmpty(curr.endTime)) {
                        if (i + 1 < data.size()) {
                            curr.endTime = data.get(i + 1).time;
                        } else {
                            // 当日最后节目没有下一条，默认+60分钟
                            curr.endTime = addOneHour(curr.time);
                        }
                    }
                    curr.isPlaying = false;
                    // 判断是否正在播出：当前时间在节目起止中间
                    if (now.compareTo(curr.time) >= 0 && now.compareTo(curr.endTime) < 0) {
                        curr.isPlaying = true;
                        playing = curr;
                        playingIndex = i;
                    }
                }

                // 当前播放条目置顶
                if (playing != null && playingIndex > 0) {
                    data.remove(playing);
                    data.add(0, playing);
                    playingIndex = 0;
                }
            }

            ((MainActivity) context).runOnUiThread(() -> {
                if (adapter == null) {
                    adapter = new EpgAdapter(context, currentChannel, data, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(currentChannel, data, selectDayIndex);
                }
                if (playingIndex >= 0) {
                    lvEpg.setSelection(playingIndex);
                    selectedPosition = playingIndex;
                } else {
                    lvEpg.setSelection(0);
                    selectedPosition = 0;
                }
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    // 时分字符串+60分钟
    private String addOneHour(String hm) {
        String[] arr = hm.split(":");
        int h = Integer.parseInt(arr[0]);
        int m = Integer.parseInt(arr[1]);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.add(Calendar.MINUTE, 60);
        return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private String getNow() {
        return String.format("%02d:%02d",
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE));
    }

    private void registerReminderReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REMINDER.equals(intent.getAction())) {
                    String title = intent.getStringExtra("title");
                    Toast.makeText(context, "⏰ 节目提醒：" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_REMINDER));
    }

    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final Context ctx;
        private Channel currentChannel;
        private List<Channel.EpgItem> list;
        private final LayoutInflater inflater;
        private int dayIndex;
        private final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

        public EpgAdapter(Context ctx, Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            super(ctx, R.layout.item_epg, list);
            this.ctx = ctx;
            this.currentChannel = currentChannel;
            this.list = list;
            this.inflater = LayoutInflater.from(ctx);
            this.dayIndex = dayIndex;
        }

        public void setData(Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            this.currentChannel = currentChannel;
            this.list.clear();
            this.list.addAll(list);
            this.dayIndex = dayIndex;
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

            Channel.EpgItem item = list.get(position);
            holder.tv_dayName.setText(item.dayName);
            holder.tv_time.setText(item.time + "-" + item.endTime);
            holder.tv_title.setText(item.title);

            // 选中/正在播放 蓝色高亮
            if (position == selectedPosition || item.isPlaying) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
            } else {
                holder.tv_dayName.setTextColor(Color.WHITE);
                holder.tv_time.setTextColor(Color.LTGRAY);
                holder.tv_title.setTextColor(Color.WHITE);
            }

            String key = currentChannel.getName() + "_" + position;
            boolean isPast = item.time.compareTo(getNow()) < 0;

            if (item.isPlaying) {
                holder.tv_action.setText("播放中");
                holder.tv_action.setBackgroundColor(0xFFFF9800);
                holder.tv_action.setEnabled(false);
            } else if (isPast) {
                holder.tv_action.setText("回看");
                holder.tv_action.setBackgroundColor(0xFF607D8B);
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(v -> {
                    String liveUrl = currentChannel.getPlayUrl();
                    if (TextUtils.isEmpty(liveUrl)) {
                        Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 构建回看当天日期
                    Calendar playDay = Calendar.getInstance();
                    playDay.add(Calendar.DAY_OF_YEAR, dayIndex);

                    // 开始时间
                    String[] startHm = item.time.split(":");
                    Calendar startCal = (Calendar) playDay.clone();
                    startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startHm[0]));
                    startCal.set(Calendar.MINUTE, Integer.parseInt(startHm[1]));
                    startCal.set(Calendar.SECOND, 0);

                    // 结束时间【真实EPG结束时间，不再固定+60】
                    String[] endHm = item.endTime.split(":");
                    Calendar endCal = (Calendar) playDay.clone();
                    endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endHm[0]));
                    endCal.set(Calendar.MINUTE, Integer.parseInt(endHm[1]));
                    endCal.set(Calendar.SECOND, 0);

                    String startStr = sdfFull.format(startCal.getTime());
                    String endStr = sdfFull.format(endCal.getTime());

                    // 适配江西移动PLTV规则
                    String catchUrl;
                    if (liveUrl.contains("PLTV")) {
                        catchUrl = liveUrl.replace("PLTV", "TVOD");
                    } else {
                        catchUrl = liveUrl;
                    }
                    if (catchUrl.contains("?")) {
                        catchUrl += "&playseek=" + startStr + "-" + endStr;
                    } else {
                        catchUrl += "?playseek=" + startStr + "-" + endStr;
                    }

                    ((MainActivity) ctx).mPlayerManager.playUrl(catchUrl);
                    Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                });
            } else {
                // 未开播节目：预约
                if (bookedSet.contains(key)) {
                    holder.tv_action.setText("已预约");
                    holder.tv_action.setBackgroundColor(0xFF607D8B);
                } else {
                    holder.tv_action.setText("预约");
                    holder.tv_action.setBackgroundColor(0xFF4CAF50);
                }
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(v -> {
                    if (bookedSet.contains(key)) {
                        bookedSet.remove(key);
                        Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
                    } else {
                        bookedSet.add(key);
                        Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                    }
                    notifyDataSetChanged();
                });
            }
            return convertView;
        }

        // ✅【唯一修复点】正确的 ViewHolder 类
        class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }
    }
}
