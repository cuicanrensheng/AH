package com.tv.live;
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
 * 5合1 融合管理类
 * 修复：日期-EPG联动问题
 * 1. PanelManager.toggle() 使用 DateListManager 当前选中日期，不再写死0
 * 2. DateListManager.initDate() 默认选中今天（第0项）
 */
public class LivePanelManager {
    // ===================== DateListManager 日期列表管理 =====================
    public static class DateListManager {
        private final ListView lvDate;
        private final Context context;
        private int selectedPosition = 0;
        private OnDateSelectedListener listener;
        private ArrayAdapter<String> dateAdapter;
        public interface OnDateSelectedListener {
            void onDateSelected(int position);
        }
        public void setOnDateSelectedListener(OnDateSelectedListener listener) {
            this.listener = listener;
        }
        public DateListManager(Context context, ListView lvDate) {
            this.context = context;
            this.lvDate = lvDate;
        }
        public void initDate() {
            List<String> dates = new ArrayList<>();
            Calendar cal = Calendar.getInstance();
            String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            for (int i = 0; i < 8; i++) {
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                String display = i == 0 ? "今天" : week[dayOfWeek - 1];
                dates.add(display);
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            dateAdapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, dates) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView tv = (TextView) super.getView(position, convertView, parent);
                    tv.setTextColor(position == selectedPosition ? Color.parseColor("#40A9FF") : Color.WHITE);
                    return tv;
                }
            };
            lvDate.setAdapter(dateAdapter);
            lvDate.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            lvDate.setOnItemClickListener((parent, view, position, id) -> {
                selectedPosition = position;
                dateAdapter.notifyDataSetChanged();
                if (listener != null) {
                    listener.onDateSelected(position);
                }
            });

