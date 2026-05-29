package com.tv.live;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class HttpServer {
    private static HttpServer instance;
    private static final int PORT = 10481;
    private final Context context;
    private MainActivity mainActivity;

    private HttpServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public static HttpServer getInstance(Context context) {
        if (instance == null) {
            instance = new HttpServer(context);
        }
        return instance;
    }

    public void setMainActivity(MainActivity activity) {
        this.mainActivity = activity;
    }

    public void start() {
        try {
            instance = HttpServer.create(new InetSocketAddress(PORT), 0);
            instance.createContext("/", new RootHandler());
            instance.createContext("/config", new ConfigHandler());
            instance.start();
            Log.d("HttpServer", "服务器已启动：http://" + getDeviceIP() + ":" + PORT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        if (instance != null) {
            instance.stop(0);
        }
    }

    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "<html><body style='background:#111;color:#fff;padding:20px;'>" +
                    "<h1>TV Live 后台管理</h1>" +
                    "<p>当前直播源：" + UrlConfig.LIVE_URL + "</p>" +
                    "<p>当前节目单：" + UrlConfig.EPG_URL + "</p>" +
                    "<form action='/config' method='post'>" +
                    "<div style='margin:10px 0;'>直播源地址：<input name='live' style='width:80%;padding:8px;'></div>" +
                    "<div style='margin:10px 0;'>节目单地址：<input name='epg' style='width:80%;padding:8px;'></div>" +
                    "<input type='submit' value='保存配置' style='padding:10px 20px;background:#007bff;color:#fff;border:none;cursor:pointer;'>" +
                    "</form></body></html>";
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String liveUrl = extractParam(body, "live");
                String epgUrl = extractParam(body, "epg");

                if (mainActivity != null) {
                    mainActivity.onReceiveConfig(liveUrl, epgUrl);
                }

                String response = "<html><body style='background:#111;color:#fff;padding:20px;'>" +
                        "<h1>配置已更新</h1>" +
                        "<p>直播源和节目单已更新，应用将自动重新加载。</p>" +
                        "<a href='/' style='color:#007bff;'>返回管理页面</a></body></html>";
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private String extractParam(String body, String key) {
        String[] params = body.split("&");
        for (String p : params) {
            if (p.startsWith(key + "=")) {
                return URLDecoder.decode(p.substring(key.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    public String getDeviceIP() {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ipAddress = wifiInfo.getIpAddress();
        return String.format("%d.%d.%d.%d",
                (ipAddress & 0xFF),
                (ipAddress >> 8 & 0xFF),
                (ipAddress >> 16 & 0xFF),
                (ipAddress >> 24 & 0xFF));
    }
}
