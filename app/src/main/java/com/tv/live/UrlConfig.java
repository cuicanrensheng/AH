package com.tv.live;

import android.util.Log;

import com.tv.live.security.SecurityCore;

public final class UrlConfig {

    private static final String TAG = "UrlConfig";

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
        String l1 = getLiveUrl();  if (l1 != null) { LIVE_URL   = l1; Log.i(TAG, "LIVE_URL 解密成功: " + l1.substring(0, Math.min(60, l1.length())) + "..."); }
        else { Log.e(TAG, "LIVE_URL 解密失败，将使用空字符串"); }

        String l2 = getLiveUrl2(); if (l2 != null) { LIVE_URL_2 = l2; Log.i(TAG, "LIVE_URL_2 解密成功: " + l2.substring(0, Math.min(60, l2.length())) + "..."); }
        else { Log.w(TAG, "LIVE_URL_2 解密失败"); }

        String e1 = getEpgUrl();   if (e1 != null) EPG_URL    = e1;
        String e2 = getEpgUrl2();  if (e2 != null) EPG_URL_2  = e2;

        Log.i(TAG, "URL 配置解密完成，SecurityCore.isLoaded=" + SecurityCore.isLoaded());
    }
}
