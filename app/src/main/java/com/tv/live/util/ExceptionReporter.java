package com.tv.live.util;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.tencent.bugly.crashreport.CrashReport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局异常上报器
 * 将所有被捕获的异常（try-catch）自动上报到 Bugly
 *
 * 【设计要点】
 * 1. 静态方法，任何地方都能调用 ExceptionReporter.report(e)
 * 2. 相同异常10秒内只上报一次（去重，防止刷屏）
 * 3. 单进程最多上报500个异常（防止内存溢出）
 * 4. 敏感信息自动过滤
 * 5. 上报失败不影响业务逻辑
 *
 * 【使用示例】
 * try {
 *     doSomething();
 * } catch (Exception e) {
 *     ExceptionReporter.report("TVPlayerManager", e);
 * }
 */
public class ExceptionReporter {
    private static final String TAG = "ExceptionReporter";

    private static final long DEDUP_WINDOW_MS = 10_000;
    private static final int MAX_REPORTS_PER_SESSION = 500;

    private static final String[] SENSITIVE_KEYWORDS = {
        "password", "token", "secret", "key", "credential",
        "直播源", "rtmp", "hls", "flv", "m3u8", "api_key"
    };

    private static final Map<String, AtomicLong> lastReportTimeMap = new ConcurrentHashMap<>();
    private static final AtomicInteger reportCount = new AtomicInteger(0);
    private static volatile boolean enabled = true;
    private static volatile Context appContext;

    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public static void setEnabled(boolean e) {
        enabled = e;
    }

    public static void report(String tag, Throwable throwable) {
        if (!enabled || throwable == null) return;
        if (reportCount.get() >= MAX_REPORTS_PER_SESSION) return;

        try {
            String key = tag + "#" + throwable.getClass().getSimpleName();
            long now = System.currentTimeMillis();
            AtomicLong lastTime = lastReportTimeMap.get(key);
            if (lastTime == null) {
                AtomicLong newTime = new AtomicLong(0);
                AtomicLong existing = lastReportTimeMap.putIfAbsent(key, newTime);
                lastTime = existing != null ? existing : newTime;
            }
            if (now - lastTime.get() < DEDUP_WINDOW_MS) {
                return;
            }
            lastTime.set(now);

            String safeTag = filterSensitive(tag);
            String safeMsg = filterSensitive(throwable.getMessage());

            putUserDataSafe("exception_tag", truncate(safeTag));
            putUserDataSafe("exception_type", throwable.getClass().getName());
            putUserDataSafe("exception_msg", truncate(safeMsg));
            putUserDataSafe("exception_time", String.valueOf(now));

            CrashReport.postCatchedException(throwable);

            reportCount.incrementAndGet();

            Log.w(TAG, "已上报异常: " + safeTag + " -> " + throwable.getClass().getSimpleName()
                    + ": " + (safeMsg != null ? safeMsg : ""));

        } catch (Exception e) {
            Log.e(TAG, "异常上报失败: " + e.getMessage());
        }
    }

    public static void report(Throwable throwable) {
        if (throwable == null) return;
        StackTraceElement caller = findCaller();
        String tag = caller != null
                ? caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1)
                : "Unknown";
        report(tag, throwable);
    }

    public static void reportError(String tag, String msg, Throwable throwable) {
        if (!enabled || throwable == null) return;
        if (reportCount.get() >= MAX_REPORTS_PER_SESSION) return;

        try {
            String safeTag = filterSensitive(tag);
            String safeMsg = filterSensitive(msg);

            String key = safeTag + "#" + throwable.getClass().getSimpleName();
            long now = System.currentTimeMillis();
            AtomicLong lastTime = lastReportTimeMap.get(key);
            if (lastTime == null) {
                AtomicLong newTime = new AtomicLong(0);
                AtomicLong existing = lastReportTimeMap.putIfAbsent(key, newTime);
                lastTime = existing != null ? existing : newTime;
            }
            if (now - lastTime.get() < DEDUP_WINDOW_MS) {
                return;
            }
            lastTime.set(now);

            putUserDataSafe("exception_tag", truncate(safeTag));
            putUserDataSafe("exception_type", throwable.getClass().getName());
            putUserDataSafe("exception_msg", truncate(safeMsg));
            putUserDataSafe("exception_time", String.valueOf(now));

            CrashReport.postCatchedException(throwable);

            reportCount.incrementAndGet();

            Log.w(TAG, "已上报错误: " + safeTag + " - " + safeMsg);
        } catch (Exception e) {
            Log.e(TAG, "错误上报失败: " + e.getMessage());
        }
    }

    private static void putUserDataSafe(String key, String value) {
        try {
            if (appContext != null) {
                CrashReport.putUserData(appContext, key, value);
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    private static StackTraceElement findCaller() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int i = 0; i < stack.length; i++) {
            if (!stack[i].getClassName().equals(ExceptionReporter.class.getName())
                    && !stack[i].getClassName().equals(LogCollector.class.getName())) {
                return stack[i];
            }
        }
        return null;
    }

    private static String filterSensitive(String input) {
        if (TextUtils.isEmpty(input)) return input;
        String lower = input.toLowerCase();
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return "[FILTERED]";
            }
        }
        return input;
    }

    private static String truncate(String msg) {
        if (msg == null) return "";
        return msg.length() > 500 ? msg.substring(0, 500) + "...(truncated)" : msg;
    }

    public static int getReportCount() {
        return reportCount.get();
    }

    public static void resetCount() {
        reportCount.set(0);
        lastReportTimeMap.clear();
    }
}