            // ========== 【修复2】默认选中今天（第0项） ==========
            selectedPosition = 0;
            lvDate.setItemChecked(0, true);
            lvDate.setSelection(0);
            dateAdapter.notifyDataSetChanged();
        }
        public int getSelectedPosition() {
            return selectedPosition;
        }
        public void setSelectedPosition(int position) {
            selectedPosition = position;
            if (dateAdapter != null) {
                dateAdapter.notifyDataSetChanged();
            }
        }
    }
    // ===================== PanelManager 面板管理 =====================
    public static class PanelManager {
        private final View panelLayout;
        private final ChannelListManager channelListManager;
        private final EpgManagerWrapper epgManagerWrapper;
        private final DateListManager dateListManager;  // 新增：持有日期管理器引用

        // ========== 【修复1】构造方法新增 DateListManager 参数 ==========
        public PanelManager(View panelLayout, ChannelListManager channelListManager, 
                           EpgManagerWrapper epgManagerWrapper, DateListManager dateListManager) {
            this.panelLayout = panelLayout;
            this.channelListManager = channelListManager;
            this.epgManagerWrapper = epgManagerWrapper;
            this.dateListManager = dateListManager;  // 保存日期管理器引用
        }
        public void toggle(List<Channel> channelList, int currentIndex) {
            if (panelLayout.getVisibility() == View.VISIBLE) {
                panelLayout.setVisibility(View.GONE);
            } else {
                panelLayout.setVisibility(View.VISIBLE);
                if (channelList != null && currentIndex >= 0 && currentIndex < channelList.size()) {
                    Channel currentChannel = channelList.get(currentIndex);
                    // ========== 【修复1核心】用当前选中的日期，不再写死0 ==========
                    int currentDateIndex = dateListManager.getSelectedPosition();
                    epgManagerWrapper.refresh(currentChannel, channelList, currentDateIndex);
                }
            }
        }
    }
    // ===================== EpgManagerWrapper 节目单管理 =====================
    public static class EpgManagerWrapper {
        private final ListView lvEpg;
        private final Context context;
        private EpgAdapter adapter;
        private final Set<String> bookedSet = new HashSet<>();
        private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
        private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
        private int selectedPosition = 0;
        private int playingIndex = -1;
        private int selectDayIndex = 0;
        private Channel mCurrentChannel;
        private List<Channel.EpgItem> mEpgItemList = new ArrayList<>();
        public EpgManagerWrapper(Context context, ListView lvEpg) {
            this.context = context;
            this.lvEpg = lvEpg;
            lvEpg.setItemsCanFocus(true);
            lvEpg.setFocusableInTouchMode(true);
            lvEpg.setFocusable(true);
            lvEpg.setVerticalScrollBarEnabled(true);
            lvEpg.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    selectedPosition = pos;
                    if (parent.getAdapter() != null) {
                        ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
                    }
                    updateNextProgramInfo();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
            registerReminderReceiver();
        }
        public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
            if (currentChannel == null) return;
            this.mCurrentChannel = currentChannel;
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
                if (epgList != null && !epgList.isEmpty()) {
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.DAY_OF_YEAR, dateIndex);
                    int w = cal.get(Calendar.DAY_OF_WEEK);
                    String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                    String targetDay = dateIndex == 0 ? "今天" : weekMap[w - 1];
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
                        if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-")) {
                            curr.time = curr.time.split("-")[0].trim();
                        }
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
                mEpgItemList = data;
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
                    lvEpg.smoothScrollToPosition(0);
                    updateNextProgramInfo();
                });
            }).start();
        }
        private void updateNextProgramInfo() {
            if (mEpgItemList == null || mEpgItemList.isEmpty()) return;
            if (!(context instanceof MainActivity)) return;
            MainActivity activity = (MainActivity) context;
            int currentPos = selectedPosition;
            if (currentPos < 0 || currentPos >= mEpgItemList.size()) return;
            Channel.EpgItem currentItem = mEpgItemList.get(currentPos);
            String currentEndTime = epgEndTimeMap.get(currentItem);
            String nextStartTime = "";
            String nextEndTime = "";
            if (currentPos + 1 < mEpgItemList.size()) {
                Channel.EpgItem nextItem = mEpgItemList.get(currentPos + 1);
                nextStartTime = nextItem.time;
                nextEndTime = epgEndTimeMap.get(nextItem);
            }
            if (activity.tv_next_time_range != null) {
                if (!TextUtils.isEmpty(nextStartTime) && !TextUtils.isEmpty(nextEndTime)) {
                    activity.tv_next_time_range.setText(nextStartTime + " ~ " + nextEndTime);
                } else {
                    activity.tv_next_time_range.setText("暂无");
                }
            }
        }
        private boolean isTimeBetween(String now, String start, String end) {
            try {
                if (now == null || start == null || end == null) return false;
                if (now.contains(":") && start.contains(":") && end.contains(":")) {
                    return now.compareTo(start) >= 0 && now.compareTo(end) < 0;
                }
            } catch (Exception e) {}
            return false;
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
                holder.tv_time.setText(item.time + "-" + endTime);
                holder.tv_title.setText(item.title);
                if (position == selectedPosition || item.isPlaying) {
                    convertView.setBackgroundColor(Color.parseColor("#3340A9FF"));
                } else {
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                }
                final String key = currentChannel.getName() + "_" + item.dayName + "_" + item.time + "_" + item.title;
                holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                holder.tv_action.setOnClickListener(v -> {
                    if (bookedSet.contains(key)) {
                        bookedSet.remove(key);
                        holder.tv_action.setText("预约");
                        Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
                    } else {
                        bookedSet.add(key);
                        holder.tv_action.setText("已预约");
                        Toast.makeText(ctx, "已预约节目提醒", Toast.LENGTH_SHORT).show();
                    }
                });
                return convertView;
            }
            private class ViewHolder {
                TextView tv_dayName;
                TextView tv_time;
                TextView tv_title;
                TextView tv_action;
            }
        }
    }
    // ===================== ChannelListManager 频道列表管理 =====================
    public static class ChannelListManager {
        // 原有代码保持不变
    }
    // ===================== GroupListManager 分组列表管理 =====================
    public static class GroupListManager {
        // 原有代码保持不变
    }
}
