package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 加密存储工具类
 * 使用 Android Keystore 系统保护敏感数据
 * 
 * 安全特性：
 * 1. AES-256-GCM 加密算法（带完整性校验）
 * 2. 密钥存储在 Android Keystore 中，无法被导出
 * 3. 支持设备重启后自动恢复密钥
 * 4. 密钥失效检测和自动重新生成
 */
public class EncryptedStorage {

    private static final String TAG = "EncryptedStorage";
    
    private static final String PREFS_NAME = "encrypted_prefs";
    private static final String KEY_ALIAS = "tv_live_encryption_key";
    
    private static final String KEY_SALT = "key_salt";
    private static final String KEY_Version = "key_version";
    
    private static final int GCM_TAG_LENGTH = 128; // 16 bytes tag
    private static final int GCM_IV_LENGTH = 12;    // 12 bytes IV (recommended for GCM)
    
    private Context context;
    private SharedPreferences prefs;
    private SecretKey secretKey;
    private boolean initialized = false;

    private static volatile EncryptedStorage instance;

    public static EncryptedStorage getInstance(Context context) {
        if (instance == null) {
            synchronized (EncryptedStorage.class) {
                if (instance == null) {
                    instance = new EncryptedStorage(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private EncryptedStorage(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initialize();
    }

    /**
     * 初始化加密存储
     */
    private void initialize() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    generateOrLoadKey();
                } catch (Exception keystoreError) {
                    Log.w(TAG, "Android Keystore 不可用，降级到旧版本方案: " + keystoreError.getMessage());
                    generateLegacyKey();
                }
            } else {
                // 低版本设备降级方案：使用基于设备信息的派生密钥
                generateLegacyKey();
            }
            initialized = true;
            Log.i(TAG, "✅ EncryptedStorage 初始化成功");
        } catch (Exception e) {
            Log.e(TAG, "❌ EncryptedStorage 初始化失败: " + e.getMessage());
        }
    }

    /**
     * 生成或加载加密密钥（Android 6.0+）
     */
    private void generateOrLoadKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            // 生成新的 AES-256 密钥
            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");
            
            keyGenerator.init(
                    new KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setUserAuthenticationRequired(false)
                            .build());
            
            keyGenerator.generateKey();
            Log.i(TAG, "✅ 生成新的 AES-256 密钥");
        }

