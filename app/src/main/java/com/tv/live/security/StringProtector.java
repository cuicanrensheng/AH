package com.tv.live.security;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * 字符串动态解密保护
 * 
 * 功能：
 * 1. 敏感字符串运行时解密，不暴露明文
 * 2. 防止静态提取字符串
 * 3. 支持多级加密
 * 
 * 使用方式：
 *   // 注册加密字符串
 *   StringProtector.register("api_key", "加密后的Base64字符串");
 *   
 *   // 运行时获取明文
 *   String apiKey = StringProtector.get("api_key");
 * 
 * 仅在 Release 版本启用。
 */
public final class StringProtector {

    private static final String TAG = "StringProtector";
    private static volatile boolean sEnabled = false;
    
    // 加密字符串缓存
    private static final Map<String, String> sEncryptedCache = new HashMap<>();
    // 已解密字符串缓存（避免重复解密）
    private static final Map<String, String> sDecryptedCache = new HashMap<>();
    
    // 动态密钥（运行时生成）
    private static volatile byte[] sDynamicKey = null;
    
    // 加密方式
    private static final int XOR_KEY_LENGTH = 16;

    private StringProtector() {}

    /**
     * 初始化字符串保护
     */
    public static void init(boolean enable) {
        sEnabled = enable;
        if (enable) {
            // 生成动态密钥
            sDynamicKey = generateDynamicKey();
            Log.i(TAG, "字符串保护已启用");
        } else {
            Log.i(TAG, "字符串保护已禁用（调试模式）");
        }
    }

    /**
     * 注册加密字符串
     * @param key 标识符
     * @param encryptedValue Base64 编码的加密值
     */
    public static void register(String key, String encryptedValue) {
        if (key == null || encryptedValue == null) return;
        sEncryptedCache.put(key, encryptedValue);
        // 清除对应的解密缓存
        sDecryptedCache.remove(key);
    }

    /**
     * 注册字符串（可选加密）
     * @param key 标识符
     * @param value 原始值
     * @param encrypt 是否加密
     */
    public static void register(String key, String value, boolean encrypt) {
        if (key == null || value == null) return;
        
        if (encrypt && sEnabled) {
            String encrypted = encryptValue(value);
            sEncryptedCache.put(key, encrypted);
            sDecryptedCache.remove(key);
        } else {
            // 调试模式直接存储明文
            sDecryptedCache.put(key, value);
        }
    }

    /**
     * 获取字符串值
     * @param key 标识符
     * @return 明文字符串
     */
    public static String get(String key) {
        if (key == null) return null;
        
        // 先检查解密缓存
        String cached = sDecryptedCache.get(key);
        if (cached != null) return cached;
        
        // 获取加密值并解密
        String encrypted = sEncryptedCache.get(key);
        if (encrypted != null) {
            try {
                String decrypted = decryptValue(encrypted);
                // 缓存解密结果
                if (decrypted != null) {
                    sDecryptedCache.put(key, decrypted);
                }
                return decrypted;
            } catch (Exception e) {
                Log.e(TAG, "解密字符串失败: " + key);
                return null;
            }
        }
        
        return null;
    }

    /**
     * 批量获取字符串
     * @param keys 键名数组
     * @return 值数组
     */
    public static String[] getBatch(String... keys) {
        if (keys == null) return null;
        String[] result = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            result[i] = get(keys[i]);
        }
        return result;
    }

    /**
     * 加密值（XOR + Base64）
     */
    private static String encryptValue(String plain) {
        if (plain == null) return null;
        try {
            byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
            byte[] encryptedBytes = xorEncrypt(plainBytes, sDynamicKey);
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "加密失败: " + e.getMessage());
            return plain; // 失败时返回原文（调试模式）
        }
    }

    /**
     * 解密值
     */
    private static String decryptValue(String encryptedBase64) {
        if (encryptedBase64 == null) return null;
        try {
            byte[] encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP);
            byte[] decryptedBytes = xorDecrypt(encryptedBytes, sDynamicKey);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "解密失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * XOR 加密
     */
    private static byte[] xorEncrypt(byte[] data, byte[] key) {
        if (data == null || key == null) return null;
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return result;
    }

    /**
     * XOR 解密
     */
    private static byte[] xorDecrypt(byte[] data, byte[] key) {
        // XOR 解密和加密相同
        return xorEncrypt(data, key);
    }

    /**
     * 生成动态密钥
     */
    private static byte[] generateDynamicKey() {
        try {
            // 基于设备信息 + 时间戳生成动态密钥
            String seed = "TVLive_" + System.currentTimeMillis() + "_" + 
                         android.os.Build.VERSION.SDK_INT;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[XOR_KEY_LENGTH];
            System.arraycopy(hash, 0, key, 0, XOR_KEY_LENGTH);
            return key;
        } catch (Exception e) {
            // Fallback: 固定密钥
            return "TVLive2026Key!".getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 清除所有缓存（用于密钥更新）
     */
    public static void clearCache() {
        sDecryptedCache.clear();
    }

    /**
     * 更新动态密钥
     */
    public static void rotateKey() {
        if (!sEnabled) return;
        sDynamicKey = generateDynamicKey();
        clearCache();
        Log.i(TAG, "动态密钥已更新");
    }

    /**
     * 检查是否启用
     */
    public static boolean isEnabled() {
        return sEnabled;
    }

    /**
     * 获取已注册的键数量
     */
    public static int getRegisteredCount() {
        return sEncryptedCache.size();
    }

    /**
     * 快速加密工具（用于编译时加密字符串）
     * 使用方法：调用此方法获取加密后的值，硬编码到代码中
     */
    public static String quickEncrypt(String plain) {
        if (plain == null) return null;
        byte[] key = "TVLive2026Key!".getBytes(StandardCharsets.UTF_8);
        byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = new byte[plainBytes.length];
        for (int i = 0; i < plainBytes.length; i++) {
            encryptedBytes[i] = (byte) (plainBytes[i] ^ key[i % key.length]);
        }
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);
    }

    /**
     * 快速解密工具（调试用）
     */
    public static String quickDecrypt(String encryptedBase64) {
        if (encryptedBase64 == null) return null;
        byte[] key = "TVLive2026Key!".getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP);
        byte[] decryptedBytes = new byte[encryptedBytes.length];
        for (int i = 0; i < encryptedBytes.length; i++) {
            decryptedBytes[i] = (byte) (encryptedBytes[i] ^ key[i % key.length]);
        }
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
}
