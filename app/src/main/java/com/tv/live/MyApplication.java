package com.tv.live;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tv.live.BuildConfig;
import com.tv.live.util.AppCacheInspector;
import com.tv.live.util.LogCollector;
import com.tv.live.util.LogServer;
import com.tv.live.util.NetUtil;
import com.tv.live.util.HuyaCacheGovernor;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.HuyaCredentials;
import com.tv.live.util.EncryptedStorage;
import com.tv.live.util.SecurityCertificatePinner;
import com.tv.live.util.SecurityGuardManager;
import com.tv.live.util.SecureDataStore;
import com.tv.live.util.DeviceCapabilities;
import com.tv.live.util.CloudLogSender;
import com.tv.live.util.BuglyLogSender;
import com.tv.live.util.ExceptionReporter;
import com.tv.live.security.SecurityCore;
import com.tv.live.security.AntiDebug;
import com.tv.live.security.StringObfuscator;
import com.tv.live.security.DexProtector;
import com.tv.live.security.StringProtector;
import com.tv.live.security.SecurityGuard;
public class MyApplication extends Application {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();

        // ===== 关键路径：只做最小化初始化，确保启动速度 =====
        // 1. 初始化日志收集器（内存操作，很快）
        LogCollector.getInstance();

        // 2. 在后台线程执行所有耗时初始化
        initializeAsync();

