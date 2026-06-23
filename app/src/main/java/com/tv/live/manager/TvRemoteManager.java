package com.tv.live.manager;

import android.view.KeyEvent;

import com.tv.live.SettingsActivity;

/**
 * 电视遥控器统一管理器
 *
 * 【职责】
 * 统一管理所有遥控器按键操作，支持三种模式：
 * 1. 播放模式（PLAY_MODE）- 全屏播放时
 * 2. 频道面板模式（CHANNEL_PANEL_MODE）- 频道面板打开时
 * 3. 设置模式（SETTINGS_MODE）- 设置页面打开时
 *
 * 【设计原则】
 * 1. 单一入口：所有按键都走 dispatchKeyEvent()
 * 2. 模式驱动：根据当前模式决定按键行为
 * 3. 焦点记忆：记住每个模式下的焦点位置
 * 4. 边界友好：到达边界时有日志，不崩溃
 * 5. 日志完整：每个按键都记录，方便排查
 *
 * 【使用方式】
 * 1. 创建实例：TvRemoteManager remoteManager = new TvRemoteManager()
 * 2. 设置模式：remoteManager.setMode(Mode.CHANNEL_PANEL_MODE)
 * 3. 设置回调：remoteManager.setOnRemoteActionListener(listener)
 * 4. 分发按键：remoteManager.dispatchKeyEvent(keyCode)
 */
public class TvRemoteManager {

    // ====================== 模式枚举 ======================

    /**
     * 遥控器工作模式
     */
    public enum Mode {
        PLAY_MODE,           // 播放模式（全屏播放）
        CHANNEL_PANEL_MODE,  // 频道面板模式
        SETTINGS_MODE        // 设置页面模式
    }

    // ====================== 频道面板焦点位置枚举 ======================

    /**
     * 频道面板焦点位置
     */
    public enum PanelFocus {
        LEFT_GROUP,      // 左侧 - 分组列表
        LEFT_CHANNEL,    // 左侧 - 频道列表
        LEFT_EPG_BTN,    // 左侧 - 节目单按钮
        RIGHT_BACK_BTN,  // 右侧 - 返回按钮
        RIGHT_CHANNEL,   // 右侧 - 频道列表
        RIGHT_DATE,      // 右侧 - 日期列表
        RIGHT_EPG        // 右侧 - EPG列表
    }

    // ====================== 回调接口 ======================

    /**
     * 遥控器动作回调监听器
     */
    public interface OnRemoteActionListener {

        // ================== 播放模式回调 ==================

        /** 上键（播放模式：上一台） */
        void onPlayChannelUp();

        /** 下键（播放模式：下一台） */
        void onPlayChannelDown();

        /** OK键（播放模式：切换面板） */
        void onPlayTogglePanel();

        /** 菜单键（播放模式：打开设置） */
        void onPlayOpenSettings();

        /** 返回键（播放模式：退出应用/返回） */
        boolean onPlayBack();

        // ================== 频道面板模式回调 ==================

        /** 上键（面板模式：列表上移） */
        void onPanelMoveUp();

        /** 下键（面板模式：列表下移） */
        void onPanelMoveDown();

        /** 左键（面板模式：向左切换列） */
        void onPanelMoveLeft();

        /** 右键（面板模式：向右切换列） */
        void onPanelMoveRight();

        /** OK键（面板模式：选中当前项） */
        void onPanelConfirm();

        /** 返回键（面板模式：返回/关闭） */
        boolean onPanelBack();

        /** 菜单键（面板模式：关闭面板） */
        void onPanelMenu();

        /** 数字键（面板模式：数字选台） */
        void onPanelNumber(int number);

        /** 焦点面板变化 */
        void onPanelFocusChanged(PanelFocus newFocus);

        // ================== 设置模式回调 ==================

        /** 上键（设置模式：上移一项） */
        void onSettingsMoveUp();

        /** 下键（设置模式：下移一项） */
        void onSettingsMoveDown();

        /** OK键（设置模式：选中当前项） */
        void onSettingsConfirm();

        /** 返回键（设置模式：关闭设置） */
        boolean onSettingsBack();

        /** 菜单键（设置模式：关闭设置） */
        void onSettingsMenu();

        /** 焦点位置变化 */
        void onSettingsFocusChanged(int position);
    }

    // ====================== 成员变量 ======================

    /** 当前模式 */
    private Mode currentMode = Mode.PLAY_MODE;

    /** 回调监听器 */
    private OnRemoteActionListener listener;

