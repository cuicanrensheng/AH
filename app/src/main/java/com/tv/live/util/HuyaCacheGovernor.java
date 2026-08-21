package com.tv.live.util;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🔧 虎牙 Berry SDK 缓存治理
 *
 * 【问题根因】
 * 虎牙 SDK 默认会把日志、崩溃 dump、设备指纹、HTTP DNS 缓存、游戏资源、xlog、MMKV、礼物表情等
 * 写入多个目录（filesDir、外部存储、sdcard 公共目录、databases），且**不受 CacheManager 管控**。
 * 长时间运行后，/data/data/com.tv.live/files + /sdcard/Android/data/com.tv.live 会增长到数百 MB，
 * 导致 "应用安装后占用变大"。
 *
 * 【治理手段】
 * ① 反射在 HuyaBerryConfig.Builder#build() 之前，自动禁用日志/上报/游戏/连麦等不需要的功能，
 *    并尽力把 SDK 的 cache/file 目录重定向到 App cacheDir。
 * ② 启动时递归扫描虎牙 SDK 常见写入目录，按「过期先删、超容量 LRU 再删」原则清理。
 * ③ 对已知的日志/dump/xlog/crash/native_crash/MMKV 损坏锁文件等直接清理。
 */
public class HuyaCacheGovernor {

    private static final String TAG = "HuyaCacheGov";

    /** 允许 SDK 缓存占用的最大总容量（默认 30MB），超过按 LRU 清理到 15MB */
    private static final long MAX_TOTAL_BYTES = 30L * 1024 * 1024;
    private static final long TARGET_TOTAL_BYTES = 15L * 1024 * 1024;

    /** 文件可被清理的最短存活时间（2 小时内的文件不动，避免误伤正在用的缓存） */
    private static final long MIN_AGE_MS = 2 * 60 * 60 * 1000L;

    /** 过期判定阈值（> 7 天的 SDK 文件直接删除，无论是否超容） */
    private static final long EXPIRE_MS = 7L * 24 * 60 * 60 * 1000L;

    private static final String[] LOG_EXT = {".xlog", ".log", ".txt", ".bak", ".trace"};
    private static final String[] CRASH_DIR_HINTS = {"crash", "tombstone", "dump", "anr", "dropbox", "core"};

    // ===================== 对外入口 =====================

    /**
     * 启动时调用（在 HuyaSDKParser.init 之前即可）。
     *
     * 做两件事：
     *   1) 清理 >7 天的老旧 SDK 文件 + 立即清理日志/崩溃/锁文件
     *   2) 若总占用仍 > 30MB，按修改时间 LRU 清理到 15MB
     */
    public static void startupCleanup(final Context ctx) {
        if (ctx == null) return;
        // 用独立线程，不阻塞 Application.onCreate
        new Thread(() -> {
            try {
                performCleanup(ctx);
            } catch (Throwable t) {
                Log.w(TAG, "startupCleanup failed: " + t.getMessage());
            }
        }, "HuyaCacheCleanup").start();
    }

