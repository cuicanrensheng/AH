package com.tv.live;

public class ChannelSwitchManager {
    private static ChannelSwitchManager instance;
    private int currentIndex = 0;
    private int size = 0;

    public static ChannelSwitchManager getInstance() {
        if (instance == null) {
            instance = new ChannelSwitchManager();
        }
        return instance;
    }

    public void setChannelListSize(int size) {
        this.size = size;
    }

    public void setCurrentIndex(int index) {
        this.currentIndex = index;
    }

    public int next() {
        currentIndex++;
        if (currentIndex >= size) currentIndex = 0;
        return currentIndex;
    }

    public int prev() {
        currentIndex--;
        if (currentIndex < 0) currentIndex = size - 1;
        return currentIndex;
    }
}
