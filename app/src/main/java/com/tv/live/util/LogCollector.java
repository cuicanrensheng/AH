package com.tv.live.util;

import android.text.TextUtils;

import com.tv.live.BuildConfig;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class LogCollector {
    private static volatile LogCollector sInstance;
    private final List<String> logs;
    private final List<LogEntry> logEntries;
    private final SimpleDateFormat sdf;
    private final List<LogListener> listeners;
    private final List<DeviceInfoProvider> deviceInfoProviders;

    // 🟢【新增】分割线特殊标记符
    public static final String DIVIDER_TOKEN = "###DIVIDER###";

    // 日志类型常量
    public static final String TYPE_INFO = "info";
    public static final String TYPE_WARN = "warn";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_DEBUG = "debug";
    public static final String TYPE_CRASH = "crash";
    public static final String TYPE_NETWORK = "network";
    public static final String TYPE_PARSE = "parse";
    public static final String TYPE_PLAYBACK = "playback";
    public static final String TYPE_OPERATION = "operation";
    
    // 不应上报到Bugly的日志关键词（防止正常日志误报）
    private static final String[] NON_REPORTABLE_KEYWORDS = {
        "success", "GetLivingInfo", "GetLivingInfoRsp",
        "SDK-Auk", "BerryEvent", "CustomUI",
        "onResultCallback", "playUrl", "stream",
        // 反调试相关 - 这些是安全检测的正常行为，不应作为崩溃上报
        "检测到调试", "检测到可疑环境", "检测到模拟器", 
        "检测到usb调试", "检测到模拟位置", "检测到root",
        "应用可能被逆向", "反调试检测", "安全检测发现风险",
        "安全检查未通过", "降级运行", "篡改",
        // 网络相关 - 网络波动是外部问题
        "连接超时", "超时", "timeout",
        "404 not found", "http 404", "失败: http"
    };

    public interface LogListener {
        void onLogAdded(LogEntry entry);
        void onLogCleared();
    }

    public interface DeviceInfoProvider {
        String getDeviceId();
        String getDeviceName();
        String getDeviceModel();
        String getAppVersion();
    }

    public static class LogEntry {
        public final long timestamp;
        public final String time;
        public final String tag;
        public final String message;
        public final String type;
        public final String deviceId;
        public final String deviceName;
        public final String deviceModel;
        public final String appVersion;

        public LogEntry(long timestamp, String time, String tag, String message, String type,
                        String deviceId, String deviceName, String deviceModel, String appVersion) {
            this.timestamp = timestamp;
            this.time = time;
            this.tag = tag;
            this.message = message;
            this.type = type;
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.deviceModel = deviceModel;
            this.appVersion = appVersion;
        }
    }

    private LogCollector() {
        logs = new ArrayList<>();
        logEntries = new ArrayList<>();
        sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        listeners = new CopyOnWriteArrayList<>();
        deviceInfoProviders = new CopyOnWriteArrayList<>();
    }

    public static LogCollector getInstance() {
        if (sInstance == null) {
            synchronized (LogCollector.class) {
                if (sInstance == null) {
                    sInstance = new LogCollector();
                }
            }
        }
        return sInstance;
    }

    public void registerDeviceInfoProvider(DeviceInfoProvider provider) {
        deviceInfoProviders.add(provider);
    }

    public void unregisterDeviceInfoProvider(DeviceInfoProvider provider) {
        deviceInfoProviders.remove(provider);
    }

    public void addLogListener(LogListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeLogListener(LogListener listener) {
        listeners.remove(listener);
    }

    private LogEntry createLogEntry(String tag, String msg, String type) {
        long timestamp = System.currentTimeMillis();
        String time = sdf.format(new Date(timestamp));
        String deviceId = "unknown";
        String deviceName = "Unknown Device";
        String deviceModel = "Unknown";
        String appVersion = "0.0.0";

        for (DeviceInfoProvider provider : deviceInfoProviders) {
            try {
                String id = provider.getDeviceId();
                if (!TextUtils.isEmpty(id)) deviceId = id;
                String name = provider.getDeviceName();
                if (!TextUtils.isEmpty(name)) deviceName = name;
                String model = provider.getDeviceModel();
                if (!TextUtils.isEmpty(model)) deviceModel = model;
                String version = provider.getAppVersion();
                if (!TextUtils.isEmpty(version)) appVersion = version;
            } catch (Exception ignored) {}
        }

        return new LogEntry(timestamp, time, tag, msg, type, deviceId, deviceName, deviceModel, appVersion);
    }

    private void notifyListeners(LogEntry entry) {
        for (LogListener listener : listeners) {
            try {
                listener.onLogAdded(entry);
            } catch (Exception ignored) {}
        }
    }

    public void addLog(String tag, String msg) {
        addLog(tag, msg, TYPE_INFO);
    }

    public void addLog(String tag, String msg, String type) {
        LogEntry entry = createLogEntry(tag, msg, type);
        String line;
        if (!TextUtils.isEmpty(tag)) {
            line = entry.time + " 【" + tag + "】 " + msg;
        } else {
            line = entry.time + " " + msg;
        }
        synchronized (logs) {
            logs.add(0, line);
            if (logs.size() > 500) {
                logs.remove(logs.size() - 1);
            }
        }
        synchronized (logEntries) {
            logEntries.add(0, entry);
            if (logEntries.size() > 500) {
                logEntries.remove(logEntries.size() - 1);
            }
        }
        notifyListeners(entry);

        // 自动上报 ERROR 和 CRASH 类型日志到 Bugly（用于监控）
        // 过滤掉不应该上报的日志（如SDK成功日志、网络状态等）
        if (TYPE_ERROR.equals(type) || TYPE_CRASH.equals(type)) {
            if (shouldReportToBugly(msg)) {
                try {
                    ExceptionReporter.reportError(
                            tag != null ? tag : "TVLive",
                            msg != null ? msg : "Unknown error",
                            new RuntimeException(msg != null ? msg : "Unknown error"));
                } catch (Throwable ignored) {}
            } else {
                // 过滤掉的日志仍记录到 logcat，但不上报Bugly
                android.util.Log.w("LogCollector", "日志已过滤不上报Bugly: " + 
                    (msg != null ? msg.substring(0, Math.min(100, msg.length())) : ""));
            }
        }

        // 同时写入 Android logcat，确保 ADB 也能抓取到
        try {
            String logTag = TextUtils.isEmpty(tag) ? "TVLive" : tag;
            switch (type) {
                case TYPE_ERROR:
                    android.util.Log.e(logTag, msg);
                    break;
                case TYPE_WARN:
                    android.util.Log.w(logTag, msg);
                    break;
                case TYPE_DEBUG:
                    android.util.Log.d(logTag, msg);
                    break;
                case TYPE_CRASH:
                    android.util.Log.e(logTag, "CRASH: " + msg);
                    break;
                case TYPE_NETWORK:
                    android.util.Log.i(logTag, "[NET] " + msg);
                    break;
                case TYPE_PARSE:
                    android.util.Log.i(logTag, "[PARSE] " + msg);
                    break;
                case TYPE_PLAYBACK:
                    android.util.Log.i(logTag, "[PLAY] " + msg);
                    break;
                case TYPE_OPERATION:
                    android.util.Log.i(logTag, "[OP] " + msg);
                    break;
                default:
                    android.util.Log.i(logTag, msg);
                    break;
            }
        } catch (Throwable ignored) {}
    }

    // 仅上报真实异常到 Bugly（由调用方直接调用 reportCrashToBugly）

    public void info(String tag, String msg) { addLog(tag, msg, TYPE_INFO); }
    public void warn(String tag, String msg) { addLog(tag, msg, TYPE_WARN); }
    public void error(String tag, String msg) { addLog(tag, msg, TYPE_ERROR); }
    public void error(String tag, String msg, Throwable throwable) {
        addLog(tag, msg + (throwable != null ? ": " + throwable.getMessage() : ""), TYPE_ERROR);
        if (throwable != null) {
            ExceptionReporter.reportError(tag, msg, throwable);
        }
    }
    public void debug(String tag, String msg) { addLog(tag, msg, TYPE_DEBUG); }
    public void crash(String tag, String msg) { addLog(tag, msg, TYPE_CRASH); }
    public void crash(String tag, String msg, Throwable throwable) {
        addLog(tag, msg + (throwable != null ? ": " + throwable.getMessage() : ""), TYPE_CRASH);
        if (throwable != null) {
            ExceptionReporter.report(tag, throwable);
        }
    }
    public void network(String tag, String msg) {
        // 网络日志仅调试版记录
        if (BuildConfig.IS_DEBUG) {
            addLog(tag, msg, TYPE_NETWORK);
        }
    }
    public void parse(String tag, String msg) {
        // 解析日志仅调试版记录
        if (BuildConfig.IS_DEBUG) {
            addLog(tag, msg, TYPE_PARSE);
        }
    }
    public void playback(String tag, String msg) {
        // 播放日志仅调试版记录
        if (BuildConfig.IS_DEBUG) {
            addLog(tag, msg, TYPE_PLAYBACK);
        }
    }
    public void operation(String tag, String msg) { addLog(tag, msg, TYPE_OPERATION); }

    // ============== 运营统计事件上报 ==============
    
    /**
     * 上报页面访问事件（运营统计）
     */
    public void trackPageView(String pageName) {
        try {
            BuglyLogSender.reportPageViewSafely(pageName);
        } catch (Throwable ignored) {}
        operation("PageView", pageName);
    }
    
    /**
     * 上报功能使用事件（运营统计）
     */
    public void trackFeatureUse(String featureName, String detail) {
        try {
            BuglyLogSender.reportFeatureUseSafely(featureName, detail);
        } catch (Throwable ignored) {}
        operation("FeatureUse", featureName + (detail != null ? ": " + detail : ""));
    }
    
    /**
     * 上报自定义统计事件
     */
    public void trackEvent(String eventName, java.util.Map<String, String> params) {
        try {
            BuglyLogSender.reportEventSafely(eventName, params);
        } catch (Throwable ignored) {}
        operation("TrackEvent", eventName);
    }
    
    /**
     * 上报播放开始事件
     */
    public void trackPlayStart(String channelName) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("channel", channelName != null ? channelName : "unknown");
        params.put("action", "start");
        trackEvent("playback", params);
    }
    
    /**
     * 上报播放结束事件
     */
    public void trackPlayEnd(String channelName, long durationMs) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("channel", channelName != null ? channelName : "unknown");
        params.put("action", "end");
        params.put("duration_ms", String.valueOf(durationMs));
        trackEvent("playback", params);
    }
    
    /**
     * 上报频道选择事件
     */
    public void trackChannelSelect(String channelName, int position) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("channel", channelName != null ? channelName : "unknown");
        params.put("position", String.valueOf(position));
        trackEvent("channel_select", params);
    }
    
    /**
     * 上报搜索事件
     */
    public void trackSearch(String keyword, int resultCount) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("keyword", keyword != null ? keyword : "");
        params.put("result_count", String.valueOf(resultCount));
        trackEvent("search", params);
    }

    public void addDivider() {
        String time = sdf.format(new Date());
        synchronized (logs) {
            logs.add(0, time + " " + DIVIDER_TOKEN);
            if (logs.size() > 500) {
                logs.remove(logs.size() - 1);
            }
        }
    }

    public String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        synchronized (logs) {
            for (String log : logs) {
                sb.append(log).append("\n");
            }
        }
        return sb.toString();
    }

    public List<LogEntry> getStructuredLogs() {
        synchronized (logEntries) {
            return new ArrayList<>(logEntries);
        }
    }

    public void clear() {
        synchronized (logs) {
            logs.clear();
        }
        synchronized (logEntries) {
            logEntries.clear();
        }
        for (LogListener listener : listeners) {
            try {
                listener.onLogCleared();
            } catch (Exception ignored) {}
        }
    }
    
    /**
     * 判断日志是否应该上报到Bugly
     * 过滤掉SDK内部状态日志、成功日志等
     */
    private static boolean shouldReportToBugly(String msg) {
        if (msg == null || msg.isEmpty()) return false;
        
        String lowerMsg = msg.toLowerCase();
        
        // 检查是否包含不应上报的关键词
        for (String keyword : NON_REPORTABLE_KEYWORDS) {
            if (lowerMsg.contains(keyword.toLowerCase())) {
                // 包含"success"但也包含"error"/"fail"的仍然需要上报
                if (keyword.equals("success")) {
                    if (lowerMsg.contains("error") || lowerMsg.contains("fail") || 
                        lowerMsg.contains("exception") || lowerMsg.contains("crash")) {
                        return true;
                    }
                }
                // 其他关键词直接过滤
                return false;
            }
        }
        
        return true;
    }
}
