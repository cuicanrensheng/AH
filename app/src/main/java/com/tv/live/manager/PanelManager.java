package com.tv.live.manager;

import android.view.View;
import com.tv.live.Channel;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.EpgManagerWrapper;

import java.util.List;

/**
 * 面板管理类
 * 控制左侧频道面板、节目单的显示与隐藏
 */
public class PanelManager {

    private final View panelLayout;
    private final ChannelListManager channelListManager;
    private final EpgManagerWrapper epgManagerWrapper;

    // 保存当前选中的日期索引（关键修复）
    private int currentDateIndex = 0;

    public PanelManager(View panelLayout, ChannelListManager channelListManager, EpgManagerWrapper epgManagerWrapper) {
        this.panelLayout = panelLayout;
        this.channelListManager = channelListManager;
        this.epgManagerWrapper = epgManagerWrapper;
    }

    /**
     * 外部设置当前选中的日期（日期列表选中时调用）
     */
    public void setCurrentDateIndex(int index) {
        this.currentDateIndex = index;
    }

    /**
     * 开关面板：显示 / 隐藏
     */
    public void toggle(List<Channel> channelList, int currentIndex) {
        if (panelLayout.getVisibility() == View.VISIBLE) {
            panelLayout.setVisibility(View.GONE);
        } else {
            panelLayout.setVisibility(View.VISIBLE);

            // 关键修复：打开面板时，使用【当前选中的日期】刷新节目单
            if (channelList != null && currentIndex >= 0 && currentIndex < channelList.size()) {
                Channel currentChannel = channelList.get(currentIndex);
                epgManagerWrapper.refresh(currentChannel, channelList, currentDateIndex);
            }
        }
    }
}
