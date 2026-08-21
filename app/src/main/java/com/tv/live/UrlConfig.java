package com.tv.live;

import com.tv.live.security.SecurityCore;

/**
 * 字符串加密版 UrlConfig（AES-256-CBC 升级版）：
 *
 * - 字段 B_XX 存储 AES-256-CBC 密文（Base64 编码，IV 在前 16 字节）
 * - 通过 getLiveUrl()/getLiveUrl2()/getEpgUrl()/getEpgUrl2() 在运行时由
 *   SecurityCore（Native 层 libtvlive_security.so）解密
 * - 反编译只能看到一串 base64 字符 + 1 个 token 字符串，看不到真实 URL
 * - Key 分片（KEY_PART_A/B）只在 SO 中，反编译 dex 拿不到
 *
 * 离线生成密文：见 cpp/gen_cipher.py
 */
public final class UrlConfig {

    // AES-256-CBC 密文（Base64）
    private static final String B_LIVE_1 =
            "X1VMrWGdBxjwBStc4NnSMUhAfwrjH5kyLv2C0HAZ1PWe4BHasi2jMLZA9R280Eau9vwjcc3xo2Q2UYv3bbU4MUE3ElCK/RdPDHZShkATt6tH2B26Z31xhlk0rr6/SB8xf2aaHKjersgNY5U4PGGWwQ==";
    private static final String B_LIVE_2 =
            "m9A3okgqbGwkUAmtn0iD0rY5TUZKBmhzgWAsSQLs2vDTPAahAUoMnd6M6gQbOlCL4REHMdfgBnz4FS1p2SoM/u8T2PBaGxVVjCAG8BSia1s=";
    private static final String B_EPG_1  =
            "zZBnTSdOZW8g3DioT8rZiEwqv7K/TdIhF9s8x56Dh9WSVEZop4ruoPZnJY4MRpqO";
    private static final String B_EPG_2  =
            "BVdyAjq5u+rbwdBJG2GM10tMvJ4HKY7eC9CcKxTlJEPP9zOxQoHqCSLTHeOMxg8q";

    private static volatile String sLive1, sLive2, sEpg1, sEpg2;

    private UrlConfig() {}

    private static String d(String cipherB64) {
        // 1) 优先用 Native AES 解密（key 碎片在 SO 里）
        String s = SecurityCore.decryptToString(cipherB64);
        if (s != null) return s;
        // 2) Native 未装载时兜底返回 null（启动期 url 还没准备好不影响后续逻辑）
        return null;
    }

    public static String getLiveUrl()  { 
        String v = sLive1; 
        return v != null ? v : (sLive1 = d(B_LIVE_1)); 
    }
    public static String getLiveUrl2() { 
        String v = sLive2; 
        return v != null ? v : (sLive2 = d(B_LIVE_2)); 
    }
    public static String getEpgUrl()   { 
        String v = sEpg1; 
        return v != null ? v : (sEpg1 = d(B_EPG_1)); 
    }
    public static String getEpgUrl2()  { 
        String v = sEpg2; 
        return v != null ? v : (sEpg2 = d(B_EPG_2)); 
    }

    // 兼容旧字段名（外部代码可能仍引用公共字段）
    public static String LIVE_URL   = ""; // 在 MyApplication 完成 init 后回填
    public static String LIVE_URL_2 = "";
    public static String EPG_URL    = "";
    public static String EPG_URL_2  = "";

    /** 启动后由 SecurityCore.init 完成后调用一次，把解出的明文回填到静态字段 */
    public static void fillPublicFields() {
        String l1 = getLiveUrl();  if (l1 != null) LIVE_URL   = l1;
        String l2 = getLiveUrl2(); if (l2 != null) LIVE_URL_2 = l2;
        String e1 = getEpgUrl();   if (e1 != null) EPG_URL    = e1;
        String e2 = getEpgUrl2();  if (e2 != null) EPG_URL_2  = e2;
    }
}
