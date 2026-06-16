package com.tv.live.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import com.google.android.exoplayer2.ui.PlayerView;

/**
 * 终极屏蔽版播放器视图
 * 自带全局开关，一键彻底禁用所有原生控制器相关能力
 * 从View底层切断所有触发路径，100%杜绝控制器弹出
 */
public class BlockControllerPlayerView extends PlayerView {

    // 全局开关：true=彻底屏蔽所有控制器，false=恢复默认
    private boolean mBlockAllController = true;

    public BlockControllerPlayerView(Context context) {
        super(context);
        initBlockConfig();
    }

    public BlockControllerPlayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initBlockConfig();
    }

    public BlockControllerPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initBlockConfig();
    }

    /**
     * 初始化：默认彻底开启屏蔽
     */
    private void initBlockConfig() {
        if (mBlockAllController) {
            // 1. 核心：关闭控制器
            setUseController(false);
            // 2. 关闭缓冲弹窗
            setShowBuffering(SHOW_BUFFERING_NEVER);
            // 3. 切台保留最后一帧
            setKeepContentOnPlayerReset(true);
            // 4. 禁用所有交互属性
            setClickable(false);
            setLongClickable(false);
            setFocusable(false);
            setFocusableInTouchMode(false);
        }
    }

    /**
     * 【全局开关】一键设置是否屏蔽所有原生控制器
     * @param block true=彻底屏蔽，false=恢复默认
     */
    public void setBlockAllController(boolean block) {
        this.mBlockAllController = block;
        initBlockConfig();
    }

    // ====================== 底层事件彻底拦截 ======================

    // 拦截触摸事件分发：事件根本传不到PlayerView原生逻辑
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mBlockAllController) {
            return true; // 直接消费，不向下传递
        }
        return super.dispatchTouchEvent(ev);
    }

    // 拦截触摸事件
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mBlockAllController) {
            return true;
        }
        return super.onTouchEvent(ev);
    }

    // 拦截按键事件（遥控器、键盘、媒体按键）
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mBlockAllController) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // 拦截按键抬起
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mBlockAllController) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // 拦截轨迹球事件
    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        if (mBlockAllController) {
            return true;
        }
        return super.onTrackballEvent(event);
    }

    // 拦截焦点变化
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        if (mBlockAllController) {
            super.onWindowFocusChanged(false); // 永远不获取焦点
            return;
        }
        super.onWindowFocusChanged(hasWindowFocus);
    }

    // 拦截无障碍事件（部分系统通过这个触发控制器）
    @Override
    public void sendAccessibilityEvent(int eventType) {
        if (mBlockAllController) {
            return;
        }
        super.sendAccessibilityEvent(eventType);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (mBlockAllController) {
            return true;
        }
        return super.dispatchPopulateAccessibilityEvent(event);
    }
}
