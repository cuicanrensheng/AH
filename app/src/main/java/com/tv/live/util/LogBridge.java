package com.tv.live.util;

import android.util.Log;

/**
 * 统一日志桥（全局日志门面）
 *
 * 全项目所有 android.util.Log 调用都经由本类转发：
 *  1. 保持原有 logcat 输出（ADB 可抓）；
 *  2. 同时写入 LogCollector 内存缓冲 —— App 内置日志服务器
 *     LogServer（端口 9527）的数据源，浏览器/接口可直接拉取。
 *
 * 这样即使 release 构建下 R8 通过 -assumenosideeffects 裁剪掉
 * Log.v / Log.d 的 logcat 输出，服务器端口依然能拉到全部日志，
 * 不会让 R8 把软件变成"黑盒子"。
 */
public final class LogBridge {
    private LogBridge() {}

    public static void v(String tag, String msg) {
        Log.v(tag, msg);
        forward(tag, msg, LogCollector.TYPE_DEBUG);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        forward(tag, msg, LogCollector.TYPE_DEBUG);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        forward(tag, msg, LogCollector.TYPE_INFO);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        forward(tag, msg, LogCollector.TYPE_WARN);
    }

    public static void w(String tag, String msg, Throwable tr) {
        Log.w(tag, msg, tr);
        forward(tag, msg + (tr != null ? " : " + tr.getMessage() : ""), LogCollector.TYPE_WARN);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        forward(tag, msg, LogCollector.TYPE_ERROR);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        forward(tag, msg + (tr != null ? " : " + tr.getMessage() : ""), LogCollector.TYPE_ERROR);
    }

    public static int println(int priority, String tag, String msg) {
        int r = Log.println(priority, tag, msg);
        forward(tag, msg, LogCollector.TYPE_INFO);
        return r;
    }

    private static void forward(String tag, String msg, String type) {
        try {
            // 使用 addLogNoReport：桥接日志不进 Bugly 误报通道，
            // 真实异常仍由各模块显式调用 ExceptionReporter / LogCollector.error 上报。
            LogCollector.getInstance().addLogNoReport(tag, msg, type);
        } catch (Throwable ignored) {}
    }
}