    // ---------- 频道面板模式相关 ----------
    /** 频道面板当前焦点位置 */
    private PanelFocus currentPanelFocus = PanelFocus.LEFT_CHANNEL;
    /** 右侧面板是否打开 */
    private boolean isRightPanelOpen = false;

    // ---------- 设置模式相关 ----------
    /** 设置项总数 */
    private int settingsItemCount = 0;
    /** 设置当前焦点位置 */
    private int settingsFocusPosition = 0;

    // ====================== 构造函数 ======================

    public TvRemoteManager() {
        // 默认播放模式
    }

    // ====================== 模式切换 ======================

    /**
     * 设置当前模式
     */
    public void setMode(Mode mode) {
        this.currentMode = mode;
        SettingsActivity.logOperation("【遥控】切换模式：" + mode);

        // 切换模式时重置焦点
        switch (mode) {
            case CHANNEL_PANEL_MODE:
                resetPanelFocus();
                break;
            case SETTINGS_MODE:
                resetSettingsFocus();
                break;
            case PLAY_MODE:
            default:
                // 播放模式不需要特殊处理
                break;
        }
    }

    /**
     * 获取当前模式
     */
    public Mode getCurrentMode() {
        return currentMode;
    }

    // ====================== 设置回调监听器 ======================

    public void setOnRemoteActionListener(OnRemoteActionListener listener) {
        this.listener = listener;
    }

    // ====================== 核心：按键分发 ======================

    /**
     * 分发遥控器按键事件（统一入口）
     *
     * @param keyCode 按键码
     * @return true=已处理，false=未处理（继续向上传递）
     */
    public boolean dispatchKeyEvent(int keyCode) {
        switch (currentMode) {
            case CHANNEL_PANEL_MODE:
                return dispatchChannelPanelKey(keyCode);
            case SETTINGS_MODE:
                return dispatchSettingsKey(keyCode);
            case PLAY_MODE:
            default:
                return dispatchPlayKey(keyCode);
        }
    }

    // ====================================================================
    // 一、播放模式按键处理
    // ====================================================================

    /**
     * 播放模式按键分发
     */
    private boolean dispatchPlayKey(int keyCode) {
        switch (keyCode) {
            // 上键：上一台
            case KeyEvent.KEYCODE_DPAD_UP:
                SettingsActivity.logOperation("【遥控-播放】上键 → 上一台");
                if (listener != null) {
                    listener.onPlayChannelUp();
                }
                return true;

            // 下键：下一台
            case KeyEvent.KEYCODE_DPAD_DOWN:
                SettingsActivity.logOperation("【遥控-播放】下键 → 下一台");
                if (listener != null) {
                    listener.onPlayChannelDown();
                }
                return true;

            // OK键/确认键：切换频道面板
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                SettingsActivity.logOperation("【遥控-播放】OK键 → 切换面板");
                if (listener != null) {
                    listener.onPlayTogglePanel();
                }
                return true;

            // 左右键：切换频道面板
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                SettingsActivity.logOperation("【遥控-播放】左右键 → 切换面板");
                if (listener != null) {
                    listener.onPlayTogglePanel();
                }
                return true;

            // 菜单键：打开设置
            case KeyEvent.KEYCODE_MENU:
                SettingsActivity.logOperation("【遥控-播放】菜单键 → 打开设置");
                if (listener != null) {
                    listener.onPlayOpenSettings();
                }
                return true;

            // 返回键
            case KeyEvent.KEYCODE_BACK:
                SettingsActivity.logOperation("【遥控-播放】返回键");
                if (listener != null) {
                    return listener.onPlayBack();
                }
                return false;

            // 数字键：数字选台
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                int number = keyCode - KeyEvent.KEYCODE_0;
                SettingsActivity.logOperation("【遥控-播放】数字键 → " + number);
                if (listener != null) {
                    listener.onPanelNumber(number);
                }
                return true;

            default:
                return false;
        }
    }

    // ====================================================================
    // 二、频道面板模式按键处理
    // ====================================================================

