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
 * 3. 上滑：上一个频道（带反转 + 防抖）
 * 4. 下滑：下一个频道（带反转 + 防抖）
 *
 * 【2026-06-20 修复：手势切台也支持反转 + 操作日志】
 * 【2026-06-22 新增：手势启用/禁用开关 + 画中画适配】
 * 【2026-06-22 恢复：手势防抖拦截（用户确认防抖没问题）】
 */
public class GestureManager {
    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 手势总开关
    private boolean isEnabled = true;
    
    // 防抖锁定标记
    private boolean isGestureLocked = false;
    // 防抖延迟时间（300ms）
    private static final long DEBOUNCE_DELAY_MS = 300;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                if (!isEnabled) return;
                SettingsActivity.logOperation("【手势】单击 → 切换面板");
                activity.togglePanel();
            }

            @Override
            public void onLongOk() {
                if (!isEnabled) return;
                SettingsActivity.logOperation("【手势】长按 → 打开设置");
                activity.openSettings();
            }

            @Override
            public void onMenu() {
                if (!isEnabled) return;
                SettingsActivity.logOperation("【手势】菜单 → 打开设置");
                activity.openSettings();
            }

            @Override
            public void onPrevChannel() {
                if (!isEnabled) return;
                
                // ✅ 防抖检查
                if (isGestureLocked) {
                    SettingsActivity.logOperation("【手势】上滑 → 防抖拦截");
                    return;
                }
                isGestureLocked = true;
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        isGestureLocked = false;
                    }
                }, DEBOUNCE_DELAY_MS);
                
                boolean isReverse = activity.isChannelReverse();
                SettingsActivity.logOperation("【手势】上滑 → 反转状态：" 
                        + (isReverse ? "开启" : "关闭")
                        + " → 实际方向：" + (isReverse ? "下一台" : "上一台"));
                if (isReverse) {
                    activity.playNext();
                } else {
                    activity.playPrev();
                }
            }

            @Override
            public void onNextChannel() {
                if (!isEnabled) return;
                
                // ✅ 防抖检查
                if (isGestureLocked) {
                    SettingsActivity.logOperation("【手势】下滑 → 防抖拦截");
                    return;
                }
                isGestureLocked = true;
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        isGestureLocked = false;
                    }
                }, DEBOUNCE_DELAY_MS);
                
                boolean isReverse = activity.isChannelReverse();
                SettingsActivity.logOperation("【手势】下滑 → 反转状态：" 
                        + (isReverse ? "开启" : "关闭")
                        + " → 实际方向：" + (isReverse ? "上一台" : "下一台"));
                if (isReverse) {
                    activity.playPrev();
                } else {
                    activity.playNext();
                }
            }
        });
    }

    /**
     * 重置手势状态（清除防抖锁定）
     */
    public void reset() {
        isGestureLocked = false;
        mainHandler.removeCallbacksAndMessages(null);
        SettingsActivity.logOperation("【手势】状态已重置");
    }
    
    /**
     * 设置手势启用/禁用状态
     */
    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
        if (enabled) {
            // 启用时清除防抖锁定
            isGestureLocked = false;
            mainHandler.removeCallbacksAndMessages(null);
        }
        SettingsActivity.logOperation("【手势】" + (enabled ? "✅ 已启用" : "❌ 已禁用"));
    }
    
    public boolean isEnabled() {
        return isEnabled;
    }
}
