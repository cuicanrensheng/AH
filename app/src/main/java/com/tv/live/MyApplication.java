package com.tv.live;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import com.tv.live.util.LogBridge;

import com.tv.live.BuildConfig;
import com.tv.live.util.AppCacheInspector;

import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.exceptions.UndeliverableException;
import java.net.UnknownHostException;
import java.net.SocketException;
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
import com.tv.live.util.ExceptionReporter;
import com.tv.live.security.SecurityCore;
import com.tv.live.security.AntiDebug;
import com.tv.live.security.StringObfuscator;
import com.tv.live.security.DexProtector;
import com.tv.live.security.StringProtector;
import com.tv.live.security.SecurityGuard;
public class MyApplication extends Application {

    private static MyApplication sInstance;

    public static MyApplication getInstance() {
        return sInstance;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ===== SDK 兼容修复：动态注册 receiver 补 RECEIVER_NOT_EXPORTED =====
    // Android 14+ (targetSdk 34+) 强制要求动态注册 receiver 必须声明
    // RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED。虎牙 SDK 的
    // HuyaBerryImpl.init() 裸调 registerReceiver 未指定该标志，会抛
    // SecurityException。这里统一补上 RECEIVER_NOT_EXPORTED（SDK 内部
    // receiver 均为本应用私有广播，不需要导出）。
    // 用反射调用 setReceiverFlags（API 33+），避免编译期 SDK 版本依赖。
    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        markReceiverNotExported(filter);
        return super.registerReceiver(receiver, filter);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        markReceiverNotExported(filter);
        return super.registerReceiver(receiver, filter, flags);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                   String broadcastPermission, Handler scheduler) {
        markReceiverNotExported(filter);
        return super.registerReceiver(receiver, filter, broadcastPermission, scheduler);
    }

