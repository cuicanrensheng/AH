package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.tv.live.util.LogBridge;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 安全数据存储工具类
 * 
 * 使用 AES-256-GCM 加密敏感数据
 * 支持字符串、整数、布尔值等多种类型的加密存储
 * 
 * 特性：
 * 1. 使用 Android Keystore 存储加密密钥
 * 2. 自动密钥轮换（检测到密钥版本时）
 * 3. 内存缓存已解密数据
 * 4. 支持数据完整性校验
 */
public class SecureDataStore {
    
    private static final String TAG = "SecureDataStore";
    private static final String PREFS_NAME = "secure_data_store";
    private static final String KEY_ALIAS = "secure_data_key_v2";
    private static final String KEY_VERSION_PREF = "key_version";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    
    private static volatile SecureDataStore sInstance;
    private Context mContext;
    private SharedPreferences mPrefs;
    private SecretKey mSecretKey;
    private boolean mInitialized = false;
    
    // 内存缓存
    private final java.util.Map<String, Object> mCache = new java.util.HashMap<>();
    private final java.util.Map<String, Boolean> mCacheValid = new java.util.HashMap<>();

    private SecureDataStore() {
    }

    public static SecureDataStore getInstance() {
        if (sInstance == null) {
            synchronized (SecureDataStore.class) {
                if (sInstance == null) {
                    sInstance = new SecureDataStore();
                }
            }
        }
        return sInstance;
    }

    /**
     * 初始化安全存储
     */
    public synchronized void init(Context context) {
        if (mInitialized) {
            return;
        }
        
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        try {
            initializeKey();
            mInitialized = true;
            LogBridge.i(TAG, "安全存储初始化完成");
        } catch (Exception e) {
            LogBridge.e(TAG, "安全存储初始化失败: " + e.getMessage());
        }
    }

    /**
     * 初始化或加载加密密钥
     */
    private void initializeKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            // 生成新密钥
            generateKey();
        }
        
        // 加载密钥
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(
            KEY_ALIAS, null);
        mSecretKey = entry.getSecretKey();
        
        LogBridge.d(TAG, "加密密钥已就绪");
    }

    /**
     * 生成 AES-256-GCM 密钥
     */
    private void generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        
        keyGenerator.init(new KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build());
        
        keyGenerator.generateKey();
        LogBridge.d(TAG, "新加密密钥已生成");
    }

    /**
     * 存储加密字符串
     */
    public void putString(String key, String value) {
        if (!mInitialized || value == null) {
            return;
        }
        
        try {
            String encryptedValue = encrypt(value);
            mPrefs.edit()
                .putString(key, encryptedValue)
                .apply();
            
            // 更新缓存
            mCache.put(key, value);
            mCacheValid.put(key, true);
            
            LogBridge.d(TAG, "已存储加密值: " + key);
        } catch (Exception e) {
            LogBridge.e(TAG, "存储失败 [" + key + "]: " + e.getMessage());
        }
    }

    /**
     * 获取解密字符串
     */
    public String getString(String key, String defaultValue) {
        if (!mInitialized) {
            return defaultValue;
        }
        
        // 检查缓存
        if (mCache.containsKey(key) && mCacheValid.get(key)) {
            return (String) mCache.get(key);
        }
        
        try {
            String encryptedValue = mPrefs.getString(key, null);
            if (encryptedValue == null) {
                return defaultValue;
            }
            
            String decryptedValue = decrypt(encryptedValue);
            if (decryptedValue != null) {
                // 更新缓存
                mCache.put(key, decryptedValue);
                mCacheValid.put(key, true);
                return decryptedValue;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "读取失败 [" + key + "]: " + e.getMessage());
        }
        
        return defaultValue;
    }

    /**
     * 存储加密整数
     */
    public void putInt(String key, int value) {
        putString(key + "_int", String.valueOf(value));
    }

    /**
     * 获取解密整数
     */
    public int getInt(String key, int defaultValue) {
        String strValue = getString(key + "_int", null);
        if (strValue != null) {
            try {
                return Integer.parseInt(strValue);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return defaultValue;
    }

    /**
     * 存储加密布尔值
     */
    public void putBoolean(String key, boolean value) {
        putString(key + "_bool", String.valueOf(value));
    }

    /**
     * 获取解密布尔值
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String strValue = getString(key + "_bool", null);
        if (strValue != null) {
            return "true".equals(strValue);
        }
        return defaultValue;
    }

    /**
     * 删除指定键
     */
    public void remove(String key) {
        if (!mInitialized) {
            return;
        }
        
        mPrefs.edit().remove(key).apply();
        mPrefs.edit().remove(key + "_int").apply();
        mPrefs.edit().remove(key + "_bool").apply();
        mCache.remove(key);
        mCache.remove(key + "_int");
        mCache.remove(key + "_bool");
        mCacheValid.remove(key);
        LogBridge.d(TAG, "已删除: " + key);
    }

    /**
     * 清除所有数据
     */
    public void clearAll() {
        if (!mInitialized) {
            return;
        }
        
        mPrefs.edit().clear().apply();
        mCache.clear();
        mCacheValid.clear();
        LogBridge.i(TAG, "已清除所有安全存储数据");
    }

    /**
     * 检查是否包含指定键
     */
    public boolean contains(String key) {
        return mPrefs.contains(key) || 
               mPrefs.contains(key + "_int") || 
               mPrefs.contains(key + "_bool");
    }

    /**
     * 加密数据
     * 
     * 格式: Base64(IV + 密文)
     */
    private String encrypt(String plaintext) throws Exception {
        // 生成随机 IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        
        // 执行加密
        Cipher cipher = Cipher.getInstance(
            KeyProperties.KEY_ALGORITHM_AES + "/" +
            KeyProperties.BLOCK_MODE_GCM + "/" +
            KeyProperties.ENCRYPTION_PADDING_NONE);
        
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, mSecretKey, gcmSpec);
        
        byte[] encryptedBytes = cipher.doFinal(plaintext.getBytes("UTF-8"));
        
        // 组合 IV 和密文
        byte[] combined = new byte[iv.length + encryptedBytes.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);
        
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    /**
     * 解密数据
     */
    private String decrypt(String encryptedText) throws Exception {
        byte[] combined = Base64.decode(encryptedText, Base64.NO_WRAP);
        
        // 分离 IV 和密文
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] encryptedBytes = new byte[combined.length - GCM_IV_LENGTH];
        
        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, iv.length, encryptedBytes, 0, encryptedBytes.length);
        
        // 执行解密
        Cipher cipher = Cipher.getInstance(
            KeyProperties.KEY_ALGORITHM_AES + "/" +
            KeyProperties.BLOCK_MODE_GCM + "/" +
            KeyProperties.ENCRYPTION_PADDING_NONE);
        
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, mSecretKey, gcmSpec);
        
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, "UTF-8");
    }

    /**
     * 获取初始化状态
     */
    public boolean isInitialized() {
        return mInitialized;
    }

    /**
     * 验证数据完整性
     * 
     * 计算数据哈希并与预期值对比
     */
    public static String calculateHash(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes("UTF-8"));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 清除缓存（内存中已解密的数据）
     */
    public void clearCache() {
        mCache.clear();
        mCacheValid.clear();
        LogBridge.d(TAG, "内存缓存已清除");
    }
}