    /**
     * HuyaSDKParser.init 内部调用：在 Builder.build() 之前调用。
     *
     * 反射扫描 HuyaBerryConfig.Builder，尝试自动：
     *   - 关闭日志写入 (isOpenLog / openLog / enableLog / debugMode)
     *   - 关闭 Bugly/数据上报 (isOpenBugly isOpenAnalytics / openStatistics)
     *   - 关闭不需要的功能（gamesdk/webview/连麦/摄像头等，冗余开关不报错也没副作用）
     *   - 设置缓存/文件目录到 context.getCacheDir()/huya_sdk（系统设置"清除缓存"能把它清掉）
     */
    public static void applyOnBuilder(Object builder, Context ctx) {
        if (builder == null) return;
        try {
            // 1) 先尝试 setRootDir / setBaseDir / setCacheDir / setLogDir / setFileDir：把 SDK 写入全部收到 cacheDir/huya_sdk
            try {
                File base = new File(ctx.getCacheDir(), "huya_sdk");
                if (!base.exists()) base.mkdirs();
                File cacheDir = new File(base, "cache");
                File fileDir  = new File(base, "files");
                File logDir   = new File(base, "logs");
                File tmpDir   = new File(base, "tmp");
                for (File d : new File[]{cacheDir, fileDir, logDir, tmpDir}) {
                    try { if (!d.exists()) d.mkdirs(); } catch (Throwable ignored) {}
                }
                trySetDir(builder, "setRootDir",    base);
                trySetDir(builder, "rootDir",       base);
                trySetDir(builder, "setBaseDir",    base);
                trySetDir(builder, "baseDir",       base);
                trySetDir(builder, "setWorkDir",    base);
                trySetDir(builder, "workDir",       base);
                trySetDir(builder, "setSdkDir",     base);
                trySetDir(builder, "sdkDir",        base);
                trySetDir(builder, "setDataDir",    fileDir);
                trySetDir(builder, "dataDir",       fileDir);
                trySetDir(builder, "setFileDir",    fileDir);
                trySetDir(builder, "fileDir",       fileDir);
                trySetDir(builder, "setFilesDir",   fileDir);
                trySetDir(builder, "filesDir",      fileDir);
                trySetDir(builder, "setCacheDir",   cacheDir);
                trySetDir(builder, "cacheDir",      cacheDir);
                trySetDir(builder, "setTempDir",    tmpDir);
                trySetDir(builder, "tempDir",       tmpDir);
                trySetDir(builder, "setLogDir",     logDir);
                trySetDir(builder, "logDir",        logDir);
                trySetDir(builder, "setXLogDir",    logDir);
                trySetDir(builder, "xLogDir",       logDir);
                trySetDir(builder, "setCrashDir",   new File(base, "crash"));
                trySetDir(builder, "crashDir",      new File(base, "crash"));
                Log.i(TAG, "✅ SDK 根目录重定向至: " + base);
            } catch (Throwable t) {
                Log.w(TAG, "⚠️ SDK 目录重定向失败: " + t.getMessage());
            }

            // 2) 布尔型开关 — 直接尝试调用 setter，存在（兼容签名可强转）就置 false，不存在静默跳过
            //    列表按经验 + SDK 常见命名规律排列，覆盖度更广
            String[][] boolSwitches = {
                    // 日志 / 调试 — 写入磁盘最多，优先级最高
                    {"debugMode", "false"},
                    {"isDebug", "false"},
                    {"debug", "false"},
                    {"isOpenLog", "false"},
                    {"openLog", "false"},
                    {"enableLog", "false"},
                    {"isEnableLog", "false"},
                    {"isLogEnable", "false"},
                    {"logEnable", "false"},
                    {"isOpenXLog", "false"},
                    {"openXLog", "false"},
                    {"xlogEnable", "false"},
                    {"isOpenConsoleLog", "false"},
                    {"printLog", "false"},
                    {"isPrintLog", "false"},
                    // Bugly / 崩溃上报
                    {"isOpenBugly", "false"},
                    {"openBugly", "false"},
                    {"enableBugly", "false"},
                    {"buglyEnable", "false"},
                    {"isBuglyEnable", "false"},
                    {"isOpenCrashReport", "false"},
                    {"openCrashReport", "false"},
                    {"enableCrashReport", "false"},
                    // 数据统计 / 埋点 / 分析
                    {"isOpenAnalytics", "false"},
                    {"openAnalytics", "false"},
                    {"enableAnalytics", "false"},
                    {"isOpenStat", "false"},
                    {"openStat", "false"},
                    {"enableStat", "false"},
                    {"isOpenApm", "false"},
                    {"openApm", "false"},
                    {"enableApm", "false"},
                    {"isOpenReport", "false"},
                    {"openReport", "false"},
                    {"enableReport", "false"},
                    {"isOpenMonitor", "false"},
                    {"openMonitor", "false"},
                    // 功能组件（不需要，减少初始化+写入）
                    {"isNeedPlay", "false"},
                    {"cameraMode", "false"},
                    {"oneKeyGangUp", "false"},
                    {"isOpenGame", "false"},
                    {"openGame", "false"},
                    {"enableGame", "false"},
                    {"isOpenGameSdk", "false"},
                    {"openGameSdk", "false"},
                    {"enableGameSdk", "false"},
                    {"isOpenBeauty", "false"},
                    {"openBeauty", "false"},
                    {"isOpenPush", "false"},
                    {"openPush", "false"},
                    {"isOpenIm", "false"},
                    {"openIm", "false"},
                    {"isOpenLive", "false"},
                    {"isOpenLiveTool", "false"},
                    {"isOpenCamera", "false"},
                    {"enableCamera", "false"},
                    {"isOpenUpload", "false"},
                    {"openUpload", "false"},
            };
            int applied = 0, tried = 0;
            for (String[] pair : boolSwitches) {
                tried++;
                if (trySetBool(builder, pair[0], false)) applied++;
            }
            Log.i(TAG, "✅ SDK 精简开关: 尝试 " + tried + " 项, 命中 " + applied + " 项");
        } catch (Throwable t) {
            Log.w(TAG, "applyOnBuilder failed, ignore: " + t.getMessage());
        }
    }

    // ===================== 内部：目录反射 =====================

