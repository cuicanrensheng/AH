package com.tv.live.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class LogServer {
    private static final String TAG = "LogServer";
    private static final int DEFAULT_PORT = 9527;
    private static final String PROTOCOL_VERSION = "1.0";

    private static final int MAX_CLIENTS = 5;
    private static volatile LogServer sInstance;
    private final Context context;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final CopyOnWriteArrayList<WebSocket> webSockets;
    private final List<Map<String, Object>> pendingLogs;
    private volatile boolean isRunning;
    private Thread serverThread;
    private java.net.ServerSocket serverSocket;
    private int port;
    private String deviceId;
    private String deviceName;
    private String deviceModel;
    private String appVersion;
    private String token;
    private final SimpleDateFormat sdf;

    private LogServer(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new GsonBuilder().serializeNulls().create();
        this.webSockets = new CopyOnWriteArrayList<>();
        this.pendingLogs = new ArrayList<>();
        this.isRunning = false;
        this.port = DEFAULT_PORT;
        this.sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        initDeviceInfo();
        generateToken();
        setupLogListener();
    }

    public static LogServer getInstance(Context context) {
        if (sInstance == null) {
            synchronized (LogServer.class) {
                if (sInstance == null) {
                    sInstance = new LogServer(context);
                }
            }
        }
        return sInstance;
    }

    private void initDeviceInfo() {
        try {
            deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (TextUtils.isEmpty(deviceId)) {
                deviceId = "unknown_" + Build.SERIAL;
            }
            deviceModel = Build.MODEL;
            deviceName = Build.MANUFACTURER + " " + Build.MODEL;
            try {
                PackageManager pm = context.getPackageManager();
                PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
                appVersion = pi.versionName + " (" + pi.versionCode + ")";
            } catch (Exception e) {
                appVersion = "unknown";
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to init device info", e);
            deviceId = "unknown";
            deviceName = "Unknown Device";
            deviceModel = "Unknown";
            appVersion = "0.0.0";
        }
    }

    private void generateToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        token = sb.toString();
    }

    private void setupLogListener() {
        LogCollector.getInstance().registerDeviceInfoProvider(new LogCollector.DeviceInfoProvider() {
            @Override
            public String getDeviceId() { return deviceId; }
            @Override
            public String getDeviceName() { return deviceName; }
            @Override
            public String getDeviceModel() { return deviceModel; }
            @Override
            public String getAppVersion() { return appVersion; }
        });

        LogCollector.getInstance().addLogListener(new LogCollector.LogListener() {
            @Override
            public void onLogAdded(LogCollector.LogEntry entry) {
                broadcastLog(entry);
            }

            @Override
            public void onLogCleared() {
                broadcastLogCleared();
            }
        });
    }

    public void start() {
        if (isRunning) {
            // 即使正在运行，也要清理过多的残留连接
            if (webSockets.size() > MAX_CLIENTS) {
                Log.w(TAG, "Clearing " + webSockets.size() + " stale connections (max " + MAX_CLIENTS + ")");
                for (WebSocket ws : webSockets) {
                    try {
                        ws.close(1000, "Stale cleanup");
                    } catch (Exception ignored) {}
                }
                webSockets.clear();
            }
            return;
        }
        
        // 清理所有残留连接
        for (WebSocket ws : webSockets) {
            try {
                ws.close(1000, "Restarting");
            } catch (Exception ignored) {}
        }
        webSockets.clear();
        
        isRunning = true;
        serverThread = new Thread(() -> {
            try {
                startHttpServer();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start LogServer", e);
                isRunning = false;
            }
        }, "LogServer");
        serverThread.setDaemon(true);
        serverThread.start();
        Log.i(TAG, "LogServer starting on port " + port + " (cleared " + webSockets.size() + " old connections)");
        
        // 启动定期清理任务
        startConnectionCleaner();
    }
    
    private void startConnectionCleaner() {
        Thread cleaner = new Thread(() -> {
            while (isRunning) {
                try {
                    Thread.sleep(30000); // 每30秒清理一次
                    // 只保留最近的 MAX_CLIENTS 个连接
                    while (webSockets.size() > MAX_CLIENTS) {
                        WebSocket oldest = webSockets.get(0);
                        try {
                            oldest.close(1000, "Cleanup");
                        } catch (Exception ignored) {}
                        webSockets.remove(0);
                    }
                } catch (Exception e) {
                    break;
                }
            }
        }, "LogServer-Cleaner");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    public void stop() {
        isRunning = false;
        for (WebSocket ws : webSockets) {
            try {
                ws.close(1000, "Server stopped");
            } catch (Exception ignored) {}
        }
        webSockets.clear();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        serverSocket = null;
        Log.i(TAG, "LogServer stopped");
    }

    private void startHttpServer() throws IOException {
        serverSocket = new java.net.ServerSocket(port);
        serverSocket.setReuseAddress(true);
        Log.i(TAG, "HTTP Server listening on port " + port);

        while (isRunning) {
            try {
                java.net.Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket), "LogServer-Client-" + System.currentTimeMillis())
                        .start();
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "Error accepting client", e);
                }
            }
        }
    }

    private void handleClient(java.net.Socket clientSocket) {
        try {
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(clientSocket.getInputStream(), "UTF-8"));
            java.io.OutputStream out = clientSocket.getOutputStream();

            StringBuilder request = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                request.append(line).append("\r\n");
            }

            String requestStr = request.toString();
            if (requestStr.contains("Upgrade: websocket") || requestStr.contains("upgrade: websocket")) {
                handleWebSocketUpgrade(clientSocket, requestStr, out);
            } else if (requestStr.startsWith("GET")) {
                handleHttpRequest(clientSocket, requestStr, out);
            } else {
                sendHttpResponse(out, "HTTP/1.0 400 Bad Request", "text/plain", "Bad Request");
                clientSocket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
            try {
                clientSocket.close();
            } catch (Exception ignored) {}
        }
    }

    private void handleHttpRequest(java.net.Socket clientSocket, String request, java.io.OutputStream out) throws Exception {
        String path = extractPath(request);

        if ("/".equals(path) || "/index.html".equals(path)) {
            sendHttpResponse(out, "HTTP/1.0 200 OK", "text/html; charset=utf-8", getIndexHtml());
        } else if ("/api/info".equals(path)) {
            Map<String, Object> info = getServerInfo();
            sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(info));
        } else if ("/api/logs".equals(path)) {
            handleApiLogs(out, request);
        } else if ("/api/token".equals(path)) {
            Map<String, Object> result = new HashMap<>();
            result.put("token", token);
            result.put("port", port);
            result.put("protocol", PROTOCOL_VERSION);
            sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(result));
        } else if ("/health".equals(path)) {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "ok");
            health.put("connected_clients", webSockets.size());
            health.put("pending_logs", pendingLogs.size());
            sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(health));
        } else {
            sendHttpResponse(out, "HTTP/1.0 404 Not Found", "text/plain", "Not Found");
        }
        clientSocket.close();
    }

    private void handleApiLogs(java.io.OutputStream out, String request) throws Exception {
        String query = extractQueryString(request);
        if (query != null && query.contains("clear=true")) {
            LogCollector.getInstance().clear();
            Map<String, Object> result = new HashMap<>();
            result.put("status", "cleared");
            sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(result));
            return;
        }
        // 返回结构化日志数据
        List<LogCollector.LogEntry> structuredLogs = LogCollector.getInstance().getStructuredLogs();
        Map<String, Object> result = new HashMap<>();
        result.put("logs", structuredLogs);
        result.put("count", structuredLogs.size());
        result.put("timestamp", System.currentTimeMillis());
        sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(result));
    }

    private void handleWebSocketUpgrade(java.net.Socket clientSocket, String request, java.io.OutputStream out) throws Exception {
        String key = extractHeader(request, "Sec-WebSocket-Key");
        if (TextUtils.isEmpty(key)) {
            sendHttpResponse(out, "HTTP/1.0 400 Bad Request", "text/plain", "Missing WebSocket Key");
            clientSocket.close();
            return;
        }

        // 限制最大连接数
        if (webSockets.size() >= MAX_CLIENTS) {
            // 关闭最旧的连接
            WebSocket oldest = webSockets.get(0);
            oldest.close(1000, "Too many connections");
            webSockets.remove(0);
        }

        String acceptKey = computeWebSocketAccept(key);
        String upgradeResponse = "HTTP/1.0 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
                "\r\n";

        out.write(upgradeResponse.getBytes("UTF-8"));
        out.flush();

        // 立即关闭原始 socket，防止连接泄漏
        try {
            clientSocket.close();
        } catch (Exception ignored) {}

        Log.i(TAG, "WebSocket client connected, key=" + key);

        WebSocket webSocket = httpClient.newWebSocket(
                new Request.Builder().url("ws://localhost:" + port).build(),
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                        webSockets.add(webSocket);
                        sendInitMessage(webSocket);
                        sendPendingLogs(webSocket);
                        Log.i(TAG, "WebSocket client opened, total clients: " + webSockets.size());
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                        handleIncomingMessage(webSocket, text);
                    }

                    @Override
                    public void onClosing(WebSocket webSocket, int code, String reason) {
                        webSocket.close(1000, null);
                    }

                    @Override
                    public void onClosed(WebSocket webSocket, int code, String reason) {
                        webSockets.remove(webSocket);
                        Log.i(TAG, "WebSocket client closed, total clients: " + webSockets.size());
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                        webSockets.remove(webSocket);
                        Log.e(TAG, "WebSocket failure", t);
                    }
                });
    }

    private void sendInitMessage(WebSocket webSocket) {
        try {
            Map<String, Object> init = new HashMap<>();
            init.put("type", "init");
            init.put("protocol", PROTOCOL_VERSION);
            init.put("timestamp", System.currentTimeMillis());
            init.put("device", getServerInfo());
            webSocket.send(gson.toJson(init));
        } catch (Exception e) {
            Log.e(TAG, "Error sending init message", e);
        }
    }

    private void sendPendingLogs(WebSocket webSocket) {
        try {
            List<Map<String, Object>> logsToSend;
            synchronized (pendingLogs) {
                logsToSend = new ArrayList<>(pendingLogs);
                pendingLogs.clear();
            }
            if (!logsToSend.isEmpty()) {
                Map<String, Object> batch = new HashMap<>();
                batch.put("type", "batch_logs");
                batch.put("logs", logsToSend);
                batch.put("timestamp", System.currentTimeMillis());
                webSocket.send(gson.toJson(batch));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending pending logs", e);
        }
    }

    private void handleIncomingMessage(WebSocket webSocket, String text) {
        try {
            Map<String, Object> message = gson.fromJson(text, Map.class);
            if (message == null) return;

            String type = (String) message.get("type");
            if ("ping".equals(type)) {
                Map<String, Object> pong = new HashMap<>();
                pong.put("type", "pong");
                pong.put("timestamp", System.currentTimeMillis());
                webSocket.send(gson.toJson(pong));
            } else if ("subscribe".equals(type)) {
                String tokenValue = (String) message.get("token");
                if (token != null && token.equals(tokenValue)) {
                    Map<String, Object> ack = new HashMap<>();
                    ack.put("type", "subscribed");
                    ack.put("success", true);
                    ack.put("device", getServerInfo());
                    webSocket.send(gson.toJson(ack));
                } else {
                    Map<String, Object> ack = new HashMap<>();
                    ack.put("type", "error");
                    ack.put("message", "Invalid token");
                    webSocket.send(gson.toJson(ack));
                }
            } else if ("clear_logs".equals(type)) {
                LogCollector.getInstance().clear();
                Map<String, Object> ack = new HashMap<>();
                ack.put("type", "logs_cleared");
                ack.put("timestamp", System.currentTimeMillis());
                webSocket.send(gson.toJson(ack));
            } else if ("get_logs".equals(type)) {
                String allLogs = LogCollector.getInstance().getAllLogs();
                Map<String, Object> result = new HashMap<>();
                result.put("type", "logs_response");
                result.put("logs", allLogs);
                result.put("timestamp", System.currentTimeMillis());
                webSocket.send(gson.toJson(result));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming message", e);
        }
    }

    private void broadcastLog(LogCollector.LogEntry entry) {
        try {
            Map<String, Object> logMap = new HashMap<>();
            logMap.put("type", "log");
            logMap.put("timestamp", entry.timestamp);
            logMap.put("time", entry.time);
            logMap.put("tag", entry.tag);
            logMap.put("message", entry.message);
            logMap.put("logType", entry.type);
            logMap.put("deviceId", entry.deviceId);
            logMap.put("deviceName", entry.deviceName);
            logMap.put("deviceModel", entry.deviceModel);
            logMap.put("appVersion", entry.appVersion);

            String json = gson.toJson(logMap);

            boolean allSucceeded = true;
            for (WebSocket ws : webSockets) {
                try {
                    if (!ws.send(json)) {
                        allSucceeded = false;
                    }
                } catch (Exception e) {
                    allSucceeded = false;
                }
            }

            if (!allSucceeded) {
                synchronized (pendingLogs) {
                    pendingLogs.add(logMap);
                    if (pendingLogs.size() > 1000) {
                        pendingLogs.remove(0);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting log", e);
        }
    }

    private void broadcastLogCleared() {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "logs_cleared");
            msg.put("timestamp", System.currentTimeMillis());
            String json = gson.toJson(msg);
            for (WebSocket ws : webSockets) {
                try {
                    ws.send(json);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting log cleared", e);
        }
    }

    public void sendCrashLog(String crashLog) {
        try {
            Map<String, Object> logMap = new HashMap<>();
            logMap.put("type", "crash");
            logMap.put("timestamp", System.currentTimeMillis());
            logMap.put("time", sdf.format(new Date()));
            logMap.put("tag", "CrashHandler");
            logMap.put("message", crashLog);
            logMap.put("logType", LogCollector.TYPE_CRASH);
            logMap.put("deviceId", deviceId);
            logMap.put("deviceName", deviceName);
            logMap.put("deviceModel", deviceModel);
            logMap.put("appVersion", appVersion);

            String json = gson.toJson(logMap);
            for (WebSocket ws : webSockets) {
                try {
                    ws.send(json);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending crash log", e);
        }
    }

    private Map<String, Object> getServerInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("deviceId", deviceId);
        info.put("deviceName", deviceName);
        info.put("deviceModel", deviceModel);
        info.put("appVersion", appVersion);
        info.put("sdkVersion", Build.VERSION.SDK_INT);
        info.put("androidVersion", Build.VERSION.RELEASE);
        info.put("brand", Build.BRAND);
        info.put("manufacturer", Build.MANUFACTURER);
        info.put("hardware", Build.HARDWARE);
        info.put("protocol", PROTOCOL_VERSION);
        info.put("port", port);
        return info;
    }

    private String getIndexHtml() {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta charset=\"utf-8\">\n" +
                "    <title>TV Live Log Server</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }\n" +
                "        .container { max-width: 600px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n" +
                "        h1 { color: #333; }\n" +
                "        .info { background: #e8f4f8; padding: 15px; border-radius: 5px; margin: 15px 0; }\n" +
                "        .status { color: #4CAF50; font-weight: bold; }\n" +
                "        code { background: #f0f0f0; padding: 2px 5px; border-radius: 3px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <h1>📺 TV Live Log Server</h1>\n" +
                "        <p class=\"status\">● Running</p>\n" +
                "        <div class=\"info\">\n" +
                "            <h3>Device Info</h3>\n" +
                "            <p><strong>Device:</strong> " + deviceName + "</p>\n" +
                "            <p><strong>Model:</strong> " + deviceModel + "</p>\n" +
                "            <p><strong>Version:</strong> " + appVersion + "</p>\n" +
                "            <p><strong>Port:</strong> " + port + "</p>\n" +
                "        </div>\n" +
                "        <div class=\"info\">\n" +
                "            <h3>Connection Info</h3>\n" +
                "            <p><strong>WebSocket:</strong> <code>ws://" + getDeviceIpAddress() + ":" + port + "</code></p>\n" +
                "            <p><strong>HTTP API:</strong> <code>http://" + getDeviceIpAddress() + ":" + port + "/api/info</code></p>\n" +
                "        </div>\n" +
                "        <div class=\"info\">\n" +
                "            <h3>Token</h3>\n" +
                "            <p><code>" + token + "</code></p>\n" +
                "            <p style=\"color: #666; font-size: 12px;\">Use this token to connect from desktop client</p>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    private void sendHttpResponse(java.io.OutputStream out, String status, String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes("UTF-8");
        String response = status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        out.write(response.getBytes("UTF-8"));
        out.write(bodyBytes);
        out.flush();
    }

    private String extractPath(String request) {
        try {
            String[] lines = request.split("\r\n");
            if (lines.length > 0) {
                String[] parts = lines[0].split(" ");
                if (parts.length > 1) {
                    String fullPath = parts[1];
                    int queryIndex = fullPath.indexOf('?');
                    return queryIndex > 0 ? fullPath.substring(0, queryIndex) : fullPath;
                }
            }
        } catch (Exception ignored) {}
        return "/";
    }

    private String extractQueryString(String request) {
        try {
            String[] lines = request.split("\r\n");
            if (lines.length > 0) {
                String[] parts = lines[0].split(" ");
                if (parts.length > 1) {
                    String fullPath = parts[1];
                    int queryIndex = fullPath.indexOf('?');
                    return queryIndex > 0 ? fullPath.substring(queryIndex + 1) : null;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractHeader(String request, String headerName) {
        try {
            String[] lines = request.split("\r\n");
            for (String line : lines) {
                if (line.toLowerCase().startsWith(headerName.toLowerCase() + ":")) {
                    return line.substring(headerName.length() + 1).trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String computeWebSocketAccept(String key) {
        try {
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            String combined = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            byte[] digest = sha1.digest(combined.getBytes("UTF-8"));
            return java.util.Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return "";
        }
    }

    public String getDeviceIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP address", e);
        }
        return "127.0.0.1";
    }

    public int getPort() {
        return port;
    }

    public String getToken() {
        return token;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getConnectedClientCount() {
        return webSockets.size();
    }

    public void setPort(int port) {
        if (!isRunning) {
            this.port = port;
        }
    }

    public void regenerateToken() {
        generateToken();
    }
}
