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
 * EPG 节目单包装管理器
 *
 * 【职责】
 * 1. 包装 EpgManager，提供 UI 层可用的数据
 * 2. 按日期筛选节目单
 * 3. 计算节目结束时间
 * 4. 标记当前播放中的节目
 * 5. 管理 EPG 列表的显示和更新
 *
 * 【2026-06-21 优化：日志分类】
 * 【优化内容】
 * 1. 数据加载/处理相关的日志 → 播放日志（SettingsActivity.log）
 * 2. UI 更新相关的日志 → 操作日志（SettingsActivity.logOperation）
 * 3. 两种日志分开，互不混淆
 *
 * 【日志分类说明】
 * - ✅ 播放日志（log）：EPG 数据获取、筛选、解析等数据处理相关
 * - ✅ 操作日志（logOperation）：主线程更新 UI、刷新列表等界面操作相关
 * 
 * 【2026-06-24 修复：多余焦点 + 固定在首位】
 * 【修复的问题】
 * 1. 多余焦点：播放中的节目和选中的节目同时高亮，看起来有两个焦点
 * 2. 固定在首位：每次刷新数据都自动跳回播放中的节目（第一位）
 * 
 * 【修复方案】
 * 1. 区分三种状态：选中（有背景）、播放中（只有文字颜色）、普通（白色）
 * 2. 只有第一次加载时自动选中播放中的节目，后续刷新保留用户选中位置
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
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        registerReminderReceiver();
    }
    /**
     * 刷新指定日期的节目单
     *
     * 【说明】
     * 异步获取 EPG 数据，筛选指定日期的节目，计算结束时间，
     * 最后在主线程更新 UI。
     *
     * @param currentChannel 当前频道
     * @param channelSourceList 频道列表
     * @param dateIndex 日期索引（0=今天，1=明天...）
     */
    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) {
            // ✅ 保留：数据异常 → 播放日志
            SettingsActivity.log("【EPG包装】❌ refresh被调用，但currentChannel为空");
            return;
        }
        // ✅ 保留：数据处理开始 → 播放日志
        SettingsActivity.log("【EPG包装】🔄 开始刷新，频道：" + currentChannel.getName() + "，日期索引：" + dateIndex);
        playingIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();
        new Thread(() -> {
            List<Channel.EpgItem> epgList;
            try {
                epgList = new ArrayList<>(EpgManager.getInstance().getEpg(currentChannel.getName()));
            } catch (Exception e) {
                // ✅ 保留：数据异常 → 播放日志
                SettingsActivity.log("【EPG包装】获取EPG异常：" + e.getMessage());
                epgList = new ArrayList<>();
            }
            // ✅ 保留：数据统计 → 播放日志
            SettingsActivity.log("【EPG包装】📋 原始节目数：" + epgList.size());
            if (epgList.size() > 0) {
                Set<String> dayNames = new HashSet<>();
                for (Channel.EpgItem item : epgList) {
                    dayNames.add(item.dayName);
                }
                // ✅ 保留：数据统计 → 播放日志
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
                // ✅ 保留：数据筛选 → 播放日志
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
                // ✅ 保留：数据筛选结果 → 播放日志
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
                            epgEndTimeMap.put(curr, next.time.contains("-") ? next.time.split("-")[0].trim() : next.time);
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
            // 主线程更新UI
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                // ============================================================
                // ✅ 2026-06-21 修改：UI 更新 → 操作日志
                // ============================================================
                // 【为什么改？】
                // 主线程更新 UI 是界面操作，属于操作日志，
                // 不应该混进"解析 & 播放日志"里。
                SettingsActivity.logOperation("【EPG包装】📱 主线程更新UI，节目数：" + finalData.size());
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }
                // ====================================================================
                // ✅ 2026-06-24 修复：只有第一次加载时才跳到播放中的节目
                // ====================================================================
                // 【修复的问题】
                // 原来每次刷新数据都会自动把选中位置跳到播放中的节目（第一位），
                // 导致用户按上下键移动焦点后，又自动跳回第一位，看起来像"固定在首位"。
                // 
                // 【修改逻辑】
                // 只有第一次加载（adapter 为 null）时，才自动选中播放中的节目。
                // 后续刷新保留用户当前选中的位置，但确保不越界。
                if (adapter == null) {
                    // 第一次加载：默认选中播放中的节目
                    if (playingIndex >= 0) {
                        selectedPosition = playingIndex;
                    } else {
                        selectedPosition = 0;
                    }
                } else {
                    // 后续刷新：确保选中位置不越界
                    if (selectedPosition >= finalData.size()) {
                        selectedPosition = Math.max(0, finalData.size() - 1);
                    }
                }
                lvEpg.setSelection(selectedPosition);
                adapter.notifyDataSetChanged();
                // ============================================================
                // ✅ 2026-06-21 修改：UI 更新完成 → 操作日志
                // ============================================================
                // 【为什么改？】
                // UI 更新完成是界面操作的结果，属于操作日志，
                // 不应该混进"解析 & 播放日志"里。
                SettingsActivity.logOperation("【EPG包装】✅ UI更新完成");
            });
        }).start();
    }
    /**
     * 判断时间是否在区间内
     *
     * @param now 当前时间
     * @param start 开始时间
     * @param end 结束时间
     * @return 是否在区间内
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
     * 时间加一小时
     *
     * @param hm 时间字符串（HH:mm）
     * @return 加一小时后的时间
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
     * 获取当前时间（HH:mm）
     *
     * @return 当前时间字符串
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
    /**
     * EPG 节目单列表适配器
     *
     * 【说明】
     * 每个 item 包含：日期、时间、节目名称、操作按钮（回看/预约/播放中）
     */
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
        /**
         * 更新数据
         *
         * @param currentChannel 当前频道
         * @param list 节目列表
         * @param dayIndex 日期索引
         */
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
            holder.tv_time.setText(item.time + "-" + endTime);
            holder.tv_title.setText(item.title);
            // ====================================================================
            // ✅ 2026-06-24 修复：区分三种状态，去掉多余焦点
            // ====================================================================
            // 【修复的问题】
            // 原来播放中的节目和选中的节目都显示同样的高亮样式，
            // 导致出现两个高亮项（多余焦点），用户分不清哪个是当前选中的。
            // 
            // 【新的样式规范】
            // 1. 选中状态（焦点在这一项）：浅蓝色背景 + 蓝色文字 + 加粗
            // 2. 播放中状态（不是当前选中）：蓝色文字 + 透明背景 + 加粗（只标记，不抢焦点）
            // 3. 普通状态：白色文字 + 透明背景
            // 
            // 【为什么这样改？】
            // 播放中的节目只需要用蓝色文字标记一下就行，
            // 不需要背景色，这样就不会和选中态混淆了。
            // 只有当前焦点所在的项才显示浅蓝色背景，用户一眼就能看出焦点在哪。
            if (position == selectedPosition) {
                // ⭐ 选中状态（焦点在这）：浅蓝色背景 + 蓝色文字 + 加粗
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (item.isPlaying) {
                // ✅ 播放中（但不是当前焦点）：蓝色文字 + 透明背景 + 加粗
                // 只标记是正在播放的节目，不显示背景色，避免和选中态混淆
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            } else {
                // 普通状态：白色文字 + 透明背景
                holder.tv_dayName.setTextColor(Color.WHITE);
                holder.tv_time.setTextColor(Color.LTGRAY);
                holder.tv_title.setTextColor(Color.WHITE);
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }
            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            try { isPast = item.time.compareTo(getNow()) < 0; } catch (Exception ignored) {}
            if (item.isPlaying) {
                // 播放中
                holder.tv_action.setText("播放中");
                holder.tv_action.setBackgroundColor(0xFFFF9800);
                holder.tv_action.setEnabled(false);
            } else if (isPast) {
                // 已结束 → 回看
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
                        String catchUrl = liveUrl.contains("PLTV") ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                        catchUrl += catchUrl.contains("?") ? "&playseek=" + startStr + "-" + endStr : "?playseek=" + startStr + "-" + endStr;
                        ((MainActivity) ctx).mPlayerManager.playUrl(catchUrl);
                        Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                // 未开始 → 预约
                holder.tv_action.setText("预约");
                holder.tv_action.setBackgroundColor(0xFF4CAF50);
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
        /**
         * ViewHolder 模式
         */
        private class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }
    }
}
