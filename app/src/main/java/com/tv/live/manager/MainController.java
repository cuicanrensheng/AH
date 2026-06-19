package com.tv.live.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.View;

import com.tv.live.Channel;
import com.tv.live.SettingsActivity;
import com.tv.live.TVPlayerManager;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * 主控制器
 *
 * 【职责】
 * 统一管理 MainActivity 的核心控制逻辑，包括：
 * 1. 按键处理（方向键、数字键、返回键、确认键）
 * 2. 播放控制（播放频道、上一台、下一台）
 * 3. 设置管理（加载设置、配置状态同步）
 * 4. 日志管理（日志记录、日志列表、截断）
 *
 * 【拆分来源】
 * 从 MainActivity 拆分合并而来，原分散在四个地方：
 * - 按键处理：handleDirectionKey、onKeyDown、onBackPressed
 * - 播放控制：playChannel、playPrev、playNext
 * - 设置管理：loadSettings、channel_reverse、number_channel_enable
 * - 日志管理：logList、log()
 *
 * 【双播放器预加载】
 * 播放完成后自动预加载下一个频道，
 * 用户按"下"键时直接无缝切换，0 毫秒黑屏。
 *
 * 【五层逻辑闭环】
 * 1. 状态管理层：按键状态、播放状态、设置状态、日志状态
 * 2. 数据筛选层：设置项筛选、日志截断
 * 3. 状态同步层：设置变化→各 Manager 同步、播放变化→UI 同步
 * 4. 异常兜底层：按键越界保护、播放空数据保护、日志溢出保护
 * 5. 交互闭环层：按键→播放→UI→日志 全链路闭环
 */
public class MainController {

    // ====================== 常量 ======================
    /** 最大保留日志条数 */
    private static final int MAX_LOG_COUNT = 100;

    // ====================== 上下文 ======================
    private Context context;

    // ====================== 依赖的 Manager ======================
    /** 频道面板控制器 */
    private ChannelPanelController channelPanelController;
    /** 数字选台管理器 */
    private ChannelNumberManager channelNumberManager;
    /** 信息展示管理器 */
    private InfoDisplayManager infoDisplayManager;
    /** 播放器管理器 */
    private TVPlayerManager playerManager;
    /** 应用配置 */
    private AppConfig appConfig;
    /** 播放器状态监听器 */
    private PlayerStateListenerImpl playerStateListener;

    // ====================================================================
    // ✅ 双播放器：频道列表引用（用于预加载下一个频道）
    // ====================================================================
    /**
     * 所有频道列表（用于预加载时获取下一个频道）
     * 由外部（MainActivity）在加载完频道列表后设置
     */
    private List<Channel> channelList = new ArrayList<>();

    // ====================== 按键相关状态 ======================
    /** 频道切换是否反向（上键=下一台，下键=上一台） */
    private boolean channelReverse = false;
    /** 数字选台是否启用 */
    private boolean numberChannelEnable = true;
    /** EPG 功能是否启用 */
    private boolean epgEnable = true;
    /** 自动更新源是否启用 */
    private boolean autoUpdateSource = true;

    // ====================== 播放相关状态 ======================
    /** 当前播放的频道索引 */
    private int currentPlayIndex = 0;

    // ====================== 日志相关 ======================
    /** 本地日志列表（保留最近 100 条） */
    private static List<String> logList = new ArrayList<>();

    // ====================== 回调监听器 ======================
    /** 播放控制回调 */
    private OnPlayControlListener playControlListener;
    /** 面板控制回调 */
    private OnPanelControlListener panelControlListener;

    // ====================== 接口定义 ======================
    /**
     * 播放控制回调
     * 当需要播放频道时回调给外部（MainActivity）
     */
    public interface OnPlayControlListener {
        /**
         * 播放指定频道
         *
         * @param channel 频道
         * @param index   全局索引
         */
        void onPlayChannel(Channel channel, int index);
    }

