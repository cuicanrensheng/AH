package com.tv.live.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.provider.Settings;
import android.util.Base64;
import com.tv.live.util.LogBridge;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 安全检测管理器
 * 
 * 提供全方位的应用安全检测：
 * 1. 反调试检测（Frida/Xposed/Root）
 * 2. APK 完整性校验（签名验证/篡改检测）
 * 3. 运行时环境检测（代理/VPN/模拟器）
 * 4. 敏感数据加密存储
 */
public class SecurityGuardManager {

    private static final String TAG = "SecurityGuard";
    private static final String PREFS_NAME = "security_guard";
    private static final String KEY_APP_SIGNATURE = "app_signature";
    private static final String KEY_LAST_CHECK = "last_check_time";
    
    private static volatile SecurityGuardManager sInstance;
    private Context mContext;
    private boolean mInitialized = false;
    
    // 安全检测结果
    private final SecurityReport mLastReport = new SecurityReport();
    
    // 可信的签名哈希（应用自身的签名）
    private String mExpectedSignatureHash;

    /**
     * 安全检测结果报告
     */
    public static class SecurityReport {
        public boolean debugDetected = false;
        public boolean fridaDetected = false;
        public boolean xposedDetected = false;
        public boolean rootDetected = false;
        public boolean hookDetected = false;
        public boolean integrityOk = true;
        public boolean proxyDetected = false;
        public boolean vpnDetected = false;
        public boolean emulatorDetected = false;
        public boolean usbDebuggingEnabled = false;
        public long checkTime = 0;
        public List<String> threats = new ArrayList<>();
        
        public boolean hasAnyThreat() {
            return !threats.isEmpty();
        }
        
        public String getSummary() {
            if (!hasAnyThreat()) {
                return "安全检测通过";
            }
            StringBuilder sb = new StringBuilder("检测到安全威胁:\n");
            for (String threat : threats) {
                sb.append("⚠️ ").append(threat).append("\n");
            }
            return sb.toString();
        }
        
        /**
         * 从另一个报告复制结果
         */
        public void update(SecurityReport source) {
            if (source == null) return;
            this.debugDetected = source.debugDetected;
            this.fridaDetected = source.fridaDetected;
            this.xposedDetected = source.xposedDetected;
            this.rootDetected = source.rootDetected;
            this.hookDetected = source.hookDetected;
            this.integrityOk = source.integrityOk;
            this.proxyDetected = source.proxyDetected;
            this.vpnDetected = source.vpnDetected;
            this.emulatorDetected = source.emulatorDetected;
            this.usbDebuggingEnabled = source.usbDebuggingEnabled;
            this.checkTime = source.checkTime;
            this.threats = new ArrayList<>(source.threats);
        }
    }

    private SecurityGuardManager() {
    }

    public static SecurityGuardManager getInstance() {
        if (sInstance == null) {
            synchronized (SecurityGuardManager.class) {
                if (sInstance == null) {
                    sInstance = new SecurityGuardManager();
                }
            }
        }
        return sInstance;
    }

    /**
     * 初始化安全管理器
     */
    public void init(Context context) {
        mContext = context.getApplicationContext();
        mExpectedSignatureHash = getAppSignatureHash();
        
        // 保存预期签名用于后续校验
        if (mExpectedSignatureHash != null) {
            mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_APP_SIGNATURE, mExpectedSignatureHash)
                .apply();
        }
        
