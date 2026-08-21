package com.tv.live.util;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.tencent.bugly.crashreport.CrashReport;

import java.util.HashMap;
import java.util.Map;

/**
 * Bugly 日志发送器
 * 只上报真实的异常/崩溃到 Bugly，业务日志和事件通过自定义维度上报
 * ⚠️ 不再使用 postCatchedException() 上报普通日志，避免 Bugly 将其当作 crash
 */
public class BuglyLogSender {
    private static final String TAG = "BuglyLogSender";
    
    private static final String[] SENSITIVE_KEYWORDS = {
        "live", "stream", "直播源", "频道", "source", 
        "rtmp", "hls", "flv", "m3u8", ".tv", ".com/live"
    };
    
    private static volatile BuglyLogSender sInstance;
    private final Context context;
    private volatile boolean isInitialized;
    private volatile boolean isEnabled;
    
    private String deviceId;
    private String deviceName;
    private String deviceModel;
    private String appVersion;
    
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
            CrashReport.initCrashReport(context, appId, true);
            CrashReport.setUserId(deviceId);
            CrashReport.putUserData(context, "device_model", deviceModel);
            CrashReport.putUserData(context, "device_name", deviceName);
            CrashReport.putUserData(context, "app_version", appVersion);
            CrashReport.putUserData(context, "device_id", deviceId);
            
            isInitialized = true;
            isEnabled = true;
            
            Log.i(TAG, "Bugly initialized: appId=" + appId + ", deviceId=" + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Bugly initialization failed", e);
        }
    }
    
    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }
    
    public boolean isEnabled() {
        return isEnabled;
    }
    
    public boolean isInitialized() {
        return isInitialized;
    }
    
    /**
     * 静态安全上报方法 - 仅上报真实异常
     * 普通日志不再通过 Bugly 异常中心上报
     */
    public static void reportLogSafely(String tag, String msg, String type) {
        // 业务日志不再上报 Bugly 异常中心，避免污染崩溃数据
        // 日志通过 LogCollector 和 CloudLogSender 远程系统上报
    }
    
    public static void reportEventSafely(String eventName, Map<String, String> params) {
        try {
            if (sInstance != null && sInstance.isEnabled && sInstance.isInitialized) {
                sInstance.reportEvent(eventName, params);
            }
        } catch (Exception e) {
            Log.e(TAG, "reportEventSafely failed", e);
        }
    }
    
    public static void reportPageViewSafely(String pageName) {
        try {
            if (sInstance != null && sInstance.isEnabled && sInstance.isInitialized) {
                sInstance.reportPageView(pageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "reportPageViewSafely failed", e);
        }
    }
    
    public static void reportFeatureUseSafely(String featureName, String detail) {
        try {
            if (sInstance != null && sInstance.isEnabled && sInstance.isInitialized) {
                sInstance.reportFeatureUse(featureName, detail);
            }
        } catch (Exception e) {
            Log.e(TAG, "reportFeatureUseSafely failed", e);
        }
    }
    
    /**
     * 上报业务日志 - 不再上报到 Bugly 异常中心
     * 日志通过 LogCollector + CloudLogSender 远程系统上报
     * Bugly 只保留真实 crash/exception 上报
     */
    public void reportLog(String tag, String msg, String type) {
        if (!isEnabled || !isInitialized) return;
        if (containsSensitiveData(msg)) return;
        
        try {
            if (type.equals(LogCollector.TYPE_ERROR) || type.equals(LogCollector.TYPE_CRASH)) {
                CrashReport.putUserData(context, "last_error", truncateMsg(tag + ": " + msg));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to report log", e);
        }
    }
    
    /**
     * 上报真实异常
     */
    public void reportException(String tag, Throwable throwable, String extraInfo) {
        if (!isEnabled || !isInitialized) return;
        
        try {
            if (extraInfo != null && containsSensitiveData(extraInfo)) {
                extraInfo = "[FILTERED]";
            }
            CrashReport.postCatchedException(throwable);
        } catch (Exception e) {
            Log.e(TAG, "Failed to report exception", e);
        }
    }
    
    /**
     * 上报自定义事件到运营统计
     */
    public void reportEvent(String eventName, Map<String, String> params) {
        if (!isEnabled || !isInitialized) return;
        
        try {
            Map<String, String> safeParams = new HashMap<>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!containsSensitiveData(entry.getValue())) {
                    safeParams.put(entry.getKey(), entry.getValue());
                } else {
                    safeParams.put(entry.getKey(), "[FILTERED]");
                }
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("[EVENT] ").append(eventName);
            if (!safeParams.isEmpty()) {
                sb.append(" | ");
                for (Map.Entry<String, String> entry : safeParams.entrySet()) {
                    sb.append(entry.getKey()).append("=").append(entry.getValue()).append(", ");
                }
                sb.setLength(sb.length() - 2);
            }
            
            CrashReport.putUserData(context, "last_event", truncateMsg(sb.toString()));
            CrashReport.putUserData(context, "last_event_time", String.valueOf(System.currentTimeMillis()));
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to report event", e);
        }
    }
    
    public void reportEvent(String eventName) {
        reportEvent(eventName, new HashMap<>());
    }
    
    public void reportPageView(String pageName) {
        Map<String, String> params = new HashMap<>();
        params.put("page_name", pageName);
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        reportEvent("page_view", params);
    }
    
    public void reportFeatureUse(String featureName, String detail) {
        Map<String, String> params = new HashMap<>();
        params.put("feature", featureName);
        if (detail != null) {
            params.put("detail", truncateMsg(detail));
        }
        reportEvent("feature_use", params);
    }
    
    private boolean containsSensitiveData(String msg) {
        if (TextUtils.isEmpty(msg)) return false;
        String lowerMsg = msg.toLowerCase();
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lowerMsg.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
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
    
    public String getDeviceId() {
        return deviceId;
    }
    
    public String getStatusInfo() {
        return String.format("Bugly: %s, Init: %s, Device: %s",
            isEnabled ? "enabled" : "disabled",
            isInitialized ? "yes" : "no",
            deviceId);
    }
}