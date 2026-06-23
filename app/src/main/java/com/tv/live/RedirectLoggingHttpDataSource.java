package com.tv.live;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

// ====================================================================
// ✅ Media3 迁移：所有 import 从 exoplayer2.upstream 改为 media3.datasource
// ====================================================================
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
// HttpDataSourceException 在 Media3 中变成了 HttpDataSource 的内部类
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException;

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
 * 【最大重定向次数】
 * 默认 20 次，防止无限重定向死循环。
 *
 * 【2026-06-23 Media3 迁移说明】
 * 从 ExoPlayer 2.x 升级到 Media3 1.10.1：
 * - 包名从 com.google.android.exoplayer2.upstream 改为 androidx.media3.datasource
 * - C 类移到 common 包
 * - HttpDataSourceException 变成了 HttpDataSource 的内部类
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

    // ===== 新增：保存 HTTP 响应状态码 =====
    /** HTTP 响应状态码（用于 getResponseCode()） */
    private int responseCode = -1;

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
            responseCode = connection.getResponseCode();  // 保存状态码

            // ===== 获取响应头 =====
            Map<String, List<String>> headers = connection.getHeaderFields();

            // ===== 处理错误响应 =====
            if (responseCode < 200 || responseCode > 299) {
                String responseMessage = connection.getResponseMessage();
                SettingsActivity.log("❌ HTTP 请求失败：" + responseCode + " " + responseMessage);
                SettingsActivity.log("   URL：" + dataSpec.uri);
                // ===== 修复：TYPE_RESPONSE_CODE_UNSUPPORTED 换成 TYPE_OPEN =====
                throw new HttpDataSourceException(
                        "HTTP " + responseCode + " " + responseMessage,
                        dataSpec,
                        HttpDataSourceException.TYPE_OPEN);
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

           
