package com.tv.live;

import android.text.TextUtils;
import com.tv.live.util.LogBridge;

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

    // ✅ 第3套本地直播源（随APK打包在assets，不需要解密，永久离线可用）
    public static final String LIVE_URL_3_LOCAL_ASSET = "asset://live_source_3.txt";

    private UrlConfig() {}

    // 🔒 「内置源原始地址永久备份」
    // 🔴 设计背景：
    //   LIVE_URL/LIVE_URL_2/EPG_URL/EPG_URL_2 被 UI 切源时会被外部代码覆盖为当前生效源（历史设计兼容），
    //   但 SourceManager 用 BuiltinSpec.url 来识别内置源，如果 spec.url 也跟着被污染，会导致跨 spec 串台合并
    //   （例如：切源3 → LIVE_URL = 源3地址 → LIVE_1 spec url 恒等于 LIVE_3 spec url → 删源1保留源3）
    // 因此引入 *_RAW 静态字段：首次解密后写一次，之后永远不改；SourceManager 的 BuiltinSpec 一律读 *_RAW。
    public static final  String LIVE_URL_3_RAW = LIVE_URL_3_LOCAL_ASSET; // 本地地址永远不变
    public static volatile String LIVE_URL_1_RAW = ""; // 解密后一次性写入
    public static volatile String LIVE_URL_2_RAW = "";
    public static volatile String EPG_URL_1_RAW  = "";
    public static volatile String EPG_URL_2_RAW  = "";

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
    public static String LIVE_URL_3 = LIVE_URL_3_LOCAL_ASSET; // 第3套：本地assets，无需解密
    public static String EPG_URL    = "";
    public static String EPG_URL_2  = "";

    /** 启动后由 SecurityCore.init 完成后调用一次，把解出的明文回填到静态字段 */
    public static void fillPublicFields() {
        String l1 = getLiveUrl();  if (l1 != null) { LIVE_URL   = l1; if (TextUtils.isEmpty(LIVE_URL_1_RAW)) LIVE_URL_1_RAW = l1; LogBridge.i(TAG, "LIVE_URL 解密成功: " + l1.substring(0, Math.min(60, l1.length())) + "..."); }
        else { LogBridge.e(TAG, "LIVE_URL 解密失败，将使用空字符串"); }

        String l2 = getLiveUrl2();
        // 🔧 内置源2地址迁移：
        //   原密文解密出的 https://gitee.com/qf_1111/iptv/raw/master/iptvedqu.m3u 已失效（仓库/文件被删除），
        //   命中该 URL 时自动替换为用户提供的最新地址：https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u
        //   （302 跳转到 raw.giteeusercontent.com，实际返回完整 M3U 列表，包含江西卫视/江西都市等江西地方台）
        final String OLD_BROKEN = "https://gitee.com/qf_1111/iptv/raw/master/iptvedqu.m3u";
        final String NEW_WORKING = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
        if (l2 != null) {
            String l2fixed = OLD_BROKEN.equals(l2) ? NEW_WORKING : l2;
            LIVE_URL_2 = l2fixed;
            if (TextUtils.isEmpty(LIVE_URL_2_RAW)) LIVE_URL_2_RAW = l2fixed;
            if (!l2.equals(l2fixed)) {
                LogBridge.w(TAG, "LIVE_URL_2 原始地址已失效(404)，已自动迁移到新地址: " + NEW_WORKING);
            } else {
                LogBridge.i(TAG, "LIVE_URL_2 解密成功: " + l2.substring(0, Math.min(60, l2.length())) + "...");
            }
        }
        else { LogBridge.w(TAG, "LIVE_URL_2 解密失败"); }

        String e1 = getEpgUrl();   if (e1 != null) { EPG_URL    = e1; if (TextUtils.isEmpty(EPG_URL_1_RAW))  EPG_URL_1_RAW  = e1; }
        String e2 = getEpgUrl2();  if (e2 != null) { EPG_URL_2  = e2; if (TextUtils.isEmpty(EPG_URL_2_RAW))  EPG_URL_2_RAW  = e2; }

        LogBridge.i(TAG, "URL 配置解密完成，SecurityCore.isLoaded=" + SecurityCore.isLoaded() +
                   " | RAW[L1=" + (TextUtils.isEmpty(LIVE_URL_1_RAW) ? "null" : "ok") +
                   ", L2=" + (TextUtils.isEmpty(LIVE_URL_2_RAW) ? "null" : "ok") +
                   ", E1=" + (TextUtils.isEmpty(EPG_URL_1_RAW)  ? "null" : "ok") +
                   ", E2=" + (TextUtils.isEmpty(EPG_URL_2_RAW)  ? "null" : "ok") +
                   ", L3=" + LIVE_URL_3_RAW + "]");
    }

    /**
     * 对任意传入的 URL 做一次「已知失效地址」迁移。
     * 用于：SourceManager 读取 SP 中历史写入的旧 URL 时，把 qf_1111 那个永久 404 地址也同步替换成 playlist.m3u，
     * 避免在「fillPublicFields 已经修好了 RAW，但 SP 中还存着老的 404 URL」的不一致场景下，
     * buildBuiltinSources 注入时因 name 命中而继续保留老 404 URL，最终用户切源2 仍加载失败。
     */
    public static String sanitizeLiveUrl(String url) {
        if (url == null) return null;
        if ("https://gitee.com/qf_1111/iptv/raw/master/iptvedqu.m3u".equals(url)) {
            return "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
        }
        return url;
    }
}
