package com.tv.live.util;

import android.content.Context;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;

import com.tv.live.BuildConfig;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 日志发送器（原 Bugly 普通版上报通道）
 *
 * ⚠️ 实验（2026-08-28）：Bugly 普通版引用（com.tencent.bugly:crashreport）已从 build.gradle 移除。
 *    本类保留原有类名/方法签名（调用方零改动），内部所有上传逻辑降级为「本地日志」：
 *    - init() 不再调用 CrashReport.initCrashReport
 *    - reportException / reportHuyaException / reportEvent 仅输出打码后的本地日志
 *    - 敏感词打码逻辑保留（日志中不泄露直播源/频道/凭证信息）
 *
 * 原用户规则（2026-08-22）：
 *   1. 上传「异常 / 崩溃（Throwable）」—— 仅真实 Throwable 才走异常路径
 *   2. 上传「运营统计 / 自定义事件」—— 上传前 eventId / params 全部敏感词打码
 *   3. 敏感词分两类：业务敏感→整条 [MASKED_BIZ]，凭证敏感→仅 value 打码 ****
 */
public class BuglyLogSender {
    private static final String TAG = "BuglyLogSender";

    // 🔒 凭证类敏感词（命中时仅 value 打码 ****，保留其余上下文）
    private static final String[] CREDENTIAL_SENSITIVE_KEYWORDS = {
            "password", "passwd", "token", "secret", "credential",
            "api_key", "apikey", "api-key",
            "HY_APPKEY", "HY_APPID", "huya_app_key", "huya_app_id",
            "appkey", "app_key", "signkey", "sign_key",
            "wsSecret", "wssecret", "encryptkey", "privatekey"
    };

    // 🔒 业务类敏感词（按用户要求：直播源/频道/虎牙/rtmp/hls/flv/m3u8 都算敏感，命中整段变 [MASKED_BIZ]）
    private static final String[] BUSINESS_SENSITIVE_KEYWORDS = {
            "直播源", "频道", "虎牙", "rtmp", "hls", "flv", "m3u8",
            "房间号", "roomId", "room_id", "频道名", "liveId",
            ".tv", ".com/live", "LiveInfo", "SubscribeInfo",
            "streamId", "live_list", "togetherWatchChannel",
            "http://", "https://"
    };

    private static volatile BuglyLogSender sInstance;
    private final Context context;
    private volatile boolean isInitialized;
    private volatile boolean isEnabled;

    private String deviceId;
    private String deviceName;
    private String deviceModel;
    private String appVersion;

    /** Bugly 场景标签：虎牙 SDK 抛出的 Throwable 统一打 10001（已随 Bugly 移除，保留常量兼容调用方） */
    public static final int SCENE_TAG_HUYA_SDK = 10001;

    private BuglyLogSender(Context context) {
        this.context = context.getApplicationContext();
        this.isInitialized = false;
        this.isEnabled = false;
        initDeviceInfo();
    }

    public static BuglyLogSender getInstance(Context context) {
        if (sInstance == null) {
            synchronized (BuglyLogSender.class) {
                if (sInstance == null) {
                    sInstance = new BuglyLogSender(context);
                }
            }
        }
        return sInstance;
    }

    public void init(String appId) {
        if (isInitialized) {
            LogBridge.w(TAG, "BuglyLogSender already initialized (local-only mode)");
            return;
        }

        // Bugly 普通版引用已移除（实验）：不再调用 CrashReport.initCrashReport。
        // 所有上报降级为本地日志，isEnabled 恒为 false。
        isInitialized = true;
        isEnabled = false;
        LogBridge.i(TAG, "BuglyLogSender initialized in LOCAL-ONLY mode (Bugly dependency removed), channel="
                + detectChannel());
    }

    private static String detectChannel() {
        try {
            String ch = System.getProperty("BUGLY_CHANNEL");
            if (!TextUtils.isEmpty(ch)) return ch;
        } catch (Throwable ignored) {}
        return "tv_store";
    }

    public void setEnabled(boolean enabled) { isEnabled = enabled; }
    public boolean isEnabled() { return isEnabled; }
    public boolean isInitialized() { return isInitialized; }

    // ========================== 静态安全入口 ==========================
    public static void reportLogSafely(String tag, String msg, String type) {
        // 纯文字 log：本地打一行即可（没有 Throwable → 不走异常上报；没有 eventId → 不走运营统计）
        if (BuildConfig.IS_DEBUG) {
            LogBridge.d(TAG, "LOG[" + (type == null ? "?" : type) + "] "
                    + (tag == null ? "" : tag) + ": " + maskAllSensitive(msg == null ? "" : msg));
        }
    }

    /**
     * 运营埋点事件：本地记录（打码后）。
     */
    public static void reportEventSafely(String eventName, Map<String, String> params) {
        try {
            if (sInstance != null) {
                sInstance.reportEvent(eventName, params);
                return;
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "reportEventSafely failed, fallback local", t);
        }
        // fallback：本地打一行
        if (BuildConfig.IS_DEBUG) {
            LogBridge.d(TAG, "[EVENT local-only-fallback] " + maskAllSensitive(eventName)
                    + " " + (params == null ? "" : maskAllSensitiveMap(params).toString()));
        }
    }

