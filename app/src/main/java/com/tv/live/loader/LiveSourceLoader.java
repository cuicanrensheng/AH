package com.tv.live.loader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tv.live.Channel;
import com.tv.live.PlaylistParser;
import com.tv.live.UrlConfig;
import com.tv.live.util.CacheManager;
import com.tv.live.util.NetUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

public class LiveSourceLoader {
    private static final String TAG = "LiveSourceLoader";
    private static final String FALLBACK_FILE = "live_source_fallback.m3u";
    private static final int MIN_CHANNELS_FOR_FALLBACK = 10;

    private static LiveSourceLoader instance;
    private final Context context;
    private final Handler mainHandler;
    private final CacheManager cacheManager;

    public enum AccelerateType {
        JSDELIVR,
        GHPROXY,
        GITMIRROR,
        NONE
    }

    private AccelerateType accelerateType = AccelerateType.JSDELIVR;
    private boolean accelerateEnabled = true;

    public interface LoadCallback {
        void onSuccess(List<Channel> channels);
        void onError(String errorMsg);
    }

    private LiveSourceLoader(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.cacheManager = CacheManager.getInstance(context);
    }

    public static LiveSourceLoader getInstance(Context context) {
        if (instance == null) {
            instance = new LiveSourceLoader(context.getApplicationContext());
        }
        return instance;
    }

    public void setAccelerateEnabled(boolean enabled) {
        this.accelerateEnabled = enabled;
    }

    public void setAccelerateType(AccelerateType type) {
        this.accelerateType = type;
    }

    public void load(final LoadCallback callback) {
        new Thread(() -> {
            String assetUrl = UrlConfig.LIVE_URL;
            if (assetUrl != null && assetUrl.startsWith("asset://")) {
                try {
                    String assetPath = assetUrl.substring("asset://".length());
                    String rawContent = loadFromAssets(assetPath);
                    if (rawContent != null && !rawContent.isEmpty()) {
                        cacheManager.saveFileCache("live_source", rawContent);
                        saveFallback(rawContent);
                    }
                    List<Channel> channels = PlaylistParser.parseContent(rawContent);
                    mainHandler.post(() -> callback.onSuccess(channels));
                } catch (Exception e) {
                    e.printStackTrace();
                    mainHandler.post(() -> callback.onError(e.getMessage()));
                }
                return;
            }

            String lastErrorMsg = null;
            List<String> sources = Arrays.asList(UrlConfig.LIVE_URL, UrlConfig.LIVE_URL_2);
            Log.e(TAG, "LOAD: source count=" + sources.size() + " LIVE_URL='" + UrlConfig.LIVE_URL + "' LIVE_URL_2='" + UrlConfig.LIVE_URL_2 + "'");
            for (int i = 0; i < sources.size(); i++) {
                String url = sources.get(i);
                if (url == null || url.trim().isEmpty()) {
                    Log.e(TAG, "LOAD: source #" + (i+1) + " is null/empty, skipping");
                    continue;
                }
                String acceleratedUrl = getAcceleratedUrl(url);
                Log.d(TAG, "【网络】直播源 #" + (i + 1) + " 开始加载：" + acceleratedUrl);
                Log.e(TAG, "LOAD: source #" + (i+1) + " downloading: " + acceleratedUrl);
                try {
                    String rawContent = downloadRawContent(acceleratedUrl);
                    if (rawContent == null || rawContent.isEmpty()) {
                        Log.e(TAG, "LOAD: source #" + (i+1) + " download returned empty/null");
                        throw new IOException("下载为空");
                    }
                    List<Channel> channels = PlaylistParser.parseContent(rawContent);
                    if (channels == null || channels.isEmpty()) {
                        Log.e(TAG, "LOAD: source #" + (i+1) + " parsed 0 channels");
                        throw new IOException("解析后频道数=0");
                    }
                    cacheManager.saveFileCache("live_source", rawContent);
                    if (channels.size() >= MIN_CHANNELS_FOR_FALLBACK) {
                        saveFallback(rawContent);
                        Log.d(TAG, "【网络】永久兜底已更新，频道数 " + channels.size());
                    } else {
                        Log.w(TAG, "【网络】返回频道数过少（" + channels.size() + " < " + MIN_CHANNELS_FOR_FALLBACK + "），不覆盖永久兜底");
                    }
                    final List<Channel> finalChannels = channels;
                    Log.d(TAG, "【网络】直播源 #" + (i + 1) + " 加载成功，共 " + finalChannels.size() + " 个频道");
                    Log.e(TAG, "LOAD: source #" + (i+1) + " SUCCESS, channels=" + finalChannels.size());
                    mainHandler.post(() -> callback.onSuccess(finalChannels));
                    return;
                } catch (Exception e) {
                    lastErrorMsg = "源#" + (i + 1) + "(" + url + "): " + e.getMessage();
                    Log.w(TAG, "【网络】直播源 #" + (i + 1) + " 失败：" + e.getMessage());
                }
            }

            Log.w(TAG, "【兜底】网络全部失败，尝试读取本地永久兜底 " + FALLBACK_FILE);
            String fallback = readFallback();
            if (fallback != null && !fallback.isEmpty()) {
                try {
                    List<Channel> fallbackChannels = PlaylistParser.parseContent(fallback);
                    if (fallbackChannels != null && !fallbackChannels.isEmpty()) {
                        Log.i(TAG, "【兜底】启用本地永久兜底，共 " + fallbackChannels.size() + " 个频道，继续可看电视");
                        final List<Channel> finalFb = fallbackChannels;
                        mainHandler.post(() -> callback.onSuccess(finalFb));
                        return;
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "【兜底】解析失败：" + ex.getMessage());
                }
            }

            final String err = (lastErrorMsg != null ? lastErrorMsg : "未知错误") + "（且无本地兜底数据）";
            mainHandler.post(() -> callback.onError(err));
        }).start();
    }

