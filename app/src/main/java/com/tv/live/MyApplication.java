package com.tv.live;

import android.app.Application;
import com.tv.live.util.NetUtil;

public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        CrashHandler.getInstance().init(this);

        NetUtil.init(this);
    }
}
