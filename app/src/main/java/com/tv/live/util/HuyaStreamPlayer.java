package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.tv.live.Channel;
import com.tv.live.RedirectLoggingHttpDataSource;
import com.tv.live.exception.RedirectFailedException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;

import okhttp3.Headers;

/**
 * 虎牙流播放器
 * 负责虎牙 SDK 解析、线路轮询、清晰度变体填充、
 * 虎牙专属播放（浏览器 UA/Referer/防盗链）、
 * M3U 播放列表解析（普通源和虎牙源）
 */
public class HuyaStreamPlayer {
    private static final String TAG = "HuyaStreamPlayer";

    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";
    private static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";

    public interface StreamPlaybackCallback {
        void onPlayError(String msg);
        void onSourceFailed();
        void dLog(String msg);
        void onPlaySuccess();
        void onSeekTo(long positionMs);
        void onPlay();
        void onPrepare();
        void onStuckDetectionStart();
        Context getContext();
        ExoPlayer getPlayer();
        PlayerView getPlayerView();
        FrameLayout getSdkPlayerContainer();
        android.os.Handler getHandler();
        ExecutorService getPlaylistExecutor();
        SharedPreferences getSharedPrefs();
        String getCurrentChannelName();
        Channel getCurrentChannel();
        void setCurrentUrl(String url);
        void setHuyaRoomId(int roomId);
        int getHuyaRoomId();
        void setPendingHeaders(Map<String, String> headers);
        Map<String, String> getPendingHeaders();
        void setReusableHeaderMap(Map<String, String> map);
        Map<String, String> getReusableHeaderMap();
        void setCurrentResolutionLabel(String label);
        void ensurePlayerBoundToView();
        void setMediaSourceAndPrepare(MediaSource source, long seekPosition);
    }

    private StreamPlaybackCallback callback;
    private VariantManager variantManager;

    public HuyaStreamPlayer(StreamPlaybackCallback callback, VariantManager variantManager) {
        this.callback = callback;
        this.variantManager = variantManager;
    }

    public boolean isHuyaRoomUrl(String url) {
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

    public boolean isHuyaProtocolUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.startsWith("huya://room/");
    }

