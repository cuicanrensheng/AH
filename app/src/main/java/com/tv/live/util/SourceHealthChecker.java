package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tv.live.Channel;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 直播源健康检测器
 *
 * 【职责】
 * 1. 后台批量检测频道 URL 的可用性（HTTP HEAD/GET）
 * 2. 记录每个 URL 的连续失败次数
 * 3. 连续失败达到阈值 → 自动从频道的 backupUrls 中剔除
 * 4. 主源失效且有备用源 → 自动切换到可用备用源
 * 5. 播放失败时实时标记，定期批量复检
 *
 * 【设计原则】
 * - 只检测、不阻塞播放（后台子线程执行）
 * - 网络错误（超时/连不上）不计入失效，只有 HTTP 4xx/5xx 才算
 * - 检测结果持久化到 SharedPreferences，重启后保留
 */
public class SourceHealthChecker {
    private static final String TAG = "SourceHealthChecker";

    private static final String SP_NAME = "source_health";
    private static final String KEY_ENABLED = "health_check_enabled";
    private static final String KEY_FAIL_PREFIX = "fail_";
    private static final String KEY_LAST_CHECK = "last_full_check";

    /** 连续失败多少次后剔除该 URL */
    private static final int FAIL_THRESHOLD = 3;
    /** 检测超时时间 */
    private static final int CHECK_TIMEOUT_MS = 8000;
    /** 全量检测间隔（7天） */
    private static final long FULL_CHECK_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L;
    /** 单次批量检测的最大并发数 */
    private static final int MAX_CONCURRENT = 8;

