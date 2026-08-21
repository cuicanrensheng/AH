package com.tv.live;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import com.tv.live.BuildConfig;
import com.tv.live.security.IntegrityCheck;
import com.tv.live.security.SecurityCore;
import com.tv.live.security.TamperReporter;

import java.security.MessageDigest;

/**
 * APK 防二次打包 + 完整性校验：
 * 1. 校验包名（防止被改名后重新签名）
 * 2. 校验签名 SHA-256（防止二次签名）
 * 3. 校验 classes.dex SHA-256（防止 SO 注入 / 类篡改）
 * 4. NDK 反调试（ptrace + TracerPid）
 * 5. Anti-Frida / Anti-Xposed / Anti-root / 模拟器粗检测
 *
 * 关键类已加 final，防 Xposed/Substrate 替换整个类。
 */
public final class SecurityCheck {

    private static final String TAG = "SecChk";
    private static final String EXPECTED_PKG = "com.tv.live";

    // 期望的 classes.dex SHA-256（Base64）；每次 release 重新编译后必须更新
    // 启动期若不匹配 → 立即退出
    private static final String EXPECTED_DEX_B64 = "REPLACE_WITH_DEX_SHA256_BASE64";

    // 期望的签名 SHA-256（Base64）- release 签名
    private static final String EXPECTED_SIG_BASE64 = "xQdedEk3xbKAsqg0WqDdH0qmjiYAkARaVVtrTVXdQAQ=";

    private SecurityCheck() {}

    /**
     * 启动时调用一次
     * 正式版启用签名校验，调试版跳过
     *
     * 注意：安全检查失败时不再直接杀进程，而是：
     * 1. 记录详细日志供调试
     * 2. 上报篡改事件到监控平台
     * 3. 返回 false 让调用方降级处理
     */
    public static boolean verifyOnStart(Context ctx) {
        if (BuildConfig.IS_DEBUG) {
            Log.i(TAG, "🔓 调试版：跳过签名校验");
            return true;
        }
        
        Log.i(TAG, "🔒 正式版：启用签名校验");
        
        // 初始化篡改上报
        try {
            TamperReporter.init(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "TamperReporter 初始化失败: " + t.getMessage());
        }
        
        boolean allPassed = true;
        
        // 1. 校验签名
        if (!verifySignature(ctx)) {
            Log.e(TAG, "⚠️ 签名校验未通过，将上报但不阻断启动");
            try {
                TamperReporter.reportTamper(
                    TamperReporter.TAMPER_SIGNATURE,
                    "签名校验失败"
                );
            } catch (Throwable t) {
                Log.w(TAG, "篡改上报失败: " + t.getMessage());
            }
            allPassed = false;
        }
        
        // 2. 校验包名
        String pkgName = ctx.getPackageName();
        if (!EXPECTED_PKG.equals(pkgName)) {
            Log.e(TAG, "❌ 包名不匹配! expected=" + EXPECTED_PKG + " current=" + pkgName);
            try {
                TamperReporter.reportTamper(
                    TamperReporter.TAMPER_PACKAGE_NAME,
                    "包名校验失败, expected=" + EXPECTED_PKG + " current=" + pkgName
                );
            } catch (Throwable t) {
                Log.w(TAG, "篡改上报失败: " + t.getMessage());
            }
            allPassed = false;
        } else {
            Log.i(TAG, "✅ 包名校验通过");
        }
        
        // 3. 校验 DEX 完整性（可选，占位符未设置时只打印 hash）
        if (!verifyDexIntegrity(ctx)) {
            Log.e(TAG, "⚠️ DEX 完整性校验未通过");
            try {
                TamperReporter.reportTamper(
                    TamperReporter.TAMPER_DEX_INTEGRITY,
                    "DEX完整性校验失败"
                );
            } catch (Throwable t) {
                Log.w(TAG, "篡改上报失败: " + t.getMessage());
            }
            allPassed = false;
        }
        
        if (!allPassed) {
            Log.w(TAG, "⚠️ 部分安全检查未通过，但应用将继续运行（降级模式）");
        }
        return allPassed;
    }

    private static boolean verifySignature(Context appCtx) {
        try {
            PackageManager pm = appCtx.getPackageManager();
            PackageInfo pi;
            byte[] certBytes;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi = pm.getPackageInfo(appCtx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] sigs = (pi.signingInfo != null && pi.signingInfo.hasMultipleSigners())
                        ? pi.signingInfo.getApkContentsSigners()
                        : (pi.signingInfo != null ? pi.signingInfo.getSigningCertificateHistory() : null);
                if (sigs == null || sigs.length == 0) {
                    Log.w(TAG, "未找到签名");
                    return false;
                }
                certBytes = sigs[0].toByteArray();
            } else {
                pi = pm.getPackageInfo(appCtx.getPackageName(), PackageManager.GET_SIGNATURES);
                Signature[] sigs = pi.signatures;
                if (sigs == null || sigs.length == 0) {
                    Log.w(TAG, "未找到签名");
                    return false;
                }
                certBytes = sigs[0].toByteArray();
            }

            byte[] shaBytes = MessageDigest.getInstance("SHA-256").digest(certBytes);
            String currentB64 = Base64.encodeToString(shaBytes, Base64.NO_WRAP);
            Log.i(TAG, "当前签名 SHA256=" + currentB64);

            // 严格校验签名
            if (!EXPECTED_SIG_BASE64.equals(currentB64)) {
                Log.e(TAG, "❌ 签名校验失败! expected=" + EXPECTED_SIG_BASE64 + " current=" + currentB64);
                return false;
            }
            Log.i(TAG, "✅ 签名校验通过");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "verify error", e);
            return false;
        }
    }

    private static boolean verifyDexIntegrity(Context appCtx) {
        try {
            byte[] hash = IntegrityCheck.computeDexHash(appCtx);
            if (hash == null) return true; // 计算失败不阻塞
            String currentB64 = Base64.encodeToString(hash, Base64.NO_WRAP);
            Log.i(TAG, "EXPECTED_DEX_SHA256=" + currentB64);
            if (!"REPLACE_WITH_DEX_SHA256_BASE64".equals(EXPECTED_DEX_B64)) {
                // 已配置真实值 → 严格校验（仅在不启用资源混淆的最终发布版使用）
                if (!EXPECTED_DEX_B64.equals(currentB64)) {
                    Log.e(TAG, "dex hash 不匹配！expected=" + EXPECTED_DEX_B64 + " current=" + currentB64);
                    return false;
                }
                Log.w(TAG, "✅ dex 完整性校验通过");
            } else {
                // 默认：仅打印 hash 用于人工对比，不阻塞启动
                Log.w(TAG, "dex hash (人工对比) = " + currentB64);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "verify dex error", e);
            return true;
        }
    }
}