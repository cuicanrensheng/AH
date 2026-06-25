package com.tv.live;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

// ====================================================================
// ✅ 2026-06-23 修改：升级到 Media3 1.10.1
// ====================================================================
// 所有 import 从 com.google.android.exoplayer2.upstream.*
// 改成对应的 androidx.media3.datasource.* 包名
//
// 【包名迁移对照表】
// 旧包名 (ExoPlayer 2.x)                → 新包名 (Media3 1.x)
// com.google.android.exoplayer2.C        → androidx.media3.common.C
// com.google.android.exoplayer2.upstream.BaseDataSource → androidx.media3.datasource.BaseDataSource
// com.google.android.exoplayer2.upstream.DataSpec → androidx.media3.datasource.DataSpec
// com.google.android.exoplayer2.upstream.HttpDataSource → androidx.media3.datasource.HttpDataSource
//
// ⚠️ 重要变化：HttpDataSourceException
// 在 ExoPlayer 2.x 中：HttpDataSourceException 是独立的类
// 在 Media3 1.x 中：HttpDataSourceException 变成了 HttpDataSource 的内部类
//   用法：HttpDataSource.HttpDataSourceException
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

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
* 带重定向日志的 HTTP 数据源（精简日志版）
*
* 【功能】
* 1. 手动处理 HTTP 重定向（301/302/303/307/308）
* 2. 支持跨协议重定向（HTTP ↔ HTTPS）
* 3. 支持相对路径的 Location
* 4. 支持 GZIP 解压
* 5. 日志精简，只输出核心信息
*
* 【2026-06-21 优化：精简日志输出】
* 【优化内容】
* 1. 去掉每次重定向的详细输出（从哪到哪、Headers等）
* 2. 去掉复杂的分隔线和 emoji
* 3. 只保留核心信息：请求开始、重定向次数、最终地址
* 4. 其他功能全部保留（GZIP、跨协议、Range、Factory等）
*
* 【日志输出示例】
* 有重定向：
*   [HTTP] 请求开始: http://xxx
*   [HTTP] 重定向: 2次
*   [HTTP] 最终地址: http://yyy
*
* 无重定向：
*   [HTTP] 请求开始: http://xxx
*   [HTTP] 无重定向
*   [HTTP] 最终地址: http://xxx
*
* 失败：
*   [HTTP] 请求开始: http://xxx
*   [HTTP] ❌ 失败: HTTP 404 Not Found
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

/** HTTP 响应状态码（用于 getResponseCode()） */
private int responseCode = -1;

// ====================================================================
// 构造函数
// ====================================================================
protected RedirectLoggingHttpDataSource(
Map<String, String> defaultRequestProperties,
boolean allowCrossProtocolRedirects) {
super(true);
this.defaultRequestProperties = defaultRequestProperties != null
? new HashMap<>(defaultRequestProperties)
: new HashMap<>();
this.allowCrossProtocolRedirects = allowCrossProtocolRedirects;
}

// ====================================================================
// open 方法
// ====================================================================
@Override
// ====================================================================
// ✅ 2026-06-23 修改：HttpDataSourceException 变成内部类
// ====================================================================
// 从 HttpDataSourceException
// 改成 HttpDataSource.HttpDataSourceException
//
// 【为什么变了？】
// Media3 对类的组织结构做了调整，
// 把 HttpDataSourceException 挪到了 HttpDataSource 接口内部，
// 这样更符合面向对象的设计原则（异常和接口放在一起）。
public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
try {
transferInitializing(dataSpec);

// ===== 打开连接（手动处理重定向） =====
connection = openConnection(dataSpec);
responseCode = connection.getResponseCode();  // 保存状态码

// ===== 处理错误响应 =====
if (responseCode < 200 || responseCode > 299) {
String responseMessage = connection.getResponseMessage();

// ============================================================
// ✅ 精简：错误日志简化
// ============================================================
SettingsActivity.log("[HTTP] ❌ 失败: HTTP " + responseCode + " " + responseMessage);

throw new HttpDataSource.HttpDataSourceException(
"HTTP " + responseCode + " " + responseMessage,
dataSpec,
HttpDataSource.HttpDataSourceException.TYPE_OPEN);
}

// ===== 获取输入流 =====
try {
inputStream = connection.getInputStream();

// 处理 GZIP 压缩
String contentEncoding = connection.getContentEncoding();
if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
inputStream = new GZIPInputStream(inputStream);
// GZIP 这个信息还是保留吧，挺有用的，而且只有一行
SettingsActivity.log("[HTTP] GZIP 压缩，已自动解压");
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

// ================================================================
// ✅ 精简：最终响应日志简化
// ================================================================
// 只输出最终地址，其他都去掉
SettingsActivity.log("[HTTP] 最终地址: " + connection.getURL());

opened = true;
transferStarted(dataSpec);
return bytesToRead;

} catch (IOException e) {
closeConnectionQuietly();
throw new HttpDataSource.HttpDataSourceException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN);
}
}

