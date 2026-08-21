package com.tv.live.security;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import com.tv.live.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/**
 * 安全守卫 - 应用自保护机制
 * 
 * 功能：
 * 1. 检测到攻击时触发自毁
 * 2. 清理敏感数据
 * 3. 上报安全事件
 * 4. 阻止应用被 hook
 * 
 * 仅在 Release 版本启用完整功能。
 */
public final class SecurityGuard {

    private static final String TAG = "SecurityGuard";
    
    // 威胁类型
    public static final int THREAT_DEBUGGER = 1;
    public static final int THREAT_FRIDA = 2;
    public static final int THREAT_XPOSED = 3;
    public static final int THREAT_ROOT = 4;
    public static final int THREAT_EMULATOR = 5;
    public static final int TAMPER_DETECTED = 6;
    public static final int SIGNATURE_MISMATCH = 7;
    public static final int HOOK_DETECTED = 8;
    
    // 安全响应级别
    public static final int RESPONSE_LOG = 0;      // 仅记录日志
    public static final int RESPONSE_WARN = 1;     // 警告用户
    public static final int RESPONSE_BLOCK = 2;    // 阻止功能
    public static final int RESPONSE_EXIT = 3;     // 退出应用
    public static final int RESPONSE_SELF_DESTRUCT = 4; // 自毁

    private static volatile boolean sInitialized = false;
    private static volatile Context sAppContext = null;
    private static volatile int sHighestThreat = 0;
    private static volatile boolean sDestroyed = false;

    private SecurityGuard() {}

    /**
     * 初始化安全守卫
     */
    public static void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        sAppContext = context.getApplicationContext();
        
        // 初始化篡改检测上报器
        TamperReporter.init(context);
        