    /**
     * 面板控制回调
     * 当需要控制面板时回调给外部（MainActivity）
     */
    public interface OnPanelControlListener {
        /**
         * 切换面板显示/隐藏
         */
        void onTogglePanel();

        /**
         * 请求焦点（关闭面板后给播放器）
         */
        void onRequestFocus();
    }

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     *
     * @param context                上下文
     * @param channelPanelController 频道面板控制器
     * @param channelNumberManager   数字选台管理器
     * @param infoDisplayManager     信息展示管理器
     * @param playerManager          播放器管理器
     * @param appConfig              应用配置
     * @param playerStateListener    播放器状态监听器
     */
    public MainController(
            Context context,
            ChannelPanelController channelPanelController,
            ChannelNumberManager channelNumberManager,
            InfoDisplayManager infoDisplayManager,
            TVPlayerManager playerManager,
            AppConfig appConfig,
            PlayerStateListenerImpl playerStateListener
    ) {
        this.context = context.getApplicationContext();
        this.channelPanelController = channelPanelController;
        this.channelNumberManager = channelNumberManager;
        this.infoDisplayManager = infoDisplayManager;
        this.playerManager = playerManager;
        this.appConfig = appConfig;
        this.playerStateListener = playerStateListener;
    }

    // ====================================================================
    // 1. 按键处理相关
    // ====================================================================

    /**
     * 处理按键按下事件
     *
     * @param keyCode 按键码
     * @param event   按键事件
     * @return 是否处理了该按键
     */
    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        // 第一步：数字选台处理
        if (channelNumberManager.handleNumberKey(keyCode)) {
            return true;
        }

        // 第二步：方向键处理
        if (handleDirectionKey(keyCode)) {
            return true;
        }

