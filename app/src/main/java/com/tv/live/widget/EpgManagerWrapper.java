package com.tv.live.widget;
import com.tv.live.manager.ChannelPanelController;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
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

/**
 * EPG 节目单包装管理器
 */
public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private int selectedPosition = 0;
    private int playingIndex = -1;
    private int selectDayIndex = 0;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        lvEpg.setItemsCanFocus(true);
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedPosition = -1;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        });
        lvEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        });
        registerReminderReceiver();
    }

    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) return;
        playingIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();
        new Thread(() -> {
            List<Channel.EpgItem> originEpgList;
            try {
                List<Channel.EpgItem> temp = EpgManager.getInstance().getEpg(currentChannel.getName());
                originEpgList = temp == null ? new ArrayList<>() : new ArrayList<>(temp);
            } catch (Exception e) {
                originEpgList = new ArrayList<>();
            }
            List<Channel.EpgItem> data = new ArrayList<>();
            if (!originEpgList.isEmpty()) {
                String targetDay;
                String targetWeekDay = null;
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, dateIndex);
                int w = cal.get(Calendar.DAY_OF_WEEK);
                String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                String weekDay = weekMap[w - 1];
                if (dateIndex == 0) {
                    targetDay = "今天";
                    targetWeekDay = weekDay;
                } else if (dateIndex == 1) {
                    targetDay = "明天";
                    targetWeekDay = weekDay;
                } else if (dateIndex == 2) {
                    targetDay = "后天";
                    targetWeekDay = weekDay;
                } else {
                    targetDay = weekDay;
                }
                for (Channel.EpgItem item : originEpgList) {
                    if (item.dayName == null) continue;
                    String dayName = item.dayName.trim();
                    boolean match = targetDay.equals(dayName);
                    if (!match && targetWeekDay != null) match = targetWeekDay.equals(dayName);
                    if (match) {
                        data.add(item);
                    }
                }
                Collections.sort(data, Comparator.comparing(o -> o.time));
                if (dateIndex == 0) {
                    String now = getNow();
                    Channel.EpgItem playing = null;
                    for (int i = 0; i < data.size(); i++) {
                        Channel.EpgItem curr = data.get(i);
                        if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                            curr.time = curr.time.split("-")[0].trim();
                        if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                            if (i + 1 < data.size())
                                epgEndTimeMap.put(curr, data.get(i + 1).time.split("-")[0].trim());
                            else
                                epgEndTimeMap.put(curr, addOneHour(curr.time));
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
                } else {
                    playingIndex = -1;
                    for (int i = 0; i < data.size(); i++) {
                        Channel.EpgItem curr = data.get(i);
                        if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                            curr.time = curr.time.split("-")[0].trim();
                        if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                            if (i + 1 < data.size())
                                epgEndTimeMap.put(curr, data.get(i + 1).time.split("-")[0].trim());
                            else
                                epgEndTimeMap.put(curr, addOneHour(curr.time));
                        }
                        curr.isPlaying = false;
                    }
                }
            }
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }
                if (selectedPosition >= finalData.size()) {
                    selectedPosition = Math.max(0, finalData.size() - 1);
                }
                lvEpg.setSelection(selectedPosition);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private boolean isTimeBetween(String now, String start, String end) {
        try {
            if (now == null || start == null || end == null) return false;
            return now.contains(":") && start.contains(":") && end.contains(":")
                    && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String addOneHour(String hm) {
        try {
            if (hm == null || !hm.contains(":")) return "23:59";
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
                    Toast.makeText(context, "节目提醒：" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_REMINDER));
    }

    // ================= 🛠️ 核心优化的 Adapter 部分 =================
    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final Context ctx;
        private Channel currentChannel;
        private List<Channel.EpgItem> list;
        private final LayoutInflater inflater;
        private int dayIndex;
        private String currentNowStr; // 🟢 缓存当前时间字符串
        private final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

        // 🟢 复用单例 OnClickListener 避免重复 new
        private final View.OnClickListener actionClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Object tag = v.getTag();
                if (!(tag instanceof ItemActionTag)) return;
                ItemActionTag actionTag = (ItemActionTag) tag;

                Channel.EpgItem item = actionTag.item;
                String key = actionTag.key;

                // 复用日历对象，减少 GC
                if (actionTag.isPast) {
                    // 回看逻辑
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
                        String endTime = epgEndTimeMap.get(item);
                        String[] endHm = endTime.split(":");
                        Calendar endCal = (Calendar) playDay.clone();
                        endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endHm[0].trim()));
                        endCal.set(Calendar.MINUTE, Integer.parseInt(endHm[1].trim()));
                        endCal.set(Calendar.SECOND, 0);
                        String startStr = sdfFull.format(startCal.getTime());
                        String endStr = sdfFull.format(endCal.getTime());
                        String catchUrl = liveUrl.contains("PLTV") ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                        catchUrl += catchUrl.contains("?") ? "&playseek=" + startStr + "-" + endStr : "?playseek=" + startStr + "-" + endStr;

                        if (ctx instanceof MainActivity) {
                            MainActivity activity = (MainActivity) ctx;

                            // 🔥【联动 1】关闭设置页（通过公共访问器）
                            if (activity.isOpeningSettings()) {
                                activity.sendBroadcast(new Intent("com.tv.live.CLOSE_SETTINGS"));
                                activity.setOpeningSettings(false);
                            }

                            // 🔥【联动 2】关闭频道面板（通过公共访问器）
                            ChannelPanelController controller = activity.getChannelPanelController();
                            if (controller != null && controller.isPanelOpen()) {
                                controller.hidePanel();
                            }

                            // 🔴【联动 3】标记当前进入了回看模式
                            activity.setCatchUpMode(true);

                            // 🔥【联动 4】触发 ExoPlayer 原生控制栏
                            activity.showExoController();

                            // 播放回看流
                            activity.mPlayerManager.playUrl(catchUrl);
                        }
                        Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    // 预约/取消预约逻辑
                    if (bookedSet.contains(key)) {
                        bookedSet.remove(key);
                        Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
                    } else {
                        bookedSet.add(key);
                        Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                    }
                    // 🟢 改用局部更新，避免全量刷新掉帧
                    updateActionButtonState(v, actionTag);
                }
            }
        };

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
            this.currentNowStr = getNow(); // 缓存一次当前时间
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

            if (position < 0 || position >= list.size()) {
                return convertView;
            }

            Channel.EpgItem item = list.get(position);
            String endTime = epgEndTimeMap.get(item);
            holder.tv_dayName.setText(item.dayName);
            holder.tv_time.setText(item.time + "-" + endTime);
            holder.tv_title.setText(item.title);

            // 重置样式
            holder.tv_dayName.setTextColor(Color.WHITE);
            holder.tv_time.setTextColor(Color.LTGRAY);
            holder.tv_title.setTextColor(Color.WHITE);
            holder.tv_title.setTypeface(null, Typeface.NORMAL);
            convertView.setBackgroundColor(Color.TRANSPARENT);
            convertView.setSelected(false);

            boolean isFocused = (position == selectedPosition) && lvEpg.hasFocus();
            boolean isPlaying = item.isPlaying && dayIndex == 0;

            if (isFocused) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (isPlaying) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            
            // 🟢 优化：缓存 now 字符串，避免重复获取
            if (dayIndex == 0) {
                if (currentNowStr == null) currentNowStr = getNow();
                try {
                    if (item.time != null) {
                        isPast = item.time.compareTo(currentNowStr) < 0;
                    }
                } catch (Exception ignored) {}
            }

            // 🟢 构建保存 Tag，供复用监听器使用
            ItemActionTag tag = new ItemActionTag();
            tag.item = item;
            tag.key = key;
            tag.isPast = isPast;
            holder.tv_action.setTag(tag);
            holder.tv_action.setOnClickListener(actionClickListener);

            if (dayIndex == 0) {
                if (item.isPlaying) {
                    holder.tv_action.setText("播放中");
                    holder.tv_action.setBackgroundColor(0xFFFF9800);
                    holder.tv_action.setEnabled(false);
                } else if (isPast) {
                    holder.tv_action.setText("回看");
                    holder.tv_action.setBackgroundColor(0xFF607D8B);
                    holder.tv_action.setEnabled(true);
                } else {
                    holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                    holder.tv_action.setBackgroundColor(0xFF4CAF50);
                    holder.tv_action.setEnabled(true);
                }
            } else {
                holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                holder.tv_action.setBackgroundColor(0xFF4CAF50);
                holder.tv_action.setEnabled(true);
            }

            return convertView;
        }

        // 🟢 局部更新按钮状态，避免调用全局 notifyDataSetChanged
        private void updateActionButtonState(View rootView, ItemActionTag tag) {
            TextView actionBtn = rootView.findViewById(R.id.tv_action);
            if (actionBtn == null) return;
            if (tag.isPast) {
                // 回看按钮无状态变化，不需要更新
            } else {
                boolean isBooked = bookedSet.contains(tag.key);
                actionBtn.setText(isBooked ? "已预约" : "预约");
                actionBtn.setBackgroundColor(0xFF4CAF50);
            }
        }

        private class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }

        // 🔧【修复】去掉 static 关键字，因为 ItemActionTag 定义在非静态内部类 EpgAdapter 内部
        private class ItemActionTag {
            Channel.EpgItem item;
            String key;
            boolean isPast;
        }
    }
    // ================= 🛠️ 优化结束 =================
}
