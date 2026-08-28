package com.tv.live;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.util.DisplayMetrics;
import com.tv.live.util.LogBridge;
import android.view.WindowManager;

import com.tv.live.util.LogCollector;
import com.tv.live.util.LogServer;

import io.reactivex.exceptions.UndeliverableException;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全局崩溃捕获器（已修复主线程阻塞导致的ANR问题）
 *
 * 【功能清单】
 * 1. ✅ 捕获应用未处理的异常
 * 2. ✅ 保存崩溃日志到本地文件（持久化，重启后还能看到）
 * 3. ✅ 记录详细设备信息（手机型号、系统版本、APP版本等）
 * 4. ✅ 崩溃页面保留 1 分钟（方便查看崩溃原因）
 * 5. ✅ 最多保留 10 个崩溃日志，自动清理旧的
 * 6. ✅ 提供读取崩溃日志的方法，供设置页面查看
 *
 * 【使用方法】
 * 在 Application 的 onCreate 中调用：
 * CrashHandler.getInstance().init(this);
 *
 * 【自动重启说明】
 * 默认关闭自动重启，崩溃页面显示 1 分钟后直接退出。
 * 如果需要自动重启，可以调用：
 * CrashHandler.getInstance().setAutoRestartEnabled(true);
 */
@SuppressLint("StaticFieldLeak")
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";

    // 单例
    private static CrashHandler instance;

    // 上下文（ApplicationContext，生命周期与进程一致，安全）
    private Context context;

    // 系统默认的异常处理器（兜底用）
    private Thread.UncaughtExceptionHandler defaultHandler;

    // ====================================================================
    // 崩溃日志相关配置
    // ====================================================================

    /** 崩溃日志保存的目录名 */
    private static final String CRASH_DIR_NAME = "crash_logs";

    /** 最多保留的崩溃日志数量 */
    private static final int MAX_CRASH_LOG_COUNT = 10;

    /** 崩溃日志文件名前缀 */
    private static final String CRASH_FILE_PREFIX = "crash_";

    /** 崩溃日志文件名后缀 */
    private static final String CRASH_FILE_SUFFIX = ".txt";

    // ====================================================================
    // 崩溃日志（静态变量，供 CrashActivity 读取）
    // ====================================================================

    /**
     * 崩溃日志（静态变量）
     *
     * 【说明】
     * CrashActivity 直接读取这个静态变量来显示崩溃信息。
     * 同时也会保存到本地文件，持久化存储。
     */
    public static volatile String CRASH_LOG = "";

    // ====================================================================
    // 崩溃页面显示时长配置
    // ====================================================================

    /**
     * 崩溃页面显示时长（毫秒）
     *
     * 【说明】
     * 崩溃后，崩溃页面会显示这么长时间，方便用户查看崩溃原因。
     * 时间到了之后自动关闭应用。
     *
     * 默认 1 分钟（60000ms）
     */
    private static final long CRASH_PAGE_DISPLAY_DURATION = 60 * 1000; // 1分钟

    // ====================================================================
    // 自动重启相关配置
    // ====================================================================

    /**
     * 是否启用自动重启（默认关闭）
     *
     * 【说明】
     * 默认关闭，崩溃页面显示 1 分钟后直接退出应用。
     * 如果需要自动重启，可以调用 setAutoRestartEnabled(true) 开启。
     */
    private boolean autoRestartEnabled = false;

    /** 重启延迟时间（毫秒），默认 1 秒 */
    private static final long RESTART_DELAY = 1000;

    // 私有构造函数（单例模式）
    private CrashHandler() {}

    /**
     * 获取单例实例
     */
    public static CrashHandler getInstance() {
        if (instance == null) {
            instance = new CrashHandler();
        }
        return instance;
    }

    // ====================================================================
    // 初始化
    // ====================================================================

    /**
     * 初始化崩溃捕获器
     *
     * @param ctx 上下文
     */
    public void init(Context ctx) {
        context = ctx.getApplicationContext();

        // 保存系统默认的异常处理器（兜底用）
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        // 设置为默认异常处理器
        Thread.setDefaultUncaughtExceptionHandler(this);

        LogBridge.d(TAG, "全局崩溃捕获器已初始化");
        LogBridge.d(TAG, "崩溃日志保存目录：" + getCrashDir().getAbsolutePath());
        LogBridge.d(TAG, "崩溃页面显示时长：" + CRASH_PAGE_DISPLAY_DURATION / 1000 + " 秒");
        LogBridge.d(TAG, "自动重启：" + (autoRestartEnabled ? "已开启" : "已关闭"));
    }

    // ====================================================================
    // 设置是否自动重启
    // ====================================================================

    /**
     * 设置是否启用自动重启
     *
     * @param enabled true=开启自动重启，false=关闭
     */
    public void setAutoRestartEnabled(boolean enabled) {
        this.autoRestartEnabled = enabled;
        LogBridge.d(TAG, "自动重启已" + (enabled ? "开启" : "关闭"));
    }

    // ====================================================================
    // 核心：崩溃处理（已修复主线程阻塞）
    // ====================================================================

    /**
     * 未捕获异常回调（系统自动调用）
     *
     * 【执行流程 - 对齐旧版实现】
     * 1. 收集崩溃信息 → 保存到静态变量/文件
     * 2. 推送到日志系统 + 远程监控
     * 3. 启动崩溃页面
     * 4. 子线程中延迟1分钟后重启或杀进程
     *
     * 【崩溃上报说明】
     * Bugly（普通版 + 专业版）已全部移除（实验 2026-08-28），不存在 native
     * signal handler / 文件持久化 / 自动上传机制。崩溃信息由 ExceptionReporter /
     * LogCollector 在本地记录，此处不转发给任何第三方上报 SDK，崩溃页面可正常显示。
     */
    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            // ================================================================
            // 第零步：黑名单免疫 —— 已知无法修复的 SO 兼容异常（Mars STN 在
            //         Android 5.1.1 等老设备上 UnsatisfiedLinkError），不能让
            //         它弹出崩溃页面把用户赶到 CrashActivity。
            //         业务层 HuyaSDKParser 已对 API <= 22 做了 SDK 短路，但
            //         虎牙 SDK init 之后会异步启动 mars 网络栈上报 SDK 自身
            //         内部指标（统计/心跳/链路），完全绕过 sInitOk 守门。
            //         这些异常通常发生在 SDK 内部工作线程，被 Thread 顶层
            //         UncaughtExceptionHandler 捕获后到达此处。
            //         处理策略：仅记录日志 + 上报到日志服务/异常上报，**不**
            //         启动崩溃页、不杀进程（保留应用主功能可用）。
            // ================================================================
            if (isIgnoredMarsLinkError(ex)) {
                LogBridge.e(TAG, "🛡️【mars 黑名单】忽略已知 SO 不兼容异常: " + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    ex.printStackTrace(pw);
                    pw.close();
                    LogCollector.getInstance().error("CrashHandler", "mars_ignored:" + sw.toString());
                    if (context != null) {
                        LogServer.getInstance(context).sendCrashLog(
                                "【mars 黑名单免疫】\n" + sw.toString());
                    }
                } catch (Throwable ignored) {
                }
                return;
            }
            // ================================================================
            // 第一步：收集完整的崩溃信息
            // ================================================================
            String crashLog = buildCrashLog(thread, ex);

            // ================================================================
            // 第二步：保存到静态变量（供 CrashActivity 读取显示）
            // ================================================================
            CRASH_LOG = crashLog;
            LogBridge.e(TAG, crashLog);

            // ================================================================
            // 第三步：保存到本地文件（持久化，重启后还能看到）
            // ================================================================
            saveCrashLogToFile(crashLog);

            // ================================================================
            // 第四步：同步到日志系统 + 推送到远程监控
            // ================================================================
            try {
                LogBridge.e(TAG, "【崩溃】" + ex.getClass().getName() + ": " + ex.getMessage());
                LogBridge.e(TAG, "【崩溃】详细日志已保存到文件");
                LogBridge.e(TAG, "【崩溃】崩溃页面将显示 " + (CRASH_PAGE_DISPLAY_DURATION / 1000) + " 秒");

                LogCollector.getInstance().crash("CrashHandler",
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());

                if (context != null) {
                    LogServer.getInstance(context).sendCrashLog(crashLog);
                }
            } catch (Exception ignored) {}

            // ================================================================
            // 第五步：启动崩溃页面（显示崩溃原因）
            // ================================================================
            startCrashActivity();

            // ================================================================
            // 第六步：子线程中延迟处理，不阻塞主线程
            // 崩溃信息已由本地日志记录（Bugly 已移除，无第三方上报）
            // ================================================================
            new Thread(() -> {
                try {
                    Thread.sleep(CRASH_PAGE_DISPLAY_DURATION);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                if (autoRestartEnabled) {
                    restartApp();
                }

                Process.killProcess(Process.myPid());
                System.exit(1);
            }, "crash-handler").start();

        } catch (Exception e) {
            LogBridge.e(TAG, "崩溃处理失败", e);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
        }
    }
    
    // ====================================================================
    // 构建完整的崩溃日志（包含设备信息）
    // ====================================================================

    /**
     * 构建完整的崩溃日志
     *
     * 【包含内容】
     * 1. 基本信息：时间、线程、异常类型、异常信息
     * 2. 设备信息：品牌、型号、系统版本、SDK版本
     * 3. APP信息：版本名、版本号、包名
     * 4. 屏幕信息：分辨率、密度
     * 5. 完整堆栈信息
     * 6. 提示信息
     *
     * @param thread 崩溃的线程
     * @param ex 异常对象
     * @return 完整的崩溃日志字符串
     */
    private String buildCrashLog(Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder();

        // ================================================================
        // 1. 基本信息
        // ================================================================
        sb.append("================ 崩溃日志 ================\n");
        sb.append("时间：").append(getCurrentTime()).append("\n");
        sb.append("线程：").append(thread.getName()).append(" (ID: ").append(thread.getId()).append(")\n");
        sb.append("异常类型：").append(ex.getClass().getName()).append("\n");
        sb.append("异常信息：").append(ex.getMessage()).append("\n");

        // ================================================================
        // 2. 设备信息
        // ================================================================
        sb.append("\n========== 设备信息 ==========\n");
        sb.append("品牌：").append(Build.BRAND).append("\n");
        sb.append("型号：").append(Build.MODEL).append("\n");
        sb.append("产品：").append(Build.PRODUCT).append("\n");
        sb.append("系统版本：Android ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("SDK版本：").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("构建版本：").append(Build.DISPLAY).append("\n");
        sb.append("CPU架构：").append(Build.SUPPORTED_ABIS[0]).append("\n");

        // ================================================================
        // 3. APP信息
        // ================================================================
        sb.append("\n========== APP信息 ==========\n");
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            sb.append("包名：").append(pi.packageName).append("\n");
            sb.append("版本名：").append(pi.versionName).append("\n");
            sb.append("版本号：").append(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)).append("\n");
        } catch (PackageManager.NameNotFoundException e) {
            sb.append("包名：").append(context.getPackageName()).append("\n");
            sb.append("版本信息：获取失败\n");
        }

        // ================================================================
        // 4. 屏幕信息
        // ================================================================
        sb.append("\n========== 屏幕信息 ==========\n");
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(metrics);
            sb.append("分辨率：").append(metrics.widthPixels).append(" x ").append(metrics.heightPixels).append("\n");
            sb.append("密度：").append(metrics.densityDpi).append("dpi\n");
            sb.append("缩放比例：").append(metrics.density).append("\n");
        } catch (Exception e) {
            sb.append("屏幕信息：获取失败\n");
        }

        // ================================================================
        // 5. 完整堆栈信息
        // ================================================================
        sb.append("\n========== 堆栈信息 ==========\n");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        pw.close();
        sb.append(sw.toString());

        // ================================================================
        // 6. 提示信息
        // ================================================================
        sb.append("\n========== 提示 ==========\n");
        if (autoRestartEnabled) {
            sb.append("页面将在 ").append(CRASH_PAGE_DISPLAY_DURATION / 1000).append(" 秒后自动重启应用\n");
        } else {
            sb.append("页面将在 ").append(CRASH_PAGE_DISPLAY_DURATION / 1000).append(" 秒后自动关闭\n");
        }
        sb.append("详细日志已保存到本地文件，可在设置页面查看\n");

        sb.append("\n========================================\n");

        return sb.toString();
    }

    // ====================================================================
    // 保存崩溃日志到文件
    // ====================================================================

    /**
     * 保存崩溃日志到本地文件
     *
     * 【保存位置】
     * /data/data/com.tv.live/files/crash_logs/crash_20260621_205026.txt
     *
     * 【命名规则】
     * crash_yyyyMMdd_HHmmss.txt
     *
     * 【自动清理】
     * 最多保留 10 个崩溃日志，超过就删除最旧的
     *
     * @param crashLog 崩溃日志内容
     */
    private void saveCrashLogToFile(String crashLog) {
        try {
            // 1. 获取崩溃日志目录
            File crashDir = getCrashDir();
            if (!crashDir.exists()) {
                crashDir.mkdirs();
            }

            // 2. 生成文件名（用时间戳命名）
            String fileName = CRASH_FILE_PREFIX
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())
                    + CRASH_FILE_SUFFIX;

            File crashFile = new File(crashDir, fileName);

            // 3. 写入文件
            FileWriter writer = new FileWriter(crashFile);
            writer.write(crashLog);
            writer.flush();
            writer.close();

            LogBridge.d(TAG, "崩溃日志已保存：" + crashFile.getAbsolutePath());

            // 4. 自动清理旧的日志文件
            cleanOldCrashLogs();

        } catch (Exception e) {
            LogBridge.e(TAG, "保存崩溃日志到文件失败", e);
        }
    }

    // ====================================================================
    // 自动清理旧的崩溃日志
    // ====================================================================

    /**
     * 清理旧的崩溃日志，只保留最新的 MAX_CRASH_LOG_COUNT 个
     */
    private void cleanOldCrashLogs() {
        try {
            File crashDir = getCrashDir();
            File[] files = crashDir.listFiles();

            if (files == null || files.length <= MAX_CRASH_LOG_COUNT) {
                return; // 数量没超过，不用清理
            }

            // 按修改时间排序（最新的在前）
            List<File> fileList = new ArrayList<>(Arrays.asList(files));
            Collections.sort(fileList, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f2.lastModified(), f1.lastModified());
                }
            });

            // 删除超过数量限制的旧文件
            for (int i = MAX_CRASH_LOG_COUNT; i < fileList.size(); i++) {
                File oldFile = fileList.get(i);
                if (oldFile.delete()) {
                    LogBridge.d(TAG, "已删除旧的崩溃日志：" + oldFile.getName());
                }
            }

        } catch (Exception e) {
            LogBridge.e(TAG, "清理旧崩溃日志失败", e);
        }
    }

    // ====================================================================
    // 自动重启应用
    // ====================================================================

    /**
     * 崩溃后自动重启应用
     *
     * 【实现方式】
     * 使用 AlarmManager 设置一个延迟闹钟，
     * 1 秒后启动 MainActivity，这样即使进程被杀了也能重启。
     *
     * 【注意】
     * 默认关闭，需要手动调用 setAutoRestartEnabled(true) 开启。
     */
    private void restartApp() {
        try {
            // 创建启动 MainActivity 的 Intent
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            // 创建 PendingIntent
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            );

            // 获取 AlarmManager
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

            // 设置延迟闹钟
            if (alarmManager != null) {
                alarmManager.set(
                        AlarmManager.RTC,
                        System.currentTimeMillis() + RESTART_DELAY,
                        pendingIntent
                );
                LogBridge.d(TAG, "已设置自动重启，" + RESTART_DELAY + "ms 后启动");
            }

        } catch (Exception e) {
            LogBridge.e(TAG, "设置自动重启失败", e);
        }
    }

    // ====================================================================
    // 启动崩溃页面
    // ====================================================================

    /**
     * 启动崩溃页面
     *
     * 【说明】
     * 崩溃后启动 CrashActivity，显示崩溃原因，
     * 页面会显示 1 分钟，然后自动关闭。
     */
    private void startCrashActivity() {
        try {
            Intent intent = new Intent(context, CrashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            LogBridge.d(TAG, "已启动崩溃页面");
        } catch (Exception e) {
            LogBridge.e(TAG, "启动崩溃页面失败", e);
        }
    }

    // ====================================================================
    // 工具方法：获取崩溃日志目录
    // ====================================================================

    /**
     * 获取崩溃日志保存目录
     *
     * @return 崩溃日志目录 File 对象
     */
    private File getCrashDir() {
        return new File(context.getFilesDir(), CRASH_DIR_NAME);
    }

    // ====================================================================
    // 工具方法：获取当前时间字符串
    // ====================================================================

    /**
     * 获取当前时间的格式化字符串
     *
     * @return 格式化后的时间字符串
     */
    private String getCurrentTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    // ====================================================================
    // 公开方法：获取崩溃日志列表（供 SettingsActivity 使用）
    // ====================================================================

    /**
     * 获取所有崩溃日志文件列表（按时间倒序，最新的在前）
     *
     * 【用途】
     * 供 SettingsActivity 显示崩溃日志列表，让用户选择查看哪个。
     *
     * @return 崩溃日志文件列表
     */
    public List<File> getCrashLogList() {
        try {
            File crashDir = getCrashDir();
            File[] files = crashDir.listFiles();

            if (files == null || files.length == 0) {
                return new ArrayList<>();
            }

            // 按修改时间排序（最新的在前）
            List<File> fileList = new ArrayList<>(Arrays.asList(files));
            Collections.sort(fileList, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f2.lastModified(), f1.lastModified());
                }
            });

            return fileList;

        } catch (Exception e) {
            LogBridge.e(TAG, "获取崩溃日志列表失败", e);
            return new ArrayList<>();
        }
    }

    // ====================================================================
    // 公开方法：获取最新的崩溃日志（供 SettingsActivity 使用）
    // ====================================================================

    /**
     * 获取最新的崩溃日志内容
     *
     * 【用途】
     * 供 SettingsActivity 直接显示最新的崩溃日志。
     *
     * @return 最新的崩溃日志内容，如果没有返回 null
     */
    public String getLatestCrashLog() {
        List<File> list = getCrashLogList();
        if (list.isEmpty()) {
            return null;
        }

        try {
            File latestFile = list.get(0);
            return readFileToString(latestFile);
        } catch (Exception e) {
            LogBridge.e(TAG, "读取最新崩溃日志失败", e);
            return null;
        }
    }

    // ====================================================================
    // 公开方法：清空所有崩溃日志（供 SettingsActivity 使用）
    // ====================================================================

    /**
     * 清空所有崩溃日志文件
     *
     * 【用途】
     * 供 SettingsActivity 的"清空日志"按钮使用。
     *
     * @return 成功删除的文件数量
     */
    public int clearAllCrashLogs() {
        try {
            File crashDir = getCrashDir();
            File[] files = crashDir.listFiles();

            if (files == null || files.length == 0) {
                return 0;
            }

            int count = 0;
            for (File file : files) {
                if (file.delete()) {
                    count++;
                }
            }

            LogBridge.d(TAG, "已清空 " + count + " 个崩溃日志");
            return count;

        } catch (Exception e) {
            LogBridge.e(TAG, "清空崩溃日志失败", e);
            return 0;
        }
    }

    // ====================================================================
    // 工具方法：读取文件到字符串
    // ====================================================================

    /**
     * 判定一个异常是否属于"已知 SO 不兼容（mars 在老设备上）"黑名单。
     * 黑名单内的异常将被静默忽略，不弹崩溃页、不杀进程。
     *
     * 判定规则（任一命中即视为黑名单）：
     * 1. 异常本身是 UnsatisfiedLinkError，且其完整堆栈包含以下任一关键字：
     *    "mars"、"StnLogic"、"libmarsstn"、"MtpMarsTransporter"
     * 2. 异常是 UndeliverableException（RxJava 已 dispose），且 cause（任意深度）
     *    是 UnsatisfiedLinkError 且 stack 含上述关键字。
     * 3. 异常是 NoSuchMethodError 且 stack 含 "StnLogic"（少见：JNI 找不到 Java
     *    端注册方法，与 5.1.1 mars SO 不兼容表现相同）。
     *
     * @param ex 待检测异常
     * @return true 表示应忽略；false 表示按普通崩溃处理
     */
    private boolean isIgnoredMarsLinkError(Throwable ex) {
        if (ex == null) return false;
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth < 10) {
            if (cur instanceof UnsatisfiedLinkError || cur instanceof NoSuchMethodError) {
                String msg = (cur.getMessage() == null ? "" : cur.getMessage())
                        + " " + stackToString(cur);
                if (msg.contains("mars") || msg.contains("StnLogic")
                        || msg.contains("libmarsstn") || msg.contains("MtpMarsTransporter")) {
                    return true;
                }
            }
            // UndeliverableException 类本身是 RxJava 包装：拆 cause
            if (cur instanceof UndeliverableException) {
                cur = cur.getCause();
                depth++;
                continue;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 将异常堆栈拼为字符串（用于关键字检索，避免 printStackTrace 副作用）。
     */
    private String stackToString(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            pw.close();
            return sw.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 读取文件内容为字符串
     *
     * @param file 要读取的文件
     * @return 文件内容字符串
     */
    private String readFileToString(File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);
            fis.close();
            return new String(buffer);
        } catch (Exception e) {
            LogBridge.e(TAG, "读取文件失败：" + file.getAbsolutePath(), e);
            return null;
        }
    }
}
