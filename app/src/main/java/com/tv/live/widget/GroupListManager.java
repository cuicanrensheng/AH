package com.tv.live.widget;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.tv.live.Channel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GroupListManager {
    private final ListView lvGroup;
    private final Context context;
    private List<String> groupList;
    private int selectedPosition = 0;
    private ArrayAdapter<String> adapter;
    // 分组点击回调，通知外部更新频道列表
    private OnGroupClickListener listener;

    public interface OnGroupClickListener {
        void onGroupClick(String groupName, int position);
    }

    public void setOnGroupClickListener(OnGroupClickListener listener) {
        this.listener = listener;
    }

    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;
        lvGroup.setItemsCanFocus(true);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // ✅ 内部处理点击事件，自己更新选中状态，确保高亮一定跟着变
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                setSelectedPosition(position);
                if (listener != null && groupList != null && position >= 0 && position < groupList.size()) {
                    listener.onGroupClick(groupList.get(position), position);
                }
            }
        });

        // 遥控器选择时也同步更新
        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        Set<String> groupSet = new HashSet<>();
        for (Channel c : channelSourceList) groupSet.add(c.getGroup());
        groupList = new ArrayList<>(groupSet);

        adapter = new ArrayAdapter<String>(lvGroup.getContext(), android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);

                if (position == selectedPosition) {
                    // 选中状态：蓝色 + 加粗
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(0x3340A9FF);
                } else {
                    // 未选中状态：白色 + 常规
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return view;
            }
        };
        lvGroup.setAdapter(adapter);
        // 默认选中第一个
        if (groupList.size() > 0) {
            setSelectedPosition(0);
        }
    }

    /**
     * 设置选中位置，立即刷新高亮
     */
    public void setSelectedPosition(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) {
            return;
        }
        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    public void onBackPressed() {}
}
