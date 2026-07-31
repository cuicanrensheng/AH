package com.tv.live.widget;

import com.tv.live.manager.ChannelPanelController;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

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
 * EPG 节目单包装管理器（修复：统一焦点与选中样式，去除播放中背景冲突）
 */
public class EpgManagerWrapper {
    private final ListView lvEpg;
    private Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";

    private static final int COLOR_BLUE = 0xFF40A9FF;
    private static final int COLOR_BG_BLUE = 0x3340A9FF;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_GRAY = 0xFFCCCCCC;

    private int selectedPosition = 0;
    private int playingIndex = -1;
    private int selectDayIndex = 0;
    private boolean isTouching = false;
    private int pendingConfirmPosition = -1;

    private BroadcastReceiver reminderReceiver;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        lvEpg.setItemsCanFocus(true);
        lvEpg.setFocusable(true);
        lvEpg.setFocusableInTouchMode(true);
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPosition = position;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        lvEpg.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                isTouching = true;
            } else if (event.getAction() == android.view.MotionEvent.ACTION_UP
                    || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                isTouching = false;
            }
            return false;
        });

        lvEpg.setOnItemClickListener((parent, view, position, id) -> {
            if (isTouching) {
                // 触摸操作：即点即触发（一次点击 = 立即执行）
                TextView actionBtn = view.findViewById(R.id.tv_action);
                if (actionBtn != null && actionBtn.isEnabled()) {
                    actionBtn.performClick();
                }
                selectedPosition = position;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            } else {
                // 按键操作：第一次选中高亮，第二次确认执行
                if (position == pendingConfirmPosition) {
                    // 第二次OK按压：确认执行
                    TextView actionBtn = view.findViewById(R.id.tv_action);
                    if (actionBtn != null && actionBtn.isEnabled()) {
                        actionBtn.performClick();
                    }
                    pendingConfirmPosition = -1;
                } else {
                    // 第一次选中：高亮 + 提示
                    pendingConfirmPosition = position;
                    selectedPosition = position;
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    lvEpg.setSelection(position);
                    Toast.makeText(context, "再次按确认键执行", Toast.LENGTH_SHORT).show();
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
                List<Channel.EpgItem> temp = EpgManager.getInstance().getEpg(currentChannel);
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

                if (!finalData.isEmpty()) {
                    int focusPos = 0;
                    if (playingIndex >= 0 && playingIndex < finalData.size()) {
                        focusPos = playingIndex;
                    }
                    selectedPosition = focusPos;
                    lvEpg.setSelection(focusPos);
                }
                
                adapter.notifyDataSetChanged();
                scrollToCurrentProgram(finalData);
            });
        }).start();
    }

    private void scrollToCurrentProgram(List<Channel.EpgItem> epgList) {
        if (epgList == null || epgList.isEmpty() || selectDayIndex != 0) {
            return;
        }
        String now = getNow();
        for (int i = 0; i < epgList.size(); i++) {
            Channel.EpgItem item = epgList.get(i);
            String start = item.time;
            String end = epgEndTimeMap.get(item);
            if (start != null && end != null && isTimeBetween(now, start, end)) {
                final int scrollPos = i;
                lvEpg.post(() -> {
                    lvEpg.setSelection(scrollPos);
                    lvEpg.setSelectionFromTop(scrollPos, lvEpg.getHeight() / 2);
                });
                break;
            }
        }
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
            return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        } catch (Exception e) {
            return "23:59";
        }
    }

    private String getNow() {
        return String.format(Locale.ROOT, "%02d:%02d",
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE));
    }

    private void registerReminderReceiver() {
        reminderReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REMINDER.equals(intent.getAction())) {
                    String title = intent.getStringExtra("title");
                    Toast.makeText(context, "节目提醒：" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_REMINDER);
        ContextCompat.registerReceiver(context, reminderReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    public void release() {
        if (context != null && reminderReceiver != null) {
            try {
                context.unregisterReceiver(reminderReceiver);
            } catch (Exception ignored) {}
            reminderReceiver = null;
        }
        if (adapter != null) {
            adapter.clear();
            adapter = null;
        }
        bookedSet.clear();
        epgEndTimeMap.clear();
        if (lvEpg != null) {
            lvEpg.setAdapter(null);
            lvEpg.setOnItemSelectedListener(null);
            lvEpg.setOnFocusChangeListener(null);
            lvEpg.setOnItemClickListener(null);
        }
        context = null;
    }

    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final Context ctx;
        private Channel currentChannel;
        private List<Channel.EpgItem> list;
        private final LayoutInflater inflater;
        private int dayIndex;
        private String currentNowStr;
        private final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

        private final View.OnClickListener actionClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Object tag = v.getTag();
                if (!(tag instanceof ItemActionTag)) return;
                ItemActionTag actionTag = (ItemActionTag) tag;

                Channel.EpgItem item = actionTag.item;
                String key = actionTag.key;

                if (actionTag.isPast) {
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

                        List<String> catchUrls = new ArrayList<>();
                        List<String> urlsToTry = new ArrayList<>();
                        urlsToTry.add(currentChannel.getMainPlayUrl());
                        urlsToTry.addAll(currentChannel.getBackupUrls());

                        for (String baseUrl : urlsToTry) {
                            if (TextUtils.isEmpty(baseUrl)) continue;
                            String catchUrl = baseUrl.contains("PLTV") ? baseUrl.replace("PLTV", "TVOD") : baseUrl;
                            catchUrl += catchUrl.contains("?") ? "&playseek=" + startStr + "-" + endStr : "?playseek=" + startStr + "-" + endStr;
                            catchUrls.add(catchUrl);
                        }

                        if (ctx instanceof MainActivity) {
                            MainActivity activity = (MainActivity) ctx;
                            ChannelPanelController controller = activity.getChannelPanelController();
                            if (controller != null && controller.isPanelOpen()) {
                                controller.hidePanel();
                            }
                            activity.setCatchUpMode(true);
                            activity.showExoController();
                            if (!catchUrls.isEmpty()) {
                                activity.mPlayerManager.playUrlWithFallbacks(
                                        catchUrls.get(0),
                                        currentChannel.getName(),
                                        currentChannel,
                                        catchUrls);
                            }
                        }
                        Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    if (bookedSet.contains(key)) {
                        bookedSet.remove(key);
                        Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
                    } else {
                        bookedSet.add(key);
                        Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                    }
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
            this.currentNowStr = getNow();
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
            holder.tv_time.setText(String.format(Locale.ROOT, "%s-%s", item.time, endTime));
            holder.tv_title.setText(item.title);

            boolean isSelected = (position == selectedPosition);

            if (isSelected) {
                holder.tv_dayName.setTextColor(COLOR_BLUE);
                holder.tv_time.setTextColor(COLOR_BLUE);
                holder.tv_title.setTextColor(COLOR_BLUE);
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(COLOR_BG_BLUE);
            } else {
                holder.tv_dayName.setTextColor(COLOR_WHITE);
                holder.tv_time.setTextColor(COLOR_GRAY);
                holder.tv_title.setTextColor(COLOR_WHITE);
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            if (dayIndex == 0) {
                if (currentNowStr == null) currentNowStr = getNow();
                try {
                    if (item.time != null) {
                        isPast = item.time.compareTo(currentNowStr) < 0;
                    }
                } catch (Exception ignored) {}
            }

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

        private void updateActionButtonState(View rootView, ItemActionTag tag) {
            TextView actionBtn = rootView.findViewById(R.id.tv_action);
            if (actionBtn == null) return;
            if (tag.isPast) {
                // 回看按钮无状态变化
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

        private class ItemActionTag {
            Channel.EpgItem item;
            String key;
            boolean isPast;
        }
    }
}
