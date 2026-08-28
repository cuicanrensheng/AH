package com.tv.live.security;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import com.tv.live.util.LogBridge;

import com.tv.live.BuildConfig;
import com.tv.live.util.LogCollector;

/**
 * 反编译检测上报器
 * 
 * 功能：
 * 1. 检测到反编译/篡改时自动记录行为
 * 2. 记录到本地日志监控（LogCollector）与本地文件（Bugly 普通版已移除，实验）
 * 3. 触发应用崩溃，记录篡改行为
 * 
 * 仅在 Release 版本启用。
 */
public final class TamperReporter {

    private static final String TAG = "TamperReporter";
    
    // 篡改类型
    public static final int TAMPER_SIGNATURE = 1;      // 签名不匹配
    public static final int TAMPER_PACKAGE_NAME = 2;   // 包名不匹配
    public static final int TAMPER_DEX_INTEGRITY = 3; // DEX 完整性
    public static final int TAMPER_DEBUGGER = 4;      // 调试器
    public static final int TAMPER_FRIDA = 5;         // Frida
    public static final int TAMPER_XPOSED = 6;        // Xposed
    public static final int TAMPER_HOOK = 7;          // Hook
    public static final int TAMPER_ROOT = 8;          // Root
    public static final int TAMPER_EMULATOR = 9;     // 模拟器
    public static final int TAMPER_MEMORY = 10;       // 内存篡改

    private static volatile boolean sReported = false;
    private static volatile boolean sCrashing = false;
    private static Context sAppContext;

    private TamperReporter() {}

