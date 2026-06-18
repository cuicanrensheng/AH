package com.tv.live;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 带重定向日志的 HTTP 数据源
 *
 * 【功能】
 * 1. 手动处理 HTTP 重定向（301/302/303/307/308）
 * 2. 每一次重定向都打印详细日志（状态码、原始URL、目标URL、Header）
 * 3. 支持跨协议重定向（HTTP ↔ HTTPS）
 * 4. 支持相对路径的 Location
 * 5. 日志同时输出到 Logcat 和 SettingsActivity.PLAY_LOG
 *
 * 【为什么不用 DefaultHttpDataSource？】
 * DefaultHttpDataSource 虽然也能自动跟随重定向，
 * 但是不会打印每一次重定向的详细信息，不利于调试直播源。
 * 这个类手动处理重定向，每一重都打日志，方便排查问题。
 *
 * 【最大重定向次数】
 * 默认 20 次，防止无限重定向死循环。
 */
public class RedirectLoggingHttpDataSource extends BaseDataSource implements HttpDataSource {

    private static final String TAG = "RedirectHttp";
    /** 最大重定向次数，防止无限循环 */
    private static final int MAX_REDIRECTS = 20;
    /** 连接超时时间（毫秒） */
    private static final int CONNECT_TIMEOUT = 5000;
    /** 读取超时时间（毫秒） */
    private static final int READ_TIMEOUT = 15000;

    /** 默认请求头 */
    private final Map<String, String> defaultRequestProperties;
    /** 是否允许跨协议重定向 */
    private final boolean allowCrossProtocolRedirects;

    /** 当前 HTTP 连接 */
    private HttpURLConnection connection;
    /** 输入流 */
    private InputStream inputStream;
    /** 是否已经打开 */
    private boolean opened;

    /** 当前请求的字节数 */
    private long bytesToRead;
    /** 已读取的字节数 */
    private long bytesRead;