    /**
     * 频道面板模式按键分发
     */
    private boolean dispatchChannelPanelKey(int keyCode) {
        switch (keyCode) {
            // 上键：列表上移
            case KeyEvent.KEYCODE_DPAD_UP:
                SettingsActivity.logOperation("【遥控-面板】上键 → 当前焦点：" + currentPanelFocus);
                if (listener != null) {
                    listener.onPanelMoveUp();
                }
                return true;

            // 下键：列表下移
            case KeyEvent.KEYCODE_DPAD_DOWN:
                SettingsActivity.logOperation("【遥控-面板】下键 → 当前焦点：" + currentPanelFocus);
                if (listener != null) {
                    listener.onPanelMoveDown();
                }
                return true;

            // 左键：向左切换列
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return handlePanelLeftKey();

            // 右键：向右切换列
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return handlePanelRightKey();

            // OK键/确认键：选中当前项
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                SettingsActivity.logOperation("【遥控-面板】OK键 → 当前焦点：" + currentPanelFocus);
                if (listener != null) {
                    listener.onPanelConfirm();
                }
                return true;

            // 返回键：返回/关闭面板
            case KeyEvent.KEYCODE_BACK:
                SettingsActivity.logOperation("【遥控-面板】返回键");
                if (listener != null) {
                    return listener.onPanelBack();
                }
                return false;

            // 菜单键：关闭面板
            case KeyEvent.KEYCODE_MENU:
                SettingsActivity.logOperation("【遥控-面板】菜单键 → 关闭面板");
                if (listener != null) {
                    listener.onPanelMenu();
                }
                return true;

            // 数字键：数字选台
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                int number = keyCode - KeyEvent.KEYCODE_0;
                SettingsActivity.logOperation("【遥控-面板】数字键 → " + number);
                if (listener != null) {
                    listener.onPanelNumber(number);
                }
                return true;

            default:
                return false;
        }
    }

    /**
     * 处理面板左键（向左切换列）
     */
    private boolean handlePanelLeftKey() {
        PanelFocus oldFocus = currentPanelFocus;

        switch (currentPanelFocus) {
            case LEFT_EPG_BTN:
                // 节目单按钮 → 频道列表
                currentPanelFocus = PanelFocus.LEFT_CHANNEL;
                break;
            case LEFT_CHANNEL:
                // 频道列表 → 分组列表
                currentPanelFocus = PanelFocus.LEFT_GROUP;
                break;
            case RIGHT_EPG:
                // EPG列表 → 日期列表
                currentPanelFocus = PanelFocus.RIGHT_DATE;
                break;
            case RIGHT_DATE:
                // 日期列表 → 频道列表
                currentPanelFocus = PanelFocus.RIGHT_CHANNEL;
                break;
            case RIGHT_CHANNEL:
                // 频道列表 → 返回按钮
                currentPanelFocus = PanelFocus.RIGHT_BACK_BTN;
                break;
            default:
                // 已经在最左边了
                SettingsActivity.logOperation("【遥控-面板】左键 → 已在最左侧，无法左移");
                return false;
        }

        SettingsActivity.logOperation("【遥控-面板】左键 → " + oldFocus + " → " + currentPanelFocus);

        if (listener != null) {
            listener.onPanelMoveLeft();
            listener.onPanelFocusChanged(currentPanelFocus);
        }

        return true;
    }

    /**
     * 处理面板右键（向右切换列）
     */
    private boolean handlePanelRightKey() {
        PanelFocus oldFocus = currentPanelFocus;

        switch (currentPanelFocus) {
            case LEFT_GROUP:
                // 分组列表 → 频道列表
                currentPanelFocus = PanelFocus.LEFT_CHANNEL;
                break;
            case LEFT_CHANNEL:
                // 频道列表 → 节目单按钮
                currentPanelFocus = PanelFocus.LEFT_EPG_BTN;
                break;
            case RIGHT_BACK_BTN:
                // 返回按钮 → 频道列表
                currentPanelFocus = PanelFocus.RIGHT_CHANNEL;
                break;
            case RIGHT_CHANNEL:
                // 频道列表 → 日期列表
                currentPanelFocus = PanelFocus.RIGHT_DATE;
                break;
            case RIGHT_DATE:
                // 日期列表 → EPG列表
                currentPanelFocus = PanelFocus.RIGHT_EPG;
                break;
            default:
                // 已经在最右边了
                SettingsActivity.logOperation("【遥控-面板】右键 → 已在最右侧，无法右移");
                return false;
        }

        SettingsActivity.logOperation("【遥控-面板】右键 → " + oldFocus + " → " + currentPanelFocus);

        if (listener != null) {
            listener.onPanelMoveRight();
            listener.onPanelFocusChanged(currentPanelFocus);
        }

        return true;
    }

    // ====================================================================
    // 三、设置页面模式按键处理
    // ====================================================================

