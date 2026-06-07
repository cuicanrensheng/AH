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

    // 面板根布局
    private final View panelLayout;
    // 频道列表管理器
    private final ChannelListManager channelListManager;
    // 节目单管理器
    private final EpgManagerWrapper epgManagerWrapper;
    // 当前选中的日期索引
    private int currentSelectedDateIndex = 0;

    /**
     * 构造方法
     * @param panelLayout 整个左侧面板布局
     * @param channelListManager 频道列表管理
     * @param epgManagerWrapper 节目单管理
     */
    public PanelManager(View panelLayout, ChannelListManager channelListManager, EpgManagerWrapper epgManagerWrapper) {
        this.panelLayout = panelLayout;
        this.channelListManager = channelListManager;
        this.epgManagerWrapper = epgManagerWrapper;
    }

    /**
     * 设置当前选中日期索引
     * @param index 日期下标
     */
    public void setSelectedDateIndex(int index) {
        this.currentSelectedDateIndex = index;
    }

    /**
     * 开关面板：显示 / 隐藏
     * @param channelList 频道列表
     * @param currentIndex 当前播放的频道下标
     */
    public void toggle(List<Channel> channelList, int currentIndex) {
        if (panelLayout.getVisibility() == View.VISIBLE) {
            // 如果已经显示，则隐藏
            panelLayout.setVisibility(View.GONE);
        } else {
            // 如果隐藏，则显示，并刷新节目单
            panelLayout.setVisibility(View.VISIBLE);

            // 自动刷新当前频道的节目单
            if (channelList != null && currentIndex >= 0 && currentIndex < channelList.size()) {
                Channel currentChannel = channelList.get(currentIndex);
                epgManagerWrapper.refresh(currentChannel, channelList, currentSelectedDateIndex);
            }
        }
    }
}
