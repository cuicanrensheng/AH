package com.tv.live;

import android.app.Application;
import android.content.Context;
import androidx.multidex.MultiDex;
import com.tv.live.util.NetUtil;

public class MyApplication extends Application {

    // ✅ 在 Application 创建之初安装 MultiDex，解决旧电视启动闪退
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 🔧【Emoji2 已被剔除】不再需要初始化 EmojiCompat，彻底移除此问题源。

        CrashHandler.getInstance().init(this);

        NetUtil.init(this);
    }
}
