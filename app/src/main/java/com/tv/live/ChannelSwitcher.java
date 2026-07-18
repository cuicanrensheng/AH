package com.tv.live;

import java.util.List;

public class ChannelSwitcher {

    private static ChannelSwitcher instance;
    private List<Channel> channelList;
    private int currentIndex = 0;

    private ChannelSwitcher() {}

    public static ChannelSwitcher getInstance() {
        if (instance == null) {
            instance = new ChannelSwitcher();
        }
        return instance;
    }

    // 设置频道列表（必须在加载直播源后调用一次）
    public void setChannelList(List<Channel> list) {
        this.channelList = list;
        if (channelList == null || channelList.isEmpty()) {
            currentIndex = 0;
        }
    }

    // 获取当前索引
    public int getCurrentIndex() {
        return currentIndex;
    }

    // 设置当前索引（比如用户点击列表选台时）
    public void setCurrentIndex(int index) {
        if (channelList == null || channelList.isEmpty()) {
            currentIndex = 0;
            return;
        }
        if (index < 0) index = 0;
        if (index >= channelList.size()) index = channelList.size() - 1;
        this.currentIndex = index;
    }

    // 上一个频道（稳定不乱跳）
    public int prev() {
        if (channelList == null || channelList.isEmpty()) return 0;
        currentIndex--;
        if (currentIndex < 0) {
            currentIndex = channelList.size() - 1;
        }
        return currentIndex;
    }

    // 下一个频道（稳定不乱跳）
    public int next() {
        if (channelList == null || channelList.isEmpty()) return 0;
        currentIndex++;
        if (currentIndex >= channelList.size()) {
            currentIndex = 0;
        }
        return currentIndex;
    }
}
