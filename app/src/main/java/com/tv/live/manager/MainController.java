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
        this.infoDisplay
