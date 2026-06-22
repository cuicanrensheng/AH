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
 * 
 * 【2026-06-22 新增：手势启用/禁用开关 + 画中画适配】
 * 【问题原因】
 * 画中画模式下，PlayerView 尺寸变小，容易误触手势，
 * 而且小窗模式下本来就不需要手势操作。
 * 
 * 【解决方案】
 * 新增 setEnabled() 方法，可以动态启用/禁用手势。
 * 画中画模式下禁用手势，退出画中画时重新启用。
 * 启用时自动重置防抖状态，确保返回后手势立即可用。
 */
public class GestureManager {
    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_DELAY_MS = 300; // 300ms防抖
    private boolean isGestureLocked = false;
    
    // 新增：手势总开关
    private boolean isEnabled = true;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                // 手势禁用时直接返回
                if (!isEnabled) {
                    return;
                }
                SettingsActivity.logOperation("【手势】单击 → 切换面板");
                activity.togglePanel();
            }

            @Override
            public void onLongOk() {
                // 手势禁用时直接返回
                if (!isEnabled) {
                    return;
                }
                SettingsActivity.logOperation("【手势】长按 → 打开设置");
                activity.openSettings();
            }

            @Override
            public void onMenu() {
                // 手势禁用时直接返回
                if (!isEnabled) {
                    return;
                }
                SettingsActivity.logOperation("【手势】菜单 → 打开设置");
                activity.openSettings();
            }

            // ====================================================================
            // ✅ 修复：上滑手势加上反转判断 + 操作日志
            // ====================================================================
            @Override
            public void onPrevChannel() {
                // 手势禁用时直接返回
                if (!isEnabled) {
                    return;
                }
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
                // 手势禁用时直接返回
                if (!isEnabled) {
                    return;
                }
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
     * 
     * 【2026-06-22 新增：解决退出画中画后手势被拦截问题】
     * 【问题原因】
     * 画中画模式下可能误触发手势，导致 isGestureLocked = true，
     * 退出画中画后，锁定状态还没解除，导致正常手势被防抖拦截。
     * 
     * 【解决方案】
     * 退出画中画时调用此方法，强制重置锁定状态，
     * 并移除 Handler 中所有待处理的延迟消息。
     */
    public void reset() {
        isGestureLocked = false;
        // 移除所有待处理的延迟消息，确保状态干净
        mainHandler.removeCallbacksAndMessages(null);
        SettingsActivity.logOperation("【手势】✅ 状态已重置（清除防抖锁定）");
    }
    
    /**
     * 设置手势启用/禁用状态
     * 【使用场景】
     * 1. 进入画中画时禁用手势，防止误触
     * 2. 退出画中画时启用手势，恢复正常操作
     * 
     * @param enabled true-启用，false-禁用
     */
    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
        if (enabled) {
            // 启用时顺便重置状态，确保立即可用
            isGestureLocked = false;
            mainHandler.removeCallbacksAndMessages(null);
        }
        SettingsActivity.logOperation("【手势】" + (enabled ? "✅ 已启用" : "❌ 已禁用"));
    }
    
    /**
     * 获取手势是否启用
     * @return true-启用，false-禁用
     */
    public boolean isEnabled() {
        return isEnabled;
    }
}
