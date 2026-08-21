package com.tv.live.util;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
 * <p>直接接入 SDK 内部所有日志的枢纽：
 * <ol>
 *   <li>通过同名类 {@link com.duowan.auk.util.L} 存根直接接管 SDK 全部日志输出（零反射）</li>
 *   <li>捕获 SDK {@link com.huya.berry.client.HuyaBerry.BerryEvent} 事件回调</li>
 *   <li>汇总 {@link com.huya.berry.client.customui.CustomUICallback} 回调日志</li>
 *   <li>将所有日志转发到 LogCollector 以便在日志监控面板显示</li>
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
    private static final int MAX_RECENT = 1000;

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
        public final String source;

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
    private static volatile boolean sForwardToLogCollector = true;

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
        sEventNames.put("getLiveData", "获取直播数据");
        sEventNames.put("getLiveListData", "获取直播列表");
        sEventNames.put("getTagListData", "获取标签列表");
        sEventNames.put("subscribe", "关注");
        sEventNames.put("unSubscribe", "取消关注");
        sEventNames.put("querySubscribeStatus", "查询关注状态");
        sEventNames.put("customUIGetAuthorInfo", "获取主播信息");
        sEventNames.put("customUILogin", "登录");
        sEventNames.put("customUILogout", "登出");
        sEventNames.put("customUIGetResolution", "获取清晰度");
        sEventNames.put("customUISetResolution", "设置清晰度");
        sEventNames.put("setReceiveDanmuData", "设置弹幕接收");
    }

    /**
     * 初始化日志中心
     *
     * 通过 logcat 监听捕获 SDK 内部日志（标签: auk），
     * 以及接收应用层主动记录的 SDK 回调日志。
     */
    public static void init() {
        if (!sInitialized.compareAndSet(false, true)) return;

        // 注册到 LogCollector
        if (sForwardToLogCollector) {
            addLogListener(new OnLogListener() {
                @Override
                public void onLog(LogEntry entry) {
                    forwardToLogCollector(entry);
                }
            });
        }

        // 启动 logcat 监听，捕获 SDK 的 auk 标签日志
        startAukLogCapture();

        Log.i(TAG, "✅ HuyaSDKLogger 初始化完成（logcat 监听 SDK 日志）");
    }

    /**
     * 启动 logcat 监听，捕获 SDK 的 auk 标签日志
     */
    private static void startAukLogCapture() {
        Thread captureThread = new Thread(() -> {
            Process process = null;
            BufferedReader reader = null;
            try {
                // 清空旧日志
                try {
                    Runtime.getRuntime().exec("logcat -c").waitFor();
                } catch (Exception ignored) {}

                // 启动 logcat 读取 auk 标签日志
                process = Runtime.getRuntime().exec(new String[]{"logcat", "-s", "auk:D", "HuyaSDKLogger:I"});
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    parseAndDispatchAukLog(line);
                }
            } catch (Exception e) {
                Log.w(TAG, "⚠️ auk 日志捕获线程退出: " + e.getMessage());
            } finally {
                try {
                    if (reader != null) reader.close();
                    if (process != null) process.destroy();
                } catch (Exception ignored) {}
            }
        }, "AukLogCapture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    /**
     * 解析 logcat 行并分发到日志系统
     */
    private static void parseAndDispatchAukLog(String line) {
        if (line == null || line.isEmpty()) return;

        try {
            // 跳过我们自己输出的日志（避免反馈循环）
            // 英文括号格式（dispatch 之前输出的）
            if (line.contains("[SDK-Auk]") || line.contains("[HuyaSDKLogger]")) return;
            // 中文括号格式（forwardToLogCollector 中生成的）
            if (line.contains("【SDK-Auk】")) return;

            // 跳过时间戳等前缀，提取标签和消息
            // 格式: "08-20 12:48:01.789  4162  4287 I auk     : GetLivingInfo success..."
            int colonIndex = line.indexOf(": ");
            if (colonIndex <= 0) return;

            // 检查是否是 auk 标签的日志
            if (!line.contains(" auk ") && !line.contains(": auk")) return;

            String logContent = line.substring(colonIndex + 2).trim();

            // 判断日志级别
            int level = DEBUG;
            if (line.contains(" E auk ")) level = ERROR;
            else if (line.contains(" W auk ")) level = WARN;
            else if (line.contains(" I auk ")) level = INFO;
            else if (line.contains(" D auk ")) level = DEBUG;
            else if (line.contains(" V auk ")) level = VERBOSE;

            // 过滤 SDK 内部噪音日志，只保留有意义的
            if (shouldCaptureLog(logContent)) {
                dispatch(level, "auk", logContent, "SDK-Auk");
            }
        } catch (Exception ignored) {}
    }

    /**
     * 判断是否应该捕获这条日志（过滤噪音）
     */
    private static boolean shouldCaptureLog(String msg) {
        if (msg == null) return false;

        // 重要日志总是捕获
        if (msg.contains("success") || msg.contains("error") || msg.contains("fail") ||
            msg.contains("GetLivingInfo") || msg.contains("GetLivingInfoRsp") ||
            msg.contains("onResultCallback") || msg.contains("startLive") ||
            msg.contains("playUrl") || msg.contains("stream") ||
            msg.contains("code=") || msg.contains("retCode")) {
            return true;
        }

        // API 请求相关日志
        if (msg.contains("cgi:/") || msg.contains("NS request") ||
            msg.contains("WupRsp") || msg.contains("WupReq") ||
            msg.contains("deliverResponse") || msg.contains("execute")) {
            return true;
        }

        return false;
    }

    /**
     * 转发日志到 LogCollector
     */
    private static void forwardToLogCollector(LogEntry entry) {
        if (!sForwardToLogCollector) return;

        try {
            String type;
            switch (entry.level) {
                case ERROR:
                    type = LogCollector.TYPE_ERROR;
                    break;
                case WARN:
                    type = LogCollector.TYPE_WARN;
                    break;
                case INFO:
                    type = LogCollector.TYPE_NETWORK;
                    break;
                case DEBUG:
                default:
                    type = LogCollector.TYPE_DEBUG;
                    break;
            }

            String logMsg = "【" + entry.source + "】" + entry.msg;
            LogCollector.getInstance().addLog(entry.tag, logMsg, type);
        } catch (Throwable ignored) {
        }
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

        int level = INFO;
        if ("endLive".equals(eventType)) {
            level = DEBUG;
        } else if ("init".equals(eventType)) {
            level = INFO;
        }

        dispatch(level, "BerryEvent", sb.toString(), "BerryEvent");
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
     * 记录 SDK 内部错误日志
     */
    public static void onSDKError(String tag, String errorMsg, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("【SDK-Error】").append(errorMsg);
        if (throwable != null) {
            sb.append(" - ").append(throwable.getClass().getSimpleName())
              .append(": ").append(throwable.getMessage());
        }
        dispatch(ERROR, tag, sb.toString(), "Internal");
    }

    /**
     * 记录 SDK 内部状态日志
     */
    public static void onSDKState(String tag, String stateMsg) {
        dispatch(DEBUG, tag, "【SDK-State】" + stateMsg, "Internal");
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

    /**
     * 设置是否转发到 LogCollector
     */
    public static void setForwardToLogCollector(boolean enabled) {
        sForwardToLogCollector = enabled;
    }

    // ============ 日志分发（public 供 L 存根类直接调用） ============
    public static void dispatch(int level, String tag, String msg, String source) {
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

        // 注意：不再写回 logcat，避免与 logcat 监听形成反馈循环
        // 所有日志通过 LogCollector 和日志监控面板展示
    }

    private HuyaSDKLogger() {
    }
}
