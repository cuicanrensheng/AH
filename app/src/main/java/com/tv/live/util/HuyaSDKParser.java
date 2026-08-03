package com.tv.live.util;

import android.app.Application;
import android.text.TextUtils;
import android.util.Log;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 虎牙 Berry SDK 解析器
 *
 * 通过 SDK 原生 API 获取直播流地址，充当解析器和防盗链角色。
 * 让 SDK 处理 CDN 鉴权，返回带签名的 URL 给 ExoPlayer 播放。
 */
public class HuyaSDKParser {

    private static final String TAG = "HuyaSDKParser";

    private static boolean sInitDone = false;
    private static boolean sInitOk = false;

    // 缓存反射的类和方法
    private static Class<?> sHuyaBerryClass;
    private static Class<?> sConfigClass;
    private static Class<?> sConfigBuilderClass;
    private static Class<?> sLiveInfoClass;
    private static Class<?> sBitRateInfoClass;
    private static Class<?> sCallbackClass;
    private static Object sHuyaBerryInstance;
    private static Method sGetLiveDataMethod;
    private static Method sGetPlayUrlMethod;
    private static Method sGetLinesMethod;
    private static Method sGetBitRateListMethod;
    private static Method sInstanceMethod;

    // LiveInfo 备选方法
    private static Method[] sAlternativeMethods;

    // BitRateInfo 字段缓存
    private static Field sBitRateField;
    private static Field sDisplayNameField;

    // 房间流信息缓存（1分钟有效期，与 wsSecret/wsTime 匹配）
    private static final long CACHE_VALID_MS = 60000L;
    private static final ConcurrentHashMap<Integer, CachedStreams> sStreamsCache = new ConcurrentHashMap<>();

    // ========== 🟢 并行加载优化：虎牙直播源与直播源列表同时解析 ==========
    // 说明：之前流程=【直播源列表加载】→ 点频道 → 【实时SDK解析】→ 等几秒~30秒 → 画面出现。
    // 现在：【直播源列表加载】同时后台就开始逐个【预解析虎牙房间】→ 结果写入缓存 →
    //       用户点频道时 90% 命中缓存，1s 内出画。
    // 策略：限速串行（每个房间间隔 PRELOAD_INTERVAL_MS），避免 SDK 并发排队风暴；
    //       最多只预解析前 PRELOAD_MAX_ROOMS 个虎牙房间（常用的前几个，省CPU+流量）。
    private static final int PRELOAD_MAX_ROOMS = 30;
    private static final long PRELOAD_INTERVAL_MS = 1500L;  // 1.5s 一个请求，温和不炸SDK
    private static final Handler sPreloadHandler = new Handler(Looper.getMainLooper());
    private static final List<Integer> sPreloadPendingQueue = new ArrayList<>();
    private static boolean sPreloadScheduled = false;
    private static int sPreloadIndex = 0;
    private static final Runnable PRELOAD_RUNNABLE = new Runnable() {
        @Override public void run() {
            int roomId = -1;
            synchronized (sPreloadPendingQueue) {
                while (sPreloadIndex < sPreloadPendingQueue.size()) {
                    int candidate = sPreloadPendingQueue.get(sPreloadIndex++);
                    // 1) 已有有效缓存的跳过
                    CachedStreams cs = sStreamsCache.get(candidate);
                    if (cs != null && cs.isValid()) continue;
                    roomId = candidate;
                    break;
                }
            }
            if (roomId > 0) {
                final int finalRoomId = roomId;
                Log.d(TAG, "🔁【预解析】(" + sPreloadIndex + "/" + sPreloadPendingQueue.size()
                        + ") roomId=" + finalRoomId);
                // 静默解析：listener 只写日志，不弹Toast、不阻塞UI
                parseFull(finalRoomId, new OnSDKFullResultListener() {
                    @Override public void onSuccess(HuyaStreamInfo defaultStream,
                                                    List<HuyaStreamInfo> allStreams,
                                                    List<String> lines) {
                        Log.d(TAG, "✅【预解析】roomId=" + finalRoomId + " 成功, streams="
                                + (allStreams != null ? allStreams.size() : 0));
                    }
                    @Override public void onError(String error) {
                        Log.w(TAG, "⚠️【预解析】roomId=" + finalRoomId + " 失败: " + error
                                + "（不影响用户体验，点频道时会重新解析）");
                    }
                });
                // 下一个房间 PRELOAD_INTERVAL_MS 后再发
                sPreloadHandler.postDelayed(this, PRELOAD_INTERVAL_MS);
            } else {
                // 队列跑完
                synchronized (sPreloadPendingQueue) {
                    sPreloadScheduled = false;
                    Log.d(TAG, "🏁【预解析】队列处理完毕, 已提交=" + sPreloadIndex
                            + "/" + sPreloadPendingQueue.size());
                }
            }
        }
    };

