package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class HttpUtil {

    // 全局信任所有 HTTPS 证书（电视盒子必备）
    static {
        trustAllCertificates();
    }

    private static void trustAllCertificates() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {}
    }

    // 标准 GET —— 兼容你旧代码 100% 不用改
    public static String get(String url) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();

        // 超时（防卡死、防端口占用）
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        
        // 允许重定向（301/302）
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");

        BufferedReader br = null;
        InputStreamReader isr = null;
        InputStream inputStream = null;

        try {
            inputStream = conn.getInputStream();
            isr = new InputStreamReader(inputStream);
            br = new BufferedReader(isr);

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();

        } finally {
            // 安全关闭所有流，避免资源泄漏 → 解决你端口占用问题
            try { if (br != null) br.close(); } catch (Exception ignored) {}
            try { if (isr != null) isr.close(); } catch (Exception ignored) {}
            try { if (inputStream != null) inputStream.close(); } catch (Exception ignored) {}
            conn.disconnect();
        }
    }

    // 安全调用：不抛异常 → 推荐项目所有地方用这个
    public static String getSafe(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            return get(url);
        } catch (Exception e) {
            return "";
        }
    }
}
