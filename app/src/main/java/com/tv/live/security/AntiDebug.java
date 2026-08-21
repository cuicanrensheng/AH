package com.tv.live.security;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import com.tv.live.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * 反调试检测
 * 检测调试器、Frida、Xposed、模拟器等安全威胁
 */
public class AntiDebug {

    private static final String TAG = "AntiDebug";
    private static volatile boolean initialized = false;
    private static volatile boolean debugDetected = false;

    /**
     * 初始化反调试检测
     * @param context 上下文
     * @return 是否检测到调试
     */
    public static boolean init(Context context) {
        return init(context, true);
    }

    /**
     * 初始化反调试检测
     * @param context 上下文
     * @param enable 是否启用反调试检测（debug版本传false跳过检测）
     * @return 是否检测到调试
     */
    public static boolean init(Context context, boolean enable) {
        if (!enable) {
            Log.i(TAG, "反调试检测已禁用（调试模式）");
            initialized = true;
            debugDetected = false;
            return false;
        }
        if (initialized) return debugDetected;
        initialized = true;
        
        // 初始化TamperReporter
        TamperReporter.init(context);
        
        // 逐项检测并记录具体的威胁类型
        boolean debuggerFound = checkDebugger(context);
        boolean fridaFound = checkFrida();
        boolean xposedFound = checkXposed();
        boolean emulatorFound = checkEmulator(context);
        boolean testSettingsFound = checkTestSettings(context);
        
        debugDetected = debuggerFound || fridaFound || xposedFound || 
                        emulatorFound || testSettingsFound;
        
        if (debugDetected) {
            Log.e(TAG, "⚠️ 检测到调试环境！");
            
            // 上报各类威胁到TamperReporter
            if (!BuildConfig.IS_DEBUG) {
                if (debuggerFound) {
                    TamperReporter.reportTamper(
                        TamperReporter.TAMPER_DEBUGGER,
                        "AntiDebug检测到调试器连接");
                }
                if (fridaFound) {
                    TamperReporter.reportTamper(
                        TamperReporter.TAMPER_FRIDA,
                        "AntiDebug检测到Frida工具");
                }
                if (xposedFound) {
                    TamperReporter.reportTamper(
                        TamperReporter.TAMPER_XPOSED,
                        "AntiDebug检测到Xposed框架");
                }
                if (emulatorFound) {
                    TamperReporter.reportTamper(
                        TamperReporter.TAMPER_EMULATOR,
                        "AntiDebug检测到模拟器环境");
                }
            }
        }
        return debugDetected;
    }

