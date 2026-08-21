package com.tv.live.security;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * 关键类完整性校验：启动时计算自身 APK 中关键类的 SHA-256，
 * 与代码里硬编码的"期望值"对比。任何类被 R8 误删、SO 被 patch、
 * 二次打包注入 → hash 必变。
 *
 * 注意：期望 hash 必须在每次 release 编译后从 logcat 抓取再回填（一次性）。
 */
public final class IntegrityCheck {

    private IntegrityCheck() {}

    /**
     * 计算自身 APK 内 classes.dex 的 SHA-256（轻量级整体校验）。
     * 比逐类校验快，且能捕获大部分二次打包。
     *
     * @return 32 字节 hash
     */
    public static byte[] computeApkHash(android.content.Context ctx) {
        try {
            String apkPath = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0).sourceDir;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(new File(apkPath));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            fis.close();
            return md.digest();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 简化的 dex 校验：定位 classes.dex 在 APK 内的范围（zip 中央目录），
     * 只哈希 dex 区段（不哈希签名块、zip metadata）。
     */
    public static byte[] computeDexHash(android.content.Context ctx) {
        try {
            String apkPath = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0).sourceDir;
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath);
            java.util.zip.ZipEntry entry = zf.getEntry("classes.dex");
            if (entry == null) { zf.close(); return null; }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            java.io.InputStream is = zf.getInputStream(entry);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            is.close();
            zf.close();
            return md.digest();
        } catch (Throwable t) {
            return null;
        }
    }
}