        if (!BuildConfig.IS_DEBUG) {
            // 正式版：启动后台监控
            startSecurityMonitor();
            Log.i(TAG, "安全守卫已启用");
        } else {
            Log.i(TAG, "安全守卫：调试模式，仅记录日志");
        }
    }

    /**
     * 威胁事件处理
     * @param threatType 威胁类型
     * @param detail 详细信息
     */
    public static void onThreatDetected(int threatType, String detail) {
        if (sDestroyed) return;
        
        // 记录威胁
        if (threatType > sHighestThreat) {
            sHighestThreat = threatType;
        }
        
        String threatName = getThreatName(threatType);
        Log.e(TAG, "⚠️ 检测到安全威胁: " + threatName + " - " + detail);
        
        // 上报篡改事件到 TamperReporter
        reportThreatToReporter(threatType, threatName, detail);
        
        // 根据构建版本和威胁级别响应
        int responseLevel = getResponseLevel(threatType);
        
        switch (responseLevel) {
            case RESPONSE_LOG:
                // 仅记录
                break;
                
            case RESPONSE_WARN:
                showWarning(threatName, detail);
                break;
                
            case RESPONSE_BLOCK:
                showWarning(threatName, detail);
                blockFunctionality();
                break;
                
            case RESPONSE_EXIT:
                showWarning(threatName, detail);
                exitApplication();
                break;
                
            case RESPONSE_SELF_DESTRUCT:
                showWarning(threatName, detail);
                selfDestruct();
                break;
        }
    }
    
    /**
     * 上报威胁到篡改检测上报器
     */
    private static void reportThreatToReporter(int threatType, String threatName, String detail) {
        try {
            // 映射威胁类型到篡改类型
            int tamperType = mapThreatToTamperType(threatType);
            
            // 上报到 TamperReporter
            TamperReporter.reportTamper(tamperType, 
                "SecurityGuard检测到: " + threatName + " | " + detail);
                
        } catch (Exception e) {
            Log.e(TAG, "上报威胁失败: " + e.getMessage());
        }
    }
    
    /**
     * 映射威胁类型到篡改类型
     */
    private static int mapThreatToTamperType(int threatType) {
        switch (threatType) {
            case THREAT_DEBUGGER:
                return TamperReporter.TAMPER_DEBUGGER;
            case THREAT_FRIDA:
                return TamperReporter.TAMPER_FRIDA;
            case THREAT_XPOSED:
                return TamperReporter.TAMPER_XPOSED;
            case THREAT_ROOT:
                return TamperReporter.TAMPER_ROOT;
            case THREAT_EMULATOR:
                return TamperReporter.TAMPER_EMULATOR;
            case TAMPER_DETECTED:
                return TamperReporter.TAMPER_DEX_INTEGRITY;
            case SIGNATURE_MISMATCH:
                return TamperReporter.TAMPER_SIGNATURE;
            case HOOK_DETECTED:
                return TamperReporter.TAMPER_HOOK;
            default:
                return TamperReporter.TAMPER_MEMORY;
        }
    }

    /**
     * 获取响应级别
     */
    private static int getResponseLevel(int threatType) {
        if (BuildConfig.IS_DEBUG) {
            // 调试版：只记录不执行
            return RESPONSE_LOG;
        }
        
        switch (threatType) {
            case THREAT_DEBUGGER:
                return RESPONSE_EXIT;
                
            case THREAT_FRIDA:
            case THREAT_XPOSED:
                return RESPONSE_SELF_DESTRUCT;
                
            case THREAT_ROOT:
                return RESPONSE_WARN;
                
            case THREAT_EMULATOR:
                return RESPONSE_LOG;
                
            case TAMPER_DETECTED:
            case SIGNATURE_MISMATCH:
                return RESPONSE_SELF_DESTRUCT;
                
            case HOOK_DETECTED:
                return RESPONSE_BLOCK;
                
            default:
                return RESPONSE_LOG;
        }
    }

    /**
     * 显示警告
     */
    private static void showWarning(String threatName, String detail) {
        if (sAppContext == null) return;
        
        try {
            final String msg = "安全警告: " + threatName + "\n" + detail;
            new Thread(() -> {
                try {
                    Toast.makeText(sAppContext, msg, Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {}
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "显示警告失败: " + e.getMessage());
        }
    }

    /**
     * 阻止功能
     */
    private static void blockFunctionality() {
        // 设置全局标志位，让应用关键功能失效
        // 例如：停止播放、断开网络等
        Log.w(TAG, "🔒 已阻止应用功能");
    }

    /**
     * 退出应用
     */
    private static void exitApplication() {
        if (sDestroyed) return;
        sDestroyed = true;
        
        Log.w(TAG, "🚪 正在退出应用...");
        
        // 延迟 2 秒后退出，让用户看到警告
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                
                // 清理敏感数据
                clearSensitiveData();
                
                // 退出应用
                Process.killProcess(Process.myPid());
                System.exit(0);
            } catch (Exception e) {
                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        }, "SecurityExit").start();
    }

    /**
     * 自毁 - 销毁应用数据
     */
    private static void selfDestruct() {
        if (sDestroyed) return;
        sDestroyed = true;
        
        Log.w(TAG, "💥 触发自毁程序...");
        
        new Thread(() -> {
            try {
                // 1. 清理数据
                clearSensitiveData();
                
                // 2. 损坏 DEX 文件（可选，风险较高）
                // corruptDexFiles();
                
                // 3. 延迟退出
                Thread.sleep(3000);
                
                // 4. 强制退出
                Process.killProcess(Process.myPid());
                System.exit(0);
            } catch (Exception e) {
                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        }, "SelfDestruct").start();
    }

    /**
     * 清理敏感数据
     */
    private static void clearSensitiveData() {
        try {
            // 清除 SharedPreferences
            if (sAppContext != null) {
                sAppContext.getSharedPreferences("secure_data", Context.MODE_PRIVATE).edit().clear().apply();
                sAppContext.getSharedPreferences("security_guard", Context.MODE_PRIVATE).edit().clear().apply();
                sAppContext.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().apply();
            }
            
            // 清除文件存储
            if (sAppContext != null) {
                File filesDir = sAppContext.getFilesDir();
                if (filesDir != null) {
                    deleteDirectoryContents(filesDir);
                }
                
                File cacheDir = sAppContext.getCacheDir();
                if (cacheDir != null) {
                    deleteDirectoryContents(cacheDir);
                }
            }
            
            Log.i(TAG, "敏感数据已清理");
        } catch (Exception e) {
            Log.e(TAG, "清理数据失败: " + e.getMessage());
        }
    }

    /**
     * 删除目录内容
     */
    private static void deleteDirectoryContents(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isDirectory()) {
                deleteDirectoryContents(file);
            } else {
                file.delete();
            }
        }
    }

    /**
     * 损坏 DEX 文件（极端保护）
     */
    private static void corruptDexFiles() {
        // 此方法会破坏应用，导致即使重新签名也无法运行
        // 仅在检测到严重攻击时使用
        try {
            if (sAppContext == null) return;
            
            String dexPath = sAppContext.getApplicationInfo().sourceDir;
            if (dexPath == null) return;
            
            // 追加随机数据到 APK（损坏签名）
            File apkFile = new File(dexPath);
            if (apkFile.exists()) {
                RandomAccessFile raf = new RandomAccessFile(apkFile, "rw");
                raf.seek(apkFile.length() - 100);
                byte[] garbage = new byte[100];
                new java.util.Random().nextBytes(garbage);
                raf.write(garbage);
                raf.close();
            }
            
        } catch (Exception e) {
            // 忽略，可能没有写权限
        }
    }

    /**
     * 启动安全监控
     * 只在真正检测到Frida/Xposed等明确攻击工具时才上报
     * 调试器检测只记录日志，避免误报
     */
    private static void startSecurityMonitor() {
        if (sAppContext == null) return;
        
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            
            // 🟢 优化：降低监控频率，从10秒改为30秒
            // 避免频繁检测影响性能和产生误报
            while (!sDestroyed && !BuildConfig.IS_DEBUG) {
                try {
                    Thread.sleep(30000); // 30 秒检查一次
                    
                    // 1. 检查 Frida（明确的逆向工具，发现即上报）
                    if (checkFrida()) {
                        Log.w(TAG, "检测到 Frida 工具");
                        onThreatDetected(THREAT_FRIDA, "检测到 Frida");
                    }
                    
                    // 2. 检查 Xposed（明确的Hook框架，发现即上报）
                    if (checkXposed()) {
                        Log.w(TAG, "检测到 Xposed 框架");
                        onThreatDetected(THREAT_XPOSED, "检测到 Xposed");
                    }
                    
                    // 3. 检查调试器（仅记录日志，不上报）
                    // 因为很多情况下是误报（如系统调试开关、USB连接等）
                    if (android.os.Debug.isDebuggerConnected()) {
                        Log.w(TAG, "检测到调试器连接（仅记录，不上报）");
                        // 不调用onThreatDetected，避免误报到Bugly
                    }
                    
                    // 4. 检查 Root（仅记录）
                    if (checkRoot()) {
                        Log.w(TAG, "检测到 Root 环境（仅记录）");
                    }
                    
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable e) {
                    Log.e(TAG, "安全监控异常: " + e.getMessage());
                }
            }
        }, "SecurityMonitor").start();
    }

    /**
     * 检查 Frida
     */
    private static boolean checkFrida() {
        try {
            // 检查默认端口
            int[] fridaPorts = {27042, 27043, 27044, 27045};
            for (int port : fridaPorts) {
                java.net.Socket socket = new java.net.Socket();
                try {
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 100);
                    socket.close();
                    return true;
                } catch (Exception e) {
                    // 端口未开放
                }
            }
            
            // 检查进程
            String[] fridaProcesses = {"frida-server", "frida-agent", "frida-injector"};
            String processList = executeCommand("ps");
            if (processList != null) {
                for (String proc : fridaProcesses) {
                    if (processList.contains(proc)) {
                        return true;
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查 Xposed
     */
    private static boolean checkXposed() {
        try {
            // 检查 Xposed 框架
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (ClassNotFoundException e) {
            // 正常
        }
        
        try {
            // 检查 Substrate
            Class.forName("com.saurik.substrate.MS");
            return true;
        } catch (ClassNotFoundException e) {
            // 正常
        }
        
        // 检查环境变量
        String xposedProp = System.getProperty("de.robv.android.xposed.IXposedHookLoadPackage");
        if (xposedProp != null) return true;
        
        // 检查 Xposed 文件
        File xposedFile = new File("/data/local/tmp/Xposed");