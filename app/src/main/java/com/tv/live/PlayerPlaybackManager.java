package com.tv.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.tv.live.exception.RedirectFailedException;
import com.tv.live.util.HuyaParser;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.NetUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;

class PlayerPlaybackManager {
    private static final String TAG = "PlayerPlaybackManager";

    private final TVPlayerManager mgr;
    private final Context context;

    PlayerPlaybackManager(TVPlayerManager mgr, Context context) {
        this.mgr = mgr;
        this.context = context;
    }

    void playUrl(String url) {
        playUrl(url, null, null);
    }

    void playUrl(String url, String channelName) {
        playUrl(url, channelName, null);
    }

    void playUrl(String url, String channelName, Channel channel) {
        if (!TextUtils.isEmpty(channelName)) mgr.currentChannelName = channelName;
        mgr.currentChannel = channel;
        mgr.backupRetryIndex = -1;
        if (channel != null && TextUtils.isEmpty(mgr.currentChannelName)) {
            mgr.currentChannelName = channel.getName();
        }
        mgr.retryManager.cancelRetry();
        mgr.retryManager.resetRetryState();
        mgr.initialPlayStartTime = 0;
        mgr.resetPerformanceStats();
        playUrlInternal(url, 0);
    }

    boolean isHuyaRoomUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) return false;
            if (!host.contains("huya.com") && !host.contains("huya.cn")) return false;
            String path = uri.getPath();
            if (TextUtils.isEmpty(path)) return false;
            String roomIdStr = path.replace("/", "").trim();
            return roomIdStr.matches("\\d+");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isHuyaProtocolUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.startsWith("huya://room/");
    }

    boolean isHlsUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String path = uri.getPath();
            if (TextUtils.isEmpty(path)) return false;
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) return true;
            String host = uri.getHost();
            if (host != null && host.contains(".hls.huya.com")) return true;
            return false;
        } catch (Exception e) {
            String lower = url.toLowerCase(Locale.ROOT);
            int q = lower.indexOf('?');
            String beforeQuery = q >= 0 ? lower.substring(0, q) : lower;
            if (beforeQuery.contains(".m3u8") || beforeQuery.contains(".m3u")) return true;
            if (lower.contains(".hls.huya.com")) return true;
            return false;
        }
    }

    private void playUrlInternal(String url) {
        playUrlInternal(url, 0);
    }

    void playUrlInternal(String url, long initialSeekPosition) {
        if (isHuyaProtocolUrl(url)) {
            String roomIdStr = url.replace("huya://room/", "").trim();
            int roomId;
            try {
                roomId = Integer.parseInt(roomIdStr);
            } catch (NumberFormatException e) {
                mgr.retryManager.autoRetry("虎牙房间号格式错误: " + url);
                return;
            }
            playHuyaStream(roomId, initialSeekPosition);
            return;
        }
        if (isHuyaRoomUrl(url)) {
            String roomIdStr = url.replaceAll(".*/(\\d+).*", "$1");
            int roomId;
            try {
                roomId = Integer.parseInt(roomIdStr);
            } catch (NumberFormatException e) {
                mgr.retryManager.autoRetry("虎牙房间号格式错误: " + url);
                return;
            }
            playHuyaStream(roomId, initialSeekPosition);
            return;
        }
        doPlay(url, initialSeekPosition);
    }

    void playHuyaStream(int roomId, long initialSeekPosition) {
        mgr.mHuyaRoomId = roomId;
        if (mgr.playerView != null) {
            mgr.playerView.setVisibility(View.VISIBLE);
        }

        synchronized (mgr.variantListLock) {
            mgr.variantList.clear();
        }
        if (mgr.currentChannel != null) {
            try {
                mgr.currentChannel.clearBackupUrls();
            } catch (Exception ignored) {}
        }

        boolean isTogetherWatch = mgr.currentChannel != null && mgr.currentChannel.isTogetherWatch();
        if (isTogetherWatch) {
            Log.d(TAG, "【虎牙一起看】使用 HuyaSDKParser 解析, roomId=" + roomId);
            playViaSDKParser(roomId, initialSeekPosition);
        } else {
            Log.d(TAG, "【虎牙普通源】使用 HuyaParser 解析, roomId=" + roomId);
            playViaHuyaParser(roomId, initialSeekPosition);
        }
    }

    private void playViaHuyaParser(int roomId, long initialSeekPosition) {
        HuyaParser.parse(roomId, new HuyaParser.OnParseResultListener() {
            @Override
            public void onSuccess(String hlsUrl, String flvUrl, boolean isTogetherWatch) {
                String playUrl = !TextUtils.isEmpty(hlsUrl) ? hlsUrl : flvUrl;
                if (!TextUtils.isEmpty(playUrl)) {
                    Log.d(TAG, "【虎牙普通源】HuyaParser 解析成功: " + playUrl.substring(0, Math.min(80, playUrl.length())));
                    mgr.mPendingPlaybackHeaders = null;
                    if (mgr.currentChannel != null) {
                        try {
                            mgr.currentChannel.setMainPlayUrl(playUrl);
                            mgr.currentChannel.clearBackupUrls();
                        } catch (Exception ignored) {}
                    }
                    mgr.mHandler.post(() -> doPlay(playUrl, initialSeekPosition));
                } else {
                    Log.w(TAG, "【虎牙普通源】HuyaParser 返回空地址");
                    mgr.mHandler.post(() -> {
                        android.widget.Toast.makeText(context, "虎牙解析失败：未获取到播放地址", android.widget.Toast.LENGTH_SHORT).show();
                        if (mgr.sourceFailedListener != null) {
                            mgr.sourceFailedListener.onSourceFailed();
                        }
                    });
                }
            }

            @Override
            public void onFailed(String errorMsg) {
                Log.e(TAG, "【虎牙普通源】HuyaParser 解析失败: " + errorMsg);
                mgr.mHandler.post(() -> {
                    android.widget.Toast.makeText(context, "虎牙解析失败：" + errorMsg, android.widget.Toast.LENGTH_SHORT).show();
                    if (mgr.sourceFailedListener != null) {
                        mgr.sourceFailedListener.onSourceFailed();
                    }
                });
            }
        });
    }

    private void playViaSDKParser(int roomId, long initialSeekPosition) {
        new Thread(() -> {
            if (!HuyaSDKParser.awaitInit(3000)) {
                Log.w(TAG, "【虎牙】SDK 初始化超时或失败");
                mgr.mHandler.post(() -> {
                    android.widget.Toast.makeText(context, "虎牙解析失败：SDK 初始化超时", android.widget.Toast.LENGTH_SHORT).show();
                    if (mgr.sourceFailedListener != null) {
                        mgr.sourceFailedListener.onSourceFailed();
                    }
                });
                return;
            }

            if (!HuyaSDKParser.isSDKAvailable()) {
                Log.w(TAG, "【虎牙】SDK 不可用");
                mgr.mHandler.post(() -> {
                    android.widget.Toast.makeText(context, "虎牙解析失败：SDK 不可用", android.widget.Toast.LENGTH_SHORT).show();
                    if (mgr.sourceFailedListener != null) {
                        mgr.sourceFailedListener.onSourceFailed();
                    }
                });
                return;
            }

            Log.d(TAG, "【虎牙】SDK 回退解析, roomId=" + roomId);
            HuyaSDKParser.parseFull(roomId, new HuyaSDKParser.OnSDKFullResultListener() {
                @Override
                public void onSuccess(HuyaSDKParser.HuyaStreamInfo defaultStream,
                                      java.util.List<HuyaSDKParser.HuyaStreamInfo> allStreams,
                                      java.util.List<String> lines) {
                    final HuyaSDKParser.HuyaStreamInfo[] streamHolder = {defaultStream};

                    if (streamHolder[0] == null || TextUtils.isEmpty(streamHolder[0].getPlayUrl())) {
                        Log.d(TAG, "【虎牙】SDK 返回空默认地址");
                        mgr.mHandler.post(() -> {
                            android.widget.Toast.makeText(context, "虎牙解析失败：SDK 返回空地址", android.widget.Toast.LENGTH_SHORT).show();
                            if (mgr.sourceFailedListener != null) {
                                mgr.sourceFailedListener.onSourceFailed();
                            }
                        });
                        return;
                    }

                    if (allStreams != null && allStreams.size() > 1) {
                        java.util.Set<Integer> lineIndexSet = new java.util.TreeSet<>();
                        for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                            if (!TextUtils.isEmpty(s.getPlayUrl())) {
                                lineIndexSet.add(s.lineIndex);
                            }
                        }
                        java.util.List<Integer> uniqueLineIndices = new java.util.ArrayList<>(lineIndexSet);

                        if (uniqueLineIndices.size() > 1) {
                            String linePrefKey = "huya_line_poll_" + roomId;
                            int lastLineIdx = mgr.sp.getInt(linePrefKey, uniqueLineIndices.get(0));

                            int currentPos = -1;
                            for (int i = 0; i < uniqueLineIndices.size(); i++) {
                                if (uniqueLineIndices.get(i) == lastLineIdx) {
                                    currentPos = i;
                                    break;
                                }
                            }
                            if (currentPos == -1) currentPos = 0;

                            int nextPos = (currentPos + 1) % uniqueLineIndices.size();
                            int targetLineIndex = uniqueLineIndices.get(nextPos);

                            if (targetLineIndex != streamHolder[0].lineIndex) {
                                Log.d(TAG, "【虎牙】线路轮询：切换到线路 " + targetLineIndex);
                                HuyaSDKParser.HuyaStreamInfo targetStream = null;
                                for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                                    if (s.lineIndex == targetLineIndex && !TextUtils.isEmpty(s.getPlayUrl())) {
                                        if (s.isDefaultBitrate) {
                                            targetStream = s;
                                            break;
                                        }
                                        if (targetStream == null || s.bitRate > targetStream.bitRate) {
                                            targetStream = s;
                                        }
                                    }
                                }
                                if (targetStream != null) {
                                    streamHolder[0] = targetStream;
                                }
                            }
                            mgr.sp.edit().putInt(linePrefKey, streamHolder[0].lineIndex).apply();
                        }
                    }

                    java.util.List<TVPlayerManager.Variant> allVariants = new java.util.ArrayList<>();
                    if (allStreams != null) {
                        for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                            if (!TextUtils.isEmpty(s.getPlayUrl())) {
                                allVariants.add(TVPlayerManager.Variant.fromHuyaStreamInfo(s));
                            }
                        }
                    }
                    final String defaultUrl = streamHolder[0].getPlayUrl();
                    mgr.mCurrentHuyaLineIndex = streamHolder[0].lineIndex;

                    java.util.Map<Integer, java.util.List<TVPlayerManager.Variant>> lineGroups = new java.util.TreeMap<>();
                    for (TVPlayerManager.Variant v : allVariants) {
                        lineGroups.computeIfAbsent(v.huyaLineIndex, k -> new java.util.ArrayList<>()).add(v);
                    }
                    for (java.util.List<TVPlayerManager.Variant> group : lineGroups.values()) {
                        group.sort((a, b) -> Integer.compare(b.bandwidth, a.bandwidth));
                    }

                    java.util.List<TVPlayerManager.Variant> currentLineVariants = lineGroups.get(mgr.mCurrentHuyaLineIndex);
                    if (currentLineVariants != null) {
                        int defIdx = -1;
                        for (int i = 0; i < currentLineVariants.size(); i++) {
                            if (defaultUrl.equals(currentLineVariants.get(i).url)) { defIdx = i; break; }
                        }
                        if (defIdx > 0) {
                            TVPlayerManager.Variant defV = currentLineVariants.remove(defIdx);
                            currentLineVariants.add(0, defV);
                        }
                    }

                    synchronized (mgr.variantListLock) {
                        mgr.variantList.clear();
                        mgr.variantList.addAll(allVariants);
                    }
                    mgr.currentResolutionLabel = currentLineVariants != null && !currentLineVariants.isEmpty()
                            ? currentLineVariants.get(0).getDisplayLabel() : "";
                    int totalVariantCount = 0;
                    for (java.util.List<TVPlayerManager.Variant> g : lineGroups.values()) totalVariantCount += g.size();
                    Log.d(TAG, "【虎牙】variantList 填充: 共 " + totalVariantCount + " 个清晰度，分布在 " + lineGroups.size() + " 条线路");

                    if (mgr.currentChannel != null) {
                        java.util.List<String> backups = mgr.currentChannel.getBackupUrls();
                        if (backups == null) { backups = new java.util.ArrayList<>(); }
                        else backups.clear();

                        mgr.currentChannel.setMainPlayUrl(defaultUrl);

                        java.util.Set<String> seenUrls = new java.util.HashSet<>();
                        if (allVariants != null) {
                            for (TVPlayerManager.Variant v : allVariants) {
                                if (v.url != null && !seenUrls.contains(v.url)) {
                                    seenUrls.add(v.url);
                                    if (!v.url.equals(defaultUrl) && !backups.contains(v.url)) {
                                        backups.add(v.url);
                                    }
                                }
                            }
                        }
                        Log.d(TAG, "【虎牙】扁平化线路: 主源 + " + backups.size() + " 个备源 (总变体数=" + allVariants.size() + ")");
                    }

                    Log.d(TAG, "【虎牙】SDK 全量解析成功, 默认流=" + defaultUrl.substring(0, Math.min(80, defaultUrl.length())));
                    mgr.mPendingPlaybackHeaders = null;
                    mgr.mHandler.post(() -> doPlay(defaultUrl, initialSeekPosition));
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "【虎牙】SDK 全量解析失败: " + error);
                    mgr.mHandler.post(() -> {
                        android.widget.Toast.makeText(context, "虎牙解析失败：" + error, android.widget.Toast.LENGTH_SHORT).show();
                        if (mgr.sourceFailedListener != null) {
                            mgr.sourceFailedListener.onSourceFailed();
                        }
                    });
                }
            });
        }, "HuyaSDKWait").start();
    }

    public boolean isHuyaSource(String url) {
        if (url != null && (url.contains(".huya.com/") || url.contains("huya.com/src"))) {
            return true;
        }
        if (mgr.currentChannel != null) {
            String cid = mgr.currentChannel.getChannelId();
            if (cid != null && (cid.startsWith("huya_") || cid.startsWith("hy_"))) {
                return true;
            }
        }
        return false;
    }

    private void doPlay(String url, long initialSeekPosition) {
        try {
            if (mgr.player == null || url == null || url.trim().isEmpty()) return;

            String playUrl = url.trim();

            String lowerUrl = playUrl.toLowerCase(Locale.ROOT);
            boolean isRealStream = isHlsUrl(playUrl)
                    || lowerUrl.endsWith(".flv")
                    || lowerUrl.contains(".flv.huya.com/")
                    || lowerUrl.contains(".hls.huya.com/")
                    || (lowerUrl.startsWith("http") && (lowerUrl.contains("/src?ws") || lowerUrl.contains("&wssecret=")));
            Log.d(TAG, "doPlay: url=" + playUrl.substring(0, Math.min(100, playUrl.length())) + " isHls=" + isHlsUrl(playUrl) + " isRealStream=" + isRealStream);

            String finalUrl;
            if (mgr.currentChannel != null && !isRealStream) {
                String channelKey = mgr.currentChannel.getChannelId();
                if (TextUtils.isEmpty(channelKey)) {
                    channelKey = mgr.currentChannel.getName();
                }
                String prefKey = "channel_line_index_" + channelKey;
                int lineIndex = mgr.sp.getInt(prefKey, 0);
                if (lineIndex == 0 && mgr.sp.contains(TVPlayerManager.KEY_CHANNEL_LINE_INDEX)) {
                    lineIndex = mgr.sp.getInt(TVPlayerManager.KEY_CHANNEL_LINE_INDEX, 0);
                }

                if (lineIndex == 0) {
                    finalUrl = mgr.currentChannel.getMainPlayUrl();
                } else {
                    List<String> backups = mgr.currentChannel.getBackupUrls();
                    int backupIndex = lineIndex - 1;
                    if (backupIndex >= 0 && backupIndex < backups.size()) {
                        finalUrl = backups.get(backupIndex);
                    } else {
                        finalUrl = mgr.currentChannel.getMainPlayUrl();
                        Log.w(TAG, "线路索引越界，已自动切回主源");
                    }
                }
                mgr.dLog("切换线路后播放：" + finalUrl);
            } else {
                finalUrl = playUrl;
                mgr.dLog("直接播放地址：" + finalUrl);
            }
            mgr.currentUrl = finalUrl;

            if (isHuyaSource(finalUrl)) {
                doPlayHuya(finalUrl, initialSeekPosition);
            } else {
                doPlayNormal(finalUrl, initialSeekPosition);
            }

        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            if (e instanceof RedirectFailedException) {
                if (mgr.listener != null) mgr.listener.onPlayError("源跳转失败：" + e.getMessage());
                return;
            }
            mgr.retryManager.autoRetry("播放异常：" + e.getMessage(), e);
        }
    }

    private void doPlayNormal(String url, long initialSeekPosition) {
        Log.d(TAG, "【普通源】开始播放: " + url.substring(0, Math.min(80, url.length())));

        if (isHlsUrl(url)) {
            fetchAndParseMasterPlaylistNormal(url);
        } else {
            synchronized (mgr.variantListLock) { mgr.variantList.clear(); }
        }

        Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
        mgr.reusableHeaderMap.clear();
        for (String name : globalHeaders.names()) {
            mgr.reusableHeaderMap.put(name, globalHeaders.get(name));
        }
        mgr.dLog("【普通源】使用默认 headers " + mgr.reusableHeaderMap.size() + " 项");

        mgr.httpFactory.setDefaultRequestProperties(mgr.reusableHeaderMap);
        mgr.httpFactory.setChannelName(mgr.currentChannelName);
        mgr.httpFactory.setMaxRedirects(mgr.sp.getInt(TVPlayerManager.KEY_REDIRECT_MAX_COUNT, 5))
                .setAllowCrossDomainRedirects(mgr.sp.getBoolean(TVPlayerManager.KEY_REDIRECT_CROSS_DOMAIN, true))
                .setAllowCrossProtocolRedirects(mgr.sp.getBoolean(TVPlayerManager.KEY_REDIRECT_CROSS_PROTOCOL, true))
                .setFollowRedirectsWithHeaders(mgr.sp.getBoolean(TVPlayerManager.KEY_REDIRECT_FOLLOW_HEADERS, true))
                .setIgnoreSslErrorRedirect(mgr.sp.getBoolean(TVPlayerManager.KEY_REDIRECT_IGNORE_SSL, false))
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);

        MediaItem mediaItem = MediaItem.fromUri(url);
        MediaSource mediaSource;
        if (isHlsUrl(url)) {
            mediaSource = new HlsMediaSource.Factory(mgr.httpFactory).createMediaSource(mediaItem);
        } else {
            mediaSource = new ProgressiveMediaSource.Factory(mgr.httpFactory).createMediaSource(mediaItem);
        }

        mgr.player.setMediaSource(mediaSource, true);
        mgr.player.prepare();
        if (initialSeekPosition > 0) mgr.player.seekTo(initialSeekPosition);
        mgr.player.play();
        mgr.retryManager.startStuckDetection();
    }

    private void doPlayHuya(String url, long initialSeekPosition) {
        Log.d(TAG, "【虎牙源】开始播放: " + url.substring(0, Math.min(80, url.length())));

        Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
        mgr.reusableHeaderMap.clear();
        for (String name : globalHeaders.names()) {
            mgr.reusableHeaderMap.put(name, globalHeaders.get(name));
        }
        mgr.reusableHeaderMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        String huyaReferer = mgr.mHuyaRoomId > 0 ? "https://www.huya.com/" + mgr.mHuyaRoomId : "https://www.huya.com/";
        mgr.reusableHeaderMap.put("Referer", huyaReferer);
        mgr.reusableHeaderMap.put("Accept", "*/*");
        mgr.reusableHeaderMap.put("Accept-Language", "zh-CN,zh;q=0.9");
        mgr.reusableHeaderMap.put("Accept-Encoding", "identity");
        mgr.reusableHeaderMap.put("Connection", "keep-alive");
        mgr.dLog("【虎牙源】已启用浏览器UA+Referer(" + huyaReferer + ") 防盗链头");

        if (mgr.mPendingPlaybackHeaders != null && !mgr.mPendingPlaybackHeaders.isEmpty()) {
            int cnt = 0;
            for (Map.Entry<String, String> e : mgr.mPendingPlaybackHeaders.entrySet()) {
                if ("Origin".equalsIgnoreCase(e.getKey())) continue;
                if ("Referer".equalsIgnoreCase(e.getKey())) continue;
                mgr.reusableHeaderMap.put(e.getKey(), e.getValue());
                cnt++;
                Log.d(TAG, "  Header[" + e.getKey() + "] = " + e.getValue().substring(0, Math.min(50, e.getValue().length())));
            }
            Log.d(TAG, "【虎牙源】解析器专用Headers注入 " + cnt + " 项(含Cookie="
                    + (mgr.mPendingPlaybackHeaders.containsKey("Cookie") ? "是" : "否") + ")");
        } else {
            Log.d(TAG, "【虎牙源】mPendingPlaybackHeaders 为空，走默认虎牙 headers");
        }
        mgr.mPendingPlaybackHeaders = null;

        boolean sendCookie = mgr.sp.getBoolean(TVPlayerManager.KEY_REDIRECT_SEND_COOKIE, true);
        if (sendCookie && !mgr.reusableHeaderMap.containsKey("Cookie")) {
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) mgr.reusableHeaderMap.put("Cookie", cookies);
        }

        mgr.httpFactory.setDefaultRequestProperties(mgr.reusableHeaderMap);
        mgr.httpFactory.setChannelName(mgr.currentChannelName);
        mgr.httpFactory.setMaxRedirects(mgr.sp.getInt(TVPlayerManager.KEY_REDIRECT_MAX_COUNT, 5))
                .setAllowCrossDomainRedirects(true)
                .setAllowCrossProtocolRedirects(true)
                .setFollowRedirectsWithHeaders(true)
                .setIgnoreSslErrorRedirect(mgr.sp.getBoolean(TVPlayerManager.KEY_REDIRECT_IGNORE_SSL, false))
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);

        MediaItem mediaItem = MediaItem.fromUri(url);
        MediaSource mediaSource;
        if (isHlsUrl(url)) {
            mediaSource = new HlsMediaSource.Factory(mgr.httpFactory).createMediaSource(mediaItem);
        } else {
            mediaSource = new ProgressiveMediaSource.Factory(mgr.httpFactory).createMediaSource(mediaItem);
        }

        mgr.player.setMediaSource(mediaSource, true);
        mgr.player.prepare();
        if (initialSeekPosition > 0) mgr.player.seekTo(initialSeekPosition);
        mgr.player.play();
        mgr.retryManager.startStuckDetection();
    }

    private static final ExecutorService sPlaylistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TVPlayer-PlaylistParser");
        t.setDaemon(true);
        return t;
    });

    private void fetchAndParseMasterPlaylistNormal(String masterUrl) {
        if (mgr.isParsingMasterPlaylist) return;
        mgr.isParsingMasterPlaylist = true;
        sPlaylistExecutor.execute(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                Log.d(TAG, "【普通源】解析主播放列表: " + masterUrl.substring(0, Math.min(100, masterUrl.length())));

                java.net.URL url = new java.net.URL(masterUrl);
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);

                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
                connection.setRequestProperty("Accept", "*/*");

                int code = connection.getResponseCode();
                Log.d(TAG, "【普通源】主播放列表响应码: " + code);

                if (code == java.net.HttpURLConnection.HTTP_OK) {
                    StringBuilder content = new StringBuilder();
                    try (java.io.InputStream is = connection.getInputStream();
                         java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    String playlist = content.toString();
                    Log.d(TAG, "【普通源】主播放列表长度: " + playlist.length());
                    parseMasterPlaylist(playlist, masterUrl);
                } else if (code == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                        || code == java.net.HttpURLConnection.HTTP_MOVED_PERM) {
                    String newUrl = connection.getHeaderField("Location");
                    Log.d(TAG, "【普通源】重定向到: " + newUrl);
                    mgr.isParsingMasterPlaylist = false;
                    if (newUrl != null) {
                        fetchAndParseMasterPlaylistNormal(newUrl);
                        return;
                    }
                } else {
                    Log.e(TAG, "【普通源】主播放列表请求失败: code=" + code);
                    synchronized (mgr.variantListLock) { mgr.variantList.clear(); }
                }
            } catch (Exception e) {
                Log.e(TAG, "【普通源】解析主播放列表失败: ", e);
                synchronized (mgr.variantListLock) { mgr.variantList.clear(); }
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                mgr.isParsingMasterPlaylist = false;
            }
        });
    }

    private void fetchAndParseMasterPlaylistHuya(String masterUrl) {
        if (mgr.isParsingMasterPlaylist) return;
        mgr.isParsingMasterPlaylist = true;
        sPlaylistExecutor.execute(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                Log.d(TAG, "【虎牙源】解析主播放列表: " + masterUrl.substring(0, Math.min(100, masterUrl.length())));

                java.net.URL url = new java.net.URL(masterUrl);
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(false);

                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
                connection.setRequestProperty("Referer", "https://www.huya.com/");
                connection.setRequestProperty("Origin", "https://www.huya.com");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Connection", "keep-alive");

                if (mgr.mPendingPlaybackHeaders != null && !mgr.mPendingPlaybackHeaders.isEmpty()) {
                    for (Map.Entry<String, String> e : mgr.mPendingPlaybackHeaders.entrySet()) {
                        connection.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                String cookies = android.webkit.CookieManager.getInstance().getCookie(masterUrl);
                if (cookies != null && !cookies.isEmpty()) {
                    connection.setRequestProperty("Cookie", cookies);
                    Log.d(TAG, "【虎牙源】发送 Cookie: " + cookies.substring(0, Math.min(80, cookies.length())));
                }

                int code = connection.getResponseCode();
                Log.d(TAG, "【虎牙源】主播放列表响应码: " + code);

                if (code == java.net.HttpURLConnection.HTTP_OK) {
                    StringBuilder content = new StringBuilder();
                    try (java.io.InputStream is = connection.getInputStream();
                         java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    String playlist = content.toString();
                    Log.d(TAG, "【虎牙源】主播放列表长度: " + playlist.length());
                    parseMasterPlaylist(playlist, masterUrl);
                } else if (code == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                        || code == java.net.HttpURLConnection.HTTP_MOVED_PERM) {
                    String newUrl = connection.getHeaderField("Location");
                    Log.d(TAG, "【虎牙源】手动重定向到: " + newUrl);
                    mgr.isParsingMasterPlaylist = false;
                    if (newUrl != null) {
                        fetchAndParseMasterPlaylistHuya(newUrl);
                        return;
                    }
                } else {
                    try (java.io.InputStream es = connection.getErrorStream()) {
                        if (es != null) {
                            StringBuilder err = new StringBuilder();
                            byte[] buf = new byte[1024];
                            int len;
                            while ((len = es.read(buf)) != -1) {
                                err.append(new String(buf, 0, len));
                            }
                            if (err.length() > 0) {
                                Log.e(TAG, "【虎牙源】错误响应体: " + err.substring(0, Math.min(200, err.length())));
                            }
                        }
                    }
                    Log.e(TAG, "【虎牙源】主播放列表请求失败: code=" + code);
                    synchronized (mgr.variantListLock) { mgr.variantList.clear(); }
                }
            } catch (Exception e) {
                Log.e(TAG, "【虎牙源】解析主播放列表失败: ", e);
                synchronized (mgr.variantListLock) { mgr.variantList.clear(); }
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                mgr.isParsingMasterPlaylist = false;
            }
        });
    }

    private void parseMasterPlaylist(String playlist, String baseUrl) {
        List<TVPlayerManager.Variant> list = new ArrayList<>();
        Pattern streamInfPattern = Pattern.compile("^#EXT-X-STREAM-INF:", Pattern.CASE_INSENSITIVE);
        Pattern bandwidthPattern = Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);
        Pattern resolutionPattern = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);
        mgr.dLog("播放列表内容（截取前500字符）：\n" + playlist.substring(0, Math.min(playlist.length(), 500)));

        String[] lines = playlist.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!streamInfPattern.matcher(line).find()) continue;

            Matcher bwMatcher = bandwidthPattern.matcher(line);
            if (!bwMatcher.find()) continue;
            int bandwidth = Integer.parseInt(bwMatcher.group(1));

            int width = 0, height = 0;
            String resolutionStr = null;
            Matcher resMatcher = resolutionPattern.matcher(line);
            if (resMatcher.find()) {
                width = Integer.parseInt(resMatcher.group(1));
                height = Integer.parseInt(resMatcher.group(2));
                resolutionStr = width + "x" + height;
            }

            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (!next.isEmpty() && !next.startsWith("#")) {
                    uri = next;
                    break;
                }
            }
            if (uri != null) {
                if (!uri.startsWith("http")) {
                    uri = resolveUrl(baseUrl, uri);
                }
                list.add(new TVPlayerManager.Variant(uri, bandwidth, width, height));
                mgr.dLog("解析到清晰度: " + (height > 0 ? resolutionStr : "自适应") + " -> " + uri);
            }
        }
        list.sort((a, b) -> Integer.compare(a.height, b.height));
        synchronized (mgr.variantListLock) { mgr.variantList = list; }
        if (!list.isEmpty()) {
            mgr.dLog("解析到 " + list.size() + " 个清晰度");
        } else {
            Log.w(TAG, "未解析到任何清晰度流，可能是直播源本身不支持多码率或网络被拦截");
        }
    }

    private String resolveUrl(String base, String relative) {
        try {
            URL baseUrl = new URL(base);
            URL resolved = new URL(baseUrl, relative);
            return resolved.toString();
        } catch (Exception e) {
            return relative;
        }
    }
}