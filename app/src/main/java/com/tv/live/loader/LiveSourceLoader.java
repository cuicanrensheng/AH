package com.tv.live.loader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.tv.live.CacheManager;
import com.tv.live.Channel;
import com.tv.live.PlaylistParser;
import com.tv.live.UrlConfig;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * ✅ 直播源加载器（带缓存）
 *
 * 【缓存策略】
 * 1. 加载成功后，自动保存原始M3U文本到本地缓存
 * 2. 缓存有效期24小时，过期自动失效
 * 3. MainActivity 里先读缓存快速显示，再后台刷新最新数据
 *
 * 【说明】
 * 缓存的保存在这里做，缓存的读取在 MainActivity 里做
 * 这样职责分离，Loader 只负责加载和保存
 */
public class LiveSourceLoader {
    private static LiveSourceLoader instance;
    private final Context context;
    private final Handler mainHandler;
    // 缓存管理器
    private CacheManager cacheManager;

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

    /**
     * 加载直播源（从网络）
     * 加载成功后自动保存到缓存
     *
     * @param callback 加载回调
     */
    public void load(LoadCallback callback) {
        new Thread(() -> {
            try {
                // ===== 第一步：从网络下载原始M3U文本 =====
                String rawContent = downloadRawContent(UrlConfig.LIVE_URL);

                if (rawContent != null && !rawContent.isEmpty()) {
                    // 保存到缓存
                    cacheManager.saveFileCache("live_source", rawContent);
                    // 用日志记录
                    try {
                        Class<?> settingsClass = Class.forName("com.tv.live.SettingsActivity");
                        java.lang.reflect.Method logMethod = settingsClass.getMethod("log", String.class);
                        logMethod.invoke(null, "【直播源】缓存已保存，大小：" + rawContent.length() + " 字节");
                    } catch (Exception ignored) {}
                }

                // ===== 第二步：解析直播源 =====
                // 注意：这里还是用 PlaylistParser.parse 从URL解析
                // 如果 PlaylistParser 支持从字符串解析，可以直接用 rawContent，避免重复下载
                List<Channel> channels = PlaylistParser.parse(UrlConfig.LIVE_URL);

                mainHandler.post(() -> callback.onSuccess(channels));
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    /**
     * 下载原始M3U文本内容
     * 用于保存缓存
     *
     * @param urlStr 直播源地址
     * @return 原始文本内容
     */
    private String downloadRawContent(String urlStr) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return null;
            }

            InputStream is = conn.getInputStream();
            // 处理GZIP压缩
            String encoding = conn.getContentEncoding();
            if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
                is = new java.util.zip.GZIPInputStream(is);
            }

            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }
}
