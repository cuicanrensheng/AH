package com.tv.live;

import android.app.Application;
import com.tv.live.util.NetUtil; // 🟢 记得导入 NetUtil

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

        // 🟢【新增】给 NetUtil 注入全局上下文，让它能读取 UA 设置
        NetUtil.init(this);
    }
}
