package com.tv.live;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class HttpUtil {
    // 超时配置
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 20000;
    // 最大10次重定向
    private static final int MAX_REDIRECT = 10;
    // ExoPlayer标准UA，适配虎牙、FLV拉流
    private static final String UA = "ExoPlayerLib/2.19.1 (Linux; Android 11; Android TV)";
    // 按域名存储Cookie，同域名自动携带cookie
    private static final HashMap<String, String> cookieStore = new HashMap<>();

    // 静态初始化：全局忽略SSL证书异常，兼容各类自签证书HTTPS源（虎牙必备）
    static {
        initAllTrustSSL();
    }

    /**
     * 信任全部SSL证书，解决HTTPS握手失败、证书不合法报错
     */
    private static void initAllTrustSSL() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, trustAll, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslCtx.getSocketFactory());
            // 放行域名校验
            HostnameVerifier ignoreHost = (hostname, session) -> true;
            HttpsURLConnection.setDefaultHostnameVerifier(ignoreHost);
        } catch (Exception e) {
            Log.e("HttpSSL", "SSL初始化异常：" + e.getMessage());
        }
    }

    //==================== 文本GET：m3u8/m3u/json/xml/txt 源解析专用 ====================
    public static String getStr(String url) {
        String nowUrl = url;
        int redirectNum = 0;
        while (redirectNum <= MAX_REDIRECT) {
            HttpURLConnection conn = null;
            BufferedReader br = null;
            InputStream is = null;
            try {
                URL parseUrl = new URL(nowUrl);
                conn = (HttpURLConnection) parseUrl.openConnection();
                setBaseHeader(conn, parseUrl);

                int respCode = conn.getResponseCode();
                saveDomainCookie(conn.getHeaderFields(), parseUrl.getHost());

                // 3xx重定向处理
                if (respCode >= 300 && respCode < 400) {
                    redirectNum++;
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        closeResource(conn, br, is);
                        return null;
                    }
                    // 拼接相对路径为完整url
                    URI fullUri = parseUrl.toURI().resolve(location);
                    nowUrl = fullUri.toString();
                    closeResource(conn, br, is);
                    continue;
                }

                // 正常200读取文本
                if (respCode == 200) {
                    is = conn.getInputStream();
                    br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    closeResource(conn, br, is);
                    return sb.toString();
                } else {
                    Log.e("HttpStr", "状态码异常:" + respCode + " | url:" + nowUrl);
                    closeResource(conn, br, is);
                    return null;
                }
            } catch (Exception ex) {
                Log.e("HttpStrErr", nowUrl + " | " + ex.getMessage());
                closeResource(conn, br, is);
                return null;
            }
        }
        Log.e("RedirectErr", "链接重定向超过10次：" + url);
        return null;
    }

    //==================== 二进制GET：FLV(FIV)/TS/MP4/F4V 流媒体二进制 ====================
    public static byte[] getByte(String url) {
        String nowUrl = url;
        int redirectNum = 0;
        while (redirectNum <= MAX_REDIRECT) {
            HttpURLConnection conn = null;
            InputStream is = null;
            ByteArrayOutputStream bos = null;
            try {
                URL parseUrl = new URL(nowUrl);
                conn = (HttpURLConnection) parseUrl.openConnection();
                setBaseHeader(conn, parseUrl);
                // FLV流媒体专属头：禁止gzip压缩防止流损坏、二进制接收
                conn.setRequestProperty("Accept", "application/octet-stream,*/*;q=0.9");
                conn.setRequestProperty("Accept-Encoding", "identity");

                int respCode = conn.getResponseCode();
                saveDomainCookie(conn.getHeaderFields(), parseUrl.getHost());

                // 重定向
                if (respCode >= 300 && respCode < 400) {
                    redirectNum++;
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        closeResource(conn, null, is);
                        return null;
                    }
                    URI fullUri = parseUrl.toURI().resolve(location);
                    nowUrl = fullUri.toString();
                    closeResource(conn, null, is);
                    continue;
                }

                if (respCode == 200) {
                    is = new BufferedInputStream(conn.getInputStream());
                    bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int readLen;
                    while ((readLen = is.read(buf)) != -1) {
                        bos.write(buf, 0, readLen);
                    }
                    byte[] result = bos.toByteArray();
                    closeResource(conn, null, is);
                    return result;
                } else {
                    Log.e("HttpByte", "二进制状态码异常:" + respCode + " url:" + nowUrl);
                    closeResource(conn, null, is);
                    return null;
                }
            } catch (Exception ex) {
                Log.e("HttpByteErr", nowUrl + " | " + ex.getMessage());
                closeResource(conn, null, is);
                return null;
            }
        }
        Log.e("RedirectByteErr", "二进制链接重定向超10次:" + url);
        return null;
    }

    /**
     * 统一公共请求头：自动UA/Referer/Origin/虎牙防盗链头+自动Cookie
     */
    private static void setBaseHeader(HttpURLConnection conn, URL urlObj) {
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setUseCaches(false);

        String domain = urlObj.getProtocol() + "://" + urlObj.getHost();
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", domain);
        conn.setRequestProperty("Origin", domain);
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setRequestProperty("Connection", "keep-alive");
        // 虎牙必备防盗链头
        conn.setRequestProperty("sec-fetch-site", "cross-site");
        conn.setRequestProperty("sec-fetch-mode", "cors");

        // 携带域名缓存Cookie
        String domainCk = cookieStore.get(urlObj.getHost());
        if (domainCk != null && !domainCk.isEmpty()) {
            conn.setRequestProperty("Cookie", domainCk);
        }
    }

    /**
     * 保存响应Set-Cookie，按域名区分
     */
    private static void saveDomainCookie(Map<String, List<String>> headerMap, String host) {
        if (headerMap == null || host == null) return;
        List<String> setCookieList = headerMap.get("Set-Cookie");
        if (setCookieList == null || setCookieList.isEmpty()) return;
        for (String ckItem : setCookieList) {
            // 只保留键值部分，丢弃过期、域等附加参数
            if (ckItem.contains(";")) {
                ckItem = ckItem.split(";")[0];
            }
            cookieStore.put(host, ckItem);
        }
    }

    /**
     * 统一关闭IO与连接，避免内存泄漏
     */
    private static void closeResource(HttpURLConnection conn, BufferedReader br, InputStream is) {
        try {
            if (br != null) br.close();
        } catch (Exception ignored) {}
        try {
            if (is != null) is.close();
        } catch (Exception ignored) {}
        try {
            if (conn != null) conn.disconnect();
        } catch (Exception ignored) {}
    }

    //==================== 异步回调接口定义 ====================
    public interface HttpStrCallback {
        void onResult(String result);
    }

    public interface HttpByteCallback {
        void onResult(byte[] data);
    }

    // 文本异步请求（子线程请求，主线程回调）
    public static void getStrAsync(String url, HttpStrCallback callback) {
        new Thread(() -> {
            String res = getStr(url);
            if (callback != null && MainActivity.mInstance != null) {
                MainActivity.mInstance.runOnUiThread(() -> callback.onResult(res));
            }
        }).start();
    }

    // FLV/二进制异步请求
    public static void getByteAsync(String url, HttpByteCallback callback) {
        new Thread(() -> {
            byte[] data = getByte(url);
            if (callback != null && MainActivity.mInstance != null) {
                MainActivity.mInstance.runOnUiThread(() -> callback.onResult(data));
            }
        }).start();
    }

    // 可选：清空全局Cookie缓存
    public static void clearAllCookie() {
        cookieStore.clear();
    }
}