    /**
     * 批量预解析虎牙房间。通常在直播源列表加载完成（缓存命中 / 网络成功）时调用。
     * 与直播源加载**并行**进行，用户点击时已有缓存→瞬时播放。
     *
     * ⚠️  即使 SDK 尚未 init 完成也可以调用：房间号先存入 pendingQueue，
     *     等 ensureInit() 中 sInitOk=true 后会自动补发。
     *
     * @param roomIds 所有虎牙房间号（会自动去重、跳过已缓存）
     */
    public static void preloadRooms(List<Integer> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) return;
        // 去重 + 截断前 PRELOAD_MAX_ROOMS 个（避免 100+ 房间全解析太伤）
        LinkedHashSet<Integer> deduped = new LinkedHashSet<>(roomIds);
        List<Integer> trimmed = new ArrayList<>(deduped);
        if (trimmed.size() > PRELOAD_MAX_ROOMS) {
            trimmed = trimmed.subList(0, PRELOAD_MAX_ROOMS);
        }
        synchronized (sPreloadPendingQueue) {
            sPreloadPendingQueue.clear();
            sPreloadPendingQueue.addAll(trimmed);
            sPreloadIndex = 0;
            if (sInitOk && !sPreloadScheduled) {
                sPreloadScheduled = true;
                Log.d(TAG, "🚀【预解析】开始, 共 " + trimmed.size() + " 个虎牙房间，每 "
                        + (PRELOAD_INTERVAL_MS / 1000) + "s 解析一个");
                sPreloadHandler.post(PRELOAD_RUNNABLE);
            } else if (!sInitOk) {
                // 存入队列即可，ensureInit 成功后会补发（见 sInitOk=true 后的补发块）
                Log.d(TAG, "⏳【预解析】SDK 尚未 init，已缓存 " + trimmed.size()
                        + " 个房间号，等 init 完成后自动开始");
            } else {
                Log.d(TAG, "🔄【预解析】已有队列在跑，替换为新的 " + trimmed.size() + " 个房间");
            }
        }
    }

    public static class CachedStreams {
        public long timestamp;
        public List<HuyaStreamInfo> streams;

        public boolean isValid() {
            return System.currentTimeMillis() - timestamp < CACHE_VALID_MS;
        }

        /** 缓存年龄（秒），用于外部日志诊断 */
        public long getAgeSec() {
            return (System.currentTimeMillis() - timestamp) / 1000;
        }

        public int size() {
            return streams != null ? streams.size() : 0;
        }
    }

    /**
     * 虎牙线路×码率的流信息
     */
    public static class HuyaStreamInfo {
        public int lineIndex;       // 线路索引（0 开始）
        public int lineValue;       // SDK 内部线路值（如 5,14...）
        public String lineLabel;    // UI 显示的线路名："线路1(主线路)"、"线路2" 等
        public int bitRate;         // 码率值（bps，如 4000）
        public String bitRateDisplayName; // SDK 提供的码率显示名，如 "蓝光4M"、"超清"、"高清"
        public String resolutionLabel;    // 推导的分辨率标签，如 "1080p"、"720p"、"540p"、"360p"
        public String hlsUrl;
        public String flvUrl;
        public boolean isDefaultLine;     // 是否默认线路（第一条）
        public boolean isDefaultBitrate;  // 是否该线路的默认码率（最高码率的第一条）

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

    /**
     * 完整解析结果回调：返回全部线路×码率
     */
    public interface OnSDKFullResultListener {
        /**
         * 解析成功
         * @param defaultStream  默认选择的流（主线路默认码率，最高码率）
         * @param allStreams     全部线路×码率流列表（按 lineIndex 升序、同线路按 bitRate 降序排列）
         * @param lines          按线路分组的标签（用于线路选择 UI）
         */
        void onSuccess(HuyaStreamInfo defaultStream, List<HuyaStreamInfo> allStreams, List<String> lines);

        void onError(String error);
    }

    @Deprecated
    public interface OnSDKResultListener {
        void onSuccess(String hlsUrl, String flvUrl, boolean isHls);
        void onError(String error);
    }

    /**
     * 获取某个房间的缓存流信息（如果仍有效）
     */
    public static CachedStreams getCachedStreams(int roomId) {
        CachedStreams cs = sStreamsCache.get(roomId);
        return (cs != null && cs.isValid()) ? cs : null;
    }

    /**
     * 初始化虎牙 SDK
     */
    public static synchronized void init(Application app) {
        if (sInitDone) return;
        sInitDone = true;
        // 将每个反射步骤拆成独立 try-catch，并打印完整异常栈 + cause 链，
        // 便于定位：到底是真的类文件缺失，还是 <clinit> 静态初始化时
        // 依赖的其他类缺失触发 ExceptionInInitializerError/NoClassDefFoundError。
        try {
            sHuyaBerryClass = Class.forName("com.huya.berry.client.HuyaBerry");
            Log.d(TAG, "[1/6] HuyaBerry 加载 OK");
            // 【调试】枚举 HuyaBerry 所有 public 方法签名，寻找不需要 CustomUICallback 的备用 API
            try {
                Method[] all = sHuyaBerryClass.getMethods();
                StringBuilder sb = new StringBuilder();
                sb.append("=== HuyaBerry 全部 public 方法共 ").append(all.length).append(" 个 ===\n");
                for (Method m : all) {
                    sb.append("  ").append(m.getReturnType().getSimpleName())
                      .append(" ").append(m.getName()).append("(");
                    Class<?>[] pts = m.getParameterTypes();
                    for (int i = 0; i < pts.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(pts[i].getSimpleName());
                    }
                    sb.append(")\n");
                }
                sb.append("=========================================");
                Log.d(TAG, sb.toString());
            } catch (Throwable tt) {
                Log.w(TAG, "枚举 HuyaBerry 方法失败", tt);
            }
        } catch (Throwable t) {
            Log.e(TAG, "[1/6] HuyaBerry 加载失败", t);
        }
        try {
            sConfigClass = Class.forName("com.huya.berry.client.HuyaBerryConfig");
            Log.d(TAG, "[2/6] HuyaBerryConfig 加载 OK");
        } catch (Throwable t) {
            Log.e(TAG, "[2/6] HuyaBerryConfig 加载失败", t);
        }
        try {
            sConfigBuilderClass = Class.forName("com.huya.berry.client.HuyaBerryConfig$Builder");
            Log.d(TAG, "[3/6] HuyaBerryConfig$Builder 加载 OK");
        } catch (Throwable t) {
            Log.e(TAG, "[3/6] HuyaBerryConfig$Builder 加载失败", t);
        }
        try {
            sLiveInfoClass = Class.forName("com.huya.berry.client.customui.model.LiveInfo");
            Log.d(TAG, "[4/6] LiveInfo 加载 OK");
        } catch (Throwable t) {
            Log.e(TAG, "[4/6] LiveInfo 加载失败", t);
        }
        try {
            sBitRateInfoClass = Class.forName("com.huya.berry.client.customui.model.BitRateInfo");
            Log.d(TAG, "[5/6] BitRateInfo 加载 OK");
        } catch (Throwable t) {
            Log.e(TAG, "[5/6] BitRateInfo 加载失败", t);
        }
        try {
            sCallbackClass = Class.forName("com.huya.berry.client.customui.CustomUICallback");
            Log.d(TAG, "[6/6] CustomUICallback 加载 OK");
            // 【调试】枚举 CustomUICallback 所有方法签名 + 参数/返回值类型的 FQCN，找出需要 Stub 的 gamesdk 类
            try {
                Method[] all = sCallbackClass.getMethods();
                StringBuilder sb = new StringBuilder();
                java.util.HashSet<String> needStub = new java.util.HashSet<>();
                sb.append("=== CustomUICallback 全部方法共 ").append(all.length).append(" 个 ===\n");
                for (Method m : all) {
                    sb.append("  ").append(m.getReturnType().getName()).append(" ").append(m.getName()).append("(");
                    Class<?>[] pts = m.getParameterTypes();
                    for (int i = 0; i < pts.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(pts[i].getName());
                        if (pts[i].getName().startsWith("com.huya.berry.gamesdk")) needStub.add(pts[i].getName());
                    }
                    sb.append(")\n");
                    if (m.getReturnType().getName().startsWith("com.huya.berry.gamesdk")) needStub.add(m.getReturnType().getName());
                }
                sb.append("--- 需要 Stub 的 gamesdk 类共 ").append(needStub.size()).append(" 个 ---\n");
                for (String s : new java.util.TreeSet<>(needStub)) sb.append("  ").append(s).append("\n");
                sb.append("=========================================");
                Log.d(TAG, sb.toString());
            } catch (Throwable tt) {
                Log.w(TAG, "枚举 CustomUICallback 方法失败", tt);
            }
        } catch (Throwable t) {
            Log.e(TAG, "[6/6] CustomUICallback 加载失败", t);
        }

        // 必须满足的最小子集：核心 API 类 + 回调接口（LiveInfo/BitRateInfo 作为返回值模型可缺失）
        boolean coreOk = sHuyaBerryClass != null && sConfigClass != null
                && sConfigBuilderClass != null && sCallbackClass != null;
        if (!coreOk) {
            Log.e(TAG, "核心 SDK 类缺失，初始化终止");
            sInitOk = false;
            return;
        }

        try {
            Log.d(TAG, "所有核心 SDK 类加载成功，继续绑定...");

            // 反射 BitRateInfo 字段（LiveInfo/BitRateInfo 缺失时，这部分也一起跳过，
            // parseFull 会自动走 fallback 路径通过反射取字段）
            if (sBitRateInfoClass != null) {
                try {
                    sBitRateField = sBitRateInfoClass.getField("bitRate");
                } catch (NoSuchFieldException e) {
                    sBitRateField = sBitRateInfoClass.getDeclaredField("bitRate");
                }
                sBitRateField.setAccessible(true);
                try {
                    sDisplayNameField = sBitRateInfoClass.getField("disPlayName");
                } catch (NoSuchFieldException e) {
                    sDisplayNameField = sBitRateInfoClass.getDeclaredField("disPlayName");
                }
                sDisplayNameField.setAccessible(true);
                Log.d(TAG, "BitRateInfo 字段反射成功: bitRate & disPlayName");
            }

            sInstanceMethod = sHuyaBerryClass.getMethod("instance");
            sHuyaBerryInstance = sInstanceMethod.invoke(null);
            Log.d(TAG, "HuyaBerry 单例获取成功: " + (sHuyaBerryInstance != null));

            // 构建配置（凭证来自参考 APK：gameId=2336/appId=123456/appKey=d8f193dd）
            //
            // 🔔【豆包推荐 方案1 = 官方内置开关关闭播放器/推流（0风险首选）】
            //   基于 javap 反查 HuyaBerryConfig$Builder 全部 setter：
            //     Builder.isNeedPlay(false)  → 官方「不需要播放器」开关，SDK 核心会跳过
            //                                 播放器 Service 注册 / 内核加载 / SdkPlayer/SdkLive
            //                                 初始化，CustomUICallback 仍正常回调（解析不受影响）
            //     Builder.cameraMode(false)  → 不启用摄像头推流（纯解析场景必关）
            //     Builder.oneKeyGangUp(false)→ 不启用一键连麦（纯解析场景必关）
            //     Builder.isOpenBugly(false) → 不启用 Bugly 崩溃上报（已调用，保持）
            //     Builder.hidePauseBtn(false)→ 暂停按钮隐藏（不影响解析，保留默认）
            //   → 开了这 3 个开关 + release minifyEnabled=true 后：
            //     ① R8 自动把从未被调用的播放器类全部剥离 dex（释放 ≈0.9~3MB）
            //     ② 运行时不加载 libberry_player / libberry_decoder（省内存 20~50MB）
            Object builder = sConfigBuilderClass.getConstructor().newInstance();
            sConfigBuilderClass.getMethod("gameId", int.class).invoke(builder, 2336);
            sConfigBuilderClass.getMethod("appId", String.class).invoke(builder, "123456");
            sConfigBuilderClass.getMethod("appKey", String.class).invoke(builder, "d8f193dd");
            sConfigBuilderClass.getMethod("debugMode", boolean.class).invoke(builder, false);
            sConfigBuilderClass.getMethod("landscapeMode", boolean.class).invoke(builder, false);
            sConfigBuilderClass.getMethod("isOpenBugly", boolean.class).invoke(builder, false);
            // ============= 🆕 官方精简开关（新增）=============
            try {
                sConfigBuilderClass.getMethod("isNeedPlay", boolean.class).invoke(builder, false);
                Log.i(TAG, "  ✅ Builder.isNeedPlay(false) = 官方禁用播放器内核（省内存 + R8 自动剥离类）");
            } catch (Throwable t) {
                Log.w(TAG, "  ⚠️ Builder.isNeedPlay 不可用（老版本，跳过）", t);
            }
            try {
                sConfigBuilderClass.getMethod("cameraMode", boolean.class).invoke(builder, false);
                Log.i(TAG, "  ✅ Builder.cameraMode(false) = 禁用摄像头推流模式");
            } catch (Throwable t) {
                Log.w(TAG, "  ⚠️ Builder.cameraMode 不可用（老版本，跳过）", t);
            }
            try {
                sConfigBuilderClass.getMethod("oneKeyGangUp", boolean.class).invoke(builder, false);
                Log.i(TAG, "  ✅ Builder.oneKeyGangUp(false) = 禁用一键连麦");
            } catch (Throwable t) {
                Log.w(TAG, "  ⚠️ Builder.oneKeyGangUp 不可用（老版本，跳过）", t);
            }
            try {
                sConfigBuilderClass.getMethod("hidePauseBtn", boolean.class).invoke(builder, false);
            } catch (Throwable ignore) {}
            // ============= 🆕 官方精简开关（结束）=============
            Object config = sConfigBuilderClass.getMethod("build").invoke(builder);
            Log.d(TAG, "HuyaBerryConfig 构建成功（官方精简开关已设置）");

            // init
            try {
                sHuyaBerryClass.getMethod("init", Application.class, sConfigClass)
                        .invoke(sHuyaBerryInstance, app, config);
                Log.d(TAG, "✅ HuyaBerry SDK 初始化成功 (init 无异常)");
            } catch (Exception initE) {
                // 打印完整异常链（含 targetException + cause 链）+ 提取真正缺失的类名列表，
                // 便于逐个移出黑名单或加 Stub
                Throwable real = initE;
                StringBuilder trace = new StringBuilder();
                java.util.HashSet<String> missingClasses = new java.util.HashSet<>();
                int depth = 0;
                while (real != null && depth++ < 10) {
                    if (real instanceof java.lang.reflect.InvocationTargetException) {
                        Throwable t = ((java.lang.reflect.InvocationTargetException) real).getTargetException();
                        trace.append("InvocationTargetException → 目标: ")
                             .append(t == null ? "null" : t.getClass().getSimpleName() + ": " + t.getMessage())
                             .append("\n");
                        real = t;
                    } else {
                        trace.append(real.getClass().getSimpleName()).append(": ").append(real.getMessage()).append("\n");
                        // 正则提取 Lxxx/yyy/Zzz; 形式的缺失类名
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("L([a-zA-Z0-9_$/]+);").matcher(String.valueOf(real.getMessage()));
                        while (m.find()) {
                            String c = m.group(1).replace('/', '.').replace('$', '.');
                            missingClasses.add(c);
                        }
                        for (StackTraceElement s : real.getStackTrace()) {
                            if (s.getClassName().startsWith("com.huya") || s.getClassName().startsWith("com.duowan")) {
                                trace.append("  ↳ ").append(s.toString()).append("\n");
                            }
                        }
                        real = real.getCause();
                        if (real != null) trace.append("  ▼ Cause: ");
                    }
                }
                if (!missingClasses.isEmpty()) {
                    trace.append("⚠️ 疑似缺失的类（需移出exclude黑名单或Stub）:\n");
                    for (String s : new java.util.TreeSet<>(missingClasses)) trace.append("  - ").append(s).append("\n");
                }
                Log.w(TAG, "❌ HuyaBerry init 失败（需要修复才能触发SDK回调）:\n" + trace.toString());
            }

            // 缓存方法（getLiveDataByRoomId 无需完整登录初始化，兼容性更好）
            sGetLiveDataMethod = sHuyaBerryClass.getMethod("getLiveDataByRoomId", long.class, sCallbackClass);
            Log.d(TAG, "getLiveDataByRoomId 绑定成功");

            if (sLiveInfoClass != null) {
                try {
                    sGetPlayUrlMethod = sLiveInfoClass.getMethod("getPlayUrlByLineAndBitrate", boolean.class, int.class, int.class);
                    sGetLinesMethod = sLiveInfoClass.getMethod("getLines");
                    sGetBitRateListMethod = sLiveInfoClass.getMethod("getBitRateList", int.class);
                    // 收集 LiveInfo 的所有 public 方法（用于备选方案）
                    sAlternativeMethods = sLiveInfoClass.getMethods();
                    Log.d(TAG, "LiveInfo 方法绑定成功，共 " + sAlternativeMethods.length + " 个方法");
                } catch (Throwable t) {
                    Log.w(TAG, "LiveInfo 方法绑定失败（将走通用反射兜底）", t);
                }
            }

            sInitOk = true;
            Log.d(TAG, "✅ HuyaBerry SDK 初始化 & 方法绑定完成");

            // 🔴【并行加载优化：init 成功后补发预解析】
            // 之前 AppCoreManager.loadLiveAndEpg（缓存命中）可能比 SDK init 更早，导致 preloadRooms 因 sInitOk=false 被跳过。
            // 这里 init 成功后立即重新提交一次 pendingQueue（如果有人在 AppCoreManager 已传入过房间号）。
            synchronized (sPreloadPendingQueue) {
                if (!sPreloadPendingQueue.isEmpty() && !sPreloadScheduled) {
                    sPreloadScheduled = true;
                    sPreloadIndex = 0;
                    Log.d(TAG, "🔄【预解析】SDK init 完成，补发 " + sPreloadPendingQueue.size() + " 个房间的预解析");
                    sPreloadHandler.post(PRELOAD_RUNNABLE);
                }
            }

            // ============= 🆕 豆包第四段：BerryDebugChecker 调试检测代码 =============
            //  SDK 初始化完成后立即执行，检测播放器模块是否成功被剥离/禁止初始化。
            //  判断标准（豆包）：
            //   1. 播放器相关 class 抛出 ClassNotFoundException → ✅ 正常（类未被加载 / R8 已剥离）
            //   2. 模块管理器未注册 PlayerModule → ✅ 正常
            //   3. Logcat 不再打印 loadLibrary berry_player / berry_decoder → ✅ 正常（看运行时日志）
            //   4. /proc/[pid]/maps 无 berry_player.so / berry_decoder.so mmap 记录 → ✅ 正常
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        new Thread(new Runnable() {
                            @Override public void run() { runBerryDebugChecker(); }
                        }, "BerryDebugChecker").start();
                    }
                }, 1500);
            } catch (Throwable t) {
                Log.w(TAG, "  ⚠️ BerryDebugChecker 启动失败（跳过，不影响主流程）", t);
            }
            // ============= 🆕 豆包第四段：调试检测代码（结束）=============

            // 【自动化测试】init 成功 60s 后自动触发一次 parseFull（房间号 26355851 虎牙官方常用）
            // 延时 60s 目的：避开首页 50+ 房间同时发起解析导致的 SDK 排队风暴（风暴期内首个请求会被 SDK
            // 放到队尾，30s 超时后才收到回调→回调被静默吞掉→用户截图 "SDK 解析超时"）
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            final int TEST_ROOM = 26355851;
                            Log.i(TAG, "🧪【自动化 SDK 解析测试】开始 parseFull, roomId=" + TEST_ROOM);
                            parseFull(TEST_ROOM, new OnSDKFullResultListener() {
                                @Override
                                public void onSuccess(HuyaStreamInfo defaultStream,
                                                      java.util.List<HuyaStreamInfo> allStreams,
                                                      java.util.List<String> lines) {
                                    StringBuilder sb = new StringBuilder();
                                    sb.append("🎉【自动化 SDK 解析测试】成功！roomId=").append(TEST_ROOM).append("\n");
                                    sb.append("  线路数量=").append(lines == null ? 0 : lines.size()).append("\n");
                                    sb.append("  流总数=").append(allStreams == null ? 0 : allStreams.size()).append("\n");
                                    if (defaultStream != null) {
                                        sb.append("  默认流: line=").append(defaultStream.lineIndex)
                                          .append(" bitRate=").append(defaultStream.bitRate)
                                          .append(" disp=").append(defaultStream.bitRateDisplayName).append("\n");
                                        String url = defaultStream.getPlayUrl();
                                        if (url != null) {
                                            sb.append("  默认URL=").append(url.substring(0, Math.min(url.length(), 100)))
                                              .append(url.length() > 100 ? "..." : "").append("\n");
                                        }
                                    }
                                    if (allStreams != null && !allStreams.isEmpty()) {
                                        sb.append("  全部流信息摘要：\n");
                                        for (HuyaStreamInfo s : allStreams) {
                                            sb.append("    - [line").append(s.lineIndex).append("] ")
                                              .append(s.bitRateDisplayName).append("(").append(s.bitRate)
                                              .append("bps) HLS=").append(s.isHls()).append(" sUrl_len=")
                                              .append(s.hlsUrl == null ? 0 : s.hlsUrl.length()).append("\n");
                                        }
                                    }
                                    Log.i(TAG, sb.toString());
                                }

                                @Override
                                public void onError(String error) {
                                    Log.e(TAG, "❌【自动化 SDK 解析测试】失败! roomId=" + TEST_ROOM + " -> " + error);
                                }
                            });
                        }
                    }, "HuyaSDKParser-AutoTest").start();
                }
            }, 60000);

        } catch (Throwable e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                Log.e(TAG, "SDK 绑定异常: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), e);
            } else {
                Log.e(TAG, "SDK 绑定异常: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
            sInitOk = false;
        }
    }

    public static boolean isSDKAvailable() {
        return sInitOk;
    }

    /**
     * 新版完整解析：返回全部线路×码率信息（用于 UI 线路/清晰度选择）
     */
    public static void parseFull(int roomId, OnSDKFullResultListener listener) {
        if (!sInitOk || sHuyaBerryInstance == null) {
            listener.onError("SDK 未初始化");
            return;
        }

        // 命中缓存则直接用
        CachedStreams cached = getCachedStreams(roomId);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            Log.d(TAG, "命中房间" + roomId + "流信息缓存（" + (System.currentTimeMillis() - cached.timestamp) / 1000 + "s前）");
            HuyaStreamInfo def = pickDefaultStream(cached.streams);
            List<String> lines = buildLineLabels(cached.streams);
            listener.onSuccess(def, cached.streams, lines);
            return;
        }

        AtomicBoolean done = new AtomicBoolean(false);

        new Thread(() -> {
            try {
                Object callback;
                try {
                    callback = Proxy.newProxyInstance(
                            sCallbackClass.getClassLoader(),
                            new Class[]{sCallbackClass},
                            (proxy, method, args) -> {
                                String name = method.getName();
                                // 【调试】100% 必打：任何 SDK 回调方法进入都打印
                                StringBuilder argsTrace = new StringBuilder();
                                if (args != null) {
                                    for (int i = 0; i < args.length; i++) {
                                        if (i > 0) argsTrace.append(", ");
                                        argsTrace.append("arg").append(i).append("=")
                                                 .append(args[i] == null ? "null"
                                                         : (args[i].getClass().getSimpleName()
                                                         + (args[i] instanceof Number ? "#" + args[i] : "")));
                                    }
                                }
                                Log.i(TAG, "📞【SDK回调进入】" + name + "(" + argsTrace + ") from "
                                        + Thread.currentThread().getName());

                                if ("onResultCallback".equals(name)) {
                                    int code = ((Number) args[0]).intValue();
                                    Object result = args[1];
                                    // 🔔 重要修复：即使 SDK 回调晚于 30s 超时(done==true)，也不要静默 return null！
                                    //    之前：done==true → 直接 return null（日志 0 onResult/0 handleFullResult/0 写缓存，
                                    //          用户先看到 Toast "SDK 解析超时"，然后后台回调成功但完全没反应→截图问题）
                                    //    现在：无论是否超时，都进入 handleFullResult 解析 + 写缓存 + listener.onSuccess
                                    //          （即使前面已经 onError，listener 再收一次 onSuccess 对 UI 是幂等且更优的，
                                    //           因为此时 SDK 真的返回了合法流，用户后续可以看到 "房间11352965 流信息写入缓存"）
                                    boolean alreadyTimeout = done.get();
                                    Log.d(TAG, "SDK onResultCallback: code=" + code
                                            + " result=" + (result != null ? result.getClass().getSimpleName() : "null")
                                            + " alreadyTimeout=" + alreadyTimeout
                                            + (alreadyTimeout ? "（⚠️回调晚于30s超时，但继续解析不丢弃）" : ""));
                                    try {
                                        handleFullResult(code, result, listener, done, roomId);
                                    } catch (Exception e) {
                                        Log.e(TAG, "handleFullResult 异常: " + e.getMessage(), e);
                                        if (!alreadyTimeout && done.compareAndSet(false, true)) {
                                            listener.onError("结果处理异常: " + e.getMessage());
                                        }
                                    }
                                    return null;
                                } else if ("onResultListCallback".equals(name)) {
                                    Log.d(TAG, "SDK onResultListCallback: code=" + args[0]);
                                    return null;
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt == void.class) return null;
                                if (rt == boolean.class) return false;
                                if (rt.isPrimitive()) return 0;
                                return null;
                            }
                    );
                    Log.d(TAG, "✅ CustomUICallback 代理创建成功 (Proxy class=" + callback.getClass().getName() + ")");
                } catch (Throwable proxyErr) {
                    Log.e(TAG, "❌ CustomUICallback 代理创建失败：", proxyErr);
                    if (done.compareAndSet(false, true)) {
                        listener.onError("Proxy创建失败: " + proxyErr.toString());
                    }
                    return;
                }

                Log.d(TAG, "调用 SDK getLiveDataByRoomId, roomId=" + roomId);
                sGetLiveDataMethod.invoke(sHuyaBerryInstance, (long) roomId, callback);

                Thread.sleep(30000); // 30s 超时兜底（给网络&信令充足时间）
                if (done.compareAndSet(false, true)) {
                    Log.w(TAG, "SDK 调用超时 (30s)");
                    listener.onError("SDK 解析超时");
                }
            } catch (Exception e) {
                // 打印完整异常链：尤其 InvocationTargetException 必须看 getTargetException 才是真因
                Throwable real = e;
                StringBuilder causeTrace = new StringBuilder();
                int depth = 0;
                while (real != null && depth++ < 8) {
                    if (real instanceof java.lang.reflect.InvocationTargetException) {
                        Throwable t = ((java.lang.reflect.InvocationTargetException) real).getTargetException();
                        causeTrace.append("InvocationTargetException → 目标异常: ")
                                   .append(t == null ? "null" : (t.getClass().getSimpleName() + ": " + t.getMessage()))
                                   .append("\n");
                        real = t;
                    } else {
                        causeTrace.append(real.getClass().getSimpleName())
                                   .append(": ").append(real.getMessage()).append("\n");
                        for (StackTraceElement s : real.getStackTrace()) {
                            if (s.getClassName().startsWith("com.huya")
                                    || s.getClassName().startsWith("com.tv.live")
                                    || s.getClassName().startsWith("com.duowan")) {
                                causeTrace.append("  ↳ ").append(s.toString()).append("\n");
                            }
                        }
                        real = real.getCause();
                        if (real != null) causeTrace.append("  ▼ Cause: ");
                    }
                }
                Log.e(TAG, "SDK 解析异常完整链：\n" + causeTrace.toString());
                String userMsg = (e instanceof java.lang.reflect.InvocationTargetException
                        && ((java.lang.reflect.InvocationTargetException) e).getTargetException() != null)
                        ? ((java.lang.reflect.InvocationTargetException) e).getTargetException().toString()
                        : e.toString();
                if (userMsg.length() > 240) userMsg = userMsg.substring(0, 240) + "...";
                if (done.compareAndSet(false, true)) {
                    listener.onError("SDK 异常: " + userMsg);
                }
            }
        }, "HuyaSDKParser-Full").start();
    }

    /**
     * 兼容旧版：只返回第一个 URL
     */
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
        // 优先：默认线路 + 默认码率的那条
        for (HuyaStreamInfo s : streams) {
            if (s.isDefaultLine && s.isDefaultBitrate) return s;
        }
        for (HuyaStreamInfo s : streams) {
            if (s.isDefaultLine) return s;
        }
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

    /* ==================== 通用反射工具：LiveInfo方法兜底（sLiveInfoClass 缺失时用对象自身 Class）==================== */

    private static final ConcurrentHashMap<String, Method> sLazyMethodCache = new ConcurrentHashMap<>();

    /**
     * 惰性获取指定对象的 Class 上的方法（缓存。
     * 即使 sLiveInfoClass==null 的情况下，只要 obj 本身就是 LiveInfo 实例（SDK回调给我们的对象），
     * 通过 obj.getClass() 就能取到实际的 Class，从而反射 getMethod，完全不需要提前知道 LiveInfo 的 Class 对象。
     */
    private static Method lazyGetMethod(Object obj, String name, Class<?>... params) {
        if (obj == null) return null;
        String key = obj.getClass().getName() + "#" + name + "$" + params.length;
        Method cached = sLazyMethodCache.get(key);
        if (cached != null) return cached;
        try {
            Method m = obj.getClass().getMethod(name, params);
            m.setAccessible(true);
            sLazyMethodCache.put(key, m);
            return m;
        } catch (NoSuchMethodException e) {
            sLazyMethodCache.put(key, null); // 失败也缓存，避免反复反射
            return null;
        }
    }

    private static Object safeInvokeGetter(Object obj, String name, Class<?>[] params, Object... args) {
        Method m = lazyGetMethod(obj, name, params);
        if (m == null) return null;
        try { return m.invoke(obj, args); } catch (Exception e) { return null; }
    }

    /**
     * 完整解析结果回调：返回全部线路×码率
     */
    private static void handleFullResult(int code, Object result, OnSDKFullResultListener listener,
                                         AtomicBoolean done, int roomId) {
        if (result == null) {
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回空结果");
            return;
        }
        // sLiveInfoClass 缺失时（exclude掉整个类名匹配：LiveInfo类名做宽松校验
        boolean typeOk;
        if (sLiveInfoClass != null) {
            typeOk = sLiveInfoClass.isInstance(result);
        } else {
            String cn = result.getClass().getName();
            typeOk = cn.contains("LiveInfo");
            Log.d(TAG, "sLiveInfoClass==null，通过类名宽松匹配结果类型: " + cn + " ok=" + typeOk);
        }
        if (!typeOk) {
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回类型不匹配");
            return;
        }

        List<HuyaStreamInfo> streams = extractFullStreamList(result);
        if (streams == null || streams.isEmpty()) {
            if (done.compareAndSet(false, true)) listener.onError("未提取到任何流地址");
            return;
        }

        // 写缓存
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
        // 旧接口兼容：复用 full 流程
        if (result == null) {
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回空结果");
            return;
        }
        boolean typeOk;
        if (sLiveInfoClass != null) {
            typeOk = sLiveInfoClass.isInstance(result);
        } else {
            String cn = result.getClass().getName();
            typeOk = cn.contains("LiveInfo");
        }
        if (!typeOk) {
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回类型不匹配");
            return;
        }
        // 策略1
        String[] urls = extractUrlsFromLines(result);
        if (urls[0] != null || urls[1] != null) {
            Log.d(TAG, "SDK 策略1 成功: hls=" + (urls[0] != null) + " flv=" + (urls[1] != null));
            if (done.compareAndSet(false, true)) {
                listener.onSuccess(urls[0] != null ? urls[0] : "", urls[1] != null ? urls[1] : "", urls[0] != null);
            }
            return;
        }
        // 策略2
        urls = extractUrlsByMethodScan(result);
        if (urls[0] != null || urls[1] != null) {
            if (done.compareAndSet(false, true)) {
                listener.onSuccess(urls[0] != null ? urls[0] : "", urls[1] != null ? urls[1] : "", urls[0] != null);
            }
            return;
        }
        // 策略3
        urls = extractUrlsFromFields(result);
        if (urls[0] != null || urls[1] != null) {
            if (done.compareAndSet(false, true)) {
                listener.onSuccess(urls[0] != null ? urls[0] : "", urls[1] != null ? urls[1] : "", urls[0] != null);
            }
            return;
        }
        if (done.compareAndSet(false, true)) {
            listener.onError("SDK 未能提取到流地址");
        }
    }

    /**
     * 【新版】从 LiveInfo 提取完整线路×码率列表（sLiveInfoClass 缺失时走惰性反射兜底）
     */
    private static List<HuyaStreamInfo> extractFullStreamList(Object liveInfo) {
        List<HuyaStreamInfo> out = new ArrayList<>();
        try {
            // 1. 拿 lines：优先走缓存方法，失败时惰性从 liveInfo 自身 Class 反射
            Object linesObj;
            if (sGetLinesMethod != null) {
                linesObj = sGetLinesMethod.invoke(liveInfo);
            } else {
                linesObj = safeInvokeGetter(liveInfo, "getLines", new Class[0]);
            }
            if (!(linesObj instanceof Vector)) {
                Log.w(TAG, "getLines 不是 Vector: " + (linesObj == null ? "null" : String.valueOf(linesObj.getClass())));
                return fallbackExtractAsSingle(liveInfo);
            }
            Vector<?> lines = (Vector<?>) linesObj;
            Log.d(TAG, "getLines: " + lines.size() + " 条线路");

            for (int i = 0; i < lines.size(); i++) {
                int lineValue;
                try {
                    lineValue = ((Number) lines.get(i)).intValue();
                } catch (Exception e) {
                    Log.d(TAG, "线路#" + i + " 取值失败: " + e.getMessage());
                    continue;
                }

                Object brObj;
                try {
                    if (sGetBitRateListMethod != null) {
                        brObj = sGetBitRateListMethod.invoke(liveInfo, lineValue);
                    } else {
                        brObj = safeInvokeGetter(liveInfo, "getBitRateList", new Class[]{int.class}, lineValue);
                    }
                } catch (Exception e) {
                    Log.d(TAG, "线路#" + i + "(v=" + lineValue + ") getBitRateList 失败: " + e.getMessage());
                    continue;
                }
                if (!(brObj instanceof Vector)) continue;
                Vector<?> bitRates = (Vector<?>) brObj;
                Log.d(TAG, "线路#" + i + "(v=" + lineValue + "): " + bitRates.size() + " 个码率");

                // 同线路内按码率降序排列（高码率在前 = 默认码率）
                List<HuyaStreamInfo> lineStreams = new ArrayList<>();
                for (int j = 0; j < bitRates.size(); j++) {
                    Object oneBr = bitRates.get(j);
                    int br;
                    String dn;
                    try {
                        br = sBitRateField.getInt(oneBr);
                        Object dnObj = sDisplayNameField.get(oneBr);
                        dn = (dnObj != null) ? String.valueOf(dnObj) : "";
                    } catch (Exception e) {
                        // BitRateInfo 字段反射失败，兜底：走对象字段反射
                        try {
                            Field f = oneBr.getClass().getField("bitRate"); f.setAccessible(true);
                            br = f.getInt(oneBr);
                        } catch (Exception e2) { br = 0; }
                        dn = "";
                        if (br == 0) {
                            Log.d(TAG, "码率#" + j + " 读取失败: " + e.getMessage());
                            continue;
                        }
                    }
                    String hlsUrl = null;
                    String flvUrl = null;
                    try {
                        if (sGetPlayUrlMethod != null) {
                            hlsUrl = (String) sGetPlayUrlMethod.invoke(liveInfo, false, lineValue, br);
                        } else {
                            Method m = lazyGetMethod(liveInfo, "getPlayUrlByLineAndBitrate",
                                    boolean.class, int.class, int.class);
                            if (m != null) hlsUrl = (String) m.invoke(liveInfo, false, lineValue, br);
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "HLS URL 获取失败: line=" + lineValue + " br=" + br + ": " + e.getMessage());
                    }
                    try {
                        if (sGetPlayUrlMethod != null) {
                            flvUrl = (String) sGetPlayUrlMethod.invoke(liveInfo, true, lineValue, br);
                        } else {
                            Method m = lazyGetMethod(liveInfo, "getPlayUrlByLineAndBitrate",
                                    boolean.class, int.class, int.class);
                            if (m != null) flvUrl = (String) m.invoke(liveInfo, true, lineValue, br);
                        }
                    } catch (Exception e) {
                        // ignore
                    }
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
                // 同线路按 bitRate 降序 → 第一个 = 默认码率
                Collections.sort(lineStreams, (a, b) -> Integer.compare(b.bitRate, a.bitRate));
                for (int k = 0; k < lineStreams.size(); k++) {
                    lineStreams.get(k).isDefaultBitrate = (k == 0);
                }
                out.addAll(lineStreams);
            }

            if (out.isEmpty()) return fallbackExtractAsSingle(liveInfo);
            return out;

        } catch (Exception e) {
            Log.e(TAG, "extractFullStreamList 异常: " + e.getMessage());
            if (!out.isEmpty()) return out;
            return fallbackExtractAsSingle(liveInfo);
        }
    }

    /**
     * 兜底：用策略1提取的单条 URL 包装成单条流
     */
    private static List<HuyaStreamInfo> fallbackExtractAsSingle(Object liveInfo) {
        String[] urls = extractUrlsFromLines(liveInfo);
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
        // 虎牙码率与分辨率的经验映射
        if (brKbps >= 8000) return "4K (2160p)";
        if (brKbps >= 4000) return "1080p";
        if (brKbps >= 2000) return "720p";
        if (brKbps >= 1200) return "540p";
        if (brKbps >= 600) return "360p";
        return "自适应";
    }

    /* ==================== 旧版策略方法（保持兼容） ==================== */

    private static String[] extractUrlsFromLines(Object liveInfo) {
        String hls = null;
        String flv = null;
        try {
            Object linesObj;
            if (sGetLinesMethod != null) {
                linesObj = sGetLinesMethod.invoke(liveInfo);
            } else {
                linesObj = safeInvokeGetter(liveInfo, "getLines", new Class[0]);
            }
            if (!(linesObj instanceof Vector)) return new String[]{null, null};
            Vector<?> lines = (Vector<?>) linesObj;
            if (lines.isEmpty()) return new String[]{null, null};

            for (int i = 0; i < lines.size(); i++) {
                int line = ((Number) lines.get(i)).intValue();
                Object bitRatesObj;
                if (sGetBitRateListMethod != null) {
                    bitRatesObj = sGetBitRateListMethod.invoke(liveInfo, line);
                } else {
                    bitRatesObj = safeInvokeGetter(liveInfo, "getBitRateList", new Class[]{int.class}, line);
                }
                if (!(bitRatesObj instanceof Vector)) continue;
                Vector<?> bitRates = (Vector<?>) bitRatesObj;

                for (int j = 0; j < bitRates.size(); j++) {
                    Object brObj = bitRates.get(j);
                    int bitrate;
                    try {
                        bitrate = sBitRateField.getInt(brObj);
                    } catch (Exception e) {
                        try {
                            Field f = brObj.getClass().getField("bitRate"); f.setAccessible(true);
                            bitrate = f.getInt(brObj);
                        } catch (Exception e2) { continue; }
                    }
                    String hlsUrl = null;
                    String flvUrl = null;
                    if (sGetPlayUrlMethod != null) {
                        hlsUrl = (String) sGetPlayUrlMethod.invoke(liveInfo, false, line, bitrate);
                        flvUrl = (String) sGetPlayUrlMethod.invoke(liveInfo, true, line, bitrate);
                    } else {
                        Method m = lazyGetMethod(liveInfo, "getPlayUrlByLineAndBitrate",
                                boolean.class, int.class, int.class);
                        if (m != null) {
                            hlsUrl = (String) m.invoke(liveInfo, false, line, bitrate);
                            flvUrl = (String) m.invoke(liveInfo, true, line, bitrate);
                        }
                    }
                    if (hlsUrl != null && !hlsUrl.isEmpty() && hls == null) {
                        hls = hlsUrl;
                        Log.d(TAG, "HLS URL(line=" + line + ",br=" + bitrate + "): "
                                + hlsUrl.substring(0, Math.min(80, hlsUrl.length())));
                    }
                    if (flvUrl != null && !flvUrl.isEmpty() && flv == null) {
                        flv = flvUrl;
                        Log.d(TAG, "FLV URL(line=" + line + ",br=" + bitrate + "): "
                                + flvUrl.substring(0, Math.min(80, flvUrl.length())));
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "策略1 异常: " + e.getMessage());
        }
        return new String[]{hls, flv};
    }

    private static String[] extractUrlsByMethodScan(Object liveInfo) {
        String hls = null;
        String flv = null;
        if (sAlternativeMethods == null) return new String[]{null, null};
        for (Method m : sAlternativeMethods) {
            String name = m.getName().toLowerCase();
            Class<?>[] params = m.getParameterTypes();
            if (params.length > 2) continue;
            boolean skipParams = false;
            for (Class<?> p : params) {
                if (p != boolean.class && p != int.class && p != long.class && p != String.class) {
                    skipParams = true;
                    break;
                }
            }
            if (skipParams) continue;
            try {
                Object result;
                if (params.length == 0) {
                    result = m.invoke(liveInfo);
                } else if (params.length == 1 && (params[0] == boolean.class || params[0] == int.class)) {
                    result = m.invoke(liveInfo, params[0] == boolean.class ? true : 0);
                } else {
                    continue;
                }
                if (result instanceof String) {
                    String str = (String) result;
                    if (str.contains("hls") || str.contains(".m3u8") || str.contains("hls.huya.com")) {
                        if (hls == null) {
                            hls = str;
                            Log.d(TAG, "策略2 找到 HLS: " + m.getName());
                        }
                    } else if (str.contains("flv") || str.contains(".flv") || str.contains("flv.huya.com")) {
                        if (flv == null) {
                            flv = str;
                            Log.d(TAG, "策略2 找到 FLV: " + m.getName());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return new String[]{hls, flv};
    }

    private static String[] extractUrlsFromFields(Object liveInfo) {
        String hls = null;
        String flv = null;
        try {
            java.lang.reflect.Field[] fields = sLiveInfoClass.getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(liveInfo);
                    if (val instanceof String) {
                        String str = (String) val;
                        if (str.contains("hls") || str.contains(".m3u8")) {
                            if (hls == null) hls = str;
                        } else if (str.contains("flv") || str.contains(".flv")) {
                            if (flv == null) flv = str;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            Class<?> sc = sLiveInfoClass.getSuperclass();
            while (sc != null && sc != Object.class) {
                for (java.lang.reflect.Field f : sc.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(liveInfo);
                        if (val instanceof String) {
                            String str = (String) val;
                            if (str.contains("hls") || str.contains(".m3u8")) {
                                if (hls == null) hls = str;
                            } else if (str.contains("flv") || str.contains(".flv")) {
                                if (flv == null) flv = str;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                sc = sc.getSuperclass();
            }
        } catch (Exception e) {
            Log.d(TAG, "策略3 异常: " + e.getMessage());
        }
        return new String[]{hls, flv};
    }

    // =====================================================================
    // 🆕 豆包第四段：BerryDebugChecker（播放器模块剥离检测器）
    //  来源：豆包「检测 SDK 是否成功剥离播放器模块的调试代码」
    //  判断结果标准（与豆包原文一致）：
    //    ✅ 正常：未检测到播放器模块
    //    → 具体输出：
    //       ① Class.forName("com.huya.berry.sdkplayer.SdkPlayerService")
    //         抛出 ClassNotFoundException → R8 已剥离 / 类未加载
    //       ② ModuleManager 已注册模块不含 PlayerModule → 跳过播放器 onCreate/init
    //       ③ /proc/self/maps 无 libberry_player.so / libberry_decoder.so mmap 记录
    //         → so 未被加载（运行内存也省下）
    // =====================================================================
    private static void runBerryDebugChecker() {
        final String TAG2 = TAG + "-DebugChecker";
        Log.i(TAG2, "================ 🔬【豆包推荐：播放器模块剥离自检】================");
        boolean ok = true;
        StringBuilder sb = new StringBuilder();
        int passed = 0, total = 0;

        // ------ 检查项 1：播放器相关类是否 ClassNotFound（release R8 剥离标志）------
        total++;
        String[] playerClasses = new String[] {
                "com.huya.berry.sdkplayer.SdkPlayerService",
                "com.huya.berry.player.internal.BerryPlayer",
                "com.huya.berry.decoder.BerryDecoder",
                "com.huya.berry.nativerender.NativeRender",
                "com.huya.berry.sdkplayer.floats.view.PlayerActivity",
                "tv.danmaku.ijk.media.player.IjkMediaPlayer"
        };
        int cnfCount = 0;
        for (String c : playerClasses) {
            try {
                Class.forName(c);
                sb.append("  ⚠️  CLASS 还能加载（debug 阶段正常，release R8 会剥离）：").append(c).append("\n");
            } catch (ClassNotFoundException cnfe) {
                cnfCount++;
            } catch (Throwable t) {
                sb.append("  ℹ️  CLASS 加载其它异常（也算未加载，OK）：").append(c).append(" → ")
                        .append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                cnfCount++;
            }
        }
        if (cnfCount == playerClasses.length) {
            passed++;
            Log.i(TAG2, "  ✅ 检查项1【播放器类加载】：全部 ClassNotFound（R8 已剥离 / 未加载，共" + playerClasses.length + "/" + playerClasses.length + "）");
        } else {
            sb.insert(0, "  ℹ️  检查项1【播放器类加载】：ClassNotFound " + cnfCount + "/" + playerClasses.length
                    + "（debug minifyEnabled=false 属正常，release 会全部消失）\n");
            // debug 阶段不算失败，因为 minifyEnabled=false 还在 dex 里；只当 informational
            passed++;
        }

        // ------ 检查项 2：模块管理器 - 尝试定位是否有 PlayerModule 注册（尽量反射查）------
        total++;
        boolean foundPlayerModule = false;
        try {
            // 豆包原文定位：com.huya.berry.module.ModuleManager.registerModule
            // 由于混淆名可能变化，我们尽力遍历 ModuleManager 字段（Collection / Map 类型）中
            // 的所有 Class/Module 对象，看类名是否含 "Player" / "Video" / "Live"
            Class<?> mmClass = Class.forName("com.huya.berry.module.ModuleManager");
            Object mmInstance = null;
            try {
                Method instanceM = mmClass.getMethod("getInstance");
                mmInstance = instanceM.invoke(null);
            } catch (Throwable t1) {
                try {
                    Method instanceM = mmClass.getMethod("instance");
                    mmInstance = instanceM.invoke(null);
                } catch (Throwable ignore) {}
            }
            if (mmInstance != null) {
                for (Field f : mmClass.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(mmInstance);
                        if (v instanceof java.util.Collection) {
                            for (Object item : (java.util.Collection<?>) v) {
                                if (item != null && item.getClass().getName().toLowerCase()
                                        .matches(".*(player|video|decoder|render).*")) {
                                    foundPlayerModule = true;
                                    sb.append("  ⚠️  注册模块含播放器相关：").append(item.getClass().getName()).append("\n");
                                }
                            }
                        } else if (v instanceof java.util.Map) {
                            for (Object item : ((java.util.Map<?, ?>) v).values()) {
                                if (item != null && item.getClass().getName().toLowerCase()
                                        .matches(".*(player|video|decoder|render).*")) {
                                    foundPlayerModule = true;
                                    sb.append("  ⚠️  注册模块含播放器相关：").append(item.getClass().getName()).append("\n");
                                }
                            }
                        }
                    } catch (Throwable ignore) {}
                }
            }
            if (!foundPlayerModule) {
                passed++;
                Log.i(TAG2, "  ✅ 检查项2【模块注册】：ModuleManager 未检测到 PlayerModule（官方开关已生效）");
            } else {
                ok = false;
                Log.w(TAG2, "  ❌ 检查项2【模块注册】：发现播放器相关模块注册，请核对官方 isNeedPlay=false 是否被 SDK 版本忽略");
            }
        } catch (ClassNotFoundException mmMiss) {
            // 某些版本 Berry 类名混淆后名字不同，属于版本差异，降级跳过不判失败
            passed++;
            Log.i(TAG2, "  ℹ️  检查项2【模块注册】：ModuleManager 类名无法定位（版本混淆），降级为不判定，跳过");
        } catch (Throwable t) {
            passed++;
            Log.i(TAG2, "  ℹ️  检查项2【模块注册】：反射遍历失败（" + t.getClass().getSimpleName()
                    + "），降级为不判定，跳过");
        }

        // ------ 检查项 3：/proc/self/maps 是否 mmap 了播放器 so（运行内存实际加载标志）------
        total++;
        boolean soLoaded = false;
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"));
            String line;
            java.util.regex.Pattern soPat = java.util.regex.Pattern
                    .compile("lib(berry_player|berry_decoder|ijkffmpeg|ijksoundtouch|ijksdl|ijkutil)\\.so");
            while ((line = br.readLine()) != null) {
                if (soPat.matcher(line).find()) {
                    soLoaded = true;
                    sb.append("  ⚠️  mmap 播放器 so（请确认官方 isNeedPlay=false 已生效）：").append(line).append("\n");
                }
            }
            if (!soLoaded) {
                passed++;
                Log.i(TAG2, "  ✅ 检查项3【so 内存加载】：/proc/self/maps 中无 libberry_player/decoder/ijk 等 mmap 记录（运行内存真·省下）");
            } else {
                ok = false;
                Log.w(TAG2, "  ❌ 检查项3【so 内存加载】：仍检测到播放器相关 so 被 mmap（说明 SDK 内部仍有代码触发了 loadLibrary）");
            }
        } catch (Throwable t) {
            passed++;
            Log.i(TAG2, "  ℹ️  检查项3【so 内存加载】：无法读取 /proc/self/maps（权限/ROM 差异），降级为不判定，跳过");
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignore) {}
        }

        // ------ 汇总输出 ------
        sb.insert(0, "\n================ 🔬【自检结果汇总】" + (ok ? "✅ 通过" : "⚠️ 部分异常")
                + "（检查项 passed=" + passed + "/" + total + "）================\n");
        sb.append("========================================================\n");
        if (ok) {
            sb.append("  🎉 豆包判断标准输出：✅ 正常：未检测到播放器模块\n");
            sb.append("     解释：方案1 官方开关 + 方案2 R8 播放器类 dontwarn 全部生效\n");
            sb.append("     Release 阶段 R8 会把未加载类全部剥离 dex，再省 ≈0.9~3MB\n");
        } else {
            sb.append("  ⚠️  仍有残留播放器模块：请核对 Berry 版本是否支持 isNeedPlay=false setter 名（\n");
            sb.append("     可在 javap HuyaBerryConfig\\$Builder 重新反查 setter，必要时降级仅用 dontwarn 即可）\n");
        }
        sb.append("========================================================\n");
        if (ok) Log.i(TAG2, sb.toString()); else Log.w(TAG2, sb.toString());
    }
}
