package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

/**
 * ✅ 缓存管理工具类
 *
 * 【功能】
 * 1. 文件缓存：缓存直播源、EPG等大文本数据
 * 2. SP缓存：缓存上次播放地址、设置等小数据
 *
 * 【缓存策略】
 * 1. 先读缓存，快速显示
 * 2. 后台刷新最新数据
 * 3. 文件缓存24小时过期，SP缓存永久有效
 *
 * 【使用场景】
 * - 进入APP时，先读缓存秒开，后台再刷新
 * - 网络不好时，至少能显示缓存的数据
 */
public class CacheManager {
    private static final String CACHE_DIR = "tv_cache";
    private static final long CACHE_VALID_TIME = 24 * 60 * 60 * 1000; // 缓存有效期：24小时
    private static final String SP_NAME = "tv_cache_sp";
    private static final String KEY_LAST_PLAY_URL = "last_play_url";
    private static final String KEY_LAST_PLAY_NAME = "last_play_name";
    private static final String KEY_LAST_PLAY_INDEX = "last_play_index";

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

    /**
     * 读取文件缓存
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
     * 保存文件缓存
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

    /**
     * 获取缓存文件对象
     */
    private File getCacheFile(String key) {
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return new File(cacheDir, key + ".cache");
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
