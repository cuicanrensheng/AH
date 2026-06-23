package com.tv.live;

import android.app.Application;

/**
 * 自定义 Application 类
 * 用于初始化全局崩溃捕获器
 *
 * 【注意】
 * 需要在 AndroidManifest.xml 的 application 标签中添加：
 * android:name=".MyApplication"
 */
public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // 初始化全局崩溃捕获
        CrashHandler.getInstance().init(this);
    }
}
