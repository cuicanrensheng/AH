package com.tv.live.manager;

import com.tv.live.Channel;

import java.util.List;

/**
 * 频道切换管理器
 *
 * 【功能】
 * 1. 管理频道列表和当前索引
 * 2. 上/下切换频道（分组内循环）
 * 3. 切换防抖（100ms）
 * 4. 换台反转功能 ← ✅ 新增
 *
 * 【换台反转说明】
 * 反转关闭（默认）：
 *   - 上键 = 上一台（prev）= 索引-1
 *   - 下键 = 下一台（next）= 索引+1
 *
 * 反转开启：
 *   - 上键 = 下一台（next）= 索引+1
 *   - 下键 = 上一台（prev）= 索引-1
 *
 * 【为什么把反转逻辑放在这里？】
 * 因为 ChannelSwitchManager 是专门管理频道切换的，
 * 反转是切换行为的一部分，应该放在这里统一管理。
 * 这样 MainActivity 只需要调用 switchUp() 和 switchDown()，
 * 不用关心内部是加还是减。
 */
public class ChannelSwitchManager {
    private static ChannelSwitchManager instance;

    private List<Channel> channelList;
    private int currentIndex = 0;
    private boolean isSwitching = false;

    // ====================================================================
    // ✅ 新增：换台反转标志
    // ====================================================================
    /**
     * 是否开启换台反转
     * 默认 false = 不反转
     */
    private boolean isReverse = false;

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

    // ====================================================================
    // ✅ 新增：设置换台反转
    // ====================================================================
    /**
     * 设置是否开启换台反转
     * @param reverse true = 开启反转，false = 关闭反转
     *
     * 【调用时机】
     * 1. App 启动时，从 SP 读取设置后调用
     * 2. 从设置页面返回时，重新读取设置后调用
     */
    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
    }

    /**
     * 获取当前反转状态
     * @return true = 已开启反转
     */
    public boolean isReverse() {
        return isReverse;
    }

    // ====================================================================
    // ✅ 新增：按上键切换（考虑反转）
    // ====================================================================
    /**
     * 按上键时调用
     *
     * 【逻辑】
     * - 反转关闭：上键 = 上一台（索引-1）
     * - 反转开启：上键 = 下一台（索引+1）
     *
     * @return 切换后的频道索引
     */
    public int switchUp() {
        if (isReverse) {
            // 反转开启：上键 = 下一台
            return next();
        } else {
            // 反转关闭：上键 = 上一台
            return prev();
        }
    }

    // ====================================================================
    // ✅ 新增：按下键切换（考虑反转）
    // ====================================================================
    /**
     * 按下键时调用
     *
     * 【逻辑】
     * - 反转关闭：下键 = 下一台（索引+1）
     * - 反转开启：下键 = 上一台（索引-1）
     *
     * @return 切换后的频道索引
     */
    public int switchDown() {
        if (isReverse) {
            // 反转开启：下键 = 上一台
            return prev();
        } else {
            // 反转关闭：下键 = 下一台
            return next();
        }
    }

    // 上一台（索引-1）
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

    // 下一台（索引+1）
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
