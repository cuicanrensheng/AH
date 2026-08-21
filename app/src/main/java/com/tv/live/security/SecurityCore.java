package com.tv.live.security;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * JNI 桥接：所有反调试/反 hook/AES-256 解密都走 Native 层。
 *
 * 调用流程：
 *   1. 进程启动 → onCreate → SecurityCore.init() → 装载 libtvlive_security.so
 *   2. 喂一个 runtime token（App 启动期随机生成）→ Native 拼装 AES key
 *   3. 调用 nativeAntiDebug() → ptrace TRACEME 失败即 _exit
 *   4. 调用 nativeStartMonitor() → 后台线程 2s 扫一次 /proc/self/maps
 *   5. 调用 nativeCheck() → 返回 5-bit mask，bit0=debug bit1=frida_port bit2=frida_maps bit3=root bit4=emu
 *   6. nativeDecrypt()  解密 AES-256-CBC 密文（IV 在前 16 字节）
 */
public final class SecurityCore {

    private static volatile boolean sLoaded = false;
    private static volatile byte[] sToken = null;

    private SecurityCore() {}

    /**
     * 固定 token：必须与 cpp/security.cpp 的 KEY_PART_A/B 配套使用。
     * 用作 AES-256 key 拼装的扰动因子；固定后可离线生成密文。
     * 反编译时即使拿到该字符串也不知道 key（因为 KEY_PART_A/B 在 SO 里）。
     */
    private static final byte[] FIXED_TOKEN =
            "TVLiveSec!2026Se".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static synchronized void init() {
        if (sLoaded) return;
        try {
            System.loadLibrary("tvlive_security");
            // 用固定 token（密文可离线生成）
            sToken = FIXED_TOKEN.clone();
            nativeSetToken(sToken);
            sLoaded = true;
        } catch (Throwable t) {
            // 装载失败不阻塞启动（避免 root 设备因 SO 加载失败而拒绝服务）
            sLoaded = false;
        }
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    /** 启动 ptrace 反调试：失败则进程 _exit(9) */
    public static void antiDebug() {
        if (sLoaded) {
            try { nativeAntiDebug(); } catch (Throwable ignored) {}
        }
        // 兜底：Java 层也做 TracerPid 检测
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

    /** 综合安全检查，返回 5-bit mask（bit0=debug bit1=frida_port bit2=frida_maps bit3=root bit4=emu） */
    public static int check() {
        if (!sLoaded) return 0;
        try { return nativeCheck(); } catch (Throwable t) { return 0; }
    }

    /**
     * AES-256-CBC 解密（密文前 16 字节为 IV）
     * @param cipherB64  Base64 编码的密文
     * @return 明文，失败返回 null
     */
    public static String decryptToString(String cipherB64) {
        if (!sLoaded || cipherB64 == null) return null;
        try {
            byte[] cipher = Base64.decode(cipherB64, Base64.NO_WRAP);
            byte[] plain = nativeDecrypt(cipher);
            if (plain == null) return null;
            String s = new String(plain, StandardCharsets.UTF_8);
            // 覆写明文缓冲
            Arrays.fill(plain, (byte) 0);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    // ============ 内部辅助 ============
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

    // ============ JNI ============
    private static native void nativeSetToken(byte[] token);
    private static native void nativeAntiDebug();
    private static native void nativeStartMonitor();
    private static native int  nativeCheck();
    private static native byte[] nativeDecrypt(byte[] cipher);
}
