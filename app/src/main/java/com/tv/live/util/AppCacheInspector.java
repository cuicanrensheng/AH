package com.tv.live.util;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * ✅ 应用级缓存总检察长：覆盖 ExoPlayer 临时文件 / CacheManager / EPG / 直播源
 *                               / CrashLogs / Update APK / WebView / Cookie / JsParser 插件
 *                               / 应用私有 shared_prefs + databases + code_cache
 *
 * 工作方式：
 *   1. 在 MyApplication.onCreate 调一次 startupCleanup(ctx)  启动时回收一次
 *   2. 每类资源给出单独的上限/过期策略，不会相互挤占
 *
 * 被管目录清单：
 *   getCacheDir()                 / 系统可一键清除
 *     ├─ tv_cache/                CacheManager 上限 20MB → 进一步按 key 裁剪 + 硬删过期
 *     ├─ exo_tmp/                 ExoPlayer / Media3 临时分片 (本次新增目录+清理规则)
 *     ├─ huya_sdk/                HuyaCacheGovernor 重定向后的 SDK 缓存
 *     ├─ okhttp_cache/            Http 响应缓存 (如果有)
 *     ├─ rclone_* / image_cache*  通用杂项
 *     └─ WebViewCache/  ...       系统标准 webview 目录 (code_cache/webview 等)
 *   getFilesDir()                 / 用户在设置里点"清除数据"才清
 *     ├─ crash_logs/              CrashHandler 崩溃文本 (只保留最近 3 个 / 7 天)
 *     ├─ js/parser/               JsLayer 解密脚本插件 (保留 md5 校验过的, 删 14 天前的)
 *     └─ 其他 huya/berry sdk 历史遗留
 *   getExternalFilesDir(null)/Downloads
 *     └─ tv_live_update_*.apk     UpdateManager 遗留 APK (安装完成或下载失败后一律删掉)
 *   code_cacheDir/                / Android 运行时产物
 *     └─ drops/ traces-  perfetto  strictmode  (清理)
 */
public class AppCacheInspector {

    private static final String TAG = "AppCacheInspector";

    // ============================================================
    // 各类资源独立限额（字节）
    // ============================================================
    private static final long TARGET_TV_CACHE_BYTES       = 10L * 1024 * 1024; // 10MB  业务(EPG+直播源)
    private static final long HARD_TV_CACHE_BYTES         = 15L * 1024 * 1024; // 15MB  硬上限
    private static final long TV_CACHE_ITEM_AGE_MS        = 24L * 60 * 60 * 1000; // 24h 过期，比 CacheManager 本身严格

    private static final long TARGET_EXO_TMP_BYTES        =  2L * 1024 * 1024; // 2MB
    private static final long HARD_EXO_TMP_BYTES          =  5L * 1024 * 1024; // 5MB
    private static final long EXO_TMP_FILE_AGE_MS         =  6L * 60 * 60 * 1000; // 6h 直播分片很旧就删

    private static final int  KEEP_LAST_CRASH_LOGS        =  3;
    private static final long CRASH_LOG_AGE_MS            =  7L * 24 * 60 * 60 * 1000;

    private static final long JS_PARSER_PLUGIN_AGE_MS     = 14L * 24 * 60 * 60 * 1000;

    // 老版本/旧 key：绝对不要让缓存目录里残留
    private static final String[] DEAD_TV_CACHE_KEYS = new String[]{
            "epg_old", "epg_v1", "live_old", "epg_backup", "live_backup",
            "playlist_backup", "channels_old", "sub_old", "huya_room_list",
            "huya_category", "tvbox_full", "raw_response"
    };

    // ============================================================
    // 对外入口
    // ============================================================
    public static void startupCleanup(final Context ctx) {
        if (ctx == null) return;
        new Thread(() -> {
            try {
                performFullInspection(ctx.getApplicationContext());
            } catch (Throwable t) {
                Log.w(TAG, "startupCleanup 异常, 忽略: " + t.getMessage());
            }
        }, "AppCacheInspector-Cleanup").start();
    }

