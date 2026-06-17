package com.tv.live;

import android.net.Uri;
import android.util.Log;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ✅ 带重定向日志的Http数据源
 *
 * 【功能特点】
 * 1. 手动处理HTTP重定向，每一次重定向都打印详细日志
 * 2. 支持301/302/303/307/308等所有重定向状态码
 * 3. 自动处理相对路径的Location
 * 4. 支持跨协议重定向（http→https）
 * 5. Header全程生效，每一层重定向都带上
 * 6. 日志同步输出到Logcat和SettingsActivity
 *
 * 【稳定版说明】
 * Header保持简洁（和DefaultHttpDataSource版本一致），保证播放稳定
 */
public class RedirectLoggingHttpDataSource extends BaseDataSource implements HttpDataSource {
    private static final String TAG = "RedirectLog";
    // 最大重定向次数（防止死循环）
    private static final int MAX_REDIRECTS = 20;
    // 连接超时
    private final int connectTimeoutMs;
    // 读取超时
    private final int readTimeoutMs;
    // 默认请求Header
    private final Map<String, String> defaultRequestProperties;
    // User-Agent
    private final String userAgent;

    // HTTP连接
    private HttpURLConnection connection;
    // 输入流
    private InputStream inputStream;
    // 是否已打开
    private boolean opened;
    // 剩余字节数
    private long bytesRemaining;
    // 当前URI
    private Uri uri;
    // 响应Header
    private Map<String, List<String>> responseHeaders;
    // 响应状态码
    private int responseCode = 0;

    /**
     * 构造函数
     */
    protected RedirectLoggingHttpDataSource(
            String userAgent,
            int connectTimeoutMs,
            int readTimeoutMs,
            Map<String, String> defaultRequestProperties) {
        super(true); // isNetwork = true
        this.userAgent = userAgent;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.defaultRequestProperties = defaultRequestProperties != null
                ? new HashMap<>(defaultRequestProperties)
                : new HashMap<String, String>();
    }

    /**
     * 打开数据源
     * 核心方法：手动处理重定向，每一次重定向都打日志
     */
    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        transferInitializing(dataSpec);
        this.uri = dataSpec.uri;
        String currentUrl = uri.toString();
        int redirectCount = 0;

        log("========================================");
        log("【HTTP】开始请求");
        log("【HTTP】初始地址：" + currentUrl);
        log("========================================");

        try {
            // ===== 手动处理重定向循环 =====
            while (redirectCount < MAX_REDIRECTS) {
                URL url = new URL(currentUrl);
                connection = (HttpURLConnection) url.openConnection();

                // 设置超时
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false); // 手动处理重定向
                connection.setDoOutput(false);

                // 设置默认Header
                for (Map.Entry<String, String> entry : defaultRequestProperties.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }

                // 设置User-Agent（如果Header里没有的话）
                if (userAgent != null && !defaultRequestProperties.containsKey("User-Agent")) {
                    connection.setRequestProperty("User-Agent", userAgent);
                }

                // 设置Range（断点续传）
                if (dataSpec.position != 0) {
                    connection.setRequestProperty("Range", "bytes=" + dataSpec.position + "-");
                }

                // 发送请求，获取响应码
                responseCode = connection.getResponseCode();
                log("【HTTP】第" + (redirectCount + 1) + "次请求  状态码：" + responseCode);
                log("【HTTP】地址：" + currentUrl);

                // ===== 判断是否为重定向 =====
                if (responseCode == 301 || responseCode == 302 || responseCode == 303
                        || responseCode == 307 || responseCode == 308) {

                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        log("【HTTP】⚠️ 重定向Location为空，停止跟随");
                        break;
                    }

                    // 处理相对路径（以/开头）
                    if (location.startsWith("/")) {
                        String baseUrl = url.getProtocol() + "://" + url.getHost();
                        int port = url.getPort();
                        if (port != -1 && port != url.getDefaultPort()) {
                            baseUrl += ":" + port;
                        }
                        location = baseUrl + location;
                    }

                    redirectCount++;
                    log("【HTTP】🔄 第" + redirectCount + "次重定向");
                    log("【HTTP】   → " + location);

                    // 准备下一次请求
                    currentUrl = location;
                    connection.disconnect();
                    connection = null;
                    continue;
                }

                // ===== 正常响应（2xx） =====
                if (responseCode >= 200 && responseCode < 300) {
                    log("========================================");
                    log("【HTTP】✅ 请求成功");
                    log("【HTTP】最终地址：" + currentUrl);
                    log("【HTTP】总共重定向：" + redirectCount + "次");
                    log("========================================");
                    break;
                }

