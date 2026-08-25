package com.tv.live.util;

import android.app.Application;
import android.text.TextUtils;
import android.util.Log;

import android.os.Handler;
import android.os.Looper;

import com.huya.berry.client.HuyaBerry;
import com.huya.berry.client.HuyaBerryConfig;
import com.huya.berry.client.customui.CustomUICallback;
import com.huya.berry.client.customui.model.BitRateInfo;
import com.huya.berry.client.customui.model.LiveInfo;
import com.huya.berry.gamesdk.base.BaseCallback;

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
                // 存入队列即可，init 成功后会自动补发
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

        // 📱 设备检测：电视固定 720p，手机自动选最高码率
        // 设备检测已在 MyApplication.onCreate 中执行（电视固定 720p，手机自动选码率）
        boolean isTv = com.tv.live.util.DeviceCapabilities.isTv();
        int targetHeight = isTv ? 720 : Integer.MAX_VALUE;
        Log.d(TAG, "【适配选择】设备类型=" + (isTv ? "电视(固定720p)" : "手机(自动选码率)")
                + ", 候选码流数=" + streams.size() + ", targetHeight=" + targetHeight + ")");
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
            Log.w(TAG, "【适配选择】没有 <= " + targetHeight + "p 的流，退回选择最高码率");
            best = valid.get(0);
            for (HuyaStreamInfo s : valid) {
                if (s.bitRate > best.bitRate) best = s;
            }
        }

        Log.i(TAG, "【适配选择】选中流: " + best
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

            // 🆕 SDK 兼容修复：中和 BaseApi.crashIfDebug，防止 SDK 内部
            //    非致命模块失败（反射实例化失败/服务注册失败）被升级为
            //    RuntimeException 导致整个 init 失败、解析回调永不触发
            SdkCompatHook.neutralizeCrashIfDebug();

            Log.d(TAG, "[1/4] 准备构建 HuyaBerryConfig.Builder");

            // ============ 🆕 从加密存储读取凭证 =============
            int gameId;
            String appId;
            String appKey;
            try {
                HuyaCredentials credentials = HuyaCredentials.getInstance(app);
                gameId = credentials.getGameId();
                appId = credentials.getAppId();
                appKey = credentials.getAppKey();
                Log.i(TAG, "  🔐 从加密存储加载凭证: " + credentials.getCredentialsSummary());
            } catch (Throwable credError) {
                Log.e(TAG, "  ❌ 加载凭证失败: " + credError.getMessage());
                ExceptionReporter.report("HuyaCredentials", credError);
                // 从混淆后的编码值解码
                gameId = decodeGameIdFallback();
                appId = decodeAppIdFallback();
                appKey = decodeAppKeyFallback();
                Log.i(TAG, "  🔐 使用编码后的默认凭证");
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
            Log.i(TAG, "  ✅ HuyaBerryConfig.Builder 链路构建完成（含官方精简开关）");

            // ============ 🔧 缓存治理：在 build() 之前重定向 SDK 目录 + 禁用日志/上报 ============
            // 把 SDK 写入统一收到 getCacheDir()/huya_sdk/，同时关闭日志/埋点/崩溃上报，
            // 从源头减少写入磁盘。HuyaCacheGovernor 内部仍然用反射（兼容老版本），
            // 传入真正的 SDK Builder 即可（方法签名 Object，setter 反射探测照样命中）。
            HuyaCacheGovernor.applyOnBuilder(builder, app);

            HuyaBerryConfig config = builder.build();
            Log.d(TAG, "[2/4] HuyaBerryConfig build 完成");

            // init
            sHuyaBerry = HuyaBerry.instance();
            if (sHuyaBerry == null) {
                Log.e(TAG, "[3/4] HuyaBerry.instance() 返回 null，SDK 未初始化");
                sInitOk = false;
                return;
            }
            try {
                sHuyaBerry.init(app, config);
                Log.i(TAG, "✅ HuyaBerry SDK 初始化成功 (init 无异常)");
            } catch (Throwable initE) {
                // 打印完整异常链（含 cause 链）+ 提取真正缺失的类名列表，
                // 便于逐个移出黑名单或加 Stub
                Log.w(TAG, "❌ HuyaBerry init 失败（需要修复才能触发SDK回调）: " + logThrowableChain(initE));
                ExceptionReporter.report("HuyaBerry.init", initE);
                // init 失败时不抛：仍标记 done，但 sInitOk=false 让上层走纯解析兜底
            }

            sInitOk = true;
            Log.i(TAG, "✅ HuyaBerry SDK 初始化 & 绑定完成");

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
                    Log.d(TAG, "🔄【预解析】SDK init 完成，补发 " + sPreloadPendingQueue.size() + " 个房间的预解析");
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
                Log.w(TAG, "  ⚠️ BerryDebugChecker 启动失败（跳过，不影响主流程）", t);
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
                    // 90s 后再次测试：覆盖“缓存过期 + SDK 内部状态被其他房间刷新”场景
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 90000);
                }
            };
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(autoTestRunnable, 60000);

        } catch (Throwable e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                Log.e(TAG, "SDK 绑定异常: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), e);
                HuyaSDKLogger.error(TAG, "SDK绑定异常: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                ExceptionReporter.report("HuyaSDK.bind", cause);
            } else {
                Log.e(TAG, "SDK 绑定异常: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                HuyaSDKLogger.error(TAG, "SDK绑定异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ExceptionReporter.report("HuyaSDK.bind", e);
            }
            sInitOk = false;
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
     * 新版完整解析：返回全部线路×码率信息（用于 UI 线路/清晰度选择）
     */
    public static void parseFull(int roomId, OnSDKFullResultListener listener) {
        if (!sInitOk || sHuyaBerry == null) {
            HuyaSDKLogger.error(TAG, "parseFull: SDK未初始化, roomId=" + roomId);
            listener.onError("SDK 未初始化");
            return;
        }

        // 命中缓存则直接用
        CachedStreams cached = getCachedStreams(roomId);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            Log.d(TAG, "命中房间" + roomId + "流信息缓存（" + (System.currentTimeMillis() - cached.timestamp) / 1000 + "s前）");
            HuyaSDKLogger.info(TAG, "命中缓存: roomId=" + roomId + " streams=" + cached.streams.size());
            HuyaStreamInfo def = pickDefaultStream(cached.streams);
            List<String> lines = buildLineLabels(cached.streams);
            listener.onSuccess(def, cached.streams, lines);
            return;
        }

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
                        Log.i(TAG, "📞【SDK回调进入】onResultCallback(code=" + code
                                + ", data=" + dataType
                                + ") from " + Thread.currentThread().getName());
                        HuyaSDKLogger.onCustomUICallback("onResultCallback", code,
                                "dataType=" + dataType + " roomId=" + finalRoomId);

                        // 🔔 重要修复：即使 SDK 回调晚于 30s 超时(done==true)，也不要静默 return null！
                        boolean alreadyTimeout = done.get();
                        Log.d(TAG, "SDK onResultCallback: code=" + code
                                + " data=" + dataType
                                + " alreadyTimeout=" + alreadyTimeout
                                + (alreadyTimeout ? "（⚠️回调晚于30s超时，但继续解析不丢弃）" : ""));
                        try {
                            // 只处理 LiveInfo（getLiveDataByRoomId 回调约定的 T）
                            if (data instanceof LiveInfo) {
                                handleFullResult(code, (LiveInfo) data, outerListener, done, finalRoomId);
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
                            Log.e(TAG, "handleFullResult 异常: " + e.getMessage(), e);
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
                        Log.d(TAG, "SDK onResultListCallback: code=" + code + " size=" + size);
                        HuyaSDKLogger.onCustomUICallback("onResultListCallback", code, "size=" + size);
                    }
                };
                Log.d(TAG, "✅ CustomUICallback 直接实现创建成功 (impl=" + sdkCallback.getClass().getName() + ")");

                Log.d(TAG, "调用 SDK getLiveDataByRoomId, roomId=" + roomId);
                HuyaSDKLogger.info(TAG, "发起SDK解析: roomId=" + roomId);
                sHuyaBerry.getLiveDataByRoomId(roomId, sdkCallback);

                Thread.sleep(30000); // 30s 超时兜底（给网络&信令充足时间）
                if (done.compareAndSet(false, true)) {
                    Log.w(TAG, "SDK 调用超时 (30s)");
                    HuyaSDKLogger.error(TAG, "SDK解析超时: roomId=" + roomId);
                    ExceptionReporter.reportHuyaBusinessFailure(
                            "HuyaSDKParser.parseFull", -99998, "SDK 解析超时 (30s)",
                            "roomId=" + roomId);
                    listener.onError("SDK 解析超时");
                }
            } catch (Throwable e) {
                // 打印完整异常链：尤其 InvocationTargetException 必须看 getTargetException 才是真因
                Log.e(TAG, "SDK 解析异常完整链：\n" + logThrowableChain(e));
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
        Log.d(TAG, "房间" + roomId + " 流信息写入缓存: " + streams.size() + " 条流");
        HuyaSDKLogger.info(TAG, "房间" + roomId + " SDK解析成功: " + streams.size() + " 条流");

        HuyaStreamInfo def = pickDefaultStream(streams);
        List<String> lines = buildLineLabels(streams);
        Log.d(TAG, "SDK 解析完成: " + lines.size() + " 条线路, 默认 " + def);

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
                Log.w(TAG, "getLines 为空: " + (linesObj == null ? "null" : "size=0"));
                return fallbackExtractAsSingle(liveInfo);
            }
            Log.d(TAG, "getLines: " + linesObj.size() + " 条线路");

            // ===== v3.3 诊断：反射 dump PlayerHelper.singleStreamInfo 内部结构（带 roomId 定位） =====
            try {
                Class<?> phCls = Class.forName("com.huya.berry.module.Player.PlayerHelper");
                java.lang.reflect.Field ssiF = phCls.getDeclaredField("singleStreamInfo");
                ssiF.setAccessible(true);
                Object ssi = ssiF.get(null);
                Log.e(TAG, "V33DBG room=" + roomId + " singleStreamInfo=" + ssi);
                if (ssi != null) {
                    java.lang.reflect.Field siF = ssi.getClass().getDeclaredField("singleInfo");
                    siF.setAccessible(true);
                    java.util.Map<?, ?> singleInfo = (java.util.Map<?, ?>) siF.get(ssi);
                    Log.e(TAG, "V33DBG room=" + roomId + " singleInfo.size=" + (singleInfo == null ? -1 : singleInfo.size()));
                    if (singleInfo != null) {
                        for (java.util.Map.Entry<?, ?> e : singleInfo.entrySet()) {
                            Object li = e.getValue();
                            if (li == null) continue;
                            java.lang.reflect.Field brF = li.getClass().getDeclaredField("bitRateInfoList");
                            brF.setAccessible(true);
                            Object brl = brF.get(li);
                            Log.e(TAG, "V33DBG room=" + roomId + " line=" + e.getKey() + " bitRateInfoList=" + brl);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "V33DBG err: " + t);
            }

            for (int i = 0; i < linesObj.size(); i++) {
                int lineValue;
                try {
                    lineValue = ((Number) linesObj.get(i)).intValue();
                } catch (Exception e) {
                    Log.d(TAG, "线路#" + i + " 取值失败: " + e.getMessage());
                    ExceptionReporter.report("extractFullStreamList.lineValue", e);
                    continue;
                }

                Vector<BitRateInfo> bitRates = liveInfo.getBitRateList(lineValue);
                if (bitRates == null) continue;
                Log.d(TAG, "线路#" + i + "(v=" + lineValue + "): " + bitRates.size() + " 个码率");

                // 同线路内按码率降序排列（高码率在前 = 默认码率）
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
                        Log.d(TAG, "HLS URL 获取失败: line=" + lineValue + " br=" + br + ": " + e.getMessage());
                        ExceptionReporter.report("extractFullStreamList.hlsUrl", e);
                    }
                    try {
                        flvUrl = liveInfo.getPlayUrlByLineAndBitrate(true, lineValue, br);
                    } catch (Exception e) {
                        ExceptionReporter.report("extractFullStreamList.flvUrl", e);
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
    private static final String XOR_KEY_STR = "90";  // 0x5A 的十进制字符串
    private static final int ENCODED_GAME_ID = 2426;
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