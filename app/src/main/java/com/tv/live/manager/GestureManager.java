package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;
import com.tv.live.SettingsActivity;

/**
 * 手势管理器
 *
 * 【职责】
 * 处理播放器上的手势操作，包括：
 * 1. 单击：切换频道面板
 * 2. 长按：打开设置页面
 * 3. 上滑：上一个频道（带反转）
 * 4. 下滑：下一个频道（带反转）
 *
 * 【2026-06-20 修复：手势切台也支持反转 + 操作日志】
 * 【问题原因】
 * 之前手势切台直接调用 playPrev()/playNext()，
 * 不考虑反转设置，导致手势切台和按键切台行为不一致。
 *
 * 【解决方案】
 * 1. 加上反转判断：调用 activity.isChannelReverse() 获取反转状态
 * 2. 加上详细的操作日志，记录是从手势入口触发的切台
 *
 * 【效果】
 * 手势切台和按键切台行为一致，都支持反转，
 * 而且在操作日志里可以看到是手势触发的切台。
 */
public class GestureManager {
    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_DELAY_MS = 300; // 300ms防抖
    private boolean isGestureLocked = false;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                SettingsActivity.logOperation("【手势】单击 → 切换面板");
                activity.togglePanel();
            }

            @Override
            public void onLongOk() {
                SettingsActivity.logOperation("【手势】长按 → 打开设置");
                activity.openSettings();
            }

            @Override
            public void onMenu() {
                SettingsActivity.logOperation("【手势】菜单 → 打开设置");
                activity.openSettings();
            }

            // ====================================================================
            // ✅ 修复：上滑手势加上反转判断 + 操作日志
            // ====================================================================
            @Override
            public void onPrevChannel() {
                if (!isGestureLocked) {
                    isGestureLocked = true;
                    // 记录入口日志：是手势上滑触发的
                    boolean isReverse = activity.isChannelReverse();
                    SettingsActivity.logOperation("【手势】上滑 → 反转状态：" 
                            + (isReverse ? "开启" : "关闭")
                            + " → 实际方向：" + (isReverse ? "下一台" : "上一台"));
                    // 根据反转状态决定调用哪个方法
                    if (isReverse) {
                        // 反转开启：上滑 = 下一台
                        activity.playNext();
                    } else {
                        // 反转关闭：上滑 = 上一台
                        activity.playPrev();
                    }
                    // 解锁
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                } else {
                    SettingsActivity.logOperation("【手势】上滑 → 防抖拦截");
                }
            }

            // ====================================================================
            // ✅ 修复：下滑手势加上反转判断 + 操作日志
            // ====================================================================
            @Override
            public void onNextChannel() {
                if (!isGestureLocked) {
                    isGestureLocked = true;
                    // 记录入口日志：是手势下滑触发的
                    boolean isReverse = activity.isChannelReverse();
                    SettingsActivity.logOperation("【手势】下滑 → 反转状态：" 
                            + (isReverse ? "开启" : "关闭")
                            + " → 实际方向：" + (isReverse ? "上一台" : "下一台"));
                    // 根据反转状态决定调用哪个方法
                    if (isReverse) {
                        // 反转开启：下滑 = 上一台
                        activity.playPrev();
                    } else {
                        // 反转关闭：下滑 = 下一台
                        activity.playNext();
                    }
                    // 解锁
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                } else {
                    SettingsActivity.logOperation("【手势】下滑 → 防抖拦截");
                }
            }
        });
    }

    /**
     * 重置手势状态（清除防抖锁定）
     * 【使用场景】
     * 1. 退出画中画时，防止残留的锁定状态影响正常手势
     * 2. 页面恢复时，确保手势可用
     */
    public void reset() {
        isGestureLocked = false;
        // 移除所有待处理的延迟消息，确保状态干净
        mainHandler.removeCallbacksAndMessages(null);
        SettingsActivity.logOperation("【手势】✅ 状态已重置（清除防抖锁定）");
    }
}