    /**
     * 检测调试器
     */
    private static boolean checkDebugger(Context context) {
        try {
            // 1. 检测 Debug.isDebuggerConnected()
            if (Debug.isDebuggerConnected()) {
                Log.w(TAG, "检测到调试器连接");
                return true;
            }
            
            // 2. 检测 AndroidManifest 中的 debuggable 标志
            ApplicationInfo appInfo = context.getApplicationInfo();
            if ((appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                // debuggable=true 不一定是调试，可能是 debug 版本
                // 仅在 release 版本中视为可疑
                if (!isReleaseBuild()) {
                    return false;
                }
                Log.w(TAG, "应用标记为可调试");
                return true;
            }
            
            // 3. 检测 /proc/self/status 中的 TracerPid
            try {
                File file = new File("/proc/self/status");
                if (file.exists()) {
                    FileInputStream fis = new FileInputStream(file);
                    byte[] buffer = new byte[1024];
                    int len = fis.read(buffer);
                    fis.close();
                    String content = new String(buffer, 0, len);
                    if (content.contains("TracerPid:\t1") || 
                        content.contains("TracerPid: 0")) {
                        // 正常，无调试
                        return false;
                    }
                    // TracerPid 不为 0 或 1，说明被调试
                    for (String line : content.split("\n")) {
                        if (line.startsWith("TracerPid:")) {
                            String pidStr = line.substring("TracerPid:".length()).trim();
                            int pid = Integer.parseInt(pidStr);
                            if (pid > 1) {
                                Log.w(TAG, "检测到调试器 PID: " + pid);
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 无法读取可能是权限问题，不视为调试
            }
            
        } catch (Exception e) {
            Log.e(TAG, "检测调试器失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 检测 Frida
     */
    private static boolean checkFrida() {
        try {
            // 1. 检测 frida-server 进程
            File procDir = new File("/proc");
            String[] pids = procDir.list();
            if (pids != null) {
                for (String pid : pids) {
                    try {
                        File cmdLineFile = new File("/proc/" + pid + "/cmdline");
                        if (cmdLineFile.exists()) {
                            FileInputStream fis = new FileInputStream(cmdLineFile);
                            byte[] buffer = new byte[256];
                            int len = fis.read(buffer);
                            fis.close();
                            String cmdline = new String(buffer, 0, len);
                            if (cmdline.contains("frida") || 
                                cmdline.contains("frida-server") ||
                                cmdline.contains("frida-qt") ||
                                cmdline.contains("REJECT")) {
                                Log.w(TAG, "检测到 Frida 相关进程");
                                return true;
                            }
                        }
                    } catch (Exception e) {
                        // 忽略
                    }
                }
            }
            
            // 2. 检测默认 Frida 端口
            String[] fridaPorts = {"27042", "27043", "27044", "27045"};
            for (String port : fridaPorts) {
                try {
                    java.net.Socket socket = new java.net.Socket();
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1", 
                        Integer.parseInt(port)), 100);
                    socket.close();
                    Log.w(TAG, "检测到 Frida 默认端口: " + port);
                    return true;
                } catch (Exception e) {
                    // 端口未开放，正常
                }
            }
            
            // 3. 检测 Frida 相关文件
            String[] fridaPaths = {
                "/data/local/tmp/frida-server",
                "/data/local/tmp/frida-gadget",
                "/system/bin/frida-server",
                "/system/xbin/frida-server"
            };
            for (String path : fridaPaths) {
                if (new File(path).exists()) {
                    Log.w(TAG, "检测到 Frida 文件: " + path);
                    return true;
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "检测 Frida 失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 检测 Xposed/LSPosed
     */
    private static boolean checkXposed() {
        try {
            // 1. 检测 Xposed 框架类
            try {
                Class.forName("de.robv.android.xposed.XposedBridge");
                Log.w(TAG, "检测到 Xposed 框架");
                return true;
            } catch (ClassNotFoundException e) {
                // 正常
            }
            
            // 2. 检测 Xposed 相关文件
            String[] xposedPaths = {
                "/data/adb/lspd/config",
                "/data/adb/modules/lsposed",
                "/system/framework/XposedBridge.jar",
                "/data/local/tmp/XposedBridge.jar"
            };
            for (String path : xposedPaths) {
                if (new File(path).exists()) {
                    Log.w(TAG, "检测到 Xposed/LSPosed: " + path);
                    return true;
                }
            }
            
            // 3. 检测 Xposed 环境变量
            try {
                for (java.util.Map.Entry<String, String> entry : System.getenv().entrySet()) {
                    if (entry.getKey().contains("XPOSED") || 
                        entry.getValue().contains("xposed")) {
                        Log.w(TAG, "检测到 Xposed 环境变量");
                        return true;
                    }
                }
            } catch (Exception e) {
                // 忽略
            }
            
        } catch (Exception e) {
            Log.e(TAG, "检测 Xposed 失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 检测模拟器
     */
    private static boolean checkEmulator(Context context) {
        try {
            // 1. 检测常见模拟器特征
            String[] emulatorFiles = {
                "/dev/socket/qemud",
                "/dev/qemu_pipe",
                "/system/lib/libc_malloc_debug_qemu.so",
                "/sys/qemu_trace",
                "/system/bin/qemu-props"
            };
            for (String path : emulatorFiles) {
                if (new File(path).exists()) {
                    Log.w(TAG, "检测到模拟器文件: " + path);
                    return true;
                }
            }
            
            // 2. 检测模拟器 Build 属性
            String fingerprint = android.os.Build.FINGERPRINT;
            if (fingerprint.contains("generic") || 
                fingerprint.contains("sdk") ||
                fingerprint.contains("vbox") ||
                fingerprint.contains("emulator")) {
                Log.w(TAG, "检测到模拟器指纹");
                return true;
            }
            
            // 3. 检测 QEMU 管道
            try {
                File qemuPipe = new File("/dev/socket/qemud");
                if (qemuPipe.exists()) {
                    return true;
                }
            } catch (Exception e) {
                // 忽略
            }
            
        } catch (Exception e) {
            Log.e(TAG, "检测模拟器失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 检测测试设置
     */
    private static boolean checkTestSettings(Context context) {
        try {
            // 检测 USB 调试开关
            if (Settings.Global.getInt(context.getContentResolver(), 
                Settings.Global.ADB_ENABLED, 0) == 1) {
                // USB 调试开启，可能是调试环境
                // 仅在 release 版本中报警告
                if (isReleaseBuild()) {
                    Log.w(TAG, "USB 调试已开启");
                    // 不直接返回 true，因为用户可能在使用 ADB
                }
            }
            
            // 检测允许模拟位置
            String mockLocationApp = Settings.Secure.getString(
                context.getContentResolver(), 
                Settings.Secure.ALLOW_MOCK_LOCATION);
            if (mockLocationApp != null && !mockLocationApp.isEmpty()) {
                Log.w(TAG, "检测到模拟位置应用");
                return true;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "检测测试设置失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 检测是否是 release 版本
     */
    private static boolean isReleaseBuild() {
        try {
            // 通过检查 BuildConfig.APPLICATION_ID 判断
            // release 版本通常不包含 "debug" 后缀
            String packageName = "com.tv.live";
            return !packageName.contains("debug");
        } catch (Exception e) {
            return true; // 默认为 release
        }
    }

    /**
     * 获取当前检测状态
     */
    public static boolean isDebugDetected() {
        return debugDetected;
    }

    /**
     * 计算文件 MD5（用于完整性校验）
     */
    public static String calculateMD5(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return null;
            
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                md.update(buffer, 0, len);
            }
            fis.close();
            
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "计算 MD5 失败: " + e.getMessage());
            return null;
        }
    }
}
