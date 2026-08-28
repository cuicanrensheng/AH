package com.tv.live.util;

import android.content.Context;
import android.text.TextUtils;
import com.tv.live.MyApplication;
import com.tv.live.util.LogBridge;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局异常上报器（TVLive 业务代码 catch 到 Throwable 时调用）。
 *
 * ⚠️ 用户规则（严格执行，与 BuglyLogSender 完全一致，2026-08-22 更新）：
 *   1. 上传「异常 / 崩溃（Throwable）」—— 真实 Throwable → Bugly.postCatchedException
 *      无 Throwable 的业务失败（code != 0、null 结果、超时等）→ 不包装 RuntimeException，不触发异常路径上报
 *   2. 上传「运营统计 / 埋点 / 事件聚合」（但先敏感词打码）
 *      —— 虎牙 SDK 业务失败（reportHuyaBusinessFailure）在这里走 BuglyLogSender.reportHuyaBusinessFailureAsEvent
 *   3. 敏感词打码（两种）：
 *      ① 业务敏感（直播源 / 频道 / 虎牙 / rtmp / hls / flv / m3u8 / 房间号 / http(s):// …）
 *         → 命中整条值变 [MASKED_BIZ]
 *      ② 凭证敏感（password / token / secret / appkey / HY_APPKEY / api_key …）
 *         → 仅 value 打码 ****
 */
public class ExceptionReporter {
    private static final String TAG = "ExceptionReporter";

    // 🔒 凭证类敏感词（同 BuglyLogSender，保持一致）
    private static final String[] CREDENTIAL_SENSITIVE_KEYWORDS = {
        "password", "token", "secret", "credential",
        "api_key", "apikey", "HY_APPKEY", "HY_APPID",
        "appkey", "signkey", "wssecret", "encryptkey", "privatekey"
    };

    // 🔒 业务类敏感词（同 BuglyLogSender，保持一致）
    private static final String[] BUSINESS_SENSITIVE_KEYWORDS = {
        "直播源", "频道", "虎牙", "rtmp", "hls", "flv", "m3u8",
        "房间号", "roomId", "频道名", "liveId",
        ".tv", ".com/live", "LiveInfo", "SubscribeInfo",
        "streamId", "live_list", "togetherWatchChannel",
        "http://", "https://"
    };

    private static final long DEDUP_WINDOW_MS = 10_000;
    private static final int MAX_REPORTS_PER_SESSION = 500;

    private static final Map<String, AtomicLong> lastReportTimeMap = new ConcurrentHashMap<>();
    private static final AtomicInteger reportCount = new AtomicInteger(0);
    private static volatile boolean enabled = true;

    public static void init(Context context) {
        enabled = true;
        LogBridge.i(TAG, "ExceptionReporter initialized (policy: throwable+events / biz-masked / tracking-on)");
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        LogBridge.i(TAG, "ExceptionReporter " + (value ? "enabled" : "disabled"));
    }

    // ==================== LogCollector 依赖的对外入口 ====================

    /**
     * 被 LogCollector.error(tag,msg,throwable) 和 ERROR 级日志自动上报调用。
     *
     * 规则：
     *  - throwable != null → 走 `report(tag,msg,throwable)` → Bugly 异常表
     *  - throwable == null → 仅本地 Log，**不**包装 RuntimeException，避免正常 ERROR 日志污染崩溃率
     */
    public static void reportError(String tag, String msg, Throwable throwable) {
        if (throwable != null) {
            report(tag, msg, throwable);
        } else {
            LogBridge.w(TAG, "[reportError no-throwable skip] tag=" + (tag == null ? "" : tag)
                    + " msg=" + filterSensitive(msg == null ? "" : msg));
        }
    }

    // ==================== 全局 catch(Throwable) 入口 ====================

    /**
     * 上报异常。
     * throwable == null → 严格遵守「只上传异常/崩溃」→ 只本地Log，不上传 Bugly 异常路径。
     */
    public static void report(String module, Throwable throwable) {
        report(module, null, throwable);
    }

    public static void report(String module, String context, Throwable throwable) {
        if (!enabled) return;

        // —— 关键：无 Throwable 不上传异常路径（用户规则核心）——
        if (throwable == null) {
            LogBridge.w(TAG, "[non-throwable skip Bugly-exception] module=" + module
                    + " context=" + filterSensitive(context == null ? "" : context));
            return;
        }

        String key = buildKey(module, throwable);
        if (!shouldReport(key)) {
            return;
        }

        int count = reportCount.incrementAndGet();
        if (count > MAX_REPORTS_PER_SESSION) {
            if (count == MAX_REPORTS_PER_SESSION + 1) {
                LogBridge.w(TAG, "Report count exceeded limit " + MAX_REPORTS_PER_SESSION + ", stop reporting.");
            }
            return;
        }

        String stackTrace = getStackTrace(throwable);

        // 本地收集（logcat / LogCollector）→ 不过滤（因为本地是自己看）
        LogBridge.e(TAG, "[" + module + "] Exception reported: " + throwable.getMessage(), throwable);
        if (context != null && !context.isEmpty()) {
            LogBridge.e(TAG, "[" + module + "] Context: " + context);
        }
        try {
            LogCollector.getInstance().error(module,
                    (context != null ? context + " | " : "") + stackTrace);
        } catch (Throwable ignored) {}

        // 真正 Bugly 异常上报（仅 Throwable）
        try {
            BuglyLogSender.reportHuyaExceptionSafely(module, throwable, context);
        } catch (Throwable t) {
            LogBridge.e(TAG, "Bugly report failed", t);
        }
    }

