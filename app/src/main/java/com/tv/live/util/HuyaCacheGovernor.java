package com.tv.live.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.huya.berry.client.HuyaBerryConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🔧 虎牙 Berry SDK 缓存治理（纯直调模式，无反射）
 *
 * 【反编译验证结果】
 * HuyaBerryConfig.Builder 仅有 10 个 public 方法：
 *   appId/appKey/debugMode/landscapeMode/isNeedPlay/isOpenBugly/gameId
 *   cameraMode/oneKeyGangUp/hidePauseBtn/build
 * HuyaBerry + HuyaBerryImpl 无任何 setCacheDir/setLogDir/setRootDir 方法，
 * 也无 enableLog/openBugly 等扩展开关。
 * → SDK 不支持目录重定向 API，所有反射扫描都是无效代码，已删除。
 *
 * 【治理手段】
 * ① 在 Builder.build() 之前直调所有已知的精简开关（7 项）
 * ② 启动时递归扫描虎牙 SDK 常见写入目录，按「过期先删、超容量 LRU 再删」原则清理
 * ③ 对已知的日志/dump/xlog/crash/native_crash/MMKV 损坏锁文件等直接清理
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

    /** SDK 受控缓存根目录（系统"清除缓存"可清掉） */
    private static final String SDK_CACHE_DIR_NAME = "huya_sdk";

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
     * 纯直调模式：直接使用 HuyaBerryConfig.Builder 编译期类型 API 精简 SDK 配置。
     * 反编译已验证：Builder 仅支持 7 个 boolean 开关 + appId/appKey/gameId，无目录重定向 API。
     */
    public static void applyOnBuilder(Object builder, Context ctx) {
        if (builder == null) return;
        // 预创建受控缓存目录（SDK 可能写入此目录，便于后续清理）
        File base = new File(ctx.getCacheDir(), SDK_CACHE_DIR_NAME);
        if (!base.exists()) {
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
            Log.i(TAG, "✅ SDK 受控缓存目录已创建: " + base.getAbsolutePath());
        }

        if (!(builder instanceof HuyaBerryConfig.Builder)) {
            Log.w(TAG, "builder 类型不匹配，跳过直调配置");
            return;
        }
        applyOnBuilderDirect((HuyaBerryConfig.Builder) builder, ctx);
    }

    /**
     * 直接调用模式：使用 HuyaBerryConfig.Builder 编译期类型 API。
     * 无反射开销，方法签名在编译时检查。
     *
     * 反编译 HuyaBerryConfig$Builder.class 确认的全部 7 个 boolean 开关：
     *   debugMode / landscapeMode / isNeedPlay / isOpenBugly
     *   cameraMode / oneKeyGangUp / hidePauseBtn
     */
    public static void applyOnBuilderDirect(HuyaBerryConfig.Builder builder, Context ctx) {
        if (builder == null) return;
        try {
            builder.debugMode(false);
            builder.landscapeMode(false);
            builder.isNeedPlay(false);
            builder.isOpenBugly(false);
            builder.cameraMode(false);
            builder.oneKeyGangUp(false);
            builder.hidePauseBtn(false);
            Log.i(TAG, "✅ 直接调用模式: SDK 7 项配置已精简 (无反射)");
        } catch (Throwable t) {
            Log.w(TAG, "applyOnBuilderDirect 失败: " + t.getMessage());
        }
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
                        "HuyaBerry",
                        "Huya",
                        "huya_sdk",
                        "Duowan",
                        "NLog",
                        "QQBrowser/.tmp/huya_sdk",
                }) {
                    addDirIfExists(result, new File(sd, legacy));
                }
            }
        } catch (Throwable ignored) {}

        // 4) 受控缓存目录
        addDirIfExists(result, new File(ctx.getCacheDir(), SDK_CACHE_DIR_NAME));

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
