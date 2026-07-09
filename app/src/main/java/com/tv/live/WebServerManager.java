package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 网页后台管理器
 *
 * 【职责】
 * 负责管理自建的 HTTP 服务器，包括：
 * 1. 启动/停止 ServerSocket
 * 2. 处理 HTTP 请求（GET/POST）
 * 3. 构建 HTML 页面（配置页/日志页/成功页）
 * 4. 保存配置到 SharedPreferences
 */
public class WebServerManager {

    // ====================== 常量 ======================
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    private static final String KEY_CUSTOM_UA = "custom_user_agent";
    private static final String SP_NAME = "app_settings";

    // 🟢 防刷新冷却时间
    private static final long SUBMIT_COOLDOWN = 2000; 

    // ====================== 成员变量 ======================
    private Context context;
    private int port;
    private ServerSocket serverSocket;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    private static WebServerManager runningInstance;

    // 🟢 记录最后一次提交配置的时间，用于防连点锁
    private long lastSubmitTime = 0;

    // ====================== 构造函数 ======================
    public WebServerManager(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
    }

    // ====================== 公共方法 ======================
    public void start() {
        if (isRunning) return;

        int actualPort = findAvailablePort(port);
        if (actualPort == -1) {
            isRunning = false;
            return;
        }
        this.port = actualPort;
        final int finalPort = actualPort;

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new java.net.InetSocketAddress(finalPort));

                isRunning = true;
                runningInstance = this;

