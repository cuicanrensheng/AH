package com.tv.live.util;

public class LifecycleLogger {
    public static void onCreate() {
        LogUtils.log("【主页】onCreate -> 页面创建");
    }

    public static void onResume() {
        LogUtils.log("【主页】onResume -> 回到前台");
    }

    public static void onPause() {
        LogUtils.log("【主页】onPause -> 切到后台");
    }

    public static void onDestroy() {
        LogUtils.log("【主页】onDestroy -> 页面销毁");
    }
}
