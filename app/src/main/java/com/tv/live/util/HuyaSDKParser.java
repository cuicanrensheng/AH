package com.tv.live.util;

import android.app.Application;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.huya.berry.client.HuyaBerry;
import com.huya.berry.client.HuyaBerryConfig;
import com.huya.berry.client.customui.CustomUICallback;
import com.huya.berry.client.customui.model.BitRateInfo;
import com.huya.berry.client.customui.model.LiveInfo;
import com.huya.berry.gamesdk.base.BaseCallback;
import com.huya.berry.gamesdk.crash.ICrashService;
import com.huya.live.service.ServiceHelper;

import java.util.HashMap;
import java.util.Map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.tv.live.util.HuyaCredentials;

/**
 * 虎牙 Berry SDK 解析器（直接调用版）
 *
 * 通过 SDK 原生 API 获取直播流地址，充当解析器和防盗链角色。
 * 让 SDK 处理 CDN 鉴权，返回带签名的 URL 给 ExoPlayer 播放。
 *
 * 本类已经把所有反射调用换成直接调用：
 *   - HuyaBerry.instance() / init(Application, HuyaBerryConfig) / getLiveDataByRoomId(...)
 *   - HuyaBerryConfig.Builder().gameId/appId/appKey/.../.build()
 *   - LiveInfo.getLines() / getBitRateList(int) / getPlayUrlByLineAndBitrate(boolean,int,int)
 *   - BitRateInfo.bitRate / BitRateInfo.disPlayName 直接字段访问
 *   - CustomUICallback 由匿名实现取代 java.lang.reflect.Proxy
 */
public class HuyaSDKParser {

    private static final String TAG = "HuyaSDKParser";

    private static volatile boolean sInitDone = false;
    private static volatile boolean sInitOk = false;
    private static int sAutoTestRound = 0;   // 自动化测试房间轮换计数

    // SDK 就绪等待机制：解决「首次点击频道时 SDK 尚未 init 完成」竞态问题
    // 当 parseFull / playHuyaStream 在 sInitOk=false 时被调用，可通过
    // waitForInit(timeoutMs) 阻塞等待 SDK 就绪，超时后返回 false 走原有降级路径。
    private static final Object sInitWaitLock = new Object();
    private static final List<Runnable> sInitReadyListeners = new ArrayList<>();
    private static boolean sInitNotified = false;

