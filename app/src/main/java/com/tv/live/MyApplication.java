package com.tv.live;

import android.app.Application;
import android.util.Log;

import com.tv.live.util.AppCacheInspector;
import com.tv.live.util.NetUtil;
import com.tv.live.util.HuyaCacheGovernor;
import com.tv.live.util.HuyaSDKParser;

public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        CrashHandler.getInstance().init(this);

        NetUtil.init(this);

        // 🔧 启动立刻做一次"全应用缓存巡检"（后台线程，不阻塞启动）：
        //   覆盖 CacheManager(EPG/直播源) / ExoPlayer 临时分片 / Crash 日志 /
        //   更新APK遗留 / JsParser 过期插件 / WebView 资源缓存 / code_cache traces 等
        try {
            AppCacheInspector.startupCleanup(this);
        } catch (Exception e) {
            Log.w("MyApplication", "AppCacheInspector.startupCleanup failed (ignore): " + e.getMessage());
        }

        // 🔧 启动立即清理虎牙 SDK 过期/日志/崩溃缓存（后台线程执行，不阻塞启动）
        // 把 SDK 可能写入的目录一次性扫描清理，避免安装后占用持续变大
        try {
            HuyaCacheGovernor.startupCleanup(this);
        } catch (Exception e) {
            Log.w("MyApplication", "HuyaCacheGovernor.startupCleanup failed (ignore): " + e.getMessage());
        }

        // 初始化虎牙 Berry SDK
        try {
            HuyaSDKParser.init(this);
        } catch (Exception e) {
            Log.e("MyApplication", "虎牙 SDK 初始化异常: " + e.getMessage());
        }
    }
}

