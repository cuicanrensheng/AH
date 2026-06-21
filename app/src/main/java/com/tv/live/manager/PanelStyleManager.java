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
 * 【2026-06-21 修改：统一高亮样式】
 * 【修改内容】
 * 1. 遥控器模式焦点：蓝色文字 + 标题加粗 + 浅蓝色背景
 * 2. 触屏模式选中：蓝色文字 + 标题加粗 + 浅蓝色背景
 * 3. 两种模式的高亮样式统一，视觉效果一致
 * 4. 新增文字加粗功能，高亮项的标题自动加粗
 *
 * 【两种模式】
 * 1. 遥控器模式（MODE_REMOTE）：用遥控器操作时
 *    - 焦点：蓝色文字 + 标题加粗 + 浅蓝色背景（最显眼）
 *    - 选中：蓝色文字 + 透明背景（次之，当前选中的项）
 *    - 普通：白色文字 + 透明背景
 *
 * 2. 触屏模式（MODE_TOUCH）：用手机触屏操作时
 *    - 选中：蓝色文字 + 标题加粗 + 浅蓝色背景（明显的选中效果）
 *    - 普通：白色文字 + 透明背景
 *
 * 【以后怎么改样式？】
 * 只改这个文件里的颜色常量就行，不用改四个列表管理器！
 */
public class PanelStyleManager {
    // ====================================================================
    // 单例模式
    // ====================================================================
    private static volatile PanelStyleManager instance;

    /**
     * 获取单例
     */
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

    // 私有构造函数，禁止外部直接创建
    private PanelStyleManager() {
        // 默认遥控器模式
        currentMode = MODE_REMOTE;
    }

    // ====================================================================
    // 模式定义
    // ====================================================================
    /** 遥控器模式（用遥控器/按键操作时） */
    public static final int MODE_REMOTE = 0;
    /** 触屏模式（用手机触屏点击时） */
    public static final int MODE_TOUCH = 1;
    /** 当前模式 */
    private int currentMode;

    // ====================================================================
    // 样式常量 - 遥控器模式
    // ====================================================================
    // 【2026-06-21 修改：统一高亮样式】
    // 焦点：蓝色文字 + 标题加粗 + 浅蓝色背景
    // 选中：蓝色文字 + 透明背景
    // 普通：白色文字 + 透明背景

    /** 遥控器模式 - 焦点文字颜色：蓝色 #40A9FF */
    private static final int REMOTE_FOCUS_TEXT_COLOR = Color.parseColor("#40A9FF");
    /** 遥控器模式 - 焦点背景颜色：浅蓝色（20% 透明度） */
    private static final int REMOTE_FOCUS_BG_COLOR = 0x3340A9FF;
    /** 遥控器模式 - 焦点文字是否加粗：是 */
    private static final boolean REMOTE_FOCUS_TEXT_BOLD = true;

    /** 遥控器模式 - 选中文字颜色：蓝色 #40A9FF */
    private static final int REMOTE_SELECTED_TEXT_COLOR = Color.parseColor("#40A9FF");
    /** 遥控器模式 - 选中背景颜色：透明 */
    private static final int REMOTE_SELECTED_BG_COLOR = Color.TRANSPARENT;
    /** 遥控器模式 - 选中文字是否加粗：否（只有焦点才加粗） */
    private static final boolean REMOTE_SELECTED_TEXT_BOLD = false;

    /** 遥控器模式 - 普通文字颜色：白色 */
    private static final int REMOTE_NORMAL_TEXT_COLOR = Color.WHITE;
    /** 遥控器模式 - 普通背景颜色：透明 */
    private static final int REMOTE_NORMAL_BG_COLOR = Color.TRANSPARENT;
    /** 遥控器模式 - 普通文字是否加粗：否 */
    private static final boolean REMOTE_NORMAL_TEXT_BOLD = false;

    // ====================================================================
    // 样式常量 - 触屏模式
    // ====================================================================
    // 【2026-06-21 修改：统一高亮样式】
    // 选中：蓝色文字 + 标题加粗 + 浅蓝色背景（和遥控器焦点样式一致）
    // 普通：白色文字 + 透明背景

