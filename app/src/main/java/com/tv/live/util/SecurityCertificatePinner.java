package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;

/**
 * SSL 证书安全管理器
 * 
 * 采用 TOFU (Trust On First Use) 策略：
 * 1. 首次连接时，自动获取并存储服务器证书指纹
 * 2. 后续连接验证证书是否与存储的指纹匹配
 * 3. 证书变更时发出警告（可能是服务器更新或中间人攻击）
 * 4. 支持手动更新证书指纹
 * 
 * 安全特性：
 * - 防止中间人攻击（MITM）
 * - 检测证书变更
 * - 证书持久化存储
 * - 自适应验证策略
 */
public class SecurityCertificatePinner {

    private static final String TAG = "SSLCertPinner";
    private static final String PREFS_NAME = "ssl_cert_store";
    private static final long CERT_VALIDITY_CHECK_INTERVAL = 24 * 60 * 60 * 1000L; // 24小时

    private static volatile SecurityCertificatePinner sInstance;
    private Context mContext;
    private SharedPreferences mCertStore;
    
    // 内存中的证书指纹缓存（域名 -> SHA-256指纹列表）
    private final ConcurrentHashMap<String, List<String>> mCertPins = new ConcurrentHashMap<>();
    
    // 证书变更事件监听
    private final List<CertChangeListener> mListeners = new ArrayList<>();
    
    // 验证模式
    private VerificationMode mMode = VerificationMode.LEARN;
    
    // 证书统计
    private int mTotalVerifications = 0;
    private int mFailedVerifications = 0;
    private long mLastVerificationTime = 0;

    public enum VerificationMode {
        /** 学习模式：首次连接自动信任并保存证书 */
        LEARN,
        /** 验证模式：严格验证证书，不匹配则拒绝 */
        VERIFY,
        /** 警告模式：验证失败只记录日志，不拒绝连接 */
        WARN_ONLY,
        /** 禁用模式：不进行证书验证 */
        DISABLED
    }

    private SecurityCertificatePinner() {
    }

    public static SecurityCertificatePinner getInstance() {
        if (sInstance == null) {
            synchronized (SecurityCertificatePinner.class) {
                if (sInstance == null) {
                    sInstance = new SecurityCertificatePinner();
                }
            }
        }
        return sInstance;
    }

    /**
     * 初始化证书管理器
     */
    public void init(Context context) {
        mContext = context.getApplicationContext();
        mCertStore = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadCertPinsFromStorage();
        Log.i(TAG, "SSL证书管理器初始化完成，已存储 " + mCertPins.size() + " 个域名证书");
        
        // 默认使用学习模式（首次安装时）
        if (mCertPins.isEmpty()) {
            mMode = VerificationMode.LEARN;
            Log.i(TAG, "首次启动，启用学习模式");
        } else {
            mMode = VerificationMode.WARN_ONLY;
            Log.i(TAG, "已有证书数据，启用警告模式");
        }
    }

