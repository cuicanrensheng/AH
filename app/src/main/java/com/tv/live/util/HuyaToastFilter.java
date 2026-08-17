package com.tv.live.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 虎牙 SDK Toast 拦截器
 *
 * 【背景】
 * 虎牙 Berry SDK 在模拟器环境下初始化时，内部会异步触发 LiveStream.getConfig 等请求，
 * 失败时通过 com.duowan.auk.ui.widget.ArkToast.show(CharSequence) 弹出
 * "获取参数失败，请重试" 等 Toast，与观看直播功能无关，但对用户造成困扰。
 *
 * 【方案】
 * ArkToast.show(text) 流程：
 *   1) 主线程 post 一个 Runnable (ArkToast$1)
 *   2) Runnable.run():
 *        sToast = ArkToast.access$000()
 *        if sToast == null → createToast() 用 defaultText 创建空内容 Toast
 *        sToast.setGravity(17, xOff, yOff)     // Gravity.CENTER
 *        sToast.setText(text)                   // ★ 设置实际错误文本
 *        sToast.setDuration(duration)
 *        sToast.show()                           // ★ 显示
 *
 * 我们通过反射把 ArkToast.sToast 静态字段替换为自定义 Toast 子类，
 * 重写 setText(CharSequence) 记录待显示文本，重写 show() 在内容命中关键词时直接 no-op。
 *
 * 【兼容性】
 * - 只在 SDK 初始化后调用一次 install()
 * - 失败时静默回退，不影响应用正常 Toast 调用
 * - 应用本身的 Toast 不走 ArkToast，不受影响
 */
public final class HuyaToastFilter {

    private static final String TAG = "HuyaToastFilter";
    private static volatile boolean sInstalled = false;

    /** 命中以下关键词的 Toast 将被静默拦截（大小写不敏感） */
    private static final String[] BLOCK_KEYWORDS = {
            "获取参数失败",
            "请重试",
            "获取失败",
            "init fail",
            "monitor",
            "获取开始推流",
    };

    private HuyaToastFilter() {}

    /**
     * 安装 ArkToast 拦截。在 HuyaSDKParser.init 完成后调用一次即可。
     */
    public static synchronized void install(Context ctx) {
        if (sInstalled) return;
        sInstalled = true;
        // 必须在主线程执行（Toast 必须在主线程创建）
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> doInstall(ctx));
        } else {
            doInstall(ctx);
        }
    }

    private static void doInstall(Context ctx) {
        Class<?> arkToastCls;
        try {
            ClassLoader cl = findSdkClassLoader();
            if (cl == null) cl = HuyaToastFilter.class.getClassLoader();
            arkToastCls = cl.loadClass("com.duowan.auk.ui.widget.ArkToast");
        } catch (Throwable t) {
            Log.w(TAG, "ArkToast 类未找到，跳过拦截: " + t.getMessage());
            return;
        }

        try {
            Field sToastField = arkToastCls.getDeclaredField("sToast");
            sToastField.setAccessible(true);

            // 创建 Hook Toast 替换 sToast
            Object hookToast = new FilteredToast(ctx.getApplicationContext());
            sToastField.set(null, hookToast);
            Log.i(TAG, "✅ ArkToast 拦截已安装 (sToast 字段替换为 FilteredToast)");
        } catch (Throwable t) {
            Log.w(TAG, "⚠️ ArkToast 拦截未生效: " + t.getMessage());
        }
    }

    private static ClassLoader findSdkClassLoader() {
        try {
            // 优先用 HuyaBerry 类的 ClassLoader（可能是子 DexClassLoader）
            Class<?> berryCls = Class.forName("com.huya.berry.client.HuyaBerry");
            return berryCls.getClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 自定义 Toast 子类：重写 setText(CharSequence) 记录待显示文本，
     * 重写 show() 在内容命中关键词时直接 no-op，实现 SDK 错误 toast 拦截。
     *
     * ArkToast$1.run() 的调用顺序:
     *   setGravity(int, int, int) → setText(CharSequence) → setDuration(int) → show()
     */
    public static final class FilteredToast extends Toast {
        private CharSequence pendingText = "";

        public FilteredToast(Context context) {
            super(context);
        }

        @Override
        public void setText(int resId) {
            super.setText(resId);
            try { pendingText = getView().getContext().getString(resId); } catch (Throwable ignored) {}
        }

        @Override
        public void setText(CharSequence s) {
            super.setText(s);
            pendingText = s != null ? s : "";
        }

        @Override
        public void show() {
            if (shouldBlock(pendingText)) {
                Log.d(TAG, "🔇 拦截 SDK Toast: " + pendingText);
                return;
            }
            try {
                super.show();
            } catch (Throwable ignored) {}
        }
    }

    private static boolean shouldBlock(CharSequence text) {
        if (text == null) return false;
        String s = text.toString();
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        for (String kw : BLOCK_KEYWORDS) {
            if (lower.contains(kw.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }
}
