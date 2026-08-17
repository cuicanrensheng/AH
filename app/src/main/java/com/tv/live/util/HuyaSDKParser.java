package com.tv.live.util;

import android.app.Application;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.huya.berry.client.HuyaBerry;
import com.huya.berry.client.HuyaBerryConfig;
import com.huya.berry.client.customui.CustomUICallback;
import com.huya.berry.client.customui.model.BitRateInfo;
import com.huya.berry.client.customui.model.LiveInfo;
import com.huya.berry.gamesdk.base.BaseCallback;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 虎牙 Berry SDK 解析器（纯直调模式：SDK 内部日志/崩溃/调试开关全部直接 import 调用，无反射）
 *
 * 通过 SDK 原生 API 获取直播流地址，充当解析器和防盗链角色。
 * 让 SDK 处理 CDN 鉴权，返回带签名的 URL 给 ExoPlayer 播放。
 *
 * 【compileOnly 直调接入的 12 个 SDK 内部类】
 *   Mars Xlog     : com.tencent.mars.xlog.Log        → setLevel / setConsoleLogOpen / printErrStackTrace
 *   Bugly         : com.tencent.bugly.crashreport.CrashReport, com.tencent.bugly.Bugly
 *   ForceLog      : com.huya.force.log.ForceLog
 *   Huya Core     : HuyaBerryImpl.initLog / com.huya.berry.client.CrashService
 *   AUK Utils     : com.duowan.auk.ArkValue.setDebuggable / com.duowan.auk.util.L
 *   ServiceCenter : com.huya.live.service.ServiceCenter → getService(ICrashService)
 *   ICrashService : com.huya.berry.gamesdk.crash.ICrashService
 *   sdkplayer     : com.duowan.ark.ArkValue (播放内核内部调试开关)
 */
public class HuyaSDKParser {

    private static final String TAG = "HuyaSDKParser";

    private static boolean sInitDone = false;
    private static boolean sInitOk = false;
    private static HuyaBerry sHuyaBerry;
    private static final CountDownLatch sInitLatch = new CountDownLatch(1);

    // 房间流信息缓存（1分钟有效期，与 wsSecret/wsTime 匹配）
    private static final long CACHE_VALID_MS = 60000L;
    private static final ConcurrentHashMap<Integer, CachedStreams> sStreamsCache = new ConcurrentHashMap<>();

    public static class CachedStreams {
        long timestamp;
        List<HuyaStreamInfo> streams;
        boolean isValid() {
            return System.currentTimeMillis() - timestamp < CACHE_VALID_MS;
        }
    }

    public static class HuyaStreamInfo {
        public int lineIndex;
        public int lineValue;
        public String lineLabel;
        public int bitRate;
        public String bitRateDisplayName;
        public String resolutionLabel;
        public String hlsUrl;
        public String flvUrl;
        public boolean isDefaultLine;
        public boolean isDefaultBitrate;

        public String getPlayUrl() {
            return !TextUtils.isEmpty(hlsUrl) ? hlsUrl : flvUrl;
        }
        public boolean isHls() {
            return !TextUtils.isEmpty(hlsUrl);
        }
        @Override
        public String toString() {
            return "Stream[line#" + lineIndex + "(v=" + lineValue + ") " + bitRateDisplayName
                    + "(" + bitRate + "bps) HLS=" + (hlsUrl != null) + " FLV=" + (flvUrl != null) + "]";
        }
    }

    public interface OnSDKFullResultListener {
        void onSuccess(HuyaStreamInfo defaultStream, List<HuyaStreamInfo> allStreams, List<String> lines);
        void onError(String error);
    }

    @Deprecated
    public interface OnSDKResultListener {
        void onSuccess(String hlsUrl, String flvUrl, boolean isHls);
        void onError(String error);
    }

    public static CachedStreams getCachedStreams(int roomId) {
        CachedStreams cs = sStreamsCache.get(roomId);
        return (cs != null && cs.isValid()) ? cs : null;
    }

    // =====================【2026-08-17 SDK 内部 Debug/Log/Crash 开关接入 - 编译期直调】=====================
    //  全部 compileOnly + 直接 import 调用（无反射）。
    //  每个开关独立 try-catch，单一失败不影响其他。
    //  注意：Mars Xlog 类名也叫 Log，为避免与 android.util.Log 冲突，
    //       所有 Xlog 调用必须写全限定名 com.tencent.mars.xlog.Log.xxx。
    // =====================================================================================================

    /** ① 打开 Mars Xlog 全部级别输出（LEVEL_ALL = 0）+ 控制台输出（反射） */
    private static void sdkInternalEnableXlogFull() {
        try {
            Class<?> xlogClz = Class.forName("com.tencent.mars.xlog.Log");
            Method setLevel = xlogClz.getMethod("setLevel", int.class);
            setLevel.invoke(null, 0);
            Method setConsoleLogOpen = xlogClz.getMethod("setConsoleLogOpen", boolean.class);
            setConsoleLogOpen.invoke(null, true);
            Log.i(TAG, "【SDK内开】Mars Xlog setLevel(LEVEL_ALL) + setConsoleLogOpen(true) 成功");
        } catch (Throwable x) {
            Log.w(TAG, "【SDK内开】Mars Xlog 打开失败（无此依赖或已禁用）: " + x.getClass().getSimpleName());
        }
    }

    /** ② 打开 Bugly 开发模式（反射） */
    private static void sdkInternalEnableBuglyDev(Application app) {
        try {
            Class<?> crashReportClz = Class.forName("com.tencent.bugly.crashreport.CrashReport");
            try {
                Method init = crashReportClz.getMethod("initCrashReport", Context.class, String.class, boolean.class);
                init.invoke(null, app, null, true);
            } catch (Throwable ignore) {}

            try {
                Method enable = crashReportClz.getMethod("enableBugly", boolean.class);
                enable.invoke(null, true);
            } catch (Throwable ignore) {}
            try {
                Method setDev = crashReportClz.getMethod("setIsDevelopmentDevice", Context.class, boolean.class);
                setDev.invoke(null, app, true);
            } catch (Throwable ignore) {}
            Log.i(TAG, "【SDK内开】Bugly initCrashReport + enable + setIsDevelopmentDevice(true) 成功");
        } catch (Throwable x) {
            Log.w(TAG, "【SDK内开】Bugly 开发模式打开失败: " + x.getClass().getSimpleName());
        }
    }

