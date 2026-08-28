package com.tv.live.manager;

import com.tv.live.TVPlayerManager;
import android.content.BroadcastReceiver;
import com.tv.live.util.LogBridge;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import androidx.core.content.ContextCompat;
import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.UrlConfig;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.util.AppExecutors;
import com.tv.live.util.CacheManager;
import com.tv.live.SourceManager;
import com.tv.live.util.HuyaSDKParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppCoreManager {
    private static final long LOAD_TIMEOUT = 15000;
    private static final int MAX_CONSECUTIVE_SKIP = 10;

    private Context context;
    private TVPlayerManager playerManager;
    private AppConfig appConfig;
    private CacheManager cacheManager;

    private List<Channel> channelSourceList = new ArrayList<>();
    private final Object channelListLock = new Object(); // 读写锁

    private boolean hasPlayedWithCache = false;
    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private boolean isLoading = false;

    private BroadcastReceiver toggleControllerReceiver;
    private BroadcastReceiver refreshReceiver;
    private boolean receiversRegistered = false;

    private boolean isOpeningSettings = false;
    private boolean isControllerVisible = false;

    private int consecutiveFailedCount = 0;
    private OnSourceSkipListener sourceSkipListener;

    private OnDataLoadListener dataLoadListener;
    private OnRefreshListener refreshListener;

    public interface OnDataLoadListener {
        void onLiveSourceLoaded(List<Channel> channels, boolean fromCache);
        void onLiveSourceFailed(String errorMsg);
        void onEpgLoaded();
        void onLoadTimeout(boolean hasData);
    }
    public interface OnRefreshListener { void onRefreshNeeded(); }
    public interface OnSourceSkipListener {
        void onNeedSkipChannel();
        void onSkipLimitReached(int maxSkip);
        void onSourceFailed(String channelName, int failedCount);
    }

    public AppCoreManager(Context context, TVPlayerManager playerManager, AppConfig appConfig) {
        this.context = context.getApplicationContext();
        this.playerManager = playerManager;
        this.appConfig = appConfig;
        this.cacheManager = CacheManager.getInstance(context);
    }

    static <T> List<T> sanitizeChannels(List<T> channels) {
        return (channels != null) ? channels : new ArrayList<>();
    }

    static <T> List<T> ensureChannelListNotNull(List<T> existing) {
        return (existing != null) ? existing : new ArrayList<>();
    }

    // ========== 1. 直播源 & EPG 加载 ==========
    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");
        isLoading = true;

        timeoutHandler.postDelayed(() -> {
            if (isLoading) {
                log("【加载】超时，自动隐藏加载动画");
                boolean hasData;
                synchronized (channelListLock) {
                    hasData = !channelSourceList.isEmpty();
                }
                if (dataLoadListener != null) {
                    dataLoadListener.onLoadTimeout(hasData);
                }
                // 🟢 首次秒开：超时不停止后台加载，仅把 isLoading 置为 false 避免重复回调
                isLoading = false;
            }
        }, LOAD_TIMEOUT);

        boolean cacheHit = false;
        String cacheContent = cacheManager.getFileCache("live_source");
        if (cacheContent != null && !cacheContent.isEmpty()) {
            log("【缓存】找到直播源缓存，快速显示");
            List<Channel> cacheChannels = parseLiveSource(cacheContent);
            if (cacheChannels != null && !cacheChannels.isEmpty()) {
                synchronized (channelListLock) {
                    channelSourceList.clear();
                    channelSourceList.addAll(cacheChannels);
                }
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceLoaded(cacheChannels, true);
                }
                loadEpgCache();
                log("【缓存】直播源缓存加载完成，频道数：" + cacheChannels.size());
                collectAndPreloadHuyaRooms(cacheChannels, "缓存");
                cacheHit = true;
            }
        }

        // 🟢 首次打开直接出画面：无缓存时也立即回调 onLiveSourceLoaded(空列表)，
        //    让 MainActivity 立刻完成UI初始化（频道面板/播放器框架），避免黑屏等待
        if (!cacheHit && dataLoadListener != null) {
            dataLoadListener.onLiveSourceLoaded(new ArrayList<Channel>(), true);
            log("【缓存】无缓存，立即渲染空UI，后台继续网络加载");
        }

        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(context).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                List<Channel> safeChannels = sanitizeChannels(channels);
                log("【网络】直播源加载成功，频道总数：" + safeChannels.size());
                synchronized (channelListLock) {
                    channelSourceList = ensureChannelListNotNull(channelSourceList);
                    if (safeChannels.isEmpty()) {
                        // 🔧 网络加载但返回 0 条频道 → 视为「源地址失效/404/返回空内容」。
                        //   原本如果 channelSourceList 非空，会继续走 mergeChannels(safeChannels)，
                        //   结果 merge 后仍然保留上一个源的缓存列表 → UI 显示上一个源的频道，
                        //   用户误以为「切换按钮点了没生效」。正确行为：清空列表并回调失败。
                        channelSourceList.clear();
                    } else if (channelSourceList.isEmpty()) {
                        channelSourceList.addAll(safeChannels);
                    } else {
                        // 只有 network 真正返回了有效频道数才做 merge，避免用空数据污染已有的缓存列表。
                        mergeChannels(safeChannels);
                    }
                }
                timeoutHandler.removeCallbacksAndMessages(null);
                // 防止超时后再次回调
                boolean firstTime = isLoading;
                isLoading = false;
                if (dataLoadListener != null) {
                    // 即使已超时，只要列表真正加载成功就再回调一次刷新UI和播放
                    dataLoadListener.onLiveSourceLoaded(safeChannels, false);
                    // 🔧 如果网络返回空频道数，额外回调 onLiveSourceFailed，让 MainActivity 弹 Toast 提示用户
                    // （之前的代码只有 HTTP/IO 异常才 onFailed，"解析0条"被当成 onSuccess，用户毫无感知）
                    if (safeChannels.isEmpty()) {
                        dataLoadListener.onLiveSourceFailed("直播源返回 0 个频道，地址可能已失效或暂时不可达");
                    }
                }
                if (firstTime) {
                    log("【网络】直播源列表已更新");
                } else {
                    log("【网络】直播源列表已更新（超时后补到数据）");
                }
                if (!safeChannels.isEmpty()) {
                    collectAndPreloadHuyaRooms(safeChannels, "网络");
                    triggerHealthCheck(safeChannels);
                }
                loadEpg();
            }

            @Override
            public void onError(String errorMsg) {
                log("【网络】直播源加载失败：" + errorMsg);
                isLoading = false;
                timeoutHandler.removeCallbacksAndMessages(null);
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceFailed(errorMsg);
                }
                loadEpgCache();
            }
        });
    }

    /**
     * 🟢【并行加载优化】从直播源列表中提取所有虎牙房间号，交给 HuyaSDKParser.preloadRooms()。
     * 预解析与直播源显示/健康检测/EPG加载 完全并行，用户点击虎牙频道时 90%+ 命中缓存，瞬时播放。
     *
     * 收集策略：
     *   - 优先 channel.getHuyaRoomId() > 0（Channel 对象自带，准确率最高）
     *   - 其次 huya://room/xxx 协议 URL
     *   - 最后 huya.com/纯数字 房间 URL
     */
    private void collectAndPreloadHuyaRooms(List<Channel> channels, String source) {
        if (channels == null || channels.isEmpty()) return;
        try {
            java.util.ArrayList<Integer> roomIds = new java.util.ArrayList<>();
            java.util.regex.Pattern huyaProtocol = java.util.regex.Pattern.compile("huya://room/(\\d+)");
            // 🟢 修复正则：扩展到 m.huya.com / huya.com / 带查询参数?xxx 的场景
            java.util.regex.Pattern huyaHttpRoom = java.util.regex.Pattern.compile("(?:m\\.|www\\.)?huya\\.com/(\\d+)(?:[?#/].*)?$");
            java.util.regex.Pattern huyaProfileId = java.util.regex.Pattern.compile("profileRoom=(\\d+)");
            java.util.regex.Pattern huyaRoomIdInUrl = java.util.regex.Pattern.compile("[?&]roomId=(\\d+)");
            for (Channel ch : channels) {
                if (ch == null) continue;
                boolean added = false;
                // 1) 一起看专用字段（最准）
                if (ch.getHuyaRoomId() > 0) {
                    roomIds.add(ch.getHuyaRoomId());
                    continue;
                }
                // 2) 主/备 URL 扫描
                java.util.List<String> urls = new java.util.ArrayList<>();
                if (!TextUtils.isEmpty(ch.getMainPlayUrl())) urls.add(ch.getMainPlayUrl());
                if (ch.getBackupUrls() != null) urls.addAll(ch.getBackupUrls());
                for (String u : urls) {
                    if (TextUtils.isEmpty(u)) continue;
                    String trimmed = u.trim();
                    java.util.regex.Matcher m1 = huyaProtocol.matcher(trimmed);
                    if (m1.find()) {
                        try { roomIds.add(Integer.parseInt(m1.group(1))); added = true; break; } catch (Exception ignored) {}
                    }
                    java.util.regex.Matcher m2 = huyaHttpRoom.matcher(trimmed);
                    if (m2.find()) {
                        try { roomIds.add(Integer.parseInt(m2.group(1))); added = true; break; } catch (Exception ignored) {}
                    }
                    java.util.regex.Matcher m3 = huyaProfileId.matcher(trimmed);
                    if (m3.find()) {
                        try { roomIds.add(Integer.parseInt(m3.group(1))); added = true; break; } catch (Exception ignored) {}
                    }
                    java.util.regex.Matcher m4 = huyaRoomIdInUrl.matcher(trimmed);
                    if (m4.find()) {
                        try { roomIds.add(Integer.parseInt(m4.group(1))); added = true; break; } catch (Exception ignored) {}
                    }
                }
            }
            if (!roomIds.isEmpty()) {
                log("🟢【虎牙预解析】(" + source + ") 收集到 " + roomIds.size() + " 个虎牙房间，开始后台并行预解析");
                HuyaSDKParser.preloadRooms(roomIds);
            } else {
                log("🟡【虎牙预解析】(" + source + ") 未识别到虎牙房间，跳过预解析（列表共 " + channels.size() + " 个频道）");
            }
        } catch (Throwable t) {
            log("【虎牙预解析】收集异常: " + t.getMessage());
        }
    }

    private void triggerHealthCheck(List<Channel> channels) {
        try {
            com.tv.live.TVPlayerManager pm = com.tv.live.TVPlayerManager.getInstance(context);
            if (pm != null) {
                com.tv.live.util.SourceHealthChecker hc = pm.getHealthChecker();
                if (hc != null && hc.isEnabled()) {
                    hc.checkAll(channels);
                    log("【健康检测】已触发后台全量源检测");
                }
            }
        } catch (Exception e) {
            log("【健康检测】触发异常: " + e.getMessage());
        }
    }

    private void loadEpgCache() {
        if (dataLoadListener != null) {
            dataLoadListener.onEpgLoaded();
        }
        log("【EPG】尝试从缓存加载...");
    }

    private void loadEpg() {
        log("【EPG】开始加载节目单...");
        EpgManager.getInstance(context).setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance(context).loadEpg(() -> {
            new Handler(Looper.getMainLooper()).post(() -> {
                log("【EPG】最新节目单加载完成");
                if (dataLoadListener != null) {
                    dataLoadListener.onEpgLoaded();
                }
            });
        });
    }

    private List<Channel> parseLiveSource(String content) {
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        if (TextUtils.isEmpty(content)) {
            return new ArrayList<>();
        }
        String[] lines = content.split("\n");
        String currentName = "";
        String currentGroup = "未分类";
        String currentTvgId = "";
        boolean pendingM3uUri = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) continue;

            // ============================================================
            // ✅ 格式 A：DIYP TXT（本地666源格式）
            //   1) 央卫,#genre#   → 切换分组
            //   2) CCTV-1,http://...  → 频道条目 + 同名多源合并
            // ============================================================
            if (line.endsWith(",#genre#") || line.endsWith("#genre#")) {
                String group = line;
                if (group.endsWith(",#genre#")) {
                    group = group.substring(0, group.length() - ",#genre#".length()).trim();
                } else if (group.endsWith("#genre#")) {
                    int idx = group.lastIndexOf("#genre#");
                    if (idx > 0) group = group.substring(0, idx).trim();
                    if (group.endsWith(",")) group = group.substring(0, group.length() - 1).trim();
                }
                if (!group.isEmpty()) currentGroup = group;
                pendingM3uUri = false;
                currentName = ""; currentTvgId = "";
                continue;
            }

            int httpIdx = line.indexOf("http://");
            if (httpIdx < 0) httpIdx = line.indexOf("https://");
            // DIYP 频道行：「频道名,http://...」—— http 前面必须有逗号
            if (httpIdx > 1) {
                int diypComma = line.lastIndexOf(',', httpIdx - 1);
                if (diypComma > 0) {
                    String diypName = line.substring(0, diypComma).trim();
                    String diypUri = line.substring(diypComma + 1).trim();
                    if (!diypName.isEmpty() && diypUri.startsWith("http")) {
                        mergeChannelInto(channelMap, diypName, diypUri, currentGroup, "");
                        pendingM3uUri = false;
                        currentName = ""; currentTvgId = "";
                        continue;
                    }
                }
            }

            // ============================================================
            // 格式 B：标准 M3U (#EXTINF / group-title / tvg-id)
            // ============================================================
            if (line.startsWith("#EXTINF:")) {
                currentName = "";
                currentTvgId = "";
                // 保留之前的分组默认（#EXTINF 无 group-title 时沿用）
                int commaIndex = line.indexOf(",");
                if (commaIndex > 0 && commaIndex < line.length() - 1) {
                    currentName = line.substring(commaIndex + 1).trim();
                }
                int groupIndex = line.indexOf("group-title=\"");
                if (groupIndex > 0) {
                    int groupEnd = line.indexOf("\"", groupIndex + 13);
                    if (groupEnd > groupIndex) {
                        currentGroup = line.substring(groupIndex + 13, groupEnd);
                    }
                }
                int tvgIndex = line.indexOf("tvg-id=\"");
                if (tvgIndex > 0) {
                    int tvgEnd = line.indexOf("\"", tvgIndex + 8);
                    if (tvgEnd > tvgIndex) {
                        currentTvgId = line.substring(tvgIndex + 8, tvgEnd);
                    }
                }
                pendingM3uUri = true;
                continue;
            }

            if (line.startsWith("#")) {
                // 其他 M3U 注释/标记（非 #EXTINF）不触发 URL 匹配
                continue;
            }

            // 非注释行
            if (pendingM3uUri && line.startsWith("http")) {
                String playUrl = line;
                if (!TextUtils.isEmpty(currentName)) {
                    mergeChannelInto(channelMap, currentName, playUrl, currentGroup, currentTvgId);
                }
                pendingM3uUri = false;
                currentName = ""; currentTvgId = "";
                continue;
            }

            // 兜底：裸 URL（没分组、没#EXTINF，一行就是 URL）→ 丢未分类
            if (!pendingM3uUri && line.startsWith("http")) {
                mergeChannelInto(channelMap, line, line, currentGroup, "");
            }
        }
        return new ArrayList<>(channelMap.values());
    }

    /** parseLiveSource 内部用：同名合并备用源、不同名新建条目 */
    private static void mergeChannelInto(Map<String, Channel> channelMap,
                                         String name, String uri,
                                         String group, String tvgId) {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(uri) || !uri.startsWith("http")) return;
        String key = !TextUtils.isEmpty(tvgId) ? tvgId : name;
        if (TextUtils.isEmpty(key)) return;

        Channel existing = channelMap.get(key);
        if (existing != null) {
            existing.addBackupUrl(uri);
            if (!TextUtils.isEmpty(group)) {
                existing.setGroup(group);
            }
        } else {
            Channel newChannel = new Channel(name, uri, group, tvgId);
            channelMap.put(key, newChannel);
        }
    }

    public void mergeChannels(List<Channel> newChannels) {
        synchronized (channelListLock) {
            Map<String, Channel> mergedMap = new LinkedHashMap<>();
            for (Channel ch : channelSourceList) {
                String key = !TextUtils.isEmpty(ch.getChannelId()) ? ch.getChannelId() : ch.getName();
                if (!TextUtils.isEmpty(key)) {
                    mergedMap.put(key, ch);
                }
            }
            for (Channel ch : newChannels) {
                String key = !TextUtils.isEmpty(ch.getChannelId()) ? ch.getChannelId() : ch.getName();
                if (TextUtils.isEmpty(key)) continue;

                Channel existing = mergedMap.get(key);
                if (existing != null) {
                    for (String url : ch.getBackupUrls()) {
                        existing.addBackupUrl(url);
                    }
                    String newGroup = ch.getGroup();
                    if (!TextUtils.isEmpty(newGroup)) {
                        existing.setGroup(newGroup);
                    }
                } else {
                    mergedMap.put(key, ch);
                }
            }
            channelSourceList.clear();
            channelSourceList.addAll(mergedMap.values());
        }
    }

    // ========== 2. 广播管理 ==========
    public void registerReceivers() {
        if (receiversRegistered) return;
        toggleControllerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                isControllerVisible = !isControllerVisible;
            }
        };
        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                    AppExecutors.io(() -> {
                        if (cacheManager != null) {
                            cacheManager.clearAll();
                            log("【缓存】已强制清除所有缓存，正在重新拉取最新数据");
                        }
                        synchronized (channelListLock) {
                            channelSourceList.clear();
                        }

                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String customLive = sp.getString("custom_live_url", "");
                        if (!TextUtils.isEmpty(customLive)) {
                            UrlConfig.LIVE_URL = customLive;
                            log("【推送】成功读取到网页推送的直播源地址：" + customLive);
                        } else {
                            SourceManager liveManager = new SourceManager(context, "live_history");
                            String defaultLive = liveManager.getDefaultUrl();
                            if (!TextUtils.isEmpty(defaultLive)) {
                                UrlConfig.LIVE_URL = defaultLive;
                                log("【推送】未找到推送地址，使用历史默认源：" + defaultLive);
                            }
                        }

                        String customEpg = sp.getString("custom_epg_url", "");
                        if (!TextUtils.isEmpty(customEpg)) {
                            UrlConfig.EPG_URL = customEpg;
                            log("【推送】成功读取到网页推送的EPG地址：" + customEpg);
                        } else {
                            SourceManager epgManager = new SourceManager(context, "epg_history");
                            String defaultEpg = epgManager.getDefaultUrl();
                            if (!TextUtils.isEmpty(defaultEpg)) {
                                UrlConfig.EPG_URL = defaultEpg;
                            }
                        }

                        hasPlayedWithCache = false;
                        if (refreshListener != null) {
                            refreshListener.onRefreshNeeded();
                        }
                        loadLiveAndEpg();
                    });
                }
            }
        };
        try {
            IntentFilter filterToggle = new IntentFilter("com.tv.live.TOGGLE_CONTROL");
            ContextCompat.registerReceiver(context, toggleControllerReceiver, filterToggle, ContextCompat.RECEIVER_NOT_EXPORTED);

            IntentFilter filterRefresh = new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG");
            ContextCompat.registerReceiver(context, refreshReceiver, filterRefresh, ContextCompat.RECEIVER_NOT_EXPORTED);

            receiversRegistered = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void unregisterReceivers() {
        if (!receiversRegistered) return;
        try {
            if (toggleControllerReceiver != null) {
                context.unregisterReceiver(toggleControllerReceiver);
            }
            if (refreshReceiver != null) {
                context.unregisterReceiver(refreshReceiver);
            }
            receiversRegistered = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isControllerVisible() { return isControllerVisible; }

    // ========== 3. 生命周期 ==========
    public boolean onPause() {
        if (isOpeningSettings) return false;
        if (playerManager != null) {
            playerManager.onBackground();
        }
        return true;
    }

    public boolean onResume() {
        if (isOpeningSettings) {
            isOpeningSettings = false;
            return false;
        }
        if (playerManager != null) {
            playerManager.onForeground();
        }
        return true;
    }

    public void onWindowFocusChanged(boolean hasFocus) {
    }

    public void onDestroy() {
        unregisterReceivers();
        timeoutHandler.removeCallbacksAndMessages(null);
        if (playerManager != null) {
            playerManager.release();
        }
        synchronized (channelListLock) {
            if (channelSourceList != null) {
                channelSourceList.clear();
            } else {
                channelSourceList = new ArrayList<>();
            }
        }
    }

    public void beforeOpenSettings() {
        isOpeningSettings = true;
    }

    public boolean isOpeningSettings() { return isOpeningSettings; }

    public boolean hasPlayedWithCache() { return hasPlayedWithCache; }
    public void setHasPlayedWithCache(boolean played) { this.hasPlayedWithCache = played; }

    public List<Channel> getChannelList() {
        synchronized (channelListLock) {
            if (channelSourceList == null) {
                return new ArrayList<>();
            }
            return new ArrayList<>(channelSourceList);
        }
    }

    // ========== 4. 源失效自动切台 ==========
    public void setOnSourceSkipListener(OnSourceSkipListener listener) {
        this.sourceSkipListener = listener;
    }

    public boolean handleSourceFailed(String currentChannelName) {
        consecutiveFailedCount++;
        int count = consecutiveFailedCount;
        if (sourceSkipListener != null) {
            sourceSkipListener.onSourceFailed(currentChannelName, count);
        }
        if (count >= MAX_CONSECUTIVE_SKIP) {
            if (sourceSkipListener != null) {
                sourceSkipListener.onSkipLimitReached(MAX_CONSECUTIVE_SKIP);
            }
            return false;
        }
        if (sourceSkipListener != null) {
            sourceSkipListener.onNeedSkipChannel();
        }
        return true;
    }

    public void resetSourceFailedCount() { consecutiveFailedCount = 0; }
    public int getConsecutiveFailedCount() { return consecutiveFailedCount; }
    public int getMaxConsecutiveSkip() { return MAX_CONSECUTIVE_SKIP; }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppExecutors.io(() -> {
            appConfig.setCustomUrls(liveUrl, epgUrl);
            if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
            if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
            log("【远程配置】更新直播源：" + liveUrl);
            log("【远程配置】更新EPG：" + epgUrl);

            if (cacheManager != null) {
                cacheManager.clearAll();
                log("【缓存】远程配置触发，强制清除旧缓存");
            }

            synchronized (channelListLock) {
                channelSourceList.clear();
            }

            hasPlayedWithCache = false;
            loadLiveAndEpg();
        });
    }

    public void setOnDataLoadListener(OnDataLoadListener listener) { this.dataLoadListener = listener; }
    public void setOnRefreshListener(OnRefreshListener listener) { this.refreshListener = listener; }

    private void log(String msg) {
        LogBridge.d("AppCoreManager", msg);
    }

    public void release() {
        onDestroy();
        context = null;
        playerManager = null;
        appConfig = null;
        cacheManager = null;
        dataLoadListener = null;
        refreshListener = null;
        sourceSkipListener = null;
    }
}
