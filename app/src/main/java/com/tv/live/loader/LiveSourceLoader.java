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

/**
 * ✅ 直播源加载器（带缓存 + GitHub 智能加速 + 完整3xx重定向处理）
 *
 * 【缓存策略】
 * 1. 加载成功后，自动保存原始M3U文本到本地缓存
 * 2. 缓存有效期24小时
 * 3. MainActivity 里先读缓存快速显示，再后台刷新最新数据
 *
 * 【GitHub 智能加速】
 * 自动识别 GitHub raw 链接，自动走 CDN 加速，大幅提升下载速度。
 *
 * 【重定向修复点】
 * 1. 手动处理跨协议/跨域名301/302/307/308跳转
 * 2. 最大5次跳转限制，防止死循环
 * 3. 支持相对路径Location自动拼接
 * 4. 跳转携带UA防CDN拦截
 * 5. 完整跳转日志打印
 * 6. GZIP压缩跳转响应兼容
 */
public class LiveSourceLoader {
    private static final String TAG = "LiveSourceLoader";
    /** 永久兜底文件名（保存在 filesDir，**不随 CacheManager 24h 过期**，GitHub/Gitee 服务器故障时兜底继续看电视） */
    private static final String FALLBACK_FILE = "live_source_fallback.m3u";
    /** 最少频道数阈值：下载结果低于这个数不覆盖兜底，防止服务器返回空/半截文件把好的兜底冲掉 */
    private static final int MIN_CHANNELS_FOR_FALLBACK = 10;

