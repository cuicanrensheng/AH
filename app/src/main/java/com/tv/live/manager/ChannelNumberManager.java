package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import com.tv.live.SettingsActivity;

/**
 * 数字选台管理器
 *
 * 【功能说明】
 * 统一管理数字选台的所有逻辑，包括：
 * 1. 数字按键输入处理
 * 2. 输入超时自动确认
 * 3. 频道号显示/隐藏控制
 * 4. 输入状态管理
 *
 * 【使用方式】
 * 1. 创建实例，传入回调监听器
 * 2. 在 onKeyDown 中调用 handleNumberKey() 处理数字按键
 * 3. 在 onBackPressed 中调用 cancelInput() 取消输入
 * 4. 在 onDestroy 中调用 release() 释放资源
 *
 * 【为什么要拆分？】
 * 数字选台是一个相对独立的功能模块，有自己的状态和逻辑。
 * 拆分出来后：
 * - MainActivity 更清爽，少了 ~100 行代码
 * - 数字选台逻辑集中管理，方便维护和测试
 * - 可以在其他地方复用（比如设置页面的数字输入）
 */
public class ChannelNumberManager {

    // ====================== 常量 ======================
    /** 数字选台超时时间（毫秒），2 秒没输入就自动确认 */
    private static final long CHANNEL_NUM_TIMEOUT = 2000;

    // ====================== 回调接口 ======================
    /**
     * 数字选台事件监听器
     *
     * 【作用】
     * 把数字选台的结果回调给外部（MainActivity），
     * 外部负责实际的频道切换和 UI 显示。
     *
     * 【为什么用回调而不是直接引用 MainActivity？】
     * 1. 解耦：ChannelNumberManager 不需要知道 MainActivity 的存在
     * 2. 可复用：其他 Activity 也可以用这个管理器
     * 3. 易测试：可以用 Mock 监听器测试逻辑
     */
    public interface OnChannelNumberListener {
        /**
         * 选中频道时回调
         *
         * @param channelIndex 频道索引（从 0 开始）
         */
        void onChannelSelected(int channelIndex);

        /**
         * 显示频道号（输入过程中实时更新）
         *
         * @param number 当前输入的数字字符串
         */
        void showChannelNumber(String number);

        /**
         * 隐藏频道号显示
         */
        void hideChannelNumber();
    }

    // ====================== 成员变量 ======================
    /** 回调监听器 */
    private final OnChannelNumberListener listener;
    /** 数字输入缓冲 */
    private final StringBuilder channelNumInput = new StringBuilder();
    /** 超时 Handler */
    private final Handler channelNumHandler = new Handler(Looper.getMainLooper());
    /** 是否启用数字选台 */
    private boolean enable = true;
    /** 总频道数（用于判断频道号是否有效） */
    private int totalChannelCount = 0;

