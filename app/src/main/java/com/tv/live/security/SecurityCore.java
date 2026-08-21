package com.tv.live.security;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecurityCore {

    private static final String TAG = "SecurityCore";

    private static volatile boolean sLoaded = false;
    private static volatile byte[] sToken = null;

    private SecurityCore() {}

    private static final byte[] FIXED_TOKEN =
            "TVLiveSec!2026Se".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static synchronized void init() {
        if (sLoaded) return;
        try {
            System.loadLibrary("tvlive_security");
            sToken = FIXED_TOKEN.clone();
            nativeSetToken(sToken);
            sLoaded = true;
            Log.i(TAG, "libtvlive_security.so 加载成功");
            Log.e(TAG, "SECURITY_INIT: Native SO loaded OK");
        } catch (Throwable t) {
            sLoaded = false;
            Log.w(TAG, "libtvlive_security.so 加载失败: " + t.getMessage() + "，将使用 Java fallback 解密");
            Log.e(TAG, "SECURITY_INIT: Native SO FAILED, using Java fallback: " + t.getMessage());
        }
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    public static void antiDebug() {
        if (sLoaded) {
            try { nativeAntiDebug(); } catch (Throwable ignored) {}
        }
        try {
            String status = readFile("/proc/self/status");
            if (status != null) {
                for (String line : status.split("\n")) {
                    if (line.startsWith("TracerPid:")) {
                        String v = line.substring(10).trim();
                        if (!"0".equals(v) && !v.isEmpty()) {
                            android.os.Process.killProcess(android.os.Process.myPid());
                            System.exit(9);
                            return;
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void startMonitor() {
        if (sLoaded) {
            try { nativeStartMonitor(); } catch (Throwable ignored) {}
        }
    }

    public static int check() {
        if (!sLoaded) return 0;
        try { return nativeCheck(); } catch (Throwable t) { return 0; }
    }

    public static String getSecurityStatus() {
        if (!sLoaded) return "SecurityCore not loaded";
        try { return nativeGetSecurityStatus(); } catch (Throwable t) { return "error: " + t.getMessage(); }
    }

    public static String decryptToString(String cipherB64) {
        if (cipherB64 == null) {
            Log.e(TAG, "DECRYPT: cipherB64 is null");
            return null;
        }
        byte[] cipher;
        try {
            cipher = Base64.decode(cipherB64, Base64.NO_WRAP);
        } catch (Throwable t) {
            Log.e(TAG, "DECRYPT: Base64 decode failed: " + t.getMessage());
            return null;
        }

        if (cipher == null || cipher.length < 32) {
            Log.e(TAG, "DECRYPT: cipher invalid, len=" + (cipher != null ? cipher.length : "null"));
            return null;
        }

        if (sLoaded) {
            try {
                byte[] plain = nativeDecrypt(cipher);
                if (plain != null) {
                    String s = new String(plain, StandardCharsets.UTF_8);
                    Arrays.fill(plain, (byte) 0);
                    Log.e(TAG, "DECRYPT: Native decrypted OK, len=" + s.length());
                    return s;
                }
                Log.e(TAG, "DECRYPT: Native decrypt returned null, trying Java fallback");
            } catch (Throwable t) {
                Log.w(TAG, "Native 解密失败: " + t.getMessage() + "，尝试 Java fallback");
                Log.e(TAG, "DECRYPT: Native decrypt exception: " + t.getMessage());
            }
        } else {
            Log.e(TAG, "DECRYPT: Native not loaded, using Java fallback directly");
        }

        try {
            String s = javaAesDecrypt(cipher);
            if (s != null && !s.isEmpty()) {
                Log.e(TAG, "DECRYPT: Java fallback decrypted OK, len=" + s.length());
                return s;
            }
            Log.e(TAG, "Java fallback 解密也失败");
            Log.e(TAG, "DECRYPT: Java fallback returned empty/null");
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "Java fallback 解密异常: " + t.getMessage());
            Log.e(TAG, "DECRYPT: Java fallback exception: " + t.getMessage());
            return null;
        }
    }

    private static final byte[] KEY_PART_A = {
            (byte) 0x9c, (byte) 0x3f, (byte) 0xa1, (byte) 0x77,
            (byte) 0x55, (byte) 0x88, (byte) 0x10, (byte) 0xcc,
            (byte) 0x2d, (byte) 0x4b, (byte) 0xe6, (byte) 0x91,
            (byte) 0x07, (byte) 0xb3, (byte) 0xd5, (byte) 0x42
    };

    private static final byte[] KEY_PART_B = {
            (byte) 0x7a, (byte) 0xb1, (byte) 0x05, (byte) 0xe9,
            (byte) 0x33, (byte) 0x6f, (byte) 0xc2, (byte) 0x4d,
            (byte) 0x18, (byte) 0xfa, (byte) 0x82, (byte) 0x59,
            (byte) 0xa0, (byte) 0x21, (byte) 0x6c, (byte) 0xd7
    };

    private static byte[] buildAesKeyJava() {
        byte[] token = sToken != null ? sToken : FIXED_TOKEN;
        byte[] key = new byte[32];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) (KEY_PART_A[i] ^ token[i]);
            key[16 + i] = (byte) (KEY_PART_B[i] ^ token[i]);
        }
        return key;
    }

    private static String javaAesDecrypt(byte[] cipher) throws Exception {
        if (cipher.length < 32 || (cipher.length - 16) % 16 != 0) {
            return null;
        }
        byte[] iv = new byte[16];
        System.arraycopy(cipher, 0, iv, 0, 16);
        byte[] encrypted = new byte[cipher.length - 16];
        System.arraycopy(cipher, 16, encrypted, 0, encrypted.length);

        byte[] key = buildAesKeyJava();
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipherObj = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipherObj.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        byte[] plain = cipherObj.doFinal(encrypted);
        Arrays.fill(key, (byte) 0);

        return new String(plain, StandardCharsets.UTF_8);
    }

    private static String readFile(String path) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(path);
            byte[] buf = new byte[4096];
            int n = fis.read(buf);
            fis.close();
            return n > 0 ? new String(buf, 0, n, StandardCharsets.ISO_8859_1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static native void nativeSetToken(byte[] token);
    private static native void nativeAntiDebug();
    private static native void nativeStartMonitor();
    private static native int  nativeCheck();
    private static native byte[] nativeDecrypt(byte[] cipher);
    private static native String nativeGetSecurityStatus();
}