// ====================================================================
// 打开连接，手动处理重定向
// ====================================================================
/**
* 打开连接，手动处理重定向
*
* 【重定向处理流程】
* 1. 发起请求
* 2. 检查状态码，如果是 3xx 就处理重定向
* 3. 最多重定向 20 次
*
* @param dataSpec 请求参数
* @return 最终的 HttpURLConnection
*/
private HttpURLConnection openConnection(DataSpec dataSpec) throws IOException {
String currentUrl = dataSpec.uri.toString();
int redirectCount = 0;

// ================================================================
// ✅ 精简：请求开始日志简化
// ================================================================
SettingsActivity.log("[HTTP] 请求开始: " + currentUrl);

while (true) {
// ===== 检查重定向次数 =====
if (redirectCount > MAX_REDIRECTS) {
SettingsActivity.log("[HTTP] ❌ 失败: 重定向次数超过限制（" + MAX_REDIRECTS + "次）");
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
// Range 这个信息去掉，日志太多了
}

// ===== 发起请求 =====
int respCode = conn.getResponseCode();
String responseMessage = conn.getResponseMessage();

// ===== 判断是否是重定向 =====
boolean isRedirect = (respCode == 301 || respCode == 302
|| respCode == 303 || respCode == 307 || respCode == 308);

if (!isRedirect) {
// 不是重定向，返回这个连接

// ============================================================
// ✅ 精简：重定向次数总结（只输出一行）
// ============================================================
if (redirectCount > 0) {
SettingsActivity.log("[HTTP] 重定向: " + redirectCount + "次");
} else {
SettingsActivity.log("[HTTP] 无重定向");
}

return conn;
}

// ===== 处理重定向 =====
redirectCount++;

String location = conn.getHeaderField("Location");
if (TextUtils.isEmpty(location)) {
SettingsActivity.log("[HTTP] ❌ 失败: 第 " + redirectCount + " 重定向没有 Location 头");
conn.disconnect();
throw new IOException("Redirect with no Location header");
}

// ===== 处理相对路径 =====
String redirectUrl = resolveRedirectUrl(currentUrl, location);

// ===== 检查跨协议 =====
boolean isCrossProtocol = !url.getProtocol().equalsIgnoreCase(
Uri.parse(redirectUrl).getScheme());

if (isCrossProtocol && !allowCrossProtocolRedirects) {
SettingsActivity.log("[HTTP] ❌ 失败: 跨协议重定向被禁止");
conn.disconnect();
throw new IOException("Cross-protocol redirect not allowed");
}

// ================================================================
// ✅ 精简：去掉每次重定向的详细输出
// ================================================================
// 原来的输出：
// 🔄 第 1 重：HTTP 302 Found
//    从：http://xxx
//    到：http://yyy
//    Headers：Location=http://yyy | Server=nginx
//
// 现在都去掉了，只在最后输出总次数

// ===== 关闭当前连接，准备下一次请求 =====
conn.disconnect();
currentUrl = redirectUrl;
}
}

// ====================================================================
// 解析重定向地址（处理相对路径）
// ====================================================================
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

// ====================================================================
// 获取 Content-Length
// ====================================================================
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

// ====================================================================
// read 方法
// ====================================================================
@Override
// ====================================================================
// ✅ 2026-06-23 修改：HttpDataSourceException 变成内部类
// ====================================================================
public int read(byte[] buffer, int offset, int readLength) throws HttpDataSource.HttpDataSourceException {
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
throw new HttpDataSource.HttpDataSourceException(
"Unexpected end of input",
new DataSpec(Uri.parse(connection.getURL().toString())),
HttpDataSource.HttpDataSourceException.TYPE_READ);
}
return C.RESULT_END_OF_INPUT;
}

bytesRead += bytesReadThisTime;
bytesTransferred(bytesReadThisTime);
return bytesReadThisTime;

} catch (IOException e) {
throw new HttpDataSource.HttpDataSourceException(e,
new DataSpec(Uri.parse(connection.getURL().toString())),
HttpDataSource.HttpDataSourceException.TYPE_READ);
}
}

// ====================================================================
// 其他接口方法
// ====================================================================
@Override
public Uri getUri() {
return connection == null ? null : Uri.parse(connection.getURL().toString());
}

/**
* 获取 HTTP 响应状态码
*
* @return HTTP 状态码，如果还没建立连接返回 -1
*/
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
// ====================================================================
// ✅ 2026-06-23 修改：HttpDataSourceException 变成内部类
// ====================================================================
public void close() throws HttpDataSource.HttpDataSourceException {
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
// Factory 工厂类（完全保留，不改动）
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