    /** API 33+：给动态 receiver 补 RECEIVER_NOT_EXPORTED（值=4），规避 SecurityException。 */
    private static void markReceiverNotExported(IntentFilter filter) {
        if (Build.VERSION.SDK_INT < 33 || filter == null) {
            return;
        }
        try {
            java.lang.reflect.Method m = IntentFilter.class.getMethod("setReceiverFlags", int.class);
            m.invoke(filter, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignore) {
            // API 33 以下或反射失败时忽略
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        // ===== 开机自启兜底：动态注册广播监听（必须在主线程尽早执行） =====
        // Android 7.0+ 静态注册收不到 SCREEN_ON / USER_PRESENT，
        // 创维/酷开"快速开机"唤醒只发这些广播，必须动态注册才能感知并启动应用。
        // 只要应用进程存活（电视内存充裕，应用常驻），动态监听即长期有效。
        try {
            BootReceiver.registerDynamic(this);
        } catch (Throwable t) {
            LogBridge.w("MyApplication", "动态注册开机兜底监听失败: " + t.getMessage());
        }

        // ===== 常驻保活：粘性前台服务（START_STICKY） =====
        // 酷开等电视系统待机/内存回收时会杀掉第三方后台进程，
        // 导致待机唤醒后应用无法回到屏幕。开启自启开关时，
        // 常驻一个粘性前台服务：进程被系统回收后会自动重建，
        // 并在 onStartCommand 中重新拉起 MainActivity。
        try {
            boolean autoStart = getSharedPreferences("app_settings", MODE_PRIVATE)
                    .getBoolean("boot_auto_start", false);
            if (autoStart) {
                Intent svc = new Intent(this, BootStartForegroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                LogCollector.getInstance().info("MyApplication", "已启动常驻保活前台服务(sticky)");
            }
        } catch (Throwable t) {
            LogBridge.w("MyApplication", "启动保活服务失败: " + t.getMessage());
        }

        // ===== 关键路径：只做最小化初始化，确保启动速度 =====
        // 1. 初始化日志收集器（内存操作，很快）
        LogCollector.getInstance();

        // 2. 注册 RxJava 全局错误处理器，避免 UndeliverableException 导致应用崩溃。
        //    当 Observable 已 dispose 后，底层 so 加载失败等异常无法投递给订阅者，
        //    RxJava 会将其转交给 RxJavaPlugins.onError；默认未处理时会抛到主线程崩溃。
        //    这里吞掉 UndeliverableException，仅记录日志/上报，保证进程不崩。
        RxJavaPlugins.setErrorHandler(error -> {
            Throwable actual = error;
            if (error instanceof UndeliverableException && error.getCause() != null) {
                actual = error.getCause();
            }
            // 过滤已知的、无需上报的网络/IO 噪音
            if (actual instanceof UnknownHostException
                    || actual instanceof SocketException
                    || actual instanceof InterruptedException) {
                LogBridge.w("MyApplication", "RxJava 可忽略错误: " + actual.getMessage());
                return;
            }
            // UnsatisfiedLinkError 等 so 不兼容问题已在 HuyaSDKParser 预检中处理；
            // 若仍有漏网之鱼，记录并上报，但不崩溃。
            LogBridge.e("MyApplication", "RxJava 未投递异常: " + actual.getMessage(), actual);
            try {
                ExceptionReporter.report("RxJavaPlugins.onError", actual);
            } catch (Throwable ignored) {
            }
        });

        // 3. 在后台线程执行所有耗时初始化
        initializeAsync();

        LogCollector.getInstance().info("MyApplication", "应用启动完成（快速模式）");
    }

    /**
     * 后台异步初始化所有组件
     * 使用多个后台线程并行执行，减少总耗时
     */
    private void initializeAsync() {
        // 线程0：TVPlayerManager 预热（ExoPlayer 构建 + 解码器枚举）
        // 实测：MainActivity.onCreate 主线程同步创建 TVPlayerManager 时，
        // new ExoPlayer.Builder().build() + MediaCodecUtil.getDecoderInfos
        // 在弱 TV 上耗时 1-3 秒，是首帧延迟的主要来源之一。
        // 这里在后台线程提前构建单例（ExoPlayer 内部按主线程 Looper 调度，
        // 后台创建是官方支持的用法），MainActivity 直接拿到已建好的实例。
        // 若预热尚未完成，主线程仅在单例锁上等待，不会比原来更慢。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long t0 = android.os.SystemClock.elapsedRealtime();
                    TVPlayerManager.getInstance(MyApplication.this);
                    long dt = android.os.SystemClock.elapsedRealtime() - t0;
                    LogCollector.getInstance().info("MyApplication",
                            "TVPlayerManager 预热完成（后台线程）: " + dt + "ms");
                } catch (Throwable t) {
                    LogBridge.w("MyApplication", "TVPlayerManager 预热失败: " + t.getMessage());
                }
            }
        }, "init-player-preheat").start();

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

