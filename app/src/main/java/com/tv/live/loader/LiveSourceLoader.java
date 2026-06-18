package com.tv.live.loader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.tv.live.CacheManager;
import com.tv.live.Channel;
import com.tv.live.PlaylistParser;
import com.tv.live.SettingsActivity;
import com.tv.live.UrlConfig;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * ✅ 直播源加载器（带缓存）
 *
 * 【缓存策略】
 * 1. 加载成功后，自动保存原始M3U文本到本地缓存
 * 2. 缓存有效期24小时
 * 3. MainActivity 里先读缓存快速显示，再后台刷新最新数据
 */
public class LiveSourceLoader {
    private static LiveSourceLoader instance;
    private final Context context;
    private final Handler mainHandler;
    // 缓存管理器
    private final CacheManager cacheManager;

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
     */
    public void load(LoadCallback callback) {
        new Thread(() -> {
            try {
                // 下载原始M3U文本，用于保存缓存
                String rawContent = downloadRawContent(UrlConfig.LIVE_URL);

                if (rawContent != null && !rawContent.isEmpty()) {
                    // 保存到缓存
                    cacheManager.saveFileCache("live_source", rawContent);
                    SettingsActivity.log("【直播源】缓存已保存，大小：" + rawContent.length() + " 字节");
                }

                // 解析直播源（还是用 PlaylistParser 正式解析）
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
                is = new GZIPInputStream(is);
            }
            // URL以.gz结尾也需要解压
            if (urlStr.endsWith(".gz")) {
                is = new GZIPInputStream(is);
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