    private final Context context;
    private final SharedPreferences sp;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService checkExecutor =
            Executors.newFixedThreadPool(MAX_CONCURRENT);
    private final ExecutorService singleExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SourceHealth-Worker");
                t.setDaemon(true);
                return t;
            });

    /** URL → 连续失败次数（内存缓存，与SP同步） */
    private final Map<String, Integer> failCountMap = new ConcurrentHashMap<>();

    /** 已剔除的 URL 集合（本会话内不再检测） */
    private final Map<String, Long> removedUrls = new ConcurrentHashMap<>();

    private volatile boolean isFullCheckRunning = false;
    private OnHealthCheckListener listener;

    public interface OnHealthCheckListener {
        /** 全量检测完成：移除了多少个失效URL */
        void onCheckComplete(int removedCount, int totalChecked);
        /** 单个URL被剔除 */
        void onUrlRemoved(String channelName, String url);
    }

    public SourceHealthChecker(Context context) {
        this.context = context.getApplicationContext();
        this.sp = this.context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        loadFailCounts();
    }

    public void setListener(OnHealthCheckListener listener) {
        this.listener = listener;
    }

    public boolean isEnabled() {
        return sp.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    // ============================================================
    // 失败标记（由 TVPlayerManager 调用）
    // ============================================================

    /**
     * 标记某个 URL 播放失败（非网络原因）
     * 达到阈值后自动从频道的备用源列表中剔除
     *
     * @param url       失败的 URL
     * @param channel   所属频道（可为 null）
     * @return 是否已剔除
     */
    public void markFailed(String url, Channel channel) {
        if (url == null || url.isEmpty()) return;
        if (!isEnabled()) return;
        if (removedUrls.containsKey(url)) return;

        singleExecutor.execute(() -> {
            int count = failCountMap.getOrDefault(url, 0) + 1;
            failCountMap.put(url, count);
            saveFailCount(url, count);
            Log.w(TAG, "源失败标记 [" + count + "/" + FAIL_THRESHOLD + "]: " + url);

            if (count >= FAIL_THRESHOLD) {
                removeUrl(url, channel);
            }
        });
    }

    /**
     * 标记某个 URL 播放成功 → 重置失败计数
     */
    public void markSuccess(String url) {
        if (url == null || url.isEmpty()) return;
        Integer count = failCountMap.remove(url);
        if (count != null && count > 0) {
            clearFailCount(url);
            Log.d(TAG, "源成功，重置失败计数: " + url);
        }
    }

    // ============================================================
    // URL 剔除
    // ============================================================

    private void removeUrl(String url, Channel channel) {
        if (url == null) return;
        removedUrls.put(url, System.currentTimeMillis());

        if (channel != null) {
            // 从备用源列表中移除
            Iterator<String> it = channel.getBackupUrls().iterator();
            boolean removed = false;
            while (it.hasNext()) {
                if (url.equals(it.next())) {
                    it.remove();
                    removed = true;
                    Log.w(TAG, "已剔除失效备用源: " + channel.getName() + " → " + url);
                    break;
                }
            }
            // 如果是主源失效且有备用源，提升第一个备用源为主源
            if (!removed && url.equals(channel.getMainPlayUrl())) {
                if (!channel.getBackupUrls().isEmpty()) {
                    String newMain = channel.getBackupUrls().remove(0);
                    channel.setMainPlayUrl(newMain);
                    Log.w(TAG, "主源失效，提升备用源为主源: " + channel.getName() +
                            "\n  旧: " + url + "\n  新: " + newMain);
                    removed = true;
                }
            }
        }

        clearFailCount(url);

        if (listener != null) {
            final String chName = (channel != null) ? channel.getName() : "未知";
            mainHandler.post(() -> {
                if (listener != null) listener.onUrlRemoved(chName, url);
            });
        }
    }

    /** 判断某个 URL 是否已被剔除 */
    public boolean isRemoved(String url) {
        return removedUrls.containsKey(url);
    }

    // ============================================================
    // 全量批量检测
    // ============================================================

    /**
     * 批量检测所有频道的所有 URL
     * 在后台线程执行，不阻塞 UI
     *
     * @param channels 频道列表
     */
    public void checkAll(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) return;
        if (!isEnabled()) return;
        if (isFullCheckRunning) {
            Log.d(TAG, "全量检测已在运行中，跳过");
            return;
        }

        isFullCheckRunning = true;
        long now = System.currentTimeMillis();
        long lastCheck = sp.getLong(KEY_LAST_CHECK, 0);
        if (now - lastCheck < FULL_CHECK_INTERVAL_MS) {
            Log.d(TAG, "距上次全量检测不足7天，跳过");
            isFullCheckRunning = false;
            return;
        }
        sp.edit().putLong(KEY_LAST_CHECK, now).apply();

        singleExecutor.execute(() -> {
            Log.i(TAG, "开始全量源检测，频道数: " + channels.size());
            final AtomicInteger totalChecked = new AtomicInteger(0);
            final AtomicInteger removedCount = new AtomicInteger(0);

            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

            for (Channel ch : channels) {
                // 检测主源
                futures.add(checkExecutor.submit(() -> {
                    String url = ch.getMainPlayUrl();
                    if (url != null && !url.isEmpty() && !isRemoved(url)) {
                        // 🔧 对虎牙房间号跳过检测，直接认为可用
                        if (isHuyaRoomUrl(url)) {
                            // 跳过检测，不做任何操作
                            return;
                        }
                        totalChecked.incrementAndGet();
                        if (!checkUrl(url)) {
                            int fails = failCountMap.getOrDefault(url, 0) + 1;
                            failCountMap.put(url, fails);
                            saveFailCount(url, fails);
                            if (fails >= FAIL_THRESHOLD) {
                                removeUrl(url, ch);
                                removedCount.incrementAndGet();
                            }
                        } else {
                            failCountMap.remove(url);
                            clearFailCount(url);
                        }
                    }
                }));

                // 检测备用源
                List<String> backups = new ArrayList<>(ch.getBackupUrls());
                for (String url : backups) {
                    if (isRemoved(url)) continue;
                    futures.add(checkExecutor.submit(() -> {
                        if (isHuyaRoomUrl(url)) {
                            // 跳过检测，不做任何操作
                            return;
                        }
                        totalChecked.incrementAndGet();
                        if (!checkUrl(url)) {
                            int fails = failCountMap.getOrDefault(url, 0) + 1;
                            failCountMap.put(url, fails);
                            saveFailCount(url, fails);
                            if (fails >= FAIL_THRESHOLD) {
                                removeUrl(url, ch);
                                removedCount.incrementAndGet();
                            }
                        } else {
                            failCountMap.remove(url);
                            clearFailCount(url);
                        }
                    }));
                }
            }

            // 等待所有检测完成
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }

            final int checked = totalChecked.get();
            final int removed = removedCount.get();
            Log.i(TAG, "全量源检测完成: 检测 " + checked + " 个URL, 剔除 " + removed + " 个失效源");

            isFullCheckRunning = false;

            if (listener != null) {
                mainHandler.post(() -> {
                    if (listener != null) listener.onCheckComplete(removed, checked);
                });
            }
        });
    }

    // ============================================================
    // 核心修改：虎牙房间号识别
    // ============================================================
    /** 判断是否为虎牙房间号 URL（如 http://www.huya.com/123456） */
    private boolean isHuyaRoomUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) return false;
            if (!host.contains("huya.com") && !host.contains("huya.cn")) return false;
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return false;
            String roomIdStr = path.replace("/", "").trim();
            return roomIdStr.matches("\\d+");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测单个 URL 是否可用
     * 返回 true=可用, false=失效
     */
    private boolean checkUrl(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CHECK_TIMEOUT_MS);
            conn.setReadTimeout(CHECK_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "TVLive-HealthCheck/1.0");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            // 2xx 和 3xx 都算可用
            return code >= 200 && code < 400;
        } catch (Exception e) {
            // 网络异常（超时/DNS失败）不算源失效，返回 true 避免误删
            Log.d(TAG, "检测异常(不计入失效): " + urlStr + " → " + e.getMessage());
            return true;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ============================================================
    // SP 持久化
    // ============================================================

    private void loadFailCounts() {
        Map<String, ?> all = sp.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(KEY_FAIL_PREFIX)) {
                try {
                    String url = key.substring(KEY_FAIL_PREFIX.length());
                    int count = (Integer) entry.getValue();
                    failCountMap.put(url, count);
                } catch (Exception ignored) {}
            }
        }
        Log.d(TAG, "加载失败记录: " + failCountMap.size() + " 条");
    }

    private void saveFailCount(String url, int count) {
        sp.edit().putInt(KEY_FAIL_PREFIX + url, count).apply();
    }

    private void clearFailCount(String url) {
        sp.edit().remove(KEY_FAIL_PREFIX + url).apply();
    }

    /**
     * 清除所有失败记录（用户手动触发）
     */
    public void resetAll() {
        failCountMap.clear();
        removedUrls.clear();
        SharedPreferences.Editor editor = sp.edit();
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(KEY_FAIL_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
        Log.i(TAG, "已重置所有源健康记录");
    }

    /**
     * 获取统计信息
     */
    public String getStats() {
        return "已剔除: " + removedUrls.size() + " 个源, " +
                "失败记录: " + failCountMap.size() + " 条";
    }
}