    public boolean isHuyaSource(String url) {
        if (url != null && (url.contains(".huya.com/") || url.contains("huya.com/src"))) {
            return true;
        }
        Channel currentChannel = callback.getCurrentChannel();
        if (currentChannel != null) {
            String cid = currentChannel.getChannelId();
            if (cid != null && (cid.startsWith("huya_") || cid.startsWith("hy_"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHlsUrl(String url) {
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

    public void playUrlInternal(String url) {
        playUrlInternal(url, 0);
    }

    public void playUrlInternal(String url, long initialSeekPosition) {
        if (isHuyaProtocolUrl(url)) {
            String roomIdStr = url.replace("huya://room/", "").trim();
            int roomId;
            try {
                roomId = Integer.parseInt(roomIdStr);
            } catch (NumberFormatException e) {
                callback.onPlayError("虎牙房间号格式错误: " + url);
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
                callback.onPlayError("虎牙房间号格式错误: " + url);
                return;
            }
            playHuyaStream(roomId, initialSeekPosition);
            return;
        }
        doPlay(url, initialSeekPosition);
    }

    public void playHuyaStream(int roomId, long initialSeekPosition) {
        Context context = callback.getContext();
        android.os.Handler mHandler = callback.getHandler();
        PlayerView playerView = callback.getPlayerView();
        FrameLayout sdkPlayerContainer = callback.getSdkPlayerContainer();

        callback.setHuyaRoomId(roomId);
        final long parseStartTs = System.currentTimeMillis();

        if (sdkPlayerContainer != null) {
            sdkPlayerContainer.setVisibility(View.GONE);
        }
        if (playerView != null) {
            playerView.setVisibility(View.VISIBLE);
        }

        variantManager.clearVariantList();
        Channel currentChannel = callback.getCurrentChannel();
        if (currentChannel != null) {
            try {
                currentChannel.clearBackupUrls();
            } catch (Exception ignored) {}
        }

        if (!HuyaSDKParser.isSDKAvailable()) {
            Log.e(TAG, "【虎牙】SDK 不可用, roomId=" + roomId);
            mHandler.post(() -> {
                Toast.makeText(context, "虎牙 SDK 不可用，无法解析直播源", Toast.LENGTH_SHORT).show();
                callback.onSourceFailed();
            });
            return;
        }

        HuyaSDKParser.CachedStreams cached = HuyaSDKParser.getCachedStreams(roomId);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            long ageSec = (System.currentTimeMillis() - cached.timestamp) / 1000;
            Log.i(TAG, "🚀【虎牙并行加载】命中预解析缓存！房间=" + roomId
                    + "，缓存年龄=" + ageSec + "s，流数=" + cached.streams.size()
                    + "，即将瞬时启动播放");
        } else {
            Log.w(TAG, "⚠️【虎牙并行加载】未命中预解析缓存（房间=" + roomId + "）");
        }

        Log.d(TAG, "【虎牙】使用 SDK 全量解析, roomId=" + roomId);
        HuyaSDKParser.parseFull(roomId, new HuyaSDKParser.OnSDKFullResultListener() {
            @Override
            public void onSuccess(HuyaSDKParser.HuyaStreamInfo defaultStream,
                                  List<HuyaSDKParser.HuyaStreamInfo> allStreams,
                                  List<String> lines) {
                long costMs = System.currentTimeMillis() - parseStartTs;
                HuyaSDKParser.CachedStreams cs = HuyaSDKParser.getCachedStreams(roomId);
                boolean fromPreload = (cs != null && cs.streams == allStreams)
                        || (costMs < 300);
                Log.i(TAG, "⚡【虎牙解析耗时】" + costMs + "ms, roomId=" + roomId
                        + "，缓存命中=" + fromPreload
                        + "，流数=" + (allStreams != null ? allStreams.size() : 0));

                final HuyaSDKParser.HuyaStreamInfo[] streamHolder = {defaultStream};

                if (streamHolder[0] == null || TextUtils.isEmpty(streamHolder[0].getPlayUrl())) {
                    Log.e(TAG, "【虎牙】SDK 解析返回空默认地址");
                    mHandler.post(() -> {
                        Toast.makeText(context, "虎牙 SDK 解析失败：返回空地址", Toast.LENGTH_SHORT).show();
                        callback.onSourceFailed();
                    });
                    return;
                }

                if (allStreams != null && allStreams.size() > 1) {
                    Set<Integer> lineIndexSet = new TreeSet<>();
                    for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                        if (!TextUtils.isEmpty(s.getPlayUrl())) {
                            lineIndexSet.add(s.lineIndex);
                        }
                    }
                    List<Integer> uniqueLineIndices = new ArrayList<>(lineIndexSet);

                    if (uniqueLineIndices.size() > 1) {
                        String linePrefKey = "huya_line_poll_" + roomId;
                        SharedPreferences sp = callback.getSharedPrefs();
                        int lastLineIdx = sp.getInt(linePrefKey, uniqueLineIndices.get(0));

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
                        sp.edit().putInt(linePrefKey, streamHolder[0].lineIndex).apply();
                    }
                }

                List<Variant> allVariants = new ArrayList<>();
                if (allStreams != null) {
                    for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                        if (!TextUtils.isEmpty(s.getPlayUrl())) {
                            allVariants.add(Variant.fromHuyaStreamInfo(s));
                        }
                    }
                }
                final String defaultUrl = streamHolder[0].getPlayUrl();
                variantManager.setCurrentHuyaLineIndex(streamHolder[0].lineIndex);

                Map<Integer, List<Variant>> lineGroups = new TreeMap<>();
                for (Variant v : allVariants) {
                    lineGroups.computeIfAbsent(v.huyaLineIndex, k -> new ArrayList<>()).add(v);
                }
                for (List<Variant> group : lineGroups.values()) {
                    Collections.sort(group, (a, b) -> Integer.compare(b.bandwidth, a.bandwidth));
                }

                List<Variant> currentLineVariants = lineGroups.get(variantManager.getCurrentHuyaLineIndex());
                if (currentLineVariants != null) {
                    int defIdx = -1;
                    for (int i = 0; i < currentLineVariants.size(); i++) {
                        if (defaultUrl.equals(currentLineVariants.get(i).url)) { defIdx = i; break; }
                    }
                    if (defIdx > 0) {
                        Variant defV = currentLineVariants.remove(defIdx);
                        currentLineVariants.add(0, defV);
                    }
                }

                variantManager.setVariantList(allVariants);
                variantManager.setCurrentResolutionLabel(currentLineVariants != null && !currentLineVariants.isEmpty()
                        ? currentLineVariants.get(0).getDisplayLabel() : "");

                int totalVariantCount = 0;
                for (List<Variant> g : lineGroups.values()) totalVariantCount += g.size();
                Log.d(TAG, "【虎牙】variantList 填充: 共 " + totalVariantCount + " 个清晰度，分布在 " + lineGroups.size() + " 条线路");

                if (currentChannel != null) {
                    List<String> backups = currentChannel.getBackupUrls();
                    if (backups == null) { backups = new ArrayList<>(); }
                    else backups.clear();

                    currentChannel.setMainPlayUrl(defaultUrl);

                    Set<String> seenUrls = new HashSet<>();
                    if (allVariants != null) {
                        for (Variant v : allVariants) {
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

                callback.setPendingHeaders(null);
                mHandler.post(() -> doPlay(defaultUrl, initialSeekPosition));
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "【虎牙】SDK 全量解析失败: " + error);
                mHandler.post(() -> {
                    Toast.makeText(context, "虎牙 SDK 解析失败: " + error, Toast.LENGTH_SHORT).show();
                    callback.onSourceFailed();
                });
            }
        });
    }

    private void doPlay(String url, long initialSeekPosition) {
        Context context = callback.getContext();
        try {
            ExoPlayer player = callback.getPlayer();
            if (player == null || url == null || url.trim().isEmpty()) return;

            String playUrl = url.trim();
            String lowerUrl = playUrl.toLowerCase(Locale.ROOT);
            boolean isRealStream = isHlsUrl(playUrl)
                    || lowerUrl.endsWith(".flv")
                    || lowerUrl.contains(".flv.huya.com/")
                    || lowerUrl.contains(".hls.huya.com/")
                    || (lowerUrl.startsWith("http") && (lowerUrl.contains("/src?ws") || lowerUrl.contains("&wssecret=")));
            Log.d(TAG, "doPlay: url=" + playUrl.substring(0, Math.min(100, playUrl.length())) + " isHls=" + isHlsUrl(playUrl));

            String finalUrl;
            Channel currentChannel = callback.getCurrentChannel();
            if (currentChannel != null && !isRealStream) {
                SharedPreferences sp = callback.getSharedPrefs();
                String channelKey = currentChannel.getChannelId();
                if (TextUtils.isEmpty(channelKey)) {
                    channelKey = currentChannel.getName();
                }
                String prefKey = "channel_line_index_" + channelKey;
                int lineIndex = sp.getInt(prefKey, 0);
                if (lineIndex == 0 && sp.contains(KEY_CHANNEL_LINE_INDEX)) {
                    lineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
                }

                if (lineIndex == 0) {
                    finalUrl = currentChannel.getMainPlayUrl();
                } else {
                    List<String> backups = currentChannel.getBackupUrls();
                    int backupIndex = lineIndex - 1;
                    if (backupIndex >= 0 && backupIndex < backups.size()) {
                        finalUrl = backups.get(backupIndex);
                    } else {
                        finalUrl = currentChannel.getMainPlayUrl();
                        Log.w(TAG, "线路索引越界，已自动切回主源");
                    }
                }
                callback.dLog("切换线路后播放：" + finalUrl);
            } else {
                finalUrl = playUrl;
                callback.dLog("直接播放地址：" + finalUrl);
            }
            callback.setCurrentUrl(finalUrl);

            if (isHuyaSource(finalUrl)) {
                doPlayHuya(finalUrl, initialSeekPosition);
            } else {
                doPlayNormal(finalUrl, initialSeekPosition);
            }

        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            if (e instanceof RedirectFailedException) {
                callback.onPlayError("源跳转失败：" + e.getMessage());
                return;
            }
            callback.onPlayError("播放异常：" + e.getMessage());
        }
    }

    private void doPlayNormal(String url, long initialSeekPosition) {
        Context context = callback.getContext();
        ExoPlayer player = callback.getPlayer();
        String currentChannelName = callback.getCurrentChannelName();

        Log.d(TAG, "【普通源】开始播放: " + url.substring(0, Math.min(80, url.length())));

        if (isHlsUrl(url)) {
            fetchAndParseMasterPlaylistNormal(url);
        } else {
            variantManager.clearVariantList();
        }

        SharedPreferences sp = callback.getSharedPrefs();
        boolean debugEnabled = sp.getBoolean("debug_log_enable", false);

        RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
        httpFactory.setDebugLogEnabled(debugEnabled);

        Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
        Map<String, String> reusableHeaderMap = callback.getReusableHeaderMap();
        reusableHeaderMap.clear();
        for (String name : globalHeaders.names()) {
            reusableHeaderMap.put(name, globalHeaders.get(name));
        }
        callback.dLog("【普通源】使用默认 headers " + reusableHeaderMap.size() + " 项");

        httpFactory.setDefaultRequestProperties(reusableHeaderMap);
        httpFactory.setChannelName(currentChannelName);
        httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                .setAllowCrossDomainRedirects(sp.getBoolean("redirect_cross_domain", true))
                .setAllowCrossProtocolRedirects(sp.getBoolean("redirect_cross_protocol", true))
                .setFollowRedirectsWithHeaders(sp.getBoolean("redirect_follow_headers", true))
                .setIgnoreSslErrorRedirect(sp.getBoolean("redirect_ignore_ssl", false))
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);

        MediaItem mediaItem = MediaItem.fromUri(url);
        MediaSource mediaSource;
        if (isHlsUrl(url)) {
            mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        } else {
            mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        }

        callback.ensurePlayerBoundToView();
        callback.setMediaSourceAndPrepare(mediaSource, initialSeekPosition);
        callback.onStuckDetectionStart();
    }

    private void doPlayHuya(String url, long initialSeekPosition) {
        Context context = callback.getContext();
        ExoPlayer player = callback.getPlayer();
        String currentChannelName = callback.getCurrentChannelName();

        Log.d(TAG, "【虎牙源】开始播放: " + url.substring(0, Math.min(80, url.length())));

        SharedPreferences sp = callback.getSharedPrefs();
        boolean debugEnabled = sp.getBoolean("debug_log_enable", false);

        RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
        httpFactory.setDebugLogEnabled(debugEnabled);

        Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
        Map<String, String> reusableHeaderMap = callback.getReusableHeaderMap();
        reusableHeaderMap.clear();
        for (String name : globalHeaders.names()) {
            reusableHeaderMap.put(name, globalHeaders.get(name));
        }
        reusableHeaderMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        int huyaRoomId = callback.getHuyaRoomId();
        String huyaReferer = huyaRoomId > 0 ? "https://www.huya.com/" + huyaRoomId : "https://www.huya.com/";
        reusableHeaderMap.put("Referer", huyaReferer);
        reusableHeaderMap.put("Accept", "*/*");
        reusableHeaderMap.put("Accept-Language", "zh-CN,zh;q=0.9");
        reusableHeaderMap.put("Accept-Encoding", "identity");
        reusableHeaderMap.put("Connection", "keep-alive");
        callback.dLog("【虎牙源】已启用浏览器UA+Referer(" + huyaReferer + ") 防盗链头");

        Map<String, String> mPendingPlaybackHeaders = callback.getPendingHeaders();
        if (mPendingPlaybackHeaders != null && !mPendingPlaybackHeaders.isEmpty()) {
            int cnt = 0;
            for (Map.Entry<String, String> e : mPendingPlaybackHeaders.entrySet()) {
                if ("Origin".equalsIgnoreCase(e.getKey())) continue;
                if ("Referer".equalsIgnoreCase(e.getKey())) continue;
                reusableHeaderMap.put(e.getKey(), e.getValue());
                cnt++;
            }
            Log.d(TAG, "【虎牙源】解析器专用Headers注入 " + cnt + " 项(含Cookie="
                    + (mPendingPlaybackHeaders.containsKey("Cookie") ? "是" : "否") + ")");
        } else {
            Log.d(TAG, "【虎牙源】mPendingPlaybackHeaders 为空，走默认虎牙 headers");
        }
        callback.setPendingHeaders(null);

        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        if (sendCookie && !reusableHeaderMap.containsKey("Cookie")) {
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) reusableHeaderMap.put("Cookie", cookies);
        }

        httpFactory.setDefaultRequestProperties(reusableHeaderMap);
        httpFactory.setChannelName(currentChannelName);
        httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                .setAllowCrossDomainRedirects(true)
                .setAllowCrossProtocolRedirects(true)
                .setFollowRedirectsWithHeaders(true)
                .setIgnoreSslErrorRedirect(sp.getBoolean("redirect_ignore_ssl", false))
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);

        MediaItem mediaItem = MediaItem.fromUri(url);
        MediaSource mediaSource;
        if (isHlsUrl(url)) {
            mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        } else {
            mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        }

        callback.ensurePlayerBoundToView();
        callback.setMediaSourceAndPrepare(mediaSource, initialSeekPosition);
        callback.onStuckDetectionStart();
    }

    private void fetchAndParseMasterPlaylistNormal(String masterUrl) {
        if (variantManager.isParsingMasterPlaylist()) return;
        variantManager.setParsingMasterPlaylist(true);
        ExecutorService sPlaylistExecutor = callback.getPlaylistExecutor();
        sPlaylistExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                Log.d(TAG, "【普通源】解析主播放列表: " + masterUrl.substring(0, Math.min(100, masterUrl.length())));

                URL url = new URL(masterUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);

                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
                connection.setRequestProperty("Accept", "*/*");

                int code = connection.getResponseCode();
                Log.d(TAG, "【普通源】主播放列表响应码: " + code);

                if (code == HttpURLConnection.HTTP_OK) {
                    StringBuilder content = new StringBuilder();
                    try (InputStream is = connection.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    String playlist = content.toString();
                    Log.d(TAG, "【普通源】主播放列表长度: " + playlist.length());
                    variantManager.parseMasterPlaylist(playlist, masterUrl);
                } else if (code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_MOVED_PERM) {
                    String newUrl = connection.getHeaderField("Location");
                    Log.d(TAG, "【普通源】重定向到: " + newUrl);
                    variantManager.setParsingMasterPlaylist(false);
                    if (newUrl != null) {
                        fetchAndParseMasterPlaylistNormal(newUrl);
                        return;
                    }
                } else {
                    Log.e(TAG, "【普通源】主播放列表请求失败: code=" + code);
                    variantManager.clearVariantList();
                }
            } catch (Exception e) {
                Log.e(TAG, "【普通源】解析主播放列表失败: ", e);
                variantManager.clearVariantList();
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                variantManager.setParsingMasterPlaylist(false);
            }
        });
    }