    /**
     * 上报虎牙 SDK 内部业务失败（回调 code != 0 / liveInfo == null / no streams / 超时）
     *
     *  —— 按用户规则「无 Throwable 不走异常路径，走运营统计路径」：
     *     1) 必走本地 Log / LogCollector（本地完整明文，开发者可定位）
     *     2) 尝试走 BuglyLogSender.reportHuyaBusinessFailureAsEvent → Bugly 运营统计（参数会打码）
     *        （不会触发 postCatchedException，不会被算进崩溃/异常率）
     *
     *  参数里的 roomInfo / errorMsg 如果含「直播源/频道/虎牙/rtmp/hls/flv/m3u8」等敏感词，
     *  BuglyLogSender 内部会把对应 value 替换成 [MASKED_BIZ] 再上传。
     */
    public static void reportHuyaBusinessFailure(String module, int code, String errorMsg, String roomInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("[HUYA_BIZ_FAIL local+track_event] ")
          .append(module == null ? "" : module)
          .append(" | code=").append(code)
          .append(" | errorMsg=").append(errorMsg == null ? "" : errorMsg)
          .append(" | room=").append(roomInfo == null ? "" : roomInfo);
        LogBridge.e(TAG, sb.toString());
        try {
            LogCollector.getInstance().error(module == null ? "HuyaBizFail" : module,
                    filterSensitive(sb.toString()));
        } catch (Throwable ignored) {}

        // 运营统计上报（走 Bugly 埋点 / trackEvent，参数打码在 BuglyLogSender 内部完成）
        try {
            Context ctx = getAppContext();
            if (ctx != null) {
                BuglyLogSender.getInstance(ctx)
                        .reportHuyaBusinessFailureAsEvent(module, code, errorMsg, roomInfo);
            } else {
                LogBridge.w(TAG, "App Context unavailable, skip huya biz-fail track event upload");
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "reportHuyaBusinessFailureAsEvent upload failed (local log still kept)", t);
        }
    }

    // =======================================
    //  内部工具：敏感词打码（同 BuglyLogSender.maskAllSensitive 策略）
    // =======================================

    /**
     * 敏感词过滤/打码（严格执行用户规则）：
     *   ① 命中业务敏感词 → 整条值返回 [MASKED_BIZ]
     *   ② 命中凭证敏感词 → 仅把 key:value 里的 value 打码成 ****
     *   ③ 都没命中 → 原样返回
     */
    static String filterSensitive(String msg) {
        if (TextUtils.isEmpty(msg)) return "";

        if (containsBiz(msg)) return "[MASKED_BIZ]";

        if (!containsCredential(msg)) return msg;
        String out = msg;
        for (String kw : CREDENTIAL_SENSITIVE_KEYWORDS) {
            java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(
                    "(?i)(" + java.util.regex.Pattern.quote(kw) + "\\s*[=:]\\s*)([^&,\\s\\n\"]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            out = p1.matcher(out).replaceAll("$1****");
        }
        return out;
    }

    private static boolean containsBiz(String msg) {
        if (TextUtils.isEmpty(msg)) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        for (String kw : BUSINESS_SENSITIVE_KEYWORDS) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean containsCredential(String msg) {
        if (TextUtils.isEmpty(msg)) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        for (String kw : CREDENTIAL_SENSITIVE_KEYWORDS) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    // ============== 内部工具（去重、栈字符串、key 构建、全局 Context 获取）==============

    private static boolean shouldReport(String key) {
        long now = System.currentTimeMillis();
        AtomicLong last = lastReportTimeMap.get(key);
        if (last == null) {
            last = new AtomicLong(0);
            AtomicLong prev = lastReportTimeMap.putIfAbsent(key, last);
            if (prev != null) last = prev;
        }
        long prevTime = last.get();
        if (now - prevTime < DEDUP_WINDOW_MS) {
            return false;
        }
        return last.compareAndSet(prevTime, now);
    }

    private static String buildKey(String module, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append(module == null ? "unknown" : module).append("|");
        sb.append(throwable.getClass().getName()).append("|");
        String msg = throwable.getMessage();
        if (msg != null) {
            sb.append(msg.length() < 100 ? msg : msg.substring(0, 100));
        }
        StackTraceElement[] stack = throwable.getStackTrace();
        if (stack != null && stack.length > 0) {
            sb.append("|").append(stack[0].getClassName()).append(":")
              .append(stack[0].getMethodName()).append(":").append(stack[0].getLineNumber());
        }
        return sb.toString();
    }

    private static String getStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter(512);
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * 反射拿全局 Application Context（不依赖 MyApplication.getInstance 或 BaseApp，避免类名/包名改动）。
     * 失败返回 null（此时仅本地记录，不影响主流程）。
     */
    private static Context getAppContext() {
        MyApplication app = MyApplication.getInstance();
        if (app != null) {
            return app.getApplicationContext();
        }
        return null;
    }

    public static String getStats() {
        return String.format(Locale.ROOT,
                "Reports: %d/%d, Tracked: %d, Enabled: %s",
                reportCount.get(), MAX_REPORTS_PER_SESSION,
                lastReportTimeMap.size(), enabled);
    }
}