        // 延迟 2 秒后初始化其他组件（全部放到后台线程执行，避免占用主线程/阻塞首帧渲染）
        // 实测：虎牙 SDK 内部 Mars STN 原生库加载 + 主线程任务会阻塞主线程约 4 秒，
        // 与 ExoPlayer prepare→首帧渲染窗口重叠时，表现为启动黑屏 + 系统背光渐变（画面闪一下）。
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        initLogServices();
                        initCacheCleanup();
                    }
                }, "init-deferred").start();

                // 虎牙 SDK 初始化单独延迟到首帧显示之后（后台线程执行），
                // 避免 SDK 初始化期间主线程被占用导致首帧迟迟无法渲染。
                // 从 10s 降至 2s：SDK init 已在 HandlerThread 执行不阻塞主线程，
                // 延迟过长导致用户首次点击频道时 SDK 尚未就绪，出现"虎牙 SDK 不可用"竞态。
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                initHuyaSDK();
                            }
                        }, "init-huya-sdk").start();
                    }
                }, 2000);

                // 🛡️ 安全检测 + 安全数据存储：延后到首帧渲染后（3 秒）在后台执行。
                // performFullSecurityCheck 含 APK 签名哈希/完整性校验等耗时操作，
                // 与 TVPlayerManager 预热线程竞争 CPU 会拖慢首帧；其结果仅记录日志，
                // 不参与任何首屏路径，延后执行零副作用。
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                initSecurityGuardManager();
                                initSecureDataStore();
                            }
                        }, "security-deferred").start();
                    }
                }, 3000);
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
                // 🟢 使用WARN级别而非ERROR，避免误报到Bugly
                // 真正的篡改检测（Frida/Xposed）已在AntiDebug内部上报
                // 此处仅记录警告，不触发Bugly上报
                LogCollector.getInstance().warn("MyApplication", 
                    "⚠️ 检测到可疑环境（已记录，详见反调试日志）");
            } else {
                String mode = BuildConfig.IS_DEBUG ? "调试版：跳过反调试检测" : "反调试检测通过";
                LogCollector.getInstance().info("MyApplication", mode);
            }
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "反调试检测失败: " + e.getMessage());
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
            LogBridge.w("MyApplication", "SecurityCore init failed: " + e.getMessage());
        }

        // ✅ URL 解密必须在安全检查之前完成，确保网络功能始终可用
        try {
            UrlConfig.fillPublicFields();
            LogCollector.getInstance().info("MyApplication", "URL 配置解密完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "URL 配置解密失败: " + e.getMessage());
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
            LogBridge.e("MyApplication", "SecurityCheck failed: " + e.getMessage());
        }

        // 🔒 DEX 保护（正式版启用，即使安全检查未通过也尝试初始化）
        try {
            DexProtector.init(this);
            LogCollector.getInstance().info("MyApplication", "DEX 保护初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "DEX 保护初始化失败: " + e.getMessage());
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
                BuildConfig.IS_DEBUG ? "2135" : StringProtector.quickEncrypt("2135"));
            LogCollector.getInstance().info("MyApplication", "字符串保护初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "字符串保护初始化失败: " + e.getMessage());
        }

        // 🛡️ 安全守卫（应用自保护）
        try {
            SecurityGuard.init(this);
            LogCollector.getInstance().info("MyApplication", "安全守卫初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "安全守卫初始化失败: " + e.getMessage());
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
            LogBridge.w("MyApplication", "加密存储初始化失败: " + e.getMessage());
        }

        try {
            HuyaCredentials credentials = HuyaCredentials.getInstance(this);
            if (credentials.isInitialized()) {
                LogCollector.getInstance().info("MyApplication", "虎牙 SDK 凭证加载成功: " + credentials.getCredentialsSummary());
            } else {
                LogCollector.getInstance().warn("MyApplication", "虎牙 SDK 凭证未完全初始化");
            }
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "虎牙 SDK 凭证初始化失败: " + e.getMessage());
        }

        // 🔒 初始化 SSL 证书管理器（TOFU 证书锁定）
        try {
            SecurityCertificatePinner sslPinner = SecurityCertificatePinner.getInstance();
            sslPinner.init(this);
            LogCollector.getInstance().info("MyApplication", "SSL 证书管理器初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "SSL 证书管理器初始化失败: " + e.getMessage());
        }

        // 🌐 初始化网络工具（配置安全 HTTP 客户端）
        try {
            NetUtil.init(this);
            LogCollector.getInstance().info("MyApplication", "网络工具初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "网络工具初始化失败: " + e.getMessage());
        }

    }

    /**
     * 🛡️ 初始化安全检测管理器并执行首次安全检测（后台线程，延后到首帧渲染后执行，
     * 避免 performFullSecurityCheck 的 APK 签名哈希/完整性校验与播放器预热竞争 CPU）
     */
    private void initSecurityGuardManager() {
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
            LogBridge.w("MyApplication", "安全检测初始化失败: " + e.getMessage());
        }
    }

    /**
     * 🔐 初始化安全数据存储（后台线程，延后到首帧渲染后执行）
     */
    private void initSecureDataStore() {
        try {
            SecureDataStore secureStore = SecureDataStore.getInstance();
            secureStore.init(this);
            if (secureStore.isInitialized()) {
                LogCollector.getInstance().info("MyApplication", "安全数据存储初始化完成");
            }
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "安全数据存储初始化失败: " + e.getMessage());
        }
    }

    /**
     * 初始化全局异常上报（Bugly 普通版引用已移除，实验：仅保留本地 ExceptionReporter）
     */
    private void initBugly() {
        try {
            ExceptionReporter.init(this);
            ExceptionReporter.setEnabled(!BuildConfig.IS_DEBUG);
            LogCollector.getInstance().info("MyApplication", "全局异常上报初始化完成（Bugly 已移除）");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "ExceptionReporter init failed: " + e.getMessage());
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
            LogBridge.w("MyApplication", "CrashHandler init failed: " + e.getMessage());
        }

        try {
            LogServer.getInstance(this).start();
            String ip = LogServer.getInstance(this).getDeviceIpAddress();
            int port = LogServer.getInstance(this).getPort();
            LogCollector.getInstance().info("MyApplication", "LogServer 启动成功: " + ip + ":" + port);
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "LogServer init failed (ignore): " + e.getMessage());
        }

        // 启动时截断自启日志，防止文件无限膨胀（保留最近 500 行）
        try {
            BootReceiver.trimBootLog(this);
        } catch (Throwable ignored) {
        }

    }

    /**
     * 初始化缓存清理
     */
    private void initCacheCleanup() {
        try {
            AppCacheInspector.startupCleanup(this);
        } catch (Exception e) {
            LogBridge.w("MyApplication", "AppCacheInspector failed: " + e.getMessage());
        }

        try {
            HuyaCacheGovernor.startupCleanup(this);
        } catch (Exception e) {
            LogBridge.w("MyApplication", "HuyaCacheGovernor failed: " + e.getMessage());
        }
    }

    /**
     * 初始化虎牙 SDK
     *
     * 兼容策略：
     * 1. Android 5.1.1（API 22）及以下设备，libmarsstn.so 与系统不兼容，
     *    SDK init 本身可能不报错，但内部异步启动 mars 网络栈时会在
     *    SDK 内部工作线程抛出 UnsatisfiedLinkError，被 CrashHandler
     *    拦截走黑名单免疫。提前短路 HuyaBerry.init 进一步从源头消除
     *    mars 异步调用风险。
     * 2. 预检失败时，HuyaSDKParser 已把 sInitOk 置 false，业务层
     *    走纯 HTTP 兜底解析。
     */
    private void initHuyaSDK() {
        try {
            if (Build.VERSION.SDK_INT <= 22) {
                // v7a 兼容验证（2026-08-26）：不再硬跳过。useLegacyPackaging 已修复
                // so 解压问题，交由 HuyaSDKParser 的 Mars 预检 + 降级兜底：
                // 若 libmarsstn.so 能加载则正常启用 SDK，否则 sInitOk=false 走纯 HTTP 解析。
                LogBridge.w("MyApplication", "⚠️ API <= 22（Android 5.x）尝试初始化虎牙 SDK"
                        + "（useLegacyPackaging + v7a 兼容验证）");
            }
            // 关键修复（2026-08-26，反编译 SDK 内部确认）：
            // 虎牙 SDK 调用链 HuyaBerryImpl.init → Mars.init → NetworkSignalUtil
            // 会创建 PhoneStateListener，其内部 new Handler() 绑定【当前线程】Looper。
            // 普通 Thread 无 Looper → 抛 NullPointerException → SDK 初始化失败(sInitOk=false)。
            // SDK 内部 Mars.init 的 handler 参数固定传主线程 Handler（仅 native 回调用），
            // 并不要求初始化必须在主线程。因此用 HandlerThread（自带 Looper）执行初始化：
            // 既不卡主线程（native 库加载/长连接在子线程），SDK 也能正常初始化。
            HandlerThread sdkThread = new HandlerThread("huya-sdk-init");
            sdkThread.start();
            new Handler(sdkThread.getLooper()).post(() -> {
                try {
                    HuyaSDKParser.init(MyApplication.this);
                    LogCollector.getInstance().info("MyApplication", "虎牙 SDK 初始化完成");
                } catch (Exception e) {
                    LogBridge.e("MyApplication", "虎牙 SDK 初始化异常: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LogBridge.e("MyApplication", "虎牙 SDK 初始化异常: " + e.getMessage());
        }
    }

}