    private String loadFromAssets(String assetPath) {
        try {
            InputStream is = context.getAssets().open(assetPath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            is.close();
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getAcceleratedUrl(String originalUrl) {
        if (!accelerateEnabled) return originalUrl;
        if (originalUrl == null || originalUrl.trim().isEmpty()) return originalUrl;
        if (!isGitHubUrl(originalUrl)) return originalUrl;
        switch (accelerateType) {
            case JSDELIVR: return convertToJsdelivr(originalUrl);
            case GHPROXY: return convertToGhproxy(originalUrl);
            case GITMIRROR: return convertToGitmirror(originalUrl);
            default: return originalUrl;
        }
    }

    private boolean isGitHubUrl(String url) {
        if (url == null) return false;
        return url.contains("raw.githubusercontent.com")
                || url.contains("github.com/") && url.contains("/raw/")
                || url.contains("raw.github.com");
    }

    private String convertToJsdelivr(String githubUrl) {
        try {
            GitHubUrlInfo info = parseGitHubUrl(githubUrl);
            if (info == null) return githubUrl;
            StringBuilder sb = new StringBuilder();
            sb.append("https://cdn.jsdelivr.net/gh/");
            sb.append(info.user).append("/").append(info.repo);
            if (info.branch != null && !info.branch.isEmpty()) {
                sb.append("@").append(info.branch);
            }
            sb.append("/").append(info.path);
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return githubUrl;
        }
    }

    private String convertToGhproxy(String githubUrl) {
        try { return "https://ghproxy.com/" + githubUrl; }
        catch (Exception e) { return githubUrl; }
    }

    private String convertToGitmirror(String githubUrl) {
        try {
            return githubUrl.replace("raw.githubusercontent.com", "raw.gitmirror.com")
                    .replace("raw.github.com", "raw.gitmirror.com");
        } catch (Exception e) { return githubUrl; }
    }

    private static class GitHubUrlInfo {
        String user;
        String repo;
        String branch;
        String path;
    }

    private GitHubUrlInfo parseGitHubUrl(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        try {
            GitHubUrlInfo info = new GitHubUrlInfo();
            String cleanUrl = url.startsWith("https://") ? url.substring(8) : (url.startsWith("http://") ? url.substring(7) : url);
            if (cleanUrl.startsWith("raw.githubusercontent.com/")) {
                String pathPart = cleanUrl.substring("raw.githubusercontent.com/".length());
                String[] parts = pathPart.split("/", 4);
                if (parts.length >= 4) {
                    info.user = parts[0]; info.repo = parts[1]; info.branch = parts[2]; info.path = parts[3];
                    return info;
                }
            }
            if (cleanUrl.startsWith("github.com/") && cleanUrl.contains("/raw/")) {
                Pattern pattern = Pattern.compile("github\\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)");
                Matcher matcher = pattern.matcher(cleanUrl);
                if (matcher.find()) {
                    info.user = matcher.group(1); info.repo = matcher.group(2); info.branch = matcher.group(3); info.path = matcher.group(4);
                    return info;
                }
            }
            if (cleanUrl.startsWith("raw.github.com/")) {
                String pathPart = cleanUrl.substring("raw.github.com/".length());
                String[] parts = pathPart.split("/", 4);
                if (parts.length >= 4) {
                    info.user = parts[0]; info.repo = parts[1]; info.branch = parts[2]; info.path = parts[3];
                    return info;
                }
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private static final int DOWNLOAD_MAX_RETRIES = 3;
    private static final long DOWNLOAD_RETRY_DELAY_MS = 500;

    private String downloadRawContent(String urlStr) {
        int lastCode = -1;
        String lastError = "";
        for (int attempt = 1; attempt <= DOWNLOAD_MAX_RETRIES; attempt++) {
            try {
                Response response = NetUtil.getInstance().syncGet(urlStr);
                try (Response resp = response) {
                    int responseCode = resp.code();
                    if (responseCode != 200 || resp.body() == null) {
                        lastCode = responseCode;
                        lastError = "HTTP " + responseCode;
                        Log.w(TAG, "下载失败 attempt=" + attempt + "/" + DOWNLOAD_MAX_RETRIES
                                + " code=" + responseCode + " url=" + urlStr);
                        if (responseCode >= 400 && responseCode < 500) break;
                        continue;
                    }
                    String content = resp.body().string();
                    Log.d(TAG, "下载成功 attempt=" + attempt + " size=" + content.length()
                            + " url=" + urlStr.substring(0, Math.min(80, urlStr.length())));
                    return content;
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                Log.w(TAG, "下载异常 attempt=" + attempt + "/" + DOWNLOAD_MAX_RETRIES
                        + " url=" + urlStr + " err=" + lastError);
            }
            if (attempt < DOWNLOAD_MAX_RETRIES) {
                try { Thread.sleep(DOWNLOAD_RETRY_DELAY_MS); } catch (InterruptedException ignored) {}
            }
        }
        Log.e(TAG, "下载最终失败 code=" + lastCode + " url=" + urlStr + " err=" + lastError);
        return null;
    }

    private File getFallbackFile() {
        return new File(context.getFilesDir(), FALLBACK_FILE);
    }

    private void saveFallback(String content) {
        if (content == null || content.isEmpty()) return;
        File file = getFallbackFile();
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        } catch (IOException e) {
            Log.e(TAG, "保存永久兜底失败：" + e.getMessage());
        } finally {
            if (fos != null) { try { fos.close(); } catch (IOException ignored) {} }
        }
    }

    private String readFallback() {
        File file = getFallbackFile();
        if (!file.exists() || file.length() <= 0) return null;
        FileInputStream fis = null;
        BufferedReader br = null;
        try {
            fis = new FileInputStream(file);
            br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "读取永久兜底失败：" + e.getMessage());
            return null;
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
            if (fis != null) { try { fis.close(); } catch (IOException ignored) {} }
        }
    }
}