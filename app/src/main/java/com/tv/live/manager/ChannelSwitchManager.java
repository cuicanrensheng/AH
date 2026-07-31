package com.tv.live.manager;

import com.tv.live.Channel;
import java.util.List;

/**
 * 频道切换管理器
 * 
 * 【职责】
 * - 安全地管理当前频道索引
 * - 提供上一台/下一台切换方法
 * - 不包含任何阻塞锁，切换时机由调用方控制（如 ChannelPanelController）
 * 
 * 【优化说明】
 * 1. 移除内部阻塞锁（isSwitching + Handler），防止连续按键丢失。
 * 2. 添加空列表保护，避免空指针崩溃。
 * 3. 提供 release() 方法，在 Activity 销毁时主动清空引用，避免内存泄漏。
 */
public class ChannelSwitchManager {

    private static ChannelSwitchManager instance;
    private List<Channel> channelList;
    private int currentIndex = 0;

    private ChannelSwitchManager() {}

    public static ChannelSwitchManager getInstance() {
        if (instance == null) {
            instance = new ChannelSwitchManager();
        }
        return instance;
    }

    /**
     * 设置频道列表，并重置当前索引为 0（或保护性调整）
     */
    public void setChannelList(List<Channel> list) {
        this.channelList = list;
        // 如果列表为空或无效，强制将索引置为 0
        if (channelList == null || channelList.isEmpty()) {
            currentIndex = 0;
        } else {
            // 保持当前索引有效
            if (currentIndex >= channelList.size()) {
                currentIndex = channelList.size() - 1;
            }
            if (currentIndex < 0) {
                currentIndex = 0;
            }
        }
    }

    /**
     * 设置当前索引（带边界保护）
     */
    public void setCurrentIndex(int index) {
        if (channelList == null || channelList.isEmpty()) {
            currentIndex = 0;
            return;
        }
        if (index < 0) index = 0;
        if (index >= channelList.size()) index = channelList.size() - 1;
        currentIndex = index;
    }

    /**
     * 上一台（无阻塞锁，调用方需自行控制频率）
     */
    public int prev() {
        if (channelList == null || channelList.isEmpty()) {
            return currentIndex;
        }
        currentIndex--;
        if (currentIndex < 0) {
            currentIndex = channelList.size() - 1;
        }
        return currentIndex;
    }

    /**
     * 下一台（无阻塞锁，调用方需自行控制频率）
     */
    public int next() {
        if (channelList == null || channelList.isEmpty()) {
            return currentIndex;
        }
        currentIndex++;
        if (currentIndex >= channelList.size()) {
            currentIndex = 0;
        }
        return currentIndex;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * 释放资源，清空列表，并重置单例（建议在 MainActivity.onDestroy 中调用）
     */
    public void release() {
        channelList = null;
        instance = null;
    }
}