    /**
     * 设置模式按键分发
     */
    private boolean dispatchSettingsKey(int keyCode) {
        switch (keyCode) {
            // 上键：上移一项
            case KeyEvent.KEYCODE_DPAD_UP:
                return handleSettingsMoveUp();

            // 下键：下移一项
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return handleSettingsMoveDown();

            // OK键/确认键：选中当前项
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                SettingsActivity.logOperation("【遥控-设置】OK键 → 第 " + settingsFocusPosition + " 项");
                if (listener != null) {
                    listener.onSettingsConfirm();
                }
                return true;

            // 返回键：关闭设置
            case KeyEvent.KEYCODE_BACK:
                SettingsActivity.logOperation("【遥控-设置】返回键 → 关闭设置");
                if (listener != null) {
                    return listener.onSettingsBack();
                }
                return false;

            // 菜单键：关闭设置
            case KeyEvent.KEYCODE_MENU:
                SettingsActivity.logOperation("【遥控-设置】菜单键 → 关闭设置");
                if (listener != null) {
                    listener.onSettingsMenu();
                }
                return true;

            default:
                return false;
        }
    }

    /**
     * 处理设置上移
     */
    private boolean handleSettingsMoveUp() {
        if (settingsFocusPosition > 0) {
            settingsFocusPosition--;
            SettingsActivity.logOperation("【遥控-设置】上移 → 第 " + settingsFocusPosition + " 项");
            if (listener != null) {
                listener.onSettingsMoveUp();
                listener.onSettingsFocusChanged(settingsFocusPosition);
            }
            return true;
        } else {
            SettingsActivity.logOperation("【遥控-设置】上移 → 已在顶部");
            return false;
        }
    }

    /**
     * 处理设置下移
     */
    private boolean handleSettingsMoveDown() {
        if (settingsFocusPosition < settingsItemCount - 1) {
            settingsFocusPosition++;
            SettingsActivity.logOperation("【遥控-设置】下移 → 第 " + settingsFocusPosition + " 项");
            if (listener != null) {
                listener.onSettingsMoveDown();
                listener.onSettingsFocusChanged(settingsFocusPosition);
            }
            return true;
        } else {
            SettingsActivity.logOperation("【遥控-设置】下移 → 已在底部");
            return false;
        }
    }

    // ====================================================================
    // 频道面板相关辅助方法
    // ====================================================================

    /**
     * 设置右侧面板是否打开
     */
    public void setRightPanelOpen(boolean open) {
        this.isRightPanelOpen = open;
        // 切换面板时重置焦点
        resetPanelFocus();
    }

    /**
     * 获取当前面板焦点位置
     */
    public PanelFocus getCurrentPanelFocus() {
        return currentPanelFocus;
    }

    /**
     * 设置当前面板焦点位置
     */
    public void setCurrentPanelFocus(PanelFocus focus) {
        this.currentPanelFocus = focus;
        SettingsActivity.logOperation("【遥控-面板】设置焦点：" + focus);
    }

    /**
     * 重置面板焦点到默认位置
     */
    public void resetPanelFocus() {
        if (isRightPanelOpen) {
            currentPanelFocus = PanelFocus.RIGHT_CHANNEL;
        } else {
            currentPanelFocus = PanelFocus.LEFT_CHANNEL;
        }
        SettingsActivity.logOperation("【遥控-面板】重置焦点：" + currentPanelFocus);
    }

    // ====================================================================
    // 设置页面相关辅助方法
    // ====================================================================

    /**
     * 设置设置项总数
     */
    public void setSettingsItemCount(int count) {
        this.settingsItemCount = count;
        // 确保当前焦点在有效范围内
        if (settingsFocusPosition >= count) {
            settingsFocusPosition = count - 1;
        }
        if (settingsFocusPosition < 0) {
            settingsFocusPosition = 0;
        }
    }

    /**
     * 获取设置项总数
     */
    public int getSettingsItemCount() {
        return settingsItemCount;
    }

    /**
     * 获取设置当前焦点位置
     */
    public int getSettingsFocusPosition() {
        return settingsFocusPosition;
    }

    /**
     * 设置设置焦点位置
     */
    public void setSettingsFocusPosition(int position) {
        if (position >= 0 && position < settingsItemCount) {
            this.settingsFocusPosition = position;
            SettingsActivity.logOperation("【遥控-设置】设置焦点：第 " + position + " 项");
        }
    }

    /**
     * 重置设置焦点到第一项
     */
    public void resetSettingsFocus() {
        settingsFocusPosition = 0;
        SettingsActivity.logOperation("【遥控-设置】重置焦点到第一项");
    }
}
