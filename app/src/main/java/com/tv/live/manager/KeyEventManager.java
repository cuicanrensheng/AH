package com.tv.live.manager;

import android.view.KeyEvent;

import com.tv.live.MainActivity;
import com.tv.live.SettingsActivity;

/**
 * 按键事件管理器
 *
 * 【职责】
 * 处理遥控器的按键事件，包括：
 * 1. 上下键：切换频道（带反转逻辑）
 * 2. OK键/确认键：打开/关闭频道面板
 * 3. Menu键：打开设置页面
 *
 * 【2026-06-20 修复：换台反转失效 + 详细操作日志】
 * 【问题原因】
 * 之前直接调用 activity.playPrev() 和 activity.playNext()，
 * 这两个是底层方法，不考虑反转设置，导致反转失效，而且没有日志很难排查。
 *
 * 【解决方案】
 * 1. 加上反转判断：调用 activity.isChannelReverse() 获取反转状态
 * 2. 加上详细的操作日志，记录是从 KeyEventManager 入口触发的
 *
 * 【日志效果】
 * 在设置页面的"操作日志"里可以看到：
 * - 按键是从哪个入口处理的（KeyEventManager / handleDirectionKey）
 * - 反转状态是什么
 * - 实际切台方向是什么
 */
public class KeyEventManager {

    private final MainActivity activity;

    public KeyEventManager(MainActivity activity) {
        this.activity = activity;
    }

    /**
     * 分发按键事件
     *
     * @param keyCode 按键码
     * @return 是否处理了按键（true=已处理）
     */
    public boolean dispatchKey(int keyCode) {
        switch (keyCode) {
            // ====================================================================
            // ✅ 上键：加上反转判断 + 操作日志
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_UP:
                // 记录入口日志：是 KeyEventManager 处理的上键
                SettingsActivity.logOperation("【按键】KeyEventManager 上键 → 反转状态：" 
                        + (activity.isChannelReverse() ? "开启" : "关闭"));
                
                if (activity.isChannelReverse()) {
                    // 反转开启：上键 = 下一台
                    activity.playNext();
                } else {
                    // 反转关闭：上键 = 上一台
                    activity.playPrev();
                }
                return true;

            // ====================================================================
            // ✅ 下键：加上反转判断 + 操作日志
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 记录入口日志：是 KeyEventManager 处理的下键
                SettingsActivity.logOperation("【按键】KeyEventManager 下键 → 反转状态：" 
                        + (activity.isChannelReverse() ? "开启" : "关闭"));
                
                if (activity.isChannelReverse()) {
                    // 反转开启：下键 = 上一台
                    activity.playPrev();
                } else {
                    // 反转关闭：下键 = 下一台
                    activity.playNext();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                SettingsActivity.logOperation("【按键】KeyEventManager OK键 → 切换面板");
                activity.togglePanel();
                return true;

            case KeyEvent.KEYCODE_MENU:
                SettingsActivity.logOperation("【按键】KeyEventManager Menu键 → 打开设置");
                activity.openSettings();
                return true;
        }
        return false;
    }
}
