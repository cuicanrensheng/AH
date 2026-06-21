package com.tv.live.manager;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板样式统一管理器
 *
 * 【功能】
 * 统一管理频道面板所有列表的样式，区分遥控器模式和触屏模式
 *
 * 【2026-06-21 最终确认：完全参照 EPG 样式】
 * 【高亮样式】（和 EPG 列表完全一致）
 * - 所有文字：蓝色 #40A9FF
 * - 标题：加粗
 * - 背景：浅蓝色 0x3340A9FF（20% 透明度）
 *
 * 【两种模式】
 * 1. 遥控器模式（MODE_REMOTE）：用遥控器操作时
 *    - 焦点：蓝色文字 + 标题加粗 + 浅蓝色背景（最显眼）
 *    - 选中：蓝色文字 + 透明背景（次之）
 *    - 普通：白色文字 + 透明背景
 *
 * 2. 触屏模式（MODE_TOUCH）：用手机触屏操作时
 *    - 选中：蓝色文字 + 标题加粗 + 浅蓝色背景（明显的选中效果）
 *    - 普通：白色文字 + 透明背景
 *
 * 【使用说明】
 * - 简单布局（分组、日期）：直接调用 applyXxxStyle(view) 即可
 * - 复杂布局（频道、EPG）：建议直接在 getView() 里手动设置每个 TextView，
 *   就像 EPG 列表那样，确保 100% 生效。PanelStyleManager 可作为参考。
 */
public class PanelStyleManager {
    // ====================================================================
    // 单例模式
    // ====================================================================
    private static volatile PanelStyleManager instance;

    public static PanelStyleManager getInstance() {
        if (instance == null) {
            synchronized (PanelStyleManager.class) {
                if (instance == null) {
                    instance = new PanelStyleManager();
                }
            }
        }
        return instance;
    }

    private PanelStyleManager() {
        currentMode = MODE_REMOTE;
    }

    // ====================================================================
    // 模式定义
    // ====================================================================
    public static final int MODE_REMOTE = 0;
    public static final int MODE_TOUCH = 1;
    private int currentMode;

    // ====================================================================
    // 样式常量 - 完全参照 EPG 代码
    // ====================================================================

    // ── 高亮通用样式（焦点/选中 都用这个）──
    /** 高亮文字颜色：蓝色 #40A9FF（和 EPG 完全一致） */
    private static final int HIGHLIGHT_TEXT_COLOR = Color.parseColor("#40A9FF");
    /** 高亮背景颜色：浅蓝色 0x3340A9FF（和 EPG 完全一致） */
    private static final int HIGHLIGHT_BG_COLOR = 0x3340A9FF;
    /** 高亮标题是否加粗：是（和 EPG 完全一致） */
    private static final boolean HIGHLIGHT_TEXT_BOLD = true;

    // ── 遥控器模式 - 选中（只有文字变蓝，背景透明）──
    /** 遥控器模式 - 选中背景颜色：透明 */
    private static final int REMOTE_SELECTED_BG_COLOR = Color.TRANSPARENT;
    /** 遥控器模式 - 选中标题是否加粗：否 */
    private static final boolean REMOTE_SELECTED_TEXT_BOLD = false;

    // ── 普通样式 ──
    /** 普通文字颜色：白色 */
    private static final int NORMAL_TEXT_COLOR = Color.WHITE;
    /** 普通背景颜色：透明 */
    private static final int NORMAL_BG_COLOR = Color.TRANSPARENT;
    /** 普通标题是否加粗：否 */
    private static final boolean NORMAL_TEXT_BOLD = false;

    // ====================================================================
    // 模式变化监听器
    // ====================================================================
    public interface OnModeChangedListener {
        void onModeChanged(int newMode);
    }

    private List<OnModeChangedListener> listeners = new ArrayList<>();

    // ====================================================================
    // 模式相关方法
    // ====================================================================
    public void setMode(int mode) {
        if (mode != MODE_REMOTE && mode != MODE_TOUCH) {
            return;
        }
        if (currentMode != mode) {
            currentMode = mode;
            notifyModeChanged();
        }
    }

    public int getMode() {
        return currentMode;
    }

    public boolean isRemoteMode() {
        return currentMode == MODE_REMOTE;
    }

    public boolean isTouchMode() {
        return currentMode == MODE_TOUCH;
    }

    // ====================================================================
    // 样式应用方法
    // ====================================================================

    /**
     * 应用焦点样式
     *
     * 【遥控器模式】
     * - 蓝色文字 + 标题加粗 + 浅蓝色背景（和 EPG 高亮样式完全一致）
     *
     * 【触屏模式】
     * - 触屏没有焦点概念，用普通样式
     */
    public void applyFocusStyle(View view) {
        if (view == null) return;

        if (currentMode == MODE_REMOTE) {
            // 遥控器模式：焦点 = 高亮样式
            setAllTextColor(view, HIGHLIGHT_TEXT_COLOR);
            setFirstTextBold(view, HIGHLIGHT_TEXT_BOLD);
            view.setBackgroundColor(HIGHLIGHT_BG_COLOR);
        } else {
            // 触屏模式：没有焦点，用普通样式
            applyNormalStyle(view);
        }
    }

