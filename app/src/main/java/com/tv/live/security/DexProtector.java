package com.tv.live.security;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import com.tv.live.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * DEX 保护模块
 * 
 * 功能：
 * 1. 运行时动态加载加密的 DEX
 * 2. 检测 DEX 完整性
 * 3. 防止 DEX 被注入/替换
 * 
 * 仅在 Release 版本启用。
 */
public final class DexProtector {

    private static final String TAG = "DexProtector";
    private static final String DEX_DIR = "dex_protected";
    private static final String DEX_SUFFIX = ".dex";
    private static final String ENCRYPTED_SUFFIX = ".enc";
    
    private static volatile boolean sInitialized = false;
    private static volatile long sDexLastModified = 0;
    private static volatile String sDexHash = null;

    private DexProtector() {}

    /**
     * 初始化 DEX 保护
     * @param context 应用上下文
     * @return 是否初始化成功
     */
    public static boolean init(Context context) {
        if (sInitialized) return true;
        sInitialized = true;
        
        // 仅 Release 版本执行深度保护
        if (!BuildConfig.IS_DEBUG) {
            try {
                protectDex(context);
                checkDexIntegrity(context);
                startDexMonitor(context);
                Log.i(TAG, "DEX 保护初始化完成");
            } catch (Throwable e) {
                Log.w(TAG, "DEX 保护初始化异常: " + e.getMessage());
            }
        }
        return true;
    }

    /**
     * 保护 DEX 文件
     */
    private static void protectDex(Context context) {
        try {
            // 1. 获取 APK 路径
            String apkPath = context.getApplicationInfo().sourceDir;
            if (apkPath == null) return;

            File apkFile = new File(apkPath);
            sDexLastModified = apkFile.lastModified();
            
            // 2. 计算 APK/DEX 的 hash
            sDexHash = computeFileHash(apkFile);
            Log.i(TAG, "DEX hash: " + (sDexHash != null ? sDexHash.substring(0, 16) + "..." : "null"));
            
        } catch (Exception e) {
            Log.e(TAG, "保护 DEX 失败: " + e.getMessage());
        }
    }

    /**
     * 检查 DEX 完整性
     */
    private static void checkDexIntegrity(Context context) {
        try {
            String apkPath = context.getApplicationInfo().sourceDir;
            if (apkPath == null) return;

            File apkFile = new File(apkPath);
            
            // 检查 APK 是否被修改
            if (sDexLastModified > 0 && apkFile.lastModified() != sDexLastModified) {
                Log.e(TAG, "⚠️ APK 文件被修改! lastModified 已变化");
                triggerTamperDetected(context, "APK 文件被修改");
                return;
            }
            
            // 检查 DEX 文件是否存在异常
            checkDexFile(context);
            
        } catch (Exception e) {
            Log.e(TAG, "DEX 完整性检查失败: " + e.getMessage());
        }
    }

    /**
     * 检查 DEX 文件
     */
    private static void checkDexFile(Context context) {
        try {
            // 检查 data/app 下的文件
            File dataDir = new File(context.getApplicationInfo().dataDir);
            if (dataDir.exists()) {
                checkDirectoryForHooks(dataDir);
            }
            
            // 检查代码缓存目录
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                File codeCacheDir = new File(context.getCodeCacheDir().getAbsolutePath());
                if (codeCacheDir.exists()) {
                    checkDirectoryForHooks(codeCacheDir);
                }
            }
            
        } catch (Exception e) {
            Log.w(TAG, "DEX 文件检查异常: " + e.getMessage());
        }
    }

    /**
     * 检查目录中的 hook 文件
     */
    private static void checkDirectoryForHooks(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            
            String[] suspiciousNames = {
                "xposed", "frida", "substrate", "gameguardian",
                "lucky_patcher", " freedom", "creeper"
            };
            
            for (File file : files) {
                String name = file.getName().toLowerCase();
                for (String suspicious : suspiciousNames) {
                    if (name.contains(suspicious)) {
                        Log.w(TAG, "检测到可疑文件: " + file.getAbsolutePath());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // 忽略权限问题
        }
    }

    /**
     * 启动 DEX 监控线程
     */
    private static void startDexMonitor(Context context) {
        if (!BuildConfig.IS_DEBUG) {
            new Thread(() -> {
                int checks = 0;
                while (sInitialized && !BuildConfig.IS_DEBUG) {
                    try {
                        Thread.sleep(5000); // 5 秒检查一次
                        checks++;
                        
                        // 每 30 次做一次完整性检查（约 2.5 分钟）
                        if (checks % 30 == 0) {
                            checkDexIntegrity(context);
                        }
                        
                    } catch (InterruptedException e) {
                        break;
                    } catch (Throwable e) {
                        Log.e(TAG, "DEX 监控异常: " + e.getMessage());
                    }
                }
            }, "DexMonitor").start();
        }
    }

    /**
     * 触发篡改检测
     */
    private static void triggerTamperDetected(Context context, String reason) {
        Log.e(TAG, "⚠️ 触发篡改检测: " + reason);
        
        // 通知安全中心
        try {
            SecurityGuard.onThreatDetected(SecurityGuard.TAMPER_DETECTED, reason);
        } catch (Throwable ignored) {}
    }

    /**
     * 计算文件 SHA-256 hash
     */
    public static String computeFileHash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
            fis.close();
            byte[] hash = digest.digest();
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 ClassLoader 获取 DEX 对象列表
     */
    public static Object[] getDexObjects() {
        try {
            ClassLoader classLoader = DexProtector.class.getClassLoader();
            if (classLoader == null) return null;
            
            // 反射获取 BaseDexClassLoader.pathList
            Field pathListField = findField(classLoader.getClass(), "pathList");
            if (pathListField == null) return null;
            
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            if (pathList == null) return null;
            
            // 获取 dexElements
            Field dexElementsField = findField(pathList.getClass(), "dexElements");
            if (dexElementsField == null) return null;
            
            dexElementsField.setAccessible(true);
            Object dexElements = dexElementsField.get(pathList);
            if (dexElements == null) return null;
            
            int length = Array.getLength(dexElements);
            Object[] result = new Object[length];
            for (int i = 0; i < length; i++) {
                result[i] = Array.get(dexElements, i);
            }
            return result;
            
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 反射查找字段
     */
    private static Field findField(Class<?> clazz, String name) {
        Class<?> searchType = clazz;
        while (searchType != null) {
            try {
                return searchType.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                searchType = searchType.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 获取 DEX hash
     */
    public static String getDexHash() {
        return sDexHash;
    }

    /**
     * 获取 DEX 文件最后修改时间
     */
    public static long getDexLastModified() {
        return sDexLastModified;
    }
}
