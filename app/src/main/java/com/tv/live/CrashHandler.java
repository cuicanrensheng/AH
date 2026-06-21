package com.tv.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

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
 * 全局崩溃捕获器（优化版）
 *
 * 【功能清单】
 * 1. ✅ 捕获应用未处理的异常
 * 2. ✅ 保存崩溃日志到本地文件（持久化，重启后还能看到）
 * 3. ✅ 记录详细设备信息（手机型号、系统版本、APP版本等）
 * 4. ✅ 崩溃后自动重启到主页（用户体验更好）
 * 5. ✅ 最多保留 10 个崩溃日志，自动清理旧的
 * 6. ✅ 提供读取崩溃日志的方法，供设置页面查看
 *
 * 【使用方法】
 * 在 Application 的 onCreate 中调用：
 * CrashHandler.getInstance().init(this);
 *
 * 【优化说明】
 * 原来的版本：
 * - 只保存到静态变量，进程被杀后就没了
 * - 只有异常信息，没有设备信息
 * - 崩溃后显示崩溃页面然后杀进程，体验不好
 *
 * 优化后的版本：
 * - 保存到本地文件，永久保存，随时可以查看
 * - 包含完整的设备信息，方便排查问题
 * - 崩溃后自动重启到主页，用户几乎感知不到
 * - 自动管理日志文件数量，不会占用太多空间
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";

    // 单例
    private static CrashHandler instance;

    // 上下文
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
    // 崩溃日志（静态变量，兼容旧版 CrashActivity）
    // ====================================================================

    /**
     * 崩溃日志（静态变量）
     *
     * 【说明】
     * 保留这个变量是为了兼容旧版的 CrashActivity。
     * 优化后主要用文件保存，但是静态变量也会同步更新，
     * 这样 CrashActivity 不用改也能正常显示。
     */
    public static volatile String CRASH_LOG = "";

    // ====================================================================
    // 自动重启相关配置
    // ====================================================================

    /** 是否启用自动重启（默认开启） */
    private boolean autoRestartEnabled = true;

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

        Log.d(TAG, "全局崩溃捕获器已初始化");
        Log.d(TAG, "崩溃日志保存目录：" + getCrashDir().getAbsolutePath());
        Log.d(TAG, "自动重启：" + (autoRestartEnabled ? "已开启" : "已关闭"));
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
    }

    // ====================================================================
    // 核心：崩溃处理
    // ====================================================================

    /**
     * 未捕获异常回调（系统自动调用）
     *
     * 【执行流程】
     * 1. 收集崩溃信息和设备信息
     * 2. 保存到静态变量（兼容旧版）
     * 3. 保存到本地文件（持久化）
     * 4. 自动重启应用（如果开启了）
     * 5. 杀死当前进程
     */
    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            // ================================================================
            // 第一步：收集完整的崩溃信息
            // ================================================================
            String crashLog = buildCrashLog(thread, ex);

            // ================================================================
            // 第二步：保存到静态变量（兼容旧版 CrashActivity）
            // ================================================================
            CRASH_LOG = crashLog;
            Log.e(TAG, crashLog);

            // ================================================================
            // 第三步：保存到本地文件（持久化，重启后还能看到）
            // ================================================================
            saveCrashLogToFile(crashLog);

            // ================================================================
            // 第四步：同步到播放日志（设置页面能看到）
            // ================================================================
            try {
                SettingsActivity.log("【崩溃】" + ex.getClass().getName() + ": " + ex.getMessage());
                SettingsActivity.log("【崩溃】详细日志已保存到文件");
            } catch (Exception ignored) {}

            // ================================================================
            // 第五步：自动重启应用（如果开启了）
            // ================================================================
            if (autoRestartEnabled) {
                restartApp();
            } else {
                // 如果不自动重启，就启动崩溃页面（兼容旧版）
                startCrashActivity();
            }

            // ================================================================
            // 第六步：等待一下，然后杀死当前进程
            // ================================================================
            try {
                Thread.sleep(autoRestartEnabled ? 200 : 500);
            } catch (InterruptedException ignored) {}

            Process.killProcess(Process.myPid());
            System.exit(1);

        } catch (Exception e) {
            Log.e(TAG, "崩溃处理失败", e);
            // 如果自定义处理失败，交给系统默认处理
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
            sb.append("版本号：").append(pi.versionCode).append("\n");
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

            Log.d(TAG, "崩溃日志已保存：" + crashFile.getAbsolutePath());

            // 4. 自动清理旧的日志文件
            cleanOldCrashLogs();

        } catch (Exception e) {
            Log.e(TAG, "保存崩溃日志到文件失败", e);
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
                    Log.d(TAG, "已删除旧的崩溃日志：" + oldFile.getName());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "清理旧崩溃日志失败", e);
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
     * 【为什么用 AlarmManager？】
     * 如果直接 startActivity，然后杀进程，可能会导致启动失败。
     * 用 AlarmManager 的话，系统会在指定时间自动启动应用，更可靠。
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
                Log.d(TAG, "已设置自动重启，" + RESTART_DELAY + "ms 后启动");
            }

        } catch (Exception e) {
            Log.e(TAG, "设置自动重启失败", e);
            // 如果自动重启设置失败，就 fallback 到崩溃页面
            startCrashActivity();
        }
    }

    // ====================================================================
    // 启动崩溃页面（兼容旧版）
    // ====================================================================

    /**
     * 启动崩溃页面（兼容旧版逻辑）
     *
     * 【说明】
     * 如果关闭了自动重启，就用原来的方式：启动崩溃页面。
     */
    private void startCrashActivity() {
        try {
            Intent intent = new Intent(context, CrashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "启动崩溃页面失败", e);
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
            Log.e(TAG, "获取崩溃日志列表失败", e);
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
            Log.e(TAG, "读取最新崩溃日志失败", e);
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

            Log.d(TAG, "已清空 " + count + " 个崩溃日志");
            return count;

        } catch (Exception e) {
            Log.e(TAG, "清空崩溃日志失败", e);
            return 0;
        }
    }

    // ====================================================================
    // 工具方法：读取文件到字符串
    // ====================================================================

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
            Log.e(TAG, "读取文件失败：" + file.getAbsolutePath(), e);
            return null;
        }
    }
}