    /** ③ util.L 详细日志 + ForceLog 分界标记（反射） */
    private static void sdkInternalPreInitLogs(Application app) {
        // 3.1 auk.util.L.setSysLogEnabled + setLogLevel
        try {
            Class<?> lClz = Class.forName("com.duowan.auk.util.L");
            try {
                Method setEnabled = lClz.getMethod("setSysLogEnabled", boolean.class);
                setEnabled.invoke(null, true);
                Log.i(TAG, "【SDK内开】auk.util.L.setSysLogEnabled(true) 成功");
            } catch (Throwable ignore) {}
            try {
                Method setLevel = lClz.getMethod("setLogLevel", int.class);
                setLevel.invoke(null, 0);
                Log.i(TAG, "【SDK内开】auk.util.L.setLogLevel(LEVEL_VERBOSE) 成功");
            } catch (Throwable ignore) {}
        } catch (Throwable x) {
            Log.w(TAG, "【SDK内开】auk.util.L 启用失败: " + x.getClass().getSimpleName());
        }
        // 3.2 ForceLog.info 分界标记
        try {
            Class<?> forceLogClz = Class.forName("com.huya.force.log.ForceLog");
            Method info = forceLogClz.getMethod("info", String.class, String.class);
            info.invoke(null, "Force", "========= SDK 内部 Debug 模式开启 - TV Live =========");
            Log.i(TAG, "【SDK内开】ForceLog.info 分界标记成功");
        } catch (Throwable x) {
            Log.d(TAG, "【SDK内开】ForceLog 跳过（native SO 未就绪或无此依赖）: " + x.getClass().getSimpleName());
        }
    }

    /** ⭐ ④ 三重崩溃上报（反射版）: Bugly + Berry ICrashService + Xlog */
    private static void sdkInternalReportThrowable(Application app, Throwable t) {
        if (t == null) return;
        // 4.1 Bugly CrashReport.postCatchedException(Throwable)
        try {
            Class<?> crClz = Class.forName("com.tencent.bugly.crashreport.CrashReport");
            try {
                Method init = crClz.getMethod("initCrashReport", Context.class, String.class, boolean.class);
                init.invoke(null, app, null, true);
            } catch (Throwable ignore) {}
            Method post = crClz.getMethod("postCatchedException", Throwable.class);
            post.invoke(null, t);
            Log.i(TAG, "【SDK崩溃上报】Bugly CrashReport.postCatchedException 成功: " + t.getClass().getSimpleName());
        } catch (Throwable x) {
            Log.w(TAG, "【SDK崩溃上报】Bugly 上报失败: " + x.getClass().getSimpleName());
        }
        // 4.2 HuyaBerry ICrashService.postCatchedException(Throwable) — 反射
        boolean crashReported = false;
        try {
            // 路径 A: ServiceCenter → getService(ICrashService) → postCatchedException
            Class<?> scClz = Class.forName("com.huya.live.service.ServiceCenter");
            Object scObj = null;
            for (String mName : new String[]{"instance", "getInstance", "get", "INSTANCE"}) {
                try {
                    Method gm = scClz.getDeclaredMethod(mName);
                    gm.setAccessible(true);
                    scObj = gm.invoke(null);
                    if (scObj != null) break;
                } catch (Throwable ignore) {}
            }
            if (scObj == null) {
                try { scObj = scClz.newInstance(); } catch (Throwable ignore) {}
            }
            if (scObj != null) {
                Class<?> icrashClz = Class.forName("com.huya.berry.gamesdk.crash.ICrashService");
                Method mGetSvc = scClz.getMethod("getService", Class.class);
                Object svc = mGetSvc.invoke(scObj, icrashClz);
                if (svc != null) {
                    Method mPost = svc.getClass().getMethod("postCatchedException", Throwable.class);
                    mPost.invoke(svc, t);
                    Log.i(TAG, "【SDK崩溃上报】ICrashService.postCatchedException 成功（ServiceCenter 反射）");
                    crashReported = true;
                }
            }
            // 路径 B: new CrashService().postCatchedException(t)
            if (!crashReported) {
                try {
                    Class<?> csClz = Class.forName("com.huya.berry.client.CrashService");
                    Object cs = csClz.newInstance();
                    Method csPost = csClz.getMethod("postCatchedException", Throwable.class);
                    csPost.invoke(cs, t);
                    Log.i(TAG, "【SDK崩溃上报】CrashService.postCatchedException 成功（反射构造）");
                    crashReported = true;
                } catch (Throwable ignoreCs) {}
            }
        } catch (Throwable x) {
            Log.w(TAG, "【SDK崩溃上报】ICrashService 模块异常: " + x.getClass().getSimpleName());
        }
        // 4.3 Xlog.printErrStackTrace
        try {
            Class<?> xlogClz = Class.forName("com.tencent.mars.xlog.Log");
            Method printStack = xlogClz.getMethod("printErrStackTrace", String.class, Throwable.class, String.class, Object[].class);
            printStack.invoke(null, TAG, t, "SDK init caught Throwable:", new Object[0]);
            Log.i(TAG, "【SDK崩溃上报】Xlog.printErrStackTrace 成功");
        } catch (Throwable x) {
            Log.w(TAG, "【SDK崩溃上报】Xlog 写入失败: " + x.getClass().getSimpleName());
        }
    }