        // 从 Keystore 加载密钥
        KeyStore.SecretKeyEntry keyEntry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
        secretKey = keyEntry.getSecretKey();
        Log.i(TAG, "✅ 加载 AES-256 密钥成功");
    }

    /**
     * 旧版本设备降级方案（Android 6.0 以下）
     * 注意：此方案安全性较低，仅作为兼容方案
     */
    private void generateLegacyKey() throws Exception {
        String existingSalt = prefs.getString(KEY_SALT, null);
        byte[] salt;
        
        if (existingSalt == null) {
            salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            prefs.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).apply();
        } else {
            salt = Base64.decode(existingSalt, Base64.NO_WRAP);
        }

        // 使用设备 ID + 应用签名哈希派生密钥
        String deviceKey = getDeviceKeyMaterial();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        byte[] keyBytes = digest.digest(deviceKey.getBytes("UTF-8"));
        
        secretKey = new SecretKeySpec(keyBytes, "AES");
        Log.i(TAG, "✅ 使用降级方案生成密钥（安全性较低）");
    }

    /**
     * 获取设备密钥材料（用于旧版本设备的密钥派生）
     */
    private String getDeviceKeyMaterial() {
        StringBuilder sb = new StringBuilder();
        
        // 使用多个设备属性组合生成稳定的密钥材料
        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), 
                    Settings.Secure.ANDROID_ID);
            sb.append(androidId != null ? androidId : "unknown");
        } catch (Exception e) {
            sb.append("unknown");
        }
        sb.append(android.os.Build.MODEL);
        sb.append(android.os.Build.BRAND);
        sb.append(context.getPackageName());
        
        return sb.toString();
    }

    /**
     * 加密字符串
     * @param plainText 要加密的明文
     * @return Base64 编码的密文（格式：Base64(IV) + ":" + Base64(Ciphertext+Tag)）
     */
    public String encrypt(String key, String plainText) {
        if (!initialized || secretKey == null) {
            Log.e(TAG, "加密存储未初始化");
            return plainText;
        }

        try {
            // 生成随机 IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // AES-256-GCM 加密
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
            
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes("UTF-8"));

            // 格式: Base64(IV):Base64(Ciphertext+Tag)
            String encrypted = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + 
                             Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);
            
            // 存储加密后的值
            prefs.edit().putString(key, encrypted).apply();
            
            return encrypted;
        } catch (Exception e) {
            Log.e(TAG, "加密失败: " + e.getMessage());
            return plainText;
        }
    }

    /**
     * 解密字符串
     * @param key 存储键
     * @return 解密后的明文
     */
    public String decrypt(String key) {
        if (!initialized || secretKey == null) {
            Log.e(TAG, "加密存储未初始化");
            return null;
        }

        String encrypted = prefs.getString(key, null);
        if (encrypted == null) {
            return null;
        }

        try {
            // 解析格式: Base64(IV):Base64(Ciphertext+Tag)
            String[] parts = encrypted.split(":");
            if (parts.length != 2) {
                Log.e(TAG, "密文格式错误");
                return null;
            }

            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP);

            // AES-256-GCM 解密
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);
            
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "解密失败（可能密钥已失效）: " + e.getMessage());
            // 密钥失效时清除数据
            prefs.edit().remove(key).apply();
            return null;
        }
    }

    /**
     * 存储明文字符串（首次存储时加密）
     * @param key 存储键
     * @param value 要存储的值
     */
    public void putString(String key, String value) {
        encrypt(key, value);
    }

    /**
     * 读取加密存储的字符串
     * @param key 存储键
     * @param defaultValue 默认值
     * @return 解密后的值
     */
    public String getString(String key, String defaultValue) {
        String result = decrypt(key);
        return result != null ? result : defaultValue;
    }

    /**
     * 存储整数
     */
    public void putInt(String key, int value) {
        encrypt(key, String.valueOf(value));
    }

    /**
     * 读取整数
     */
    public int getInt(String key, int defaultValue) {
        String result = decrypt(key);
        if (result == null) return defaultValue;
        try {
            return Integer.parseInt(result);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 移除存储的键
     */
    public void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    /**
     * 检查是否包含指定的键
     */
    public boolean contains(String key) {
        return prefs.contains(key);
    }

    /**
     * 清除所有加密存储的数据
     */
    public void clearAll() {
        prefs.edit().clear().apply();
        Log.i(TAG, "已清除所有加密存储数据");
    }

    /**
     * 检查初始化状态
     */
    public boolean isInitialized() {
        return initialized && secretKey != null;
    }

    /**
     * 检查密钥是否仍然有效（防止密钥失效）
     */
    public boolean isKeyValid() {
        if (!initialized || secretKey == null) return false;
        
        try {
            // 尝试加密/解密测试
            String testData = "key_validation_test";
            String encrypted = encrypt("__key_validation__", testData);
            String decrypted = decrypt("__key_validation__");
            prefs.edit().remove("__key_validation__").apply();
            
            return testData.equals(decrypted);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 重新生成密钥（清除所有现有数据）
     * 仅在密钥失效检测到后使用
     */
    public boolean regenerateKey() {
        try {
            clearAll();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS);
                }
                generateOrLoadKey();
            } else {
                // 清除旧 salt 以触发新 salt 生成
                prefs.edit().remove(KEY_SALT).apply();
                generateLegacyKey();
            }
            
            Log.i(TAG, "✅ 密钥已重新生成");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ 密钥重新生成失败: " + e.getMessage());
            return false;
        }
    }
}
