package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.R;
import com.tv.live.SettingsActivity;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * EPG 节目单包装管理器
 *
 * 【2026-06-21 修改：统一三种状态高亮样式（焦点优先）】
 * 【样式规则】
 * 焦点 > 选中 > 普通
 * - 焦点：白色文字 + 蓝色背景（最显眼，遥控器停在哪里）
 * - 选中：蓝色文字 + 透明背景（当前正在播放的节目）
 * - 普通：白色文字 + 透明背景
 */
public class EpgManagerWrapper {
    private Context context;
    private ListView listView;
    private List<Channel.EpgItem> epgList = new ArrayList<>();
    private EpgAdapter adapter;

    // ====================================================================
    // ✅ 新增：焦点位置和选中位置分开记录
    // ====================================================================
    /** 当前焦点位置（遥控器移动到的位置） */
    private int focusedPosition = 0;
    /** 当前选中位置（当前正在播放的节目） */
    private int selectedPosition = 0;

    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.CHINA);

    public EpgManagerWrapper(Context context, ListView listView) {
        this.context = context;
        this.listView = listView;
        adapter = new EpgAdapter();
        listView.setAdapter(adapter);
        initListeners();
    }

    private void initListeners() {
        // 选中事件：只移动焦点，不选中
        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SettingsActivity.logOperation("【EPG】焦点移动：" + position);
                // ✅ 只更新焦点位置，不更新选中位置
                setFocusedPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做处理
            }
        });
    }

    // ====================================================================
    // ✅ 新增：设置焦点位置
    // ====================================================================
    public void setFocusedPosition(int position) {
        if (position < 0 || position >= epgList.size()) return;
        this.focusedPosition = position;
        adapter.notifyDataSetChanged();
    }

    public int getFocusedPosition() {
        return focusedPosition;
    }

    public void setSelectedPosition(int position) {
        if (position < 0 || position >= epgList.size()) return;
        this.selectedPosition = position;
        // 选中时也同步移动焦点到选中项
        this.focusedPosition = position;
        adapter.notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void refresh(Channel channel, List<Channel> allChannels, int dateIndex) {
        // 【操作日志】UI 更新操作 → 操作日志
        SettingsActivity.logOperation("【EPG包装】📱 主线程更新UI，节目数：" + epgList.size());

        epgList.clear();

        if (channel == null || channel.getEpgList() == null) {
            adapter.notifyDataSetChanged();
            return;
        }

        // 筛选指定日期的节目
        List<Channel.EpgItem> allItems = channel.getEpgList();
        for (Channel.EpgItem item : allItems) {
            // 简单的日期筛选逻辑（根据实际 EPG 格式调整）
            epgList.add(item);
        }

        // 按开始时间排序
        Collections.sort(epgList, new Comparator<Channel.EpgItem>() {
            @Override
            public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                try {
                    Date t1 = timeFormat.parse(o1.getStartTime());
                    Date t2 = timeFormat.parse(o2.getStartTime());
                    return t1.compareTo(t2);
                } catch (ParseException e) {
                    return 0;
                }
            }
        });

        // 找到当前正在播放的节目（默认选中第一个）
        int currentIndex = findCurrentProgramIndex();
        this.selectedPosition = currentIndex;
        this.focusedPosition = currentIndex;

        adapter.notifyDataSetChanged();

        // 【操作日志】UI 更新完成 → 操作日志
        SettingsActivity.logOperation("【EPG包装】✅ UI更新完成");
    }

    /**
     * 找到当前正在播放的节目索引
     */
    private int findCurrentProgramIndex() {
        if (epgList.isEmpty()) return 0;

        String nowTime = timeFormat.format(new Date());
        try {
            Date now = timeFormat.parse(nowTime);
            for (int i = 0; i < epgList.size(); i++) {
                Channel.EpgItem item = epgList.get(i);
                Date start = timeFormat.parse(item.getStartTime());
                Date end = timeFormat.parse(item.getEndTime());
                // 如果当前时间在节目时间段内
                if (now.compareTo(start) >= 0 && now.compareTo(end) < 0) {
                    return i;
                }
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return 0;
    }

    // ====================================================================
    // 适配器
    // ====================================================================
    private class EpgAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return epgList.size();
        }

        @Override
        public Object getItem(int position) {
            return epgList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_epg, parent, false);
                holder = new ViewHolder();
                holder.tvTime = convertView.findViewById(R.id.tv_time);
                holder.tvProgram = convertView.findViewById(R.id.tv_program);
                holder.tvStatus = convertView.findViewById(R.id.tv_status);
                // 去掉系统默认焦点高亮
                convertView.setDefaultFocusHighlightEnabled(false);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            Channel.EpgItem item = epgList.get(position);
            holder.tvTime.setText(item.getStartTime() + " - " + item.getEndTime());
            holder.tvProgram.setText(item.getTitle());

            // 判断节目状态
            boolean isPlaying = (position == selectedPosition);
            if (isPlaying) {
                holder.tvStatus.setText("直播中");
                holder.tvStatus.setVisibility(View.VISIBLE);
            } else {
                holder.tvStatus.setVisibility(View.GONE);
            }

            // ====================================================================
            // ✅ 修改：统一三种状态样式（焦点优先）
            // ====================================================================
            // 判断优先级：焦点 > 选中 > 普通
            if (position == focusedPosition) {
                // ── 焦点状态：白色文字 + 蓝色背景（最显眼）──
                holder.tvTime.setTextColor(Color.WHITE);
                holder.tvProgram.setTextColor(Color.WHITE);
                holder.tvStatus.setTextColor(Color.WHITE);
                holder.tvProgram.setTypeface(Typeface.DEFAULT);
                convertView.setBackgroundColor(Color.parseColor("#40A9FF"));
            } else if (position == selectedPosition) {
                // ── 选中状态：蓝色文字 + 透明背景（次之）──
                holder.tvTime.setTextColor(Color.parseColor("#40A9FF"));
                holder.tvProgram.setTextColor(Color.parseColor("#40A9FF"));
                holder.tvStatus.setTextColor(Color.parseColor("#40A9FF"));
                holder.tvProgram.setTypeface(Typeface.DEFAULT);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            } else {
                // ── 普通状态：白色文字 + 透明背景 ──
                holder.tvTime.setTextColor(Color.WHITE);
                holder.tvProgram.setTextColor(Color.WHITE);
                holder.tvStatus.setTextColor(Color.WHITE);
                holder.tvProgram.setTypeface(Typeface.DEFAULT);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            return convertView;
        }

        class ViewHolder {
            TextView tvTime;
            TextView tvProgram;
            TextView tvStatus;
        }
    }
}
