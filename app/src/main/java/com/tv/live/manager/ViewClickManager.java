package com.tv.live.manager;

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
    private final EpgManagerWrapper epgManagerWrapper;
    private final GroupListManager groupListManager;
    private final DateListManager dateListManager;
    private final ChannelListManager channelListManager;

    public ViewClickManager(MainActivity activity,
                            List<Channel> channelSourceList,
                            List<Channel> currentGroupChannelList,
                            PlayControlManager playControlManager,
                            EpgManagerWrapper epgManagerWrapper,
                            GroupListManager groupListManager,
                            DateListManager dateListManager,
                            ChannelListManager channelListManager) {
        this.activity = activity;
        this.channelSourceList = channelSourceList;
        this.currentGroupChannelList = currentGroupChannelList;
        this.playControlManager = playControlManager;
        this.epgManagerWrapper = epgManagerWrapper;
        this.groupListManager = groupListManager;
        this.dateListManager = dateListManager;
        this.channelListManager = channelListManager;
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
            String group = groupListManager.getGroupList().get(position);
            activity.setNowSelectGroup(group);

            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (group.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannels(currentGroupChannelList, activity.getCurrentPlayIndex());
        });
    }

    // ✅ 修复 4：正常可用的点击事件
    public void bindChannelClick(ListView lvChannelList) {
        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            activity.currentPlayIndex = position;
            playControlManager.playChannel(position, channelSourceList);
        });
    }
}
