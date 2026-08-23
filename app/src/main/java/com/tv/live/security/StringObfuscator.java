package com.tv.live.security;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * 字符串加密工具
 * 用于在运行时解密敏感字符串，防止静态提取
 */
public class StringObfuscator {

    private static final String TAG = "StrObfuscator";
    
    // 字符串缓存（避免重复解码）
    private static final Map<String, String> stringCache = new HashMap<>();
    
    // 编码方式
    private static final int XOR_KEY = 0x3C;
    private static final int XOR_KEY_2 = 0xA5;
    
    /**
     * 使用 XOR 编码字符串
     * 编译时使用此方法编码，运行时使用 decodeString 解码
     */
    public static String encodeString(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            // 双重 XOR
            c ^= XOR_KEY;
            c ^= XOR_KEY_2;
            sb.append(c);
        }
        return Base64.encodeToString(sb.toString().getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
    }
    
    /**
     * 解码字符串
     */
    public static String decodeString(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        
        // 检查缓存
        if (stringCache.containsKey(encoded)) {
            return stringCache.get(encoded);
        }
        
        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            String str = new String(decoded, StandardCharsets.ISO_8859_1);
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                // 逆向双重 XOR
                c ^= XOR_KEY_2;
                c ^= XOR_KEY;
                sb.append(c);
            }
            
            String result = sb.toString();
            stringCache.put(encoded, result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "解码字符串失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 简单的字符替换编码
     * 比 XOR 更难被识别
     */
    public static String simpleEncode(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            // 字符替换表
            if (c >= 'a' && c <= 'z') {
                c = (char) ((c - 'a' + 13) % 26 + 'a');
            } else if (c >= 'A' && c <= 'Z') {
                c = (char) ((c - 'A' + 13) % 26 + 'A');
            } else if (c >= '0' && c <= '9') {
                c = (char) ((c - '0' + 5) % 10 + '0');
            }
            sb.append(c);
        }
        return Base64.encodeToString(sb.toString().getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
    }
    
    /**
     * 简单的字符替换解码
     */
    public static String simpleDecode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        
        if (stringCache.containsKey(encoded)) {
            return stringCache.get(encoded);
        }
        
        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            String str = new String(decoded, StandardCharsets.ISO_8859_1);
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                // 逆向字符替换
                if (c >= 'a' && c <= 'z') {
                    c = (char) ((c - 'a' - 13 + 26) % 26 + 'a');
                } else if (c >= 'A' && c <= 'Z') {
                    c = (char) ((c - 'A' - 13 + 26) % 26 + 'A');
                } else if (c >= '0' && c <= '9') {
                    c = (char) ((c - '0' - 5 + 10) % 10 + '0');
                }
                sb.append(c);
            }
            
            String result = sb.toString();
            stringCache.put(encoded, result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "解码字符串失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 生成随机盐值
     */
    public static String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.encodeToString(salt, Base64.NO_WRAP);
    }
    
    /**
     * 清除缓存
     */
    public static void clearCache() {
        stringCache.clear();
    }
    
    // 预定义的常用字符串编码（编译时生成）
    // 使用方式: String password = StringObfuscator.decodeString("BASE64编码值");
    
    /**
     * 编码整数字符串
     */
    public static String encodeInt(int value) {
        return encodeString(String.valueOf(value));
    }
    
    /**
     * 解码为整数
     */
    public static int decodeInt(String encoded) {
        String decoded = decodeString(encoded);
        if (decoded == null) return -1;
        try {
            return Integer.parseInt(decoded);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
