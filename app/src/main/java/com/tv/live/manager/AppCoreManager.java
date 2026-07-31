package com.tv.live.manager;

import com.tv.live.TVPlayerManager;
import android.content.BroadcastReceiver;
import android.util.Log;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.content.ContextCompat; // 🔧 新增导入
import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.UrlConfig;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.util.CacheManager;
import com.tv.live.SourceManager;
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
        this.context = context.getApplicationContext(); // 防止内存泄漏
        this.playerManager = playerManager;
        this.appConfig = appConfig;
        this.cacheManager = CacheManager.getInstance(context);
    }

    // ====================================================================
    // ✅ NPE 防御：真机首次启动时 LiveSourceLoader 异步回调与 onDestroy() 存在竞态，
    //             会导致 channelSourceList == null 或 channels == null → 崩。
    //             以下两个包私有方法供 TDD 单元测试直接调用。
    // ====================================================================

    /** @return channels 非 null（若原始为 null，则返回空 ArrayList；否则原对象） */
    static <T> List<T> sanitizeChannels(List<T> channels) {
        return (channels != null) ? channels : new ArrayList<>();
    }

    /** @return existing 非 null（若原始为 null，则新建空 ArrayList；否则原对象） */
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
                isLoading = false;
            }
        }, LOAD_TIMEOUT);

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
            }
        }

        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(context).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                // 🛡️ NPE 防御 1：PlaylistParser.parse 可能返回 null（网络失败 / 解析异常）
                List<Channel> safeChannels = sanitizeChannels(channels);
                log("【网络】直播源加载成功，频道总数：" + safeChannels.size());
                synchronized (channelListLock) {
                    // 🛡️ NPE 防御 2：onDestroy() 可能已把 channelSourceList 置 null（异步回调竞态）
                    channelSourceList = ensureChannelListNotNull(channelSourceList);
                    if (channelSourceList.isEmpty()) {
                        channelSourceList.clear();
                        channelSourceList.addAll(safeChannels);
                    } else {
                        mergeChannels(safeChannels);
                    }
                }
                isLoading = false;
                timeoutHandler.removeCallbacksAndMessages(null);
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceLoaded(safeChannels, false);
                }
                log("【网络】直播源列表已更新");
                loadEpg();
                fetchTogetherWatchChannels();
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
        String currentGroup = "";
        String currentTvgId = "";

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("#EXTINF:")) {
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
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                String playUrl = line;
                if (!TextUtils.isEmpty(currentName) && !TextUtils.isEmpty(playUrl)) {
                    String key = !TextUtils.isEmpty(currentTvgId) ? currentTvgId : currentName;
                    if (TextUtils.isEmpty(key)) continue;

                    Channel existing = channelMap.get(key);
                    if (existing != null) {
                        existing.addBackupUrl(playUrl);
                        if (!TextUtils.isEmpty(currentGroup)) {
                            existing.setGroup(currentGroup);
                        }
                    } else {
                        Channel newChannel = new Channel(currentName, playUrl, currentGroup, currentTvgId);
                        channelMap.put(key, newChannel);
                    }
                }
                currentName = "";
                currentGroup = "";
                currentTvgId = "";
            }
        }
        return new ArrayList<>(channelMap.values());
    }

    // 🛠️ 加锁保护
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
                    new Thread(() -> {
                        if (cacheManager != null) {
                            cacheManager.clearAll();
                            log("【缓存】已强制清除所有缓存，正在重新拉取最新数据");
                        }
                        synchronized (channelListLock) {
                            channelSourceList.clear();
                        }

                        SourceManager liveManager = new SourceManager(context, "live_history");
                        String defaultLive = liveManager.getDefaultUrl();
                        if (!TextUtils.isEmpty(defaultLive)) {
                            UrlConfig.LIVE_URL = defaultLive;
                        }

                        SourceManager epgManager = new SourceManager(context, "epg_history");
                        String defaultEpg = epgManager.getDefaultUrl();
                        if (!TextUtils.isEmpty(defaultEpg)) {
                            UrlConfig.EPG_URL = defaultEpg;
                        }

                        hasPlayedWithCache = false;
                        if (refreshListener != null) {
                            refreshListener.onRefreshNeeded();
                        }
                        loadLiveAndEpg();
                    }).start();
                }
            }
        };
        try {
            IntentFilter filterToggle = new IntentFilter("com.tv.live.TOGGLE_CONTROL");
            // 🔧 修复：使用 ContextCompat.registerReceiver 并传递 ContextCompat.RECEIVER_NOT_EXPORTED
            ContextCompat.registerReceiver(context, toggleControllerReceiver, filterToggle, ContextCompat.RECEIVER_NOT_EXPORTED);

            IntentFilter filterRefresh = new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG");
            // 🔧 修复：使用 ContextCompat.registerReceiver 并传递 ContextCompat.RECEIVER_NOT_EXPORTED
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
            // 🛡️ 修复：不再赋值为 null，改为 clear() —— 避免 LiveSourceLoader 异步回调回来时
            //         读到 null channelSourceList 导致 isEmpty() NPE（真机 PID 30860 崩溃根因）
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

    // 🛠️ 判空保护
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
        new Thread(() -> {
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
        }).start();
    }

    public void setOnDataLoadListener(OnDataLoadListener listener) { this.dataLoadListener = listener; }
    public void setOnRefreshListener(OnRefreshListener listener) { this.refreshListener = listener; }

    private void log(String msg) {
        Log.d("AppCoreManager", msg);
    }

    private void fetchTogetherWatchChannels() {
        HuyaTogetherWatchManager.getInstance().fetchTogetherWatchChannels(
                new HuyaTogetherWatchManager.OnChannelsFetchedListener() {
                    @Override
                    public void onSuccess(List<Channel> channels) {
                        log("【虎牙一起看】加载成功，频道数：" + channels.size());
                        synchronized (channelListLock) {
                            channelSourceList.addAll(channels);
                            log("【虎牙一起看】合并后总频道数：" + channelSourceList.size());
                        }
                        if (dataLoadListener != null) {
                            dataLoadListener.onLiveSourceLoaded(getChannelList(), false);
                        }
                    }

                    @Override
                    public void onFailed(String errorMsg) {
                        log("【虎牙一起看】加载失败：" + errorMsg);
                    }
                });
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
