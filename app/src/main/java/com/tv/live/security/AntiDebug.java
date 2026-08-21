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
        
        // 🟢 优化：提高调试器检测门槛
        // 只有当Debug.isDebuggerConnected()明确返回true时才判定为调试器
        // 避免因TracerPid等信息误判
        boolean confirmedDebugger = Debug.isDebuggerConnected();
        
        // 确认的篡改：Frida/Xposed（这些明确是逆向工具）
        // 调试器需要更严格的验证
        debugDetected = fridaFound || xposedFound || confirmedDebugger;
        
        if (debugDetected) {
            Log.w(TAG, "⚠️ 检测到可疑环境: " + 
                (confirmedDebugger ? "调试器" : "") +
                (fridaFound ? "Frida" : "") +
                (xposedFound ? "Xposed" : ""));
            
            // 上报各类威胁到TamperReporter（仅上报确认的篡改）
            if (!BuildConfig.IS_DEBUG) {
                if (confirmedDebugger && !fridaFound && !xposedFound) {
                    // 仅检测到调试器：使用reportSuspicious而非reportTamper
                    // 因为很多情况可能是误报
                    TamperReporter.reportSuspicious(
                        TamperReporter.TAMPER_DEBUGGER,
                        "检测到调试器连接，但不确认是否为篡改");
                }
                if (fridaFound) {
                    // Frida是明确的逆向工具，直接上报篡改
                    TamperReporter.reportTamper(
                        TamperReporter.TAMPER_FRIDA,
                        "AntiDebug检测到Frida工具");
                }
                if (xposedFound) {
                    // Xposed是明确的Hook框架，直接上报篡改
                    TamperReporter.reportTamper(
                        TamperReporter.TAMPER_XPOSED,
                        "AntiDebug检测到Xposed框架");
                }
            }
        }
        
        // 记录警告级别的可疑环境（不上报崩溃，仅记录）
        boolean adbEnabled = checkAdbEnabled(context);
        boolean mockLocation = checkMockLocation(context);
        
        if (adbEnabled) {
            Log.w(TAG, "⚠️ USB调试已开启（可能用于开发调试，非篡改）");
        }
        if (mockLocation) {
            Log.w(TAG, "⚠️ 检测到模拟位置应用（可能用于测试，非篡改）");
        }
        if (emulatorFound) {
            Log.w(TAG, "⚠️ 检测到模拟器环境（可能是合法测试，非篡改）");
        }
        
        // 记录调试器检测的详细信息（供调试使用）
        if (debuggerFound && !confirmedDebugger) {
            Log.w(TAG, "调试器检测结果存疑：TracerPid检测到异常但Debug.isDebuggerConnected()未确认");
        }
        
        return debugDetected;
    }

    /**
     * 检测调试器（参考信息，不作为最终判定依据）
     * 真正的判定在init方法中使用Debug.isDebuggerConnected()
     */
    private static boolean checkDebugger(Context context) {
        try {
            // 1. 检测 Debug.isDebuggerConnected() - 这是最可靠的检测方式
            if (Debug.isDebuggerConnected()) {
                Log.w(TAG, "检测到调试器连接 (Debug.isDebuggerConnected=true)");
                return true;
            }
            
            // 2. 检测 AndroidManifest 中的 debuggable 标志（仅记录，不判定）
            ApplicationInfo appInfo = context.getApplicationInfo();
            boolean isDebuggable = (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (isDebuggable && !BuildConfig.IS_DEBUG) {
                // 正式版但有debuggable标志，可能是被重打包，记录一下
                Log.w(TAG, "⚠️ 正式版应用但标记为可调试（可能被重打包）");
                // 不作为篡改依据，因为可能是某些电视系统的特殊设置
            }
            
            // 3. 检测 /proc/self/status 中的 TracerPid（仅记录参考信息）
            // 注意：这个检测在某些设备上可能不可靠
            try {
                File file = new File("/proc/self/status");
                if (file.exists()) {
                    FileInputStream fis = new FileInputStream(file);
                    byte[] buffer = new byte[1024];
                    int len = fis.read(buffer);
                    fis.close();
                    String content = new String(buffer, 0, len);
                    
                    // 解析 TracerPid
                    for (String line : content.split("\n")) {
                        if (line.startsWith("TracerPid:")) {
                            String pidStr = line.substring("TracerPid:".length()).trim();
                            int pid = Integer.parseInt(pidStr);
                            // 仅记录日志供调试，不作为判定依据
                            if (pid != 0) {
                                Log.d(TAG, "TracerPid=" + pid + " (参考信息)");
                                // 注意：即使TracerPid!=0，也不一定是调试器
                                // 某些系统进程可能会设置这个值
                                // 只有当Debug.isDebuggerConnected()同时为true才确认
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // 无法读取可能是权限问题，不视为异常
            }
            
        } catch (Exception e) {
            Log.e(TAG, "检测调试器失败: " + e.getMessage());
        }
        // 只有Debug.isDebuggerConnected()返回true才是可靠的调试器检测
        return Debug.isDebuggerConnected();
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
     * 检测 USB 调试是否开启
     * 注意：USB调试开启≠应用被篡改，可能是用户在开发调试
     */
    private static boolean checkAdbEnabled(Context context) {
        try {
            if (Settings.Global.getInt(context.getContentResolver(), 
                Settings.Global.ADB_ENABLED, 0) == 1) {
                Log.w(TAG, "USB调试已开启");
                return true;
            }
        } catch (Exception e) {
            // 忽略
        }
        return false;
    }
    
    /**
     * 检测是否启用了模拟位置
     * 注意：模拟位置≠应用被篡改，可能是用户在测试
     */
    private static boolean checkMockLocation(Context context) {
        try {
            String mockLocationApp = Settings.Secure.getString(
                context.getContentResolver(), 
                Settings.Secure.ALLOW_MOCK_LOCATION);
            if (mockLocationApp != null && !mockLocationApp.isEmpty()) {
                Log.w(TAG, "检测到模拟位置应用");
                return true;
            }
        } catch (Exception e) {
            // 忽略
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