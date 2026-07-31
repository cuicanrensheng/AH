package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * ✅ 缓存管理工具类（内存优化版）
 *
 * 【2026-06-21 内存优化】
 * 【优化原因】
 * 原来的 saveFileCache(String) 会调用 content.getBytes()，
 * 瞬间分配一个和字符串一样大的 byte 数组，大文件时容易 OOM。
 *
 * 【优化方案】
 * 1. 新增 saveFileCache(String, InputStream) 流式保存方法
 * 2. 新增 getFileCacheStream(String) 流式读取方法
 * 3. 大文件用流式方法，内存占用只有几 KB
 * 4. 原来的 String 版本保留，兼容小文件和旧代码
 *
 * 【功能】
 * 1. 文件缓存：缓存直播源、EPG等大文本数据
 * 2. SP缓存：缓存上次播放地址、设置等小数据
 *
 * 【缓存策略】
 * 1. 先读缓存，快速显示
 * 2. 后台刷新最新数据
 * 3. 文件缓存24小时过期，SP缓存永久有效
 */
public class CacheManager {

    private static final String CACHE_DIR = "tv_cache";
    private static final long CACHE_VALID_TIME = 24 * 60 * 60 * 1000; // 缓存有效期：24小时
    private static final String SP_NAME = "tv_cache_sp";
    private static final String KEY_LAST_PLAY_URL = "last_play_url";
    private static final String KEY_LAST_PLAY_NAME = "last_play_name";
    private static final String KEY_LAST_PLAY_INDEX = "last_play_index";

    // 流式读写的缓冲区大小
    private static final int BUFFER_SIZE = 8192; // 8KB

    private Context context;
    private SharedPreferences sp;
    private static CacheManager instance;

    /**
     * 获取单例
     */
    public static CacheManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new CacheManager(ctx);
        }
        return instance;
    }

    private CacheManager(Context ctx) {
        context = ctx.getApplicationContext();
        sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    // ================================================
    // 文件缓存（用于直播源、EPG等大文本）
    // ================================================

    // ------------------------------------------------
    // ✅ 新增：流式保存（推荐大文件使用，内存占用极小）
    // ------------------------------------------------

    /**
     * ✅ 流式保存文件缓存
     *
     * 【推荐场景】
     * 大文件（EPG、直播源等），用这个方法不会 OOM。
     *
     * 【内存对比】
     * 原来的 String 版本：峰值内存 = 文件大小 × 2（String + byte[]）
     * 这个流式版本：峰值内存 = 8KB（只有缓冲区）
     *
     * @param key 缓存键（如 "live_source"、"epg"）
     * @param is 输入流，从这个流读取数据写入缓存
     * @return 保存的字节数，失败返回 -1
     */
    public long saveFileCache(String key, InputStream is) {
        if (is == null) {
            return -1;
        }

        File cacheFile = getCacheFile(key);
        FileOutputStream fos = null;
        long totalBytes = 0;

        try {
            fos = new FileOutputStream(cacheFile);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            // 流式读写，边读边写，不用全部读到内存
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            fos.flush();
            return totalBytes;

        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ------------------------------------------------
    // ✅ 新增：流式读取（推荐大文件使用）
    // ------------------------------------------------

    /**
     * ✅ 获取缓存文件的输入流（流式读取）
     *
     * 【推荐场景】
     * 大文件（EPG等），用这个方法不会 OOM。
     * 配合 XmlPullParser 等流式解析器使用效果最佳。
     *
     * @param key 缓存键
     * @return 缓存文件的输入流，过期或不存在返回 null
     */
    public InputStream getFileCacheStream(String key) {
        File cacheFile = getCacheFile(key);
        if (!cacheFile.exists()) {
            return null;
        }

        // 检查是否过期
        long age = System.currentTimeMillis() - cacheFile.lastModified();
        if (age > CACHE_VALID_TIME) {
            return null; // 过期了
        }

        try {
            return new FileInputStream(cacheFile);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    // ------------------------------------------------
    // 原来的 String 版本（保留，兼容小文件和旧代码）
    // ------------------------------------------------

    /**
     * 读取文件缓存（String 版本）
     *
     * 【注意】
     * 大文件请用 getFileCacheStream()，避免 OOM。
     * 这个方法适合小文件（几十 KB 以内）。
     *
     * @param key 缓存键（如 "live_source"、"epg"）
     * @return 缓存内容，过期或不存在返回null
     */
    public String getFileCache(String key) {
        File cacheFile = getCacheFile(key);
        if (!cacheFile.exists()) {
            return null;
        }

        // 检查是否过期
        long age = System.currentTimeMillis() - cacheFile.lastModified();
        if (age > CACHE_VALID_TIME) {
            return null; // 过期了
        }

        // 读取文件内容
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(cacheFile));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 保存文件缓存（String 版本）
     *
     * 【注意】
     * 大文件请用 saveFileCache(String, InputStream)，避免 OOM。
     * 这个方法适合小文件（几十 KB 以内）。
     *
     * @param key 缓存键
     * @param content 缓存内容
     */
    public void saveFileCache(String key, String content) {
        if (TextUtils.isEmpty(content)) {
            return;
        }

        File cacheFile = getCacheFile(key);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(cacheFile);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ------------------------------------------------
    // 新增：获取缓存文件对象（供外部直接操作文件）
    // ------------------------------------------------

    /**
     * 获取缓存文件对象
     *
     * 【说明】
     * 供外部直接操作文件使用，比如用 FileInputStream 读取。
     * 注意：调用者需要自己判断文件是否存在、是否过期。
     *
     * @param key 缓存键
     * @return 缓存文件对象
     */
    public File getCacheFile(String key) {
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return new File(cacheDir, key + ".cache");
    }

    // ------------------------------------------------
    // 新增：检查缓存是否有效
    // ------------------------------------------------

    /**
     * 检查缓存是否有效（存在且未过期）
     *
     * @param key 缓存键
     * @return true=有效，false=不存在或已过期
     */
    public boolean isCacheValid(String key) {
        File cacheFile = getCacheFile(key);
        if (!cacheFile.exists()) {
            return false;
        }
        long age = System.currentTimeMillis() - cacheFile.lastModified();
        return age <= CACHE_VALID_TIME;
    }

    // ================================================
    // SP缓存（用于上次播放地址等小数据）
    // ================================================

    /**
     * 保存上次播放的频道信息
     */
    public void saveLastPlay(String url, String name, int index) {
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(KEY_LAST_PLAY_URL, url);
        editor.putString(KEY_LAST_PLAY_NAME, name);
        editor.putInt(KEY_LAST_PLAY_INDEX, index);
        editor.apply();
    }

    /**
     * 获取上次播放的地址
     */
    public String getLastPlayUrl() {
        return sp.getString(KEY_LAST_PLAY_URL, "");
    }

    /**
     * 获取上次播放的频道名称
     */
    public String getLastPlayName() {
        return sp.getString(KEY_LAST_PLAY_NAME, "");
    }

    /**
     * 获取上次播放的索引
     */
    public int getLastPlayIndex() {
        return sp.getInt(KEY_LAST_PLAY_INDEX, 0);
    }

    // ================================================
    // 清除缓存
    // ================================================

    /**
     * 清除所有文件缓存
     */
    public void clearAllFileCache() {
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }
    }

    /**
     * 清除所有缓存（包括SP）
     */
    public void clearAll() {
        clearAllFileCache();
        sp.edit().clear().apply();
    }
}
