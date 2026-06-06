package com.tv.live.manager;

import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;
import java.util.List;

public class ViewClickManager {
    private final MainActivity activity;
    private final List<Channel> channelSourceList;
    private final List<Channel> currentGroupChannelList;
    private final PlayControlManager playControlManager;
    private final PanelManager panelManager;
    private final EpgManagerWrapper epgManagerWrapper;
    private final GroupListManager groupListManager;
    private final DateListManager dateListManager;
    private final ChannelListManager channelListManager;

    public ViewClickManager(MainActivity activity,
                            List<Channel> sourceList,
                            List<Channel> groupList,
                            PlayControlManager playControl,
                            PanelManager panelManager,
                            EpgManagerWrapper epgManager,
                            GroupListManager groupManager,
                            DateListManager dateManager,
                            ChannelListManager channelManager) {
        this.activity = activity;
        this.channelSourceList = sourceList;
        this.currentGroupChannelList = groupList;
        this.playControlManager = playControl;
        this.panelManager = panelManager;
        this.epgManagerWrapper = epgManager;
        this.groupListManager = groupManager;
        this.dateListManager = dateManager;
        this.channelListManager = channelManager;
    }

    public void bindDateClick(ListView lvDate) {
        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            activity.setCurrentSelectedDateIndex(position);
            playControlManager.setCurrentDateIndex(position);
            if (!channelSourceList.isEmpty()) {
                Channel curr = channelSourceList.get(activity.getCurrentPlayIndex());
                epgManagerWrapper.refresh(curr, channelSourceList, position);
            }
        });
    }

    public void bindGroupClick(ListView lvGroup) {
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
            String group = groupListManager.getCurrentGroup(position);
            activity.setNowSelectGroup(group);

            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (group.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, group, activity.getCurrentPlayIndex());
        });
    }

    public void bindChannelClick() {
        channelListManager.setOnChannelClickListener(filterPos -> {
            if (filterPos >= 0 && filterPos < currentGroupChannelList.size()) {
                Channel target = currentGroupChannelList.get(filterPos);
                int global = channelSourceList.indexOf(target);
                if (global != -1) {
                    playControlManager.playChannel(global, channelSourceList);
                    panelManager.toggle(channelSourceList, activity.getCurrentPlayIndex());
                }
            }
        });
    }
}