    private static void trySetDir(Object builder, String methodName, File dir) {
        try {
            Method m = findMethod(builder, methodName, File.class);
            if (m == null) return;
            m.invoke(builder, dir);
        } catch (Throwable t) { /* ignore */ }
    }

    private static boolean trySetBool(Object builder, String methodName, boolean value) {
        // 同时尝试：foo(boolean) / setFoo(boolean) / isSetFoo(boolean) 三种命名
        String[] candidates;
        if (methodName.startsWith("is") && methodName.length() > 2
                && Character.isUpperCase(methodName.charAt(2))) {
            String base = methodName.substring(2);
            candidates = new String[]{
                    methodName,
                    "set" + base,
                    Character.toLowerCase(base.charAt(0)) + base.substring(1),
                    "enable" + base,
            };
        } else {
            String cap = Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
            candidates = new String[]{
                    methodName,
                    "set" + cap,
                    "is" + cap,
                    "enable" + cap,
            };
        }
        for (String name : candidates) {
            try {
                Method m = findMethod(builder, name, boolean.class);
                if (m == null) continue;
                m.invoke(builder, value);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static Method findMethod(Object o, String name, Class<?> paramType) {
        Method[] ms = o.getClass().getMethods();
        for (Method m : ms) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 1) continue;
            Class<?> pt = pts[0];
            if (pt == paramType) {
                return m;
            }
            // 对 Boolean 包装类型也兼容
            if (paramType == boolean.class && pt == Boolean.class) return m;
        }
        return null;
    }

    // ===================== 内部：清理逻辑 =====================

    private static void performCleanup(Context ctx) {
        long start = System.currentTimeMillis();
        List<File> candidates = collectHuyaCandidateDirs(ctx);

        long beforeBytes = 0L;
        List<FileEntry> all = new ArrayList<>();
        for (File root : candidates) beforeBytes += walkAndCollect(root, all, true);

        Log.i(TAG, "扫描到 SDK 候选目录 " + candidates.size() + " 个, 共 " + all.size()
                + " 个文件, 当前占用 = " + human(beforeBytes));

        // Step 1: 删除肯定安全的内容（日志/崩溃 dump / 过期 7 天）
        long deletedStep1 = 0L;
        for (FileEntry e : all) {
            boolean shouldDelete = false;
            if (isLogOrCrashFile(e.file)) shouldDelete = true;
            long age = System.currentTimeMillis() - e.file.lastModified();
            if (age > EXPIRE_MS) shouldDelete = true;

            if (shouldDelete && age > MIN_AGE_MS) {
                if (e.file.delete()) deletedStep1 += e.size;
            }
        }
        // 清理后重新统计剩余文件
        all.clear();
        long afterStep1 = 0L;
        for (File root : candidates) afterStep1 += walkAndCollect(root, all, false);
        Log.i(TAG, "Step1(日志/崩溃/过期) 清理: " + human(deletedStep1)
                + "  剩余 " + all.size() + " 文件 = " + human(afterStep1));

        // Step 2: 如果仍大于 MAX_TOTAL_BYTES，按 LRU 清理到 TARGET_TOTAL_BYTES
        if (afterStep1 > MAX_TOTAL_BYTES) {
            Collections.sort(all, new Comparator<FileEntry>() {
                @Override public int compare(FileEntry a, FileEntry b) {
                    return Long.compare(a.file.lastModified(), b.file.lastModified());
                }
            });
            long toFree = afterStep1 - TARGET_TOTAL_BYTES;
            long freed = 0L;
            for (FileEntry e : all) {
                if (freed >= toFree) break;
                long age = System.currentTimeMillis() - e.file.lastModified();
                if (age < MIN_AGE_MS) continue;   // 近 2 小时内新文件不删
                if (isLogOrCrashFile(e.file) || age > MIN_AGE_MS) {
                    if (e.file.delete()) {
                        freed += e.size;
                    }
                }
            }
            Log.i(TAG, "Step2(容量超限LRU) 清理: " + human(freed));
        }

        long after = 0L;
        for (File root : candidates) after += walkSize(root);
        long cost = System.currentTimeMillis() - start;
        Log.i(TAG, "✅ 清理完成: " + human(beforeBytes) + " → " + human(after)
                + "  节省 " + human(beforeBytes - after) + "  耗时 " + cost + "ms");
    }

    private static List<File> collectHuyaCandidateDirs(Context ctx) {
        List<File> result = new ArrayList<>();
        String pkg = ctx.getPackageName();

        // 1) 应用私有目录
        addDirIfExists(result, ctx.getFilesDir());
        addDirIfExists(result, ctx.getCacheDir());
        addDirIfExists(result, ctx.getDir("huya_sdk", Context.MODE_PRIVATE));
        File databases = new File(ctx.getApplicationInfo().dataDir, "databases");
        addDirIfExists(result, databases);
        File sharedPrefs = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
        addDirIfExists(result, sharedPrefs);
        File codeCache = new File(ctx.getApplicationInfo().dataDir, "code_cache");
        addDirIfExists(result, codeCache);
        File noBackup = new File(ctx.getApplicationInfo().dataDir, "no_backup");
        addDirIfExists(result, noBackup);

        // 2) 外部存储 / Android/data/<pkg>/  (读写不需要权限)
        try {
            File extFiles = ctx.getExternalFilesDir(null);
            addDirIfExists(result, extFiles);
            File extCache = ctx.getExternalCacheDir();
            addDirIfExists(result, extCache);
            if (extFiles != null) {
                addDirIfExists(result, new File(extFiles.getParentFile(), "cache"));
            }
        } catch (Throwable ignored) {}

        // 3) 公共存储 SDK 历史遗留目录（绝大多数 Android 11+ 不可访问，列出来不报错）
        try {
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                File sd = Environment.getExternalStorageDirectory();
                for (String legacy : new String[]{
                        "Android/data/" + pkg + "/files/huya_sdk",
                        "Android/data/" + pkg + "/files/HuyaBerry",
                        "Android/data/" + pkg + "/files/tencent/MobileQQ",   // 不相关，避免误报
                        "HuyaBerry",
                        "Huya",
                        "huya_sdk",
                        "Duowan",
                        "NLog",
                        "QQBrowser/.tmp/huya_sdk",
                }) {
                    if (legacy.contains("MobileQQ")) continue;
                    addDirIfExists(result, new File(sd, legacy));
                }
            }
        } catch (Throwable ignored) {}

        // 4) 针对我们之前在 applyOnBuilder 里主动设置的 huya_sdk 子目录
        addDirIfExists(result, new File(ctx.getCacheDir(), "huya_sdk"));

        return result;
    }

