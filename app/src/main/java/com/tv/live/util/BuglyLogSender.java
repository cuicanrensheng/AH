package com.tv.live.util;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.tencent.bugly.crashreport.CrashReport;
import com.tv.live.BuildConfig;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bugly 日志发送器
 *
 * ⚠️ 用户最终规则（严格执行，2026-08-22 更新）：
 *   1. 上传「异常 / 崩溃（Throwable）」到 Bugly —— 仅真实 Throwable 对象才走 postCatchedException；
 *      无 Throwable 的业务失败（code=-1、房间下线、超时、null结果）不包装 RuntimeException、不走异常路径。
 *   2. 上传「运营统计 / 自定义事件」（BerryEvent、埋点、页面访问、功能使用、虎牙SDK业务失败）
 *      —— 走 CrashReport.postTrackEvent（或 Bugly.report）兼容反射路径；
 *      但上传前对 eventId / params.key / params.value 全部应用敏感词打码。
 *   3. 敏感词分两类（命中后，无论是异常 extraUserData 还是 运营统计参数都必须打码）：
 *        ① 业务敏感（直播源 / 频道 / 虎牙 / rtmp / hls / flv / m3u8 / 房间号 / http(s):// …）
 *           → 命中则整条值替换成 [MASKED_BIZ]
 *        ② 凭证敏感（password / token / secret / appkey / HY_APPKEY …）
 *           → 只把 key=value 中的 value 打码成 ****
 *
 *   4. App Key（Bugly 后台看到的 "064332ed-d30e..."）Bugly Android SDK 实际上不使用；
 *      初始化 CrashReport.initCrashReport() 只需要 AppId = 4a23007223。
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

    /** Bugly 场景标签：虎牙 SDK 抛出的 Throwable 统一打 10001，后台"场景筛选"可切到虎牙SDK专属 */
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
            Log.w(TAG, "Bugly already initialized");
            return;
        }
        if (TextUtils.isEmpty(appId)) {
            Log.e(TAG, "Bugly AppID is empty, skip initialization");
            return;
        }

        try {
            // init 前先设置通用维度（值本身不含敏感词，所以安全）
            try { CrashReport.setUserSceneTag(context, SCENE_TAG_HUYA_SDK); } catch (Throwable ignore) {}
            try { CrashReport.setAppChannel(context, detectChannel()); } catch (Throwable ignore) {}
            try { CrashReport.setAppVersion(context, appVersion); } catch (Throwable ignore) {}
            CrashReport.putUserData(context, "device_model", deviceModel);
            CrashReport.putUserData(context, "device_name", deviceName);
            CrashReport.putUserData(context, "app_version", appVersion);
            CrashReport.putUserData(context, "device_id", deviceId);
            CrashReport.putUserData(context, "sdk_integration", "huya_berry_bugly_v2");
            CrashReport.putUserData(context, "is_tv_device",
                    DeviceCapabilities.isTv() ? "tv" : "phone");
            CrashReport.putUserData(context, "policy",
                    "throwable_and_events__biz_masked__tracking_on");

            // Bugly Android SDK 只需要 AppId；第三个参数 IS_DEBUG 控制 SDK 自身日志
            CrashReport.initCrashReport(context, appId, BuildConfig.IS_DEBUG);
            CrashReport.setUserId(deviceId);

            isInitialized = true;
            isEnabled = true;
            Log.i(TAG, "Bugly initialized: appId=" + appId + ", channel=" + detectChannel()
                    + ", huya_scene=" + SCENE_TAG_HUYA_SDK);
        } catch (Exception e) {
            Log.e(TAG, "Bugly initialization failed", e);
        }
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
            Log.d(TAG, "LOG[" + (type == null ? "?" : type) + "] "
                    + (tag == null ? "" : tag) + ": " + maskAllSensitive(msg == null ? "" : msg));
        }
    }

    /**
     * 运营埋点事件：按用户规则"上传运营统计"，但对事件名和参数先做敏感词打码。
     */
    public static void reportEventSafely(String eventName, Map<String, String> params) {
        try {
            if (sInstance != null) {
                sInstance.reportEvent(eventName, params);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "reportEventSafely failed, fallback local", t);
        }
        // fallback：本地打一行
        if (BuildConfig.IS_DEBUG) {
            Log.d(TAG, "[EVENT local-only-fallback] " + maskAllSensitive(eventName)
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
     * 仅当 throwable != null 时上报异常（严格遵守"只上传异常/崩溃"）。
     * 无 Throwable 的 SDK 业务失败（code=-1、result==null、timeout、房间下线等）
     * 只本地打 Log.w + 走 reportHuyaBusinessFailure（运营统计事件），绝不包装 RuntimeException 上传异常。
     */
    public static void reportHuyaExceptionSafely(String tag, Throwable throwable, String extraInfo) {
        if (throwable == null) {
            Log.w(TAG, "[HUYA local-only non-throwable skip-exception] tag=" + tag
                    + " extra=" + maskAllSensitive(extraInfo == null ? "" : extraInfo));
            return;
        }
        try {
            if (sInstance != null) {
                sInstance.reportHuyaException(tag, throwable, extraInfo);
            }
        } catch (Exception e) {
            Log.e(TAG, "reportHuyaExceptionSafely failed", e);
        }
    }

    /**
     * 虎牙 BerryEvent / 回调结果统计：
     * 按用户最新规则「上传运营统计」，所以先 mask，再调实例 reportHuyaEvent → postTrackEventCompat
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
        Log.i(TAG, sb.toString());

        try {
            if (sInstance != null) sInstance.reportHuyaEvent(eventName, params);
        } catch (Throwable t) {
            Log.w(TAG, "reportHuyaEventSafely upload failed (local log kept)", t);
        }
    }

    // ========================== 实例方法 ==========================

    /** 纯文字业务日志 → 本地，不走 Bugly。 */
    public void reportLog(String tag, String msg, String type) {
        if (BuildConfig.IS_DEBUG) {
            Log.d(TAG, "LOG[local-only] [" + type + "] " + tag + ": " + maskAllSensitive(msg));
        }
    }

    /**
     * 通用异常上报（非虎牙模块的 Throwable）。
     * throwable == null 直接 return，避免包装伪异常上传。
     */
    public void reportException(String tag, Throwable throwable, String extraInfo) {
        if (!isEnabled || !isInitialized) return;
        if (throwable == null) return;

        try {
            CrashReport.setUserSceneTag(context, 10000);
            CrashReport.putUserData(context, "exception_module",
                    maskAllSensitive(truncateMsg(tag == null ? "unknown" : tag)));
            if (extraInfo != null) {
                CrashReport.putUserData(context, "exception_extra",
                        maskAllSensitive(truncateMsg(extraInfo)));
            }
            CrashReport.postCatchedException(throwable);
        } catch (Exception e) {
            Log.e(TAG, "Failed to report exception", e);
        }
    }

    /**
     * 🐯 虎牙 SDK 抛出的 Throwable 专属上报入口。
     *  - 场景 tag = 10001（SCENE_TAG_HUYA_SDK），Bugly 后台按场景筛选即可只看虎牙SDK异常
     *  - throwable == null → 严格不上传（logcat 本地告警）
     *  - putUserData 全部走 maskAllSensitive：业务敏感→[MASKED_BIZ]，凭证敏感→value=****
     */
    public void reportHuyaException(String tag, Throwable throwable, String extraInfo) {
        if (!isEnabled || !isInitialized) return;
        if (throwable == null) {
            Log.w(TAG, "[HUYA local-only skip-no-throwable] tag=" + tag + " extra=" + extraInfo);
            return;
        }
        try {
            CrashReport.setUserSceneTag(context, SCENE_TAG_HUYA_SDK);
            CrashReport.putUserData(context, "huya_sdk_tag",
                    maskAllSensitive(truncateMsg(tag == null ? "unknown" : tag)));
            CrashReport.putUserData(context, "huya_sdk_exception_at",
                    String.valueOf(System.currentTimeMillis()));
            if (extraInfo != null) {
                CrashReport.putUserData(context, "huya_sdk_extra",
                        maskAllSensitive(truncateMsg(extraInfo)));
            }
            CrashReport.putUserData(context, "huya_sdk_ex_type",
                    throwable.getClass().getSimpleName());
            String msg = throwable.getMessage();
            if (msg != null) {
                CrashReport.putUserData(context, "huya_sdk_ex_msg",
                        maskAllSensitive(truncateMsg(msg)));
            }
            CrashReport.postCatchedException(throwable);
        } catch (Exception e) {
            Log.e(TAG, "Failed to report huya exception", e);
        }
    }

    // ========= 运营统计 / 事件埋点：先打码，再上传 =========

    public void reportEvent(String eventName, Map<String, String> params) {
        if (!isEnabled || !isInitialized) return;

        String maskedEventId = maskAllSensitive(eventName == null ? "unnamed_event" : eventName);
        // 如果事件名本身就是敏感内容，直接改名（避免事件列表里出现敏感词）
        if ("[MASKED_BIZ]".equals(maskedEventId)) {
            maskedEventId = "masked_biz_event";
        } else if (maskedEventId.length() > 64) {
            maskedEventId = maskedEventId.substring(0, 64); // Bugly eventId 通常上限 64
        }
        Map<String, String> maskedProps = maskAllSensitiveMap(params);

        if (BuildConfig.IS_DEBUG) {
            Log.d(TAG, "[EVENT upload] " + maskedEventId + " " + maskedProps);
        }
        postTrackEventCompat(maskedEventId, maskedProps);
    }

    public void reportHuyaEvent(String eventName, Map<String, String> params) {
        // 虎牙事件：在事件名前加 "huya_" 前缀，Bugly 后台可按前缀筛选
        String name = eventName == null ? "event" : eventName;
        if (!name.startsWith("huya_") && !name.startsWith("HUYA_")) {
            name = "huya_" + name;
        }
        reportEvent(name, params);
    }

    /**
     * 上报虎牙 SDK 业务失败（无 Throwable，所以走"运营统计"路径而非异常路径）。
     * ExceptionReporter.reportHuyaBusinessFailure 会调用这里。
     */
    public void reportHuyaBusinessFailureAsEvent(String module, int code, String errorMsg, String roomInfo) {
        if (!isEnabled || !isInitialized) return;
        Map<String, String> m = new HashMap<>();
        m.put("module", module == null ? "" : module);
        m.put("code", String.valueOf(code));
        m.put("error", errorMsg == null ? "" : errorMsg);
        m.put("room", roomInfo == null ? "" : roomInfo);
        reportEvent("huya_biz_fail", m);
    }

    /**
     * 三版本反射兼容调 Bugly 运营统计事件：
     *   优先 CrashReport.postTrackEvent(ctx, eventId, props) （Bugly 原生新版）
     *   → 降级 testTrackEvent(...)
     *   → 降级 Bugly.report(ctx, eventId, props)
     *
     * 注意：入参必须是已经 mask 过的 eventId / props。
     */
    @SuppressWarnings({"unused", "SameParameterValue"})
    private void postTrackEventCompat(String eventId, Map<String, String> props) {
        if (TextUtils.isEmpty(eventId)) return;
        Map<String, String> safeProps = (props == null) ? new HashMap<String, String>() : props;

        // 1) CrashReport.postTrackEvent(Context, String, Map)
        try {
            Method m1 = CrashReport.class.getMethod("postTrackEvent",
                    Context.class, String.class, Map.class);
            m1.invoke(null, context, eventId, safeProps);
            if (BuildConfig.IS_DEBUG) Log.v(TAG, "postTrackEvent via CrashReport.postTrackEvent: " + eventId);
            return;
        } catch (Throwable ignore) {}

        // 2) testTrackEvent(Context, String, String, Map, long)
        try {
            Method m2 = CrashReport.class.getMethod("testTrackEvent",
                    Context.class, String.class, String.class, Map.class, long.class);
            m2.invoke(null, context, eventId, "app", safeProps, 1L);
            if (BuildConfig.IS_DEBUG) Log.v(TAG, "postTrackEvent via testTrackEvent: " + eventId);
            return;
        } catch (Throwable ignore) {}

        // 3) com.tencent.bugly.Bugly.report(Context, String, Map)
        try {
            Class<?> buglyCls = Class.forName("com.tencent.bugly.Bugly");
            Method m3 = buglyCls.getMethod("report",
                    Context.class, String.class, Map.class);
            m3.invoke(null, context, eventId, safeProps);
            if (BuildConfig.IS_DEBUG) Log.v(TAG, "postTrackEvent via Bugly.report: " + eventId);
        } catch (Throwable ignore) {
            if (BuildConfig.IS_DEBUG) {
                Log.d(TAG, "[EVENT upload skipped: Bugly postTrackEvent API not available in current SDK version] "
                        + eventId + " " + safeProps);
            }
        }
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
                appVersion = pi.versionName + " (" + pi.versionCode + ")";
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
                "Bugly: %s, Init: %s, Device: %s, Policy: throwable+events/biz-masked/tracking-on",
            isEnabled ? "enabled" : "disabled",
            isInitialized ? "yes" : "no",
            deviceId);
    }
}