                // ===== 错误响应 =====
                log("【HTTP】❌ 请求失败，状态码：" + responseCode);
                throw new HttpDataSourceException(
                        "HTTP " + responseCode,
                        dataSpec,
                        HttpDataSourceException.TYPE_OPEN);
            }

            // 达到最大重定向次数
            if (redirectCount >= MAX_REDIRECTS) {
                log("【HTTP】⚠️ 达到最大重定向次数(" + MAX_REDIRECTS + ")，停止跟随");
            }

            // ===== 获取输入流 =====
            try {
                inputStream = connection.getInputStream();
            } catch (IOException e) {
                // 出错时尝试读取错误流
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    try { errorStream.close(); } catch (IOException ignored) {}
                }
                throw new HttpDataSourceException(
                        e,
                        dataSpec,
                        HttpDataSourceException.TYPE_OPEN);
            }

            // 保存响应Header
            responseHeaders = connection.getHeaderFields();

            // 获取内容长度
            long contentLength = getContentLength(connection);
            if (dataSpec.position != 0 && contentLength != C.LENGTH_UNSET) {
                bytesRemaining = contentLength - dataSpec.position;
            } else {
                bytesRemaining = contentLength;
            }

            // 标记为已打开
            opened = true;
            transferStarted(dataSpec);

            return bytesRemaining;

        } catch (IOException e) {
            throw new HttpDataSourceException(
                    e,
                    dataSpec,
                    HttpDataSourceException.TYPE_OPEN);
        }
    }

    /**
     * 从响应头中获取内容长度
     */
    private long getContentLength(HttpURLConnection connection) {
        String contentLength = connection.getHeaderField("Content-Length");
        if (contentLength != null) {
            try {
                return Long.parseLong(contentLength);
            } catch (NumberFormatException e) {
                // 解析失败，返回未知长度
            }
        }
        return C.LENGTH_UNSET;
    }

    /**
     * 读取数据
     */
    @Override
    public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }

        int bytesRead;
        try {
            bytesRead = inputStream.read(buffer, offset, length);
        } catch (IOException e) {
            throw new HttpDataSourceException(
                    e,
                    new DataSpec(uri),
                    HttpDataSourceException.TYPE_READ);
        }

        if (bytesRead == -1) {
            // 读取结束
            if (bytesRemaining != C.LENGTH_UNSET && bytesRemaining != 0) {
                throw new HttpDataSourceException(
                        new EOFException(),
                        new DataSpec(uri),
                        HttpDataSourceException.TYPE_READ);
            }
            return C.RESULT_END_OF_INPUT;
        }

        if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= bytesRead;
        }
        bytesTransferred(bytesRead);

        return bytesRead;
    }

    /**
     * 获取当前URI
     */
    @Override
    public Uri getUri() {
        return uri;
    }

    /**
     * ✅ 获取响应状态码（实现抽象方法）
     */
    @Override
    public int getResponseCode() {
        return responseCode;
    }

    /**
     * 获取响应头
     */
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return responseHeaders;
    }

    /**
     * 设置请求属性
     */
    @Override
    public void setRequestProperty(String name, String value) {
        defaultRequestProperties.put(name, value);
    }

    /**
     * 清除请求属性
     */
    @Override
    public void clearRequestProperty(String name) {
        defaultRequestProperties.remove(name);
    }

    /**
     * 清除所有请求属性
     */
    @Override
    public void clearAllRequestProperties() {
        defaultRequestProperties.clear();
    }

    /**
     * 关闭数据源
     */
    @Override
    public void close() throws HttpDataSourceException {
        if (opened) {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                throw new HttpDataSourceException(
                        e,
                        new DataSpec(uri),
                        HttpDataSourceException.TYPE_CLOSE);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                    connection = null;
                }
                opened = false;
                transferEnded();
            }
        }
    }

    /**
     * 打印日志
     * 同时输出到Logcat和SettingsActivity的日志系统
     */
    private void log(String msg) {
        Log.d(TAG, msg);
        // 同步到SettingsActivity日志
        try {
            Class<?> settingsClass = Class.forName("com.tv.live.SettingsActivity");
            java.lang.reflect.Method logMethod = settingsClass.getMethod("log", String.class);
            logMethod.invoke(null, msg);
        } catch (Exception e) {
            // 忽略反射失败
        }
    }

    // ================================================
    // Factory 工厂类
    // ================================================

    /**
     * Factory for RedirectLoggingHttpDataSource
     */
    public static final class Factory implements HttpDataSource.Factory {
        private String userAgent;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 10000;
        private Map<String, String> defaultRequestProperties;

        public Factory() {
        }

        public Factory setUserAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Factory setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Factory setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        public Factory setDefaultRequestProperties(Map<String, String> defaultRequestProperties) {
            this.defaultRequestProperties = defaultRequestProperties;
            return this;
        }

        public Factory setAllowCrossProtocolRedirects(boolean allow) {
            // 本实现默认就支持跨协议重定向
            return this;
        }

        @Override
        public RedirectLoggingHttpDataSource createDataSource() {
            return new RedirectLoggingHttpDataSource(
                    userAgent,
                    connectTimeoutMs,
                    readTimeoutMs,
                    defaultRequestProperties);
        }
    }
}