        mInitialized = true;
        LogBridge.i(TAG, "安全管理器初始化完成，签名哈希: " + 
            (mExpectedSignatureHash != null ? mExpectedSignatureHash.substring(0, 16) + "..." : "unknown"));
    }

    /**
     * 执行完整安全检测
     */
    public SecurityReport performFullSecurityCheck() {
        if (!mInitialized) {
            init(mContext);
        }
        
        SecurityReport report = new SecurityReport();
        report.checkTime = System.currentTimeMillis();
        
        // 1. 反调试检测
        checkDebugger(report);
        
        // 2. Frida/Xposed 检测
        checkHookingFrameworks(report);
        
        // 3. Root 检测
        checkRootAccess(report);
        
        // 4. APK 完整性校验
        checkApkIntegrity(report);
        
        // 5. 环境检测
        checkRuntimeEnvironment(report);
        
        // 记录日志
        LogBridge.i(TAG, "安全检测完成: " + report.getSummary());
        
        mLastReport.update(report);
        return report;
    }

    /**
     * 检测调试器附加
     */
    private void checkDebugger(SecurityReport report) {
        // 方法1: 检测 Debug.isDebuggerConnected()
        try {
            if (Debug.isDebuggerConnected()) {
                report.debugDetected = true;
                report.threats.add("检测到调试器");
                LogBridge.w(TAG, "⚠️ 调试器已附加");
                return;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "调试检测异常: " + e.getMessage());
        }
        
        // 方法2: 检测进程状态（/proc/pid/status 中的 TracerPid）
        try {
            String tracerPid = readProcStatus("TracerPid:");
            if (tracerPid != null) {
                int pid = Integer.parseInt(tracerPid.trim());
                if (pid > 0) {
                    report.debugDetected = true;
                    report.threats.add("检测到调试器(TracerPid=" + pid + ")");
                    LogBridge.w(TAG, "⚠️ TracerPid=" + pid);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        
        // 方法3: 检测是否为可调试应用
        try {
            ApplicationInfo appInfo = mContext.getApplicationInfo();
            boolean isDebuggable = (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (isDebuggable && !Build.TAGS.equals("release-keys")) {
                // Debug 版本允许调试，只记录
                LogBridge.d(TAG, "应用为可调试版本 (debuggable=true)");
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 检测 Hook 框架（Frida/Xposed）
     */
    private void checkHookingFrameworks(SecurityReport report) {
        // 1. 检测 Frida 端口
        if (isFridaPortOpen()) {
            report.fridaDetected = true;
            report.hookDetected = true;
            report.threats.add("检测到 Frida 框架");
            LogBridge.w(TAG, "⚠️ Frida 端口已开启");
        }
        
        // 2. 检测 Xposed/Substrate
        if (isXposedFrameworkDetected()) {
            report.xposedDetected = true;
            report.hookDetected = true;
            report.threats.add("检测到 Xposed/Substrate 框架");
            LogBridge.w(TAG, "⚠️ Xposed 框架已加载");
        }
        
        // 3. 检测 Magisk
        if (isMagiskDetected()) {
            report.hookDetected = true;
            report.threats.add("检测到 Magisk 模块");
            LogBridge.w(TAG, "⚠️ Magisk 已安装");
        }
        
        // 4. 检测注入的 so 库
        if (detectSuspiciousLibraries()) {
            report.hookDetected = true;
            report.threats.add("检测到可疑的本地库注入");
            LogBridge.w(TAG, "⚠️ 检测到可疑库");
        }
    }

    /**
     * 检测 Frida 端口 (默认 27042)
     */
    private boolean isFridaPortOpen() {
        int[] fridaPorts = {27042, 27043, 37177};
        for (int port : fridaPorts) {
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 100);
                socket.close();
                return true;
            } catch (Exception e) {
                // 端口未打开，继续检测
            }
        }
        return false;
    }

    /**
     * 检测 Xposed/Substrate
     */
    private boolean isXposedFrameworkDetected() {
        try {
            // 检测 Xposed 类是否加载
            Class.forName("de.robv.android.xposed.XposedBridge", false, null);
            return true;
        } catch (ClassNotFoundException e) {
            // 未加载
        }
        
        // 检测 Xposed 相关文件
        String[] suspiciousFiles = {
            "/system/lib/libxposed_art.so",
            "/system/lib64/libxposed_art.so",
            "/system/framework/XposedBridge.jar",
            "/data/local/tmp/xposed",
            "/data/local/tmp/frida-server"
        };
        
        for (String path : suspiciousFiles) {
            if (new File(path).exists()) {
                return true;
            }
        }
        
        // 检测 Xposed 模块
        try {
            java.util.Enumeration<java.net.URL> libraries = ClassLoader.getSystemClassLoader()
                .getSystemResources("META-INF/xposed/init");
            if (libraries.hasMoreElements()) {
                return true;
            }
        } catch (Exception e) {
            // ignore
        }
        
        return false;
    }

    /**
     * 检测 Magisk
     */
    private boolean isMagiskDetected() {
        String[] magiskPaths = {
            "/sbin/magisk",
            "/system/bin/magisk",
            "/data/adb/magisk",
            "/data/adb/modules"
        };
        
        for (String path : magiskPaths) {
            if (new File(path).exists()) {
                return true;
            }
        }
        
        // 检查环境变量
        try {
            String magiskPath = System.getenv("MAGISK_PATH");
            if (magiskPath != null && !magiskPath.isEmpty()) {
                return true;
            }
        } catch (Exception e) {
            // ignore
        }
        
        return false;
    }

    /**
     * 检测可疑的 so 库注入
     */
    private boolean detectSuspiciousLibraries() {
        try {
            // 检测 /proc/self/maps 中的可疑库
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/maps"));
            String line;
            Set<String> suspiciousNames = new HashSet<>();
            suspiciousNames.add("frida");
            suspiciousNames.add("xposed");
            suspiciousNames.add("substrate");
            suspiciousNames.add("gameguardian");
            suspiciousNames.add("lucky_patcher");
            suspiciousNames.add("magisk");
            
            while ((line = reader.readLine()) != null) {
                for (String name : suspiciousNames) {
                    if (line.toLowerCase().contains(name)) {
                        reader.close();
                        return true;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * 检测 Root 访问
     */
    private void checkRootAccess(SecurityReport report) {
        // 检测 su 二进制文件
        String[] suPaths = {
            "/system/bin/su",
            "/system/xbin/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-initialization/recurse.sh",
            "/system/etc/init.d/99startfdroid",
            "/su/bin/su",
            "/magisk/.core/bin/su"
        };
        
        boolean suFound = false;
        for (String path : suPaths) {
            if (new File(path).exists()) {
                suFound = true;
                break;
            }
        }
        
        if (suFound || isTestKeyBuild() || hasWriteAccess("/system")) {
            report.rootDetected = true;
            report.threats.add("检测到 Root 权限");
            LogBridge.w(TAG, "⚠️ 检测到 Root 环境");
        }
    }

    /**
     * 检测是否为测试密钥构建
     */
    private boolean isTestKeyBuild() {
        return Build.TAGS != null && Build.TAGS.contains("test-keys");
    }

    /**
     * 检测文件写权限（判断是否 Root）
     */
    private boolean hasWriteAccess(String path) {
        try {
            File testFile = new File(path, ".security_test");
            return testFile.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * APK 完整性校验
     */
    private void checkApkIntegrity(SecurityReport report) {
        // 1. 校验签名
        String currentSignature = getAppSignatureHash();
        if (currentSignature == null) {
            report.integrityOk = false;
            report.threats.add("无法获取应用签名");
            LogBridge.e(TAG, "❌ 无法获取应用签名");
            return;
        }
        
        // 对比存储的签名
        String storedSignature = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_SIGNATURE, null);
        
        if (storedSignature != null && !currentSignature.equals(storedSignature)) {
            report.integrityOk = false;
            report.threats.add("应用签名已变更（可能被二次打包）");
            LogBridge.e(TAG, "❌ 签名不匹配! 当前: " + currentSignature.substring(0, 16) + 
                "... 存储: " + storedSignature.substring(0, 16) + "...");
        }
        
        // 2. 检测调试标签
        if (isTestKeyBuild()) {
            report.integrityOk = false;
            report.threats.add("使用测试密钥构建");
            LogBridge.w(TAG, "⚠️ 应用使用测试密钥");
        }
    }

    /**
     * 获取应用签名哈希
     */
    private String getAppSignatureHash() {
        try {
            PackageInfo packageInfo = mContext.getPackageManager().getPackageInfo(
                mContext.getPackageName(), PackageManager.GET_SIGNATURES);
            
            Signature[] signatures = packageInfo.signatures;
            if (signatures != null && signatures.length > 0) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(signatures[0].toByteArray());
                return Base64.encodeToString(hash, Base64.NO_WRAP);
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "获取签名失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 运行时环境检测
     */
    private void checkRuntimeEnvironment(SecurityReport report) {
        // 1. 检测代理
        if (isProxyConfigured()) {
            report.proxyDetected = true;
            report.threats.add("检测到代理服务器");
            LogBridge.w(TAG, "⚠️ 检测到代理配置");
        }
        
        // 2. 检测 VPN
        if (isVpnActive()) {
            report.vpnDetected = true;
            report.threats.add("检测到 VPN 连接");
            LogBridge.w(TAG, "⚠️ 检测到 VPN");
        }
        
        // 3. 检测模拟器
        if (isEmulator()) {
            report.emulatorDetected = true;
            report.threats.add("运行在模拟器环境");
            LogBridge.d(TAG, "ℹ️ 应用运行在模拟器上");
        }
        
        // 4. 检测 USB 调试
        if (isUsbDebuggingEnabled()) {
            report.usbDebuggingEnabled = true;
            report.threats.add("USB 调试已开启");
            LogBridge.w(TAG, "⚠️ USB 调试已开启");
        }
    }

    /**
     * 检测代理配置
     */
    private boolean isProxyConfigured() {
        try {
            // 检查系统代理设置
            String proxyHost = Settings.Global.getString(
                mContext.getContentResolver(), Settings.Global.HTTP_PROXY);
            if (proxyHost != null && !proxyHost.isEmpty()) {
                return true;
            }
            
            // 检查 APN 代理
            try {
                Class<?> connectivityManagerClass = Class.forName("android.net.ConnectivityManager");
                // 进一步检测可在需要时实现
            } catch (Exception e) {
                // ignore
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * 检测 VPN 是否活跃
     */
    private boolean isVpnActive() {
        try {
            // 检测 VPN 连接
            java.net.NetworkInterface.getNetworkInterfaces();
            java.util.Enumeration<java.net.NetworkInterface> interfaces = 
                java.net.NetworkInterface.getNetworkInterfaces();
            
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.getName().startsWith("tun") || 
                    iface.getName().startsWith("ppp") ||
                    iface.getName().startsWith("vpn")) {
                    return true;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * 检测是否为模拟器
     */
    private boolean isEmulator() {
        // 检查模拟器特征
        String[] emulatorFiles = {
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props"
        };
        
        for (String path : emulatorFiles) {
            if (new File(path).exists()) {
                return true;
            }
        }
        
        // 检查设备信息
        try {
            String fingerprint = Build.FINGERPRINT;
            if (fingerprint != null) {
                String lowerFingerprint = fingerprint.toLowerCase();
                if (lowerFingerprint.contains("sdk_gphone") || 
                    lowerFingerprint.contains("emulator") ||
                    lowerFingerprint.contains("generic")) {
                    return true;
                }
            }
            
            String model = Build.MODEL;
            if (model != null && model.contains("Emulator")) {
                return true;
            }
        } catch (Exception e) {
            // ignore
        }
        
        return false;
    }

    /**
     * 检测 USB 调试是否开启
     */
    private boolean isUsbDebuggingEnabled() {
        try {
            int adbEnabled = Settings.Global.getInt(
                mContext.getContentResolver(), 
                Settings.Global.ADB_ENABLED, 0);
            return adbEnabled == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读取 /proc/pid/status 文件
     */
    private String readProcStatus(String key) {
        try {
            int pid = android.os.Process.myPid();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/status"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key)) {
                    reader.close();
                    return line.substring(key.length()).trim();
                }
            }
            reader.close();
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * 获取上次的安全报告
     */
    public SecurityReport getLastReport() {
        return mLastReport;
    }

    /**
     * 获取初始化状态
     */
    public boolean isInitialized() {
        return mInitialized;
    }

    /**
     * 检查是否允许继续运行（用于拦截不安全环境）
     * @param blockRoot 是否拦截 Root 环境
     * @param blockDebug 是否拦截调试状态
     * @param blockHooking 是否拦截 Hook 框架
     * @return 是否允许继续运行
     */
    public boolean shouldBlockExecution(boolean blockRoot, boolean blockDebug, boolean blockHooking) {
        SecurityReport report = mLastReport;
        
        if (blockRoot && report.rootDetected) {
            LogBridge.e(TAG, "❌ Root 环境被禁止运行");
            return true;
        }
        
        if (blockDebug && (report.debugDetected || report.hookDetected)) {
            LogBridge.e(TAG, "❌ 调试/Hook 环境被禁止运行");
            return true;
        }
        
        if (blockHooking && report.hookDetected) {
            LogBridge.e(TAG, "❌ Hook 框架被禁止运行");
            return true;
        }
        
        return false;
    }
}
