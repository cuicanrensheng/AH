package com.tv.live;

import android.text.TextUtils;
import android.util.Log;

import com.tv.live.exception.RedirectFailedException;

class PlayerRetryManager {
    private static final String TAG = "PlayerRetryManager";

    static final int MAX_RETRY_COUNT = 2;
    static final int MAX_RETRY_COUNT_NETWORK = 5;
    static final long STUCK_TIMEOUT = 20000;
    static final long SOURCE_FAILED_COOLDOWN_MS = 30000;

    private final TVPlayerManager mgr;

    PlayerRetryManager(TVPlayerManager mgr) {
        this.mgr = mgr;
    }

    private long lastPosition = 0;
    private long lastPositionUpdateTime = 0;

    void internalStuckCheck() {
        long lastPos = getLastPosition();
        long lastUpdate = getLastPositionUpdateTime();
        long now = System.currentTimeMillis();
        if (lastPos > 0 && now - lastUpdate > STUCK_TIMEOUT) {
            Log.w(TAG, "【卡住检测】播放卡住超过 " + (STUCK_TIMEOUT / 1000) + "s，自动重试");
            autoRetry("播放卡住", null);
        }
        mgr.mHandler.postDelayed(mgr.stuckCheckRunnable, 2000);
    }

    private long getLastPosition() {
        try {
            if (mgr.player != null) {
                return mgr.player.getCurrentPosition();
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private long getLastPositionUpdateTime() {
        return mgr.getLastPositionUpdateTime();
    }

    void startStuckDetection() {
        mgr.mHandler.removeCallbacks(mgr.stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        mgr.mHandler.postDelayed(mgr.stuckCheckRunnable, 2000);
    }

    void stopStuckDetection() {
        mgr.mHandler.removeCallbacks(mgr.stuckCheckRunnable);
    }

    void cancelRetry() {
        if (mgr.retryRunnable != null) {
            mgr.mHandler.removeCallbacks(mgr.retryRunnable);
            mgr.retryRunnable = null;
        }
        mgr.isRetrying = false;
    }

    void resetRetryState() {
        mgr.retryCount = 0;
        mgr.isRetrying = false;
    }

    void autoRetry(String reason) {
        autoRetry(reason, null);
    }

    void autoRetry(String reason, Throwable cause) {
        if (cause != null) {
            Throwable t = cause;
            int depth = 0;
            while (t != null && depth < 20) {
                if (t instanceof RedirectFailedException) return;
                t = t.getCause();
                depth++;
            }
        }
        if (cause == null && reason != null && reason.contains("RedirectFailedException")) {
            return;
        }
        if (mgr.isRetrying) return;

        boolean isNetworkError = isNetworkError(cause) || (reason != null && (reason.contains("网络") || reason.contains("卡住")));
        int maxRetry = isNetworkError ? MAX_RETRY_COUNT_NETWORK : MAX_RETRY_COUNT;

        if (mgr.retryCount >= maxRetry) {
            Log.w(TAG, "重试次数已达上限：" + maxRetry + "（" + (isNetworkError ? "网络错误" : "源错误") + "），判定为失效源");

            if (!isNetworkError || !isNetworkUnavailable()) {
                boolean backupSwitched = trySwitchBackup();
                if (!backupSwitched) {
                    long now = System.currentTimeMillis();
                    if (now - mgr.lastSourceFailedTime < SOURCE_FAILED_COOLDOWN_MS) {
                        Log.w(TAG, "切台冷却中（" + (SOURCE_FAILED_COOLDOWN_MS / 1000) + "s），跳过本次自动切台");
                        return;
                    }
                    mgr.lastSourceFailedTime = now;
                    if (mgr.sourceFailedListener != null) {
                        mgr.mHandler.post(() -> mgr.sourceFailedListener.onSourceFailed());
                    }
                }
            } else {
                Log.w(TAG, "网络不可用，不切台不切源，保持当前频道等待网络恢复");
            }
            return;
        }
        mgr.isRetrying = true;
        mgr.retryCount++;
        long delayMs = isNetworkError ? (2000L * (1L << (mgr.retryCount - 1))) : 3000L;
        Log.w(TAG, "自动重试（第" + mgr.retryCount + "/" + maxRetry + "次），延迟" + delayMs + "ms，原因：" + reason);
        mgr.retryRunnable = () -> {
            mgr.isRetrying = false;
            if (!TextUtils.isEmpty(mgr.currentUrl)) {
                if (mgr.currentUrl.contains(".huya.com") && mgr.mHuyaRoomId > 0) {
                    Log.d(TAG, "【虎牙】重试：重新触发解析获取新签名, roomId=" + mgr.mHuyaRoomId);
                    mgr.playbackManager.playHuyaStream(mgr.mHuyaRoomId, 0);
                } else {
                    mgr.playbackManager.playUrlInternal(mgr.currentUrl);
                }
            }
            mgr.retryRunnable = null;
        };
        mgr.mHandler.postDelayed(mgr.retryRunnable, delayMs);
    }

    private boolean trySwitchBackup() {
        if (mgr.currentChannel == null || mgr.currentChannel.getBackupUrls().isEmpty()) {
            return false;
        }
        if (mgr.backupRetryIndex < 0) {
            mgr.backupRetryIndex = 0;
        } else {
            mgr.backupRetryIndex++;
        }
        java.util.List<String> backups = mgr.currentChannel.getBackupUrls();
        if (mgr.backupRetryIndex >= backups.size()) {
            mgr.backupRetryIndex = -1;
            return false;
        }
        String backupUrl = backups.get(mgr.backupRetryIndex);

        if (mgr.playbackManager.isHuyaRoomUrl(backupUrl)) {
            Log.w(TAG, "备用源是虎牙房间号，跳过！尝试下一个...");
            return trySwitchBackup();
        }

        Log.d(TAG, "尝试切换到备用源：" + backupUrl);
        mgr.playbackManager.playUrlInternal(backupUrl);
        return true;
    }

    boolean isNetworkError(Throwable throwable) {
        if (throwable == null) return false;
        Throwable t = throwable;
        int depth = 0;
        while (t != null && depth < 20) {
            String className = t.getClass().getName();
            String msg = t.getMessage();
            if (msg == null) msg = "";
            if (className.contains("SocketTimeoutException")
                    || className.contains("ConnectException")
                    || className.contains("UnknownHostException")
                    || className.contains("NetworkOnMainThreadException")
                    || msg.contains("timeout")
                    || msg.contains("timed out")
                    || msg.contains("Connection")
                    || msg.contains("connection")
                    || msg.contains("unreachable")
                    || msg.contains("ECONNRESET")
                    || msg.contains("ECONNREFUSED")) {
                return true;
            }
            t = t.getCause();
            depth++;
        }
        return false;
    }

    boolean isNetworkUnavailable() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    mgr.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info == null || !info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }
}