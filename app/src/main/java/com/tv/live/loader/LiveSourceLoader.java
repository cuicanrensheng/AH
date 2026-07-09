package com.tv.live.loader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tv.live.Channel;
import com.tv.live.PlaylistParser;
import com.tv.live.UrlConfig;
import com.tv.live.util.CacheManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

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
    private static LiveSourceLoader instance;
    private final Context context;
    private final Handler mainHandler;
    // 缓存管理器
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
     * 加载直播源（从网络）
     * 加载成功后自动保存到缓存
     *
     * 【智能加速】
     * 如果是 GitHub 链接，自动走 CDN 加速。
     */
    public void load(LoadCallback callback) {
        new Thread(() -> {
            try {
                String originalUrl = UrlConfig.LIVE_URL;
                // ====================================================================
                // ✅ GitHub 智能加速
                // ====================================================================
                String acceleratedUrl = getAcceleratedUrl(originalUrl);
                if (!originalUrl.equals(acceleratedUrl)) {
                    // 🟢【已移除】SettingsActivity.log("【直播源加速】检测到 GitHub 链接，已自动加速");
                    // 🟢【已移除】SettingsActivity.log("【直播源加速】原地址：" + originalUrl);
                    // 🟢【已移除】SettingsActivity.log("【直播源加速】加速地址：" + acceleratedUrl);
                }
                // 下载原始M3U文本，用于保存缓存（用加速后的地址下载）
                String rawContent = downloadRawContent(acceleratedUrl);
                if (rawContent != null && !rawContent.isEmpty()) {
                    // 保存到缓存
                    cacheManager.saveFileCache("live_source", rawContent);
                    // 🟢【已移除】SettingsActivity.log("【直播源】缓存已保存，大小：" + rawContent.length() + " 字节");
                }
                // 解析直播源（用加速后的地址解析）
                List<Channel> channels = PlaylistParser.parse(acceleratedUrl);
                mainHandler.post(() -> callback.onSuccess(channels));
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
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
    // 下载原始内容【完整修复重定向】
    // ====================================================================
    /**
     * 下载原始M3U文本内容
     * 修复：完整3xx重定向、相对路径、最大跳转、UA、日志、GZIP兼容
     */
    private String downloadRawContent(String urlStr) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        String currentUrl = urlStr;
        final int MAX_REDIRECT = 5;
        int redirectCount = 0;
        try {
            while (redirectCount <= MAX_REDIRECT) {
                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LiveTV M3U Downloader");
                conn.connect();
                int responseCode = conn.getResponseCode();
                // 处理3xx重定向
                if (responseCode >= 300 && responseCode < 400) {
                    redirectCount++;
                    String location = conn.getHeaderField("Location");
                    // 🟢【已移除】SettingsActivity.log("【直播源下载重定向】第" + redirectCount + "次跳转，原地址：" + currentUrl + " -> Location：" + location);
                    if (location == null || location.isEmpty()) {
                        // 🟢【已移除】SettingsActivity.log("【直播源下载】重定向Location为空，终止下载");
                        return null;
                    }
                    // 拼接相对路径
                    if (!location.startsWith("http")) {
                        URL baseUrl = new URL(currentUrl);
                        currentUrl = new URL(baseUrl, location).toString();
                    } else {
                        currentUrl = location;
                    }
                    conn.disconnect();
                    conn = null;
                    if (redirectCount >= MAX_REDIRECT) {
                        // 🟢【已移除】SettingsActivity.log("【直播源下载】重定向已达最大次数" + MAX_REDIRECT + "，下载失败");
                        return null;
                    }
                    continue;
                }
                // 非200直接失败
                if (responseCode != 200) {
                    // 🟢【已移除】SettingsActivity.log("【直播源下载】响应码非200：" + responseCode + " url=" + currentUrl);
                    return null;
                }
                // 读取流
                InputStream is = conn.getInputStream();
                String encoding = conn.getContentEncoding();
                if ((encoding != null && encoding.equalsIgnoreCase("gzip")) || currentUrl.endsWith(".gz")) {
                    is = new GZIPInputStream(is);
                }
                reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            // 🟢【已移除】SettingsActivity.log("【直播源下载】下载异常：" + e.getMessage());
            return null;
        } finally {
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }
}
