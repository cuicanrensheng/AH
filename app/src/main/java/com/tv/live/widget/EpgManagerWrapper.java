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
import com.tv.live.Settings;

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
 * EPG节目单UI包装管理器 EpgManagerWrapper
 * 顶层职责：对底层EpgManager做UI层封装，隔离数据层与界面逻辑
 * ========================================
 * 核心业务职责清单
 * 1. 接收外部频道、日期参数，异步调用EpgManager拉取原始节目数据
 * 2. 按「今天/明天/后天/周X」双重兼容规则筛选对应日期节目
 * 3. 自动计算每档节目结束时间，存入全局缓存Map供Adapter读取
 * 4. 根据系统当前时间匹配正在播放节目，自动置顶展示
 * 5. 管理ListView选中位置、焦点状态，修复双高亮冲突问题
 * 6. 维护预约节目集合，处理预约/取消预约逻辑
 * 7. 内置自定义Adapter渲染节目条目，区分三种UI样式：选中/播放中/普通
 * 8. 注册全局节目提醒广播，收到提醒弹窗Toast提示用户
 * ========================================
 * 版本优化记录
 * 【2026-06-21 日志分层优化】
 *  优化点：拆分两类日志，区分数据处理日志 & UI操作日志，便于排查卡顿/数据异常
 *  日志区分规则：
 *   SettingsActivity.log() → 数据加载、筛选、时间计算、网络解析（后台数据行为）
 *   SettingsActivity.logOperation() → 主线程刷新ListView、UI渲染（界面操作行为）
 * 【2026-06-24 双焦点+固定首位BUG修复】
 *  BUG1：播放中节目+选中条目同时蓝色背景，出现两个焦点视觉冲突
 *  修复方案：样式分层
 *      选中条目：蓝文字+加粗+半透蓝背景（焦点标识）
 *      播放中条目：仅蓝文字加粗、无背景（仅标记播放，不抢占焦点）
 *      普通条目：白色常规文字透明背景
 *  BUG2：每次刷新列表强制滚动到播放节目，用户上下切换后自动回弹，体验极差
 *  修复方案：仅首次加载节目时自动定位播放节目；后续刷新保留用户手动选中位置，仅做边界越界保护
 * ========================================
 * 线程规范
 *  1. 数据拉取、筛选、时间计算全部放在子线程，避免主线程阻塞卡顿
 *  2. 所有View、Adapter刷新操作强制切回MainActivity主线程执行
 * ========================================
 * 依赖关系
 *  数据底层：EpgManager（全局单例，存储全频道原始EPG数据）
 *  UI载体：外部传入ListView控件
 *  播放器：MainActivity.mPlayerManager（回看功能调用）
 *  广播动作：ACTION_REMINDER 节目预约提醒广播
 */
public class EpgManagerWrapper {
    // 页面节目单列表控件（外部传入，持有引用）
    private final ListView lvEpg;
    // 页面上下文（绑定MainActivity）
    private final Context context;
    // 自定义节目列表适配器，负责条目渲染
    private EpgAdapter adapter;
    // 预约节目存储集合
    // Key规则：频道名称_条目position，保证多频道预约不冲突
    private final Set<String> bookedSet = new HashSet<>();
    // 节目结束时间缓存映射表
    // Key：单条Epg节目对象  Value：格式化HH:mm结束时间
    // 作用：复用时间计算结果，避免每条item重复解析计算
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    // 节目预约提醒广播Action标识
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    // ListView当前用户选中条目下标
    private int selectedPosition = 0;
    // 当日正在播放节目在列表中的下标，无播放节目为-1
    private int playingIndex = -1;
    // 当前页面选中日期索引：0今天、1明天、2后天、3及以上对应周几
    private int selectDayIndex = 0;

    /**
     * 构造方法
     * @param context 页面上下文 MainActivity
     * @param lvEpg 承载EPG列表的ListView控件
     */
    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;