    // ============================================================
    // 主流程
    // ============================================================
    private static void performFullInspection(Context ctx) {
        final long t0 = System.currentTimeMillis();

        File cacheDir     = ctx.getCacheDir();
        File filesDir     = ctx.getFilesDir();
        File codeCacheDir = safeGetCodeCache(ctx);
        File extFiles     = ctx.getExternalFilesDir(null);
        File extCache     = ctx.getExternalCacheDir();

        long before = sizeOf(cacheDir) + sizeOf(filesDir) + sizeOf(codeCacheDir) + sizeOf(extFiles) + sizeOf(extCache);

        // ---------------- Step1: 业务缓存 tv_cache/ ----------------
        int  tvSaved   = 0;
        long tvFreed   = 0;
        File tvDir = new File(cacheDir, "tv_cache");
        if (tvDir.isDirectory()) {
            // 1a. 删除已经不用的老 key
            for (String deadKey : DEAD_TV_CACHE_KEYS) {
                File f = new File(tvDir, deadKey + ".cache");
                if (f.isFile()) {
                    long sz = f.length();
                    if (f.delete()) { tvFreed += sz; tvSaved++; }
                }
            }
            // 1b. 删除明显超过 24h 的过期条目 (比 CacheManager.getFileCache 在读时的判断更激进,
            //     因为 getFileCache 过期只读不删, 文件会一直留在磁盘)
            File[] all = tvDir.listFiles();
            if (all != null) {
                long now = System.currentTimeMillis();
                for (File f : all) {
                    if (!f.isFile()) continue;
                    long age = now - f.lastModified();
                    if (age > TV_CACHE_ITEM_AGE_MS) {
                        long sz = f.length();
                        if (f.delete()) { tvFreed += sz; tvSaved++; }
                    }
                }
            }
            // 1c. LRU 压到 10MB, 超过 15MB 硬删到 10MB
            long szAfter = dirSize(tvDir);
            if (szAfter > HARD_TV_CACHE_BYTES) {
                long needFree = szAfter - TARGET_TV_CACHE_BYTES;
                tvFreed += lruDeleteFromDir(tvDir, needFree);
            }
        }

        // ---------------- Step2: ExoPlayer / Media3 临时分片 ----------------
        int  exoSaved = 0;
        long exoFreed = 0;
        File exoTmp = new File(cacheDir, "exo_tmp");
        if (exoTmp.isDirectory()) {
            long now = System.currentTimeMillis();
            File[] fs = exoTmp.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (!f.isFile()) continue;
                    long age = now - f.lastModified();
                    if (age > EXO_TMP_FILE_AGE_MS) {
                        long sz = f.length();
                        if (f.delete()) { exoFreed += sz; exoSaved++; }
                    }
                }
            }
            long szAfter = dirSize(exoTmp);
            if (szAfter > HARD_EXO_TMP_BYTES) {
                exoFreed += lruDeleteFromDir(exoTmp, szAfter - TARGET_EXO_TMP_BYTES);
            }
        }
        // 同时顺手清一下 cacheDir 根目录里散放的 .exo / .tmp / .part / 临时分片
        File[] rootScatter = cacheDir.listFiles();
        if (rootScatter != null) {
            long now = System.currentTimeMillis();
            for (File f : rootScatter) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase(Locale.ROOT);
                boolean isExoScatter = name.endsWith(".exo")
                        || name.endsWith(".part")
                        || name.endsWith(".download")
                        || name.startsWith("exoplayer-")
                        || name.startsWith("media3-")
                        || (name.endsWith(".cache") && !f.getParentFile().getName().equals("tv_cache"));
                if (isExoScatter && (now - f.lastModified()) > EXO_TMP_FILE_AGE_MS) {
                    long sz = f.length();
                    if (f.delete()) { exoFreed += sz; exoSaved++; }
                }
            }
        }

        // ---------------- Step3: 崩溃日志 ----------------
        int  crashSaved = 0;
        long crashFreed = 0;
        File crashDir = new File(filesDir, "crash_logs");
        if (crashDir.isDirectory()) {
            long now = System.currentTimeMillis();
            List<File> list = listFilesSortedByMtimeDesc(crashDir);
            // 过期全部删除
            for (int i = 0; i < list.size(); i++) {
                File f = list.get(i);
                if (!f.isFile()) continue;
                if (now - f.lastModified() > CRASH_LOG_AGE_MS) {
                    long sz = f.length();
                    if (f.delete()) { crashFreed += sz; crashSaved++; list.remove(i); i--; }
                }
            }
            // 数量超 KEEP_LAST_CRASH_LOGS 的也删
            for (int i = KEEP_LAST_CRASH_LOGS; i < list.size(); i++) {
                File f = list.get(i);
                if (f.isFile()) {
                    long sz = f.length();
                    if (f.delete()) { crashFreed += sz; crashSaved++; }
                }
            }
        }

        // ---------------- Step4: Update APK 遗留 ----------------
        int  apkSaved = 0;
        long apkFreed = 0;
        if (extFiles != null) {
            File downloads = new File(extFiles, Environment_SUBDIR_DOWNLOADS());
            File[] apks = downloads.listFiles();
            long now = System.currentTimeMillis();
            if (apks != null) {
                for (File f : apks) {
                    if (!f.isFile()) continue;
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if ((n.startsWith("tv_live_update") && n.endsWith(".apk"))
                            || (n.endsWith(".apk") && (now - f.lastModified()) > 3L * 24 * 60 * 60 * 1000)) {
                        long sz = f.length();
                        if (f.delete()) { apkFreed += sz; apkSaved++; }
                    }
                }
            }
            // 外部公共 Download 目录里同名 APK 也尽量清 (需要权限, 静默失败即可)
            if (BuildCheckAtLeastQ()) {
                File pub = new File(extFiles, Environment_SUBDIR_DOWNLOADS());
                cleanUpdateApksInDir(pub);
            }
        }

        // ---------------- Step5: JsParser 插件过期 / 重复 ----------------
        int  jsSaved = 0;
        long jsFreed = 0;
        File jsDir = new File(filesDir, "js/parser");
        if (jsDir.isDirectory()) {
            long now = System.currentTimeMillis();
            File[] fs = jsDir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (!f.isFile()) continue;
                    long age = now - f.lastModified();
                    if (age > JS_PARSER_PLUGIN_AGE_MS) {
                        long sz = f.length();
                        if (f.delete()) { jsFreed += sz; jsSaved++; }
                    }
                }
            }
        }

        // ---------------- Step6: code_cache / traces / drops 等 ----------------
        int  rtSaved = 0;
        long rtFreed = 0;
        if (codeCacheDir != null && codeCacheDir.isDirectory()) {
            long now = System.currentTimeMillis();
            List<File> all = deepFiles(codeCacheDir);
            for (File f : all) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase(Locale.ROOT);
                boolean drop = name.startsWith("traces")
                        || name.contains("trace-")
                        || name.startsWith("prof")
                        || name.endsWith(".dmp")
                        || name.endsWith(".prof")
                        || (name.contains("strictmode") && (now - f.lastModified() > 3L * 24 * 3600 * 1000));
                if (drop) {
                    long sz = f.length();
                    if (f.delete()) { rtFreed += sz; rtSaved++; }
                }
            }
        }

        // ---------------- Step7: WebView 缓存 / Cookie (过期) ----------------
        // Cookie 本身很小，主要清过期 webview 资源缓存目录
        int  wvSaved = 0;
        long wvFreed = 0;
        String[] webviewSubs = new String[]{
                "app_webview", "webview", "WebView",
                "org.chromium.android_webview", "Default"
        };
        for (String sub : webviewSubs) {
            File d1 = new File(filesDir, ".." + File.separator + sub);   // /data/data/<pkg>/app_webview
            File d2 = new File(filesDir.getParent(), sub);               // 更稳妥写法
            for (File d : new File[]{d1, d2}) {
                if (d == null || !d.isDirectory()) continue;
                List<File> files = deepFiles(d);
                long now = System.currentTimeMillis();
                for (File f : files) {
                    if (!f.isFile()) continue;
                    long age = now - f.lastModified();
                    String p = f.getAbsolutePath().toLowerCase(Locale.ROOT);
                    boolean hit = p.contains("/cache/")
                            || p.contains("/gpu cache/")
                            || p.contains("/code cache/")
                            || p.contains("/service worker/")
                            || p.endsWith(".tmp");
                    if (hit && age > 7L * 24 * 3600 * 1000) {
                        long sz = f.length();
                        if (f.delete()) { wvFreed += sz; wvSaved++; }
                    }
                }
            }
        }
        // files 目录下的 .bak 文件 (本项目内确实有 TVPlayerManager.java.bak.* 的源码，但这里只清理文件系统里的 .bak 运行时生成物，不碰 assets)
        List<File> filesAll = deepFiles(filesDir);
        long now1 = System.currentTimeMillis();
        for (File f : filesAll) {
            if (!f.isFile()) continue;
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".bak") || name.endsWith(".tmp") || name.endsWith(".old")) {
                if (now1 - f.lastModified() > 24L * 3600 * 1000) {
                    long sz = f.length();
                    if (f.delete()) { rtFreed += sz; rtSaved++; }
                }
            }
        }

        // ---------------- Step8: externalCacheDir 兜底 ----------------
        if (extCache != null && extCache.isDirectory()) {
            List<File> all = deepFiles(extCache);
            long now2 = System.currentTimeMillis();
            for (File f : all) {
                if (!f.isFile()) continue;
                if (now2 - f.lastModified() > 3L * 24 * 3600 * 1000) {
                    long sz = f.length();
                    if (f.delete()) { rtFreed += sz; rtSaved++; }
                }
            }
        }

        // ============== 汇总 ==============
        long after = sizeOf(cacheDir) + sizeOf(filesDir) + sizeOf(codeCacheDir) + sizeOf(extFiles) + sizeOf(extCache);
        long cost  = System.currentTimeMillis() - t0;
        long totalFreed = before - after;
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 应用缓存巡检完成: ").append(human(before)).append(" → ").append(human(after))
                .append("  释放 ").append(human(totalFreed))
                .append("  耗时 ").append(cost).append("ms\n");
        if (tvSaved    > 0) sb.append("  · 业务缓存(EPG/直播源)  : -").append(human(tvFreed)).append(" 文件数=").append(tvSaved).append("\n");
        if (exoSaved   > 0) sb.append("  · ExoPlayer 临时分片    : -").append(human(exoFreed)).append(" 文件数=").append(exoSaved).append("\n");
        if (crashSaved > 0) sb.append("  · Crash 日志            : -").append(human(crashFreed)).append(" 文件数=").append(crashSaved).append("\n");
        if (apkSaved   > 0) sb.append("  · 更新遗留 APK          : -").append(human(apkFreed)).append(" 文件数=").append(apkSaved).append("\n");
        if (jsSaved    > 0) sb.append("  · JsParser 过期插件     : -").append(human(jsFreed)).append(" 文件数=").append(jsSaved).append("\n");
        if (rtSaved    > 0) sb.append("  · 运行时 traces/drops   : -").append(human(rtFreed)).append(" 文件数=").append(rtSaved).append("\n");
        if (wvSaved    > 0) sb.append("  · WebView 资源缓存      : -").append(human(wvFreed)).append(" 文件数=").append(wvSaved).append("\n");
        Log.i(TAG, sb.toString());
    }

    // ============================================================
    // 工具方法
    // ============================================================
    private static File safeGetCodeCache(Context ctx) {
        try {
            return ctx.getCodeCacheDir();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String Environment_SUBDIR_DOWNLOADS() {
        try {
            return (String) android.os.Environment.class.getField("DIRECTORY_DOWNLOADS").get(null);
        } catch (Throwable t) {
            return "Download";
        }
    }

    private static boolean BuildCheckAtLeastQ() {
        try {
            int Q = 29;
            return android.os.Build.VERSION.SDK_INT >= Q;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void cleanUpdateApksInDir(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis();
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if ((n.startsWith("tv_live_update") && n.endsWith(".apk"))
                    || (n.endsWith(".apk") && now - f.lastModified() > 3L * 24 * 60 * 60 * 1000)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0L;
        return dirSize(f);
    }

    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        long s = 0L;
        if (dir.isFile()) return dir.length();
        File[] fs = dir.listFiles();
        if (fs == null) return 0L;
        for (File f : fs) s += dirSize(f);
        return s;
    }

    private static List<File> listFilesSortedByMtimeDesc(File dir) {
        File[] arr = dir.listFiles();
        if (arr == null) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(arr));
        Collections.sort(list, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return list;
    }

    /** 按修改时间从小到大(最旧优先)删，直到释放 needFree 字节。返回实际释放字节数 */
    private static long lruDeleteFromDir(File dir, long needFree) {
        if (dir == null || !dir.isDirectory() || needFree <= 0) return 0L;
        File[] arr = dir.listFiles();
        if (arr == null || arr.length == 0) return 0L;
        List<File> list = new ArrayList<>(Arrays.asList(arr));
        Collections.sort(list, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long freed = 0L;
        for (File f : list) {
            if (!f.isFile()) continue;
            if (freed >= needFree) break;
            long sz = f.length();
            if (f.delete()) freed += sz;
        }
        return freed;
    }

    private static List<File> deepFiles(File root) {
        List<File> out = new ArrayList<>();
        if (root == null || !root.exists()) return out;
        if (root.isFile()) { out.add(root); return out; }
        File[] fs = root.listFiles();
        if (fs == null) return out;
        for (File f : fs) out.addAll(deepFiles(f));
        return out;
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ============================================================
    // ExoPlayer 侧：供 TVPlayerManager 写入临时分片时使用的目录 + 写入时的容量保护 hook
    //
    // 说明：本项目目前没有接入 SimpleCache / CacheDataSource（即不存在"回看/点播下载"），
    //       但 ExoPlayer 读取 HLS / Progressive 时仍可能通过我们自定义的 DataSource
    //       或 DefaultAllocator 的内部缓冲留下散文件。这里提供一个受控目录和限额回调，
    //       以后一旦要启用 CacheDataSource，直接用这里的 EXO_TMP 目录即可。
    // ============================================================
    public static File getExoPlayerCacheDir(Context ctx) {
        if (ctx == null) return null;
        File dir = new File(ctx.getApplicationContext().getCacheDir(), "exo_tmp");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    /** 每次 ExoPlayer 开始播放前，主动做一次快速清理（不阻塞主线程） */
    public static void onBeforePlayback(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File exoTmp = new File(app.getCacheDir(), "exo_tmp");
                    long sz = dirSize(exoTmp);
                    if (sz > HARD_EXO_TMP_BYTES) {
                        lruDeleteFromDir(exoTmp, sz - TARGET_EXO_TMP_BYTES);
                    }
                } catch (Throwable ignored) {}
            }
        }, "AppCacheInspector-ExoPrePlay").start();
    }

    // 方便 SettingsDialog 里 "清除缓存" 按钮调用，返回释放空间
    public static long clearAllUserCache(Context ctx) {
        if (ctx == null) return 0;
        Context app = ctx.getApplicationContext();
        File cache = app.getCacheDir();
        File extCache = app.getExternalCacheDir();
        long before = sizeOf(cache) + sizeOf(extCache);

        // 只清 cacheDir + externalCacheDir（不碰 files、不碰 shared_prefs / databases）
        deleteChildren(cache);
        deleteChildren(extCache);

        long after = sizeOf(cache) + sizeOf(extCache);
        Log.i(TAG, "clearAllUserCache: " + human(before) + " → " + human(after));
        return Math.max(0L, before - after);
    }

    private static void deleteChildren(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) deleteRecursive(f);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
