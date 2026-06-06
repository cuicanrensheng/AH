package com.tv.live;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
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
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 20000;
    private static final int MAX_REDIRECT = 10;
    private static final String UA = "ExoPlayerLib/2.19.1 (Linux; Android 11; Android TV)";
    private static final HashMap<String, String> cookieStore = new HashMap<>();

    static {
        initAllTrustSSL();
    }

    private static void initAllTrustSSL() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                        @Override
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        @Override
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, trustAll, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslCtx.getSocketFactory());
            HostnameVerifier ignoreHost = (hostname, session) -> true;
            HttpsURLConnection.setDefaultHostnameVerifier(ignoreHost);
        } catch (Exception e) {
            Log.e("HttpSSL", "SSL初始化异常：" + e.getMessage());
        }
    }

    // 兼容旧项目：保留原来的 get() 方法
    public static String get(String url) {
        return getStr(url);
    }

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

                if (respCode >= 300 && respCode < 400) {
                    redirectNum++;
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        closeResource(conn, br, is);
                        return null;
                    }
                    URI fullUri = parseUrl.toURI().resolve(location);
                    nowUrl = fullUri.toString();
                    closeResource(conn, br, is);
                    continue;
                }

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
                    closeResource(conn, br, is);
                    return null;
                }
            } catch (Exception ex) {
                closeResource(conn, br, is);
                return null;
            }
        }
        return null;
    }

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
                conn.setRequestProperty("Accept", "application/octet-stream,*/*;q=0.9");
                conn.setRequestProperty("Accept-Encoding", "identity");

                int respCode = conn.getResponseCode();
                saveDomainCookie(conn.getHeaderFields(), parseUrl.getHost());

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
                    closeResource(conn, null, is);
                    return null;
                }
            } catch (Exception ex) {
                closeResource(conn, null, is);
                return null;
            }
        }
        return null;
    }
    private static void setBaseHeader(HttpURLConnection conn, URL urlObj) {
    conn.setConnectTimeout(CONNECT_TIMEOUT);
    conn.setReadTimeout(READ_TIMEOUT);
    try {
        conn.setRequestMethod("GET");
    } catch (ProtocolException e) {
        e.printStackTrace();
    }
    conn.setInstanceFollowRedirects(false);
    conn.setUseCaches(false);

    String host = urlObj.getHost();
    // 【核心适配这套3层中转虎牙接口】只要是中转域名，固定Referer/Origin
    if(host.contains("jdshipin.com") || host.contains("zxyxndc.top")){
        // 和抓包请求头完全一致
        conn.setRequestProperty("Referer","http://cdn.jdshipin.com:8880/");
        conn.setRequestProperty("Origin","http://cdn.jdshipin.com");
        conn.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
    }else if(host.contains("huya.com")){
        // 最终虎牙FLV域名
        conn.setRequestProperty("Referer","https://www.huya.com/");
        conn.setRequestProperty("Origin","https://www.huya.com");
        conn.setRequestProperty("User-Agent","ExoPlayerLib/2.19.1 (Linux; Android 11; Android TV)");
    }else{
        // 普通源沿用原有自动域名
        String domain = urlObj.getProtocol() + "://" + urlObj.getHost();
        conn.setRequestProperty("Referer", domain);
        conn.setRequestProperty("Origin", domain);
        conn.setRequestProperty("User-Agent", UA);
    }

    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
    conn.setRequestProperty("Connection", "keep-alive");
    conn.setRequestProperty("Accept","*/*");
    conn.setRequestProperty("sec-fetch-site","cross-site");
    conn.setRequestProperty("sec-fetch-mode","cors");

    String domainCk = cookieStore.get(urlObj.getHost());
    if (domainCk != null && !domainCk.isEmpty()) {
        conn.setRequestProperty("Cookie", domainCk);
    }
}
    private static void saveDomainCookie(Map<String, List<String>> headerMap, String host) {
        if (headerMap == null || host == null) return;
        List<String> setCookieList = headerMap.get("Set-Cookie");
        if (setCookieList == null || setCookieList.isEmpty()) return;
        for (String ckItem : setCookieList) {
            if (ckItem.contains(";")) {
                ckItem = ckItem.split(";")[0];
            }
            cookieStore.put(host, ckItem);
        }
    }

    private static void closeResource(HttpURLConnection conn, BufferedReader br, InputStream is) {
        try { if (br != null) br.close(); } catch (Exception ignored) {}
        try { if (is != null) is.close(); } catch (Exception ignored) {}
        try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
    }

    public interface HttpStrCallback {
        void onResult(String result);
    }

    public interface HttpByteCallback {
        void onResult(byte[] data);
    }

    public static void getStrAsync(String url, HttpStrCallback callback) {
        new Thread(() -> {
            String res = getStr(url);
            if (callback != null && MainActivity.mInstance != null) {
                MainActivity.mInstance.runOnUiThread(() -> callback.onResult(res));
            }
        }).start();
    }

    public static void getByteAsync(String url, HttpByteCallback callback) {
        new Thread(() -> {
            byte[] data = getByte(url);
            if (callback != null && MainActivity.mInstance != null) {
                MainActivity.mInstance.runOnUiThread(() -> callback.onResult(data));
            }
        }).start();
    }

    public static void clearAllCookie() {
        cookieStore.clear();
    }
}