    /**
     * 从 SharedPreferences 加载证书指纹
     */
    private void loadCertPinsFromStorage() {
        if (mCertStore == null) return;
        
        Map<String, ?> allPrefs = mCertStore.getAll();
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            // 证书指纹以 "pin:" 开头存储
            if (key.startsWith("pin:") && value instanceof String) {
                String domain = key.substring(4); // 移除 "pin:" 前缀
                String pinsStr = (String) value;
                List<String> pins = parsePinsFromString(pinsStr);
                if (!pins.isEmpty()) {
                    mCertPins.put(domain, pins);
                }
            }
        }
    }

    /**
     * 保存证书指纹到 SharedPreferences
     */
    private void saveCertPin(String domain, List<String> pins) {
        if (mCertStore == null) return;
        
        String key = "pin:" + domain;
        String value = serializePins(pins);
        mCertStore.edit().putString(key, value).apply();
        mCertPins.put(domain, pins);
        
        Log.i(TAG, "保存证书指纹: " + domain + " -> " + pins.size() + " 个pin");
    }

    /**
     * 序列化证书指纹列表
     */
    private String serializePins(List<String> pins) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pins.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(pins.get(i));
        }
        return sb.toString();
    }

    /**
     * 反序列化证书指纹列表
     */
    private List<String> parsePinsFromString(String str) {
        List<String> pins = new ArrayList<>();
        if (str == null || str.isEmpty()) return pins;
        
        String[] parts = str.split("\\|");
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                pins.add(part);
            }
        }
        return pins;
    }

    /**
     * 注册证书变更监听
     */
    public void addCertChangeListener(CertChangeListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    /**
     * 移除证书变更监听
     */
    public void removeCertChangeListener(CertChangeListener listener) {
        mListeners.remove(listener);
    }

    /**
     * 设置验证模式
     */
    public void setMode(VerificationMode mode) {
        mMode = mode;
        Log.i(TAG, "证书验证模式: " + mode.name());
    }

    /**
     * 获取当前验证模式
     */
    public VerificationMode getMode() {
        return mMode;
    }

    /**
     * 获取指定域名的证书指纹
     */
    public List<String> getCertPins(String domain) {
        List<String> pins = mCertPins.get(domain);
        return pins != null ? pins : Collections.emptyList();
    }

    /**
     * 检查是否存储了指定域名的证书
     */
    public boolean hasCertPin(String domain) {
        return mCertPins.containsKey(domain);
    }

    /**
     * 移除指定域名的证书指纹
     */
    public void removeCertPin(String domain) {
        if (mCertStore != null) {
            mCertStore.edit().remove("pin:" + domain).apply();
        }
        mCertPins.remove(domain);
        Log.i(TAG, "移除证书指纹: " + domain);
    }

    /**
     * 清除所有存储的证书指纹
     */
    public void clearAllCertPins() {
        if (mCertStore != null) {
            mCertStore.edit().clear().apply();
        }
        mCertPins.clear();
        Log.i(TAG, "清除所有证书指纹");
    }

    /**
     * 处理服务器证书
     * @param hostname 主机名
     * @param certs 证书链
     * @return 验证结果
     */
    public CertVerificationResult processServerCertificates(String hostname, X509Certificate[] certs) {
        if (mMode == VerificationMode.DISABLED) {
            return CertVerificationResult.SUCCESS;
        }
        
        mTotalVerifications++;
        mLastVerificationTime = System.currentTimeMillis();
        
        if (certs == null || certs.length == 0) {
            Log.w(TAG, "证书链为空: " + hostname);
            return CertVerificationResult.FAIL;
        }

        // 计算证书指纹
        List<String> certPins = calculateCertPins(certs);
        if (certPins.isEmpty()) {
            return CertVerificationResult.FAIL;
        }

        List<String> storedPins = mCertPins.get(hostname);
        
        if (storedPins == null || storedPins.isEmpty()) {
            // 首次连接：学习模式
            if (mMode == VerificationMode.LEARN || mMode == VerificationMode.WARN_ONLY) {
                saveCertPin(hostname, certPins);
                Log.i(TAG, "首次学习证书: " + hostname);
                notifyCertChanged(hostname, null, certPins, true);
                return CertVerificationResult.SUCCESS;
            } else if (mMode == VerificationMode.VERIFY) {
                Log.e(TAG, "证书未存储，拒绝连接: " + hostname);
                mFailedVerifications++;
                return CertVerificationResult.FAIL;
            }
        } else {
            // 验证模式：检查证书是否匹配
            boolean matches = certPins.stream().anyMatch(storedPins::contains);
            
            if (matches) {
                return CertVerificationResult.SUCCESS;
            } else {
                // 证书不匹配
                mFailedVerifications++;
                
                if (mMode == VerificationMode.LEARN) {
                    // 学习模式：自动更新证书（可能是服务器更新了证书）
                    Log.w(TAG, "证书变更，自动更新: " + hostname);
                    saveCertPin(hostname, certPins);
                    notifyCertChanged(hostname, storedPins, certPins, true);
                    return CertVerificationResult.SUCCESS;
                } else if (mMode == VerificationMode.WARN_ONLY) {
                    // 警告模式：记录日志但允许连接
                    Log.w(TAG, "证书不匹配，但允许连接: " + hostname);
                    Log.w(TAG, "  存储的证书: " + storedPins);
                    Log.w(TAG, "  当前证书: " + certPins);
                    notifyCertChanged(hostname, storedPins, certPins, false);
                    return CertVerificationResult.WARNING;
                } else if (mMode == VerificationMode.VERIFY) {
                    // 严格验证模式：拒绝连接
                    Log.e(TAG, "证书不匹配，拒绝连接: " + hostname);
                    Log.e(TAG, "  存储的证书: " + storedPins);
                    Log.e(TAG, "  当前证书: " + certPins);
                    notifyCertChanged(hostname, storedPins, certPins, false);
                    return CertVerificationResult.FAIL;
                }
            }
        }
        
        return CertVerificationResult.SUCCESS;
    }

    /**
     * 计算证书的 SHA-256 指纹
     */
    private List<String> calculateCertPins(X509Certificate[] certs) {
        List<String> pins = new ArrayList<>();
        
        try {
            for (X509Certificate cert : certs) {
                // 计算公钥的 SHA-256 指纹
                byte[] pubKeyBytes = cert.getPublicKey().getEncoded();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(pubKeyBytes);
                String pin = "sha256/" + android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
                pins.add(pin);
            }
        } catch (Exception e) {
            Log.e(TAG, "计算证书指纹失败: " + e.getMessage());
        }
        
        return pins;
    }

    /**
     * 通知证书变更
     */
    private void notifyCertChanged(String hostname, List<String> oldPins, List<String> newPins, boolean trusted) {
        for (CertChangeListener listener : mListeners) {
            try {
                listener.onCertChanged(hostname, oldPins, newPins, trusted);
            } catch (Exception e) {
                Log.e(TAG, "通知证书变更失败: " + e.getMessage());
            }
        }
    }

    /**
     * 构建 OkHttpClient 的证书配置
     */
    public OkHttpClient.Builder configureOkHttpClient(OkHttpClient.Builder builder) {
        // 添加自定义 TrustManager 用于证书处理
        builder.sslSocketFactory(
            javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory(),
            new CertAwareTrustManager()
        );
        
        // 添加自定义 HostnameVerifier
        builder.hostnameVerifier(new CertAwareHostnameVerifier());
        
        return builder;
    }

    /**
     * 证书感知的 TrustManager
     */
    private class CertAwareTrustManager implements X509TrustManager {
        private final X509TrustManager systemTm;

        CertAwareTrustManager() {
            try {
                javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((java.security.KeyStore) null);
                javax.net.ssl.TrustManager[] tms = tmf.getTrustManagers();
                systemTm = (X509TrustManager) tms[0];
            } catch (Exception e) {
                throw new RuntimeException("初始化 TrustManager 失败", e);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) 
                throws CertificateException {
            systemTm.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) 
                throws CertificateException {
            systemTm.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return systemTm.getAcceptedIssuers();
        }
    }

    /**
     * 证书感知的 HostnameVerifier
     */
    private class CertAwareHostnameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            // 首先使用系统验证
            try {
                HostnameVerifier hv = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
                if (!hv.verify(hostname, session)) {
                    Log.w(TAG, "主机名验证失败: " + hostname);
                    return false;
                }
            } catch (Exception e) {
                Log.e(TAG, "主机名验证异常: " + hostname + " -> " + e.getMessage());
                return false;
            }
            
            // 然后进行证书绑定检查
            try {
                Certificate[] peerCerts = session.getPeerCertificates();
                if (peerCerts != null && peerCerts.length > 0) {
                    X509Certificate[] certs = new X509Certificate[peerCerts.length];
                    for (int i = 0; i < peerCerts.length; i++) {
                        if (peerCerts[i] instanceof X509Certificate) {
                            certs[i] = (X509Certificate) peerCerts[i];
                        }
                    }
                    CertVerificationResult result = processServerCertificates(hostname, certs);
                    
                    if (result == CertVerificationResult.FAIL) {
                        return false;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "证书处理异常: " + hostname + " -> " + e.getMessage());
                if (mMode == VerificationMode.VERIFY) {
                    return false;
                }
            }
            
            return true;
        }
    }

    /**
     * 获取安全统计信息
     */
    public Map<String, Object> getSecurityStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_verifications", mTotalVerifications);
        stats.put("failed_verifications", mFailedVerifications);
        stats.put("stored_cert_domains", mCertPins.size());
        stats.put("verification_mode", mMode.name());
        stats.put("last_verification_time", mLastVerificationTime);
        stats.put("failure_rate", mTotalVerifications > 0 ? 
            (double) mFailedVerifications / mTotalVerifications : 0);
        return stats;
    }

    /**
     * 导出所有存储的证书信息
     */
    public String exportCertInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("SSL证书存储信息:\n");
        sb.append("模式: ").append(mMode.name()).append("\n");
        sb.append("存储域名数: ").append(mCertPins.size()).append("\n");
        sb.append("验证统计: 总").append(mTotalVerifications)
          .append(", 失败").append(mFailedVerifications).append("\n\n");
        
        for (Map.Entry<String, List<String>> entry : mCertPins.entrySet()) {
            sb.append("域名: ").append(entry.getKey()).append("\n");
            for (String pin : entry.getValue()) {
                sb.append("  pin: ").append(pin).append("\n");
            }
        }
        
        return sb.toString();
    }

    /**
     * 证书验证结果
     */
    public enum CertVerificationResult {
        /** 验证成功 */
        SUCCESS,
        /** 验证失败，拒绝连接 */
        FAIL,
        /** 证书变更警告，但允许连接 */
        WARNING
    }

    /**
     * 证书变更事件监听接口
     */
    public interface CertChangeListener {
        /**
         * 证书变更回调
         * @param hostname 主机名
         * @param oldPins 旧证书指纹（首次为null）
         * @param newPins 新证书指纹
         * @param trusted 是否自动信任
         */
        void onCertChanged(String hostname, List<String> oldPins, List<String> newPins, boolean trusted);
    }
}