                while (!serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        new Thread(() -> handleHttpRequest(socket)).start();
                    } catch (Exception e) {
                        if (!serverSocket.isClosed()) {
                            // 忽略正常关闭导致的异常
                        }
                    }
                }

                isRunning = false;
                runningInstance = null;

            } catch (Exception e) {
                e.printStackTrace();
                isRunning = false;
                runningInstance = null;
            }
        }).start();
    }

    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                isRunning = false;
                if (runningInstance == this) {
                    runningInstance = null;
                }
            }
            handler.removeCallbacksAndMessages(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int findAvailablePort(int startPort) {
        int maxTry = 10;
        for (int i = 0; i < maxTry; i++) {
            int tryPort = startPort + i;
            if (!isPortInUse(tryPort)) {
                return tryPort;
            }
        }
        return -1;
    }

    private boolean isPortInUse(int port) {
        try {
            ServerSocket testSocket = new ServerSocket();
            testSocket.setReuseAddress(true);
            testSocket.bind(new java.net.InetSocketAddress(port));
            testSocket.close();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public String getAccessUrl() {
        return "http://" + getDeviceIPAddress() + ":" + port;
    }

    public boolean isRunning() {
        return isRunning;
    }

    // ====================== HTTP 请求处理 ======================
    private void handleHttpRequest(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            List<String> headerLines = new ArrayList<>();
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (line.isEmpty()) break;
                headerLines.add(line);
                if (lineCount > 100) break;
            }

            if (headerLines.isEmpty()) {
                socket.close();
                return;
            }

            String firstLine = headerLines.get(0);
            String[] parts = firstLine.split(" ");
            if (parts.length < 2) {
                sendResponse(socket, "400 Bad Request", "text/plain", "Bad Request");
                return;
            }
            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            for (String headerLine : headerLines) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                    } catch (Exception e) {
                        contentLength = 0;
                    }
                    break;
                }
            }

            String body = "";
            if ("POST".equals(method) && contentLength > 0) {
                char[] bodyBuffer = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int len = reader.read(bodyBuffer, totalRead, contentLength - totalRead);
                    if (len <= 0) break;
                    totalRead += len;
                }
                body = new String(bodyBuffer, 0, totalRead);
            }

            String responseBody = "";
            String contentType = "text/html; charset=utf-8";
            String purePath = path.contains("?") ? path.split("\\?")[0] : path;

            if ("GET".equals(method) && ("/".equals(purePath) || "/index.html".equals(purePath))) {
                responseBody = buildConfigPage();
            } else if ("GET".equals(method) && "/log".equals(purePath)) {
                responseBody = buildLogPage();
            } else if ("POST".equals(method) && "/submit".equals(purePath)) {
                // 防连点锁
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastSubmitTime < SUBMIT_COOLDOWN) {
                    sendResponse(socket, "429 Too Many Requests", "text/plain", "操作过于频繁，请稍后再试！");
                    return;
                }
                lastSubmitTime = currentTime;

                Map<String, String> params = parseFormData(body);
                final String liveUrl = params.get("live_url");
                final String epgUrl = params.get("epg_url");
                final String customUa = params.get("custom_ua");

                handler.post(() -> {
                    boolean hasUpdate = false;
                    SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);

                    if (liveUrl != null && !liveUrl.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_LIVE, liveUrl.trim()).apply();
                        addHistory("live_history", liveUrl.trim());
                        hasUpdate = true;
                    }
                    if (epgUrl != null && !epgUrl.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_EPG, epgUrl.trim()).apply();
                        addHistory("epg_history", epgUrl.trim());
                        hasUpdate = true;
                    }
                    if (customUa != null && !customUa.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_UA, customUa.trim()).apply();
                        hasUpdate = true;
                    }

                    if (hasUpdate) {
                        context.sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    }
                });

                responseBody = buildSuccessPage();
            } else {
                responseBody = "404 Not Found";
                contentType = "text/plain; charset=utf-8";
            }

            sendResponse(socket, "200 OK", contentType, responseBody);

        } catch (Exception e) {
            e.printStackTrace();
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    private void sendResponse(Socket socket, String status, String contentType, String body) throws Exception {
        byte[] bodyBytes = body.getBytes("UTF-8");
        String header = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes("UTF-8"));
        out.write(bodyBytes);
        out.flush();
        socket.close();
    }

    private Map<String, String> parseFormData(String body) {
        Map<String, String> params = new java.util.HashMap<>();
        if (body == null || body.isEmpty()) return params;
        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    String key = URLDecoder.decode(kv[0], "UTF-8");
                    String value = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
                    params.put(key, value);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return params;
    }

    // ====================== HTML 页面构建 ======================

    /**
     * 构建配置页面 HTML
     * 🟢【新增】更新为远程/文件/静态三选一 UI
     */
    private String buildConfigPage() {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String currentLive = sp.getString(KEY_CUSTOM_LIVE, "");
        String currentEpg = sp.getString(KEY_CUSTOM_EPG, "");
        String currentUa = sp.getString(KEY_CUSTOM_UA, "");

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                "    <title>我的电视</title>\n" +
                "    <style>\n" +
                "        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif; background: #f5f5f5; color: #333; font-size: 14px; line-height: 1.5; padding-bottom: 60px; }\n" +
                "        .section-title { padding: 16px 16px 8px; font-size: 14px; color: #999; font-weight: normal; }\n" +
                "        .card { background: #fff; margin: 0 12px; }\n" +
                "        .card:first-of-type { border-radius: 12px 12px 0 0; }\n" +
                "        .card:last-of-type { border-radius: 0 0 12px 12px; }\n" +
                "        .item { display: flex; align-items: center; padding: 14px 16px; border-bottom: 1px solid #f0f0f0; min-height: 48px; }\n" +
                "        .item:last-child { border-bottom: none; }\n" +
                "        .item-label { flex-shrink: 0; width: 70px; color: #333; font-size: 15px; }\n" +
                "        .item input[type=text], .item textarea { flex: 1; text-align: right; border: none; outline: none; font-size: 14px; color: #333; background: transparent; }\n" +
                "        .item textarea { text-align: left; resize: none; height: 50px; }\n" +
                "        .item input[type=text]::placeholder { color: #ccc; }\n" +
                "        .header-item { flex-direction: column; align-items: flex-start; padding: 16px; }\n" +
                "        .header-title { font-size: 17px; color: #333; font-weight: 500; margin-bottom: 4px; }\n" +
                "        .header-desc { font-size: 13px; color: #999; }\n" +
                "        .btn-blue { display: block; margin: 12px 12px 0; padding: 8px 20px; background: #40A9FF; color: white; border: none; border-radius: 6px; font-size: 14px; font-weight: 500; cursor: pointer; float: right; }\n" +
                "        .btn-blue:active { background: #1890FF; }\n" +
                "        .btn-wrap { overflow: hidden; padding: 0 0 12px; }\n" +
                "        .upload-box { width: 80px; height: 80px; border: 1px solid #eee; border-radius: 8px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; color: #ccc; cursor: pointer; margin-left: auto; margin-top: 10px; }\n" +
                "        .upload-icon { font-size: 24px; }\n" +
                "        .upload-text { font-size: 11px; }\n" +
                "        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; display: flex; border-top: 1px solid #eee; padding: 8px 0 calc(8px + env(safe-area-inset-bottom)); }\n" +
                "        .nav-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; color: #999; font-size: 12px; cursor: pointer; text-decoration: none; }\n" +
                "        .nav-item.active { color: #40A9FF; }\n" +
                "        .nav-icon { font-size: 20px; line-height: 1; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "\n" +
                "    <!-- 1. 直播源分组 (适配远程/文件/静态) -->\n" +
                "    <div class=\"section-title\">直播源</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item header-item\">\n" +
                "            <div class=\"header-title\">自定义直播源</div>\n" +
                "            <div class=\"header-desc\">支持m3u、txt格式</div>\n" +
                "        </div>\n" +
                "        <form method=\"post\" action=\"/submit\">\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">类型</div>\n" +
                "                <div style=\"flex:1; display: flex; gap: 20px;\">\n" +
                "                    <label><input type=\"radio\" name=\"live_type\" value=\"remote\" checked onchange=\"toggleLiveInput('remote')\"> 远程</label>\n" +
                "                    <label><input type=\"radio\" name=\"live_type\" value=\"file\" onchange=\"toggleLiveInput('file')\"> 文件</label>\n" +
                "                    <label><input type=\"radio\" name=\"live_type\" value=\"static\" onchange=\"toggleLiveInput('static')\"> 静态</label>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">名称</div>\n" +
                "                <input type=\"text\" name=\"live_name\" placeholder=\"添加于 " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()) + "\" value=\"\">\n" +
                "            </div>\n" +
                "            <div id=\"live_remote_div\" class=\"item\">\n" +
                "                <div class=\"item-label\">链接</div>\n" +
                "                <input type=\"text\" name=\"live_url\" placeholder=\"直播源链接\" value=\"" + currentLive + "\">\n" +
                "            </div>\n" +
                "            <div id=\"live_file_div\" class=\"item\" style=\"display:none;\">\n" +
                "                <div class=\"item-label\">文件路径</div>\n" +
                "                <input type=\"text\" name=\"live_url\" placeholder=\"直播源文件路径\">\n" +
                "            </div>\n" +
                "            <div id=\"live_static_div\" class=\"item\" style=\"display:none;\">\n" +
                "                <div class=\"item-label\">内容</div>\n" +
                "                <div style=\"flex:1; display: flex; flex-direction: column; align-items: flex-end;\">\n" +
                "                    <textarea name=\"live_url\" placeholder=\"直播源内容\"></textarea>\n" +
                "                    <div class=\"upload-box\" onclick='alert(\"文件上传功能待实现\")'>\n" +
                "                        <div class=\"upload-icon\">📷</div>\n" +
                "                        <div class=\"upload-text\">上传</div>\n" +
                "                    </div>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "            <div class=\"btn-wrap\">\n" +
                "                <button type=\"submit\" class=\"btn-blue\">推送直播源</button>\n" +
                "            </div>\n" +
                "        </form>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 2. 节目单分组 -->\n" +
                "    <div class=\"section-title\">节目单</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item header-item\">\n" +
                "            <div class=\"header-title\">自定义节目单</div>\n" +
                "            <div class=\"header-desc\">支持xml、xml.gz格式</div>\n" +
                "        </div>\n" +
                "        <form method=\"post\" action=\"/submit\">\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">类型</div>\n" +
                "                <div style=\"flex:1; display: flex; gap: 20px;\">\n" +
                "                    <label><input type=\"radio\" name=\"epg_type\" value=\"remote\" checked onchange=\"toggleEpgInput('remote')\"> 远程</label>\n" +
                "                    <label><input type=\"radio\" name=\"epg_type\" value=\"file\" onchange=\"toggleEpgInput('file')\"> 文件</label>\n" +
                "                    <label><input type=\"radio\" name=\"epg_type\" value=\"static\" onchange=\"toggleEpgInput('static')\"> 静态</label>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">名称</div>\n" +
                "                <input type=\"text\" name=\"epg_name\" placeholder=\"添加于 " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()) + "\">\n" +
                "            </div>\n" +
                "            <div id=\"epg_remote_div\" class=\"item\">\n" +
                "                <div class=\"item-label\">链接</div>\n" +
                "                <input type=\"text\" name=\"epg_url\" placeholder=\"节目单链接\" value=\"" + currentEpg + "\">\n" +
                "            </div>\n" +
                "            <div id=\"epg_file_div\" class=\"item\" style=\"display:none;\">\n" +
                "                <div class=\"item-label\">文件路径</div>\n" +
                "                <input type=\"text\" name=\"epg_url\" placeholder=\"节目单文件路径\">\n" +
                "            </div>\n" +
                "            <div id=\"epg_static_div\" class=\"item\" style=\"display:none;\">\n" +
                "                <div class=\"item-label\">内容</div>\n" +
                "                <div style=\"flex:1; display: flex; flex-direction: column; align-items: flex-end;\">\n" +
                "                    <textarea name=\"epg_url\" placeholder=\"节目单内容\"></textarea>\n" +
                "                    <div class=\"upload-box\" onclick='alert(\"文件上传功能待实现\")'>\n" +
                "                        <div class=\"upload-icon\">📷</div>\n" +
                "                        <div class=\"upload-text\">上传</div>\n" +
                "                    </div>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "            <div class=\"btn-wrap\">\n" +
                "                <button type=\"submit\" class=\"btn-blue\">推送节目单</button>\n" +
                "            </div>\n" +
                "        </form>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 3. 播放器分组 -->\n" +
                "    <div class=\"section-title\">播放器</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item header-item\">\n" +
                "            <div class=\"header-title\">自定义UA</div>\n" +
                "            <div class=\"header-desc\">播放器自定义UA</div>\n" +
                "        </div>\n" +
                "        <form method=\"post\" action=\"/submit\">\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\"></div>\n" +
                "                <input type=\"text\" name=\"custom_ua\" placeholder=\"自定义 User-Agent\" value=\"" + currentUa + "\">\n" +
                "            </div>\n" +
                "            <div class=\"btn-wrap\">\n" +
                "                <button type=\"submit\" class=\"btn-blue\">推送</button>\n" +
                "            </div>\n" +
                "        </form>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 4. 调试分组 -->\n" +
                "    <div class=\"section-title\">调试</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item\">\n" +
                "            <div class=\"item-label\">上传apk</div>\n" +
                "            <div class=\"upload-box\" onclick='alert(\"功能开发中，敬请期待~\")'>\n" +
                "                <div class=\"upload-icon\">📷</div>\n" +
                "                <div class=\"upload-text\">上传</div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 底部导航 -->\n" +
                "    <div class=\"bottom-nav\">\n" +
                "        <a href=\"/\" class=\"nav-item active\">\n" +
                "            <div class=\"nav-icon\">🖥️</div>\n" +
                "            <div>配置</div>\n" +
                "        </a>\n" +
                "        <a href=\"/log\" class=\"nav-item\">\n" +
                "            <div class=\"nav-icon\">📋</div>\n" +
                "            <div>日志</div>\n" +
                "        </a>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 🟢 新增：动态切换输入框的脚本 -->\n" +
                "    <script>\n" +
                "        function toggleLiveInput(type) {\n" +
                "            document.getElementById('live_remote_div').style.display = 'none';\n" +
                "            document.getElementById('live_file_div').style.display = 'none';\n" +
                "            document.getElementById('live_static_div').style.display = 'none';\n" +
                "            if (type === 'remote') document.getElementById('live_remote_div').style.display = 'flex';\n" +
                "            else if (type === 'file') document.getElementById('live_file_div').style.display = 'flex';\n" +
                "            else if (type === 'static') document.getElementById('live_static_div').style.display = 'flex';\n" +
                "        }\n" +
                "        function toggleEpgInput(type) {\n" +
                "            document.getElementById('epg_remote_div').style.display = 'none';\n" +
                "            document.getElementById('epg_file_div').style.display = 'none';\n" +
                "            document.getElementById('epg_static_div').style.display = 'none';\n" +
                "            if (type === 'remote') document.getElementById('epg_remote_div').style.display = 'flex';\n" +
                "            else if (type === 'file') document.getElementById('epg_file_div').style.display = 'flex';\n" +
                "            else if (type === 'static') document.getElementById('epg_static_div').style.display = 'flex';\n" +
                "        }\n" +
                "        // 默认直播源选中远程，防止页面回显时样式错乱\n" +
                "        toggleLiveInput('remote');\n" +
                "        toggleEpgInput('remote');\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    // ====================== 日志页面 (不变) ======================
    private String buildLogPage() {
        String playLogContent = getSystemLogs();

        String[] playLines = playLogContent.split("\n");
        StringBuilder playLogHtml = new StringBuilder();
        for (int i = Math.min(playLines.length - 1, 500); i >= 0; i--) {
            String line = playLines[i];
            if (line == null || line.trim().isEmpty()) continue;

            String time = "";
            String content = line;
            if (line.contains(" ") && line.length() > 15) {
                int timeStart = line.indexOf(" ");
                int timeEnd = line.indexOf(" ", timeStart + 1);
                if (timeStart > 0 && timeEnd > timeStart) {
                    time = line.substring(timeStart, timeEnd).trim();
                    content = line.substring(timeEnd).trim();
                }
            }

            boolean isError = content.contains("错误") || content.contains("失败")
                    || content.contains("异常") || content.contains("ERROR") || content.contains("E/");
            String levelColor = isError ? "#F5222D" : "#1890FF";
            String icon = isError ? "✕" : "i";

            playLogHtml.append("        <div class=\"log-item\">\n");
            playLogHtml.append("            <div class=\"log-icon\" style=\"background: ").append(levelColor).append(";\">").append(icon).append("</div>\n");
            playLogHtml.append("            <div class=\"log-content\">\n");
            playLogHtml.append("                <div class=\"log-level\" style=\"color: ").append(levelColor).append(";\">").append(isError ? "ERROR" : "INFO").append("</div>\n");
            playLogHtml.append("                <div class=\"log-text\">").append(escapeHtml(content)).append("</div>\n");
            playLogHtml.append("            </div>\n");
            playLogHtml.append("            <div class=\"log-time\">").append(time).append("</div>\n");
            playLogHtml.append("        </div>\n");
        }
        if (playLogHtml.length() == 0) {
            playLogHtml.append("        <div style=\"padding: 40px 20px; text-align: center; color: #999; font-size: 14px;\">暂无原生日志</div>\n");
        }

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                "    <title>我的电视 - 日志</title>\n" +
                "    <style>\n" +
                "        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif; background: #fff; color: #333; font-size: 14px; line-height: 1.5; padding-bottom: 60px; }\n" +
                "        .header { padding: 20px 16px 12px; }\n" +
                "        .header-title { font-size: 32px; font-weight: 500; color: #000; margin-bottom: 8px; }\n" +
                "        .header-sub { font-size: 14px; color: #999; }\n" +
                "        .tab-bar { display: flex; border-bottom: 1px solid #f0f0f0; padding: 0 16px; }\n" +
                "        .tab-item { padding: 12px 20px; font-size: 15px; color: #666; cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; }\n" +
                "        .tab-item.active { color: #40A9FF; border-bottom-color: #40A9FF; font-weight: 500; }\n" +
                "        .log-panel { display: none; }\n" +
                "        .log-panel.active { display: block; }\n" +
                "        .log-list { padding: 0 16px; }\n" +
                "        .log-item { display: flex; align-items: flex-start; padding: 12px 0; border-bottom: 1px solid #f5f5f5; gap: 12px; }\n" +
                "        .log-icon { width: 20px; height: 20px; border-radius: 4px; display: flex; align-items: center; justify-content: center; color: white; font-size: 12px; font-weight: bold; flex-shrink: 0; margin-top: 2px; }\n" +
                "        .log-content { flex: 1; min-width: 0; }\n" +
                "        .log-level { font-size: 13px; font-weight: 500; margin-bottom: 4px; }\n" +
                "        .log-text { font-size: 14px; color: #333; word-break: break-all; line-height: 1.4; }\n" +
                "        .log-time { flex-shrink: 0; font-size: 12px; color: #999; margin-top: 2px; }\n" +
                "        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; display: flex; border-top: 1px solid #eee; padding: 8px 0 calc(8px + env(safe-area-inset-bottom)); }\n" +
                "        .nav-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; color: #999; font-size: 12px; cursor: pointer; text-decoration: none; }\n" +
                "        .nav-item.active { color: #40A9FF; }\n" +
                "        .nav-icon { font-size: 20px; line-height: 1; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "\n" +
                "    <!-- 头部 -->\n" +
                "    <div class=\"header\">\n" +
                "        <div class=\"header-title\">我的电视</div>\n" +
                "        <div class=\"header-sub\">http://" + getDeviceIPAddress() + ":" + port + "</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- Tab 切换 -->\n" +
                "    <div class=\"tab-bar\">\n" +
                "        <div class=\"tab-item active\" onclick=\"switchTab('operation')\">系统日志</div>\n" +
                "        <div class=\"tab-item\" onclick=\"switchTab('play')\">播放日志</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 原操作日志面板（现在统一展示原生日志） -->\n" +
                "    <div id=\"panel-operation\" class=\"log-panel active\">\n" +
                "        <div class=\"log-list\">\n" +
                "        <div style=\"padding: 40px 20px; text-align: center; color: #999; font-size: 14px;\">原生日志已合并展示，此处无额外操作日志</div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 播放日志面板（抓取 Logcat 替换原 PLAY_LOG） -->\n" +
                "    <div id=\"panel-play\" class=\"log-panel\">\n" +
                "        <div class=\"log-list\">\n" +
                playLogHtml.toString() +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 底部导航 -->\n" +
                "    <div class=\"bottom-nav\">\n" +
                "        <a href=\"/\" class=\"nav-item\">\n" +
                "            <div class=\"nav-icon\">🖥️</div>\n" +
                "            <div>配置</div>\n" +
                "        </a>\n" +
                "        <a href=\"/log\" class=\"nav-item active\">\n" +
                "            <div class=\"nav-icon\">📋</div>\n" +
                "            <div>日志</div>\n" +
                "        </a>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- Tab 切换脚本 -->\n" +
                "    <script>\n" +
                "        function switchTab(tabName) {\n" +
                "            document.querySelectorAll('.tab-item').forEach(function(item) {\n" +
                "                item.classList.remove('active');\n" +
                "            });\n" +
                "            event.target.classList.add('active');\n" +
                "            document.querySelectorAll('.log-panel').forEach(function(panel) {\n" +
                "                panel.classList.remove('active');\n" +
                "            });\n" +
                "            document.getElementById('panel-' + tabName).classList.add('active');\n" +
                "        }\n" +
                "    </script>\n" +
                "\n" +
                "</body>\n" +
                "</html>";
    }

    private String buildSuccessPage() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>保存成功</title>\n" +
                "    <style>\n" +
                "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif; background: #f5f5f5; padding: 20px; display: flex; justify-content: center; align-items: center; min-height: 100vh; }\n" +
                "        .container { max-width: 400px; background: white; border-radius: 12px; padding: 32px 24px; text-align: center; box-shadow: 0 2px 12px rgba(0,0,0,0.1); }\n" +
                "        .icon { font-size: 48px; margin-bottom: 16px; }\n" +
                "        h2 { font-size: 20px; color: #333; margin-bottom: 12px; }\n" +
                "        p { font-size: 14px; color: #666; margin-bottom: 24px; }\n" +
                "        a { display: inline-block; padding: 10px 24px; background: #40A9FF; color: white; text-decoration: none; border-radius: 8px; font-size: 14px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <div class=\"icon\">✅</div>\n" +
                "        <h2>配置保存成功！</h2>\n" +
                "        <p>配置已更新，电视端正在刷新...</p>\n" +
                "        <a href=\"/\">返回继续修改</a>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    // ============================================================
    // 🟢【优化】调用原生 logcat 抓取系统日志，增加超时保护
    // ============================================================
    private String getSystemLogs() {
        StringBuilder logResult = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec("logcat -d -v time -t 500");
            boolean completed = process.waitFor(2000, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroy();
                return "日志抓取超时，请稍后重试...";
            }

            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                logResult.append(line).append("\n");
            }
        } catch (Exception e) {
            return "加载日志失败：" + e.getMessage();
        }
        return logResult.toString();
    }

    private String escapeHtml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    private String getDeviceIPAddress() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            int ip = info.getIpAddress();
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Exception e) {
            return "192.168.1.100";
        }
    }

    private void addHistory(String key, String url) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String history = sp.getString(key, "");
        StringBuilder sb = new StringBuilder();
        sb.append(url);
        if (!history.isEmpty()) {
            String[] arr = history.split("\\|");
            for (String s : arr) {
                if (!s.equals(url) && sb.length() < 1000) {
                    sb.append("|").append(s);
                }
            }
        }
        sp.edit().putString(key, sb.toString()).apply();
    }
}
