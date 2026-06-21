package com.tv.live;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * 带重定向日志的 Http 数据源（精简版）
 *
 * 【功能】
 * 1. 记录 HTTP 请求的重定向信息
 * 2. 只输出最核心的信息，日志简洁
 *
 * 【2026-06-21 优化：精简版，减少日志输出】
 * 【优化内容】
 * 1. 去掉复杂的分隔线和 emoji，日志更简洁
 * 2. 去掉每次重定向的详细输出
 * 3. 只保留核心信息：原始地址、最终地址、重定向次数、总耗时
 * 4. 成功/失败用简单的标记区分
 * 5. 日志量减少 70% 以上
 *
 * 【日志输出示例】
 * 成功：
 *   [HTTP] 请求开始: http://xxx
 *   [HTTP] 重定向: 2次, 总耗时: 356ms
 *   [HTTP] 最终地址: http://yyy
 *
 * 失败：
 *   [HTTP] 请求开始: http://xxx
 *   [HTTP] ❌ 失败: 错误信息
 */
public class RedirectLoggingHttpDataSource extends BaseDataSource implements HttpDataSource {

    // ====================================================================
    // 常量
    // ====================================================================

    /** 最大重定向次数（防止无限重定向） */
    private static final int MAX_REDIRECTS = 20;

    /** 连接超时时间（毫秒） */
    private static final int CONNECT_TIMEOUT = 10 * 1000;

    /** 读取超时时间（毫秒） */
    private static final int READ_TIMEOUT = 30 * 1000;

    // ====================================================================
    // 成员变量
    // ====================================================================

    /** 底层的 HttpURLConnection */
    private HttpURLConnection connection;

    /** 输入流 */
    private InputStream inputStream;

    /** 当前请求的数据规格 */
    private DataSpec dataSpec;

    /** 是否已打开 */
    private boolean opened;

    /** 已传输的字节数 */
    private long bytesRemaining;

    /** 当前字节位置 */
    private long currentPosition;

    /** 重定向次数（临时存储） */
    private int redirectCount = 0;

    // ====================================================================
    // 构造函数
    // ====================================================================

    public RedirectLoggingHttpDataSource() {
        super(true);
    }

    // ====================================================================
    // 核心方法：打开连接
    // ====================================================================

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        this.dataSpec = dataSpec;
        this.currentPosition = dataSpec.position;

        // 记录请求开始时间
        long startTime = System.currentTimeMillis();

        // ================================================================
        // 输出请求开始日志（精简：一行）
        // ================================================================
        SettingsActivity.log("[HTTP] 请求开始: " + dataSpec.uri.toString());

        try {
            // 执行请求（处理重定向）
            URL finalUrl = executeRequest(dataSpec);
            int redirectCount = getRedirectCount();

            // 计算总耗时
            long totalDuration = System.currentTimeMillis() - startTime;

            // ================================================================
            // 输出重定向统计（精简：一行）
            // ================================================================
            if (redirectCount > 0) {
                SettingsActivity.log("[HTTP] 重定向: " + redirectCount + "次, 总耗时: " + totalDuration + "ms");
            } else {
                SettingsActivity.log("[HTTP] 无重定向, 耗时: " + totalDuration + "ms");
            }

            // ================================================================
            // 输出最终地址（精简：一行）
            // ================================================================
            SettingsActivity.log("[HTTP] 最终地址: " + finalUrl.toString());

            // 获取内容长度
            int contentLength = connection.getContentLength();
            if (contentLength == -1) {
                bytesRemaining = C.LENGTH_UNSET;
            } else {
                bytesRemaining = contentLength - dataSpec.position;
            }

            opened = true;
            transferInitializing(dataSpec);
            transferStarted(dataSpec);

            return bytesRemaining;

        } catch (IOException e) {
            // ================================================================
            // 输出请求失败日志（精简：一行）
            // ================================================================
            SettingsActivity.log("[HTTP] ❌ 失败: " + e.getMessage());

            throw new HttpDataSourceException(
                    "HTTP request failed",
                    e,
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            );
        }
    }

    // ====================================================================
    // 核心方法：执行请求并处理重定向
    // ====================================================================

    /**
     * 执行 HTTP 请求，自动处理重定向
     *
     * @param dataSpec 数据规格
     * @return 最终的 URL
     * @throws IOException IO 异常
     */
    private URL executeRequest(DataSpec dataSpec) throws IOException {
        String currentUrl = dataSpec.uri.toString();
        int redirectCount = 0;

        while (true) {
            // 打开连接
            URL url = new URL(currentUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(false); // 我们自己处理重定向

            // 设置 Range 头（如果有 position）
            if (dataSpec.position > 0) {
                connection.setRequestProperty("Range", "bytes=" + dataSpec.position + "-");
            }

            // 设置请求方法
            connection.setRequestMethod("GET");

            // 获取状态码
            int statusCode = connection.getResponseCode();

            // 判断是否是重定向
            boolean isRedirect = (statusCode >= 300 && statusCode < 400);

            if (isRedirect) {
                // 是重定向，获取新的 URL
                String location = connection.getHeaderField("Location");
                if (location == null) {
                    throw new IOException("Redirect with no Location header");
                }

                // 处理相对路径的重定向
                URL baseUrl = new URL(currentUrl);
                URL redirectUrl = new URL(baseUrl, location);
                currentUrl = redirectUrl.toString();

                // 关闭当前连接
                connection.disconnect();

                redirectCount++;

                // 检查是否超过最大重定向次数
                if (redirectCount > MAX_REDIRECTS) {
                    throw new IOException("Too many redirects: " + redirectCount);
                }

            } else {
                // 不是重定向，获取输入流
                try {
                    inputStream = connection.getInputStream();
                } catch (IOException e) {
                    inputStream = connection.getErrorStream();
                }

                // 保存重定向次数
                this.redirectCount = redirectCount;

                return new URL(currentUrl);
            }
        }
    }

    // ====================================================================
    // 辅助方法
    // ====================================================================

    /**
     * 获取重定向次数
     */
    private int getRedirectCount() {
        return redirectCount;
    }

    // ====================================================================
    // 其他必须实现的方法
    // ====================================================================

    @Override
    public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
        if (!opened) {
            throw new IllegalStateException("Not opened");
        }
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }

        int bytesRead;
        try {
            bytesRead = inputStream.read(buffer, offset,
                    bytesRemaining == C.LENGTH_UNSET ? length : (int) Math.min(bytesRemaining, length));
        } catch (IOException e) {
            throw new HttpDataSourceException(
                    e,
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            );
        }

        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT;
        }

        currentPosition += bytesRead;
        if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= bytesRead;
        }
        bytesTransferred(bytesRead);

        return bytesRead;
    }

    @Override
    public @Nullable Uri getUri() {
        if (connection == null) {
            return null;
        }
        return Uri.parse(connection.getURL().toString());
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        if (connection == null) {
            return null;
        }
        return connection.getHeaderFields();
    }

    @Override
    public void setRequestProperty(String key, String value) {
        // 可以在这里实现，如果需要的话
    }

    @Override
    public void clearRequestProperty(String key) {
        // 可以在这里实现，如果需要的话
    }

    @Override
    public void setDefaultRequestProperties(Map<String, String> defaultRequestProperties) {
        // 可以在这里实现，如果需要的话
    }

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
                        dataSpec,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                );
            } finally {
                connection.disconnect();
                connection = null;
                opened = false;
                transferEnded();
            }
        }
    }
}
