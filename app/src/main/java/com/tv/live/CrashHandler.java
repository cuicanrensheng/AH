package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获器
 * 捕获应用未处理的异常，保存日志并弹出错误页面
 *
 * 【使用方法】
 * 在 Application 的 onCreate 中调用：
 * CrashHandler.getInstance().init(this);
 *
 * 【2026-06-23 修改清单】
 * 1. ✅ 单例模式加 volatile，修复 DCL 多线程问题
 * 2. ✅ 增加崩溃处理标志位，防止递归崩溃
 * 3. ✅ 崩溃日志保存到文件（持久化，进程被杀后还在）
 * 4. ✅ 增加获取历史崩溃日志的方法（getAllCrashLogs）
 * 5. ✅ 增加清除历史崩溃日志的方法（clearAllCrashLogs）
 * 6. ✅ 优化崩溃日志格式，更易读
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";

    // ====================================================================
    // ✅ 修改 1：单例加 volatile，修复 DCL（双重检查锁定）多线程问题
    // ====================================================================
    //
    // 【为什么要加 volatile？】
    // 因为 new CrashHandler() 不是原子操作，JVM 可能会指令重排序：
    // 1. 分配内存空间
    // 2. 初始化对象
    // 3. instance 指向内存地址
    //
    // 指令重排后可能变成：1 → 3 → 2
    // 这时候另一个线程拿到的 instance 不是 null，
    // 但对象还没初始化完成，就会出现半初始化对象的问题。
    //
    // 加 volatile 后，禁止指令重排序，确保 2 一定在 3 之前完成。
    //
    // 【volatile 的两个作用】
    // 1. 可见性：一个线程修改了变量值，其他线程能立即看到最新值
    // 2. 禁止指令重排序：防止 JVM 为了优化而调整代码执行顺序
    // ====================================================================
    private static volatile CrashHandler instance;

    /** 上下文（ApplicationContext，避免内存泄漏） */
    private Context context;

    /** 系统默认的异常处理器（自定义处理失败时交给系统） */
    private Thread.UncaughtExceptionHandler defaultHandler;

    // ====================================================================
    // 崩溃日志（静态变量，供 CrashActivity 和 SettingsActivity 读取）
    // ====================================================================
    //
    // 【为什么用静态变量？】
    // 因为 CrashActivity 和 CrashHandler 在同一个进程中，
    // 静态变量可以直接访问，不需要通过 Intent 传递（Intent 有大小限制）。
    //
    // 【为什么加 volatile？】
    // 保证多线程可见性，崩溃线程写入后，UI 线程能立即读到最新值。
    //
    // 【注意】
    // 这只是内存中的临时存储，进程被杀后就没了。
    // 真正持久化的是文件保存的日志（见 saveCrashLogToFile 方法）。
    // ====================================================================
    public static volatile String CRASH_LOG = "";

    // ====================================================================
    // ✅ 修改 2：增加崩溃处理标志位，防止递归崩溃
    // ====================================================================
    //
    // 【为什么需要这个标志位？】
    // 如果崩溃处理过程中（比如启动 CrashActivity、保存日志文件）
    // 又发生了新的崩溃，会再次触发 uncaughtException，导致无限循环。
    //
    // 【场景举例】
    // 1. 应用崩溃 → 触发 uncaughtException
    // 2. 启动 CrashActivity → CrashActivity 自己也崩溃了
    // 3. 又触发 uncaughtException → 又启动 CrashActivity → 又崩溃...
    // 4. 无限循环，直到系统杀死进程
    //
    // 【解决方案】
    // 用一个标志位标记是否正在处理崩溃，
    // 如果已经在处理中，就直接交给系统默认处理，不再走自定义逻辑。
    //
    // 【为什么加 volatile？】
    // 保证多线程可见性，崩溃线程设置后，其他线程能立即看到。
    // ====================================================================
    private static volatile boolean isHandlingCrash = false;

    // ====================================================================
    // 崩溃日志文件相关常量
    // ====================================================================
    /** 崩溃日志保存的目录名 */
    private static final String CRASH_DIR_NAME = "crash";

    /** 崩溃日志文件名前缀 */
    private static final String CRASH_FILE_PREFIX = "crash_";

    /** 崩溃日志文件名后缀 */
    private static final String CRASH_FILE_SUFFIX = ".log";

    /** 最多保留的崩溃日志文件数量（防止占用太多存储空间） */
    private static final int MAX_CRASH_FILE_COUNT = 10;

    /**
     * 私有构造方法（单例模式）
     *
     * 【为什么私有？】
     * 单例模式要求只能有一个实例，
     * 私有构造方法防止外部通过 new 创建多个实例。
     */
    private CrashHandler() {}

    /**
     * 获取单例
     *
     * 【双重检查锁定（DCL - Double-Checked Locking）】
     * 1. 第一次检查：如果 instance 不为 null，直接返回，不用加锁，性能好
     * 2. 加锁：保证同一时间只有一个线程能进入
     * 3. 第二次检查：防止多个线程同时通过第一次检查，重复创建对象
     *
     * 【为什么不用 synchronized 直接修饰方法？】
     * 因为每次调用都要加锁，性能差。
     * DCL 只有第一次创建时才加锁，之后直接返回，性能好。
     *
     * 【为什么 instance 要加 volatile？】
     * 防止指令重排序导致半初始化对象问题。
     * 详见上面 instance 变量的注释。
     *
     * @return CrashHandler 单例对象
     */
    public static CrashHandler getInstance() {
        // 第一次检查（无锁，性能好）
        if (instance == null) {
            // 加锁（保证同一时间只有一个线程能进入）
            synchronized (CrashHandler.class) {
                // 第二次检查（防止重复创建）
                if (instance == null) {
                    instance = new CrashHandler();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化崩溃捕获器
     *
     * 【做了什么？】
     * 1. 保存 ApplicationContext（避免内存泄漏）
     * 2. 保存系统默认的异常处理器（兜底用）
     * 3. 设置自己为默认异常处理器
     *
     * 【为什么用 ApplicationContext？】
     * 因为 CrashHandler 是单例，生命周期和应用一样长。
     * 如果存 Activity 的 Context，会导致 Activity 无法被回收，造成内存泄漏。
     * ApplicationContext 的生命周期和应用一样，不会有内存泄漏问题。
     *
     * 【为什么要保存系统默认的异常处理器？】
     * 作为兜底方案：如果自定义处理失败了，
     * 还可以交给系统默认处理，不至于完全崩溃。
     *
     * @param ctx 上下文
     */
    public void init(Context ctx) {
        // 用 ApplicationContext，避免内存泄漏
        context = ctx.getApplicationContext();

        // 保存系统默认的异常处理器（兜底用）
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        // 设置为默认异常处理器
        Thread.setDefaultUncaughtExceptionHandler(this);

        Log.d(TAG, "全局崩溃捕获器已初始化");
    }

    /**
     * 未捕获异常回调
     *
     * 【什么时候调用？】
     * 当应用中发生了未被 try-catch 捕获的异常时，
     * 系统会调用这个方法。
     *
     * 【处理流程】
     * 1. 检查是否正在处理崩溃（防止递归崩溃）
     * 2. 标记正在处理崩溃
     * 3. 保存崩溃日志（内存 + 文件）
     * 4. 启动崩溃页面（CrashActivity）
     * 5. 等待 500ms 后杀死进程
     *
     * 【为什么要杀死进程？】
     * 因为应用已经崩溃了，状态不正常了，
     * 继续运行可能会导致更多问题。
     * 让用户在崩溃页面选择重启或退出更安全。
     *
     * @param thread 发生异常的线程
     * @param ex 异常对象
     */
    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        // ====================================================================
        // ✅ 修改 2：防止递归崩溃
        // ====================================================================
        //
        // 如果已经在处理崩溃了，就直接交给系统默认处理，
        // 避免崩溃处理过程中再次崩溃导致无限循环。
        if (isHandlingCrash) {
            Log.e(TAG, "检测到递归崩溃，交给系统默认处理");
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
            return;
        }

        // 标记正在处理崩溃
        isHandlingCrash = true;

        try {
            // 1. 保存崩溃日志（内存 + 文件）
            saveCrashLog(thread, ex);

            // 2. 启动崩溃页面（用新的任务栈启动）
            //
            // 【为什么用 NEW_TASK？】
            // 因为我们是从 ApplicationContext 启动 Activity 的，
            // 必须加 FLAG_ACTIVITY_NEW_TASK，否则会报错。
            //
            // 【为什么用 CLEAR_TOP？】
            // 清除任务栈中已有的 Activity，
            // 确保崩溃页面是任务栈中唯一的 Activity。
            Intent intent = new Intent(context, CrashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);

            // 3. 等待页面启动后，杀死崩溃的进程
            //
            // 【为什么要 sleep 500ms？】
            // 因为 startActivity 是异步的，需要给 CrashActivity 一点启动时间。
            // 如果立刻杀进程，CrashActivity 可能还没启动起来就被杀了。
            //
            // 【500ms 是怎么来的？】
            // 经验值：一般 Activity 启动在 100-300ms 左右，
            // 留 500ms 比较保险，又不会让用户等太久。
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                // 中断异常不用处理，继续执行就行
            }

            // 杀死当前进程
            Process.killProcess(Process.myPid());
            // 退出虚拟机（确保完全退出）
            System.exit(1);

        } catch (Exception e) {
            Log.e(TAG, "崩溃处理失败", e);

            // 如果自定义处理失败，交给系统默认处理（兜底）
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
        }
    }

    /**
     * 保存崩溃日志
     *
     * 【保存到两个地方】
     * 1. 内存：CRASH_LOG 静态变量（供 CrashActivity 立即显示）
     * 2. 文件：/sdcard/Android/data/包名/cache/crash/ 目录（持久化，供后续查看）
     *
     * 【为什么要保存两个地方？】
     * - 内存：CrashActivity 启动后能立即读取显示，速度快
     * - 文件：进程被杀后还在，用户重启应用后还能查看历史崩溃
     *
     * @param thread 发生异常的线程
     * @param ex 异常对象
     */
    private void saveCrashLog(Thread thread, Throwable ex) {
        try {
            StringBuilder sb = new StringBuilder();

            // 日志头部
            sb.append("================ 崩溃日志 ================\n");
            sb.append("时间：").append(formatDate(new Date())).append("\n");
            sb.append("线程：").append(thread.getName()).append("\n");
            sb.append("异常类型：").append(ex.getClass().getName()).append("\n");
            sb.append("异常信息：").append(ex.getMessage()).append("\n");
            sb.append("\n========== 堆栈信息 ==========\n");

            // 获取完整堆栈信息
            //
            // 【为什么用 StringWriter + PrintWriter？】
            // 因为 Throwable.printStackTrace() 方法默认是输出到控制台的，
            // 我们需要把堆栈信息保存到字符串里，
            // 所以用 StringWriter 作为输出目标，PrintWriter 作为桥接。
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            pw.close();
            sb.append(sw.toString());

            // 日志尾部
            sb.append("\n========================================\n");

            String crashLog = sb.toString();

            // 1. 保存到内存（供 CrashActivity 立即显示）
            CRASH_LOG = crashLog;
            Log.e(TAG, CRASH_LOG);

            // ====================================================================
            // ✅ 修改 3：保存到文件（持久化）
            // ====================================================================
            //
            // 【为什么要保存到文件？】
            // 因为静态变量存在内存里，进程被杀后就没了。
            // 保存到文件后，用户重启应用还能查看历史崩溃记录。
            //
            // 【保存路径】
            // /sdcard/Android/data/包名/cache/crash/crash_20260623_153045.log
            //
            // 【为什么用 cache 目录？】
            // 1. 不需要申请存储权限（Android 6.0+）
            // 2. 用户清理缓存时会自动清理，不会一直占用空间
            // 3. 应用卸载时会自动删除，不会残留垃圾文件
            saveCrashLogToFile(crashLog);

            // 3. 同步到 SettingsActivity 的日志系统
            //
            // 【为什么要同步？】
            // 让崩溃日志也出现在操作日志里，
            // 用户在操作日志页面也能看到崩溃记录。
            //
            // 【为什么用 try-catch 包起来？】
            // 因为 SettingsActivity 可能还没初始化，
            // 直接调用可能会报错，用 try-catch 兜底。
            try {
                SettingsActivity.log("【崩溃】" + ex.getClass().getName() + ": " + ex.getMessage());
            } catch (Exception ignored) {
                // SettingsActivity 没初始化就算了，不影响崩溃处理
            }

        } catch (Exception e) {
            // 保存日志失败了，不能让这个异常影响崩溃处理
            Log.e(TAG, "保存崩溃日志失败", e);
        }
    }

    // ====================================================================
    // ✅ 修改 3：崩溃日志保存到文件（持久化）
    // ====================================================================

    /**
     * 保存崩溃日志到文件
     *
     * 【保存路径】
     * /sdcard/Android/data/包名/cache/crash/crash_yyyyMMdd_HHmmss.log
     *
     * 【文件命名规则】
     * crash_20260623_153045.log
     * 前缀 + 年月日_时分秒 + 后缀
     * 方便按时间排序和查找。
     *
     * 【做了什么？】
     * 1. 获取崩溃日志目录
     * 2. 生成带时间戳的文件名
     * 3. 写入文件
     * 4. 清理过期的崩溃日志（最多保留 10 个）
     *
     * @param crashLog 崩溃日志内容
     */
    private void saveCrashLogToFile(String crashLog) {
        try {
            // 1. 获取崩溃日志目录
            File crashDir = getCrashDir();
            if (crashDir == null) {
                Log.e(TAG, "无法获取崩溃日志目录");
                return;
            }

            // 2. 生成文件名（带时间戳）
            // 文件名格式：crash_20260623_153045.log
            String fileName = CRASH_FILE_PREFIX
                    + formatDateForFileName(new Date())
                    + CRASH_FILE_SUFFIX;
            File crashFile = new File(crashDir, fileName);

            // 3. 写入文件
            //
            // 【为什么用 FileWriter？】
            // 简单方便，适合写入文本文件。
            // 写完后记得 flush 和 close，确保数据落盘。
            FileWriter writer = new FileWriter(crashFile);
            writer.write(crashLog);
            writer.flush();  // 刷新缓冲区，确保数据写入文件
            writer.close();  // 关闭文件流

            Log.d(TAG, "崩溃日志已保存到文件：" + crashFile.getAbsolutePath());

            // 4. 清理过期的崩溃日志（最多保留 10 个）
            cleanOldCrashFiles(crashDir);

        } catch (Exception e) {
            Log.e(TAG, "保存崩溃日志到文件失败", e);
        }
    }

    /**
     * 获取崩溃日志保存目录
     *
     * 【目录路径】
     * /sdcard/Android/data/包名/cache/crash/
     *
     * 【为什么用 cache 目录？】
     * 1. 不需要申请存储权限（Android 6.0+ 应用专属目录）
     * 2. 用户清理缓存时会自动清理，不会一直占用空间
     * 3. 应用卸载时会自动删除，不会残留垃圾文件
     *
     * 【如果目录不存在怎么办？】
     * 自动创建（mkdirs() 会创建所有不存在的父目录）。
     *
     * @return 崩溃日志目录，如果获取失败返回 null
     */
    private File getCrashDir() {
        try {
            // 获取应用的 cache 目录
            // /sdcard/Android/data/包名/cache/
            File cacheDir = context.getCacheDir();
            if (cacheDir == null) {
                return null;
            }

            // 创建 crash 子目录
            File crashDir = new File(cacheDir, CRASH_DIR_NAME);
            if (!crashDir.exists()) {
                // mkdirs() 会创建所有不存在的父目录
                crashDir.mkdirs();
            }

            return crashDir;

        } catch (Exception e) {
            Log.e(TAG, "获取崩溃日志目录失败", e);
            return null;
        }
    }

    /**
     * 清理过期的崩溃日志文件
     *
     * 【为什么要清理？】
     * 如果崩溃频繁发生，日志文件会越来越多，占用存储空间。
     * 只保留最近的 10 个，老的自动删除。
     *
     * 【清理策略】
     * 1. 列出目录下所有文件
     * 2. 按文件名排序（文件名带时间戳，按字母排序就是按时间排序）
     * 3. 删除最老的文件，只保留 MAX_CRASH_FILE_COUNT 个
     *
     * 【为什么按文件名排序就能按时间排序？】
     * 因为文件名格式是 crash_yyyyMMdd_HHmmss.log，
     * 时间在前的文件名也在前（字母顺序和时间顺序一致）。
     *
     * @param crashDir 崩溃日志目录
     */
    private void cleanOldCrashFiles(File crashDir) {
        try {
            File[] files = crashDir.listFiles();
            if (files == null || files.length <= MAX_CRASH_FILE_COUNT) {
                return;  // 没超过数量限制，不用清理
            }

            // 按文件名排序（默认是字母升序，也就是时间从早到晚）
            java.util.Arrays.sort(files);

            // 删除最老的文件，只保留 MAX_CRASH_FILE_COUNT 个
            // 因为排序后最老的在最前面，所以从第 0 个开始删
            int deleteCount = files.length - MAX_CRASH_FILE_COUNT;
            for (int i = 0; i < deleteCount; i++) {
                files[i].delete();
                Log.d(TAG, "删除过期崩溃日志：" + files[i].getName());
            }

        } catch (Exception e) {
            Log.e(TAG, "清理过期崩溃日志失败", e);
        }
    }

    /**
     * 获取所有历史崩溃日志
     *
     * 【用途】
     * 供 SettingsActivity 的"崩溃日志"页面显示历史崩溃记录。
     *
     * 【返回顺序】
     * 按时间倒序（最新的崩溃在最前面）。
     *
     * 【为什么是静态方法？】
     * 因为 SettingsActivity 可能还没初始化 CrashHandler，
     * 静态方法可以直接调用，不需要先 getInstance()。
     *
     * @param context 上下文
     * @return 历史崩溃日志数组，按时间倒序（最新的在最前面）
     */
    public static String[] getAllCrashLogs(Context context) {
        try {
            // 构建崩溃日志目录路径
            File crashDir = new File(context.getCacheDir(), CRASH_DIR_NAME);
            if (!crashDir.exists()) {
                return new String[0];  // 目录不存在，返回空数组
            }

            File[] files = crashDir.listFiles();
            if (files == null || files.length == 0) {
                return new String[0];  // 没有文件，返回空数组
            }

            // 按文件名排序（升序，时间从早到晚）
            java.util.Arrays.sort(files);
            // 反转成降序（最新的在最前面）
            java.util.Collections.reverse(java.util.Arrays.asList(files));

            // 读取每个文件的内容
            String[] logs = new String[files.length];
            for (int i = 0; i < files.length; i++) {
                logs[i] = readFileToString(files[i]);
            }

            return logs;

        } catch (Exception e) {
            Log.e(TAG, "获取历史崩溃日志失败", e);
            return new String[0];
        }
    }

    /**
     * 清除所有历史崩溃日志
     *
     * 【用途】
     * 供 SettingsActivity 的"清空日志"功能使用。
     *
     * 【为什么是静态方法？】
     * 和 getAllCrashLogs 一样，方便直接调用。
     *
     * @param context 上下文
     */
    public static void clearAllCrashLogs(Context context) {
        try {
            File crashDir = new File(context.getCacheDir(), CRASH_DIR_NAME);
            if (!crashDir.exists()) {
                return;  // 目录不存在，不用清
            }

            File[] files = crashDir.listFiles();
            if (files == null) {
                return;
            }

            // 遍历删除所有文件
            for (File file : files) {
                file.delete();
            }

            Log.d(TAG, "已清除所有崩溃日志");

        } catch (Exception e) {
            Log.e(TAG, "清除崩溃日志失败", e);
        }
    }

    // ====================================================================
    // 工具方法
    // ====================================================================

    /**
     * 格式化日期（显示用）
     *
     * 【格式】
     * yyyy-MM-dd HH:mm:ss
     * 例如：2026-06-23 15:30:45
     *
     * 【为什么用 Locale.getDefault()？】
     * 适配不同地区的日期格式习惯。
     * 不过我们用的是数字格式，其实影响不大，
     * 但加上是个好习惯。
     *
     * @param date 日期对象
     * @return 格式化后的日期字符串
     */
    private String formatDate(Date date) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            return sdf.format(date);
        } catch (Exception e) {
            // 格式化失败就返回时间戳，至少不会报错
            return String.valueOf(date.getTime());
        }
    }

    /**
     * 格式化日期（文件名用，不能有冒号等特殊字符）
     *
     * 【格式】
     * yyyyMMdd_HHmmss
     * 例如：20260623_153045
     *
     * 【为什么不用冒号？】
     * 因为 Windows 和某些文件系统不支持文件名里有冒号（:），
     * 所以文件名用下划线分隔，避免兼容性问题。
     *
     * @param date 日期对象
     * @return 格式化后的日期字符串（适合作为文件名）
     */
    private String formatDateForFileName(Date date) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            return sdf.format(date);
        } catch (Exception e) {
            // 格式化失败就返回时间戳
            return String.valueOf(date.getTime());
        }
    }

    /**
     * 读取文件内容为字符串
     *
     * 【为什么用 FileInputStream？】
     * 简单直接，适合读取小文件（崩溃日志一般都不大）。
     *
     * 【注意】
     * 这个方法是静态的，因为 getAllCrashLogs 是静态方法，
     * 需要调用静态的工具方法。
     *
     * @param file 要读取的文件
     * @return 文件内容字符串
     */
    private static String readFileToString(File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            // available() 返回文件的字节数（注意：只对本地文件可靠，网络流不可靠）
            byte[] buffer = new byte[fis.available()];
            fis.read(buffer);
            fis.close();
            return new String(buffer);
        } catch (Exception e) {
            return "读取失败：" + e.getMessage();
        }
    }
}