        LogCollector.getInstance().info("MyApplication", "应用启动完成（快速模式）");
    }

    /**
     * 后台异步初始化所有组件
     * 使用多个后台线程并行执行，减少总耗时
     */
    private void initializeAsync() {
        // 线程1：安全相关（最高优先级，最先执行）
        new Thread(new Runnable() {
            @Override
            public void run() {
                initSecurity();
            }
        }, "init-security").start();

        // 线程2：Bugly 初始化
        new Thread(new Runnable() {
            @Override
            public void run() {
                initBugly();
            }
        }, "init-bugly").start();

        // 延迟 2 秒后初始化其他组件
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                initLogServices();
                initCacheCleanup();
                initHuyaSDK();
                initDeviceCapabilities();
            }
        }, 2000);
    }

    /**
     * 初始化安全组件（后台线程）
     * 正式版自动开启反调试，调试版自动关闭反调试
     */
    private void initSecurity() {
        // 🔍 反调试检测 - 正式版启用，调试版跳过
        try {
            // enable: 正式版(true)开启检测，调试版(false)跳过检测
            boolean debugDetected = AntiDebug.init(this, !BuildConfig.IS_DEBUG);
            if (debugDetected) {
                LogCollector.getInstance().error("MyApplication", "⚠️ 检测到调试环境，应用可能被逆向分析");
                // 正式版检测到调试时可选：终止应用或限制功能
                // System.exit(0);
            } else {
                String mode = BuildConfig.IS_DEBUG ? "调试版：跳过反调试检测" : "反调试检测通过";
                LogCollector.getInstance().info("MyApplication", mode);
            }
        } catch (Throwable e) {
            Log.w("MyApplication", "反调试检测失败: " + e.getMessage());
        }

        // 使用字符串混淆
        try {
            String decodedKey = StringObfuscator.decodeString("a1b2c3d4e5f6");
            if (decodedKey != null) {
                LogCollector.getInstance().info("MyApplication", "字符串混淆测试通过");
            }
        } catch (Throwable e) {
            // 忽略，仅用于测试
        }

        try {
            SecurityCore.init();
            LogCollector.getInstance().info("MyApplication", "SecurityCore 初始化成功");
        } catch (Throwable e) {
            Log.w("MyApplication", "SecurityCore init failed: " + e.getMessage());
        }

        // ✅ URL 解密必须在安全检查之前完成，确保网络功能始终可用
        try {
            UrlConfig.fillPublicFields();
            LogCollector.getInstance().info("MyApplication", "URL 配置解密完成");
        } catch (Throwable e) {
            Log.w("MyApplication", "URL 配置解密失败: " + e.getMessage());
        }

        // 🔒 安全检查：仅记录告警，不阻断初始化（避免签名/DEX校验导致应用无法使用）
        boolean securityPassed = true;
        try {
            if (!SecurityCheck.verifyOnStart(this)) {
                LogCollector.getInstance().warn("MyApplication", "⚠️ 安全检查未通过（已降级运行模式）");
                securityPassed = false;
                // 注意：不再 return，继续初始化其他组件
            } else {
                LogCollector.getInstance().info("MyApplication", "安全检查通过");
            }
        } catch (Throwable e) {
            Log.e("MyApplication", "SecurityCheck failed: " + e.getMessage());
        }

        // 🔒 DEX 保护（正式版启用，即使安全检查未通过也尝试初始化）
        try {
            DexProtector.init(this);
            LogCollector.getInstance().info("MyApplication", "DEX 保护初始化完成");
        } catch (Throwable e) {
            Log.w("MyApplication", "DEX 保护初始化失败: " + e.getMessage());
        }

        // 🔐 字符串动态解密保护（正式版启用）
        try {
            StringProtector.init(!BuildConfig.IS_DEBUG);
            // 注册敏感字符串（使用加密存储）
            StringProtector.register("huya_app_id", 
                BuildConfig.IS_DEBUG ? "123456" : StringProtector.quickEncrypt("123456"));
            StringProtector.register("huya_app_key",
                BuildConfig.IS_DEBUG ? "d8f193dd" : StringProtector.quickEncrypt("d8f193dd"));
            StringProtector.register("huya_game_id",
                BuildConfig.IS_DEBUG ? "2336" : StringProtector.quickEncrypt("2336"));
            LogCollector.getInstance().info("MyApplication", "字符串保护初始化完成");
        } catch (Throwable e) {
            Log.w("MyApplication", "字符串保护初始化失败: " + e.getMessage());
        }

        // 🛡️ 安全守卫（应用自保护）
        try {
            SecurityGuard.init(this);
            LogCollector.getInstance().info("MyApplication", "安全守卫初始化完成");
        } catch (Throwable e) {
            Log.w("MyApplication", "安全守卫初始化失败: " + e.getMessage());
        }

        // 🔐 初始化加密存储和虎牙 SDK 凭证
        try {
            EncryptedStorage storage = EncryptedStorage.getInstance(this);
            if (storage.isInitialized()) {
                LogCollector.getInstance().info("MyApplication", "加密存储初始化成功");
            } else {
                LogCollector.getInstance().warn("MyApplication", "加密存储未完全初始化");
            }
        } catch (Throwable e) {
            Log.w("MyApplication", "加密存储初始化失败: " + e.getMessage());
        }

        try {
            HuyaCredentials credentials = HuyaCredentials.getInstance(this);
            if (credentials.isInitialized()) {
                LogCollector.getInstance().info("MyApplication", "虎牙 SDK 凭证加载成功: " + credentials.getCredentialsSummary());
            } else {
                LogCollector.getInstance().warn("MyApplication", "虎牙 SDK 凭证未完全初始化");
            }
        } catch (Throwable e) {
            Log.w("MyApplication", "虎牙 SDK 凭证初始化失败: " + e.getMessage());
        }

        // 🔒 初始化 SSL 证书管理器（TOFU 证书锁定）
        try {
            SecurityCertificatePinner sslPinner = SecurityCertificatePinner.getInstance();
            sslPinner.init(this);
            LogCollector.getInstance().info("MyApplication", "SSL 证书管理器初始化完成");
        } catch (Throwable e) {
            Log.w("MyApplication", "SSL 证书管理器初始化失败: " + e.getMessage());
        }

        // 🌐 初始化网络工具（配置安全 HTTP 客户端）
        try {
            NetUtil.init(this);
            LogCollector.getInstance().info("MyApplication", "网络工具初始化完成");
        } catch (Throwable e) {
            Log.w("MyApplication", "网络工具初始化失败: " + e.getMessage());
        }

        // 🛡️ 初始化安全检测管理器
        try {
            SecurityGuardManager guardManager = SecurityGuardManager.getInstance();
            guardManager.init(this);
            
            // 执行首次安全检测
            SecurityGuardManager.SecurityReport report = guardManager.performFullSecurityCheck();
            if (report.hasAnyThreat()) {
                LogCollector.getInstance().warn("MyApplication", 
                    "安全检测发现风险: " + report.getSummary());
            } else {
                LogCollector.getInstance().info("MyApplication", "安全检测通过");
            }
        } catch (Throwable e) {
            Log.w("MyApplication", "安全检测初始化失败: " + e.getMessage());
        }

        // 🔐 初始化安全数据存储
        try {
            SecureDataStore secureStore = SecureDataStore.getInstance();
            secureStore.init(this);
            if (secureStore.isInitialized()) {
                LogCollector.getInstance().info("MyApplication", "安全数据存储初始化完成");
            }
        } catch (Throwable e) {
            Log.w("MyApplication", "安全数据存储初始化失败: " + e.getMessage());
        }
    }

    /**
     * 初始化 Bugly（后台线程）
     */
    private void initBugly() {
        try {
            String buglyAppId = BuildConfig.BUGLY_APP_ID;
            if (buglyAppId != null && !buglyAppId.contains("YOUR_")) {
                BuglyLogSender buglySender = BuglyLogSender.getInstance(this);
                buglySender.init(buglyAppId);
                buglySender.setEnabled(true);
                ExceptionReporter.init(this);
                ExceptionReporter.setEnabled(!BuildConfig.IS_DEBUG);
                LogCollector.getInstance().info("MyApplication", "Bugly + 全局异常上报初始化成功");
            }
        } catch (Throwable e) {
            Log.w("MyApplication", "Bugly init failed: " + e.getMessage());
        }
    }

    /**
     * 初始化日志服务
     */
    private void initLogServices() {
        try {
            CrashHandler.getInstance().init(this);
            LogCollector.getInstance().info("MyApplication", "崩溃处理器初始化完成");
        } catch (Throwable e) {
            Log.w("MyApplication", "CrashHandler init failed: " + e.getMessage());
        }

        try {
            NetUtil.init(this);
            LogCollector.getInstance().info("MyApplication", "网络工具初始化完成");
        } catch (Throwable e) {
            Log.w("MyApplication", "NetUtil init failed: " + e.getMessage());
        }

        try {
            LogServer.getInstance(this).start();
            String ip = LogServer.getInstance(this).getDeviceIpAddress();
            int port = LogServer.getInstance(this).getPort();
            LogCollector.getInstance().info("MyApplication", "LogServer 启动成功: " + ip + ":" + port);
        } catch (Throwable e) {
            Log.w("MyApplication", "LogServer init failed (ignore): " + e.getMessage());
        }

        try {
            CloudLogSender cloudSender = CloudLogSender.getInstance(this);
            cloudSender.setServerUrl("http://192.168.1.17:8080");
            cloudSender.setEnabled(true);
            cloudSender.start();
            LogCollector.getInstance().info("MyApplication", "云端日志发送已启动");
        } catch (Throwable e) {
            Log.w("MyApplication", "CloudLogSender init failed (ignore): " + e.getMessage());
        }
    }

    /**
     * 初始化缓存清理
     */
    private void initCacheCleanup() {
        try {
            AppCacheInspector.startupCleanup(this);
        } catch (Exception e) {
            Log.w("MyApplication", "AppCacheInspector failed: " + e.getMessage());
        }

        try {
            HuyaCacheGovernor.startupCleanup(this);
        } catch (Exception e) {
            Log.w("MyApplication", "HuyaCacheGovernor failed: " + e.getMessage());
        }
    }

    /**
     * 初始化虎牙 SDK
     */
    private void initHuyaSDK() {
        try {
            HuyaSDKParser.init(this);
            LogCollector.getInstance().info("MyApplication", "虎牙 SDK 初始化完成");
        } catch (Exception e) {
            Log.e("MyApplication", "虎牙 SDK 初始化异常: " + e.getMessage());
        }
    }

    /**
     * 初始化设备能力检测
     */
    private void initDeviceCapabilities() {
        try {
            DeviceCapabilities.ensureDetected(this);
            LogCollector.getInstance().info("MyApplication", "设备能力检测完成");
        } catch (Exception e) {
            Log.w("MyApplication", "DeviceCapabilities failed: " + e.getMessage());
        }
    }
}