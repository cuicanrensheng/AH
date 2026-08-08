package com.tv.live;

import android.app.Application;
import android.util.Log;

import com.tv.live.util.NetUtil;
import com.tv.live.util.HuyaSDKParser;

public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        CrashHandler.getInstance().init(this);

        NetUtil.init(this);

        // 初始化虎牙 Berry SDK
        try {
            HuyaSDKParser.init(this);
        } catch (Exception e) {
            Log.e("MyApplication", "虎牙 SDK 初始化异常: " + e.getMessage());
        }
    }
}