    /**
     * 应用选中样式
     *
     * 【遥控器模式】
     * - 蓝色文字 + 透明背景（只变色，不加粗，没有背景）
     *
     * 【触屏模式】
     * - 蓝色文字 + 标题加粗 + 浅蓝色背景（和 EPG 高亮样式完全一致）
     */
    public void applySelectedStyle(View view) {
        if (view == null) return;

        if (currentMode == MODE_REMOTE) {
            // 遥控器模式：选中 = 蓝色文字 + 透明背景
            setAllTextColor(view, HIGHLIGHT_TEXT_COLOR);
            setFirstTextBold(view, REMOTE_SELECTED_TEXT_BOLD);
            view.setBackgroundColor(REMOTE_SELECTED_BG_COLOR);
        } else {
            // 触屏模式：选中 = 高亮样式
            setAllTextColor(view, HIGHLIGHT_TEXT_COLOR);
            setFirstTextBold(view, HIGHLIGHT_TEXT_BOLD);
            view.setBackgroundColor(HIGHLIGHT_BG_COLOR);
        }
    }

    /**
     * 应用普通样式
     *
     * 白色文字 + 透明背景
     */
    public void applyNormalStyle(View view) {
        if (view == null) return;

        setAllTextColor(view, NORMAL_TEXT_COLOR);
        setFirstTextBold(view, NORMAL_TEXT_BOLD);
        view.setBackgroundColor(NORMAL_BG_COLOR);
    }

    // ====================================================================
    // 辅助方法：设置所有 TextView 的文字颜色
    // ====================================================================
    /**
     * 设置 View 中所有 TextView 的文字颜色
     *
     * 【说明】
     * 对于复杂布局（频道、EPG），找到所有 TextView 都设置颜色，
     * 确保序号、标题、副标题等所有文字都变色。
     *
     * @param view 根 View
     * @param color 颜色值
     */
    private void setAllTextColor(View view, int color) {
        if (view instanceof TextView) {
            // 简单项：直接设置
            ((TextView) view).setTextColor(color);
        } else if (view instanceof ViewGroup) {
            // 复杂项：找到所有 TextView 都设置
            List<TextView> allTextViews = findAllTextViews((ViewGroup) view);
            for (TextView tv : allTextViews) {
                tv.setTextColor(color);
            }
        }
    }

    // ====================================================================
    // 辅助方法：设置第一个 TextView 的加粗（标题加粗）
    // ====================================================================
    /**
     * 设置第一个 TextView 的文字是否加粗（标题加粗）
     *
     * 【说明】
     * 只加粗主标题，序号、副标题等辅助文字不加粗。
     *
     * @param view 根 View
     * @param bold 是否加粗
     */
    private void setFirstTextBold(View view, boolean bold) {
        if (view instanceof TextView) {
            // 简单项：直接设置
            TextView tv = (TextView) view;
            if (bold) {
                tv.setTypeface(null, Typeface.BOLD);
            } else {
                tv.setTypeface(null, Typeface.NORMAL);
            }
        } else if (view instanceof ViewGroup) {
            // 复杂项：只给第一个 TextView（标题）设置加粗
            TextView tv = findFirstTextView((ViewGroup) view);
            if (tv != null) {
                if (bold) {
                    tv.setTypeface(null, Typeface.BOLD);
                } else {
                    tv.setTypeface(null, Typeface.NORMAL);
                }
            }
        }
    }

    /**
     * 查找第一个 TextView（标题）
     */
    private TextView findFirstTextView(ViewGroup viewGroup) {
        if (viewGroup == null) return null;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                return (TextView) child;
            } else if (child instanceof ViewGroup) {
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 查找所有 TextView
     */
    private List<TextView> findAllTextViews(ViewGroup viewGroup) {
        List<TextView> result = new ArrayList<>();
        if (viewGroup == null) return result;

        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                result.add((TextView) child);
            } else if (child instanceof ViewGroup) {
                result.addAll(findAllTextViews((ViewGroup) child));
            }
        }
        return result;
    }

    // ====================================================================
    // 监听器相关方法
    // ====================================================================
    public void addOnModeChangedListener(OnModeChangedListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeOnModeChangedListener(OnModeChangedListener listener) {
        listeners.remove(listener);
    }

    private void notifyModeChanged() {
        for (OnModeChangedListener listener : listeners) {
            listener.onModeChanged(currentMode);
        }
    }
}