    /**
     * 初始化
     */
    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
        if (!BuildConfig.IS_DEBUG) {
            LogBridge.i(TAG, "反编译检测器已启用");
        } else {
            LogBridge.i(TAG, "反编译检测器：调试模式，仅记录日志");
        }
    }

    /**
     * 上报篡改行为（确认的篡改，会上报到Bugly并可能触发崩溃）
     * @param tamperType 篡改类型
     * @param detail 详细信息
     */
    public static void reportTamper(int tamperType, String detail) {
        if (sReported) return; // 只上报一次
        sReported = true;
        
        String typeName = getTypeName(tamperType);
        LogBridge.e(TAG, "⚠️ 检测到篡改行为: " + typeName + " - " + detail);
        
        // 1. 记录到本地日志（始终执行）
        LogCollector.getInstance().error(TAG, 
            "⚠️ 反编译检测: " + typeName + " | " + detail);
        
        // 2. 记录到本地日志监控（LogCollector）
        reportToLocalMonitor(tamperType, typeName, detail);
        
        // 3. 记录完整信息（原 Bugly 上报通道已移除 → 仅本地完整记录）
        if (!BuildConfig.IS_DEBUG) {
            reportToBugly(tamperType, typeName, detail);
        }
        
        // 4. 写入本地文件日志（确保即使网络不通也能保留记录）
        writeTamperLogToFile(tamperType, typeName, detail);
    }
    
    /**
     * 上报可疑环境（仅记录日志，不上报崩溃）
     * 用于记录USB调试、模拟器等合法但可疑的环境
     * @param tamperType 可疑类型
     * @param detail 详细信息
     */
    public static void reportSuspicious(int tamperType, String detail) {
        String typeName = getTypeName(tamperType);
        LogBridge.w(TAG, "⚠️ 检测到可疑环境: " + typeName + " - " + detail);
        
        // 仅记录到本地日志，不上报Bugly
        LogCollector.getInstance().warn(TAG, 
            "可疑环境: " + typeName + " | " + detail);
    }
    
    /**
     * 写入篡改日志到本地文件
     */
    private static void writeTamperLogToFile(int tamperType, String typeName, String detail) {
        try {
            if (sAppContext == null) return;
            
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("\n===== TAMPER DETECTED =====\n");
            logBuilder.append("Time: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date())).append("\n");
            logBuilder.append("Type: ").append(typeName).append(" (").append(tamperType).append(")\n");
            logBuilder.append("Detail: ").append(detail).append("\n");
            logBuilder.append("Package: ").append(BuildConfig.APPLICATION_ID).append("\n");
            logBuilder.append("Version: ").append(BuildConfig.VERSION_NAME).append("\n");
            logBuilder.append("Device: ").append(Build.MODEL).append("\n");
            logBuilder.append("Brand: ").append(Build.BRAND).append("\n");
            logBuilder.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");
            logBuilder.append("Build: ").append(BuildConfig.IS_DEBUG ? "debug" : "release").append("\n");
            logBuilder.append("Process: ").append(android.os.Process.myPid()).append("\n");
            logBuilder.append("==========================\n\n");
            
            // 追加到文件
            java.io.File dir = new java.io.File(sAppContext.getFilesDir(), "tamper_logs");
            if (!dir.exists()) dir.mkdirs();
            
            java.io.File logFile = new java.io.File(dir, "tamper_" + System.currentTimeMillis() + ".log");
            java.io.FileWriter writer = new java.io.FileWriter(logFile, true);
            writer.write(logBuilder.toString());
            writer.close();
            
            LogBridge.i(TAG, "篡改日志已写入: " + logFile.getAbsolutePath());
            
        } catch (Exception e) {
            LogBridge.e(TAG, "写入篡改日志失败: " + e.getMessage());
        }
    }

    /**
     * 上报到本地日志监控
     */
    private static void reportToLocalMonitor(int tamperType, String typeName, String detail) {
        try {
            // 通过 LogCollector 记录完整信息（本地日志，便于电脑端日志监控查看）
            StringBuilder sb = new StringBuilder();
            sb.append("[TAMPER] ").append(typeName).append("\n");
            sb.append("Detail: ").append(detail).append("\n");
            sb.append("Package: ").append(BuildConfig.APPLICATION_ID).append("\n");
            sb.append("Version: ").append(BuildConfig.VERSION_NAME).append("\n");
            sb.append("Device: ").append(Build.MODEL).append("\n");
            sb.append("Time: ").append(System.currentTimeMillis());
            
            LogCollector.getInstance().error(TAG, sb.toString());
            
        } catch (Exception e) {
            LogBridge.e(TAG, "本地上报失败: " + e.getMessage());
        }
    }

    /**
     * 记录篡改完整信息（Bugly 普通版已移除，实验：仅本地 LogCollector + LogBridge）
     */
    private static void reportToBugly(int tamperType, String typeName, String detail) {
        try {
            // 完整信息拼装（原通过 CrashReport.putUserData / postCatchedException 上报）
            StringBuilder stackTrace = new StringBuilder();
            stackTrace.append("检测到反编译/篡改行为\n");
            stackTrace.append("类型: ").append(typeName).append("\n");
            stackTrace.append("详情: ").append(detail).append("\n");
            stackTrace.append("包名: ").append(BuildConfig.APPLICATION_ID).append("\n");
            stackTrace.append("版本: ").append(BuildConfig.VERSION_NAME).append("\n");
            stackTrace.append("设备: ").append(Build.MODEL).append("\n");
            stackTrace.append("品牌: ").append(Build.BRAND).append("\n");
            stackTrace.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");
            stackTrace.append("时间: ").append(System.currentTimeMillis()).append("\n");
            stackTrace.append("构建类型: ").append(BuildConfig.IS_DEBUG ? "debug" : "release");

            // 本地完整记录（LogCollector 会写日志文件）
            LogCollector.getInstance().error(TAG,
                "[TAMPER-LOCAL] " + typeName + " | " + detail + "\n" + stackTrace);
            LogBridge.i(TAG, "篡改行为已记录到本地（Bugly 已移除）");
        } catch (Exception e) {
            LogBridge.e(TAG, "本地篡改记录失败: " + e.getMessage());
        }
    }

    /**
     * 触发崩溃（用于彻底阻止篡改者）
     */
    public static void triggerCrash(int tamperType, String detail) {
        if (sCrashing) return;
        sCrashing = true;
        
        // 先上报
        reportTamper(tamperType, detail);
        
        if (BuildConfig.IS_DEBUG) {
            // 调试版：只记录，不崩溃
            LogBridge.w(TAG, "调试模式：跳过崩溃触发");
            return;
        }
        
        // 延迟 500ms 让上报完成
        new Thread(() -> {
            try {
                Thread.sleep(500);
                
                // 触发崩溃
                throw new TamperFatalException(
                    "【致命错误】检测到应用被篡改，已终止运行: " + detail);
            } catch (InterruptedException e) {
                // 直接退出
                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        }, "TamperCrash").start();
    }

    /**
     * 紧急上报（立即崩溃）
     */
    public static void emergencyReport(int tamperType, String detail) {
        if (!BuildConfig.IS_DEBUG) {
            // 立即上报并崩溃
            reportTamper(tamperType, detail);
            Process.killProcess(Process.myPid());
            System.exit(0);
        }
    }

    /**
     * 获取篡改类型名称
     */
    public static String getTypeName(int tamperType) {
        switch (tamperType) {
            case TAMPER_SIGNATURE: return "签名不匹配";
            case TAMPER_PACKAGE_NAME: return "包名不匹配";
            case TAMPER_DEX_INTEGRITY: return "DEX完整性校验失败";
            case TAMPER_DEBUGGER: return "调试器检测";
            case TAMPER_FRIDA: return "Frida检测";
            case TAMPER_XPOSED: return "Xposed框架检测";
            case TAMPER_HOOK: return "Hook攻击检测";
            case TAMPER_ROOT: return "Root环境";
            case TAMPER_EMULATOR: return "模拟器环境";
            case TAMPER_MEMORY: return "内存篡改检测";
            default: return "未知篡改类型(" + tamperType + ")";
        }
    }

    /**
     * 是否已上报
     */
    public static boolean isReported() {
        return sReported;
    }

    /**
     * 是否已崩溃
     */
    public static boolean isCrashing() {
        return sCrashing;
    }

    /**
     * 内部异常类 - 用于上报
     */
    private static class TamperException extends Exception {
        public TamperException(String message) {
            super(message);
            // 添加更多堆栈信息
            StackTraceElement[] stack = new StackTraceElement[] {
                new StackTraceElement(
                    "com.tv.live.security.TamperReporter",
                    "reportTamper",
                    "TamperReporter.java",
                    100),
                new StackTraceElement(
                    "com.tv.live.security.SecurityCheck",
                    "verifyOnStart",
                    "SecurityCheck.java",
                    50),
                new StackTraceElement(
                    "com.tv.live.MyApplication",
                    "onCreate",
                    "MyApplication.java",
                    100),
            };
            setStackTrace(stack);
        }
    }

    /**
     * 致命异常 - 用于触发崩溃
     */
    private static class TamperFatalException extends RuntimeException {
        public TamperFatalException(String message) {
            super(message);
        }
    }
}
