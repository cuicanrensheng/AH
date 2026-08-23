package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 云端日志发送器
 * 将日志发送到远程云端服务器，支持实时推送和批量发送
 */
public class CloudLogSender {
    private static final String TAG = "CloudLogSender";
    private static final String PREFS_NAME = "cloud_log_config";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_ENABLED = "enabled";
    
    private static final int MAX_BATCH_SIZE = 50;
    private static final long SEND_INTERVAL_MS = 2000;
    private static final int MAX_RETRY_COUNT = 3;
    
    private static volatile CloudLogSender sInstance;
    
    private final Context context;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final SharedPreferences prefs;
    
    private WebSocket cloudWebSocket;
    private final CopyOnWriteArrayList<LogCollector.LogEntry> pendingLogs;
    private final CopyOnWriteArrayList<WebSocket> cloudListeners;
    
    private volatile boolean isEnabled;
    private volatile boolean isConnecting;
    private volatile boolean isConnected;
    private Thread sendThread;
    private Thread reconnectThread;
    private int retryCount;
    
    private String deviceId;
    private String deviceName;
    private String deviceModel;
    private String appVersion;
    
    private CloudLogSender(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new GsonBuilder().serializeNulls().create();
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.pendingLogs = new CopyOnWriteArrayList<>();
        this.cloudListeners = new CopyOnWriteArrayList<>();
        this.retryCount = 0;
        
        loadConfig();
        initDeviceInfo();
        setupLogListener();
    }
    
    public static CloudLogSender getInstance(Context context) {
        if (sInstance == null) {
            synchronized (CloudLogSender.class) {
                if (sInstance == null) {
                    sInstance = new CloudLogSender(context);
                }
            }
        }
        return sInstance;
    }
    
