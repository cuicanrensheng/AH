package com.tv.live;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import com.tv.live.exception.RedirectFailedException;
import com.tv.live.util.LogCollector;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

@SuppressLint("UnsafeOptInUsageError")
public class RedirectLoggingHttpDataSource extends BaseDataSource implements HttpDataSource {
    private static final String TAG = "RedirectHttp";

    private boolean debugLogEnabled = false; 

    private int maxRedirects = 5;
    private int connectTimeout = 10000;
    private int readTimeout = 15000;
    private final Map<String, String> defaultRequestProperties;
    private final boolean allowCrossProtocolRedirects;
    private final boolean allowCrossDomainRedirects;
    private final boolean followRedirectsWithHeaders;
    private final boolean ignoreSslErrorRedirect;
    private HttpURLConnection connection;
    private InputStream inputStream;
    private boolean opened;
    private long bytesToRead;
    private long bytesRead;
    private int responseCode = -1;
    private String currentChannelName = "";

    private String getTimeStr() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        return sdf.format(new Date());
    }

    // 🟢【辅助方法】将文字包裹在红色 HTML 标签中
    private String red(String text) {
        return "<font color='#FF0000'>" + text + "</font>";
    }

    private void printLog(boolean isError, String msg) {
        String finalMsg = "[" + getTimeStr() + "] " + msg;
        if (isError) {
            Log.e(TAG, finalMsg);
        } else {
            Log.d(TAG, finalMsg);
        }

        if (debugLogEnabled) {
            if (isError) {
                LogCollector.getInstance().addLog("", "[ERROR] " + msg);
            } else {
                LogCollector.getInstance().addLog("", msg);
            }
        }
    }

    public void setDebugLogEnabled(boolean enabled) {
        this.debugLogEnabled = enabled;
    }

    protected RedirectLoggingHttpDataSource(
            Map<String, String> defaultRequestProperties,
            boolean allowCrossProtocolRedirects,
            boolean allowCrossDomainRedirects,
            boolean followRedirectsWithHeaders,
            boolean ignoreSslErrorRedirect,
            int maxRedirects,
            int connectTimeout,
            int readTimeout
    ) {
        super(true);
        this.defaultRequestProperties = defaultRequestProperties != null
                ? new HashMap<>(defaultRequestProperties)
                : new HashMap<>();
        this.allowCrossProtocolRedirects = allowCrossProtocolRedirects;
        this.allowCrossDomainRedirects = allowCrossDomainRedirects;
        this.followRedirectsWithHeaders = followRedirectsWithHeaders;
        this.ignoreSslErrorRedirect = ignoreSslErrorRedirect;
        this.maxRedirects = maxRedirects;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public void setChannelName(String channelName) {
        this.currentChannelName = (channelName != null) ? channelName : "";
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
        try {
            transferInitializing(dataSpec);
            connection = openConnection(dataSpec);
            responseCode = connection.getResponseCode();
            syncResponseCookies(connection, dataSpec.uri.toString());
            
            if (responseCode >= 200 && responseCode < 300) {
                // 成功日志在 openConnection 内部处理
            }

            if (responseCode < 200 || responseCode > 299) {
                String responseMessage = connection.getResponseMessage();
                // 🟢【标红】失败状态码显示为红色
                printLog(true, "失败: HTTP " + red(responseCode + " " + responseMessage));
                throw new HttpDataSource.HttpDataSourceException(
                        "HTTP " + responseCode + " " + responseMessage,
                        dataSpec,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN);
            }
            try {
                inputStream = connection.getInputStream();
                String contentEncoding = connection.getContentEncoding();
                if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
                    inputStream = new GZIPInputStream(inputStream);
                }
            } catch (IOException e) {
                inputStream = connection.getErrorStream();
            }
            long contentLength = getContentLength(connection);
            if (dataSpec.position != C.POSITION_UNSET) {
                bytesToRead = dataSpec.length != C.LENGTH_UNSET
                        ? dataSpec.length
                        : (contentLength != C.LENGTH_UNSET ? contentLength - dataSpec.position : C.LENGTH_UNSET);
            } else {
                bytesToRead = dataSpec.length != C.LENGTH_UNSET
                        ? dataSpec.length
                        : contentLength;
            }
            bytesRead = 0;
            opened = true;
            transferStarted(dataSpec);
            return bytesToRead;
        } catch (IOException e) {
            closeConnectionQuietly();
            throw new HttpDataSource.HttpDataSourceException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN);
        }
    }

    private void syncResponseCookies(HttpURLConnection conn, String requestUrl) {
        Map<String, List<String>> headerMap = conn.getHeaderFields();
        List<String> cookieList = headerMap.get("Set-Cookie");
        if (cookieList == null || cookieList.isEmpty()) return;
        CookieManager cookieManager = CookieManager.getInstance();
        for (String cookieStr : cookieList) {
            cookieManager.setCookie(requestUrl, cookieStr);
        }
    }

    private HttpURLConnection openConnection(DataSpec dataSpec) throws IOException {
        String originalUrl = dataSpec.uri.toString();
        String currentUrl = originalUrl;
        int redirectCount = 0;
        Map<String, String> originHeaders = new HashMap<>(defaultRequestProperties);
        
        long startTime = System.currentTimeMillis();
        final long MAX_TOTAL_DELAY = 15000;

        String prefix = TextUtils.isEmpty(currentChannelName) ? "" : (currentChannelName + " ");
        printLog(false, prefix + "请求链接: " + originalUrl);

        while (true) {
            if (System.currentTimeMillis() - startTime > MAX_TOTAL_DELAY) {
                printLog(true, "失败: 重定向总耗时超时");
                throw new RedirectFailedException("重定向总耗时超时", -1, originalUrl, currentUrl);
            }

            if (redirectCount > maxRedirects) {
                // 🟢【标红 + 修复】将 int 转为 String 再传参
                printLog(true, "失败: 重定向次数超过限制(" + red(String.valueOf(maxRedirects)) + "次)");
                throw new RedirectFailedException("重定向次数超限", -1, originalUrl, currentUrl);
            }
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setDoInput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            if (followRedirectsWithHeaders || redirectCount == 0) {
                for (Map.Entry<String, String> entry : originHeaders.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            if (dataSpec.position != C.POSITION_UNSET) {
                String rangeValue = "bytes=" + dataSpec.position + "-";
                if (dataSpec.length != C.LENGTH_UNSET) {
                    rangeValue += (dataSpec.position + dataSpec.length - 1);
                }
                conn.setRequestProperty("Range", rangeValue);
            }
            int respCode = conn.getResponseCode();
            boolean isRedirect = (respCode == 301 || respCode == 302
                    || respCode == 303 || respCode == 307 || respCode == 308);
            if (!isRedirect) {
                printLog(false, prefix + "请求成功 状态码 " + respCode + ": " + currentUrl);
                return conn;
            }
            redirectCount++;
            String location = conn.getHeaderField("Location");
            if (TextUtils.isEmpty(location)) {
                // 🟢【标红 + 修复】将 int 转为 String 再传参
                printLog(true, "失败: 第" + red(String.valueOf(redirectCount)) + "次重定向无Location头");
                conn.disconnect();
                throw new RedirectFailedException("重定向Location为空", respCode, originalUrl, currentUrl);
            }
            String redirectUrl = resolveRedirectUrl(currentUrl, location);
            Uri baseUri = Uri.parse(currentUrl);
            Uri targetUri = Uri.parse(redirectUrl);
            boolean crossProtocol = !Objects.equals(baseUri.getScheme(), targetUri.getScheme());
            if (crossProtocol && !allowCrossProtocolRedirects) {
                printLog(true, "失败: 禁止跨协议跳转");
                conn.disconnect();
                throw new RedirectFailedException("跨协议重定向被禁用", respCode, originalUrl, redirectUrl);
            }
            boolean crossDomain = !Objects.equals(baseUri.getHost(), targetUri.getHost());
            boolean isInner = isInnerIp(targetUri.getHost());
            if (crossDomain && !allowCrossDomainRedirects && !isInner) {
                printLog(true, "失败: 禁止跨域名跳转");
                conn.disconnect();
                throw new RedirectFailedException("跨域名重定向被禁用", respCode, originalUrl, redirectUrl);
            }
            if (ignoreSslErrorRedirect && "https".equals(targetUri.getScheme())) {
                // 信任管理器扩展预留
            }
            
            printLog(false, prefix + "重定向" + redirectCount + "次~ 状态码 " + respCode + ": " + redirectUrl);
            
            conn.disconnect();
            currentUrl = redirectUrl;
        }
    }

    private boolean isInnerIp(String host) {
        if (host == null) return false;
        return host.startsWith("127.")
                || host.startsWith("192.168.")
                || host.startsWith("10.")
                || host.equals("localhost");
    }

    private String resolveRedirectUrl(String baseUrl, String location) throws IOException {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
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
            sb.append(location);
        } else {
            if (path != null && path.contains("/")) {
                String parentPath = path.substring(0, path.lastIndexOf('/') + 1);
                sb.append(parentPath).append(location);
            } else {
                sb.append("/").append(location);
            }
        }
        return sb.toString();
    }

    private long getContentLength(HttpURLConnection connection) {
        String contentLength = connection.getHeaderField("Content-Length");
        if (!TextUtils.isEmpty(contentLength)) {
            try {
                return Long.parseLong(contentLength);
            } catch (NumberFormatException ignored) {}
        }
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws HttpDataSource.HttpDataSourceException {
        if (readLength == 0) return 0;
        if (bytesToRead == 0) return C.RESULT_END_OF_INPUT;
        try {
            int maxRead = (int) Math.min(readLength,
                    bytesToRead == C.LENGTH_UNSET ? Integer.MAX_VALUE : bytesToRead - bytesRead);
            int readSize = inputStream.read(buffer, offset, maxRead);
            if (readSize == -1) {
                if (bytesToRead != C.LENGTH_UNSET && bytesRead != bytesToRead) {
                    throw new HttpDataSource.HttpDataSourceException(
                            "流提前中断",
                            new DataSpec(Uri.parse(connection.getURL().toString())),
                            HttpDataSource.HttpDataSourceException.TYPE_READ);
                }
                return C.RESULT_END_OF_INPUT;
            }
            bytesRead += readSize;
            bytesTransferred(readSize);
            return readSize;
        } catch (IOException e) {
            throw new HttpDataSource.HttpDataSourceException(e,
                    new DataSpec(Uri.parse(connection.getURL().toString())),
                    HttpDataSource.HttpDataSourceException.TYPE_READ);
        }
    }

    @Override
    public Uri getUri() {
        return connection == null ? null : Uri.parse(connection.getURL().toString());
    }

    @Override
    public int getResponseCode() {
        return responseCode;
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
    public void close() throws HttpDataSource.HttpDataSourceException {
        if (opened) {
            opened = false;
            transferEnded();
            closeConnectionQuietly();
        }
    }

    private void closeConnectionQuietly() {
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {}
        inputStream = null;
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }

    public static final class Factory implements DataSource.Factory {
        private final Map<String, String> defaultRequestProperties = new HashMap<>();
        private boolean allowCrossProtocolRedirects = true;
        private boolean allowCrossDomainRedirects = true;
        private boolean followRedirectsWithHeaders = true;
        private boolean ignoreSslErrorRedirect = false;
        private int maxRedirects = 5;
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 15000;
        private String channelName = "";
        private boolean debugLogEnabled = false; 

        public Factory() {}

        public Factory setDebugLogEnabled(boolean enabled) {
            this.debugLogEnabled = enabled;
            return this;
        }

        public Factory setDefaultRequestProperties(Map<String, String> map) {
            defaultRequestProperties.clear();
            if (map != null) defaultRequestProperties.putAll(map);
            return this;
        }

        public Factory setMaxRedirects(int count) {
            this.maxRedirects = count;
            return this;
        }

        public Factory setAllowCrossProtocolRedirects(boolean enable) {
            this.allowCrossProtocolRedirects = enable;
            return this;
        }

        public Factory setAllowCrossDomainRedirects(boolean enable) {
            this.allowCrossDomainRedirects = enable;
            return this;
        }

        public Factory setFollowRedirectsWithHeaders(boolean enable) {
            this.followRedirectsWithHeaders = enable;
            return this;
        }

        public Factory setIgnoreSslErrorRedirect(boolean ignore) {
            this.ignoreSslErrorRedirect = ignore;
            return this;
        }

        public Factory setConnectTimeoutMs(int ms) {
            this.connectTimeoutMs = ms;
            return this;
        }

        public Factory setReadTimeoutMs(int ms) {
            this.readTimeoutMs = ms;
            return this;
        }

        public Factory setChannelName(String name) {
            this.channelName = name;
            return this;
        }

        @Override
        public DataSource createDataSource() {
            RedirectLoggingHttpDataSource source = new RedirectLoggingHttpDataSource(
                    defaultRequestProperties,
                    allowCrossProtocolRedirects,
                    allowCrossDomainRedirects,
                    followRedirectsWithHeaders,
                    ignoreSslErrorRedirect,
                    maxRedirects,
                    connectTimeoutMs,
                    readTimeoutMs
            );
            source.setChannelName(channelName);
            source.setDebugLogEnabled(debugLogEnabled);
            return source;
        }
    }
}
