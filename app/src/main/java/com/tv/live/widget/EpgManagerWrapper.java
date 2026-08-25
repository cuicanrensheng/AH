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

    private BroadcastReceiver reminderReceiver;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        // lvEpg.setItemsCanFocus(true); // 移除此行，让ListView正常触发onItemSelected以更新选中状态加粗
        lvEpg.setFocusable(true);
        lvEpg.setFocusableInTouchMode(false);
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

        lvEpg.setOnItemClickListener((parent, view, position, id) -> {
            if (position == selectedPosition) {
                // 第二次点击：确认执行回看/预约
                TextView actionBtn = view.findViewById(R.id.tv_action);
                if (actionBtn != null && actionBtn.isEnabled()) {
                    actionBtn.performClick();
                }
            } else {
                // 第一次点击：仅选中高亮（浅蓝背景+蓝字），不触发回看
                selectedPosition = position;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                lvEpg.setSelection(position);
            }
        });

        registerReminderReceiver();
    }

    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) return;
        playingIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();
        com.tv.live.util.AppExecutors.io(() -> {
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
                // 🟢 日期偏移：新列表第0位 = 今天(today+0) 第1位 = 明天(today+1) 第2位 = 后天(today+2)
                // 所以实际相对今天的 dayOffset = dateIndex
                int dayOffset = dateIndex;
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, dayOffset);   // 计算真实日期
                // 🔧 计算目标日期的精确 YYYYMMDD 整数键，优先与 item.dateYMD 匹配，消除中文字符串歧义
                int targetYMD = cal.get(Calendar.YEAR) * 10000
                        + (cal.get(Calendar.MONTH) + 1) * 100
                        + cal.get(Calendar.DAY_OF_MONTH);
                int w = cal.get(Calendar.DAY_OF_WEEK);
                String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                String weekDay = weekMap[w - 1];
                if (dateIndex == 0) {
                    targetDay = "今天";
                } else {
                    targetDay = weekDay;
                }
                targetWeekDay = weekDay;
                for (Channel.EpgItem item : originEpgList) {
                    if (item.dayName == null) continue;
                    String dayName = item.dayName.trim();
                    boolean match = false;
                    // 1️⃣ 优先精确 dateYMD 匹配（最可靠）
                    if (item.dateYMD > 0 && targetYMD > 0) {
                        match = (item.dateYMD == targetYMD);
                    }
                    // 2️⃣ 回退：中文昵称匹配（今天/周日~周六）
                    if (!match) match = targetDay.equals(dayName);
                    // 3️⃣ 回退：周几字符串匹配
                    if (!match && targetWeekDay != null) match = targetWeekDay.equals(dayName);
                    // 4️⃣ 最终回退：用 item.dateYMD 与 targetYMD 的年份+月日精确比较
                    if (!match && item.dateYMD > 0 && targetYMD > 0) {
                        match = (item.dateYMD == targetYMD);
                    }
                    if (match) {
                        data.add(item);
                    }
                }
                Collections.sort(data, (a, b) -> a.time.compareTo(b.time));
                if (dateIndex == 0) {    // 🟢 今天才判断当前播放中 & 实时时间对比
                    String now = getNow();
                    Channel.EpgItem playing = null;
                    for (int i = 0; i < data.size(); i++) {
                        Channel.EpgItem curr = data.get(i);
                        boolean isHuyaHistoryEpg = (currentChannel.isTogetherWatch() || currentChannel.getHuyaRoomId() > 0)
                                && (curr.time != null) && (curr.time.contains("直播中") || curr.time.contains(" - "));
                        if (!isHuyaHistoryEpg && !TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                            curr.time = curr.time.split("-")[0].trim();
                        if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                            if (isHuyaHistoryEpg && curr.time.contains(" - ")) {
                                String[] parts = curr.time.split(" - ");
                                String startPart = parts[0].trim();
                                String endPart = parts.length > 1 ? parts[1].trim() : "23:59";
                                if (!endPart.contains(":") || endPart.equals("直播中")) endPart = "23:59";
                                epgEndTimeMap.put(curr, endPart);
                                curr.time = startPart;
                            } else if (i + 1 < data.size()) {
                                Channel.EpgItem next = data.get(i + 1);
                                String nextTime = next.time;
                                if (nextTime != null && nextTime.contains(" - ")) nextTime = nextTime.split(" - ")[0].trim();
                                if (nextTime != null && nextTime.contains("-")) nextTime = nextTime.split("-")[0].trim();
                                epgEndTimeMap.put(curr, nextTime);
                            } else {
                                epgEndTimeMap.put(curr, addOneHour(curr.time));
                            }
                        }
                        curr.isPlaying = false;
                        String currEnd = epgEndTimeMap.get(curr);
                        if (isHuyaHistoryEpg && curr.isPlaying) {
                            playingIndex = i;
                        } else if (isTimeBetween(now, curr.time, currEnd)) {
                            curr.isPlaying = true;
                            playing = curr;
                            playingIndex = i;
                        }
                    }
                } else {
                    playingIndex = -1;
                    for (int i = 0; i < data.size(); i++) {
                        Channel.EpgItem curr = data.get(i);
                        boolean isHuyaHistoryEpg = (currentChannel.isTogetherWatch() || currentChannel.getHuyaRoomId() > 0)
                                && (curr.time != null) && (curr.time.contains("直播中") || curr.time.contains(" - "));
                        if (!isHuyaHistoryEpg && !TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                            curr.time = curr.time.split("-")[0].trim();
                        if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                            if (isHuyaHistoryEpg && curr.time.contains(" - ")) {
                                String[] parts = curr.time.split(" - ");
                                String startPart = parts[0].trim();
                                String endPart = parts.length > 1 ? parts[1].trim() : "23:59";
                                if (!endPart.contains(":") || endPart.equals("直播中")) endPart = "23:59";
                                epgEndTimeMap.put(curr, endPart);
                                curr.time = startPart;
                            } else if (i + 1 < data.size())
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
                if (!finalData.isEmpty()) {
                    lvEpg.post(() -> lvEpg.setSelection(selectedPosition));
                }
                scrollToCurrentProgram(finalData);
            });
        });
    }

    private void scrollToCurrentProgram(List<Channel.EpgItem> epgList) {
        if (epgList == null || epgList.isEmpty() || selectDayIndex != 0) {  // 🟢 今天才滚动到当前节目 (今天位置=0)
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

                        // 🟢 严格遵守用户：只改虎牙的，自己的不要动
                        boolean isHuyaChannel = (currentChannel.isTogetherWatch()
                                || currentChannel.getHuyaRoomId() > 0
                                || liveUrl.contains("huya.com") || liveUrl.contains("huya.cn"));

                        if (isHuyaChannel) {
                            // 虎牙不支持PLTV→TVOD playseek拼接（不是运营商IPTV直播）
                            // 用户说"随时可以调用回放" — 这里退化为"切回该频道直播"，
                            // 让用户从当前直播点继续观看
                            if (ctx instanceof MainActivity) {
                                MainActivity activity = (MainActivity) ctx;
                                ChannelPanelController controller = activity.getChannelPanelController();
                                if (controller != null) {
                                    if (controller.isPanelOpen()) controller.hidePanel();
                                    activity.setCatchUpMode(false);
                                    activity.showExoController();
                                    // 直接用原频道播放虎牙流（会走playHuyaStream，重新获取最新签名）
                                    activity.mPlayerManager.playUrl(liveUrl, currentChannel.getName(), currentChannel);
                                }
                            }
                            Toast.makeText(ctx, "虎牙直播源，已为您切换到该频道直播：" + item.title, Toast.LENGTH_LONG).show();
                            return;
                        }

                        // —— 非虎牙频道（用户自己的CCTV/卫视等）完全保留原有 playseek 逻辑 ——
                        Calendar playDay = Calendar.getInstance();
                        playDay.add(Calendar.DAY_OF_YEAR, dayIndex);   // 🟢 修正偏移：0=今天(+0) 1=明天(+1) ... 6=周一(+6)
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
                            ChannelPanelController controller = activity.getChannelPanelController();
                            if (controller != null && controller.isPanelOpen()) {
                                controller.hidePanel();
                            }
                            activity.setCatchUpMode(true);
                            activity.showExoController();
                            activity.mPlayerManager.playUrl(catchUrl);
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
                holder.tv_dayName.setTypeface(null, Typeface.BOLD);
                holder.tv_time.setTypeface(null, Typeface.BOLD);
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(COLOR_BG_BLUE);
            } else {
                holder.tv_dayName.setTextColor(COLOR_WHITE);
                holder.tv_time.setTextColor(COLOR_GRAY);
                holder.tv_title.setTextColor(COLOR_WHITE);
                holder.tv_dayName.setTypeface(null, Typeface.NORMAL);
                holder.tv_time.setTypeface(null, Typeface.NORMAL);
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            if (dayIndex == 0) {
                // 🟢 今天(0) → 按时间点判断是否已过
                if (currentNowStr == null) currentNowStr = getNow();
                try {
                    if (item.time != null) {
                        isPast = item.time.compareTo(currentNowStr) < 0;
                    }
                } catch (Exception ignored) {}
            }
            // dayIndex >= 1 (明天及以后) → isPast = false (默认值)，全部都是预约

            ItemActionTag tag = new ItemActionTag();
            tag.item = item;
            tag.key = key;
            tag.isPast = isPast;
            holder.tv_action.setTag(tag);
            holder.tv_action.setOnClickListener(actionClickListener);

            if (dayIndex == 0) {
                // 🟢 今天(0) → 播放中/回看/预约 三态按钮
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
                // 🟢 明天及以后(>=1) → 预约按钮
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