    // ====================== 超时 Runnable ======================
    /**
     * 数字选台超时自动确认
     *
     * 【逻辑】
     * 用户输入数字后，2 秒内没有继续输入，就自动确认选台。
     * 这样用户不需要按确认键，输入完等一下就自动切台了。
     */
    private final Runnable channelNumConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            confirmChannelNum();
        }
    };

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     *
     * @param listener 回调监听器
     * @param enable   是否启用数字选台
     */
    public ChannelNumberManager(OnChannelNumberListener listener, boolean enable) {
        this.listener = listener;
        this.enable = enable;
    }

    // ====================== 公开方法 ======================

    /**
     * 设置是否启用数字选台
     *
     * @param enable true=启用，false=禁用
     */
    public void setEnable(boolean enable) {
        this.enable = enable;
        // 如果禁用了，并且正在输入，就取消输入
        if (!enable && isInputting()) {
            cancelInput();
        }
    }

    /**
     * 设置总频道数
     *
     * 【作用】
     * 确认选台时判断频道号是否有效，
     * 防止输入一个不存在的频道号。
     *
     * @param count 总频道数
     */
    public void setTotalChannelCount(int count) {
        this.totalChannelCount = count;
    }

    /**
     * 是否正在输入数字
     *
     * @return true=正在输入，false=没有输入
     */
    public boolean isInputting() {
        return channelNumInput.length() > 0;
    }

    /**
     * 处理数字按键
     *
     * 【处理逻辑】
     * 1. 如果数字选台被禁用，直接返回 false
     * 2. 判断是不是 0-9 的数字键
     * 3. 是数字键就追加到输入缓冲
     * 4. 更新频道号显示
     * 5. 重置超时计时器
     *
     * @param keyCode 按键码
     * @return true=处理了该按键，false=不是数字键
     */
    public boolean handleNumberKey(int keyCode) {
        // 禁用了就不处理
        if (!enable) return false;

        // 判断是不是数字键
        int num = keyCodeToNumber(keyCode);
        if (num == -1) return false;

        // 追加到输入缓冲
        channelNumInput.append(num);

        // 更新显示
        if (listener != null) {
            listener.showChannelNumber(channelNumInput.toString());
        }

        // 重置超时计时器
        // 【为什么要重置？】
        // 用户每输入一个数字，都要重新计时 2 秒，
        // 这样连续输入多个数字不会中途超时。
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumHandler.postDelayed(channelNumConfirmRunnable, CHANNEL_NUM_TIMEOUT);

        // 记录操作日志
        SettingsActivity.logOperation("【数字选台】输入：" + channelNumInput);

        return true;
    }

    /**
     * 确认数字选台
     *
     * 【调用时机】
     * 1. 输入超时（2 秒没输入）
     * 2. 用户按了确认键/OK键
     *
     * 【处理逻辑】
     * 1. 解析输入的数字
     * 2. 判断频道号是否有效（1 ~ totalChannelCount）
     * 3. 有效就回调 onChannelSelected
     * 4. 无效就记录日志
     * 5. 清空输入缓冲
     * 6. 延迟 1 秒隐藏频道号显示
     */
    public void confirmChannelNum() {
        // 没有输入就不处理
        if (channelNumInput.length() == 0) return;

        try {
            // 解析频道号（用户输入的是 1-based，转成 0-based 索引）
            int channelNum = Integer.parseInt(channelNumInput.toString());

            if (channelNum >= 1 && channelNum <= totalChannelCount) {
                // 频道号有效，回调选中事件
                int index = channelNum - 1;
                SettingsActivity.logOperation("【数字选台】切换到第 " + channelNum + " 频道");
                if (listener != null) {
                    listener.onChannelSelected(index);
                }
            } else {
                // 频道号不存在
                SettingsActivity.logOperation("【数字选台】频道号不存在：" + channelNum);
            }
        } catch (NumberFormatException e) {
            // 数字解析失败，忽略
            SettingsActivity.logOperation("【数字选台】数字解析失败：" + channelNumInput);
        }

        // 清空输入缓冲
        channelNumInput.setLength(0);

        // 延迟 1 秒隐藏频道号显示
        // 【为什么要延迟？】
        // 让用户能看到自己输入的频道号，确认一下，
        // 不然输入完立刻消失，用户可能不确定有没有输对。
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.hideChannelNumber();
                }
            }
        }, 1000);
    }

    /**
     * 取消数字输入
     *
     * 【调用时机】
     * 1. 用户按了返回键
     * 2. 数字选台功能被禁用
     * 3. 页面销毁
     */
    public void cancelInput() {
        if (channelNumInput.length() > 0) {
            // 清空输入
            channelNumInput.setLength(0);
            // 移除超时回调
            channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
            // 隐藏显示
            if (listener != null) {
                listener.hideChannelNumber();
            }
            SettingsActivity.logOperation("【数字选台】取消输入");
        }
    }

    /**
     * 释放资源
     *
     * 【调用时机】
     * Activity 销毁时调用，防止 Handler 泄漏。
     */
    public void release() {
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumInput.setLength(0);
    }

    // ====================== 私有方法 ======================

    /**
     * 按键码转数字
     *
     * @param keyCode 按键码
     * @return 对应的数字（0-9），不是数字键返回 -1
     */
    private int keyCodeToNumber(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_0: return 0;
            case KeyEvent.KEYCODE_1: return 1;
            case KeyEvent.KEYCODE_2: return 2;
            case KeyEvent.KEYCODE_3: return 3;
            case KeyEvent.KEYCODE_4: return 4;
            case KeyEvent.KEYCODE_5: return 5;
            case KeyEvent.KEYCODE_6: return 6;
            case KeyEvent.KEYCODE_7: return 7;
            case KeyEvent.KEYCODE_8: return 8;
            case KeyEvent.KEYCODE_9: return 9;
            default: return -1;
        }
    }
}
