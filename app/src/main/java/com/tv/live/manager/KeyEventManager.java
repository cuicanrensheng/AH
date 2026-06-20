package com.tv.live.manager;

import android.view.KeyEvent;

import com.tv.live.MainActivity;

/**
 * 按键事件管理器
 *
 * 【职责】
 * 处理遥控器的按键事件，包括：
 * 1. 上下键：切换频道（带反转逻辑）
 * 2. OK键/确认键：打开/关闭频道面板
 * 3. Menu键：打开设置页面
 *
 * 【2026-06-20 修复：换台反转失效】
 * 【问题原因】
 * 之前直接调用 activity.playPrev() 和 activity.playNext()，
 * 这两个是底层方法，不考虑反转设置，导致反转失效。
 *
 * 【修复方案】
 * 加上反转判断：调用 activity.isChannelReverse() 获取反转状态，
 * 根据反转状态决定调用 playPrev() 还是 playNext()。
 *
 * 【效果】
 * KeyEventManager 里的切台也会考虑反转设置，
 * 和 handleDirectionKey()、ChannelPanelController 保持一致。
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
     *
     * 【2026-06-20 修复：上下键加上反转判断】
     * 【原来的代码】
     * case KeyEvent.KEYCODE_DPAD_UP:
     *     activity.playPrev();
     *     return true;
     * case KeyEvent.KEYCODE_DPAD_DOWN:
     *     activity.playNext();
     *     return true;
     *
     * 【问题】
     * 直接调用 playPrev()/playNext()，不考虑反转设置，导致反转失效。
     *
     * 【修复后】
     * 先调用 activity.isChannelReverse() 判断反转状态，
     * 再决定调用 playPrev() 还是 playNext()。
     */
    public boolean dispatchKey(int keyCode) {
        switch (keyCode) {
            // ====================================================================
            // ✅ 修复：上键加上反转判断
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_UP:
                if (activity.isChannelReverse()) {
                    // 反转开启：上键 = 下一台
                    activity.playNext();
                } else {
                    // 反转关闭：上键 = 上一台
                    activity.playPrev();
                }
                return true;

            // ====================================================================
            // ✅ 修复：下键加上反转判断
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_DOWN:
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
                activity.togglePanel();
                return true;

            case KeyEvent.KEYCODE_MENU:
                activity.openSettings();
                return true;
        }
        return false;
    }
}
