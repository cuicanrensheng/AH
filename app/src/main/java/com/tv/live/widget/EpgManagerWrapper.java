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
 * 【2026-06-21 优化：焦点优先样式 + 区分焦点和选中状态】
 *
 * 【三种状态说明】
 * 1. 焦点状态：白色文字 + 蓝色背景（遥控器焦点所在的项，最显眼）
 * 2. 选中状态：蓝色文字 + 透明背景（当前播放中的节目）
 * 3. 未选中状态：白色文字 + 透明背景（普通项）
 *
 * 【判断优先级】
 * 焦点状态 > 选中状态 > 未选中状态
 *
 * 【2026-06-21 优化：日志分类】
 * 【优化内容】
 * 1. 数据加载/处理相关的日志 → 播放日志（SettingsActivity.log）
 * 2. UI 更新相关的日志 → 操作日志（SettingsActivity.logOperation）
 * 3. 两种日志分开，互不混淆
 */
public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private int selectedPosition = 0;
    // ====================================================================
    // ✅ 新增：焦点位置变量
    // ====================================================================
    /** 当前焦点位置（遥控器移动到的位置） */
    private int focusedPosition = 0;
    private int playingIndex = -1;
    private int selectDayIndex = 0;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        // ✅ 改成 false，item 不需要获取焦点
        lvEpg.setItemsCanFocus(false);
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // ================================================================
        // ✅ 修改：遥控器焦点移动时只更新 focusedPosition，不更新 selectedPosition
        // ================================================================
        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                // 只更新焦点位置，不更新选中位置
                setFocusedPosition(pos);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        registerReminderReceiver();
    }

    // ====================================================================
    // ✅ 新增：焦点位置相关方法
    // ====================================================================
    /**
     * 设置焦点位置（遥控器移动时调用）
     */
    public void setFocusedPosition(int position) {
        this.focusedPosition = position;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * 获取当前焦点位置
     */
    public int getFocusedPosition() {
        return focusedPosition;
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
                SettingsActivity.logOperation("【EPG包装】📱 主线程更新UI，节目数：" + finalData.size());

                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }

                if (playingIndex >= 0) {
                    lvEpg.setSelection(playingIndex);
                    selectedPosition = playingIndex;
                    focusedPosition = playingIndex;
                } else {
                    lvEpg.setSelection(0);
                    selectedPosition = 0;
                    focusedPosition = 0;
                }
                adapter.notifyDataSetChanged();

                // ============================================================
                // ✅ 2026-06-21 修改：UI 更新完成 → 操作日志
                // ============================================================
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
