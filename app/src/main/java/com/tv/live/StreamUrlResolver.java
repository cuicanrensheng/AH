package com.tv.live;

import android.util.Log;
import com.tv.live.util.NetUtil;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

public class StreamUrlResolver {
    private static final String TAG = "StreamResolver";

    // 正则预编译，提高解析效率
    private static final Pattern M3U8_PATTERN = Pattern.compile("(https?://[^\\s\"<>]+\\.m3u8)");

    /**
     * 解析流地址
     * ⚠️ 注意：此方法涉及网络请求，必须从后台线程调用，严禁在主线程执行！
     */
    public static String resolve(String url) {
        if (url == null || url.isEmpty()) return url;
        // 已经是直链，不需要解析
        if (url.endsWith(".m3u8") || url.endsWith(".ts") || url.endsWith(".mp4")) return url;
        // 识别需要处理的动态接口
        if (url.contains(".php") || url.contains("?id=") || url.contains(".asp")) {
            return parse(url);
        }
        return url;
    }

    private static String parse(String url) {
        try (Response response = NetUtil.getInstance().syncGetNoRedirect(url)) {
            int code = response.code();
            if (code == 301 || code == 302) {
                String loc = response.header("Location");
                if (loc != null && loc.startsWith("http")) {
                    Log.d(TAG, "跳转解析成功: " + loc);
                    return loc;
                }
            }
            if (!response.isSuccessful() || response.body() == null) return url;

            // 🟢 核心优化：流式读取，逐行正则匹配，避免大文件 OOM
            BufferedReader br = new BufferedReader(new InputStreamReader(response.body().byteStream()));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    Matcher m = M3U8_PATTERN.matcher(line);
                    if (m.find()) {
                        String real = m.group(1);
                        Log.d(TAG, "流式匹配解析成功: " + real);
                        return real;
                    }
                }
            } finally {
                try { br.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "解析失败", e);
        }
        return url;
    }
}