    /** 触屏模式 - 选中文字颜色：蓝色 #40A9FF */
    private static final int TOUCH_SELECTED_TEXT_COLOR = Color.parseColor("#40A9FF");
    /** 触屏模式 - 选中背景颜色：浅蓝色（20% 透明度，和遥控器焦点一致） */
    private static final int TOUCH_SELECTED_BG_COLOR = 0x3340A9FF;
    /** 触屏模式 - 选中文字是否加粗：是 */
    private static final boolean TOUCH_SELECTED_TEXT_BOLD = true;

    /** 触屏模式 - 普通文字颜色：白色 */
    private static final int TOUCH_NORMAL_TEXT_COLOR = Color.WHITE;
    /** 触屏模式 - 普通背景颜色：透明 */
    private static final int TOUCH_NORMAL_BG_COLOR = Color.TRANSPARENT;
    /** 触屏模式 - 普通文字是否加粗：否 */
    private static final boolean TOUCH_NORMAL_TEXT_BOLD = false;

    // ====================================================================
    // 模式变化监听器
    // ====================================================================
    /**
     * 模式变化监听器
     */
    public interface OnModeChangedListener {
        /**
         * 模式变化回调
         *
         * @param newMode 新模式（MODE_REMOTE 或 MODE_TOUCH）
         */
        void onModeChanged(int newMode);
    }

    /** 监听器列表 */
    private List<OnModeChangedListener> listeners = new ArrayList<>();

    // ====================================================================
    // 模式相关方法
    // ====================================================================
    /**
     * 设置当前模式
     *
     * @param mode 模式（MODE_REMOTE 或 MODE_TOUCH）
     */
    public void setMode(int mode) {
        if (mode != MODE_REMOTE && mode != MODE_TOUCH) {
            return;
        }
        if (currentMode != mode) {
            currentMode = mode;
            notifyModeChanged();
        }
    }

    /**
     * 获取当前模式
     */
    public int getMode() {
        return currentMode;
    }

    /**
     * 判断是不是遥控器模式
     */
    public boolean isRemoteMode() {
        return currentMode == MODE_REMOTE;
    }

    /**
     * 判断是不是触屏模式
     */
    public boolean isTouchMode() {
        return currentMode == MODE_TOUCH;
    }

    // ====================================================================
    // 样式应用方法
    // ====================================================================
    /**
     * 应用焦点样式
     *
     * @param view 要应用样式的 View
     *
     * 【遥控器模式】
     * - 蓝色文字 + 标题加粗 + 浅蓝色背景
     * - 最显眼的样式，表示遥控器当前指着这里
     *
     * 【触屏模式】
     * - 触屏没有焦点概念，用普通样式
     */
    public void applyFocusStyle(View view) {
        if (view == null) return;

        if (currentMode == MODE_REMOTE) {
            // 遥控器模式：焦点样式（蓝色文字 + 加粗 + 浅蓝色背景）
            setTextColor(view, REMOTE_FOCUS_TEXT_COLOR);
            setTextBold(view, REMOTE_FOCUS_TEXT_BOLD);
            view.setBackgroundColor(REMOTE_FOCUS_BG_COLOR);
        } else {
            // 触屏模式：没有焦点，用普通样式
            applyNormalStyle(view);
        }
    }

    /**
     * 应用选中样式
     *
     * @param view 要应用样式的 View
     *
     * 【遥控器模式】
     * - 蓝色文字 + 透明背景
     * - 表示"这个是当前选中的"（选中的分组/正在播放的频道/选中的日期）
     *
     * 【触屏模式】
     * - 蓝色文字 + 标题加粗 + 浅蓝色背景
     * - 明显的选中效果，和遥控器焦点样式一致
     */
    public void applySelectedStyle(View view) {
        if (view == null) return;

        if (currentMode == MODE_REMOTE) {
            // 遥控器模式：选中样式（蓝色文字 + 透明背景）
            setTextColor(view, REMOTE_SELECTED_TEXT_COLOR);
            setTextBold(view, REMOTE_SELECTED_TEXT_BOLD);
            view.setBackgroundColor(REMOTE_SELECTED_BG_COLOR);
        } else {
            // 触屏模式：选中样式（蓝色文字 + 加粗 + 浅蓝色背景）
            setTextColor(view, TOUCH_SELECTED_TEXT_COLOR);
            setTextBold(view, TOUCH_SELECTED_TEXT_BOLD);
            view.setBackgroundColor(TOUCH_SELECTED_BG_COLOR);
        }
    }