    private void fetchAndParseMasterPlaylistHuya(String masterUrl) {
        if (variantManager.isParsingMasterPlaylist()) return;
        variantManager.setParsingMasterPlaylist(true);
        ExecutorService sPlaylistExecutor = callback.getPlaylistExecutor();
        sPlaylistExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                Log.d(TAG, "【虎牙源】解析主播放列表: " + masterUrl.substring(0, Math.min(100, masterUrl.length())));

                URL url = new URL(masterUrl);
                connection = (HttpURLConnection) url.openConnection();
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

                Map<String, String> mPendingPlaybackHeaders = callback.getPendingHeaders();
                if (mPendingPlaybackHeaders != null && !mPendingPlaybackHeaders.isEmpty()) {
                    for (Map.Entry<String, String> e : mPendingPlaybackHeaders.entrySet()) {
                        connection.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                String cookies = CookieManager.getInstance().getCookie(masterUrl);
                if (cookies != null && !cookies.isEmpty()) {
                    connection.setRequestProperty("Cookie", cookies);
                }

                int code = connection.getResponseCode();
                Log.d(TAG, "【虎牙源】主播放列表响应码: " + code);

                if (code == HttpURLConnection.HTTP_OK) {
                    StringBuilder content = new StringBuilder();
                    try (InputStream is = connection.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    String playlist = content.toString();
                    Log.d(TAG, "【虎牙源】主播放列表长度: " + playlist.length());
                    variantManager.parseMasterPlaylist(playlist, masterUrl);
                } else if (code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_MOVED_PERM) {
                    String newUrl = connection.getHeaderField("Location");
                    Log.d(TAG, "【虎牙源】手动重定向到: " + newUrl);
                    variantManager.setParsingMasterPlaylist(false);
                    if (newUrl != null) {
                        fetchAndParseMasterPlaylistHuya(newUrl);
                        return;
                    }
                } else {
                    Log.e(TAG, "【虎牙源】主播放列表请求失败: code=" + code);
                    variantManager.clearVariantList();
                }
            } catch (Exception e) {
                Log.e(TAG, "【虎牙源】解析主播放列表失败: ", e);
                variantManager.clearVariantList();
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                variantManager.setParsingMasterPlaylist(false);
            }
        });
    }

    public void release() {
        callback = null;
        variantManager = null;
    }
}
