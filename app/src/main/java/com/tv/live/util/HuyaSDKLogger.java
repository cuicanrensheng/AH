package com.tv.live.util;

import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 虎牙 SDK 统一日志中心
 *
 * <p>接入 SDK 内部所有日志的枢纽：
 * <ol>
 *   <li>接管 {@link com.duowan.auk.util.L} 的全部输出（通过 L.setLogger）</li>
 *   <li>捕获 SDK {@link com.huya.berry.client.HuyaBerry.BerryEvent} 事件回调</li>
 *   <li>汇总 {@link com.huya.berry.client.customui.CustomUICallback} 回调日志</li>
 *   <li>提供日志记录缓冲 + 观察者订阅机制</li>
 * </ol>
 *
 * <p>使用方式：
 * <pre>
 *   HuyaSDKLogger.init();          // 在 Application.onCreate 调用一次
 *   HuyaSDKLogger.observe(...)      // 注册日志观察者
 *   HuyaSDKLogger.getRecentLogs()  // 获取最近日志
 * </pre>
 */
public class HuyaSDKLogger {

    private static final String TAG = "HuyaSDKLogger";

    /** 最近日志最大保留条数 */
    private static final int MAX_RECENT = 500;

    /** 日志级别常量（与 L 类一致） */
    public static final int VERBOSE = 0;
    public static final int DEBUG   = 1;
    public static final int INFO    = 2;
    public static final int WARN    = 3;
    public static final int ERROR   = 4;

    /** 一条日志记录 */
    public static class LogEntry {
        public final long timestamp;
        public final int level;
        public final String tag;
        public final String msg;
        public final String source;   // "L" / "BerryEvent" / "CustomUICallback" / "App"

        LogEntry(long ts, int lv, String t, String m, String src) {
            this.timestamp = ts;
            this.level = lv;
            this.tag = t;
            this.msg = m;
            this.source = src;
        }

        @Override
        public String toString() {
            String levelStr;
            switch (level) {
                case ERROR: levelStr = "E"; break;
                case WARN:  levelStr = "W"; break;
                case INFO:  levelStr = "I"; break;
                case DEBUG: levelStr = "D"; break;
                default:    levelStr = "V"; break;
            }
            return String.format("[%s] %s/%s: %s",
                    levelStr, tag, source, msg);
        }
    }

    /** 日志观察者接口 */
    public interface OnLogListener {
        void onLog(LogEntry entry);
    }

    private static final AtomicBoolean sInitialized = new AtomicBoolean(false);
    private static final List<LogEntry> sRecentLogs =
            Collections.synchronizedList(new ArrayList<LogEntry>());
    private static final List<OnLogListener> sListeners =
            new CopyOnWriteArrayList<>();
    private static volatile int sMinLevel = VERBOSE;
    private static volatile boolean sLogcatEnabled = true;

    /** SDK 事件类型 -> 可读名称映射 */
    private static final ConcurrentHashMap<String, String> sEventNames = new ConcurrentHashMap<>();
    static {
        sEventNames.put("init", "SDK初始化");
        sEventNames.put("startUp", "启动直播");
        sEventNames.put("startLive", "开始直播");
        sEventNames.put("reStartLive", "重开直播");
        sEventNames.put("endLive", "结束直播");
        sEventNames.put("sendPlayerData", "发送玩家数据");
        sEventNames.put("receiveDanmu", "接收弹幕");
        sEventNames.put("exitFullScreen", "退出全屏");
        sEventNames.put("fullScreen", "进入全屏");
        sEventNames.put("closeLiveList", "关闭直播列表");
        sEventNames.put("showFloating", "显示浮窗");
    }

    /**
     * 初始化日志中心（必须在 SDK init 之前调用）
     *
     * SDK 自带 com.duowan.auk.util.L 类，其日志直接输出到 logcat。
     * 本方法不再尝试接管 L 类（避免与 SDK 重复定义），
     * 而是通过 BerryEvent 回调和 CustomUICallback 回调捕获 SDK 事件。
     */
    public static void init() {
        if (!sInitialized.compareAndSet(false, true)) return;
        Log.i(TAG, "HuyaSDKLogger 初始化完成（SDK L 类日志走 logcat）");
    }

