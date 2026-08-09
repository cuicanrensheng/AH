package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分组列表管理器（已恢复遥控器焦点）
 */
public class GroupListManager {

    private final ListView lvGroup;
    private Context context;
    private List<String> groupDisplayList;
    private List<String> groupNameList;
    private int selectedPosition = 0;
    private int focusedPosition = -1;
    private ArrayAdapter<String> adapter;
    private OnGroupSelectedListener listener;

    public static final String GROUP_ALL = "全部";
    /** 🟢 虎牙TogetherWatch里星爷/英叔/喜剧/动作/港片/武侠/热血/警匪/悬疑/盗墓 合并后的大组名 */
    public static final String GROUP_HUYA_CLASSIC_FILM = "经典影视";
    /** 要被合并到【经典影视】的关键词（仅限TogetherWatch频道，外部m3u虎牙分组不动） */
    private static final java.util.regex.Pattern HUYA_CLASSIC_MERGE_PATTERN =
            java.util.regex.Pattern.compile("星爷|英叔|喜剧|搞笑|动作|港片|武侠|热血|警匪|悬疑|盗墓");

    private static final int COLOR_BLUE_TEXT = 0xFF40A9FF;
    private static final int COLOR_BLUE_BG = 0x3340A9FF;
    private static final int COLOR_WHITE_TEXT = 0xFFFFFFFF;

    /**
     * 🟢 分组归一化（严格遵守用户3条原则）：
     *  【原则1】外部直播源(m3u文件)里自带的任何分组（包括"虎牙-游戏""虎牙-娱乐"等）都原样保留，不要动。
     *          → 判断依据：!isTogetherWatch() && huyaRoomId<=0 → 直接返回原始group
     *  【原则2】虎牙TogetherWatch自己的频道，按 HuyaTogetherWatchManager.java 关键词分好的子分组为基础：
     *          → 但其中【星爷/英叔/喜剧/动作/港片/武侠/热血/警匪/悬疑/盗墓】这10个题材
     *            统一合并成一个"经典影视"大组，方便用户快速找到。
     *  【原则3】TogetherWatch里其他未命中的题材（电影_漫威宇宙/电影_科幻星际/综艺_音乐/
     *          动漫_国漫/剧集_庆余年/剧集_武林外传/解说_热门解说等）原样保留，不合并。
     */
    public static String getNormalizedGroup(Channel c) {
        if (c == null) return "未分类";
        String raw = c.getGroup();
        if (raw == null) return "未分类";
        String g = raw.trim();
        if (g.isEmpty()) return "未分类";

        // ✅ 只对虎牙TogetherWatch频道做分组合并，外部m3u分组100%原样保留
        boolean isTogetherWatchChannel = c.isTogetherWatch() || c.getHuyaRoomId() > 0;
        if (isTogetherWatchChannel && HUYA_CLASSIC_MERGE_PATTERN.matcher(g).find()) {
            return GROUP_HUYA_CLASSIC_FILM;
        }
        return g;
    }

    /** 仅基于原始组名做归一化（用于没有Channel对象的场景，此版本不做TogetherWatch题材合并） */
    public static String getNormalizedGroup(String rawGroup) {
        if (rawGroup == null) return "未分类";
        String g = rawGroup.trim();
        return g.isEmpty() ? "未分类" : g;
    }

    public interface OnGroupSelectedListener {
        void onGroupSelected(int position, String groupName);
    }

    public void setOnGroupSelectedListener(OnGroupSelectedListener listener) {
        this.listener = listener;
    }

    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;

        // ✅ 恢复焦点
        lvGroup.setItemsCanFocus(true);
        lvGroup.setFocusable(true);
        lvGroup.setFocusableInTouchMode(true);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // ✅ 恢复 OnItemSelectedListener
        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPosition = position;
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 保持当前选中
            }
        });

        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            setSelectedPosition(position);
        });
    }

    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 🟢 虎牙分组合并：用归一化后的分组名去重
        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel c : channelSourceList) {
            groupSet.add(getNormalizedGroup(c));
        }
        List<String> originalGroups = new ArrayList<>(groupSet);

        groupNameList = new ArrayList<>();
        groupNameList.add(GROUP_ALL);
        groupNameList.addAll(originalGroups);

        groupDisplayList = new ArrayList<>();
        groupDisplayList.add(GROUP_ALL + " (" + channelSourceList.size() + ")");
        for (String group : originalGroups) {
            int count = 0;
            for (Channel c : channelSourceList) {
                if (group.equals(getNormalizedGroup(c))) {
                    count++;
                }
            }
            // 🟢 只有"全部"分组显示数量，其他分组纯文字不显示频道数
            groupDisplayList.add(group);
        }

        adapter = new ArrayAdapter<String>(lvGroup.getContext(), android.R.layout.simple_list_item_1, groupDisplayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    LayoutInflater inflater = LayoutInflater.from(context);
                    convertView = inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
                    TextView tv = convertView.findViewById(android.R.id.text1);
                    holder = new ViewHolder();
                    holder.tv = tv;
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (holder == null || holder.tv == null) {
                    LayoutInflater inflater = LayoutInflater.from(context);
                    convertView = inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
                    TextView tv = convertView.findViewById(android.R.id.text1);
                    holder = new ViewHolder();
                    holder.tv = tv;
                    convertView.setTag(holder);
                }

                TextView tv = holder.tv;
                if (tv == null) {
                    return convertView;
                }

                String text = groupDisplayList.get(position);
                tv.setText(text);

                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);

                boolean isSelected = (position == selectedPosition);
                android.util.Log.d("GroupList", "getView pos:" + position + ", selectedPos:" + selectedPosition + ", isSelected:" + isSelected);

                if (isSelected) {
                    tv.setTextColor(COLOR_BLUE_TEXT);
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(COLOR_BLUE_BG);
                } else {
                    tv.setTextColor(COLOR_WHITE_TEXT);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return convertView;
            }
        };
        lvGroup.setAdapter(adapter);
        selectedPosition = 0;
        focusedPosition = 0;
        adapter.notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        if (groupDisplayList == null || adapter == null) return;
        if (position < 0 || position >= groupDisplayList.size()) return;
        if (selectedPosition == position) return;

        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
        if (listener != null) {
            listener.onGroupSelected(position, groupNameList.get(position));
        }
    }

    public String getCurrentGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return "";
        return groupNameList.get(position);
    }

    public int getGroupPosition(String groupName) {
        if (groupNameList == null || groupName == null) return 0;
        for (int i = 0; i < groupNameList.size(); i++) {
            if (groupName.equals(groupNameList.get(i))) {
                return i;
            }
        }
        return 0;
    }

    public boolean isAllGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return false;
        return GROUP_ALL.equals(groupNameList.get(position));
    }

    public boolean isSpecialGroup(int position) {
        return position == 0;
    }

    public void onBackPressed() {}

    private static class ViewHolder {
        TextView tv;
    }

    public void release() {
        if (adapter != null) {
            adapter.clear();
            adapter = null;
        }
        if (lvGroup != null) {
            lvGroup.setAdapter(null);
            lvGroup.setOnItemClickListener(null);
            lvGroup.setOnItemSelectedListener(null);
        }
        if (groupDisplayList != null) {
            groupDisplayList.clear();
            groupDisplayList = null;
        }
        if (groupNameList != null) {
            groupNameList.clear();
            groupNameList = null;
        }
        listener = null;
        context = null;
    }
}
