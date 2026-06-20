package com.tv.live.widget;

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
import com.tv.live.SettingsActivity;

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
 * EPG 节目单包装器
 * 
 * 【职责】
 * 1. 按日期筛选 EPG 节目单
 * 2. 计算每个节目的结束时间
 * 3. 标记当前正在播放的节目
 * 4. 处理节目单的选中状态和焦点
 * 5. 处理回看、预约等按钮点击事件
 * 
 * 【2026-06-20 修改说明】
 * 修改了回看按钮的点击事件里获取播放器的方式
 * 
 * 【为什么要改？】
 * 原来的代码直接访问 ((MainActivity) ctx).mPlayerManager，
 * 但是 mPlayerManager 是 private 的，外部类不能直接访问，会编译报错。
 * 
 * 【修改方案】
 * 通过 MainActivity.getInstance().getPlayerManager() 来获取播放器管理器，
 * 这是标准的封装写法，不直接暴露 private 成员变量。
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

        // ✅ 改成 false，item 不需要获取焦点
        lvEpg.setItemsCanFocus(false);
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (parent.getAdapter() != null) {
                    ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        registerReminderReceiver();
    }

    /**
     * 刷新指定日期的节目单
     * 
     * @param currentChannel 当前频道
     * @param channelSourceList 所有频道列表
     * @param dateIndex 日期索引（0=今天，1=明天，以此类推）
     */
    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) {
            SettingsActivity.log("【EPG包装】❌ refresh被调用，但currentChannel为空");
            return;
        }

        SettingsActivity.log("【EPG包装】🔄 开始刷新，频道：" + currentChannel.getName() 
                + "，日期索引：" + dateIndex);

        playingIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();

        new Thread(() -> {
            List<Channel.EpgItem> epgList;
            try {
                epgList = new ArrayList<>(EpgManager.getInstance().getEpg(currentChannel.getName()));
            } catch (Exception e) {
                SettingsActivity.log("【EPG包装】获取EPG异常：" + e.getMessage());
                epgList = new ArrayList<>();
            }

            SettingsActivity.log("【EPG包装】📋 原始节目数：" + epgList.size());

            if (epgList.size() > 0) {
                Set<String> dayNames = new HashSet<>();
                for (Channel.EpgItem item : epgList) {
                    dayNames.add(item.dayName);
                }
                SettingsActivity.log("【EPG包装】📅 EPG包含日期：" + dayNames);
            }

            List<Channel.EpgItem> data = new ArrayList<>();

            if (epgList != null && !epgList.isEmpty()) {
                // ✅ 计算目标日期 + 对应的周几（全部双重兼容）
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

                SettingsActivity.log("【EPG包装】🎯 目标日期：" + targetDay
                        + "，对应周几：" + weekDay
                        + (targetWeekDay != null ? "，兼容匹配：" + targetDay + " 或 " + targetWeekDay : ""));

                // ✅ 双重兼容筛选：匹配目标日期 或 对应的周几
                int matchCount = 0;
                for (Channel.EpgItem item : epgList) {
                    if (item.dayName == null) continue;
                    String dayName = item.dayName.trim();
                    boolean match = targetDay.equals(dayName);
                    if (!match && targetWeekDay != null) {
                        match = targetWeekDay.equals(dayName);
                    }
                    if (match) {
                        data.add(item);
                        matchCount++;
                    }
                }

                SettingsActivity.log("【EPG包装】✅ 筛选后节目数：" + matchCount);

                // 按时间排序
                Collections.sort(data, Comparator.comparing(o -> o.time));

                // 计算结束时间 + 标记播放中
                String now = getNow();
                Channel.EpgItem playing = null;

                for (int i = 0; i < data.size(); i++) {
                    Channel.EpgItem curr = data.get(i);

                    if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-")) {
                        curr.time = curr.time.split("-")[0].trim();
                    }

                    if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                        if (i + 1 < data.size()) {
                            Channel.EpgItem next = data.get(i + 1);
                            epgEndTimeMap.put(curr, next.time.contains("-") 
                                    ? next.time.split("-")[0].trim() : next.time);
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

                // 把正在播放的节目移到最前面
                if (playing != null && playingIndex > 0) {
                    data.remove(playing);
                    data.add(0, playing);
                    playingIndex = 0;
                }
            }

            // 主线程更新UI
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;

            ((MainActivity) context).runOnUiThread(() -> {
                SettingsActivity.log("【EPG包装】📱 主线程更新UI，节目数：" + finalData.size());

                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }

                if (playingIndex >= 0) {
                    lvEpg.setSelection(playingIndex);
                    selectedPosition = playingIndex;
                } else {
                    lvEpg.setSelection(0);
                    selectedPosition = 0;
                }

                adapter.notifyDataSetChanged();
                SettingsActivity.log("【EPG包装】✅ UI更新完成");
            });
        }).start();
    }

    /**
     * 判断当前时间是否在开始时间和结束时间之间
     */
    private boolean isTimeBetween(String now, String start, String end) {
        try {
            if (now == null || start == null || end == null) return false;
            return now.contains(":") && start.contains(":") && end.contains(":")
                    && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 给时间加一小时（用于计算最后一个节目的结束时间）
     */
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

    /**
     * 获取当前时间（HH:mm 格式）
     */
    private String getNow() {
        return String.format("%02d:%02d",
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE));
    }

    /**
     * 注册节目提醒广播接收器
     */
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

    // ====================================================================
    // EPG 列表适配器
    // ====================================================================

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

            // 设置节目信息
            holder.tv_dayName.setText(item.dayName);
            holder.tv_time.setText(item.time + "-" + endTime);
            holder.tv_title.setText(item.title);

            // ================================================================
            // 选中状态和焦点状态的 UI 样式
            // ================================================================
            boolean isSelected = (position == selectedPosition || item.isPlaying);

            if (isSelected) {
                // ✅ 选中状态：蓝色文字 + 标题加粗 + 浅蓝色背景
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (convertView.isFocused()) {
                // ✅ 焦点状态：蓝色文字 + 稍深一点的蓝色背景
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(0x4440A9FF);
            } else {
                // ✅ 未选中状态：原来的颜色 + 透明背景
                holder.tv_dayName.setTextColor(Color.WHITE);
                holder.tv_time.setTextColor(Color.LTGRAY);
                holder.tv_title.setTextColor(Color.WHITE);
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            // ================================================================
            // 节目状态按钮（播放中/回看/预约）
            // ================================================================
            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            try { isPast = item.time.compareTo(getNow()) < 0; } catch (Exception ignored) {}

            if (item.isPlaying) {
                // 正在播放：橙色按钮，不可点击
                holder.tv_action.setText("播放中");
                holder.tv_action.setBackgroundColor(0xFFFF9800);
                holder.tv_action.setEnabled(false);

            } else if (isPast) {
                // 已过的节目：灰色按钮，点击回看
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

                        // 计算回看的开始时间
                        Calendar playDay = Calendar.getInstance();
                        playDay.add(Calendar.DAY_OF_YEAR, dayIndex);
                        String[] startHm = item.time.split(":");
                        Calendar startCal = (Calendar) playDay.clone();
                        startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startHm[0].trim()));
                        startCal.set(Calendar.MINUTE, Integer.parseInt(startHm[1].trim()));
                        startCal.set(Calendar.SECOND, 0);

                        // 计算回看的结束时间
                        String[] endHm = endTime.split(":");
                        Calendar endCal = (Calendar) playDay.clone();
                        endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endHm[0].trim()));
                        endCal.set(Calendar.MINUTE, Integer.parseInt(endHm[1].trim()));
                        endCal.set(Calendar.SECOND, 0);

                        // 格式化成完整时间字符串
                        String startStr = sdfFull.format(startCal.getTime());
                        String endStr = sdfFull.format(endCal.getTime());

                        // 拼接回看地址（PLTV 换成 TVOD，加上 playseek 参数）
                        String catchUrl = liveUrl.contains("PLTV") 
                                ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                        catchUrl += catchUrl.contains("?") 
                                ? "&playseek=" + startStr + "-" + endStr 
                                : "?playseek=" + startStr + "-" + endStr;

                        // ============================================================
                        // ✅ 修改：获取播放器管理器并播放回看地址
                        // 【原来的代码】
                        // ((MainActivity) ctx).mPlayerManager.playUrl(catchUrl);
                        //
                        // 【为什么报错？】
                        // mPlayerManager 是 MainActivity 的 private 成员变量，
                        // 外部类不能直接访问 private 变量，会编译报错。
                        //
                        // 【修改方案】
                        // 1. 通过 MainActivity.getInstance() 获取 MainActivity 单例
                        // 2. 调用 public 的 getPlayerManager() 方法获取播放器管理器
                        // 3. 调用 playUrl() 播放回看地址
                        //
                        // 【好处】
                        // 1. 符合封装原则：private 变量不直接暴露给外部
                        // 2. 更安全：可以在 getPlayerManager() 里加 null 判断
                        // 3. 更灵活：以后实现变了，只需要改 getPlayerManager()
                        //
                        // 【修改后的代码】
                        // MainActivity.getInstance().getPlayerManager().playUrl(catchUrl);
                        // ============================================================
                        MainActivity.getInstance().getPlayerManager().playUrl(catchUrl);

                        Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                    }
                });

            } else {
                // 未来的节目：绿色按钮，点击预约/取消预约
                holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                holder.tv_action.setBackgroundColor(bookedSet.contains(key) ? 0xFF607D8B : 0xFF4CAF50);
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
            TextView tv_dayName, tv_time, tv_title, tv_action;
        }
    }
}