    /**
     * 应用普通样式
     *
     * @param view 要应用样式的 View
     *
     * 【说明】
     * 既不是焦点也不是选中的普通项，
     * 白色文字 + 透明背景。
     */
    public void applyNormalStyle(View view) {
        if (view == null) return;

        if (currentMode == MODE_REMOTE) {
            setTextColor(view, REMOTE_NORMAL_TEXT_COLOR);
            setTextBold(view, REMOTE_NORMAL_TEXT_BOLD);
            view.setBackgroundColor(REMOTE_NORMAL_BG_COLOR);
        } else {
            setTextColor(view, TOUCH_NORMAL_TEXT_COLOR);
            setTextBold(view, TOUCH_NORMAL_TEXT_BOLD);
            view.setBackgroundColor(TOUCH_NORMAL_BG_COLOR);
        }
    }

    // ====================================================================
    // 辅助方法：设置文字颜色
    // ====================================================================
    /**
     * 设置 View 的文字颜色
     *
     * 【处理两种情况】
     * 1. View 本身就是 TextView → 直接设置
     * 2. View 是 ViewGroup → 找到第一个 TextView 设置
     *
     * 【为什么要处理 ViewGroup？】
     * 因为有的列表项是 LinearLayout 包裹的（比如频道项，里面有序号、图标、文字），
     * 需要找到里面的标题 TextView 来设置颜色。
     */
    private void setTextColor(View view, int color) {
        if (view instanceof TextView) {
            // 情况 A：View 本身就是 TextView（简单项，比如日期项、分组项）
            ((TextView) view).setTextColor(color);
        } else if (view instanceof ViewGroup) {
            // 情况 B：View 是 ViewGroup（复杂项，比如频道项、EPG项）
            // 找第一个 TextView，设置文字颜色
            TextView tv = findFirstTextView((ViewGroup) view);
            if (tv != null) {
                tv.setTextColor(color);
            }
        }
    }

    // ====================================================================
    // ✅ 2026-06-21 新增：设置文字加粗
    // ====================================================================
    /**
     * 设置 View 的文字是否加粗
     *
     * 【处理两种情况】
     * 1. View 本身就是 TextView → 直接设置
     * 2. View 是 ViewGroup → 找到第一个 TextView 设置
     *
     * 【说明】
     * 高亮项（焦点/选中）的标题文字加粗，更显眼。
     * 只加粗标题，不加粗所有文字（比如序号、副标题不加粗）。
     *
     * @param view 要设置的 View
     * @param bold 是否加粗
     */
    private void setTextBold(View view, boolean bold) {
        if (view instanceof TextView) {
            // 情况 A：View 本身就是 TextView（简单项，比如日期项、分组项）
            TextView tv = (TextView) view;
            if (bold) {
                tv.setTypeface(null, Typeface.BOLD);
            } else {
                tv.setTypeface(null, Typeface.NORMAL);
            }
        } else if (view instanceof ViewGroup) {
            // 情况 B：View 是 ViewGroup（复杂项，比如频道项、EPG项）
            // 找第一个 TextView（标题），设置加粗
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
     * 在 ViewGroup 中递归查找第一个 TextView
     *
     * 【作用】
     * 对于复杂的列表项（比如频道项，LinearLayout 里有序号、图标、文字），
     * 找到里面的标题 TextView，用来设置文字颜色和加粗。
     *
     * 【为什么用递归？】
     * 因为有的布局可能嵌套多层，递归查找能确保找到第一个 TextView。
     */
    private TextView findFirstTextView(ViewGroup viewGroup) {
        if (viewGroup == null) return null;
        // 遍历所有子 View
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                // 找到了，直接返回
                return (TextView) child;
            } else if (child instanceof ViewGroup) {
                // 子 View 也是 ViewGroup，递归查找
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) {
                    return result;
                }
            }
        }
        // 没找到
        return null;
    }

    // ====================================================================
    // 监听器相关方法
    // ====================================================================
    /**
     * 添加模式变化监听器
     *
     * @param listener 监听器
     */
    public void addOnModeChangedListener(OnModeChangedListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 移除模式变化监听器
     *
     * @param listener 监听器
     */
    public void removeOnModeChangedListener(OnModeChangedListener listener) {
        listeners.remove(listener);
    }

    /**
     * 通知所有监听器：模式变化了
     */
    private void notifyModeChanged() {
        for (OnModeChangedListener listener : listeners) {
            listener.onModeChanged(currentMode);
        }
    }
}
