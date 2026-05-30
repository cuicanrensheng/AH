package com.tv.live.manager;

import android.view.View;
import com.tv.live.Channel;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.EpgManagerWrapper;
import java.util.List;

public class PanelManager {

    private final View panelLayout;
    private final ChannelListManager channelListManager;
    private final EpgManagerWrapper epgManagerWrapper;

    public PanelManager(View panelLayout, ChannelListManager channelListManager, EpgManagerWrapper epgManagerWrapper) {
        this.panelLayout = panelLayout;
        this.channelListManager = channelListManager;
        this.epgManagerWrapper = epgManagerWrapper;
    }

    public void toggle(List<Channel> channelList, int currentIndex) {
        if (panelLayout.getVisibility() == View.VISIBLE) {
            panelLayout.setVisibility(View.GONE);
        } else {
            panelLayout.setVisibility(View.VISIBLE);
            channelListManager.setChannels(channelList, currentIndex);
            if (channelList != null && !channelList.isEmpty()) {
                Channel ch = channelList.get(currentIndex);
                epgManagerWrapper.refresh(ch, channelList);
            }
        }
    }
}