    public static void reportPageViewSafely(String pageName) {
        Map<String, String> m = new HashMap<>();
        m.put("page_name", pageName == null ? "" : pageName);
        reportEventSafely("page_view", m);
    }

    public static void reportFeatureUseSafely(String featureName, String detail) {
        Map<String, String> m = new HashMap<>();
        m.put("feature", featureName == null ? "" : featureName);
        m.put("detail", detail == null ? "" : detail);
        reportEventSafely("feature_use", m);
    }

    // ==================== 虎牙 SDK 专用静态入口 ====================

    /**
     * 仅当 throwable != null 时记录异常（本地日志）。
     * 无 Throwable 的 SDK 业务失败只本地打 Log.w，绝不包装 RuntimeException。
     */
    public static void reportHuyaExceptionSafely(String tag, Throwable throwable, String extraInfo) {
        if (throwable == null) {
            LogBridge.w(TAG, "[HUYA local-only non-throwable skip-exception] tag=" + tag
                    + " extra=" + maskAllSensitive(extraInfo == null ? "" : extraInfo));
            return;
        }
        try {
            if (sInstance != null) {
                sInstance.reportHuyaException(tag, throwable, extraInfo);
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "reportHuyaExceptionSafely failed", e);
        }
    }

    /**
     * 虎牙 BerryEvent / 回调结果统计：本地记录（打码后）。
     */
    public static void reportHuyaEventSafely(String eventName, Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("[HUYA_EVENT] ").append(maskAllSensitive(eventName == null ? "" : eventName));
        Map<String, String> masked = maskAllSensitiveMap(params);
        if (masked != null && !masked.isEmpty()) {
            sb.append(" | ");
            for (Map.Entry<String, String> e : masked.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append(", ");
            }
            if (sb.length() > 2) sb.setLength(sb.length() - 2);
        }
        LogBridge.i(TAG, sb.toString());

        try {
            if (sInstance != null) sInstance.reportHuyaEvent(eventName, params);
        } catch (Throwable t) {
            LogBridge.w(TAG, "reportHuyaEventSafely failed (local log kept)", t);
        }
    }

    // ========================== 实例方法 ==========================

    /** 纯文字业务日志 → 本地。 */
    public void reportLog(String tag, String msg, String type) {
        if (BuildConfig.IS_DEBUG) {
            LogBridge.d(TAG, "LOG[local-only] [" + type + "] " + tag + ": " + maskAllSensitive(msg));
        }
    }

    /**
     * 通用异常记录（非虎牙模块的 Throwable）。Bugly 已移除 → 仅本地日志。
     */
    public void reportException(String tag, Throwable throwable, String extraInfo) {
        if (throwable == null) return;
        if (BuildConfig.IS_DEBUG) {
            LogBridge.e(TAG, "[EXCEPTION local-only] " + (tag == null ? "unknown" : tag)
                    + " | " + maskAllSensitive(truncateMsg(throwable.getMessage() == null
                    ? throwable.toString() : throwable.getMessage())));
        }
    }

    /**
     * 🐯 虎牙 SDK 抛出的 Throwable 记录入口。Bugly 已移除 → 仅本地日志（打码后）。
     */
    public void reportHuyaException(String tag, Throwable throwable, String extraInfo) {
        if (throwable == null) {
            LogBridge.w(TAG, "[HUYA local-only skip-no-throwable] tag=" + tag + " extra=" + extraInfo);
            return;
        }
        String msg = throwable.getMessage();
        LogBridge.w(TAG, "[HUYA_EXCEPTION local-only] " + (tag == null ? "unknown" : tag)
                + " | " + maskAllSensitive(truncateMsg(msg == null ? throwable.toString() : msg))
                + (extraInfo != null ? " | extra=" + maskAllSensitive(truncateMsg(extraInfo)) : ""));
    }

    // ========= 运营统计 / 事件埋点：本地记录（打码后） =========

    public void reportEvent(String eventName, Map<String, String> params) {
        String maskedEventId = maskAllSensitive(eventName == null ? "unnamed_event" : eventName);
        // 如果事件名本身就是敏感内容，直接改名
        if ("[MASKED_BIZ]".equals(maskedEventId)) {
            maskedEventId = "masked_biz_event";
        } else if (maskedEventId.length() > 64) {
            maskedEventId = maskedEventId.substring(0, 64);
        }
        Map<String, String> maskedProps = maskAllSensitiveMap(params);

        if (BuildConfig.IS_DEBUG) {
            LogBridge.d(TAG, "[EVENT local-only] " + maskedEventId + " " + maskedProps);
        }
    }