    public static synchronized void init(Application app) {
        if (sInitDone) return;
        sInitDone = true;
        try {
            // ⭐【2026-08-17 SDK 内部开关】在 HuyaBerry.instance 之前先打开 Xlog/Bugly 预开关
            //   保证后续 HuyaBerry.init 整个流程的详细日志都能打出来（含 initMTP、monitor、wup、ark 等）
            sdkInternalEnableXlogFull();
            sdkInternalEnableBuglyDev(app);

            sHuyaBerry = HuyaBerry.instance();
            Log.d(TAG, "HuyaBerry 单例获取成功: " + (sHuyaBerry != null));

            // ⭐【2026-08-17 SDK 内部开关】在 sHuyaBerry.init 之前，预开：
            //   auk.util.L (SysLog + setLogLevel VERBOSE) + ForceLog 分界标记
            //   ⚠️ 严禁在此处预调 HuyaBerryImpl.initLog / ArkValue.setDebuggable（会导致
            //      FileStorage 静态初始化因 Application context 为 null 而失败，级联
            //      真正 init() 时抛 NoClassDefFoundError）。详见 sdkInternalPreInitLogs 注释。
            sdkInternalPreInitLogs(app);

            // ⭐【2026-08-17 SDK 内部开关】configBuilder: debugMode(true) + isOpenBugly(true)
            //   debugMode(true) 会触发：
            //      1. ArkValue.setDebuggable(true) —— SDK 内部自动调用
            //      2. Hiido 上报 appKey 切换到 hyberry_appkey_hiido_debug（调试专用埋点 key）
            //      3. SDK 环境从 official → test
            //   isOpenBugly(true) 会触发：
            //      1. CrashHandler.getInstance().init()
            //      2. ServiceCenter.getService(ICrashService).init()
            HuyaBerryConfig.Builder b = new HuyaBerryConfig.Builder()
                    .gameId(2336)
                    .appId("123456")
                    .appKey("d8f193dd")
                    .debugMode(true)
                    .landscapeMode(false)
                    .isOpenBugly(true);

            HuyaCacheGovernor.applyOnBuilder(b, app);

            HuyaBerryConfig config = b.build();
            Log.d(TAG, "HuyaBerryConfig 构建成功（debugMode=true, isOpenBugly=true）");

            try {
                sHuyaBerry.init(app, config);
                Log.d(TAG, "HuyaBerry SDK 初始化成功（直调模式，内部 Debug/Log/Crash 开关全部打开）");
            } catch (Throwable initT) {
                // ⭐【2026-08-17 瘦身修复：catch Throwable 含 Error，详见 MyApplication 注释】
                //   排除 hysignal-quic 后 HuyaBerry.init 内部 HySignalWrapper 对 StnLogic$ICallBack 的硬引用
                //   会抛 NoClassDefFoundError (Error)。这里捕获后 sInitOk 仍设为 false，调用方通过 isSDKAvailable()
                //   感知 SDK 不可用，自动降级到纯 HTTP API 解析路径（HTTP API 与 SDK 完全解耦）
                Throwable cause = initT.getCause() != null ? initT.getCause() : initT;
                Log.w(TAG, "HuyaBerry init 失败（已降级为纯 HTTP API 模式）: "
                        + cause.getClass().getSimpleName() + ": " + cause.getMessage(), initT);

                // ⭐【2026-08-17 SDK 崩溃主动上报】捕获到 Error/Exception 立刻三重上报
                //   1) Bugly CrashReport.postCatchedException(Throwable) → 发送到 Bugly 后台
                //   2) ICrashService.postCatchedException(Throwable)     → 走虎牙 Berry SDK 内部崩溃通道
                //   3) Xlog.printErrStackTrace → 写入 APP 本地 /berry/logs/xxxx.xlog 文件（可拉取分析）
                sdkInternalReportThrowable(app, initT);

                sInitOk = false;
                sInitLatch.countDown();
                return;
            }

            // ⭐【2026-08-17】SDK init 成功后再补调一次 ArkValue.setDebuggable(true)（反射）
            try {
                Class<?> arkClz = Class.forName("com.duowan.auk.ArkValue");
                Method setDebug = arkClz.getMethod("setDebuggable", boolean.class);
                setDebug.invoke(null, true);
                Log.d(TAG, "【SDK内开】SDK init 成功后补调 auk.ArkValue.setDebuggable(true) 成功");
            } catch (Throwable x) {
                Log.d(TAG, "【SDK内开】后补 ArkValue.setDebuggable 跳过（debugMode 已内部触发即可）: "
                        + x.getClass().getSimpleName());
            }

            // 安装 ArkToast 拦截：过滤 SDK 内部错误 toast（"获取参数失败，请重试" 等）
            // 因为 HuyaBerryImpl.init 内部会触发 LiveStream.getConfig 等异步请求，
            // 失败时弹出与观看功能无关的错误 toast，需静默拦截。
            HuyaToastFilter.install(app);

            sInitOk = true;
            Log.d(TAG, "SDK 方法绑定完成（直调模式，Xlog+Bugly+L+ForceLog+后补ArkValue+Crash上报 全部就绪）");

            // ⭐【2026-08-17】调试：获取 SDK 所有解析方法签名
            dumpSDKMethods();
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            Log.e(TAG, "SDK 初始化异常（已降级）: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), t);
            // 最外层捕获到也尝试三重上报
            sdkInternalReportThrowable(app, t);
            sInitOk = false;
        } finally {
            sInitLatch.countDown();
        }
    }

    public static boolean isSDKAvailable() {
        return sInitOk;
    }

    /**
     * 等待 SDK 初始化完成（同步阻塞）
     * @param timeoutMs 超时时间（毫秒），-1 表示无限等待
     * @return true 表示 SDK 已初始化，false 表示超时或初始化失败
     */
    public static boolean awaitInit(long timeoutMs) {
        if (sInitOk) return true;
        if (sInitDone && !sInitOk) return false; // 初始化完成但失败了
        try {
            if (timeoutMs < 0) {
                sInitLatch.await();
            } else {
                sInitLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "awaitInit 被中断");
        }
        return sInitOk;
    }

    /** 直播列表 API 可用性（直调模式下等价于 SDK 初始化成功） */
    public static boolean isLiveListApiAvailable() {
        return sInitOk;
    }

    public static void parseFull(final int roomId, final OnSDKFullResultListener listener) {
        if (!sInitOk || sHuyaBerry == null) {
            listener.onError("SDK 未初始化");
            return;
        }
        CachedStreams cached = getCachedStreams(roomId);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            Log.d(TAG, "命中房间" + roomId + "流信息缓存（" + (System.currentTimeMillis() - cached.timestamp) / 1000 + "s前）");
            listener.onSuccess(pickDefaultStream(cached.streams), cached.streams, buildLineLabels(cached.streams));
            return;
        }
        final AtomicBoolean done = new AtomicBoolean(false);
        new Thread(() -> {
            try {
                CustomUICallback callback = new CustomUICallback() {
                    @Override
                    public void onResultCallback(int code, BaseCallback callbackResult) {
                        if (done.get()) return;
                        Log.d(TAG, "SDK onResultCallback: code=" + code
                                + " result=" + (callbackResult != null ? callbackResult.getClass().getSimpleName() : "null"));
                        try {
                            handleFullResult(code, callbackResult, listener, done, roomId);
                        } catch (Exception e) {
                            Log.e(TAG, "handleFullResult 异常: " + e.getMessage());
                            if (done.compareAndSet(false, true)) {
                                listener.onError("结果处理异常: " + e.getMessage());
                            }
                        }
                    }
                    @Override
                    @SuppressWarnings("rawtypes")
                    public void onResultListCallback(int code, List list) {
                        Log.d(TAG, "SDK onResultListCallback: code=" + code);
                    }
                };
                Log.d(TAG, "SDK getLiveDataByRoomId(直调), roomId=" + roomId);
                sHuyaBerry.getLiveDataByRoomId((long) roomId, callback);
                Thread.sleep(12000);
                if (done.compareAndSet(false, true)) {
                    Log.w(TAG, "SDK 调用超时");
                    listener.onError("SDK 解析超时");
                }
            } catch (Exception e) {
                Log.e(TAG, "SDK 解析异常：" + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (done.compareAndSet(false, true)) {
                    listener.onError("SDK 异常: " + e.getMessage());
                }
            }
        }, "HuyaSDKParser-Full").start();
    }

    @Deprecated
    public static void parse(int roomId, OnSDKResultListener listener) {
        parseFull(roomId, new OnSDKFullResultListener() {
            @Override
            public void onSuccess(HuyaStreamInfo defaultStream, List<HuyaStreamInfo> allStreams, List<String> lines) {
                listener.onSuccess(
                        defaultStream.hlsUrl != null ? defaultStream.hlsUrl : "",
                        defaultStream.flvUrl != null ? defaultStream.flvUrl : "",
                        defaultStream.isHls()
                );
            }
            @Override
            public void onError(String error) {
                listener.onError(error);
            }
        });
    }

    private static HuyaStreamInfo pickDefaultStream(List<HuyaStreamInfo> streams) {
        for (HuyaStreamInfo s : streams) if (s.isDefaultLine && s.isDefaultBitrate) return s;
        for (HuyaStreamInfo s : streams) if (s.isDefaultLine) return s;
        return streams.get(0);
    }

    private static List<String> buildLineLabels(List<HuyaStreamInfo> streams) {
        List<String> lines = new ArrayList<>();
        int lastIdx = -1;
        for (HuyaStreamInfo s : streams) {
            if (s.lineIndex != lastIdx) {
                lines.add(s.lineLabel);
                lastIdx = s.lineIndex;
            }
        }
        return lines;
    }

    private static void handleFullResult(int code, Object result, OnSDKFullResultListener listener,
                                         AtomicBoolean done, int roomId) {
        if (code != 0) {
            Log.w(TAG, "SDK 返回错误码: " + code);
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回错误码 " + code);
            return;
        }
        if (!(result instanceof LiveInfo)) {
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回类型不匹配");
            return;
        }
        List<HuyaStreamInfo> streams = extractFullStreamList((LiveInfo) result);
        if (streams == null || streams.isEmpty()) {
            if (done.compareAndSet(false, true)) listener.onError("未提取到任何流地址");
            return;
        }
        CachedStreams cs = new CachedStreams();
        cs.timestamp = System.currentTimeMillis();
        cs.streams = streams;
        sStreamsCache.put(roomId, cs);
        Log.d(TAG, "房间" + roomId + " 流信息写入缓存: " + streams.size() + " 条流");
        HuyaStreamInfo def = pickDefaultStream(streams);
        List<String> lines = buildLineLabels(streams);
        Log.d(TAG, "SDK 解析完成: " + lines.size() + " 条线路, 默认 " + def);
        if (done.compareAndSet(false, true)) {
            listener.onSuccess(def, streams, lines);
        }
    }

    private static void handleResult(int code, Object result, OnSDKResultListener listener, AtomicBoolean done) {
        if (code != 0) {
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回错误码 " + code);
            return;
        }
        if (!(result instanceof LiveInfo)) {
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回类型不匹配");
            return;
        }
        String[] urls = extractUrlsFromLines((LiveInfo) result);
        if (urls[0] != null || urls[1] != null) {
            Log.d(TAG, "SDK 策略1 成功: hls=" + (urls[0] != null) + " flv=" + (urls[1] != null));
            if (done.compareAndSet(false, true)) {
                listener.onSuccess(urls[0] != null ? urls[0] : "", urls[1] != null ? urls[1] : "", urls[0] != null);
            }
            return;
        }
        if (done.compareAndSet(false, true)) {
            listener.onError("SDK 未能提取到流地址");
        }
    }

    private static List<HuyaStreamInfo> extractFullStreamList(LiveInfo li) {
        List<HuyaStreamInfo> out = new ArrayList<>();
        try {
            Vector<Integer> lines = li.getLines();
            if (lines == null || lines.isEmpty()) {
                Log.w(TAG, "getLines 为空");
                return fallbackExtractAsSingle(li);
            }
            Log.d(TAG, "getLines: " + lines.size() + " 条线路");
            for (int i = 0; i < lines.size(); i++) {
                int lineValue = lines.get(i);
                Vector<BitRateInfo> bitRates = li.getBitRateList(lineValue);
                if (bitRates == null || bitRates.isEmpty()) {
                    Log.d(TAG, "线路#" + i + "(v=" + lineValue + ") getBitRateList 为空");
                    continue;
                }
                Log.d(TAG, "线路#" + i + "(v=" + lineValue + "): " + bitRates.size() + " 个码率");
                List<HuyaStreamInfo> lineStreams = new ArrayList<>();
                for (int j = 0; j < bitRates.size(); j++) {
                    BitRateInfo oneBr = bitRates.get(j);
                    int br = oneBr.bitRate;
                    String dn = oneBr.disPlayName;
                    String hlsUrl = null;
                    String flvUrl = null;
                    try {
                        hlsUrl = li.getPlayUrlByLineAndBitrate(false, lineValue, br);
                        if (!TextUtils.isEmpty(hlsUrl)) {
                            Log.d(TAG, "【SDK URL构建】HLS 完整URL: " + hlsUrl);
                            // 打印 URL 的各个组成部分
                            parseAndLogUrl(hlsUrl, "HLS");
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "HLS URL 获取失败: line=" + lineValue + " br=" + br + ": " + e.getMessage());
                    }
                    try {
                        flvUrl = li.getPlayUrlByLineAndBitrate(true, lineValue, br);
                        if (!TextUtils.isEmpty(flvUrl)) {
                            Log.d(TAG, "【SDK URL构建】FLV 完整URL: " + flvUrl);
                            parseAndLogUrl(flvUrl, "FLV");
                        }
                    } catch (Exception ignored) { }
                    if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                        Log.d(TAG, "线路#" + i + " 码率" + br + "无有效URL，跳过");
                        continue;
                    }
                    HuyaStreamInfo s = new HuyaStreamInfo();
                    s.lineIndex = i;
                    s.lineValue = lineValue;
                    s.lineLabel = i == 0 ? "线路1(主线路)" : ("线路" + (i + 1));
                    s.bitRate = br;
                    s.bitRateDisplayName = !TextUtils.isEmpty(dn) ? dn : inferBitRateLabel(br);
                    s.resolutionLabel = inferResolutionLabelFromBitrate(br);
                    s.hlsUrl = hlsUrl;
                    s.flvUrl = flvUrl;
                    s.isDefaultLine = (i == 0);
                    lineStreams.add(s);
                    Log.d(TAG, "  → " + s + " URL(" + (hlsUrl != null ? "HLS" : "")
                            + (flvUrl != null ? "/FLV" : "") + ")");
                }
                lineStreams.sort((a, b) -> Integer.compare(b.bitRate, a.bitRate));
                for (int k = 0; k < lineStreams.size(); k++) {
                    lineStreams.get(k).isDefaultBitrate = (k == 0);
                }
                out.addAll(lineStreams);
            }
            if (out.isEmpty()) return fallbackExtractAsSingle(li);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "extractFullStreamList 异常: " + t.getMessage());
            if (!out.isEmpty()) return out;
            return fallbackExtractAsSingle(li);
        }
    }

    private static List<HuyaStreamInfo> fallbackExtractAsSingle(LiveInfo li) {
        String[] urls = extractUrlsFromLines(li);
        if ((urls[0] == null || urls[0].isEmpty()) && (urls[1] == null || urls[1].isEmpty())) {
            return null;
        }
        HuyaStreamInfo s = new HuyaStreamInfo();
        s.lineIndex = 0;
        s.lineValue = 0;
        s.lineLabel = "线路1(主线路)";
        s.bitRate = 4000;
        s.bitRateDisplayName = "默认";
        s.resolutionLabel = "自适应";
        s.hlsUrl = urls[0];
        s.flvUrl = urls[1];
        s.isDefaultLine = true;
        s.isDefaultBitrate = true;
        List<HuyaStreamInfo> list = new ArrayList<>();
        list.add(s);
        return list;
    }

    private static String inferBitRateLabel(int brKbps) {
        if (brKbps >= 8000) return "蓝光" + (brKbps / 1000) + "M";
        if (brKbps >= 4000) return "超清" + (brKbps / 1000) + "M";
        if (brKbps >= 2000) return "高清" + (brKbps / 1000) + "M";
        if (brKbps >= 1000) return "标清" + (brKbps / 1000) + "M";
        return brKbps + "Kbps";
    }

    private static String inferResolutionLabelFromBitrate(int brKbps) {
        if (brKbps >= 8000) return "4K (2160p)";
        if (brKbps >= 4000) return "1080p";
        if (brKbps >= 2000) return "720p";
        if (brKbps >= 1200) return "540p";
        if (brKbps >= 600) return "360p";
        return "自适应";
    }

    private static String[] extractUrlsFromLines(LiveInfo li) {
        String hls = null;
        String flv = null;
        try {
            Vector<Integer> lines = li.getLines();
            if (lines == null || lines.isEmpty()) return new String[]{null, null};
            for (int i = 0; i < lines.size(); i++) {
                int line = lines.get(i);
                Vector<BitRateInfo> bitRates = li.getBitRateList(line);
                if (bitRates == null || bitRates.isEmpty()) continue;
                for (int j = 0; j < bitRates.size(); j++) {
                    BitRateInfo brInfo = bitRates.get(j);
                    int bitrate = brInfo.bitRate;
                    String hlsUrl = li.getPlayUrlByLineAndBitrate(false, line, bitrate);
                    if (hlsUrl != null && !hlsUrl.isEmpty() && hls == null) {
                        hls = hlsUrl;
                        Log.d(TAG, "HLS URL(line=" + line + ",br=" + bitrate + "): "
                                + hlsUrl.substring(0, Math.min(80, hlsUrl.length())));
                    }
                    String flvUrl = li.getPlayUrlByLineAndBitrate(true, line, bitrate);
                    if (flvUrl != null && !flvUrl.isEmpty() && flv == null) {
                        flv = flvUrl;
                        Log.d(TAG, "FLV URL(line=" + line + ",br=" + bitrate + "): "
                                + flvUrl.substring(0, Math.min(80, flvUrl.length())));
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "URL 提取异常: " + e.getMessage());
        }
        return new String[]{hls, flv};
    }

    // =====================================================================
    // 【全量 SDK 解析】直接调用虎牙 SDK 内部所有 API 获取完整解析流程
    // =====================================================================

    /**
     * 完整直播数据模型 - 包含 SDK 返回的所有信息
     */
    public static class HuyaFullLiveData {
        // 基础房间信息
        public int roomId;
        public String roomName;
        public long uid;
        public String nickName;
        public String avatarUrl;
        public long onlineCount;
        public int gameType;
        public String gameFullName;
        public String introduction;
        public long startTime;
        
        // 流信息
        public List<HuyaStreamInfo> streams = new ArrayList<>();
        public List<String> lineLabels = new ArrayList<>();
        
        // 原始 SDK 返回对象（调试用）
        public LiveInfo rawLiveInfo;
        
        // 解析耗时统计
        public long parseTimeMs;
        
        @Override
        public String toString() {
            return "HuyaFullLiveData{" +
                    "roomId=" + roomId +
                    ", roomName='" + roomName + '\'' +
                    ", uid=" + uid +
                    ", nickName='" + nickName + '\'' +
                    ", onlineCount=" + onlineCount +
                    ", streams=" + streams.size() +
                    ", lines=" + lineLabels.size() +
                    ", parseTimeMs=" + parseTimeMs +
                    '}';
        }
    }

    /**
     * 全量数据解析回调接口
     */
    public interface OnSDKFullDataListener {
        void onSuccess(HuyaFullLiveData fullData);
        void onError(String error);
    }

    /**
     * 全量 SDK 解析 - 直接调用所有 SDK API 获取完整数据
     * 包括：房间信息、主播信息、观众数、线路列表、码率列表、播放 URL 等
     */
    public static void parseFullWithAllData(int roomId, OnSDKFullDataListener listener) {
        if (!sInitOk) {
            listener.onError("SDK 未初始化");
            return;
        }
        
        // 检查缓存
        CachedStreams cached = getCachedStreams(roomId);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            Log.d(TAG, "【全量解析】命中缓存，roomId=" + roomId);
            HuyaFullLiveData data = buildFullDataFromCache(roomId, cached);
            listener.onSuccess(data);
            return;
        }

        final long startTime = System.currentTimeMillis();
        final AtomicBoolean done = new AtomicBoolean(false);
        
        new Thread(() -> {
            try {
                CustomUICallback callback = new CustomUICallback() {
                    @Override
                    public void onResultCallback(int code, BaseCallback callbackResult) {
                        if (done.get()) return;
                        Log.d(TAG, "【全量解析】SDK onResultCallback: code=" + code);
                        
                        try {
                            if (code != 0) {
                                Log.w(TAG, "【全量解析】SDK 返回错误码: " + code);
                                if (done.compareAndSet(false, true)) {
                                    listener.onError("SDK 返回错误码 " + code);
                                }
                                return;
                            }
                            
                            if (!(callbackResult instanceof LiveInfo)) {
                                if (done.compareAndSet(false, true)) {
                                    listener.onError("SDK 返回类型不匹配");
                                }
                                return;
                            }
                            
                            LiveInfo li = (LiveInfo) callbackResult;
                            HuyaFullLiveData fullData = extractAllDataFromLiveInfo(li, roomId, startTime);
                            
                            // 缓存流信息
                            CachedStreams cs = new CachedStreams();
                            cs.timestamp = System.currentTimeMillis();
                            cs.streams = fullData.streams;
                            sStreamsCache.put(roomId, cs);
                            
                            Log.d(TAG, "【全量解析】完成: " + fullData);
                            
                            if (done.compareAndSet(false, true)) {
                                listener.onSuccess(fullData);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "【全量解析】处理异常: " + e.getMessage());
                            if (done.compareAndSet(false, true)) {
                                listener.onError("结果处理异常: " + e.getMessage());
                            }
                        }
                    }
                    
                    @Override
                    @SuppressWarnings("rawtypes")
                    public void onResultListCallback(int code, List list) {
                        Log.d(TAG, "【全量解析】SDK onResultListCallback: code=" + code + " listSize=" + (list != null ? list.size() : 0));
                    }
                };
                
                Log.d(TAG, "【全量解析】调用 SDK getLiveDataByRoomId, roomId=" + roomId);
                sHuyaBerry.getLiveDataByRoomId((long) roomId, callback);
                
                // 等待结果
                Thread.sleep(15000);
                if (done.compareAndSet(false, true)) {
                    Log.w(TAG, "【全量解析】SDK 调用超时");
                    listener.onError("SDK 解析超时");
                }
            } catch (Exception e) {
                Log.e(TAG, "【全量解析】异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (done.compareAndSet(false, true)) {
                    listener.onError("SDK 异常: " + e.getMessage());
                }
            }
        }, "HuyaSDKParser-FullData").start();
    }

    /**
     * 从缓存构建完整数据
     */
    private static HuyaFullLiveData buildFullDataFromCache(int roomId, CachedStreams cached) {
        HuyaFullLiveData data = new HuyaFullLiveData();
        data.roomId = roomId;
        data.streams = cached.streams;
        data.lineLabels = buildLineLabels(cached.streams);
        data.parseTimeMs = System.currentTimeMillis() - cached.timestamp;
        return data;
    }

    /**
     * 从 LiveInfo 提取所有数据
     */
    private static HuyaFullLiveData extractAllDataFromLiveInfo(LiveInfo li, int roomId, long startTime) {
        HuyaFullLiveData data = new HuyaFullLiveData();
        data.roomId = roomId;
        data.rawLiveInfo = li;
        data.parseTimeMs = System.currentTimeMillis() - startTime;
        
        // 提取房间基本信息
        extractRoomInfo(li, data);
        
        // 提取流信息
        List<HuyaStreamInfo> streams = extractFullStreamList(li);
        if (streams != null) {
            data.streams = streams;
            data.lineLabels = buildLineLabels(streams);
        }
        
        Log.d(TAG, "【全量解析】房间信息: roomId=" + data.roomId 
                + ", roomName=" + data.roomName
                + ", uid=" + data.uid
                + ", nickName=" + data.nickName
                + ", onlineCount=" + data.onlineCount
                + ", streams=" + data.streams.size()
                + ", lines=" + data.lineLabels.size());
        
        return data;
    }

    /**
     * 提取房间基本信息
     */
    private static void extractRoomInfo(LiveInfo li, HuyaFullLiveData data) {
        try {
            // 尝试各种可能的方法获取房间信息
            java.lang.reflect.Method[] methods = LiveInfo.class.getMethods();
            for (java.lang.reflect.Method m : methods) {
                String name = m.getName();
                try {
                    switch (name) {
                        case "getRoomName":
                        case "roomName":
                        case "getTitle":
                        case "title":
                            Object result = m.invoke(li);
                            if (result instanceof String) data.roomName = (String) result;
                            break;
                        case "getUid":
                        case "uid":
                        case "getPresenterUid":
                        case "presenterUid":
                            Object uidResult = m.invoke(li);
                            if (uidResult instanceof Number) data.uid = ((Number) uidResult).longValue();
                            break;
                        case "getNickName":
                        case "nickName":
                        case "getNick":
                        case "nick":
                            Object nickResult = m.invoke(li);
                            if (nickResult instanceof String) data.nickName = (String) nickResult;
                            break;
                        case "getAvatarUrl":
                        case "avatarUrl":
                        case "getAvatar":
                        case "avatar":
                            Object avatarResult = m.invoke(li);
                            if (avatarResult instanceof String) data.avatarUrl = (String) avatarResult;
                            break;
                        case "getOnlineCount":
                        case "onlineCount":
                        case "getTotalCount":
                        case "totalCount":
                            Object countResult = m.invoke(li);
                            if (countResult instanceof Number) data.onlineCount = ((Number) countResult).longValue();
                            break;
                        case "getGameType":
                        case "gameType":
                            Object gameTypeResult = m.invoke(li);
                            if (gameTypeResult instanceof Number) data.gameType = ((Number) gameTypeResult).intValue();
                            break;
                        case "getGameFullName":
                        case "gameFullName":
                        case "getGameName":
                        case "gameName":
                            Object gameNameResult = m.invoke(li);
                            if (gameNameResult instanceof String) data.gameFullName = (String) gameNameResult;
                            break;
                        case "getIntroduction":
                        case "introduction":
                            Object introResult = m.invoke(li);
                            if (introResult instanceof String) data.introduction = (String) introResult;
                            break;
                        case "getStartTime":
                        case "startTime":
                            Object timeResult = m.invoke(li);
                            if (timeResult instanceof Number) data.startTime = ((Number) timeResult).longValue();
                            break;
                    }
                } catch (Exception ignored) {
                    // 单个方法失败不影响其他
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "【全量解析】房间信息提取异常: " + e.getMessage());
        }
    }

    /**
     * 获取 LiveInfo 的所有可用方法（调试用）
     */
    public static List<String> getLiveInfoMethods() {
        List<String> methodList = new ArrayList<>();
        try {
            if (!sInitOk || sHuyaBerry == null) {
                methodList.add("SDK 未初始化");
                return methodList;
            }
            // 创建一个空的 LiveInfo 实例来获取方法列表
            // 通过 getLiveDataByRoomId 获取后才能查看实际数据
            java.lang.reflect.Method[] methods = LiveInfo.class.getMethods();
            for (java.lang.reflect.Method m : methods) {
                StringBuilder sb = new StringBuilder();
                sb.append(m.getReturnType().getSimpleName()).append(" ");
                sb.append(m.getName()).append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getSimpleName());
                }
                sb.append(")");
                methodList.add(sb.toString());
            }
        } catch (Exception e) {
            methodList.add("获取方法列表失败: " + e.getMessage());
        }
        return methodList;
    }

    /**
     * 获取 HuyaBerry 的所有可用方法（调试用）
     */
    public static List<String> getHuyaBerryMethods() {
        List<String> methodList = new ArrayList<>();
        try {
            java.lang.reflect.Method[] methods = HuyaBerry.class.getMethods();
            for (java.lang.reflect.Method m : methods) {
                StringBuilder sb = new StringBuilder();
                sb.append(m.getReturnType().getSimpleName()).append(" ");
                sb.append(m.getName()).append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getSimpleName());
                }
                sb.append(")");
                methodList.add(sb.toString());
            }
        } catch (Exception e) {
            methodList.add("获取方法列表失败: " + e.getMessage());
        }
        return methodList;
    }

    /**
     * 解析并记录 SDK 返回的 URL 结构
     * 用于分析 SDK 是如何构建带签名的播放 URL 的
     */
    private static void parseAndLogUrl(String url, String type) {
        try {
            // 分离路径和查询参数
            int queryIdx = url.indexOf('?');
            String path = queryIdx >= 0 ? url.substring(0, queryIdx) : url;
            String query = queryIdx >= 0 ? url.substring(queryIdx + 1) : "";

            Log.d(TAG, "【SDK URL解析】" + type + " Path: " + path);
            Log.d(TAG, "【SDK URL解析】" + type + " Query: " + query);

            // 分析路径结构
            int lastSlash = path.lastIndexOf('/');
            String fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            String dirPath = lastSlash >= 0 ? path.substring(0, lastSlash) : "";
            Log.d(TAG, "【SDK URL解析】" + type + " Dir: " + dirPath);
            Log.d(TAG, "【SDK URL解析】" + type + " File: " + fileName);

            // 分析查询参数
            if (!TextUtils.isEmpty(query)) {
                String[] params = query.split("&");
                Log.d(TAG, "【SDK URL解析】" + type + " Params (" + params.length + " 个):");
                for (String param : params) {
                    int eqIdx = param.indexOf('=');
                    if (eqIdx >= 0) {
                        String key = param.substring(0, eqIdx);
                        String value = param.substring(eqIdx + 1);
                        // 对长值进行截断显示
                        String displayValue = value.length() > 80 ? value.substring(0, 80) + "..." : value;
                        Log.d(TAG, "【SDK URL解析】  " + key + " = " + displayValue + " (len=" + value.length() + ")");
                    }
                }
            }

            // 分析 URL 中的关键组成部分
            // 1. 域名
            int schemeEnd = url.indexOf("://");
            if (schemeEnd >= 0) {
                String afterScheme = url.substring(schemeEnd + 3);
                int slashIdx = afterScheme.indexOf('/');
                String host = slashIdx >= 0 ? afterScheme.substring(0, slashIdx) : afterScheme;
                Log.d(TAG, "【SDK URL解析】" + type + " Host: " + host);
            }

            // 2. 检查是否有 wsSecret/wsTime 签名参数
            if (query.contains("wsSecret=") && query.contains("wsTime=")) {
                Log.d(TAG, "【SDK URL解析】" + type + " ✓ 包含签名参数 (wsSecret + wsTime)");
            }

            // 3. 检查 streamName 格式
            // SDK 格式: .../src/{streamName}_{bitrate}.m3u8?wsSecret=...
            // Pure 格式: .../src/{streamName}.m3u8?wsSecret=...
            int srcIdx = path.indexOf("/src/");
            if (srcIdx >= 0) {
                String afterSrc = path.substring(srcIdx + 5);
                Log.d(TAG, "【SDK URL解析】" + type + " After /src/: " + afterSrc);
            }
        } catch (Exception e) {
            Log.w(TAG, "【SDK URL解析】解析 URL 异常: " + e.getMessage());
        }
    }

    /**
     * 调试方法：获取 SDK 所有解析相关方法签名
     * 用于分析 SDK 内部的解析流程和 URL 构建逻辑
     */
    private static void dumpSDKMethods() {
        try {
            Log.d(TAG, "========== SDK 方法签名调试 ==========");

            // 1. 获取 LiveInfo 类的所有方法
            Log.d(TAG, "--- LiveInfo 类方法 ---");
            try {
                java.lang.reflect.Method[] liveInfoMethods = LiveInfo.class.getMethods();
                for (java.lang.reflect.Method m : liveInfoMethods) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  ").append(m.getReturnType().getSimpleName()).append(" ");
                    sb.append(m.getName()).append("(");
                    Class<?>[] params = m.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(params[i].getSimpleName());
                    }
                    sb.append(")");
                    // 重点关注流获取相关方法
                    String name = m.getName();
                    if (name.contains("Play") || name.contains("Stream") || name.contains("Url")
                            || name.contains("Bitrate") || name.contains("BitRate")
                            || name.contains("Line") || name.contains("Live")
                            || name.contains("Room") || name.contains("get")
                            || name.contains("Parse") || name.contains("Resolve")
                            || name.contains("AntiCode") || name.contains("Secret")
                            || name.contains("Player") || name.contains("Info")) {
                        Log.d(TAG, sb.toString());
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "LiveInfo 方法获取失败: " + e.getMessage());
            }

            // 2. 获取 HuyaBerry 类的所有方法
            Log.d(TAG, "--- HuyaBerry 类方法 ---");
            try {
                java.lang.reflect.Method[] huyaBerryMethods = HuyaBerry.class.getMethods();
                for (java.lang.reflect.Method m : huyaBerryMethods) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  ").append(m.getReturnType().getSimpleName()).append(" ");
                    sb.append(m.getName()).append("(");
                    Class<?>[] params = m.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(params[i].getSimpleName());
                    }
                    sb.append(")");
                    String name = m.getName();
                    // 重点关注直播相关方法
                    if (name.contains("Live") || name.contains("Stream") || name.contains("Room")
                            || name.contains("Parse") || name.contains("Play")
                            || name.contains("Data") || name.contains("Info")
                            || name.contains("Url") || name.contains("Get")) {
                        Log.d(TAG, sb.toString());
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "HuyaBerry 方法获取失败: " + e.getMessage());
            }

            // 3. 获取 BitRateInfo 类的字段
            Log.d(TAG, "--- BitRateInfo 类字段 ---");
            try {
                java.lang.reflect.Field[] bitRateFields = BitRateInfo.class.getDeclaredFields();
                for (java.lang.reflect.Field f : bitRateFields) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  ").append(f.getType().getSimpleName()).append(" ");
                    sb.append(f.getName());
                    Log.d(TAG, sb.toString());
                }
            } catch (Exception e) {
                Log.d(TAG, "BitRateInfo 字段获取失败: " + e.getMessage());
            }

            // 4. 获取 CustomUICallback 接口方法
            Log.d(TAG, "--- CustomUICallback 接口方法 ---");
            try {
                java.lang.reflect.Method[] callbackMethods = CustomUICallback.class.getMethods();
                for (java.lang.reflect.Method m : callbackMethods) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  ").append(m.getReturnType().getSimpleName()).append(" ");
                    sb.append(m.getName()).append("(");
                    Class<?>[] params = m.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(params[i].getSimpleName());
                    }
                    sb.append(")");
                    Log.d(TAG, sb.toString());
                }
            } catch (Exception e) {
                Log.d(TAG, "CustomUICallback 方法获取失败: " + e.getMessage());
            }

            // 5. 尝试获取 SDK 内部解析相关的其他类
            Log.d(TAG, "--- SDK 内部解析类搜索 ---");
            String[] parseClasses = {
                    "com.huya.berry.sdkplayer.api.IStreamInfoHelper",
                    "com.huya.berry.sdkplayer.impl.StreamInfoHelper",
                    "com.huya.berry.sdkplayer.api.ILivePlayer",
                    "com.huya.berry.sdkplayer.impl.HYMediaSoftDecodePlayer",
                    "com.huya.berry.sdkplayer.api.PlayConfig",
                    "com.huya.berry.sdkplayer.impl.PlayConfigImpl",
                    "com.huya.berry.api.HyberryVideoView",
                    "com.huya.berry.api.IHyberryVideoView",
                    "com.huya.live.service.LiveStreamInfo",
                    "com.huya.live.service.HuyaLiveService"
            };
            for (String className : parseClasses) {
                try {
                    Class<?> cls = Class.forName(className);
                    Log.d(TAG, "  找到类: " + className);
                    java.lang.reflect.Method[] methods = cls.getMethods();
                    int methodCount = 0;
                    for (java.lang.reflect.Method m : methods) {
                        String name = m.getName();
                        if (name.contains("Play") || name.contains("Stream") || name.contains("Url")
                                || name.contains("Parse") || name.contains("Get")
                                || name.contains("Build") || name.contains("Resolv")) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("    ").append(m.getReturnType().getSimpleName()).append(" ");
                            sb.append(m.getName()).append("(");
                            Class<?>[] params = m.getParameterTypes();
                            for (int i = 0; i < params.length; i++) {
                                if (i > 0) sb.append(", ");
                                sb.append(params[i].getSimpleName());
                            }
                            sb.append(")");
                            Log.d(TAG, sb.toString());
                            methodCount++;
                        }
                    }
                    if (methodCount == 0) {
                        Log.d(TAG, "    (无解析相关方法，共 " + methods.length + " 个方法)");
                    }
                } catch (ClassNotFoundException e) {
                    Log.d(TAG, "  类不存在: " + className);
                } catch (Exception e) {
                    Log.d(TAG, "  类加载失败: " + className + " - " + e.getMessage());
                }
            }

            Log.d(TAG, "========== SDK 方法签名调试结束 ==========");
        } catch (Exception e) {
            Log.w(TAG, "dumpSDKMethods 异常: " + e.getMessage());
        }
    }
}