    private static void addDirIfExists(List<File> list, File dir) {
        if (dir == null) return;
        if (!dir.exists() || !dir.isDirectory()) return;
        list.add(dir);
    }

    private static boolean isLogOrCrashFile(File f) {
        if (f == null) return false;
        String name = f.getName().toLowerCase(Locale.ROOT);
        String abs = f.getAbsolutePath().toLowerCase(Locale.ROOT);
        for (String ext : LOG_EXT) {
            if (name.endsWith(ext)) return true;
        }
        for (String hint : CRASH_DIR_HINTS) {
            if (name.contains(hint)) return true;
            if (abs.contains("/" + hint + "/") || abs.contains("\\" + hint + "\\")) return true;
        }
        if (name.startsWith("core-") && f.length() > 1024 * 1024) return true;        // native core dump
        if (name.endsWith(".dmp") || name.endsWith(".dmp.bak")) return true;
        if (name.startsWith("crash_") && name.endsWith(".txt")) return true;             // 自家 CrashHandler 产物
        if (name.endsWith(".lock") && name.contains("mmkv")) return true;                // MMKV 锁文件（失效残留）
        if (name.startsWith("httpdns") && name.endsWith(".cache")) return true;          // hyhttpdns 缓存可删
        if (name.startsWith("dns_cache")) return true;
        if (name.endsWith(".db-shm") || name.endsWith(".db-wal")) return true;          // SQLite 临时 journal
        return false;
    }

    // ===================== 工具方法：walk =====================

    private static long walkAndCollect(File root, List<FileEntry> out, boolean includeDirs) {
        if (root == null || !root.exists()) return 0L;
        AtomicLong sum = new AtomicLong(0);
        walkRecursive(root, out, includeDirs, sum);
        return sum.get();
    }

    private static void walkRecursive(File node, List<FileEntry> out, boolean includeDirs, AtomicLong sum) {
        if (node == null || !node.exists()) return;
        if (node.isDirectory()) {
            // 不要把系统目录 / 自己的 tv_cache 也纳入（tv_cache 属于 CacheManager 自己会管）
            String n = node.getName();
            if ("tv_cache".equals(n)) return;
            // 对 .nomedia / code_cache 特殊跳过
            File[] subs = node.listFiles();
            if (subs == null) return;
            for (File s : subs) walkRecursive(s, out, includeDirs, sum);
        } else if (node.isFile()) {
            long sz = node.length();
            sum.addAndGet(sz);
            if (out != null) out.add(new FileEntry(node, sz));
        }
    }

    private static long walkSize(File root) {
        return walkAndCollect(root, null, false);
    }

    private static String human(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static class FileEntry {
        final File file;
        final long size;
        FileEntry(File f, long s) { file = f; size = s; }
    }
}
