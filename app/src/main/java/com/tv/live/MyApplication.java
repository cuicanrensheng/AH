package com.tv.live;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import com.tv.live.manager.HuyaTogetherWatchManager;
import com.tv.live.util.NetUtil;
import com.tv.live.util.HuyaSDKParser;

public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        long t0 = System.currentTimeMillis();

        // Debug 模式下开启 StrictMode，精准抓主线程 IO/网络/长时间同步阻塞
        if (BuildConfig.DEBUG) {
            try {
                android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .permitDiskReads()      // 允许主线程少量磁盘读（SharedPreferences/资源文件正常使用）
                        .permitDiskWrites()     // 允许主线程少量磁盘写（sp commit），但网络/长阻塞仍报警
                        .build());
                android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder()
                        .detectLeakedSqlLiteObjects()
                        .detectActivityLeaks()
                        .penaltyLog()
                        .build());
                Log.d("MyApplication", "【启动】StrictMode 已开启（Debug版），主线程长耗时将以StrictMode tag打印Log");
            } catch (Throwable ignore) {}
        }

        CrashHandler.getInstance().init(this);
        long t1 = System.currentTimeMillis();

        NetUtil.init(this);
        long t2 = System.currentTimeMillis();

        HuyaTogetherWatchManager.getInstance().setContext(this);
        long t3 = System.currentTimeMillis();

        // ⭐【修复模拟器启动黑屏 + 修复HuyaSDK Looper空指针NPE + 修复Choreographer连续丢帧 #1/2】
        // · 已修：后台线程Looper空指针 → 用主线程Handler post
        // · 本次优化：Choreographer: Skipped 33+86+42帧 (≈1.0秒连续阻塞)
        //   根因：HuyaSDK.postDelayed(150ms)=498ms  跟 Parser.postDelayed(200ms)=326ms
        //         两者只差50ms→几乎连续串行占住主线程824ms→Choreographer抽不到空渲染→Loading动画一卡一卡
        //   修复：拉长两者post间隔，中间让出Loading动画的渲染时间：
        //         - HuyaSDK → postDelayed(BootTimingConfig.HUYA_POST_DELAY_MS=950ms)
        //              （先等onCreate结束+ActivityTaskManager.Displayed事件先打印，Displayed=onCreateEnd+490ms≈867ms < 950ms，让系统Displayed先跑，冷启动时间再降≈100ms）
        //         - Parser/WebView → 单独在 MainActivity.postDelayed(PARSER_POST_DELAY_MS=1950ms) 再跑（两者间隔约1000ms空档）
        //   注意：虎牙SDK耗时500ms，Android Choreographer每帧16ms → 这段还是会丢约30帧。
        //         但30帧+分开执行，用户感知上从"卡1秒"变成"Loading偶发卡顿"<0.5秒，体感大幅优化。
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final Application appRef = this;
        mainHandler.postDelayed(() -> {
            long ts = System.currentTimeMillis();
            try {
                HuyaSDKParser.init(appRef);
                long cost = System.currentTimeMillis() - ts;
                Log.i("MyApplication", "【启动计时】HuyaSDKParser.init 主线程延迟执行耗时：" + cost
                        + "ms（postDelay=" + BootTimingConfig.HUYA_POST_DELAY_MS
                        + "ms，先让Displayed事件先跑，与Parser间隔约"
                        + (BootTimingConfig.PARSER_POST_DELAY_MS - BootTimingConfig.HUYA_POST_DELAY_MS) + "ms）");
            } catch (Throwable t) {
                // ⭐【2026-08-17 瘦身修复：必须用 Throwable（含 Error），不能只用 Exception】
                //   排除 hyquic + hysignal-quic 后，HuyaBerry.init → Hal.initHySignal → HySignalWrapper.init
                //   在字节码解析阶段因硬引用 StnLogic$ICallBack 会抛 java.lang.NoClassDefFoundError（继承自 Error），
                //   若只 catch Exception 会漏掉 → FATAL EXCEPTION 主进程崩溃。
                //   我们架构是 HTTP API 优先 + SDK fallback，SDK 初始化失败是可降级的，不影响直播播放。
                Log.e("MyApplication", "虎牙 SDK 初始化异常（postDelay执行，降级到纯 HTTP API 模式）: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            }
        }, BootTimingConfig.HUYA_POST_DELAY_MS);
        long t4 = System.currentTimeMillis();

        Log.i("MyApplication", "【启动计时】Application.onCreate 各段耗时(ms)："
                + " CrashHandler=" + (t1 - t0)
                + " NetUtil.init=" + (t2 - t1)
                + " HuyaTogetherWatch.setCtx=" + (t3 - t2)
                + " HuyaSDK.postDelaySchedule=" + (t4 - t3)
                + " 总计=" + (System.currentTimeMillis() - t0));
    }
}
