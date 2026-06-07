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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    // 新增：缓存每个EpgItem对应的endTime，替代实体字段，原有代码不动
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
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
        epgEndTimeMap.clear();

        new Thread(() -> {
            List<Channel.EpgItem> epgList = null;
            try {
                epgList = new ArrayList<>(EpgManager.getInstance().getEpg(currentChannel.getName()));
            } catch (Exception e) {
                epgList = new ArrayList<>();
            }

            List<Channel.EpgItem> data = new ArrayList<>();

            // 【修复核心】正确匹配今天/周一/周二...
    String targetDay;
    if (dateIndex == 0) {
        targetDay = "今天";
    } else {
        targetDay = weekMap[w % 7];
    }

    // 筛选对应日期节目
    data.clear();
    for (Channel.EpgItem item : epgList) {
        if (targetDay.equals(item.dayName)) {
            data.add(item);
        }
    }


                Collections.sort(data, Comparator.comparing(o -> o.time));
                String now = getNow();
                Channel.EpgItem playing = null;

                for (int i = 0; i < data.size(); i++) {
                    Channel.EpgItem curr = data.get(i);

                    // 修复：自动清洗脏数据，只保留合法时间
                    if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-")) {
                        curr.time = curr.time.split("-")[0].trim();
                    }
                    // 原有endTime赋值逻辑完全保留，存入Map
                    if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                        if (i + 1 < data.size()) {
                            Channel.EpgItem next = data.get(i + 1);
                            if (next.time.contains("-")) {
                                epgEndTimeMap.put(curr, next.time.split("-")[0].trim());
                            } else {
                                epgEndTimeMap.put(curr, next.time);
                            }
                        } else {
                            epgEndTimeMap.put(curr, addOneHour(curr.time));
                        }
                    }

                    curr.isPlaying = false;
                    String currEnd = epgEndTimeMap.get(curr);
                    if (isTimeBetween(now, curr.time, currEnd)) {
                        curr.isPlaying = true;
                        playing = curr;
                        playingIndex = i;
                    }
                }

                if (playing != null && playingIndex > 0) {
                    data.remove(playing);
                    data.add(0, playing);
                    playingIndex = 0;
                }
            }
            
    ((MainActivity) context).runOnUiThread(() -> {
    // 【修复核心】每次都强制重建适配器，彻底刷新UI
    adapter = new EpgAdapter(context, currentChannel, data, selectDayIndex);
    lvEpg.setAdapter(adapter);

    // 定位到正在播放的节目
    if (playingIndex >= 0) {
        lvEpg.setSelection(playingIndex);
        selectedPosition = playingIndex;
    } else {
        lvEpg.setSelection(0);
        selectedPosition = 0;
    }

    // 刷新列表
    adapter.notifyDataSetChanged();
});
     // 安全时间比较（彻底防崩）
private boolean isTimeBetween(String now, String start, String end) {
    try {
        if (now == null || start == null || end == null)
            return false;
        
        if (now.contains(":") && start.contains(":") && end.contains(":")) {
            return now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        }
    } catch (Exception e) {
    }
    return false;
}
 
    // 彻底修复：防脏数据、防崩
    private String addOneHour(String hm) {
        try {
            if (hm == null || !hm.contains(":")) return "23:59";

            // 清洗脏数据
            hm = hm.trim();
            if (hm.contains("-")) hm = hm.split("-")[0].trim();

            String[] arr = hm.split(":");
            int h = Integer.parseInt(arr[0].trim());
            int m = Integer.parseInt(arr[1].trim());

            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.add(Calendar.MINUTE, 60);

            return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        } catch (Exception e) {
            return "23:59";
        }
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
            String endTime = epgEndTimeMap.get(item);
            holder.tv_dayName.setText(item.dayName);
            // 原有拼接格式保留不变
            holder.tv_time.setText(item.time + "-" + endTime);
            holder.tv_title.setText(item.title);

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
            boolean isPast = false;
            try {
                isPast = item.time.compareTo(getNow()) < 0;
            } catch (Exception e) {}

            if (item.isPlaying) {
                holder.tv_action.setText("播放中");
                holder.tv_action.setBackgroundColor(0xFFFF9800);
                holder.tv_action.setEnabled(false);
            } else if (isPast) {
                holder.tv_action.setText("回看");
                holder.tv_action.setBackgroundColor(0xFF607D8B);
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(v -> {
                    try {
                        String liveUrl = currentChannel.getPlayUrl();
                        if (TextUtils.isEmpty(liveUrl)) {
                            Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        Calendar playDay = Calendar.getInstance();
                        playDay.add(Calendar.DAY_OF_YEAR, dayIndex);

                        String[] startHm = item.time.split(":");
                        Calendar startCal = (Calendar) playDay.clone();
                        startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startHm[0].trim()));
                        startCal.set(Calendar.MINUTE, Integer.parseInt(startHm[1].trim()));
                        startCal.set(Calendar.SECOND, 0);

                        String[] endHm = endTime.split(":");
                        Calendar endCal = (Calendar) playDay.clone();
                        endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endHm[0].trim()));
                        endCal.set(Calendar.MINUTE, Integer.parseInt(endHm[1].trim()));
                        endCal.set(Calendar.SECOND, 0);

                        String startStr = sdfFull.format(startCal.getTime());
                        String endStr = sdfFull.format(endCal.getTime());

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
                    } catch (Exception e) {
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
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

        class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }
    }
}