    private void loadConfig() {
        isEnabled = prefs.getBoolean(KEY_ENABLED, false);
    }
    
    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        
        if (enabled) {
            connectToCloud();
        } else {
            disconnectFromCloud();
        }
    }
    
    public boolean isEnabled() {
        return isEnabled;
    }
    
    public void setServerUrl(String serverUrl) {
        prefs.edit().putString(KEY_SERVER_URL, serverUrl).apply();
    }
    
    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, "");
    }
    
    public void setApiKey(String apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply();
    }
    
    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }
    
    public boolean isConnected() {
        return isConnected && cloudWebSocket != null;
    }
    
    private void initDeviceInfo() {
        try {
            deviceId = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), 
                    android.provider.Settings.Secure.ANDROID_ID);
            if (TextUtils.isEmpty(deviceId)) {
                deviceId = "unknown_" + android.os.Build.SERIAL;
            }
            deviceModel = android.os.Build.MODEL;
            deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
            
            try {
                android.content.pm.PackageManager pm = context.getPackageManager();
                android.content.pm.PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
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
    
    private void setupLogListener() {
        LogCollector.getInstance().addLogListener(new LogCollector.LogListener() {
            @Override
            public void onLogAdded(LogCollector.LogEntry entry) {
                if (isEnabled) {
                    addLogToQueue(entry);
                }
            }
            
            @Override
            public void onLogCleared() {
                // 不处理
            }
        });
    }
    
    public void start() {
        if (!isEnabled) return;
        
        connectToCloud();
        startSendLoop();
    }
    
    public void stop() {
        disconnectFromCloud();
        
        if (sendThread != null) {
            sendThread.interrupt();
            sendThread = null;
        }
        if (reconnectThread != null) {
            reconnectThread.interrupt();
            reconnectThread = null;
        }
    }
    
    private void connectToCloud() {
        if (isConnecting || isConnected) return;
        
        String serverUrl = getServerUrl();
        if (TextUtils.isEmpty(serverUrl)) {
            Log.w(TAG, "Cloud server URL not configured");
            return;
        }
        
        isConnecting = true;
        
        try {
            // 先注册设备
            registerDevice();
            
            // 建立 WebSocket 连接
            String wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws?deviceId=" + deviceId;
            String apiKey = getApiKey();
            if (!TextUtils.isEmpty(apiKey)) {
                wsUrl += "&apiKey=" + apiKey;
            }
            
            Request request = new Request.Builder()
                    .url(wsUrl)
                    .build();
            
            cloudWebSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    super.onOpen(webSocket, response);
                    isConnected = true;
                    isConnecting = false;
                    retryCount = 0;
                    Log.i(TAG, "Connected to cloud server: " + serverUrl);
                }
                
                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    super.onMessage(webSocket, text);
                    handleCloudMessage(text);
                }
                
                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                    super.onMessage(webSocket, bytes);
                }
                
                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    super.onClosing(webSocket, code, reason);
                    webSocket.close(1000, null);
                }
                
                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    super.onClosed(webSocket, code, reason);
                    isConnected = false;
                    isConnecting = false;
                    Log.w(TAG, "Disconnected from cloud server");
                    scheduleReconnect();
                }
                
                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    super.onFailure(webSocket, t, response);
                    isConnected = false;
                    isConnecting = false;
                    Log.e(TAG, "WebSocket failure: " + t.getMessage(), t);
                    scheduleReconnect();
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to cloud server", e);
            isConnecting = false;
            scheduleReconnect();
        }
    }
    
    private void registerDevice() {
        try {
            String serverUrl = getServerUrl();
            String url = serverUrl + "/api/device/register";
            
            android.os.Bundle deviceInfo = new android.os.Bundle();
            deviceInfo.putString("deviceId", deviceId);
            deviceInfo.putString("deviceName", deviceName);
            deviceInfo.putString("deviceModel", deviceModel);
            deviceInfo.putString("appVersion", appVersion);
            deviceInfo.putString("brand", android.os.Build.BRAND);
            deviceInfo.putString("manufacturer", android.os.Build.MANUFACTURER);
            
            String json = gson.toJson(deviceInfo);
            RequestBody body = RequestBody.create(json, okhttp3.MediaType.parse("application/json"));
            
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(body);
            
            String apiKey = getApiKey();
            if (!TextUtils.isEmpty(apiKey)) {
                requestBuilder.addHeader("x-api-key", apiKey);
            }
            
            httpClient.newCall(requestBuilder.build()).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    Log.w(TAG, "Device registration failed: " + e.getMessage());
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) throws java.io.IOException {
                    try {
                        if (response.isSuccessful()) {
                            Log.i(TAG, "Device registered successfully");
                        } else {
                            Log.w(TAG, "Device registration failed: " + response.code());
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to register device", e);
        }
    }
    
    private void handleCloudMessage(String message) {
        try {
            android.os.Bundle bundle = gson.fromJson(message, android.os.Bundle.class);
            String type = bundle.getString("type");
            
            if ("ping".equals(type)) {
                // 回复 pong
                if (cloudWebSocket != null) {
                    android.os.Bundle pongBundle = new android.os.Bundle();
                    pongBundle.putString("type", "pong");
                    pongBundle.putLong("timestamp", System.currentTimeMillis());
                    cloudWebSocket.send(gson.toJson(pongBundle));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle cloud message", e);
        }
    }
    
    private void scheduleReconnect() {
        if (isEnabled && retryCount < MAX_RETRY_COUNT * 10) {
            retryCount++;
            long delay = (long) Math.min(1000 * Math.pow(2, retryCount / 3), 30000);
            
            if (reconnectThread != null) {
                reconnectThread.interrupt();
            }
            
            reconnectThread = new Thread(() -> {
                try {
                    Thread.sleep(delay);
                    if (isEnabled) {
                        Log.i(TAG, "Reconnecting to cloud server... (attempt " + retryCount + ")");
                        connectToCloud();
                    }
                } catch (InterruptedException ignored) {}
            });
            reconnectThread.setName("CloudReconnect-" + retryCount);
            reconnectThread.start();
        } else if (retryCount >= MAX_RETRY_COUNT * 10) {
            Log.e(TAG, "Max retry count reached. Stopping cloud connection.");
        }
    }
    
    private void disconnectFromCloud() {
        if (cloudWebSocket != null) {
            try {
                cloudWebSocket.close(1000, "Client disconnecting");
            } catch (Exception ignored) {}
            cloudWebSocket = null;
        }
        isConnected = false;
        isConnecting = false;
    }
    
    private void addLogToQueue(LogCollector.LogEntry entry) {
        if (!isEnabled) return;
        
        if (pendingLogs.size() >= 5000) {
            pendingLogs.remove(0);
        }
        pendingLogs.add(entry);
    }
    
    private void startSendLoop() {
        if (sendThread != null) return;
        
        sendThread = new Thread(() -> {
            try {
                Thread.sleep(SEND_INTERVAL_MS);
                
                while (isEnabled && !Thread.currentThread().isInterrupted()) {
                    if (pendingLogs.size() >= 5 && isConnected) {
                        sendBatchLogs();
                    } else if (pendingLogs.size() >= 1 && !isConnected && getServerUrl() != null) {
                        // 尝试通过 HTTP 发送
                        sendHttpLogs();
                    }
                    
                    Thread.sleep(SEND_INTERVAL_MS);
                }
            } catch (InterruptedException ignored) {}
        });
        sendThread.setName("CloudLogSender");
        sendThread.start();
    }
    
    private void sendBatchLogs() {
        try {
            if (!isConnected || cloudWebSocket == null) return;
            if (pendingLogs.isEmpty()) return;
            
            List<LogCollector.LogEntry> batch = new ArrayList<>();
            int count = Math.min(MAX_BATCH_SIZE, pendingLogs.size());
            
            for (int i = 0; i < count; i++) {
                LogCollector.LogEntry log = pendingLogs.get(0);
                if (cloudWebSocket.send(gson.toJson(log))) {
                    batch.add(log);
                    pendingLogs.remove(0);
                }
            }
            
            if (!batch.isEmpty()) {
                Log.d(TAG, "Sent " + batch.size() + " logs via WebSocket");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send batch logs", e);
        }
    }
    
    private void sendHttpLogs() {
        try {
            String serverUrl = getServerUrl();
            if (TextUtils.isEmpty(serverUrl)) return;
            if (pendingLogs.isEmpty()) return;
            
            List<LogCollector.LogEntry> batch = new ArrayList<>();
            int count = Math.min(MAX_BATCH_SIZE, pendingLogs.size());
            
            for (int i = 0; i < count; i++) {
                batch.add(pendingLogs.get(i));
            }
            
            String url = serverUrl + "/api/logs/" + deviceId;
            
            java.util.Map<String, Object> payloadMap = new java.util.HashMap<>();
            payloadMap.put("logs", batch);
            String json = gson.toJson(payloadMap);
            
            RequestBody body = RequestBody.create(json, okhttp3.MediaType.parse("application/json"));
            
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(body);
            
            String apiKey = getApiKey();
            if (!TextUtils.isEmpty(apiKey)) {
                requestBuilder.addHeader("x-api-key", apiKey);
            }
            
            final int sentCount = count;
            httpClient.newCall(requestBuilder.build()).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    Log.w(TAG, "Failed to send HTTP logs: " + e.getMessage());
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) throws java.io.IOException {
                    try {
                        if (response.isSuccessful()) {
                            for (int i = 0; i < sentCount; i++) {
                                pendingLogs.remove(0);
                            }
                            Log.d(TAG, "Sent " + sentCount + " logs via HTTP");
                        } else {
                            Log.w(TAG, "Failed to send logs: HTTP " + response.code());
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to send HTTP logs", e);
        }
    }
    
    public void clearPendingLogs() {
        pendingLogs.clear();
    }
    
    public int getPendingLogCount() {
        return pendingLogs.size();
    }
    
    public String getStatusInfo() {
        String status = isEnabled ? (isConnected ? "connected" : "connecting") : "disabled";
        return String.format("Cloud: %s, Pending: %d, Server: %s",
                status, pendingLogs.size(), getServerUrl());
    }
    
    /**
     * 发送自定义事件（如篡改检测事件）
     * @param eventName 事件名称
     * @param eventData 事件数据
     */
    public void sendEvent(String eventName, Map<String, String> eventData) {
        if (!isEnabled) return;
        
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("event", eventName);
            payload.put("data", eventData);
            payload.put("device_id", deviceId);
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("app_version", appVersion);
            
            String serverUrl = getServerUrl();
            if (serverUrl == null || serverUrl.isEmpty()) {
                Log.w(TAG, "无法发送事件：服务器URL未配置");
                return;
            }
            
            String url = serverUrl + "/api/events";
            String json = gson.toJson(payload);
            
            RequestBody body = RequestBody.create(json, okhttp3.MediaType.parse("application/json"));
            
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(body);
            
            String apiKey = getApiKey();
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.addHeader("x-api-key", apiKey);
            }
            
            httpClient.newCall(requestBuilder.build()).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    Log.e(TAG, "发送事件失败: " + e.getMessage());
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) throws java.io.IOException {
                    try {
                        if (response.isSuccessful()) {
                            Log.i(TAG, "事件已发送: " + eventName);
                        } else {
                            Log.w(TAG, "发送事件失败: HTTP " + response.code());
                        }
                    } finally {
                        response.close();
                    }
                }
            });
            
            // 同时通过WebSocket发送（如果已连接）
            if (isConnected && cloudWebSocket != null) {
                try {
                    Map<String, Object> wsPayload = new HashMap<>();
                    wsPayload.put("type", "event");
                    wsPayload.put("event", eventName);
                    wsPayload.put("data", eventData);
                    wsPayload.put("timestamp", System.currentTimeMillis());
                    cloudWebSocket.send(gson.toJson(wsPayload));
                } catch (Exception e) {
                    Log.e(TAG, "WebSocket发送事件失败: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "发送事件异常: " + e.getMessage());
        }
    }
}
