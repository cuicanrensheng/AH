package com.tv.live.manager;

import android.view.View;
import com.tv.live.Channel;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.EpgManagerWrapper;
import java.util.List;

/**
 * 面板管理类
 * 控制左侧频道面板、节目单的显示与隐藏
 * ✅ 修复：打开面板强制跳回今天的bug
 * ✅ 新增：保持用户上次选中的日期
 */
public class PanelManager {

    // 面板根布局
    private final View panelLayout;
    // 频道列表管理器
    private final ChannelListManager channelListManager;
    // 节目单管理器
    private final EpgManagerWrapper epgManagerWrapper;

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
     * 开关面板：显示 / 隐藏
     * @param channelList 频道列表
     * @param currentIndex 当前播放的频道下标
     * @param dateIndex 当前选中的EPG日期索引（保持用户选择）
     */
    public void toggle(List<Channel> channelList, int currentIndex, int dateIndex) {
        if (panelLayout.getVisibility() == View.VISIBLE) {
            // 如果已经显示，则隐藏
            panelLayout.setVisibility(View.GONE);
        } else {
            // 如果隐藏，则显示
            panelLayout.setVisibility(View.VISIBLE);

            // 刷新频道列表选中状态
            if (channelList != null && currentIndex >= 0 && currentIndex < channelList.size()) {
                channelListManager.setChannels(channelList, currentIndex);
            }

            // ✅ 核心修复：使用用户选中的日期索引，不再硬编码0
            // 自动刷新当前频道的节目单，保持上次选中的日期
            if (channelList != null && currentIndex >= 0 && currentIndex < channelList.size()) {
                Channel currentChannel = channelList.get(currentIndex);
                epgManagerWrapper.refresh(currentChannel, channelList, dateIndex);
            }
        }
    }

    /**
     * 隐藏面板（单独方法，供返回键调用）
     */
    public void hide() {
        panelLayout.setVisibility(View.GONE);
    }

    /**
     * 显示面板
     * @param channelList 频道列表
     * @param currentIndex 当前播放索引
     * @param dateIndex 当前日期索引
     */
    public void show(List<Channel> channelList, int currentIndex, int dateIndex) {
        panelLayout.setVisibility(View.VISIBLE);
        if (channelList != null && currentIndex >= 0 && currentIndex < channelList.size()) {
            channelListManager.setChannels(channelList, currentIndex);
            Channel currentChannel = channelList.get(currentIndex);
            epgManagerWrapper.refresh(currentChannel, channelList, dateIndex);
        }
    }

    /**
     * 获取面板当前显示状态
     * @return true=显示，false=隐藏
     */
    public boolean isVisible() {
        return panelLayout.getVisibility() == View.VISIBLE;
    }
}