    /**
     * 记录一条日志（应用层主动调用）
     */
    public static void log(int level, String tag, String msg) {
        dispatch(level, tag, msg, "App");
    }

    public static void error(String tag, String msg) {
        dispatch(ERROR, tag, msg, "App");
    }

    public static void info(String tag, String msg) {
        dispatch(INFO, tag, msg, "App");
    }

    public static void debug(String tag, String msg) {
        dispatch(DEBUG, tag, msg, "App");
    }

    public static void warn(String tag, String msg) {
        dispatch(WARN, tag, msg, "App");
    }

    /**
     * 记录 SDK BerryEvent 事件
     */
    public static void onBerryEvent(String eventType, Map<String, String> eventData) {
        String readableName = sEventNames.containsKey(eventType)
                ? sEventNames.get(eventType)
                : eventType;
        StringBuilder sb = new StringBuilder();
        sb.append("【BerryEvent:").append(readableName).append("】");
        if (eventData != null && !eventData.isEmpty()) {
            for (Map.Entry<String, String> e : eventData.entrySet()) {
                sb.append(" ").append(e.getKey()).append("=").append(e.getValue());
            }
        }
        dispatch(INFO, "BerryEvent", sb.toString(), "BerryEvent");
    }

    /**
     * 记录 SDK 回调信息
     */
    public static void onCustomUICallback(String callbackName, int code, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("【CustomUI:").append(callbackName).append("】code=").append(code);
        if (!TextUtils.isEmpty(detail)) {
            sb.append(" ").append(detail);
        }
        int level = (code == 0) ? INFO : ERROR;
        dispatch(level, "CustomUI", sb.toString(), "CustomUICallback");
    }

    /**
     * 添加日志观察者
     */
    public static void addLogListener(OnLogListener listener) {
        if (listener != null && !sListeners.contains(listener)) {
            sListeners.add(listener);
        }
    }

    /**
     * 移除日志观察者
     */
    public static void removeLogListener(OnLogListener listener) {
        if (listener != null) {
            sListeners.remove(listener);
        }
    }

    /**
     * 获取最近的日志副本
     */
    public static List<LogEntry> getRecentLogs() {
        synchronized (sRecentLogs) {
            return new ArrayList<>(sRecentLogs);
        }
    }

    /**
     * 清空日志缓冲
     */
    public static void clearLogs() {
        synchronized (sRecentLogs) {
            sRecentLogs.clear();
        }
    }

    /**
     * 设置最低输出级别
     */
    public static void setMinLevel(int level) {
        sMinLevel = level;
    }

    /**
     * 设置是否同时输出到 logcat
     */
    public static void setLogcatEnabled(boolean enabled) {
        sLogcatEnabled = enabled;
    }

    // ============ 内部分发 ============
    private static void dispatch(int level, String tag, String msg, String source) {
        if (level < sMinLevel) return;

        LogEntry entry = new LogEntry(System.currentTimeMillis(), level, tag, msg, source);

        // 写入环形缓冲
        synchronized (sRecentLogs) {
            sRecentLogs.add(entry);
            if (sRecentLogs.size() > MAX_RECENT) {
                sRecentLogs.remove(0);
            }
        }

        // 通知观察者
        for (OnLogListener l : sListeners) {
            try {
                l.onLog(entry);
            } catch (Throwable ignored) {
            }
        }

        // logcat 输出
        if (sLogcatEnabled) {
            switch (level) {
                case ERROR: Log.e(tag, msg); break;
                case WARN:  Log.w(tag, msg); break;
                case INFO:  Log.i(tag, msg); break;
                case DEBUG: Log.d(tag, msg); break;
                default:    Log.v(tag, msg); break;
            }
        }
    }

    private HuyaSDKLogger() {
    }
}