    public void reportHuyaEvent(String eventName, Map<String, String> params) {
        // 虎牙事件：在事件名前加 "huya_" 前缀
        String name = eventName == null ? "event" : eventName;
        if (!name.startsWith("huya_") && !name.startsWith("HUYA_")) {
            name = "huya_" + name;
        }
        reportEvent(name, params);
    }

    /**
     * 上报虎牙 SDK 业务失败（无 Throwable，走"运营统计"路径）。Bugly 已移除 → 本地记录。
     */
    public void reportHuyaBusinessFailureAsEvent(String module, int code, String errorMsg, String roomInfo) {
        Map<String, String> m = new HashMap<>();
        m.put("module", module == null ? "" : module);
        m.put("code", String.valueOf(code));
        m.put("error", errorMsg == null ? "" : errorMsg);
        m.put("room", roomInfo == null ? "" : roomInfo);
        reportEvent("huya_biz_fail", m);
    }

    public void reportEvent(String eventName) { reportEvent(eventName, new HashMap<String, String>()); }
    public void reportPageView(String pageName) {
        Map<String, String> m = new HashMap<>();
        m.put("page_name", pageName == null ? "" : pageName);
        reportEvent("page_view", m);
    }
    public void reportFeatureUse(String featureName, String detail) {
        Map<String, String> m = new HashMap<>();
        m.put("feature", featureName == null ? "" : featureName);
        m.put("detail", detail == null ? "" : detail);
        reportEvent("feature_use", m);
    }

    // ================= 敏感词检测 & 打码 =================

    private static boolean containsBizKeyword(String msg) {
        if (TextUtils.isEmpty(msg)) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        for (String kw : BUSINESS_SENSITIVE_KEYWORDS) {
            if (kw.isEmpty()) continue;
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean containsCredentialKeyword(String msg) {
        if (TextUtils.isEmpty(msg)) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        for (String kw : CREDENTIAL_SENSITIVE_KEYWORDS) {
            if (kw.isEmpty()) continue;
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * 统一打码策略（严格执行用户规则）：
     *   ① 命中业务敏感词（直播源/频道/虎牙/rtmp/hls/flv/m3u8/房间号…）→ 整条返回 [MASKED_BIZ]
     *   ② 命中凭证敏感词（password/token/appkey/secret/…）→ 仅把 value 打码 ****
     *   ③ 两者都不命中 → 原样返回
     */
    private static String maskAllSensitive(String msg) {
        if (TextUtils.isEmpty(msg)) return msg;

        // 业务敏感 → 整段屏蔽（防止任何片段泄露）
        if (containsBizKeyword(msg)) return "[MASKED_BIZ]";

        // 凭证敏感 → 仅 value 打码（保留 key 名，便于识别错误发生处）
        if (!containsCredentialKeyword(msg)) return msg;
        String out = msg;
        for (String kw : CREDENTIAL_SENSITIVE_KEYWORDS) {
            if (kw.isEmpty()) continue;
            java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(
                    "(?i)(" + java.util.regex.Pattern.quote(kw) + "\\s*[=:]\\s*)([^&,\\s\\n\"]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            out = p1.matcher(out).replaceAll("$1****");
        }
        return out;
    }

    /** 对 Map<String,String> 的 key 和 value 都应用打码（运营统计参数用） */
    private static Map<String, String> maskAllSensitiveMap(Map<String, String> params) {
        if (params == null || params.isEmpty()) return new HashMap<>();
        Map<String, String> out = new HashMap<>(params.size());
        for (Map.Entry<String, String> e : params.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey();
            String v = e.getValue() == null ? "" : e.getValue();
            // key 含敏感词 → 整个 key 变 MASKED_BIZ，避免参数名泄露
            String mk = maskAllSensitive(k);
            String mv = maskAllSensitive(v);
            out.put(mk, mv);
        }
        return out;
    }

    private String truncateMsg(String msg) {
        if (TextUtils.isEmpty(msg)) return "";
        if (msg.length() > 500) {
            return msg.substring(0, 500) + "...(truncated)";
        }
        return msg;
    }

    private void initDeviceInfo() {
        try {
            deviceId = android.provider.Settings.Secure.getString(
                context.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
            if (TextUtils.isEmpty(deviceId)) {
                deviceId = "unknown_" + android.os.Build.SERIAL;
            }
            deviceModel = android.os.Build.MODEL;
            deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;

            try {
                android.content.pm.PackageManager pm = context.getPackageManager();
                android.content.pm.PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
                long vc = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi);
                appVersion = pi.versionName + " (" + vc + ")";
            } catch (Exception e) {
                appVersion = "0.0.0";
            }
        } catch (Exception e) {
            deviceId = "unknown";
            deviceName = "Unknown Device";
            deviceModel = "Unknown";
            appVersion = "0.0.0";
        }
    }

    public String getDeviceId() { return deviceId; }

    public String getStatusInfo() {
        return String.format(Locale.ROOT,
                "Bugly: removed(local-only), Init: %s, Device: %s, Policy: local-log/masked",
            isInitialized ? "yes" : "no",
            deviceId);
    }
}