    private static LiveSourceLoader instance;
    private final Context context;
    private final Handler mainHandler;
    private final CacheManager cacheManager;
    // ====================================================================
    // ✅ GitHub 加速相关配置
    // ====================================================================
    /**
     * 加速源类型
     */
    public enum AccelerateType {
        /** jsDelivr CDN（推荐，全球加速） */
        JSDELIVR,
        /** ghproxy（GitHub 反向代理） */
        GHPROXY,
        /** gitmirror（GitHub 镜像站） */
        GITMIRROR,
        /** 不加速（直连） */
        NONE
    }
    /** 当前使用的加速源（默认 jsDelivr，效果最好） */
    private AccelerateType accelerateType = AccelerateType.JSDELIVR;
    /** 是否启用 GitHub 加速 */
    private boolean accelerateEnabled = true;
    // ====================================================================
    // 接口定义
    // ====================================================================
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
    // ====================================================================
    // ✅ GitHub 加速：设置加速源
    // ====================================================================
    /**
     * 设置是否启用 GitHub 加速
     *
     * @param enabled 是否启用
     */
    public void setAccelerateEnabled(boolean enabled) {
        this.accelerateEnabled = enabled;
        // 🟢【已移除】SettingsActivity.log("【直播源加速】" + (enabled ? "已启用" : "已禁用"));
    }
    /**
     * 设置加速源类型
     *
     * @param type 加速源类型
     */
    public void setAccelerateType(AccelerateType type) {
        this.accelerateType = type;
        // 🟢【已移除】SettingsActivity.log("【直播源加速】加速源切换为：" + getAccelerateTypeName(type));
    }
    /**
     * 获取加速源名称
     */
    private String getAccelerateTypeName(AccelerateType type) {
        switch (type) {
            case JSDELIVR: return "jsDelivr CDN";
            case GHPROXY: return "ghproxy";
            case GITMIRROR: return "gitmirror";
            case NONE: return "不加速（直连）";
            default: return "未知";
        }
    }
    // ====================================================================
    // 加载直播源
    // ====================================================================
    /**
     * 加载直播源（网络多源 fallback + 本地永久兜底）
     *
     * 🟢 故障兜底链路（防止 GitHub/Gitee 服务器挂掉看不了电视）：
     *   1) 依次尝试 LIVE_URL → LIVE_URL_2（两个源都走加速 + UA）
     *   2) 任一成功 → 保存 Cache（24h）+ 保存永久兜底 filesDir，回调 onSuccess
     *   3) 都失败 → 读取本地永久兜底 FALLBACK_FILE：
     *        · 有数据 → 回调 onSuccess（兜底）继续可看电视
     *        · 无数据 → 才回调 onError（首次安装没网才会出现）
     */
    public void load(final LoadCallback callback) {
        new Thread(() -> {
            // —— asset 本地内置源（不改） ——
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

            // —— 1) 网络多源 fallback：LIVE_URL → LIVE_URL_2 ——
            String lastErrorMsg = null;
            List<String> sources = Arrays.asList(UrlConfig.LIVE_URL, UrlConfig.LIVE_URL_2);
            for (int i = 0; i < sources.size(); i++) {
                String url = sources.get(i);
                if (url == null || url.trim().isEmpty()) continue;
                String acceleratedUrl = getAcceleratedUrl(url);
                Log.d(TAG, "【网络】直播源 #" + (i + 1) + " 开始加载：" + acceleratedUrl);
                try {
                    String rawContent = downloadRawContent(acceleratedUrl);
                    if (rawContent == null || rawContent.isEmpty()) {
                        throw new IOException("下载为空");
                    }
                    List<Channel> channels = PlaylistParser.parseContent(rawContent);
                    if (channels == null || channels.isEmpty()) {
                        throw new IOException("解析后频道数=0");
                    }
                    // 🟢 成功 → 同时保存 24h 缓存 + 永久兜底
                    cacheManager.saveFileCache("live_source", rawContent);
                    if (channels.size() >= MIN_CHANNELS_FOR_FALLBACK) {
                        saveFallback(rawContent);
                        Log.d(TAG, "【网络】永久兜底已更新，频道数 " + channels.size());
                    } else {
                        Log.w(TAG, "【网络】返回频道数过少（" + channels.size() + " < " + MIN_CHANNELS_FOR_FALLBACK + "），不覆盖永久兜底");
                    }
                    final List<Channel> finalChannels = channels;
                    Log.d(TAG, "【网络】直播源 #" + (i + 1) + " 加载成功，共 " + finalChannels.size() + " 个频道");
                    mainHandler.post(() -> callback.onSuccess(finalChannels));
                    return;
                } catch (Exception e) {
                    lastErrorMsg = "源#" + (i + 1) + "(" + url + "): " + e.getMessage();
                    Log.w(TAG, "【网络】直播源 #" + (i + 1) + " 失败：" + e.getMessage());
                }
            }

            // —— 2) 网络全部失败 → 读本地永久兜底 ——
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

            // —— 3) 网络失败且无兜底 → 才抛错误 ——
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
    // ====================================================================
    // ✅ GitHub 智能加速：获取加速后的 URL
    // ====================================================================
    /**
     * 获取加速后的 URL
     *
     * 【智能识别】
     * 1. 如果是 GitHub 链接，自动替换成加速地址
     * 2. 如果不是 GitHub 链接，直接返回原地址
     * 3. 如果加速功能禁用，直接返回原地址
     *
     * @param originalUrl 原始 URL
     * @return 加速后的 URL（如果是 GitHub 链接）
     */
    public String getAcceleratedUrl(String originalUrl) {
        // 加速功能禁用，直接返回
        if (!accelerateEnabled) {
            return originalUrl;
        }
        // 空地址，直接返回
        if (originalUrl == null || originalUrl.trim().isEmpty()) {
            return originalUrl;
        }
        // 检查是否是 GitHub 链接
        if (!isGitHubUrl(originalUrl)) {
            return originalUrl;
        }
        // 根据加速源类型转换
        switch (accelerateType) {
            case JSDELIVR:
                return convertToJsdelivr(originalUrl);
            case GHPROXY:
                return convertToGhproxy(originalUrl);
            case GITMIRROR:
                return convertToGitmirror(originalUrl);
            case NONE:
            default:
                return originalUrl;
        }
    }
    // ====================================================================
    // ✅ GitHub 链接识别
    // ====================================================================
    /**
     * 判断是否是 GitHub 链接
     *
     * 支持识别的格式：
     * 1. raw.githubusercontent.com/user/repo/branch/file
     * 2. github.com/user/repo/raw/branch/file
     * 3. raw.github.com/user/repo/branch/file
     *
     * @param url 要检查的 URL
     * @return 是否是 GitHub 链接
     */
    private boolean isGitHubUrl(String url) {
        if (url == null) return false;
        return url.contains("raw.githubusercontent.com")
                || url.contains("github.com/") && url.contains("/raw/")
                || url.contains("raw.github.com");
    }
    // ====================================================================
    // ✅ 加速源 1：jsDelivr CDN（推荐）
    // ====================================================================
    /**
     * 转换成 jsDelivr CDN 地址
     *
     * 【格式说明】
     * GitHub raw: https://raw.githubusercontent.com/user/repo/branch/file
     * jsDelivr:   https://cdn.jsdelivr.net/gh/user/repo@branch/file
     *
     * 【优点】
     * - 全球 CDN，速度快
     * - 国内也有节点，访问速度不错
     * - 支持缓存，加载更快
     *
     * @param githubUrl GitHub 原始地址
     * @return jsDelivr 加速地址
     */
    private String convertToJsdelivr(String githubUrl) {
        try {
            // 解析 GitHub 链接的各个部分
            GitHubUrlInfo info = parseGitHubUrl(githubUrl);
            if (info == null) {
                return githubUrl;  // 解析失败，返回原地址
            }
            // 组装 jsDelivr 地址
            // 格式：https://cdn.jsdelivr.net/gh/user/repo@branch/path
            StringBuilder sb = new StringBuilder();
            sb.append("https://cdn.jsdelivr.net/gh/");
            sb.append(info.user);
            sb.append("/");
            sb.append(info.repo);
            if (info.branch != null && !info.branch.isEmpty()) {
                sb.append("@");
                sb.append(info.branch);
            }
            sb.append("/");
            sb.append(info.path);
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return githubUrl;  // 转换失败，返回原地址
        }
    }
    // ====================================================================
    // ✅ 加速源 2：ghproxy（GitHub 反向代理）
    // ====================================================================
    /**
     * 转换成 ghproxy 地址
     *
     * 【格式说明】
     * 直接在原 URL 前面加上 https://ghproxy.com/
     *
     * 【优点】
     * - 支持所有 GitHub 链接
     * - 不需要转换格式
     *
     * @param githubUrl GitHub 原始地址
     * @return ghproxy 加速地址
     */
    private String convertToGhproxy(String githubUrl) {
        try {
            return "https://ghproxy.com/" + githubUrl;
        } catch (Exception e) {
            e.printStackTrace();
            return githubUrl;
        }
    }
    // ====================================================================
    // ✅ 加速源 3：gitmirror（GitHub 镜像站）
    // ====================================================================
    /**
     * 转换成 gitmirror 镜像地址
     *
     * 【格式说明】
     * raw.githubusercontent.com → raw.gitmirror.com
     *
     * 【优点】
     * - 国内镜像站，速度快
     * - 格式简单，只换域名
     *
     * @param githubUrl GitHub 原始地址
     * @return gitmirror 加速地址
     */
    private String convertToGitmirror(String githubUrl) {
        try {
            // 替换域名
            return githubUrl.replace("raw.githubusercontent.com", "raw.gitmirror.com")
                    .replace("raw.github.com", "raw.gitmirror.com");
        } catch (Exception e) {
            e.printStackTrace();
            return githubUrl;
        }
    }
    // ====================================================================
    // ✅ GitHub URL 解析工具
    // ====================================================================
    /**
     * GitHub URL 信息
     */
    private static class GitHubUrlInfo {
        String user;      // 用户名
        String repo;      // 仓库名
        String branch;    // 分支名
        String path;      // 文件路径
    }
    /**
     * 解析 GitHub URL
     *
     * 支持的格式：
     * 1. https://raw.githubusercontent.com/user/repo/branch/path/to/file
     * 2. https://github.com/user/repo/raw/branch/path/to/file
     * 3. https://raw.github.com/user/repo/branch/path/to/file
     *
     * @param url GitHub URL
     * @return 解析后的信息，解析失败返回 null
     */
    private GitHubUrlInfo parseGitHubUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            GitHubUrlInfo info = new GitHubUrlInfo();
            // 去掉 https:// 或 http:// 前缀
            String cleanUrl = url;
            if (cleanUrl.startsWith("https://")) {
                cleanUrl = cleanUrl.substring(8);
            } else if (cleanUrl.startsWith("http://")) {
                cleanUrl = cleanUrl.substring(7);
            }
            // 格式 1：raw.githubusercontent.com/user/repo/branch/path
            if (cleanUrl.startsWith("raw.githubusercontent.com/")) {
                String pathPart = cleanUrl.substring("raw.githubusercontent.com/".length());
                String[] parts = pathPart.split("/", 4);
                if (parts.length >= 4) {
                    info.user = parts[0];
                    info.repo = parts[1];
                    info.branch = parts[2];
                    info.path = parts[3];
                    return info;
                }
            }
            // 格式 2：github.com/user/repo/raw/branch/path
            if (cleanUrl.startsWith("github.com/") && cleanUrl.contains("/raw/")) {
                // 用正则提取
                Pattern pattern = Pattern.compile("github\\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)");
                Matcher matcher = pattern.matcher(cleanUrl);
                if (matcher.find()) {
                    info.user = matcher.group(1);
                    info.repo = matcher.group(2);
                    info.branch = matcher.group(3);
                    info.path = matcher.group(4);
                    return info;
                }
            }
            // 格式 3：raw.github.com/user/repo/branch/path
            if (cleanUrl.startsWith("raw.github.com/")) {
                String pathPart = cleanUrl.substring("raw.github.com/".length());
                String[] parts = pathPart.split("/", 4);
                if (parts.length >= 4) {
                    info.user = parts[0];
                    info.repo = parts[1];
                    info.branch = parts[2];
                    info.path = parts[3];
                    return info;
                }
            }
            // 都没匹配上
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    // ====================================================================
    // 下载原始内容【统一走 NetUtil，UA / Referer / Origin 一致】
    // ====================================================================
    /**
     * 下载原始M3U文本内容
     * 🟢 优化：使用自动跟随重定向的client，避免手动重定向循环每次新建TCP连接
     */
    private String downloadRawContent(String urlStr) {
        try {
            Response response = NetUtil.getInstance().syncGet(urlStr);
            try (Response resp = response) {
                int responseCode = resp.code();
                if (responseCode != 200 || resp.body() == null) {
                    Log.e(TAG, "下载失败 code=" + responseCode + " url=" + urlStr);
                    return null;
                }
                return resp.body().string();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ====================================================================
    // ✅ 本地永久兜底（filesDir 内，**不随24h缓存过期**）
    //    - 每次网络加载成功且频道数正常 → 覆盖写入
    //    - 所有网络源失败 → 读取兜底继续播放
    // ====================================================================
    private File getFallbackFile() {
        return new File(context.getFilesDir(), FALLBACK_FILE);
    }

    /** 写入永久兜底文件（只有网络成功且频道数足够时才调用） */
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

    /** 读取永久兜底文件（不校验过期，永久有效） */
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
