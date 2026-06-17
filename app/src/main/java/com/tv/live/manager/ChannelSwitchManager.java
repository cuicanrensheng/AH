package com.tv.live.manager;

import com.tv.live.Channel;
import java.util.List;

public class ChannelSwitchManager {

    private static ChannelSwitchManager instance;
    private List<Channel> channelList;
    private int currentIndex = 0;
    private boolean isSwitching = false;

    private ChannelSwitchManager() {}

    public static ChannelSwitchManager getInstance() {
        if (instance == null) {
            instance = new ChannelSwitchManager();
        }
        return instance;
    }

    public void setChannelList(List<Channel> list) {
        this.channelList = list;
    }

    public void setCurrentIndex(int index) {
        if (channelList == null || channelList.isEmpty()) {
            currentIndex = 0;
            return;
        }
        if (index < 0) index = 0;
        if (index >= channelList.size()) index = channelList.size() - 1;
        currentIndex = index;
    }

    // 上一台
    public int prev() {
        if (channelList == null || channelList.isEmpty() || isSwitching) return currentIndex;
        isSwitching = true;
        currentIndex--;
        if (currentIndex < 0) {
            currentIndex = channelList.size() - 1;
        }
        // 切换完成后解锁
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> isSwitching = false, 100);
        return currentIndex;
    }

    // 下一台
    public int next() {
        if (channelList == null || channelList.isEmpty() || isSwitching) return currentIndex;
        isSwitching = true;
        currentIndex++;
        if (currentIndex >= channelList.size()) {
            currentIndex = 0;
        }
        // 切换完成后解锁
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> isSwitching = false, 100);
        return currentIndex;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }
}