        // 第三步：其他按键（交给 KeyEventManager，这里不处理）
        return false;
    }

    /**
     * 处理方向按键
     *
     * 【按键映射】
     * - 上/下键：切换频道（受换台反转影响）
     * - 确认/OK键：切换面板显示（或确认数字选台）
     * - 左/右键：切换面板显示
     *
     * @param keyCode 按键码
     * @return 是否处理了该按键
     */
    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                // 上键
                if (channelReverse) {
                    playNext();  // 反转：上键 = 下一台
                } else {
                    playPrev();  // 正常：上键 = 上一台
                }
                SettingsActivity.logOperation("【切台】上键 → "
                        + (channelReverse ? "下一台" : "上一台"));
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 下键
                if (channelReverse) {
                    playPrev();  // 反转：下键 = 上一台
                } else {
                    playNext();  // 正常：下键 = 下一台
                }
                SettingsActivity.logOperation("【切台】下键 → "
                        + (channelReverse ? "上一台" : "下一台"));
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 确认键
                if (channelNumberManager.isInputting()) {
                    // 如果正在输入数字选台，确认输入
                    channelNumberManager.confirmChannelNum();
                    return true;
                }
                // 否则切换面板显示
                togglePanel();
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 左右键：切换面板显示
                togglePanel();
                return true;

            default:
                return false;
        }
    }

    /**
     * 处理返回键
     *
     * @return 是否处理了返回键（true=已处理，不退出）
     */
    public boolean handleBackPressed() {
        // 第一步：数字选台取消输入
        if (channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            return true;
        }

        // 第二步：面板关闭
        if (channelPanelController.handleBackPressed()) {
            if (panelControlListener != null) {
                panelControlListener.onRequestFocus();
            }
            return true;
        }

        // 都没处理，让外部（MainActivity）处理
        return false;
    }

    // ====================================================================
    // 2. 播放控制相关
    // ====================================================================

    /**
     * 播放上一个频道（分组内循环）
     */
    public void playPrev() {
        channelPanelController.playPrev();
    }

    /**
     * 播放下一个频道（分组内循环）
     */
    public void playNext() {
        channelPanelController.playNext();
    }

    /**
     * 播放指定索引的频道（兼容旧接口）
     *
     * @param index 全局索引
     */
    public void playChannel(int index) {
        channelPanelController.playChannel(index);
    }

    /**
     * 执行实际播放（内部方法，被 ChannelPanelController 回调）
     *
     * 【播放流程】
     * 1. 保存当前索引
     * 2. 更新播放器状态监听器的频道名
     * 3. 保存上次播放索引
     * 4. 调用播放器播放
     * 5. 显示频道号（右上角大数字）
     * 6. 显示信息栏（底部，频道名+节目名等）
     * 7. 预加载下一个频道（双播放器无缝切台）
     * 8. 回调给外部
     *
     * @param channel 频道
     * @param index   全局索引
     */
    public void doPlayChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;

        // 保存当前索引
        currentPlayIndex = index;

        log("========================================");
        log("【播放】频道名称：" + channel.getName());
        log("【播放】播放地址：" + channel.getPlayUrl());
        log("【播放】当前索引：" + index);
        log("========================================");

        // 更新播放器状态监听器的当前频道名
        playerStateListener.setCurrentChannelName(channel.getName());

        // 保存上次播放索引
        appConfig.setLastPlayIndex(index);

        // 先播放（最重要的事情先做）
        playerManager.playUrl(channel.getPlayUrl());

        // ====================================================================
        // ✅ 修复：显示频道号（右上角大数字，频道号 = 索引 + 1）
        // ====================================================================
        infoDisplayManager.showChannelNum(index + 1);

        // 显示信息栏（底部，频道名+节目名+画质+码率等）
        TVPlayerManager.LiveInfo live = playerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);

        // ====================================================================
        // ✅ 双播放器：播放完成后，预加载下一个频道
        // ====================================================================
        preloadNextChannel(index);

        // 回调给外部
        if (playControlListener != null) {
            playControlListener.onPlayChannel(channel, index);
        }
    }

    // ====================================================================
    // ✅ 双播放器：预加载下一个频道
    // ====================================================================
    /**
     * 预加载下一个频道（双播放器无缝切台）
     *
     * 【预加载策略】
     * 只预加载"下一个"频道（索引 + 1），
     * 因为用户更多是按"下"键往下切台。
     *
     * 如果需要，也可以同时预加载上一个频道，
     * 但会多占一些内存，电视设备一般没问题。
     *
     * 【效果】
     * 用户按"下"键时，下一个频道已经缓冲好了，
     * 直接交换两个播放器的显示状态，0 毫秒黑屏，完全无缝。
     *
     * @param currentIndex 当前播放的频道索引
     */
    private void preloadNextChannel(int currentIndex) {
        // 检查频道列表是否为空
        if (channelList == null || channelList.isEmpty()) {
            return;
        }

        // 计算下一个频道的索引
        int nextIndex = currentIndex + 1;

        // 边界检查：如果已经是最后一个，就回到第一个（循环）
        if (nextIndex >= channelList.size()) {
            nextIndex = 0;
        }

        // 获取下一个频道
        Channel nextChannel = channelList.get(nextIndex);
        if (nextChannel == null || nextChannel.getPlayUrl() == null) {
            return;
        }

        // 检查是否已经预加载过了
        if (playerManager.isPreloaded(nextChannel.getPlayUrl())) {
            log("【双播放器】已预加载过下一个频道：" + nextChannel.getName());
            return;
        }

        // 开始预加载
        log("【双播放器】开始预加载下一个频道：" + nextChannel.getName());
        playerManager.preloadUrl(nextChannel.getPlayUrl());
    }

    // ====================================================================
    // ✅ 双播放器：设置频道列表
    // ====================================================================
    /**
     * 设置频道列表（用于预加载时获取下一个频道）
     *
     * 由外部（MainActivity）在加载完频道列表后调用。
     *
     * @param channels 频道列表
     */
    public void setChannelList(List<Channel> channels) {
        if (channels != null) {
            this.channelList = channels;
            log("【双播放器】频道列表已设置，共 " + channels.size() + " 个频道");
        }
    }

    /**
     * 切换面板显示/隐藏
     */
    public void togglePanel() {
        channelPanelController.togglePanel();
        if (panelControlListener != null) {
            panelControlListener.onTogglePanel();
        }
    }

    /**
     * 获取当前播放索引
     *
     * @return 当前播放索引
     */
    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    /**
     * 设置当前播放索引
     *
     * @param index 播放索引
     */
    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
        channelPanelController.setCurrentPlayIndex(index);
    }

    // ====================================================================
    // 3. 设置管理相关
    // ====================================================================

    /**
     * 从 SharedPreferences 加载各项设置
     *
     * 【加载的设置项】
     * - EPG 开关
     * - 切台反转
     * - 数字选台
     * - 自动更新源
     *
     * 加载完成后会同步到各个对应的 Manager。
     */
    public void loadSettings() {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epgEnable = sp.getBoolean("epg_enable", true);
        channelReverse = sp.getBoolean("channel_reverse", false);
        numberChannelEnable = sp.getBoolean("number_channel_enable", true);
        autoUpdateSource = sp.getBoolean("auto_update_source", true);

        // 同步到各 Manager
        if (channelNumberManager != null) {
            channelNumberManager.setEnable(numberChannelEnable);
        }
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epgEnable);
        }

        log("【设置】EPG开关：" + epgEnable);
        log("【设置】切台反转：" + channelReverse);
        log("【设置】数字选台：" + numberChannelEnable);
        log("【设置】自动更新源：" + autoUpdateSource);
    }

    /**
     * 频道切换是否反向
     *
     * @return 是否反向
     */
    public boolean isChannelReverse() {
        return channelReverse;
    }

    /**
     * 数字选台是否启用
     *
     * @return 是否启用
     */
    public boolean isNumberChannelEnable() {
        return numberChannelEnable;
    }

    /**
     * EPG 功能是否启用
     *
     * @return 是否启用
     */
    public boolean isEpgEnable() {
        return epgEnable;
    }

    /**
     * 自动更新源是否启用
     *
     * @return 是否启用
     */
    public boolean isAutoUpdateSource() {
        return autoUpdateSource;
    }

    // ====================================================================
    // 4. 日志管理相关
    // ====================================================================

    /**
     * 记录日志
     * 同时保存到本地列表和 SettingsActivity 的全局日志
     *
     * @param msg 日志内容
     */
    public static void log(String msg) {
        logList.add(0, msg);
        // 只保留最近 100 条，防止内存溢出
        while (logList.size() > MAX_LOG_COUNT) {
            logList.remove(logList.size() - 1);
        }
        // 同步到 SettingsActivity 的全局日志
        SettingsActivity.log(msg);
    }

    /**
     * 获取日志列表
     *
     * @return 日志列表
     */
    public static List<String> getLogList() {
        return logList;
    }

    /**
     * 清空日志
     */
    public static void clearLog() {
        logList.clear();
    }

    // ====================================================================
    // 5. 监听器设置
    // ====================================================================

    /**
     * 设置播放控制监听器
     *
     * @param listener 监听器
     */
    public void setOnPlayControlListener(OnPlayControlListener listener) {
        this.playControlListener = listener;
    }

    /**
     * 设置面板控制监听器
     *
     * @param listener 监听器
     */
    public void setOnPanelControlListener(OnPanelControlListener listener) {
        this.panelControlListener = listener;
    }

    // ====================================================================
    // 6. 资源释放
    // ====================================================================

    /**
     * 释放资源
     * Activity onDestroy 时调用
     */
    public void release() {
        context = null;
        channelPanelController = null;
        channelNumberManager = null;
        infoDisplayManager = null;
        playerManager = null;
        appConfig = null;
        playerStateListener = null;
        playControlListener = null;
        panelControlListener = null;
        if (channelList != null) {
            channelList.clear();
            channelList = null;
        }
    }
}