    /**
     * 构造函数
     *
     * @param defaultRequestProperties 默认请求头
     * @param allowCrossProtocolRedirects 是否允许跨协议重定向
     */
    protected RedirectLoggingHttpDataSource(
            Map<String, String> defaultRequestProperties,
            boolean allowCrossProtocolRedirects) {
        super(true);
        this.defaultRequestProperties = defaultRequestProperties != null
                ? new HashMap<>(defaultRequestProperties)
                : new HashMap<>();
        this.allowCrossProtocolRedirects = allowCrossProtocolRedirects;
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        try {
            transferInitializing(dataSpec);

            // ===== 打开连接（手动处理重定向） =====
            connection = openConnection(dataSpec);
            int responseCode = connection.getResponseCode();

            // ===== 获取响应头 =====
            Map<String, List<String>> headers = connection.getHeaderFields();

            // ===== 处理错误响应 =====
            if (responseCode < 200 || responseCode > 299) {
                // 读取错误信息
                String responseMessage = connection.getResponseMessage();
                SettingsActivity.log("❌ HTTP 请求失败：" + responseCode + " " + responseMessage);
                SettingsActivity.log("   URL：" + dataSpec.uri);
                throw new HttpDataSourceException(
                        "HTTP " + responseCode + " " + responseMessage,
                        dataSpec,
                        HttpDataSourceException.TYPE_RESPONSE_CODE_UNSUPPORTED);
            }

            // ===== 获取输入流 =====
            try {
                inputStream = connection.getInputStream();
                // 处理 GZIP 压缩
                String contentEncoding = connection.getContentEncoding();
                if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
                    inputStream = new GZIPInputStream(inputStream);
                    SettingsActivity.log("📦 响应已 GZIP 压缩，已自动解压");
                }
            } catch (IOException e) {
                // 如果获取输入流失败，尝试用错误流
                inputStream = connection.getErrorStream();
            }

            // ===== 计算要读取的字节数 =====
            long contentLength = getContentLength(connection);
            if (dataSpec.position != C.POSITION_UNSET) {
                // 有 Range 请求
                bytesToRead = dataSpec.length != C.LENGTH_UNSET
                        ? dataSpec.length
                        : (contentLength != C.LENGTH_UNSET ? contentLength - dataSpec.position : C.LENGTH_UNSET);
            } else {
                bytesToRead = dataSpec.length != C.LENGTH_UNSET
                        ? dataSpec.length
                        : contentLength;
            }
            bytesRead = 0;

            // ===== 打印最终响应信息 =====
            SettingsActivity.log("✅ 最终响应：HTTP " + responseCode);
            SettingsActivity.log("   Content-Length：" + (contentLength == C.LENGTH_UNSET ? "未知" : contentLength + " 字节"));
            String contentType = connection.getContentType();
            if (!TextUtils.isEmpty(contentType)) {
                SettingsActivity.log("   Content-Type：" + contentType);
            }
            SettingsActivity.log("   最终地址：" + connection.getURL());

            opened = true;
            transferStarted(dataSpec);

            return bytesToRead;

        } catch (IOException e) {
            closeConnectionQuietly();
            throw new HttpDataSourceException(e, dataSpec, HttpDataSourceException.TYPE_OPEN);
        }
    }

    /**
     * 打开连接，手动处理重定向
     *
     * 【重定向处理流程】
     * 1. 发起请求
     * 2. 检查状态码，如果是 3xx 就处理重定向
     * 3. 打印每一重的日志
     * 4. 最多重定向 20 次
     *
     * @param dataSpec 请求参数
     * @return 最终的 HttpURLConnection
     */
    private HttpURLConnection openConnection(DataSpec dataSpec) throws IOException {
        String currentUrl = dataSpec.uri.toString();
        int redirectCount = 0;

        // ===== 打印初始请求 =====
        SettingsActivity.log("========== HTTP 请求开始 ==========");
        SettingsActivity.log("🌐 原始地址：" + currentUrl);
        SettingsActivity.log("   方法：" + (dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST ? "POST" : "GET"));

        while (true) {
            // ===== 检查重定向次数 =====
            if (redirectCount > MAX_REDIRECTS) {
                SettingsActivity.log("❌ 重定向次数超过限制（" + MAX_REDIRECTS + "次），可能是无限重定向");
                throw new IOException("Too many redirects");
            }

            // ===== 创建连接 =====
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoInput(true);
            conn.setInstanceFollowRedirects(false);  // 关键：不自动跟随重定向，我们手动处理
            conn.setUseCaches(false);

            // ===== 设置请求头 =====
            for (Map.Entry<String, String> entry : defaultRequestProperties.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }

            // ===== 设置 Range（如果有） =====
            if (dataSpec.position != C.POSITION_UNSET) {
                String rangeValue = "bytes=" + dataSpec.position + "-";
                if (dataSpec.length != C.LENGTH_UNSET) {
                    rangeValue += (dataSpec.position + dataSpec.length - 1);
                }
                conn.setRequestProperty("Range", rangeValue);
                SettingsActivity.log("   Range：" + rangeValue);
            }

            // ===== 发起请求 =====
            int responseCode = conn.getResponseCode();
            String responseMessage = conn.getResponseMessage();

            // ===== 判断是否是重定向 =====
            boolean isRedirect = (responseCode == 301 || responseCode == 302
                    || responseCode == 303 || responseCode == 307 || responseCode == 308);

            if (!isRedirect) {
                // 不是重定向，返回这个连接
                if (redirectCount > 0) {
                    SettingsActivity.log("   └─ 重定向结束，共 " + redirectCount + " 重");
                }
                return conn;
            }

            // ===== 处理重定向 =====
            redirectCount++;
            String location = conn.getHeaderField("Location");

            if (TextUtils.isEmpty(location)) {
                SettingsActivity.log("❌ 第 " + redirectCount + " 重：HTTP " + responseCode
                        + "，但没有 Location 头");
                conn.disconnect();
                throw new IOException("Redirect with no Location header");
            }

            // ===== 处理相对路径 =====
            String redirectUrl = resolveRedirectUrl(currentUrl, location);

            // ===== 检查跨协议 =====
            boolean isCrossProtocol = !url.getProtocol().equalsIgnoreCase(
                    Uri.parse(redirectUrl).getScheme());
            if (isCrossProtocol && !allowCrossProtocolRedirects) {
                SettingsActivity.log("❌ 第 " + redirectCount + " 重：跨协议重定向被禁止");
                SettingsActivity.log("   " + url.getProtocol() + " → " + Uri.parse(redirectUrl).getScheme());
                conn.disconnect();
                throw new IOException("Cross-protocol redirect not allowed");
            }

            // ===== 打印这一重的日志 =====
            String crossProtocolTag = isCrossProtocol ? "  [跨协议]" : "";
            SettingsActivity.log("🔄 第 " + redirectCount + " 重：HTTP " + responseCode
                    + " " + responseMessage + crossProtocolTag);
            SettingsActivity.log("   从：" + currentUrl);
            SettingsActivity.log("   到：" + redirectUrl);

            // ===== 打印关键响应头 =====
            printRedirectHeaders(conn);

            // ===== 关闭当前连接，准备下一次请求 =====
            conn.disconnect();
            currentUrl = redirectUrl;
        }
    }

    /**
     * 解析重定向地址（处理相对路径）
     *
     * @param baseUrl 基础 URL
     * @param location Location 头的值（可能是相对路径）
     * @return 完整的重定向 URL
     */
    private String resolveRedirectUrl(String baseUrl, String location) throws IOException {
        // 如果 location 已经是完整 URL，直接返回
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }

        // 相对路径，需要拼接
        Uri baseUri = Uri.parse(baseUrl);
        String scheme = baseUri.getScheme();
        String host = baseUri.getHost();
        int port = baseUri.getPort();
        String path = baseUri.getPath();

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port != -1 && port != 80 && port != 443) {
            sb.append(":").append(port);
        }

        if (location.startsWith("/")) {
            // 绝对路径（相对于域名）
            sb.append(location);
        } else {
            // 相对路径（相对于当前路径）
            if (path != null && path.contains("/")) {
                String parentPath = path.substring(0, path.lastIndexOf('/') + 1);
                sb.append(parentPath).append(location);
            } else {
                sb.append("/").append(location);
            }
        }

        return sb.toString();
    }

    /**
     * 打印重定向时的关键响应头
     */
    private void printRedirectHeaders(HttpURLConnection conn) {
        // 只打印关键的几个头，避免日志太多
        String[] importantHeaders = {
                "Location",
                "Set-Cookie",
                "Content-Type",
                "Cache-Control",
                "Server"
        };

        StringBuilder sb = new StringBuilder();
        sb.append("   Headers：");
        boolean hasHeader = false;
        for (String header : importantHeaders) {
            String value = conn.getHeaderField(header);
            if (!TextUtils.isEmpty(value)) {
                if (hasHeader) sb.append(" | ");
                sb.append(header).append("=");
                // Set-Cookie 太长，截断显示
                if ("Set-Cookie".equals(header) && value.length() > 80) {
                    sb.append(value.substring(0, 80)).append("...");
                } else {
                    sb.append(value);
                }
                hasHeader = true;
            }
        }
        if (hasHeader) {
            SettingsActivity.log(sb.toString());
        }
    }

    /**
     * 获取 Content-Length
     */
    private long getContentLength(HttpURLConnection connection) {
        String contentLength = connection.getHeaderField("Content-Length");
        if (!TextUtils.isEmpty(contentLength)) {
            try {
                return Long.parseLong(contentLength);
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws HttpDataSourceException {
        if (readLength == 0) {
            return 0;
        }
        if (bytesToRead == 0) {
            return C.RESULT_END_OF_INPUT;
        }

        try {
            int bytesToReadThisTime = (int) Math.min(
                    readLength,
                    bytesToRead == C.LENGTH_UNSET ? Integer.MAX_VALUE : bytesToRead - bytesRead);
            int bytesReadThisTime = inputStream.read(buffer, offset, bytesToReadThisTime);

            if (bytesReadThisTime == -1) {
                // 读取结束
                if (bytesToRead != C.LENGTH_UNSET && bytesRead != bytesToRead) {
                    // 读取的字节数和预期不符
                    throw new HttpDataSourceException(
                            "Unexpected end of input",
                            new DataSpec(Uri.parse(connection.getURL().toString())),
                            HttpDataSourceException.TYPE_READ);
                }
                return C.RESULT_END_OF_INPUT;
            }

            bytesRead += bytesReadThisTime;
            bytesTransferred(bytesReadThisTime);
            return bytesReadThisTime;

        } catch (IOException e) {
            throw new HttpDataSourceException(e,
                    new DataSpec(Uri.parse(connection.getURL().toString())),
                    HttpDataSourceException.TYPE_READ);
        }
    }

    @Override
    public Uri getUri() {
        return connection == null ? null : Uri.parse(connection.getURL().toString());
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return connection == null ? null : connection.getHeaderFields();
    }

    @Override
    public void setRequestProperty(String name, String value) {
        defaultRequestProperties.put(name, value);
    }

    @Override
    public void clearRequestProperty(String name) {
        defaultRequestProperties.remove(name);
    }

    @Override
    public void clearAllRequestProperties() {
        defaultRequestProperties.clear();
    }

    @Override
    public void close() throws HttpDataSourceException {
        if (opened) {
            opened = false;
            transferEnded();
            closeConnectionQuietly();
        }
    }

    /**
     * 安静地关闭连接，不抛出异常
     */
    private void closeConnectionQuietly() {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                // 忽略
            }
            inputStream = null;
        }
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }

    // ====================================================================
    // Factory 工厂类
    // ====================================================================

    /**
     * 工厂类，用于创建 RedirectLoggingHttpDataSource 实例
     *
     * 【用法】
     * new RedirectLoggingHttpDataSource.Factory()
     *     .setDefaultRequestProperties(headers)
     *     .setAllowCrossProtocolRedirects(true)
     *     .createDataSource()
     */
    public static final class Factory implements HttpDataSource.Factory {

        private final Map<String, String> defaultRequestProperties;
        private boolean allowCrossProtocolRedirects;

        public Factory() {
            this.defaultRequestProperties = new HashMap<>();
            this.allowCrossProtocolRedirects = true;
        }

        /**
         * 设置默认请求头
         */
        public Factory setDefaultRequestProperties(Map<String, String> requestProperties) {
            defaultRequestProperties.clear();
            if (requestProperties != null) {
                defaultRequestProperties.putAll(requestProperties);
            }
            return this;
        }

        /**
         * 设置是否允许跨协议重定向
         */
        public Factory setAllowCrossProtocolRedirects(boolean allow) {
            this.allowCrossProtocolRedirects = allow;
            return this;
        }

        @Override
        public HttpDataSource createDataSource() {
            return new RedirectLoggingHttpDataSource(
                    defaultRequestProperties,
                    allowCrossProtocolRedirects);
        }
    }
}
