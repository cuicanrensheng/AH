package com.tv.live.util;

import android.util.Log;

import java.lang.reflect.Field;

/**
 * 虎牙 SDK 兼容性修复钩子。
 *
 * 问题：SDK 的 BaseApi 注册的 OnCrashListener（HuyaBerryImpl$6.onCrashIfDebug）
 *       会无条件 throw RuntimeException。SDK 内部任何模块启动失败（如
 *       Ark.startModule 反射实例化失败、createService 失败）都会经过
 *       crashIfDebug -> onCrashIfDebug -> throw，导致 HuyaBerry.init() 直接
 *       抛异常、整个 SDK 初始化失败，解析回调永远不会触发。
 *
 * 修复：在 HuyaBerry.init() 之前，把 BaseApi.sOnCrashListener 反射置为 null，
 *       使 crashIfDebug 只记录日志、不再抛异常。这样 SDK 的"非致命模块失败"
 *       不再升级为 init 失败。
 *
 * 注意：仅应在 release 构建使用（debug 构建 SDK 依赖此机制暴露问题）。
 */
public final class SdkCompatHook {
    private static final String TAG = "SdkCompatHook";
    private static volatile boolean sNeutralized = false;

    private SdkCompatHook() {
    }

    /**
     * 中和 BaseApi.crashIfDebug 的抛异常行为。
     * 必须在 HuyaBerry.instance().init(...) 之前调用。
     */
    public static void neutralizeCrashIfDebug() {
        if (sNeutralized) {
            return;
        }
        sNeutralized = true;
        try {
            Class<?> baseApi = Class.forName("com.huya.live.common.api.BaseApi");
            Field listener = baseApi.getDeclaredField("sOnCrashListener");
            listener.setAccessible(true);
            listener.set(null, null);
            Log.i(TAG, "✅ 已中和 BaseApi.crashIfDebug（sOnCrashListener=null），SDK init 不再因非致命错误抛异常");
        } catch (Throwable t) {
            // 失败不致命：仅失去保护，SDK 仍按原逻辑运行
            Log.w(TAG, "⚠️ 中和 BaseApi.crashIfDebug 失败（不影响主流程）: " + t);
        }
    }
}