        // 关闭Item自身获取焦点，所有焦点统一由ListView管理，消除焦点冲突
        lvEpg.setItemsCanFocus(false);
        // 设置单选模式，同一时间只能选中一个条目
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // ListView条目选中监听：用户上下按键切换时更新选中下标并刷新UI样式
        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                // 更新全局选中下标
                selectedPosition = pos;
                // 适配器存在则刷新所有条目样式
                if (parent.getAdapter() != null) {
                    ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 无选中条目场景，无需处理
            }
        });

        // 注册节目预约提醒广播接收器
        registerReminderReceiver();
    }

    /**
     * 核心对外方法：刷新指定频道、指定日期的EPG节目列表
     * 执行全流程：子线程拉取数据→筛选日期→排序→计算播放状态→主线程更新ListView
     * @param currentChannel 当前正在播放的频道对象
     * @param channelSourceList 全频道列表（预留扩展参数，当前未使用）
     * @param dateIndex 日期下标 0=今天，1=明天，2=后天，>=3对应星期
     */
    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        // 入参判空：频道为空直接终止流程，打印数据日志
        if (currentChannel == null) {
            Settings.log("【EPG包装】❌ refresh被调用，但currentChannel为空");
            return;
        }
        Settings.log("【EPG包装】🔄 开始刷新，频道：" + currentChannel.getName() + "，日期索引：" + dateIndex);

        // 重置全局临时变量
        playingIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear(); // 清空上次节目结束时间缓存

        // 开启子线程处理耗时数据操作（网络/数据解析不能阻塞UI）
        new Thread(() -> {
            List<Channel.EpgItem> epgList;
            try {
                // 调用底层EpgManager获取该频道全部原始节目
                epgList = new ArrayList<>(EpgManager.getInstance().getEpg(currentChannel.getName()));
            } catch (Exception e) {
                // 获取节目异常，捕获并初始化空列表
                Settings.log("【EPG包装】获取EPG异常：" + e.getMessage());
                epgList = new ArrayList<>();
            }
            Settings.log("【EPG包装】📋 原始节目数：" + epgList.size());

            // 打印当前频道所有日期标签，用于调试日期匹配问题
            if (epgList.size() > 0) {
                Set<String> dayNames = new HashSet<>();
                for (Channel.EpgItem item : epgList) {
                    dayNames.add(item.dayName);
                }
                Settings.log("【EPG包装】📅 EPG包含日期：" + dayNames);
            }

            // 存放筛选后符合当前日期的节目
            List<Channel.EpgItem> data = new ArrayList<>();
            if (epgList != null && !epgList.isEmpty()) {
                // ===================== 步骤1：计算目标日期匹配规则 =====================
                String targetDay;
                String targetWeekDay = null;
                Calendar cal = Calendar.getInstance();
                // 向后偏移对应天数
                cal.add(Calendar.DAY_OF_YEAR, dateIndex);
                // 获取星期枚举（1周日，2周一...7周六）
                int weekNum = cal.get(Calendar.DAY_OF_WEEK);
                String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                String weekDay = weekMap[weekNum - 1];

                // 区分日期描述文案
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
                    // 超过后天直接显示周几
                    targetDay = weekDay;
                }
                Settings.log("【EPG包装】🎯 目标日期：" + targetDay
                        + "，对应周几：" + weekDay
                        + (targetWeekDay != null ? "，兼容匹配：" + targetDay + " 或 " + targetWeekDay : ""));

                // ===================== 步骤2：双重兼容筛选节目 =====================
                // 兼容两种数据源格式：部分源存「今天」、部分源直接存「周一」
                int matchCount = 0;
                for (Channel.EpgItem item : epgList) {
                    if (item.dayName == null) continue;
                    String dayName = item.dayName.trim();
                    // 优先匹配中文日期（今天/明天）
                    boolean match = targetDay.equals(dayName);
                    // 不匹配则尝试匹配对应星期
                    if (!match && targetWeekDay != null) {
                        match = targetWeekDay.equals(dayName);
                    }
                    if (match) {
                        data.add(item);
                        matchCount++;
                    }
                }
                Settings.log("【EPG包装】✅ 筛选后节目数：" + matchCount);

                // ===================== 步骤3：按节目开始时间升序排序 =====================
                Collections.sort(data, Comparator.comparing(o -> o.time));

                // ===================== 步骤4：计算每档节目结束时间 + 标记播放状态 =====================
                String now = getNow(); // 获取系统当前HH:mm时间
                Channel.EpgItem playing = null; // 缓存正在播放的节目对象
                for (int i = 0; i < data.size(); i++) {
                    Channel.EpgItem curr = data.get(i);
                    // 切割原始time字段，只保留开始时间（原始格式 12:00-13:00）
                    if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-")) {
                        curr.time = curr.time.split("-")[0].trim();
                    }

                    // 填充结束时间缓存
                    if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                        // 非最后一档：下一档节目开始时间 = 当前节目结束时间
                        if (i + 1 < data.size()) {
                            Channel.EpgItem next = data.get(i + 1);
                            epgEndTimeMap.put(curr, next.time.contains("-") ? next.time.split("-")[0].trim() : next.time);

                        } else {
                            // 列表最后一档：自动+1小时作为结束时间
                            epgEndTimeMap.put(curr, addOneHour(curr.time));
                        }
                    }

                    // 重置播放标记
                    curr.isPlaying = false;
                    String currEnd = epgEndTimeMap.get(curr);
                    // 判断当前时间是否处于节目区间内
                    if (isTimeBetween(now, curr.time, currEnd)) {
                        curr.isPlaying = true;
                        playing = curr;
                        playingIndex = i;
                    }
                }

                // ===================== 步骤5：播放节目置顶 =====================
                if (playing != null && playingIndex > 0) {
                    data.remove(playing);
                    data.add(0, playing);
                    playingIndex = 0; // 置顶后下标变为0
                }
            }

            // 子线程数据处理完成，切换主线程刷新UI
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                Settings.logOperation("【EPG包装】📱 主线程更新UI，节目数：" + finalData.size());

                // 初始化/更新适配器
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData);
                }

                // ===================== 修复：仅首次加载自动定位播放节目 =====================
                if (adapter == null) {
                    // 第一次渲染列表，自动选中正在播放节目
                    if (playingIndex >= 0) {
                        selectedPosition = playingIndex;
                    } else {
                        selectedPosition = 0;
                    }
                } else {
                    // 用户已操作列表，保留原有选中位置，仅防止下标越界
                    if (selectedPosition >= finalData.size()) {
                        selectedPosition = Math.max(0, finalData.size() - 1);
                    }
                }

                // 滚动到目标条目并刷新样式
                lvEpg.setSelection(selectedPosition);
                adapter.notifyDataSetChanged();
                Settings.logOperation("【EPG包装】✅ UI更新完成");
            });
        }).start();
    }

    /**
     * 时间区间判断工具
     * @param now 当前时间 HH:mm
     * @param start 节目开始时间 HH:mm
     * @param end 节目结束时间 HH:mm
     * @return true 当前时间在播放区间内
     */
    private boolean isTimeBetween(String now, String start, String end) {
        try {
            // 空值直接返回false
            if (now == null || start == null || end == null) return false;
            // 字符串比较时间大小，格式必须带冒号
            return now.contains(":") && start.contains(":") && end.contains(":")
                    && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     给HH:mm时间增加一小时
     @param hm 原始时分字符串
     @return 加一小时后的标准时分
     */
    private String addOneHour(String hm) {
        try {
            if (hm == null || !hm.contains(":")) return "23:59";
            hm = hm.trim();
            // 去除原始带的结束时间后缀
            if (hm.contains("-")) hm = hm.split("-")[0].trim();
            String[] arr = hm.split(":");
            int hour = Integer.parseInt(arr[0].trim());
            int minute = Integer.parseInt(arr[1].trim());
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, hour);
            calendar.set(Calendar.MINUTE, minute);
            // 增加60分钟=1小时
            calendar.add(Calendar.MINUTE, 60);
            // 补零格式化输出 08:05而非8:5
            return String.format("%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
        } catch (Exception e) {
            // 解析失败兜底23:59
            return "23:59";
        }
    }

    /**
     获取系统当前时间 HH:mm 格式
     */
    private String getNow() {
        Calendar calendar = Calendar.getInstance();
        return String.format("%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE));
    }

    /**
     注册节目预约提醒广播
     收到广播弹出Toast展示节目名称
     */
    private void registerReminderReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REMINDER.equals(intent.getAction())) {
                    String programTitle = intent.getStringExtra("title");
                    Toast.makeText(context, "节目提醒：" + programTitle, Toast.LENGTH_LONG).show();
                }
            }
        };
        // 注册广播过滤器，只接收预约提醒动作
        context.registerReceiver(receiver, new IntentFilter(ACTION_REMINDER));
    }

    // ===================== 内部自定义节目列表适配器 =====================
    /**
     EPG列表适配器
     职责：复用Item视图、填充数据、区分三种UI样式、绑定回看/预约点击事件
     优化方案：ViewHolder缓存控件，避免频繁findViewById减少卡顿
     */
    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final Context ctx;
        private Channel currentChannel;
        private List<Channel.EpgItem> list;
        private final LayoutInflater inflater;
        // 日期索引，区分今天/其他日期（用于判断回看权限）
        private int dayIndex;
        // 回看时间格式化器：生成yyyyMMddHHmmss标准时间戳
        private final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

        /**
         适配器构造
         @param ctx 页面上下文
         @param currentChannel 当前频道
         @param list 筛选完成的节目列表
         @param dayIndex 当前选中日期下标
         */
        public EpgAdapter(Context ctx, Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            super(ctx, R.layout.item_epg, list);
            this.ctx = ctx;
            this.currentChannel = currentChannel;
            this.list = list;
            this.inflater = LayoutInflater.from(ctx);
            this.dayIndex = dayIndex;
        }

        /**
         刷新适配器数据源
         @param currentChannel 最新频道对象
         @param list 最新筛选节目列表
         @param dayIndex 最新日期下标
         */
        public void setData(Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            this.currentChannel = currentChannel;
            this.list.clear();
            this.list.addAll(list);
            this.dayIndex = dayIndex;
            // 通知列表重绘
            notifyDataSetChanged();
        }

        /**
         Item渲染核心方法，复用convertView优化性能
         @param position 当前条目下标
         @param convertView 可复用Item布局
         @param parent 父ListView
         @return 填充完成的条目View
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            // 首次创建Item，加载布局并缓存控件
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_epg, parent, false);
                holder = new ViewHolder();
                holder.tv_dayName = convertView.findViewById(R.id.tv_dayName);
                holder.tv_time = convertView.findViewById(R.id.tv_time);
                holder.tv_title = convertView.findViewById(R.id.tv_title);
                holder.tv_action = convertView.findViewById(R.id.tv_action);
                convertView.setTag(holder);
            } else {
                // 复用缓存控件，无需重复查找
                holder = (ViewHolder) convertView.getTag();
            }

            // 下标越界保护
            if (position < 0 || position >= list.size()) {
                return convertView;
            }

            Channel.EpgItem item = list.get(position);
            String endTime = epgEndTimeMap.get(item);

            // 填充基础文本内容
            holder.tv_dayName.setText(item.dayName);
            holder.tv_time.setText(item.time + "-" + endTime);
            holder.tv_title.setText(item.title);

            // ===================== 三层UI样式区分（修复双高亮BUG） =====================
            // 1. 用户选中条目：蓝字加粗 + 半透蓝背景（焦点标识）
            // 2. 播放中但未选中：仅蓝色加粗文字，无背景（仅标记播放，不抢焦点）
            // 3. 普通节目：白色常规文字透明背景
            if (position == selectedPosition) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (item.isPlaying) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            } else {
                holder.tv_dayName.setTextColor(Color.WHITE);
                holder.tv_time.setTextColor(Color.LTGRAY);
                holder.tv_title.setTextColor(Color.WHITE);
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            // 生成预约唯一标识：频道名_条目下标，防止多频道预约冲突
            String bookKey = currentChannel.getName() + "_" + position;
            // 判断节目是否已经过期（用于区分回看/预约按钮）
            boolean isProgramPast = false;
            try {
                isProgramPast = item.time.compareTo(getNow()) < 0;
            } catch (Exception ignored) {}

            // ===================== 操作按钮逻辑分支 =====================
            if (item.isPlaying) {
                // 状态1：正在播放，按钮置灰不可点击，文字播放中
                holder.tv_action.setText("播放中");
                holder.tv_action.setBackgroundColor(0xFFFF9800);
                holder.tv_action.setEnabled(false);
                holder.tv_action.setOnClickListener(null);
            } else if (dayIndex == 0 && isProgramPast) {
                // 状态2：今日已过期节目 → 回看功能
                holder.tv_action.setText("回看");
                holder.tv_action.setBackgroundColor(0xFF607D8B);
                holder.tv_action.setEnabled(true);
                // 回看点击事件
                holder.tv_action.setOnClickListener(v -> {
                    try {
                        String playUrl = currentChannel.getPlayUrl();
                        if (TextUtils.isEmpty(playUrl)) {
                            Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // 构造回看日期日历
                        Calendar playDate = Calendar.getInstance();
                        playDate.add(Calendar.DAY_OF_YEAR, dayIndex);
                        // 解析节目开始时分
                        String[] startArr = item.time.split(":");
                        Calendar startCal = (Calendar) playDate.clone();
                        startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startArr[0].trim()));
                        startCal.set(Calendar.MINUTE, Integer.parseInt(startArr[1].trim()));
                        startCal.set(Calendar.SECOND, 0);
                        // 解析节目结束时分
                        String[] endArr = endTime.split(":");
                        Calendar endCal = (Calendar) playDate.clone();
                        endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endArr[0].trim()));
                        endCal.set(Calendar.MINUTE, Integer.parseInt(endArr[1].trim()));
                        endCal.set(Calendar.SECOND, 0);
                        // 格式化回看起止时间戳
                        String startStamp = sdfFull.format(startCal.getTime());
                        String endStamp = sdfFull.format(endCal.getTime());
                        // 替换直播标识为回看TVOD，拼接时间参数
                        String replayUrl = playUrl.contains("PLTV") ? playUrl.replace("PLTV", "TVOD") : playUrl;
                        if (replayUrl.contains("?")) {
                            replay += "&playseek=" + startStamp + "-" + endStamp;
                        } else {
                            replay += "?playseek=" + startStamp + "-" + endStamp;
                        }
                        // 调用播放器播放回看流
                        ((MainActivity) ctx).mPlayerManager.playUrl(replayUrl);
                        Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT);
                    } catch (Exception e) {
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT);
                    }
                });
            } else {
                // 状态3：未播出节目 → 预约/取消预约
                // 已预约显示「已预约」，未预约显示「预约」
                holder.tv_action.setText(bookedSet.contains(bookKey) ? "已预约" : "预约");
                // 已预约灰色背景，未预约绿色
                holder.tv_action.setBackgroundColor(bookedSet.contains(bookKey) ? 0xFF607D8B : 0xFF4CAF50);
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(v -> {
                    if (bookedSet.contains(bookKey)) {
                        // 取消预约
                        bookedSet.remove(bookKey);
                        Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT);
                    } else {
                        // 新增预约
                        bookedSet.add(bookKey);
                        Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT);
                    }
                    // 刷新列表更新按钮文字
                    notifyDataSetChanged();
                });
            }
            return convertView;
        }

        /**
         ViewHolder缓存类
         缓存条目内所有TextView，减少findViewById调用，滑动更流畅
         */
        private class ViewHolder {
            TextView tv_dayName;   // 日期标签（今天/周一）
            TextView tv_time;      // 节目时间段
            TextView tv_title;     // 节目名称
            TextView tv_action;     // 操作按钮（播放中/回看/预约）
        }
    }
}