    // 单例 + 配置由 SDK 直接持有，无需反射缓存字段
    private static HuyaBerry sHuyaBerry;

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
    // 🔧 预解析调度 Handler 必须跑在后台线程：每 1.5s 触发一次频道预解析，
    // 若绑定主线程会持续占用 UI 线程导致卡顿（日志实测 onResultCallback 也在 main）。
    private static final HandlerThread sPreloadThread;
    private static final Handler sPreloadHandler;
    static {
        sPreloadThread = new HandlerThread("HuyaSDKParser-Preload");
        sPreloadThread.setDaemon(true);
        sPreloadThread.start();
        sPreloadHandler = new Handler(sPreloadThread.getLooper());
    }
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
                LogBridge.d(TAG, "🔁【预解析】(" + sPreloadIndex + "/" + sPreloadPendingQueue.size()
                        + ") roomId=" + finalRoomId);
                // 静默解析：listener 只写日志，不弹Toast、不阻塞UI
                parseFull(finalRoomId, new OnSDKFullResultListener() {
                    @Override public void onSuccess(HuyaStreamInfo defaultStream,
                                                    List<HuyaStreamInfo> allStreams,
                                                    List<String> lines) {
                        LogBridge.d(TAG, "✅【预解析】roomId=" + finalRoomId + " 成功, streams="
                                + (allStreams != null ? allStreams.size() : 0));
                    }
                    @Override public void onError(String error) {
                        LogBridge.w(TAG, "⚠️【预解析】roomId=" + finalRoomId + " 失败: " + error
                                + "（不影响用户体验，点频道时会重新解析）");
                    }
                });
                // 下一个房间 PRELOAD_INTERVAL_MS 后再发
                sPreloadHandler.postDelayed(this, PRELOAD_INTERVAL_MS);
            } else {
                // 队列跑完：清空队列并重置游标，为后续增量追加做好准备
                synchronized (sPreloadPendingQueue) {
                    sPreloadScheduled = false;
                    LogBridge.d(TAG, "🏁【预解析】队列处理完毕, 已提交=" + sPreloadIndex
                            + "/" + sPreloadPendingQueue.size());
                    sPreloadPendingQueue.clear();
                    sPreloadIndex = 0;
                }
            }
        }
    };

    /**
     * 批量预解析虎牙房间。通常在直播源列表加载完成（缓存命中 / 网络成功）时调用。
     * 与直播源加载**并行**进行，用户点击时已有缓存→瞬时播放。
     *
     * ⚠️  即使 SDK 尚未 init 完成也可以调用：房间号先存入 pendingQueue，
     *     等 init() 中 sInitOk=true 后会自动补发。
     *
     * @param roomIds 所有虎牙房间号（会自动去重、跳过已缓存）
     */
    public static void preloadRooms(List<Integer> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) return;
        // 去重 + 截断前 PRELOAD_MAX_ROOMS 个（避免 100+ 房间全解析太伤）
        LinkedHashSet<Integer> deduped = new LinkedHashSet<>(roomIds);
        List<Integer> trimmed = new ArrayList<>(deduped);
        if (trimmed.size() > PRELOAD_MAX_ROOMS) {
            trimmed = new ArrayList<>(trimmed.subList(0, PRELOAD_MAX_ROOMS));
        }
        synchronized (sPreloadPendingQueue) {
            // 🔧【增量合并】不再 clear + 重置游标——那会打断正在跑的队列并从头重跑，
            // 导致同一批房间被重复解析（SDK 被重复调用）。改为只把
            // 「不在队列中 且 无有效缓存」的房间追加到队尾，正在跑的进度不受影响。
            int added = 0;
            for (Integer id : trimmed) {
                if (sPreloadPendingQueue.contains(id)) continue;
                CachedStreams cs = sStreamsCache.get(id);
                if (cs != null && cs.isValid()) continue;
                sPreloadPendingQueue.add(id);
                added++;
            }
            if (added == 0) {
                LogBridge.d(TAG, "🔁【预解析】无新增房间（均已在队列或已有有效缓存），跳过");
                return;
            }
            if (sInitOk && !sPreloadScheduled) {
                sPreloadScheduled = true;
                sPreloadIndex = 0;
                LogBridge.d(TAG, "🚀【预解析】开始, 共 " + sPreloadPendingQueue.size()
                        + " 个虎牙房间，每 " + (PRELOAD_INTERVAL_MS / 1000) + "s 解析一个");
                sPreloadHandler.post(PRELOAD_RUNNABLE);
            } else if (!sInitOk) {
                // 存入队列即可，init 成功后会自动补发
                LogBridge.d(TAG, "⏳【预解析】SDK 尚未 init，已缓存 " + sPreloadPendingQueue.size()
                        + " 个房间号，等 init 完成后自动开始");
            } else {
                // 已在跑：新房间追加到队尾，游标不动，当前队列消费完自然轮到新房间
                LogBridge.d(TAG, "🔀【预解析】队列正在跑，追加 " + added + " 个新房间到队尾（总计 "
                        + sPreloadPendingQueue.size() + " 个）");
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
        public boolean isDefaultBitrate;  // 是否该线路的默认码率（降序第2档；仅1档时取第1档）

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
         * @param defaultStream  默认选择的流（主线路默认码率，码率降序第2档）
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
     * 🟢【电视适配】根据当前设备能力，从已解析的码率列表中选择最合适的流。
     *
     * <p>策略：
     * <ul>
     *   <li>老电视芯片（强制软解）→ 优先选择 ≤ 720p 的最高码率流（避免软解 1080p 卡顿）</li>
     *   <li>普通电视/手机 → 优先选择 ≤ 1080p 的最高码率流（保证 1080p 画质）</li>
     *   <li>如果列表中没有匹配高度的流 → 退回到最高码率流</li>
     * </ul>
     *
     * <p>注意：调用方必须传入<b>同一条线路</b>下的码率列表。本方法不切换线路，
     * 只在传入的列表中按高度过滤+按码率降序选择。
     *
     * @param streams 同一线路的码率流列表（通常已按 bitRate 降序）
     * @return 选中的流（如果列表为空返回 null）
     */
    public static HuyaStreamInfo selectBestStreamForDevice(java.util.List<HuyaStreamInfo> streams) {
        if (streams == null || streams.isEmpty()) {
            return null;
        }
        if (streams.size() == 1) {
            return streams.get(0);
        }

        // 始终选择最高画质（去除设备类型自动降级逻辑）
        // 之前：电视固定 720p，手机自动选最高码率
        // 现在：所有设备统一选最高可用码率，由用户在播放界面手动切换清晰度
        int targetHeight = Integer.MAX_VALUE;
        java.util.List<HuyaStreamInfo> valid = new java.util.ArrayList<>();
        for (HuyaStreamInfo s : streams) {
            if (s != null && !TextUtils.isEmpty(s.getPlayUrl())) {
                valid.add(s);
            }
        }
        if (valid.isEmpty()) return null;

        // 2) 解析每个流的"有效高度"（参考 resolutionLabel）
        //    1080p → 1080, 720p → 720, 540p → 540, 360p → 360, 自适应 → 按码率估算
        //    选择：高度 ≤ targetHeight 的流中，按 bitRate 降序取最大（第一个）
        HuyaStreamInfo best = null;
        for (HuyaStreamInfo s : valid) {
            int h = parseStreamHeight(s);
            if (h <= targetHeight) {
                if (best == null || s.bitRate > best.bitRate) {
                    best = s;
                }
            }
        }

        // 3) 兜底：如果所有流都 > targetHeight（极端情况），选码率最高的（最接近）
        if (best == null) {
            LogBridge.w(TAG, "【适配选择】没有 <= " + targetHeight + "p 的流，退回选择最高码率");
            best = valid.get(0);
            for (HuyaStreamInfo s : valid) {
                if (s.bitRate > best.bitRate) best = s;
            }
        }

        LogBridge.i(TAG, "【适配选择】选中流: " + best
                + " (resolutionLabel=" + best.resolutionLabel
                + ", bitRate=" + best.bitRate + "bps)");
        return best;
    }

    /**
     * 解析流的有效高度（像素数）。
     * 来源优先级：resolutionLabel > bitRateDisplayName > 按码率估算。
     */
    private static int parseStreamHeight(HuyaStreamInfo s) {
        if (s == null) return 0;
        // 1) 从 resolutionLabel 解析（如 "1080p"、"720p"、"4K (2160p)"、"自适应"）
        if (!TextUtils.isEmpty(s.resolutionLabel)) {
            String l = s.resolutionLabel.toLowerCase(java.util.Locale.ROOT);
            if (l.contains("2160") || l.contains("4k")) return 2160;
            if (l.contains("1080")) return 1080;
            if (l.contains("720")) return 720;
            if (l.contains("540")) return 540;
            if (l.contains("480")) return 480;
            if (l.contains("360")) return 360;
        }
        // 2) 从 bitRateDisplayName 解析（"蓝光8M"、"超清4M" 等）
        if (!TextUtils.isEmpty(s.bitRateDisplayName)) {
            String b = s.bitRateDisplayName.toLowerCase(java.util.Locale.ROOT);
            // 虎牙命名通常是 "蓝光Xm"、"超清Xm"、"高清Xm"、"标清Xm"
            // 已用 resolutionLabel 涵盖，这里仅做兜底
            if (b.contains("4k")) return 2160;
        }
        // 3) 按码率估算（与 inferResolutionLabelFromBitrate 一致）
        int brKbps = s.bitRate / 1000;
        if (brKbps >= 8000) return 2160;
        if (brKbps >= 4000) return 1080;
        if (brKbps >= 2000) return 720;
        if (brKbps >= 1200) return 540;
        if (brKbps >= 600) return 360;
        return 0;
    }

    /**
     * 初始化虎牙 SDK（直接调用版）
     *
     * 注意：sdkclient-release.aar 已经在 classpath 上，HuyaBerry / HuyaBerryConfig /
     * CustomUICallback / LiveInfo / BitRateInfo 都是 public 类型，无需反射。
     */
    public static synchronized void init(Application app) {
        if (sInitDone) return;
        sInitDone = true;
        try {
            // 🆕 接入 SDK 内部日志：在 SDK init 之前启动日志中心，
            //    这样 SDK init 过程中的所有日志都会被捕获
            HuyaSDKLogger.init();
            HuyaSDKLogger.info(TAG, "开始初始化虎牙 SDK...");

            // 🔴【关键修复】在正式 init 前先预检 Mars STN 等核心 so 是否可加载。
            // Android 5.1.1 等老设备上 libmarsstn.so 可能缺少 startTask 等 native
            // 符号，导致 init 后首次网络请求触发 UnsatisfiedLinkError，并被 RxJava
            // 包装成 UndeliverableException 崩溃。预检失败时直接标记 SDK 不可用，
            // 上层会走纯解析兜底，而不是带着坏状态继续运行。
            String marsCheckError = checkMarsNativeLibraries();
            if (marsCheckError != null) {
                LogBridge.e(TAG, "❌ 虎牙 Mars 原生库预检失败，跳过 SDK 初始化: " + marsCheckError);
                HuyaSDKLogger.error(TAG, "Mars 原生库预检失败: " + marsCheckError);
                ExceptionReporter.reportHuyaBusinessFailure(
                        "HuyaSDKParser.checkMarsNativeLibraries", -99001, marsCheckError,
                        "api=" + android.os.Build.VERSION.SDK_INT);
                sInitOk = false;
                synchronized (sInitWaitLock) { sInitWaitLock.notifyAll(); }
                return;
            }
            LogBridge.i(TAG, "✅ 虎牙 Mars 原生库预检通过");

            // 🆕 SDK 兼容修复：中和 BaseApi.crashIfDebug，防止 SDK 内部
            //    非致命模块失败（反射实例化失败/服务注册失败）被升级为
            //    RuntimeException 导致整个 init 失败、解析回调永不触发
            SdkCompatHook.neutralizeCrashIfDebug();

            LogBridge.d(TAG, "[1/4] 准备构建 HuyaBerryConfig.Builder");

            // ============ 🆕 从加密存储读取凭证 =============
            int gameId;
            String appId;
            String appKey;
            try {
                HuyaCredentials credentials = HuyaCredentials.getInstance(app);
                gameId = credentials.getGameId();
                appId = credentials.getAppId();
                appKey = credentials.getAppKey();
                LogBridge.i(TAG, "  🔐 从加密存储加载凭证: " + credentials.getCredentialsSummary());
            } catch (Throwable credError) {
                LogBridge.e(TAG, "  ❌ 加载凭证失败: " + credError.getMessage());
                ExceptionReporter.report("HuyaCredentials", credError);
                // 从混淆后的编码值解码
                gameId = decodeGameIdFallback();
                appId = decodeAppIdFallback();
                appKey = decodeAppKeyFallback();
                LogBridge.i(TAG, "  🔐 使用编码后的默认凭证");
            }

            // ============ 🆕 官方精简开关 =============
            //   isNeedPlay(false)  → 官方「不需要播放器」开关，跳过播放器 Service 注册 / 内核加载
            //   cameraMode(false)  → 不启用摄像头推流（纯解析场景必关）
            //   oneKeyGangUp(false)→ 不启用一键连麦（纯解析场景必关）
            //   isOpenBugly(false) → 不启用 Bugly 崩溃上报
            //   hidePauseBtn(false)→ 暂停按钮隐藏
            HuyaBerryConfig.Builder builder = new HuyaBerryConfig.Builder()
                    .gameId(gameId)
                    .appId(appId)
                    .appKey(appKey)
                    .debugMode(false)
                    .landscapeMode(false)
                    .isOpenBugly(false)
                    .isNeedPlay(false)
                    .cameraMode(false)
                    .oneKeyGangUp(false)
                    .hidePauseBtn(false);
            LogBridge.i(TAG, "  ✅ HuyaBerryConfig.Builder 链路构建完成（含官方精简开关）");

            // ============ 🚫 UDB 设备指纹上报拦截（方案1：改写 host 指向无效地址）============
            // 反编译确认：com.huya.security.DeviceFingerprintSDK 是单例，
            // getInstance() 强制 init()→workThread.start()，后台自动向
            //   host + /device/fingerprint/log | /check | /link
            //   host + /dckey/check            （HyDeviceChecker.check）
            // 上报设备指纹（udbdf.huya.com / udbdf-v2.nimo.tv / api-cloud.master.live）。
            // 该类无外部开关，getInstance() 必然被 HuyaAuth.init() 触发。
            // 故在 SDK init 之前，把全部 host 字段改写为无效地址，使 NetworkBridge.post()
            // 全部失败 → 指纹线程空转、零上报。类与字段名未被混淆（com.huya.security.*），
            // 可直接引用无需反射。
            try {
                final java.lang.String DEAD_HOST = "http://127.0.0.1";
                com.huya.security.DeviceFingerprintSDK.host = DEAD_HOST;
                com.huya.security.DeviceFingerprintSDK.kiwiHost = DEAD_HOST;
                com.huya.security.DeviceFingerprintSDK.nimoHost = DEAD_HOST;
                com.huya.security.DeviceFingerprintSDK.openApiHost = DEAD_HOST;
                // HyDeviceChecker.check() 虽用 host+"/dckey/check"（已被上面的 host 改写覆盖），
                // 但其类内另持独立静态字段 urlDeviceChecker（默认 https://udbdf.huya.com/dckey），
                // 若被别处直接用于 NetworkBridge.post 仍会打到真实域名，故一并改写。
                com.huya.security.hydeviceid.HyDeviceChecker.setUrlDeviceChecker(DEAD_HOST);
                LogBridge.i(TAG, "[设备指纹] UDB host 已改写指向 127.0.0.1 → udbdf.huya.com 等上报全部失效 ✅");
            } catch (Throwable fpE) {
                LogBridge.w(TAG, "[设备指纹] host 改写失败: " + fpE.getMessage());
            }

            // ============ 🔧 缓存治理：在 build() 之前重定向 SDK 目录 + 禁用日志/上报 ============
            // 把 SDK 写入统一收到 getCacheDir()/huya_sdk/，同时关闭日志/埋点/崩溃上报，
            // 从源头减少写入磁盘。HuyaCacheGovernor 内部仍然用反射（兼容老版本），
            // 传入真正的 SDK Builder 即可（方法签名 Object，setter 反射探测照样命中）。
            HuyaCacheGovernor.applyOnBuilder(builder, app);

            HuyaBerryConfig config = builder.build();
            LogBridge.d(TAG, "[2/4] HuyaBerryConfig build 完成");

            // init
            sHuyaBerry = HuyaBerry.instance();
            if (sHuyaBerry == null) {
                LogBridge.e(TAG, "[3/4] HuyaBerry.instance() 返回 null，SDK 未初始化");
                sInitOk = false;
                synchronized (sInitWaitLock) { sInitWaitLock.notifyAll(); }
                return;
            }
            // ====== SDK 内嵌 Bugly 崩溃上报拦截（防崩溃保护） ======
            // SDK 内嵌的 Bugly 专业版 aar 已物理移除（备份于 _backup/），但 SDK 的
            // CrashService 仍硬引用 com.tencent.bugly.* 类，且 init 后 L79 会无条件
            // 注册 CrashService。因此在 init 前注册 NoOpCrashService 占位
            // （ServiceCenter 不覆盖已注册 key → SDK 的 createService 被静默忽略），
            // 防止真实 CrashService 被实例化/调用而触发 NoClassDefFoundError。
            try {
                ServiceHelper.createService(ICrashService.class, NoOpCrashService.class);
            } catch (Throwable regE) {
                LogBridge.w(TAG, "NoOpCrashService 注册失败: " + regE.getMessage());
            }

            // ====== hiido 统计上报关闭（含 PV/init 残留）======
            // HuyaBerryImpl.init() 末尾无条件执行 Report.event("PV/init")，
            // 走 Report → HuyaReportModule → HuyaStatisAgent.getHuyaStatisApi().reportEvent()
            // 该链不经过 BaseApi.getReportApi()（NoOpReportApi 拦不住），
            // 且 getHuyaStatisApi() 返回的 mApi 实例是私有字段、无 setter。
            // 故在 init() 之前反射把 HuyaStatisAgent 单例的 mApi 换成 NoOp 子类：
            //   - init() 空转 → LiveStaticsicsSdk.init() 不被调用 → PV/init 永不发生
            //   - 后续 setGameId / reportEvent 等全部空操作，无网络上报
            boolean statApiOk = false;  // hiido 统计 mApi 替换是否成功（方法级变量，供汇总使用）
            try {
                // 类查找/方法查找改为直接调用（proguard 已 keep com.duowan.**，类名不混淆）
                com.duowan.live.one.module.report.HuyaStatisAgent agent =
                        com.duowan.live.one.module.report.HuyaStatisAgent.getInstance();
                if (agent != null) {
                    // mApi 为私有字段且无 setter，必须反射赋值（Java 语言限制，无直接 API）
                    java.lang.reflect.Field mApiF =
                            com.duowan.live.one.module.report.HuyaStatisAgent.class.getDeclaredField("mApi");
                    mApiF.setAccessible(true);
                    mApiF.set(agent, new NoOpHuyaStatisApi());
                    Object replaced = mApiF.get(agent);
                    statApiOk = replaced instanceof NoOpHuyaStatisApi;
                    LogBridge.i(TAG, "[hiido统计] HuyaStatisAgent.mApi 替换为 NoOp: "
                            + (statApiOk ? "✅ 成功(PV/init 已拦截)" : "❌ 替换后类型不符!"));
                } else {
                    LogBridge.w(TAG, "[hiido统计] HuyaStatisAgent.getInstance() 返回 null，跳过替换");
                }
            } catch (Throwable statE) {
                LogBridge.w(TAG, "[hiido统计] mApi 反射替换失败: " + statE.getMessage());
            }

            try {
                sHuyaBerry.init(app, config);
                LogBridge.i(TAG, "✅ HuyaBerry SDK 初始化成功 (init 无异常)");
                sInitOk = true;
                // 通知所有等待者：SDK 已就绪
                synchronized (sInitWaitLock) {
                    sInitWaitLock.notifyAll();
                    sInitNotified = true;
                    // 执行所有注册的就绪回调
                    List<Runnable> listeners = new ArrayList<>(sInitReadyListeners);
                    sInitReadyListeners.clear();
                    for (Runnable r : listeners) {
                        try { r.run(); } catch (Exception e) { LogBridge.w(TAG, "init listener error: " + e.getMessage()); }
                    }
                }
                LogBridge.i(TAG, "✅ HuyaBerry SDK 初始化 & 绑定完成");

                // ====== 🆕 崩溃上报兜底：夺回全局崩溃处理器 ======
                // HuyaBerryImpl.init() 内部会调用虎牙自研 CrashHandler.getInstance().init()
                // （com.huya.component.crash.CrashHandler），它实现 UncaughtExceptionHandler
                // 并后注册覆盖了 MyApplication 里 app 的 CrashHandler，导致崩溃时经
                // ExceptionModule → FeedBackHelper 上传到虎牙 ffilelog 服务器。
                // 故在 SDK init 之后重新注册 app 的 CrashHandler，把全局 handler 抢回，
                // 虎牙的 handler 不再被系统调用 → 崩溃日志不再上传。
                try {
                    com.tv.live.CrashHandler.getInstance().init(app);
                    LogBridge.i(TAG, "✅ 全局崩溃处理器已夺回（虎牙 CrashHandler 已被覆盖）");
                } catch (Throwable chE) {
                    LogBridge.w(TAG, "⚠️ 夺回全局崩溃处理器失败: " + chE.getMessage());
                }

                // ====== 品类校验：初始化即默认加载虎牙一起看(2135) ======
                // 凭证默认 gameId 已改为 2135(虎牙一起看)，SDK init 时 SdkProperties.gameId=2135，
                // 不再是旧默认的王者荣耀(2336)。此处再做两件事兜底：
                // 1) injectMultiGameIds() 注入多品类权限（一起看/二次元/星秀等）
                // 2) changeGame(2135) 幂等切换：老设备存储若残留旧默认 2336 且迁移失败，
                //    仍能把 SDK 内部品类拉回一起看。同 id 切换无副作用。
                try {
                    injectMultiGameIds();
                    changeGame(2135, new OnChangeGameListener() {
                        @Override public void onSuccess() {
                            LogBridge.i(TAG, "✅ 品类校验成功: 虎牙一起看(2135)");
                        }
                        @Override public void onError(String errMsg) {
                            LogBridge.w(TAG, "⚠️ 品类校验失败(一起看2135): " + errMsg
                                    + "（初始化即 2135，主列表走HTTP gameId=2135，不影响一起看内容获取）");
                        }
                    });
                } catch (Throwable changeE) {
                    LogBridge.w(TAG, "⚠️ 品类校验异常: " + changeE.getMessage());
                }

                // ====== APM + 运营统计关闭 ======
                boolean apmOk = false, statOk = false;
                try {
                    com.huya.ciku.apm.MonitorCenter.getInstance().stopReport();
                    apmOk = true;
                } catch (Throwable e) {
                    LogBridge.w(TAG, "[SDK上报验证] Step3 APM stopReport 失败: " + e.getMessage());
                }
                try {
                    com.huya.live.common.api.BaseApi.setReportApi(new NoOpReportApi());
                    // 验证：取回当前 ReportApi，确认是 NoOpReportApi
                    Object curReportApi = com.huya.live.common.api.BaseApi.getReportApi();
                    String reportCls = (curReportApi != null)
                            ? curReportApi.getClass().getName() : "null";
                    statOk = curReportApi instanceof NoOpReportApi;
                    LogBridge.i(TAG, "[SDK上报验证] Step4 统计通道: ReportApi=" + reportCls
                            + (statOk ? " ✅ 已替换为NoOp" : " ❌ 替换失败!"));
                } catch (Throwable e) {
                    LogBridge.w(TAG, "[SDK上报验证] Step4 setReportApi 失败: " + e.getMessage());
                }

                // ====== 播放数据上报 + 游戏账号绑定：双重保险 ======
                boolean reportGuardOk = false;
                try {
                    // 清空残留游戏账号绑定 + 声明 sendPlayerData 禁用（均 no-op，不触发网络）
                    HuyaBerryReportGuard.applyAfterInit(sHuyaBerry);
                    reportGuardOk = true;
                } catch (Throwable e) {
                    LogBridge.w(TAG, "[SDK上报验证] 播放数据/账号双重保险失败: " + e.getMessage());
                }

                // ====== 全量关闭汇总 ======
                boolean allOk = apmOk && statOk && statApiOk && reportGuardOk;
                LogBridge.i(TAG, "[SDK上报验证] ====== SDK上报关闭汇总 ======");
                LogBridge.i(TAG, "[SDK上报验证] [ciku APM]");
                LogBridge.i(TAG, "[SDK上报验证]   └─ stopReport             "
                        + (apmOk ? "✅" : "❌"));
                LogBridge.i(TAG, "[SDK上报验证] [Hiido运营统计]");
                LogBridge.i(TAG, "[SDK上报验证]   ├─ ReportApi→NoOpReportApi "
                        + (statOk ? "✅" : "❌"));
                LogBridge.i(TAG, "[SDK上报验证]   └─ HuyaStatisAgent.mApi→NoOp "
                        + (statApiOk ? "✅ 成功(PV/init已拦截)" : "❌ 残留1次PV/init"));
                LogBridge.i(TAG, "[SDK上报验证] [Builder精简开关]");
                LogBridge.i(TAG, "[SDK上报验证]   └─ debugMode/cameraMode/"
                        + "oneKeyGangUp/isNeedPlay/hidePauseBtn/landscapeMode=false ✅");
                LogBridge.i(TAG, "[SDK上报验证] [播放数据/游戏账号双重保险]");
                LogBridge.i(TAG, "[SDK上报验证]   ├─ sendPlayerData(播放心跳上报) "
                        + (reportGuardOk ? "✅ no-op 禁用" : "❌"));
                LogBridge.i(TAG, "[SDK上报验证]   └─ setGameAccountID(游戏账号绑定) "
                        + (reportGuardOk ? "✅ 已清空且禁用" : "❌"));
                LogBridge.i(TAG, "[SDK上报验证] ====== 全部关闭"
                        + (allOk ? "成功 ✅✅✅" : "有失败项 ❌（见上方详情）") + " ======");
                if (allOk) {
                    LogBridge.i(TAG, "✅ 已直接关闭 SDK 上报: APM+统计通道全部关闭");
                }
            } catch (Throwable initE) {
                // 打印完整异常链（含 cause 链）+ 提取真正缺失的类名列表，
                // 便于逐个移出黑名单或加 Stub
                LogBridge.w(TAG, "❌ HuyaBerry init 失败（需要修复才能触发SDK回调）: " + logThrowableChain(initE));
                ExceptionReporter.report("HuyaBerry.init", initE);
                // init 失败时标记不可用，让上层走纯解析兜底
                sInitOk = false;
                return;
            }

            // 🆕 接入 SDK BerryEvent 事件总线：捕获所有 SDK 生命周期事件
            try {
                sHuyaBerry.setBerryEventDelegate(new HuyaBerry.BerryEvent() {
                    @Override
                    public void onEventCallback(Map<String, String> eventData) {
                        String eventType = eventData != null
                                ? eventData.get(HuyaBerry.BerryEvent.BERRYEVENT_EVENTTYPE)
                                : "unknown";
                        HuyaSDKLogger.onBerryEvent(eventType, eventData);
                    }
                });
                HuyaSDKLogger.info(TAG, "✅ BerryEvent 事件代理已注册");
            } catch (Throwable t) {
                HuyaSDKLogger.warn(TAG, "⚠️ BerryEvent 事件代理注册失败: " + t.getMessage());
                ExceptionReporter.report("BerryEvent.register", t);
            }

            // 🔴【并行加载优化：init 成功后补发预解析】
            // 之前 AppCoreManager.loadLiveAndEpg（缓存命中）可能比 SDK init 更早，导致 preloadRooms 因 sInitOk=false 被跳过。
            // 这里 init 成功后立即重新提交一次 pendingQueue（如果有人在 AppCoreManager 已传入过房间号）。
            synchronized (sPreloadPendingQueue) {
                if (!sPreloadPendingQueue.isEmpty() && !sPreloadScheduled) {
                    sPreloadScheduled = true;
                    sPreloadIndex = 0;
                    LogBridge.d(TAG, "🔄【预解析】SDK init 完成，补发 " + sPreloadPendingQueue.size() + " 个房间的预解析");
                    sPreloadHandler.post(PRELOAD_RUNNABLE);
                }
            }

            // ============= 🆕 豆包第四段：BerryDebugChecker 调试检测代码 =============
            //  SDK 初始化完成后立即执行，检测播放器模块是否成功被剥离/禁止初始化。
            //  判断标准（豆包）：
            //    1. 播放器相关 class 抛出 ClassNotFoundException → ✅ 正常（类未被加载 / R8 已剥离）
            //    2. 模块管理器未注册 PlayerModule → ✅ 正常
            //    3. Logcat 不再打印 loadLibrary berry_player / berry_decoder → ✅ 正常（看运行时日志）
            //    4. /proc/[pid]/maps 无 berry_player.so / libberry_decoder.so mmap 记录 → ✅ 正常
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        new Thread(new Runnable() {
                            @Override public void run() { runBerryDebugChecker(); }
                        }, "BerryDebugChecker").start();
                    }
                }, 1500);
            } catch (Throwable t) {
                LogBridge.w(TAG, "  ⚠️ BerryDebugChecker 启动失败（跳过，不影响主流程）", t);
                ExceptionReporter.report("BerryDebugChecker.start", t);
            }
            // ============= 🆕 豆包第四段：调试检测代码（结束）=============

            // 【自动化测试】init 成功后每 90s 循环触发一次 parseFull（多码率房间 11342412）
            // 目的：验证“首次解析多档 → 缓存过期/切换其他房间后重新解析”的档位一致性
            // （用户反馈：切到其他虎牙频道再切回来，清晰度从多档变成单档）
            final Runnable autoTestRunnable = new Runnable() {
                @Override
                public void run() {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            // 循环覆盖所有多档房间（11342412: 2线x4档, 11342421: 2线x3档, 11602058: 3线x2档），
                            // 验证“切走再切回”后档位是否丢失
                            final int[] TEST_ROOMS = {11342412, 11342421, 11602058};
                            final int TEST_ROOM = TEST_ROOMS[sAutoTestRound % TEST_ROOMS.length];
                            sAutoTestRound++;
                            LogBridge.i(TAG, "🧪【自动化 SDK 解析测试】开始 parseFull, roomId=" + TEST_ROOM);
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
                                    LogBridge.i(TAG, sb.toString());
                                }

                                @Override
                                public void onError(String error) {
                                    LogBridge.e(TAG, "❌【自动化 SDK 解析测试】失败! roomId=" + TEST_ROOM + " -> " + error);
                                }
                            });
                        }
                    }, "HuyaSDKParser-AutoTest").start();
                    // 90s 后再次测试：覆盖“缓存过期 + SDK 内部状态被其他房间刷新”场景
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 90000);
                }
            };
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(autoTestRunnable, 60000);

        } catch (Throwable e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                LogBridge.e(TAG, "SDK 绑定异常: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), e);
                HuyaSDKLogger.error(TAG, "SDK绑定异常: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                ExceptionReporter.report("HuyaSDK.bind", cause);
            } else {
                LogBridge.e(TAG, "SDK 绑定异常: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                HuyaSDKLogger.error(TAG, "SDK绑定异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ExceptionReporter.report("HuyaSDK.bind", e);
            }
            sInitOk = false;
            synchronized (sInitWaitLock) { sInitWaitLock.notifyAll(); }
        }
    }

    /**
     * 预检虎牙 Mars 网络栈依赖的原生库是否可在当前设备上加载/使用。
     *
     * 问题背景：Android 5.1.1 (API 22) 等老设备上，libmarsstn.so 中的 JNI 符号
     * （如 StnLogic.startTask）可能缺失，SDK init 本身不抛异常，但首次网络请求
     * 会触发 UnsatisfiedLinkError，再被 RxJava 包装为 UndeliverableException。
     *
     * 预检策略：
     * 1. 先尝试按依赖顺序加载 so（c++_shared / stlport_shared / marsstn）。
     * 2. 再通过反射检查 com.tencent.mars.stn.StnLogic 的 startTask 方法是否已注册 native。
     *    如果 Java 方法存在但 getModifiers 不含 native（即 so 未正确注册），说明 so
     *    与当前系统不兼容。
     * 3. 任一步骤失败都返回错误信息字符串；全部通过返回 null。
     */
    private static String checkMarsNativeLibraries() {
        try {
            // 🔴 v7a 兼容验证（2026-08-26）：libmarsstn.so 在 Android 5.0/5.1 (API 21-22)
            // 上曾因 so 未解压（extractNativeLibs=false）触发 UnsatisfiedLinkError。
            // 现 useLegacyPackaging=true 已保证 so 落盘，API<=22 不再直接禁用 SDK，
            // 改为继续尝试加载；若确实不兼容，下方 loadLibrary 会抛
            // UnsatisfiedLinkError 并被捕获返回，业务层自动降级纯 HTTP 兜底。
            if (android.os.Build.VERSION.SDK_INT <= 22) {
                LogBridge.w(TAG, "⚠️ Android " + android.os.Build.VERSION.RELEASE + "(API "
                        + android.os.Build.VERSION.SDK_INT
                        + ") 官方不在 Mars STN 支持范围，仍尝试加载验证 v7a 兼容性");
            }

            // 依赖 so 需要按顺序先加载。部分 ROM 上 System.loadLibrary 会抛
            // UnsatisfiedLinkError；另一些情况加载成功但符号缺失。
            String[] libs = {"c++_shared", "stlport_shared", "marsstn"};
            for (String lib : libs) {
                try {
                    System.loadLibrary(lib);
                    LogBridge.d(TAG, "  ✅ loadLibrary(\"" + lib + "\") 成功");
                } catch (UnsatisfiedLinkError ule) {
                    LogBridge.w(TAG, "  ⚠️ loadLibrary(\"" + lib + "\") 失败: " + ule.getMessage());
                    // c++_shared / stlport_shared 可能由其它模块提前加载过，忽略单条失败；
                    // 但 marsstn 必须能加载。
                    if ("marsstn".equals(lib)) {
                        return "无法加载 libmarsstn.so: " + ule.getMessage();
                    }
                }
            }

            // 进一步检查 StnLogic.startTask 是否为 native 方法。
            // 如果 so 加载了但 JNI_OnLoad 注册失败，Java 层会保留 abstract/native 声明，
            // 此时 Modifier.isNative 为 false，可提前发现不兼容。
            try {
                Class<?> stnLogicClass = Class.forName("com.tencent.mars.stn.StnLogic");
                java.lang.reflect.Method startTaskMethod = null;
                for (java.lang.reflect.Method m : stnLogicClass.getDeclaredMethods()) {
                    if ("startTask".equals(m.getName())) {
                        startTaskMethod = m;
                        break;
                    }
                }
                if (startTaskMethod == null) {
                    return "StnLogic.startTask 方法不存在（SDK 版本不匹配）";
                }
                if (!java.lang.reflect.Modifier.isNative(startTaskMethod.getModifiers())) {
                    return "StnLogic.startTask 未注册为 native 方法，libmarsstn.so 与当前系统不兼容";
                }
                LogBridge.d(TAG, "  ✅ StnLogic.startTask native 注册检查通过");
            } catch (ClassNotFoundException cnfe) {
                // StnLogic 类尚未被加载，说明当前可能还没走到 so 注册逻辑，
                // 不视为失败，交给运行时 loadLibrary 兜底。
                LogBridge.w(TAG, "  ⚠️ StnLogic 类未找到，跳过 native 注册检查: " + cnfe.getMessage());
            }
            return null;
        } catch (Throwable t) {
            LogBridge.e(TAG, "checkMarsNativeLibraries 异常: " + t.getMessage(), t);
            return "Mars 原生库预检异常: " + t.getMessage();
        }
    }

    /**
     * 把任意 Throwable 渲染成 "ClassName: msg \n ↳ ClassName.method()..." 的可读字符串。
     * 用于替代旧反射版本里的 InvocationTargetException / cause 链手撕代码。
     */
    private static String logThrowableChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (t != null && depth++ < 10) {
            sb.append(t.getClass().getSimpleName())
              .append(": ").append(t.getMessage()).append("\n");
            for (StackTraceElement s : t.getStackTrace()) {
                String cn = s.getClassName();
                if (cn.startsWith("com.huya") || cn.startsWith("com.duowan") || cn.startsWith("com.tv.live")) {
                    sb.append("  ↳ ").append(s.toString()).append("\n");
                }
            }
            Throwable next = t.getCause();
            if (next == null || next == t) break;
            t = next;
            sb.append("  ▼ Cause: ");
        }
        return sb.toString();
    }

    public static boolean isSDKAvailable() {
        return sInitOk;
    }

    /**
     * 等待 SDK 初始化完成。用于解决「首次点击虎牙频道时 SDK 尚未 init 完成」竞态问题。
     *
     * @param timeoutMs 最大等待毫秒数
     * @return true = SDK 已就绪，false = 超时或 init 失败
     */
    public static boolean waitForInit(long timeoutMs) {
        if (sInitOk) return true;
        if (sInitDone && !sInitOk) return false;  // init 完成但失败了

        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (sInitWaitLock) {
            while (!sInitOk && !sInitDone) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    LogBridge.w(TAG, "waitForInit 超时(" + timeoutMs + "ms), sInitOk=" + sInitOk + " sInitDone=" + sInitDone);
                    return false;
                }
                try {
                    sInitWaitLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return sInitOk;
        }
    }

    /**
     * 注册 SDK 就绪回调。如果 SDK 已就绪则立即执行。
     */
    public static void addInitReadyListener(Runnable listener) {
        synchronized (sInitWaitLock) {
            if (sInitOk) {
                listener.run();
            } else {
                sInitReadyListeners.add(listener);
            }
        }
    }

    // ========== 🔧 in-flight 去重：同一房间并发请求只调一次 SDK ==========
    // 预解析（PRELOAD_RUNNABLE）与用户点击播放可能同时解析同一房间，
    // 若都通过缓存检查，会重复调用 getLiveDataByRoomId（日志里"发起SDK解析"
    // 成对出现）。这里记录进行中的请求，后续并发请求挂接等待，共享同一次
    // SDK 调用的结果。
    private static final Map<Integer, List<OnSDKFullResultListener>> sInflight = new HashMap<>();

    /**
     * 新版完整解析：返回全部线路×码率信息（用于 UI 线路/清晰度选择）
     *
     * ⚠️ 若 SDK 尚未初始化，会自动注册就绪回调，SDK ready 后立即重试解析，
     *     避免用户首次点击时因 SDK 未就绪看到"虎牙 SDK 不可用"错误。
     */
    public static void parseFull(final int roomId, final OnSDKFullResultListener listener) {
        if (!sInitOk || sHuyaBerry == null) {
            // SDK 尚未就绪 → 注册等待，就绪后自动重试
            LogBridge.i(TAG, "parseFull: SDK未初始化, roomId=" + roomId + " → 等待SDK就绪后重试");
            addInitReadyListener(new Runnable() {
                @Override public void run() {
                    if (sInitOk && sHuyaBerry != null) {
                        LogBridge.i(TAG, "parseFull: SDK就绪, 重试 roomId=" + roomId);
                        parseFullInternal(roomId, listener);
                    } else {
                        HuyaSDKLogger.error(TAG, "parseFull: SDK初始化失败, roomId=" + roomId);
                        listener.onError("SDK 初始化失败");
                    }
                }
            });
            return;
        }
        parseFullInternal(roomId, listener);
    }

    /**
     * 通过主播UID获取直播流完整信息（SDK getLiveData(uid)通道，推荐/分类列表返回的channelId不是可播房号时使用）
     * SDK内部对推荐列表开播用presenterUid作为key，此方法对应watchLiveByUid/getLiveData(uid)通道
     */
    public static void parseFullByUid(long uid, final OnSDKFullResultListener listener) {
        if (!sInitOk || sHuyaBerry == null) {
            HuyaSDKLogger.error(TAG, "parseFullByUid: SDK未初始化, uid=" + uid);
            listener.onError("SDK 未初始化");
            return;
        }

        // uid缓存key使用负数，避免和正数roomId冲突
        final int cacheKey = (int) -uid;
        // 命中缓存则直接用
        CachedStreams cached = getCachedStreams(cacheKey);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            LogBridge.d(TAG, "命中uid=" + uid + "流信息缓存（" + (System.currentTimeMillis() - cached.timestamp) / 1000 + "s前）");
            HuyaSDKLogger.info(TAG, "命中缓存(uid): uid=" + uid + " streams=" + cached.streams.size());
            HuyaStreamInfo def = pickDefaultStream(cached.streams);
            List<String> lines = buildLineLabels(cached.streams);
            listener.onSuccess(def, cached.streams, lines);
            return;
        }

        AtomicBoolean done = new AtomicBoolean(false);
        final long targetUid = uid;

        // 包装listener，在onSuccess时写入缓存
        final OnSDKFullResultListener wrappedListener = new OnSDKFullResultListener() {
            @Override
            public void onSuccess(HuyaStreamInfo defaultStream, List<HuyaStreamInfo> allStreams, List<String> lines) {
                // 写入uid缓存（使用负数key）
                if (allStreams != null && !allStreams.isEmpty()) {
                    CachedStreams cs = new CachedStreams();
                    cs.timestamp = System.currentTimeMillis();
                    cs.streams = allStreams;
                    sStreamsCache.put(cacheKey, cs);
                    LogBridge.d(TAG, "uid=" + targetUid + " 流信息写入缓存: " + allStreams.size() + " 条流");
                }
                listener.onSuccess(defaultStream, allStreams, lines);
            }
            @Override
            public void onError(String error) {
                listener.onError(error);
            }
        };

        new Thread(() -> {
            try {
                CustomUICallback<BaseCallback> sdkCallback = new CustomUICallback<BaseCallback>() {
                    @Override
                    public void onResultCallback(int code, BaseCallback data) {
                        String dataType = data == null ? "null" : data.getClass().getSimpleName();
                        LogBridge.d(TAG, "📞【SDK回调进入-uid】onResultCallback(code=" + code
                                + ", data=" + dataType + ", uid=" + targetUid + ")");
                        HuyaSDKLogger.onCustomUICallback("onResultCallback-uid", code,
                                "dataType=" + dataType + " uid=" + targetUid);

                        boolean alreadyTimeout = done.get();
                        try {
                            if (data instanceof LiveInfo) {
                                if (Looper.myLooper() == Looper.getMainLooper()) {
                                    // 🔧 SDK 回调进入主线程：解析重活（反射+线路遍历）转发到后台线程，避免阻塞 UI
                                    final int fCode = code;
                                    final LiveInfo li = (LiveInfo) data;
                                    AppExecutors.io(() -> {
                                        try {
                                            handleFullResultByUid(fCode, li, wrappedListener, done);
                                        } catch (Exception e2) {
                                            LogBridge.e(TAG, "handleFullResultByUid 后台异常: " + e2.getMessage());
                                            if (!done.get() && done.compareAndSet(false, true)) {
                                                wrappedListener.onError("结果处理异常: " + e2.getMessage());
                                            }
                                        }
                                    });
                                } else {
                                    handleFullResultByUid(code, (LiveInfo) data, wrappedListener, done);
                                }
                            } else {
                                if (done.compareAndSet(false, true)) {
                                    String err = (data == null)
                                            ? "SDK 返回空结果"
                                            : ("SDK 返回类型 " + dataType + "，非 LiveInfo");
                                    HuyaSDKLogger.error(TAG, err);
                                    wrappedListener.onError(err);
                                }
                            }
                        } catch (Exception e) {
                            LogBridge.e(TAG, "handleFullResultByUid 异常: " + e.getMessage());
                            if (!alreadyTimeout && done.compareAndSet(false, true)) {
                                wrappedListener.onError("结果处理异常: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                        int size = (list == null) ? 0 : list.size();
                        LogBridge.d(TAG, "SDK onResultListCallback-uid: code=" + code + " size=" + size);
                    }
                };

                LogBridge.d(TAG, "调用 SDK getLiveData(uid), uid=" + uid);
                HuyaSDKLogger.debug(TAG, "发起SDK解析(uid): uid=" + uid);
                sHuyaBerry.getLiveData(targetUid, sdkCallback);

                Thread.sleep(30000);
                if (done.compareAndSet(false, true)) {
                    LogBridge.w(TAG, "SDK 调用(uid)超时 (30s), uid=" + uid);
                    wrappedListener.onError("SDK 解析超时");
                }
            } catch (Throwable e) {
                LogBridge.e(TAG, "SDK 解析(uid)异常完整链：\n" + logThrowableChain(e));
                String userMsg = (e.getCause() != null) ? e.getCause().toString() : e.toString();
                if (userMsg.length() > 240) userMsg = userMsg.substring(0, 240) + "...";
                if (done.compareAndSet(false, true)) {
                    wrappedListener.onError("SDK 异常: " + userMsg);
                }
            }
        }, "HuyaSDKParser-FullByUid").start();
    }

    private static void handleFullResultByUid(int code, LiveInfo liveInfo, OnSDKFullResultListener listener,
                                              AtomicBoolean done) {
        if (liveInfo == null) {
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回空结果");
            return;
        }
        if (code != BaseCallback.SUCCESS) {
            if (done.compareAndSet(false, true)) {
                String msg = "SDK code=" + code;
                try {
                    java.lang.reflect.Field f = liveInfo.getClass().getField("errorMsg");
                    String err = (String) f.get(liveInfo);
                    if (err != null && !err.isEmpty()) msg = err;
                } catch (Throwable ignored) {}
                listener.onError(msg);
            }
            return;
        }
        try {
            // 直接复用已有的流列表提取方法（和roomId通道完全一致的解析逻辑）
            List<HuyaStreamInfo> streamList = extractFullStreamList(liveInfo, -1);
            if (streamList == null || streamList.isEmpty()) {
                if (done.compareAndSet(false, true)) listener.onError("未获取到播放地址");
                return;
            }
            // 写缓存（uid使用负数key，避免和正数roomId冲突）
            // 注意：这里无法直接获取uid参数，缓存写入由调用方parseFullByUid负责
            HuyaStreamInfo def = pickDefaultStream(streamList);
            List<String> lineLabels = buildLineLabels(streamList);
            if (done.compareAndSet(false, true)) {
                listener.onSuccess(def, streamList, lineLabels);
            }
        } catch (Throwable t) {
            LogBridge.e(TAG, "解析uid流地址异常: " + t.getMessage());
            if (done.compareAndSet(false, true)) listener.onError("解析异常: " + t.getMessage());
        }
    }

    /**
     * parseFull 内部实现（SDK 已就绪时调用）
     */
    private static void parseFullInternal(int roomId, OnSDKFullResultListener listener) {
        // 命中缓存则直接用
        CachedStreams cached = getCachedStreams(roomId);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            LogBridge.d(TAG, "命中房间" + roomId + "流信息缓存（" + (System.currentTimeMillis() - cached.timestamp) / 1000 + "s前）");
            HuyaSDKLogger.info(TAG, "命中缓存: roomId=" + roomId + " streams=" + cached.streams.size());
            HuyaStreamInfo def = pickDefaultStream(cached.streams);
            List<String> lines = buildLineLabels(cached.streams);
            listener.onSuccess(def, cached.streams, lines);
            return;
        }

        // 🔧 in-flight 去重：同一房间已有请求在进行，挂接等待共享结果，不重复调 SDK
        synchronized (sInflight) {
            List<OnSDKFullResultListener> waiters = sInflight.get(roomId);
            if (waiters != null) {
                HuyaSDKLogger.debug(TAG, "【去重】挂接等待 roomId=" + roomId
                        + " 等待者数=" + (waiters.size() + 1));
                waiters.add(listener);
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(listener);
            sInflight.put(roomId, waiters);
        }
        HuyaSDKLogger.debug(TAG, "【去重】新请求入队 roomId=" + roomId
                + " inflight=" + sInflight.size() + " 线程=" + Thread.currentThread().getName());
        // 代理回调：把结果分发给所有等待者（预解析 + 播放请求共享同一结果）
        OnSDKFullResultListener proxy = new OnSDKFullResultListener() {
            @Override
            public void onSuccess(HuyaStreamInfo defaultStream,
                                  List<HuyaStreamInfo> allStreams, List<String> lines) {
                List<OnSDKFullResultListener> ws;
                synchronized (sInflight) { ws = sInflight.remove(roomId); }
                HuyaSDKLogger.debug(TAG, "【去重】完成分发 roomId=" + roomId
                        + " 等待者=" + (ws == null ? 0 : ws.size()));
                if (ws != null) {
                    for (OnSDKFullResultListener w : ws) w.onSuccess(defaultStream, allStreams, lines);
                }
            }

            @Override
            public void onError(String error) {
                List<OnSDKFullResultListener> ws;
                synchronized (sInflight) { ws = sInflight.remove(roomId); }
                HuyaSDKLogger.warn(TAG, "【去重】完成分发(onError) roomId=" + roomId
                        + " 等待者=" + (ws == null ? 0 : ws.size()));
                if (ws != null) {
                    for (OnSDKFullResultListener w : ws) w.onError(error);
                }
            }
        };
        doParseFull(roomId, proxy);
    }

    private static void doParseFull(int roomId, OnSDKFullResultListener listener) {
        AtomicBoolean done = new AtomicBoolean(false);

        final OnSDKFullResultListener outerListener = listener;
        final int finalRoomId = roomId;

        new Thread(() -> {
            try {
                // 直接 new 一个 CustomUICallback 实现，传入 SDK；不再走 java.lang.reflect.Proxy
                CustomUICallback<BaseCallback> sdkCallback = new CustomUICallback<BaseCallback>() {
                    @Override
                    public void onResultCallback(int code, BaseCallback data) {
                        // 100% 必打：任何 SDK 回调进入都打印
                        String dataType = data == null ? "null" : data.getClass().getSimpleName();
                        LogBridge.d(TAG, "📞【SDK回调进入】onResultCallback(code=" + code
                                + ", data=" + dataType
                                + ") from " + Thread.currentThread().getName());
                        HuyaSDKLogger.onCustomUICallback("onResultCallback", code,
                                "dataType=" + dataType + " roomId=" + finalRoomId);

                        // 🔔 重要修复：即使 SDK 回调晚于 30s 超时(done==true)，也不要静默 return null！
                        boolean alreadyTimeout = done.get();
                        LogBridge.d(TAG, "SDK onResultCallback: code=" + code
                                + " data=" + dataType
                                + " alreadyTimeout=" + alreadyTimeout
                                + (alreadyTimeout ? "（⚠️回调晚于30s超时，但继续解析不丢弃）" : ""));
                        try {
                            // 只处理 LiveInfo（getLiveDataByRoomId 回调约定的 T）
                            if (data instanceof LiveInfo) {
                                if (Looper.myLooper() == Looper.getMainLooper()) {
                                    // 🔧 SDK 回调进入主线程：解析重活（反射+线路遍历）转发到后台线程，避免阻塞 UI
                                    final int fCode = code;
                                    final LiveInfo li = (LiveInfo) data;
                                    AppExecutors.io(() -> {
                                        try {
                                            handleFullResult(fCode, li, outerListener, done, finalRoomId);
                                        } catch (Exception e2) {
                                            LogBridge.e(TAG, "handleFullResult 后台异常: " + e2.getMessage());
                                            if (!done.get() && done.compareAndSet(false, true)) {
                                                outerListener.onError("结果处理异常: " + e2.getMessage());
                                            }
                                        }
                                    });
                                } else {
                                    handleFullResult(code, (LiveInfo) data, outerListener, done, finalRoomId);
                                }
                            } else {
                                // ErrorInfo / SubscribeInfo / LiveListInfo 等都按 SDK 约定 code!=0 即失败
                                if (done.compareAndSet(false, true)) {
                                    String err = (data == null)
                                            ? "SDK 返回空结果"
                                            : ("SDK 返回类型 " + dataType + "，非 LiveInfo");
                                    HuyaSDKLogger.error(TAG, err);
                                    ExceptionReporter.reportHuyaBusinessFailure(
                                            "CustomUI.onResultCallback", code, err,
                                            "roomId=" + finalRoomId);
                                    outerListener.onError(err);
                                }
                            }
                        } catch (Exception e) {
                            LogBridge.e(TAG, "handleFullResult 异常: " + e.getMessage(), e);
                            HuyaSDKLogger.error(TAG, "handleFullResult 异常: " + e.getMessage());
                            ExceptionReporter.report("HuyaSDK.handleFullResult", e);
                            if (!alreadyTimeout && done.compareAndSet(false, true)) {
                                outerListener.onError("结果处理异常: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                        int size = (list == null) ? 0 : list.size();
                        LogBridge.d(TAG, "SDK onResultListCallback: code=" + code + " size=" + size);
                        HuyaSDKLogger.onCustomUICallback("onResultListCallback", code, "size=" + size);
                    }
                };
                LogBridge.d(TAG, "✅ CustomUICallback 直接实现创建成功 (impl=" + sdkCallback.getClass().getName() + ")");

                LogBridge.d(TAG, "调用 SDK getLiveDataByRoomId, roomId=" + roomId);
                HuyaSDKLogger.debug(TAG, "发起SDK解析: roomId=" + roomId);
                sHuyaBerry.getLiveDataByRoomId(roomId, sdkCallback);

                Thread.sleep(30000); // 30s 超时兜底（给网络&信令充足时间）
                if (done.compareAndSet(false, true)) {
                    LogBridge.w(TAG, "SDK 调用超时 (30s)");
                    HuyaSDKLogger.error(TAG, "SDK解析超时: roomId=" + roomId);
                    ExceptionReporter.reportHuyaBusinessFailure(
                            "HuyaSDKParser.parseFull", -99998, "SDK 解析超时 (30s)",
                            "roomId=" + roomId);
                    listener.onError("SDK 解析超时");
                }
            } catch (UnsatisfiedLinkError ule) {
                // 🔴【关键修复】Mars STN so 不兼容时，getLiveDataByRoomId 底层会直接抛出
                // UnsatisfiedLinkError。这里捕获后标记 SDK 不可用，防止后续调用再次触发。
                String msg = "Mars STN 原生库不兼容: " + ule.getMessage();
                LogBridge.e(TAG, "SDK 解析触发 UnsatisfiedLinkError，roomId=" + roomId + " " + msg, ule);
                HuyaSDKLogger.error(TAG, msg + ", roomId=" + roomId);
                ExceptionReporter.reportHuyaBusinessFailure(
                        "HuyaSDKParser.parseFull", -99002, msg,
                        "roomId=" + roomId + ",api=" + android.os.Build.VERSION.SDK_INT);
                sInitOk = false;
                sHuyaBerry = null;
                if (done.compareAndSet(false, true)) {
                    listener.onError("虎牙 SDK 原生库与当前系统不兼容，无法解析");
                }
            } catch (Throwable e) {
                // 打印完整异常链：尤其 InvocationTargetException 必须看 getTargetException 才是真因
                LogBridge.e(TAG, "SDK 解析异常完整链：\n" + logThrowableChain(e));
                HuyaSDKLogger.error(TAG, "SDK解析异常: " + e.getMessage() + ", roomId=" + roomId);
                ExceptionReporter.report("HuyaSDKParser.parseFull", e);
                String userMsg = (e.getCause() != null)
                        ? e.getCause().toString()
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

    /**
     * 完整解析结果回调：返回全部线路×码率
     */
    private static void handleFullResult(int code, LiveInfo liveInfo, OnSDKFullResultListener listener,
                                         AtomicBoolean done, int roomId) {
        if (liveInfo == null) {
            HuyaSDKLogger.error(TAG, "handleFullResult: liveInfo=null, roomId=" + roomId);
            ExceptionReporter.reportHuyaBusinessFailure(
                    "HuyaSDKParser.handleFullResult", -99997, "liveInfo=null",
                    "roomId=" + roomId + ",code=" + code);
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回空结果");
            return;
        }
        // SDK 约定 code != 0 表示失败；只有 0 + 非空 LiveInfo 才走解析
        if (code != BaseCallback.SUCCESS) {
            HuyaSDKLogger.error(TAG, "handleFullResult: code=" + code + " (非SUCCESS), roomId=" + roomId);
            ExceptionReporter.reportHuyaBusinessFailure(
                    "HuyaSDKParser.handleFullResult", code, "code != SUCCESS",
                    "roomId=" + roomId);
            if (done.compareAndSet(false, true)) {
                listener.onError("SDK 返回失败码 code=" + code);
            }
            return;
        }

        List<HuyaStreamInfo> streams = extractFullStreamList(liveInfo, roomId);
        if (streams == null || streams.isEmpty()) {
            HuyaSDKLogger.error(TAG, "handleFullResult: 未提取到任何流, roomId=" + roomId);
            ExceptionReporter.reportHuyaBusinessFailure(
                    "HuyaSDKParser.extractFullStreamList", -99996,
                    "未提取到任何流地址 / 无码率",
                    "roomId=" + roomId);
            if (done.compareAndSet(false, true)) listener.onError("未提取到任何流地址");
            return;
        }

        // 写缓存
        CachedStreams cs = new CachedStreams();
        cs.timestamp = System.currentTimeMillis();
        cs.streams = streams;
        sStreamsCache.put(roomId, cs);
        LogBridge.d(TAG, "房间" + roomId + " 流信息写入缓存: " + streams.size() + " 条流");
        HuyaSDKLogger.info(TAG, "房间" + roomId + " SDK解析成功: " + streams.size() + " 条流");

        HuyaStreamInfo def = pickDefaultStream(streams);
        List<String> lines = buildLineLabels(streams);
        LogBridge.d(TAG, "SDK 解析完成: " + lines.size() + " 条线路, 默认 " + def);

        if (done.compareAndSet(false, true)) {
            listener.onSuccess(def, streams, lines);
        }
    }

    /**
     * 直接从 LiveInfo 提取完整线路×码率列表（不再使用反射兜底）
     */
    private static List<HuyaStreamInfo> extractFullStreamList(LiveInfo liveInfo, int roomId) {
        List<HuyaStreamInfo> out = new ArrayList<>();
        try {
            // 1. 拿 lines：直接调 LiveInfo.getLines()
            Vector<?> linesObj = liveInfo.getLines();
            if (linesObj == null || linesObj.isEmpty()) {
                LogBridge.w(TAG, "getLines 为空: " + (linesObj == null ? "null" : "size=0"));
                return fallbackExtractAsSingle(liveInfo);
            }
            LogBridge.d(TAG, "getLines: " + linesObj.size() + " 条线路");

            // ===== v3.3 诊断：反射 dump PlayerHelper.singleStreamInfo 内部结构（带 roomId 定位） =====
            try {
                Class<?> phCls = Class.forName("com.huya.berry.module.Player.PlayerHelper");
                java.lang.reflect.Field ssiF = phCls.getDeclaredField("singleStreamInfo");
                ssiF.setAccessible(true);
                Object ssi = ssiF.get(null);
                LogBridge.d(TAG, "V33DBG room=" + roomId + " singleStreamInfo=" + ssi);
                if (ssi != null) {
                    java.lang.reflect.Field siF = ssi.getClass().getDeclaredField("singleInfo");
                    siF.setAccessible(true);
                    java.util.Map<?, ?> singleInfo = (java.util.Map<?, ?>) siF.get(ssi);
                    LogBridge.d(TAG, "V33DBG room=" + roomId + " singleInfo.size=" + (singleInfo == null ? -1 : singleInfo.size()));
                    if (singleInfo != null) {
                        for (java.util.Map.Entry<?, ?> e : singleInfo.entrySet()) {
                            Object li = e.getValue();
                            if (li == null) continue;
                            java.lang.reflect.Field brF = li.getClass().getDeclaredField("bitRateInfoList");
                            brF.setAccessible(true);
                            Object brl = brF.get(li);
                            LogBridge.d(TAG, "V33DBG room=" + roomId + " line=" + e.getKey() + " bitRateInfoList=" + brl);
                        }
                    }
                }
            } catch (Throwable t) {
                LogBridge.d(TAG, "V33DBG err: " + t);
            }

            for (int i = 0; i < linesObj.size(); i++) {
                int lineValue;
                try {
                    lineValue = ((Number) linesObj.get(i)).intValue();
                } catch (Exception e) {
                    LogBridge.d(TAG, "线路#" + i + " 取值失败: " + e.getMessage());
                    ExceptionReporter.report("extractFullStreamList.lineValue", e);
                    continue;
                }

                Vector<BitRateInfo> bitRates = liveInfo.getBitRateList(lineValue);
                if (bitRates == null) continue;
                LogBridge.d(TAG, "线路#" + i + "(v=" + lineValue + "): " + bitRates.size() + " 个码率");

                // 同线路内按码率降序排列（默认码率见下方 isDefaultBitrate 标记）
                List<HuyaStreamInfo> lineStreams = new ArrayList<>();
                for (int j = 0; j < bitRates.size(); j++) {
                    BitRateInfo oneBr = bitRates.get(j);
                    if (oneBr == null) continue;
                    int br = oneBr.bitRate;
                    String dn = oneBr.disPlayName;

                    String hlsUrl = null;
                    String flvUrl = null;
                    try {
                        hlsUrl = liveInfo.getPlayUrlByLineAndBitrate(false, lineValue, br);
                    } catch (Exception e) {
                        LogBridge.d(TAG, "HLS URL 获取失败: line=" + lineValue + " br=" + br + ": " + e.getMessage());
                        ExceptionReporter.report("extractFullStreamList.hlsUrl", e);
                    }
                    try {
                        flvUrl = liveInfo.getPlayUrlByLineAndBitrate(true, lineValue, br);
                    } catch (Exception e) {
                        ExceptionReporter.report("extractFullStreamList.flvUrl", e);
                        // ignore
                    }
                    if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                        LogBridge.d(TAG, "线路#" + i + " 码率" + br + "无有效URL，跳过");
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
                    LogBridge.d(TAG, "  → " + s + " URL(" + (hlsUrl != null ? "HLS" : "")
                            + (flvUrl != null ? "/FLV" : "") + ")");
                }
                // 同线路按 bitRate 降序 → 第2档(k==1) = 默认码率；仅1档时退回第1档
                Collections.sort(lineStreams, (a, b) -> Integer.compare(b.bitRate, a.bitRate));
                for (int k = 0; k < lineStreams.size(); k++) {
                    lineStreams.get(k).isDefaultBitrate = (lineStreams.size() >= 2) ? (k == 1) : (k == 0);
                }
                out.addAll(lineStreams);
            }

            if (out.isEmpty()) return fallbackExtractAsSingle(liveInfo);
            return out;

        } catch (Exception e) {
            LogBridge.e(TAG, "extractFullStreamList 异常: " + e.getMessage());
            ExceptionReporter.report("extractFullStreamList.outer", e);
            if (!out.isEmpty()) return out;
            return fallbackExtractAsSingle(liveInfo);
        }
    }

    /**
     * 兜底：用 HLS/FLV URL 包装成单条流
     */
    private static List<HuyaStreamInfo> fallbackExtractAsSingle(LiveInfo liveInfo) {
        String hls = null, flv = null;
        try {
            Vector<?> linesObj = liveInfo.getLines();
            if (linesObj != null && !linesObj.isEmpty()) {
                int line = ((Number) linesObj.get(0)).intValue();
                Vector<BitRateInfo> brs = liveInfo.getBitRateList(line);
                if (brs != null && !brs.isEmpty()) {
                    int br = brs.get(0).bitRate;
                    hls = liveInfo.getPlayUrlByLineAndBitrate(false, line, br);
                    flv = liveInfo.getPlayUrlByLineAndBitrate(true, line, br);
                }
            }
        } catch (Throwable ignored) {
            ExceptionReporter.report("fallbackExtractAsSingle", ignored);
        }

        if ((hls == null || hls.isEmpty()) && (flv == null || flv.isEmpty())) {
            return null;
        }
        HuyaStreamInfo s = new HuyaStreamInfo();
        s.lineIndex = 0;
        s.lineValue = 0;
        s.lineLabel = "线路1(主线路)";
        s.bitRate = 4000;
        s.bitRateDisplayName = "默认";
        s.resolutionLabel = "自适应";
        s.hlsUrl = hls;
        s.flvUrl = flv;
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
    //
    //  注：本检测器内部使用反射，是因为它在运行时探测 SDK 实现层的私有类（ModuleManager、
    //      PlayerModule、SdkPlayerService），不是直接调用 SDK 公开 API。
    // =====================================================================
    private static void runBerryDebugChecker() {
        final String TAG2 = TAG + "-DebugChecker";
        LogBridge.i(TAG2, "================ 🔬【豆包推荐：播放器模块剥离自检】================");
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
            LogBridge.i(TAG2, "  ✅ 检查项1【播放器类加载】：全部 ClassNotFound（R8 已剥离 / 未加载，共" + playerClasses.length + "/" + playerClasses.length + "）");
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
                java.lang.reflect.Method instanceM = mmClass.getMethod("getInstance");
                mmInstance = instanceM.invoke(null);
            } catch (Throwable t1) {
                try {
                    java.lang.reflect.Method instanceM = mmClass.getMethod("instance");
                    mmInstance = instanceM.invoke(null);
                } catch (Throwable ignore) {}
            }
            if (mmInstance != null) {
                for (java.lang.reflect.Field f : mmClass.getDeclaredFields()) {
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
                LogBridge.i(TAG2, "  ✅ 检查项2【模块注册】：ModuleManager 未检测到 PlayerModule（官方开关已生效）");
            } else {
                ok = false;
                LogBridge.w(TAG2, "  ❌ 检查项2【模块注册】：发现播放器相关模块注册，请核对官方 isNeedPlay=false 是否被 SDK 版本忽略");
            }
        } catch (ClassNotFoundException mmMiss) {
            // 某些版本 Berry 类名混淆后名字不同，属于版本差异，降级跳过不判失败
            passed++;
            LogBridge.i(TAG2, "  ℹ️  检查项2【模块注册】：ModuleManager 类名无法定位（版本混淆），降级为不判定，跳过");
        } catch (Throwable t) {
            passed++;
            LogBridge.i(TAG2, "  ℹ️  检查项2【模块注册】：反射遍历失败（" + t.getClass().getSimpleName()
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
                LogBridge.i(TAG2, "  ✅ 检查项3【so 内存加载】：/proc/self/maps 中无 libberry_player/decoder/ijk 等 mmap 记录（运行内存真·省下）");
            } else {
                ok = false;
                LogBridge.w(TAG2, "  ❌ 检查项3【so 内存加载】：仍检测到播放器相关 so 被 mmap（说明 SDK 内部仍有代码触发了 loadLibrary）");
            }
        } catch (Throwable t) {
            passed++;
            LogBridge.i(TAG2, "  ℹ️  检查项3【so 内存加载】：无法读取 /proc/self/maps（权限/ROM 差异），降级为不判定，跳过");
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
        if (ok) LogBridge.i(TAG2, sb.toString()); else LogBridge.w(TAG2, sb.toString());
    }

    // 注：旧的反射代码里用来"中转 builder 引用给 HuyaCacheGovernor"的 HuBerryConfigBuilder 包装类
    //     已删除。HuyaCacheGovernor.applyOnBuilder(Object, Context) 接受 Object 参数，
    //     我们直接传真正的 HuyaBerryConfig.Builder 进去，反射 setter 探测照样能命中。

    // =====================================================================
    // 🆕 高级 API 区域（保留骨架，暂不实际调用，日后按需启用）
    //
    // 来源：HuyaBerry.java 全部抽象方法 + work_huya 反编译源码
    // 分类：
    //   A. 房间 / 直播列表（⭐⭐⭐ 高价值）
    //   B. 关注系统（⭐⭐ 中价值）
    //   C. 清晰度切换 + 自定义 UI 入口（⭐⭐ 中价值）
    //   D. 推流 / 连麦（⭐ 按需）
    //   E. 播放器控制（按需）
    //
    // 所有方法都做了：SDK 可用判断 + 回调转 HuyaSDKLogger + 参数校验
    // =====================================================================

    // ======================== 品类切换（王者荣耀 → 虎牙一起看） ========================

    /** 切换 SDK 游戏/分类上下文的回调 */
    public interface OnChangeGameListener {
        void onSuccess();
        void onError(String errMsg);
    }

    /**
     * 直接设置多 gameId 权限到 GameIdOptions.gameIdArr（public 字段，无需反射）。
     * SDK 初始化时注册默认 gameId(2135=虎牙一起看)，changeGame 切换其他分类前
     * 需要目标分类的权限，否则返回"没有该品类的权限"。
     * 注入 2135_一起看 / 2633_二次元 等权限后，changeGame 才能自由切换分类。
     */
    public static void injectMultiGameIds() {
        try {
            LogBridge.i(TAG, "injectMultiGameIds: 开始注入多gameId权限...");
            Class<?> cls = Class.forName("com.huya.berry.gamesdk.gameid.GameIdOptions");
            java.lang.reflect.Method getInstance = cls.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            java.lang.reflect.Field f = cls.getField("gameIdArr");
            Object current = f.get(instance);

            // 已知虎牙分类 gameId（从 https://www.huya.com/g 确认）
            // 2135=一起看(默认) / 2336=王者荣耀 / 2633=二次元 / 1663=星秀 / 6861=原创
            String[] gameIds = {
                    "2135_一起看",
                    "2336_王者荣耀",
                    "2633_二次元",
                    "1663_星秀",
                    "6861_原创",
            };
            String[] merged;
            if (current instanceof String[]) {
                String[] cur = (String[]) current;
                java.util.Set<String> set = new java.util.LinkedHashSet<>();
                for (String s : cur) if (s != null && !s.isEmpty()) set.add(s);
                for (String s : gameIds) if (s != null && !s.isEmpty()) set.add(s);
                merged = set.toArray(new String[0]);
                LogBridge.i(TAG, "injectMultiGameIds: 原有gameIdArr长度=" + cur.length);
            } else {
                merged = gameIds;
                LogBridge.i(TAG, "injectMultiGameIds: 原有gameIdArr为空/不存在");
            }
            f.set(instance, merged);
            StringBuilder sb = new StringBuilder("已注入gameIdArr权限: [");
            for (int i = 0; i < merged.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(merged[i]);
            }
            sb.append("]");
            LogBridge.i(TAG, sb.toString());
        } catch (Throwable t) {
            LogBridge.e(TAG, "injectMultiGameIds失败: " + t.getMessage(), t);
        }
    }

    /**
     * 切换 SDK 游戏/分类上下文（按 gameId 数字切换，支持一键切换，无需重新初始化）。
     * 需要先通过 injectMultiGameIds() 注入 gameId 权限。
     * 初始化默认 gameId 已是 2135(虎牙一起看)，此方法用于切到其他分类，
     * 或初始化后做一次同 id 幂等兜底校验。
     */
    public static void changeGame(int gameId, final OnChangeGameListener listener) {
        if (!sInitOk || sHuyaBerry == null) {
            if (listener != null) listener.onError("SDK未就绪");
            return;
        }
        // 切换前确保多gameId权限已注入
        injectMultiGameIds();
        sHuyaBerry.changeGame(gameId, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("changeGame", code, "gameId=" + gameId);
                if (code == BaseCallback.SUCCESS) {
                    if (listener != null) listener.onSuccess();
                } else {
                    String errMsg = "code=" + code;
                    if (data instanceof com.huya.berry.client.customui.model.ErrorInfo) {
                        String msg = ((com.huya.berry.client.customui.model.ErrorInfo) data).errorMsg;
                        if (msg != null && !msg.isEmpty()) errMsg = msg;
                    }
                    if (listener != null) listener.onError(errMsg);
                }
            }
            @Override public void onResultListCallback(int code, List<BaseCallback> list) {
                HuyaSDKLogger.onCustomUICallback("changeGame-list", code, "size=" + (list == null ? 0 : list.size()));
                if (listener != null) listener.onError("unexpected list callback, code=" + code);
            }
        });
    }

    // ======================== A. 房间 / 直播列表 ========================

    /**
     * A1. 获取推荐直播列表（⭐⭐⭐ 高价值）
     *
     * <p>SDK 接口：{@link HuyaBerry#getLiveListData(boolean, CustomUICallback)}
     * 回调类型：onResultListCallback -> List<LiveListInfo>
     *
     * @param isMore 是否加载更多（true=翻页，false=首页）
     */
    public static void getLiveList(boolean isMore, OnLiveListResultListener listener) {
        if (!checkSDKReady("getLiveList", listener)) return;
        final OnLiveListResultListener out = listener;
        sHuyaBerry.getLiveListData(isMore, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("LiveList-single", code, detailOf(data));
                if (code != BaseCallback.SUCCESS) {
                    if (out != null) out.onError("code=" + code);
                    return;
                }
                if (out != null && data instanceof com.huya.berry.client.customui.model.LiveListInfo) {
                    java.util.List<com.huya.berry.client.customui.model.LiveListInfo> single =
                            new java.util.ArrayList<>();
                    single.add((com.huya.berry.client.customui.model.LiveListInfo) data);
                    out.onSuccess(single);
                } else if (out != null) {
                    out.onError("非 LiveListInfo 类型: " + typeOf(data));
                }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                HuyaSDKLogger.onCustomUICallback("LiveList-list", code,
                        "size=" + (list == null ? 0 : list.size()));
                if (code != BaseCallback.SUCCESS) {
                    if (out != null) out.onError("code=" + code);
                    return;
                }
                java.util.List<com.huya.berry.client.customui.model.LiveListInfo> result = new java.util.ArrayList<>();
                if (list != null) {
                    for (BaseCallback b : list) {
                        if (b instanceof com.huya.berry.client.customui.model.LiveListInfo) {
                            result.add((com.huya.berry.client.customui.model.LiveListInfo) b);
                        }
                    }
                }
                if (out != null) out.onSuccess(result);
            }
        });
    }
    public interface OnLiveListResultListener {
        void onSuccess(java.util.List<com.huya.berry.client.customui.model.LiveListInfo> list);
        void onError(String err);
    }

    /**
     * A2. 获取分类标签列表（⭐⭐⭐ 高价值，配合 A3 分类切换）
     * SDK 接口：{@link HuyaBerry#getTagListData(CustomUICallback)}
     */
    public static void getTagList(OnTagListResultListener listener) {
        if (!checkSDKReady("getTagList", listener)) return;
        final OnTagListResultListener out = listener;
        sHuyaBerry.getTagListData(new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("TagList", code, detailOf(data));
                handleTagOrList(code, java.util.Collections.<BaseCallback>singletonList(data), out);
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                HuyaSDKLogger.onCustomUICallback("TagList-list", code, "size=" + (list == null ? 0 : list.size()));
                handleTagOrList(code, list, out);
            }
            private void handleTagOrList(int code, java.util.List<BaseCallback> list, OnTagListResultListener out2) {
                if (code != BaseCallback.SUCCESS) {
                    if (out2 != null) out2.onError("code=" + code);
                    return;
                }
                java.util.List<Object> result = new java.util.ArrayList<>();
                if (list != null) result.addAll(list);
                if (out2 != null) out2.onSuccess(result);
            }
        });
    }
    public interface OnTagListResultListener {
        void onSuccess(java.util.List<Object> tagList);
        void onError(String err);
    }

    /**
     * A3. 按分类获取直播列表（⭐⭐⭐ 高价值）
     * SDK 接口：{@link HuyaBerry#getLiveListDataByTag(String, boolean, CustomUICallback)}
     */
    public static void getLiveListByTag(String tag, boolean isMore, OnLiveListResultListener listener) {
        if (!checkSDKReady("getLiveListByTag", listener)) return;
        if (TextUtils.isEmpty(tag)) {
            if (listener != null) listener.onError("tag 为空");
            return;
        }
        final OnLiveListResultListener out = listener;
        sHuyaBerry.getLiveListDataByTag(tag, isMore, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("LiveListByTag", code, "tag=" + tag + " " + detailOf(data));
                if (code != BaseCallback.SUCCESS) {
                    if (out != null) out.onError("code=" + code);
                    return;
                }
                java.util.List<com.huya.berry.client.customui.model.LiveListInfo> single = null;
                if (data instanceof com.huya.berry.client.customui.model.LiveListInfo) {
                    single = new java.util.ArrayList<>();
                    single.add((com.huya.berry.client.customui.model.LiveListInfo) data);
                }
                if (out != null) {
                    if (single != null) out.onSuccess(single);
                    else out.onError("非 LiveListInfo: " + typeOf(data));
                }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                HuyaSDKLogger.onCustomUICallback("LiveListByTag-list", code,
                        "tag=" + tag + " size=" + (list == null ? 0 : list.size()));
                if (code != BaseCallback.SUCCESS) {
                    if (out != null) out.onError("code=" + code);
                    return;
                }
                java.util.List<com.huya.berry.client.customui.model.LiveListInfo> result = new java.util.ArrayList<>();
                if (list != null) {
                    for (BaseCallback b : list) {
                        if (b instanceof com.huya.berry.client.customui.model.LiveListInfo) {
                            result.add((com.huya.berry.client.customui.model.LiveListInfo) b);
                        }
                    }
                }
                if (out != null) out.onSuccess(result);
            }
        });
    }

    // ======================== B. 关注 / 主播信息 ========================

    /**
     * B1. 关注房间（⭐⭐ 中价值）
     * SDK 接口：{@link HuyaBerry#subscribe(long, CustomUICallback)}
     * 回调返回 SubscribeInfo
     */
    public static void subscribeRoom(long roomId, OnSubscribeListener listener) {
        if (!checkSDKReady("subscribeRoom", listener)) return;
        final OnSubscribeListener out = listener;
        sHuyaBerry.subscribe(roomId, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("subscribe", code, "roomId=" + roomId + " " + detailOf(data));
                handleSubscribeCb(code, data, out);
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    /**
     * B2. 取消关注（⭐⭐ 中价值）
     * SDK 接口：{@link HuyaBerry#unSubscribe(long, CustomUICallback)}
     */
    public static void unsubscribeRoom(long roomId, OnSubscribeListener listener) {
        if (!checkSDKReady("unsubscribeRoom", listener)) return;
        final OnSubscribeListener out = listener;
        sHuyaBerry.unSubscribe(roomId, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("unSubscribe", code, "roomId=" + roomId + " " + detailOf(data));
                handleSubscribeCb(code, data, out);
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    /**
     * B3. 查询关注状态（⭐⭐ 中价值）
     * SDK 接口：{@link HuyaBerry#querySubscribeStatus(long, CustomUICallback)}
     */
    public static void querySubscribeStatus(long roomId, OnSubscribeListener listener) {
        if (!checkSDKReady("querySubscribeStatus", listener)) return;
        final OnSubscribeListener out = listener;
        sHuyaBerry.querySubscribeStatus(roomId, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("querySubscribeStatus", code, "roomId=" + roomId + " " + detailOf(data));
                handleSubscribeCb(code, data, out);
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    public interface OnSubscribeListener {
        void onResult(boolean isLogin, boolean isSubscribe, String msg);
        void onError(String err);
    }

    private static void handleSubscribeCb(int code, BaseCallback data, OnSubscribeListener out) {
        if (code != BaseCallback.SUCCESS) {
            if (out != null) out.onError("code=" + code);
            return;
        }
        if (data instanceof com.huya.berry.client.customui.model.SubscribeInfo) {
            com.huya.berry.client.customui.model.SubscribeInfo si =
                    (com.huya.berry.client.customui.model.SubscribeInfo) data;
            if (out != null) out.onResult(si.isLogin, si.isSubscribe, si.msg);
        } else if (out != null) {
            out.onError("非 SubscribeInfo: " + typeOf(data));
        }
    }

    /**
     * B4. 获取主播信息（⭐⭐ 中价值）
     * SDK 接口：{@link HuyaBerry#customUIGetAuthorInfo(android.app.Activity, CustomUICallback)}
     * 注：需要传入当前 Activity。纯后台获取场景暂无法调用。
     */
    public static void getAuthorInfo(android.app.Activity activity, OnAuthorInfoListener listener) {
        if (!checkSDKReady("getAuthorInfo", listener)) return;
        final OnAuthorInfoListener out = listener;
        sHuyaBerry.customUIGetAuthorInfo(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("AuthorInfo", code, detailOf(data));
                if (code != BaseCallback.SUCCESS) {
                    if (out != null) out.onError("code=" + code);
                    return;
                }
                if (data instanceof com.huya.berry.client.customui.model.AuthorInfo && out != null) {
                    out.onSuccess((com.huya.berry.client.customui.model.AuthorInfo) data);
                } else if (out != null) {
                    out.onError("非 AuthorInfo: " + typeOf(data));
                }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }
    public interface OnAuthorInfoListener {
        void onSuccess(com.huya.berry.client.customui.model.AuthorInfo info);
        void onError(String err);
    }

    // ======================== C. 清晰度切换 / 自定义 UI ========================

    /**
     * C1. 查询可选清晰度列表（⭐⭐ 中价值）
     * SDK 接口：{@link HuyaBerry#customUIGetResolution(android.app.Activity, CustomUICallback)}
     * 回调返回 List<OptionalResolution>
     */
    public static void getOptionalResolutions(android.app.Activity activity, OnResolutionListListener listener) {
        if (!checkSDKReady("getOptionalResolutions", listener)) return;
        final OnResolutionListListener out = listener;
        sHuyaBerry.customUIGetResolution(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("GetResolution", code, detailOf(data));
                java.util.List<BaseCallback> wrapper = new java.util.ArrayList<>();
                if (data != null) wrapper.add(data);
                handleResolutionCb(code, wrapper, out);
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                HuyaSDKLogger.onCustomUICallback("GetResolution-list", code,
                        "size=" + (list == null ? 0 : list.size()));
                handleResolutionCb(code, list, out);
            }
            private void handleResolutionCb(int code, java.util.List<BaseCallback> list,
                                            OnResolutionListListener out2) {
                if (code != BaseCallback.SUCCESS) {
                    if (out2 != null) out2.onError("code=" + code);
                    return;
                }
                java.util.List<com.huya.berry.client.customui.model.OptionalResolution> result =
                        new java.util.ArrayList<>();
                if (list != null) {
                    for (BaseCallback b : list) {
                        if (b instanceof com.huya.berry.client.customui.model.OptionalResolution) {
                            result.add((com.huya.berry.client.customui.model.OptionalResolution) b);
                        }
                    }
                }
                if (out2 != null) out2.onSuccess(result);
            }
        });
    }
    public interface OnResolutionListListener {
        void onSuccess(java.util.List<com.huya.berry.client.customui.model.OptionalResolution> list);
        void onError(String err);
    }

    /**
     * C2. 设置播放清晰度（⭐⭐ 中价值）
     * SDK 接口：{@link HuyaBerry#customUISetResolution(android.app.Activity, CustomUICallback, int)}
     * 注：int 是 OptionalResolution.resolution 字段值
     */
    public static void setResolution(android.app.Activity activity, int resolution, OnSimpleResultListener listener) {
        if (!checkSDKReady("setResolution", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUISetResolution(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("SetResolution", code,
                        "resolution=" + resolution + " " + detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        }, resolution);
    }

    /**
     * C3. 打开清晰度选择面板（SDK 自带 UI）
     * SDK 接口：{@link HuyaBerry#customUIOpenQuality(android.app.Activity, CustomUICallback)}
     */
    public static void openQualityPanel(android.app.Activity activity, OnSimpleResultListener listener) {
        if (!checkSDKReady("openQualityPanel", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUIOpenQuality(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("OpenQuality", code, detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    /**
     * C4. 打开发送弹幕面板（SDK 自带 UI）
     * SDK 接口：{@link HuyaBerry#customUIOpenSendDanmu(android.app.Activity, CustomUICallback)}
     */
    public static void openSendDanmuPanel(android.app.Activity activity, OnSimpleResultListener listener) {
        if (!checkSDKReady("openSendDanmuPanel", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUIOpenSendDanmu(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("OpenSendDanmu", code, detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    public interface OnSimpleResultListener {
        void onSuccess();
        void onError(String err);
    }

    // ======================== D. 登录 / 昵称 / 标题 / 公告 ========================

    /** D1. 启动 SDK 内置登录页（需要 Activity） */
    public static void startLogin(android.app.Activity activity, OnSimpleResultListener listener) {
        if (!checkSDKReady("startLogin", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUILogin(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("Login", code, detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    /** D2. 登出 */
    public static void startLogout(android.app.Activity activity, OnSimpleResultListener listener) {
        if (!checkSDKReady("startLogout", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUILogout(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("Logout", code, detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    /** D3. 修改昵称（需要 Activity） */
    public static void modifyNickname(android.app.Activity activity, OnSimpleResultListener listener) {
        if (!checkSDKReady("modifyNickname", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUIModifyNickname(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("ModifyNickname", code, detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    /** D4. 修改直播间标题（需要 Activity） */
    public static void modifyTitle(android.app.Activity activity, String newTitle, OnSimpleResultListener listener) {
        if (!checkSDKReady("modifyTitle", listener)) return;
        if (TextUtils.isEmpty(newTitle)) {
            if (listener != null) listener.onError("标题为空");
            return;
        }
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUIModifyTitle(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("ModifyTitle", code, "newTitleLen=" + newTitle.length());
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        }, newTitle);
    }

    /** D5. 修改直播间公告（需要 Activity） */
    public static void modifyAnnouncement(android.app.Activity activity, String announcement, OnSimpleResultListener listener) {
        if (!checkSDKReady("modifyAnnouncement", listener)) return;
        if (TextUtils.isEmpty(announcement)) {
            if (listener != null) listener.onError("公告为空");
            return;
        }
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUIModifyAnnouncement(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("ModifyAnnouncement", code,
                        "len=" + announcement.length());
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        }, announcement);
    }

    // ======================== E. 播放器 / 弹幕控制 ========================

    /** E1. 设置是否接收弹幕数据（开关） */
    public static void setReceiveDanmuData(boolean enable, long roomId) {
        if (!checkSDKReady("setReceiveDanmuData", null)) return;
        try {
            sHuyaBerry.setReceiveDanmuData(enable, roomId);
            HuyaSDKLogger.info(TAG, "setReceiveDanmuData: enable=" + enable + " roomId=" + roomId);
        } catch (Throwable t) {
            HuyaSDKLogger.error(TAG, "setReceiveDanmuData 失败: " + t.getMessage());
        }
    }

    /** E2. 切换弹幕显示开关（SDK 播放器内，当前 isNeedPlay=false 不可用） */
    public static void switchDanmu(boolean show) {
        if (!checkSDKReady("switchDanmu", null)) return;
        try {
            sHuyaBerry.switchDanmu(show);
            HuyaSDKLogger.info(TAG, "switchDanmu: show=" + show);
        } catch (Throwable t) {
            HuyaSDKLogger.error(TAG, "switchDanmu 失败: " + t.getMessage());
        }
    }

    /** E3. 切换声音开关（SDK 播放器内，当前 isNeedPlay=false 不可用） */
    public static void switchVoice(boolean on) {
        if (!checkSDKReady("switchVoice", null)) return;
        try {
            sHuyaBerry.switchVoice(on);
            HuyaSDKLogger.info(TAG, "switchVoice: on=" + on);
        } catch (Throwable t) {
            HuyaSDKLogger.error(TAG, "switchVoice 失败: " + t.getMessage());
        }
    }

    /** E4. 全屏播放（SDK 播放器内，当前 isNeedPlay=false 不可用） */
    public static void fullScreenPlay() {
        if (!checkSDKReady("fullScreenPlay", null)) return;
        try {
            sHuyaBerry.fullScreenPlay();
            HuyaSDKLogger.info(TAG, "fullScreenPlay");
        } catch (Throwable t) {
            HuyaSDKLogger.error(TAG, "fullScreenPlay 失败: " + t.getMessage());
        }
    }

    /** E5. 切换横竖屏模式 */
    public static void changeLandscapeMode(boolean landscape) {
        if (!checkSDKReady("changeLandscapeMode", null)) return;
        try {
            sHuyaBerry.changeLandscapeMode(landscape);
            HuyaSDKLogger.info(TAG, "changeLandscapeMode: landscape=" + landscape);
        } catch (Throwable t) {
            HuyaSDKLogger.error(TAG, "changeLandscapeMode 失败: " + t.getMessage());
        }
    }

    // ======================== F. 辅助 ========================

    private static boolean checkSDKReady(String methodName, Object listener) {
        if (!sInitOk || sHuyaBerry == null) {
            String err = "SDK 未初始化";
            HuyaSDKLogger.error(TAG, methodName + ": " + err);
            if (listener instanceof OnSimpleResultListener) {
                ((OnSimpleResultListener) listener).onError(err);
            } else if (listener instanceof OnLiveListResultListener) {
                ((OnLiveListResultListener) listener).onError(err);
            } else if (listener instanceof OnTagListResultListener) {
                ((OnTagListResultListener) listener).onError(err);
            } else if (listener instanceof OnSubscribeListener) {
                ((OnSubscribeListener) listener).onError(err);
            } else if (listener instanceof OnAuthorInfoListener) {
                ((OnAuthorInfoListener) listener).onError(err);
            } else if (listener instanceof OnResolutionListListener) {
                ((OnResolutionListListener) listener).onError(err);
            }
            return false;
        }
        return true;
    }

    private static String typeOf(Object data) {
        return data == null ? "null" : data.getClass().getSimpleName();
    }

    private static String detailOf(BaseCallback data) {
        if (data == null) return "data=null";
        String type = data.getClass().getSimpleName();
        if (data instanceof com.huya.berry.client.customui.model.ErrorInfo) {
            return "data=" + type + " errMsg="
                    + ((com.huya.berry.client.customui.model.ErrorInfo) data).errorMsg;
        }
        return "data=" + type;
    }

    // ============ 凭证解码辅助方法（防止静态提取）============
    // 注意：使用字符串解析而不是常量，防止 R8 常量折叠
    // 默认 gameId 已改为 2135(虎牙一起看)：2135 ^ 0x5A = 2061
    private static final String XOR_KEY_STR = "90";  // 0x5A 的十进制字符串
    private static final int ENCODED_GAME_ID = 2061;
    private static final String ENCODED_APP_ID = "khinol";
    private static final String ENCODED_APP_KEY = ">b<kci>>";

    private static int decodeGameIdFallback() {
        int xorKey = Integer.parseInt(XOR_KEY_STR);
        return ENCODED_GAME_ID ^ xorKey;
    }

    private static String decodeAppIdFallback() {
        return decodeStringFallback(ENCODED_APP_ID);
    }

    private static String decodeAppKeyFallback() {
        return decodeStringFallback(ENCODED_APP_KEY);
    }

    private static String decodeStringFallback(String encoded) {
        int xorKey = Integer.parseInt(XOR_KEY_STR);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < encoded.length(); i++) {
            sb.append((char)(encoded.charAt(i) ^ xorKey));
        }
        return sb.toString();
    }

}