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

/**
 * 网页后台管理器
 *
 * 【职责】
 * 负责管理自建的 HTTP 服务器，包括：
 * 1. 启动/停止 ServerSocket
 * 2. 处理 HTTP 请求（GET/POST）
 * 3. 构建 HTML 页面（配置页/日志页/成功页）
 * 4. 保存配置到 SharedPreferences
 * 5. 输出日志到操作日志系统
 *
 * 【为什么拆分出来？】
 * SettingsActivity 里代码太多太杂，把网页后台独立成一个类，
 * 职责更清晰，代码更好维护。
 *
 * 【端口策略】
 * 从默认端口开始尝试，最多试 10 个端口，找到可用的就用。
 * 这样即使默认端口被占用，也能自动换一个端口启动。
 *
 * 【使用方式】
 * WebServerManager manager = new WebServerManager(context, port);
 * manager.start();  // 启动
 * manager.stop();   // 停止
 */
public class WebServerManager {

    // ====================== 常量 ======================

    /** SP 存储的 key */
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    private static final String KEY_CUSTOM_UA = "custom_user_agent";
    private static final String SP_NAME = "app_settings";

    // ====================== 成员变量 ======================

    /** 上下文 */
    private Context context;
    /** 端口号 */
    private int port;
    /** HTTP 服务器 Socket */
    private ServerSocket serverSocket;
    /** 主线程 Handler，用于子线程切主线程 */
    private Handler handler = new Handler(Looper.getMainLooper());
    /** 是否正在运行 */
    private boolean isRunning = false;

    // ====================================================================
    // ✅ 新增：当前运行的实例（静态变量）
    // ====================================================================
    /**
     * 当前正在运行的 WebServerManager 实例（静态）
     *
     * 【作用】
     * 用于端口占用检测时，找到并关闭之前的实例。
     * 因为 SettingsActivity 每次打开都会 new 一个新的 WebServerManager，
     * 如果旧的没关掉，就会端口冲突。
     * 用静态变量保存当前运行的实例，就能在启动新的之前先关掉旧的。
     *
     * 【为什么用静态而不是单例？】
     * 单例模式下全局只有一个实例，但 SettingsActivity 销毁重建时，
     * 旧的 context 可能已经失效，会有内存泄漏风险。
     * 用静态变量保存引用，每次启动新的之前检测并关闭旧的，
     * 既能解决端口冲突，又能保证每次都是新的实例、新的 context。
     */
    private static WebServerManager runningInstance;

    // ====================== 构造函数 ======================

    /**
     * 构造函数
     * @param context 上下文
     * @param port 端口号
     */
    public WebServerManager(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
    }

    // ====================== 公共方法 ======================

    // ====================================================================
    // ✅ 修改：start() 方法 - 加上自动找可用端口
    // ====================================================================
    /**
     * 启动 HTTP 服务器
     * 在子线程中运行，不会阻塞主线程
     *
     * 【完整启动流程】
     * 1. 检查是否已经在运行 → 是则直接返回
     * 2. 自动找可用端口（从默认端口开始，最多试 10 个）
     * 3. 在子线程中创建 ServerSocket，开始监听
     * 4. 保存当前实例到静态变量 runningInstance
     * 5. 循环接受连接，每个请求开一个线程处理
     *
     * 【为什么要自动找端口？】
     * 默认端口 10481 可能被其他应用占用，或者 APP 异常退出后
     * 端口处于 TIME_WAIT 状态暂时无法绑定。自动找可用端口能保证
     * 服务器总能启动成功，用户不需要手动改端口。
     *
     * 【二维码地址会变吗？】
     * 会变。如果换了端口，二维码里的地址端口号也会跟着变，
     * 但 currentWebUrl 会自动更新，所以扫码还是能正常打开。
     */
    public void start() {
        // ===== 1. 检查是否已经在运行 =====
        if (isRunning) {
            logOperation("【网页后台】已经在运行中，无需重复启动");
            return;
        }

        // ===== 2. 自动找可用端口 =====
        int actualPort = findAvailablePort(port);
        if (actualPort == -1) {
            // 试了 10 个端口都不行，启动失败
            logOperation("【网页后台】❌ 找不到可用端口，启动失败");
            logOperation("【网页后台】💡 建议：重启设备或检查网络设置");
            isRunning = false;
            return;
        }

        // 如果换了端口，打个日志说明一下
        if (actualPort != port) {
            logOperation("【网页后台】端口 " + port + " 被占用，自动改用端口 " + actualPort);
            this.port = actualPort;
        }

        final int finalPort = actualPort;

        // ===== 3. 在子线程中启动服务器 =====
        new Thread(() -> {
            try {
                logOperation("【网页后台】正在启动服务器，端口：" + finalPort);

                // ====================================================================
                // ✅ 修改：创建 ServerSocket 的方式
                // ====================================================================
                /**
                 * 【为什么要这样写？】
                 * setReuseAddress(true) 必须在 bind() 之前设置才有效。
                 *
                 * 原来的写法（错误）：
                 *   serverSocket = new ServerSocket(port);  // 构造函数里就绑定了
                 *   serverSocket.setReuseAddress(true);     // 这时候再设置已经晚了
                 *
                 * 现在的写法（正确）：
                 *   serverSocket = new ServerSocket();           // 先创建空的
                 *   serverSocket.setReuseAddress(true);          // 再设置 SO_REUSEADDR
                 *   serverSocket.bind(new InetSocketAddress(port));  // 最后绑定
                 *
                 * 【SO_REUSEADDR 的作用】
                 * 允许端口处于 TIME_WAIT 状态时重新绑定。
                 * APP 异常退出后，端口会进入 TIME_WAIT 状态（通常持续 1-2 分钟），
                 * 这时候如果没有 SO_REUSEADDR，重新绑定会失败。
                 * 有了 SO_REUSEADDR，就能立即复用这个端口。
                 */
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new java.net.InetSocketAddress(finalPort));

                isRunning = true;

                // ===== 4. 保存当前运行的实例（用于后续端口检测） =====
                runningInstance = this;

                logOperation("【网页后台】✅ 启动成功，监听端口：" + finalPort);
                logOperation("【网页后台】访问地址：http://" + getDeviceIPAddress() + ":" + finalPort);

                // ===== 5. 循环接受连接 =====
                while (!serverSocket.isClosed()) {
                    try {
                        // accept() 会阻塞，直到有新连接进来
                        Socket socket = serverSocket.accept();
                        logOperation("【网页后台】新连接进入：" + socket.getInetAddress());

                        // 每个请求开一个线程处理，避免阻塞其他请求
                        new Thread(() -> handleHttpRequest(socket)).start();
                    } catch (Exception e) {
                        // 正常关闭时也会抛异常（因为 serverSocket.close() 会中断 accept()）
                        // 这里判断一下，只有非正常关闭才打错误日志
                        if (!serverSocket.isClosed()) {
                            logOperation("【网页后台】接受连接异常：" + e.getMessage());
                        }
                    }
                }

                logOperation("【网页后台】服务器已停止");
                isRunning = false;
                runningInstance = null;

            } catch (Exception e) {
                e.printStackTrace();
                logOperation("【网页后台】❌ 启动失败：" + e.getClass().getSimpleName() + " - " + e.getMessage());
                isRunning = false;
                runningInstance = null;
            }
        }).start();
    }

    // ====================================================================
    // ✅ 修改：stop() 方法 - 同步清空静态变量
    // ====================================================================
    /**
     * 停止 HTTP 服务器
     * 释放端口资源
     *
     * 【停止流程】
     * 1. 检查 serverSocket 是否存在且未关闭
     * 2. 调用 serverSocket.close() 关闭连接
     *    （这会中断 accept() 的阻塞，让循环退出）
     * 3. 设置 isRunning = false
     * 4. 如果当前实例就是 runningInstance，清空静态引用
     *
     * 【为什么要判断 runningInstance == this？】
     * 可能存在多个实例的情况（比如旧的还没完全关掉，新的已经启动了），
     * 只清空属于自己的引用，避免误清掉新实例的引用。
     */
    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                isRunning = false;

                // 清空静态引用（只清空自己的，不误清新实例的）
                if (runningInstance == this) {
                    runningInstance = null;
                }

                logOperation("【网页后台】服务器已关闭");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ====================================================================
    // ✅ 新增：findAvailablePort() 方法 - 自动找可用端口
    // ====================================================================
    /**
     * 自动找可用端口
     *
     * 从 startPort 开始尝试，最多试 10 个端口，找到第一个可用的就返回。
     *
     * 【为什么需要这个方法？】
     * 默认端口可能被其他应用占用，或者处于 TIME_WAIT 状态。
     * 自动找可用端口能保证服务器总能启动成功。
     *
     * 【尝试范围】
     * startPort ~ startPort + 9（共 10 个端口）
     * 比如默认 10481，就会试 10481、10482、...、10490
     *
     * @param startPort 起始端口号
     * @return 可用的端口号，找不到返回 -1
     */
    private int findAvailablePort(int startPort) {
        int maxTry = 10;  // 最多试 10 个端口
        for (int i = 0; i < maxTry; i++) {
            int tryPort = startPort + i;
            if (!isPortInUse(tryPort)) {
                // 找到可用端口，直接返回
                return tryPort;
            }
        }
        // 试了 10 个都不行，返回 -1 表示失败
        return -1;
    }

    // ====================================================================
    // ✅ 修改：isPortInUse() 方法 - 正确设置 SO_REUSEADDR
    // ====================================================================
    /**
     * 检测端口是否被占用
     *
     * 【检测原理】
     * 尝试创建一个 ServerSocket 绑定到该端口：
     * - 如果绑定成功 → 说明端口空闲 → 返回 false
     * - 如果绑定失败（抛 BindException）→ 说明端口被占用 → 返回 true
     *
     * 检测完立即关闭，不影响后续正常启动。
     *
     * 【为什么不用 Socket 连接检测？】
     * 用 Socket 连接目标端口也能检测，但会发送一个 SYN 包，
     * 如果端口上有服务在运行，可能会触发一些异常行为。
     * 用 ServerSocket 绑定检测更"干净"，不会干扰正在运行的服务。
     *
     * 【重要：SO_REUSEADDR 的设置时机】
     * setReuseAddress(true) 必须在 bind() 之前设置才有效。
     * 所以要先创建空的 ServerSocket，设置完再绑定。
     *
     * @param port 端口号
     * @return true=被占用，false=空闲
     */
    private boolean isPortInUse(int port) {
        try {
            // 先创建空的 ServerSocket
            ServerSocket testSocket = new ServerSocket();
            // 再设置 SO_REUSEADDR（必须在 bind 之前）
            testSocket.setReuseAddress(true);
            // 最后绑定端口
            testSocket.bind(new java.net.InetSocketAddress(port));
            // 用完关掉
            testSocket.close();
            return false;  // 能绑定成功，说明端口空闲
        } catch (Exception e) {
            return true;   // 绑定失败（抛 BindException），说明端口被占用
        }
    }

    /**
     * 获取访问地址（用于生成二维码）
     * @return 完整的访问 URL
     */
    public String getAccessUrl() {
        return "http://" + getDeviceIPAddress() + ":" + port;
    }

    /**
     * 检查服务器是否正在运行
     */
    public boolean isRunning() {
        return isRunning;
    }

    // ====================== HTTP 请求处理 ======================

    /**
     * 处理单个 HTTP 请求
     *
     * 【完整流程】
     * 1. 按行读取请求头，读到空行结束
     * 2. 解析请求行（方法、路径、协议版本）
     * 3. 解析 Content-Length（POST 请求用）
     * 4. POST 请求：读取指定长度的 body
     * 5. 路由分发到对应页面
     * 6. 发送 HTTP 响应
     */
    private void handleHttpRequest(Socket socket) {
        try {
            logOperation("【网页后台】开始处理请求，客户端：" + socket.getInetAddress());

            // ===== 1. 读取请求头（按行读，读到空行结束） =====
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            List<String> headerLines = new ArrayList<>();
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                // 空行表示请求头结束（HTTP 协议规定）
                if (line.isEmpty()) {
                    break;
                }
                headerLines.add(line);
                // 防止恶意请求，最多读 100 行
                if (lineCount > 100) {
                    logOperation("【网页后台】请求头超过100行，停止读取");
                    break;
                }
            }
            logOperation("【网页后台】读取到 " + headerLines.size() + " 行请求头");

            // 请求为空，直接关闭连接
            if (headerLines.isEmpty()) {
                logOperation("【网页后台】请求为空，关闭连接");
                socket.close();
                return;
            }

            // ===== 2. 解析请求行 =====
            // 格式：GET /path HTTP/1.1
            String firstLine = headerLines.get(0);
            String[] parts = firstLine.split(" ");
            if (parts.length < 2) {
                logOperation("【网页后台】请求行格式错误：" + firstLine);
                sendResponse(socket, "400 Bad Request", "text/plain", "Bad Request");
                return;
            }
            String method = parts[0];  // GET / POST
            String path = parts[1];    // /  /log  /submit
            logOperation("【网页后台】请求：" + method + " " + path);

            // ===== 3. 解析 Content-Length（POST 请求用） =====
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
            logOperation("【网页后台】Content-Length: " + contentLength);

            // ===== 4. POST 请求：读取 body =====
            String body = "";
            if ("POST".equals(method) && contentLength > 0) {
                char[] bodyBuffer = new char[contentLength];
                int totalRead = 0;
                // 读满 contentLength 个字符就停，不会阻塞
                while (totalRead < contentLength) {
                    int len = reader.read(bodyBuffer, totalRead, contentLength - totalRead);
                    if (len <= 0) break;
                    totalRead += len;
                }
                body = new String(bodyBuffer, 0, totalRead);
                logOperation("【网页后台】POST body 内容：" + body);
            }

            // ===== 5. 路由分发 =====
            String responseBody = "";
            String contentType = "text/html; charset=utf-8";

            // 去掉 URL 里的查询参数（只取路径部分）
            String purePath = path.contains("?") ? path.split("\\?")[0] : path;

            // 5.1 配置页面（首页）
            if ("GET".equals(method) && ("/".equals(purePath) || "/index.html".equals(purePath))) {
                logOperation("【网页后台】→ 返回配置页面");
                responseBody = buildConfigPage();
            }
            // 5.2 日志页面
            else if ("GET".equals(method) && "/log".equals(purePath)) {
                logOperation("【网页后台】→ 返回日志页面");
                responseBody = buildLogPage();
            }
            // 5.3 提交配置（POST）
            else if ("POST".equals(method) && "/submit".equals(purePath)) {
                logOperation("【网页后台】→ 处理配置提交");
                Map<String, String> params = parseFormData(body);
                final String liveUrl = params.get("live_url");
                final String epgUrl = params.get("epg_url");
                final String customUa = params.get("custom_ua");
                logOperation("【网页后台】提交参数 - live: " + liveUrl + ", epg: " + epgUrl + ", ua: " + customUa);

                // 切到主线程保存配置（SP 和广播都要在主线程）
                handler.post(() -> {
                    boolean hasUpdate = false;
                    SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);

                    // 更新直播源
                    if (liveUrl != null && !liveUrl.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_LIVE, liveUrl.trim()).apply();
                        addHistory("live_history", liveUrl.trim());
                        hasUpdate = true;
                    }
                    // 更新节目单
                    if (epgUrl != null && !epgUrl.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_EPG, epgUrl.trim()).apply();
                        addHistory("epg_history", epgUrl.trim());
                        hasUpdate = true;
                    }
                    // 更新自定义 UA
                    if (customUa != null && !customUa.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_UA, customUa.trim()).apply();
                        hasUpdate = true;
                    }

                    // 有更新就发送广播刷新
                    if (hasUpdate) {
                        context.sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("【网页后台】配置已更新，发送刷新广播");
                    }
                });

                responseBody = buildSuccessPage();
            }
            // 5.4 404 页面
            else {
                logOperation("【网页后台】→ 404 Not Found: " + path);
                responseBody = "404 Not Found";
                contentType = "text/plain; charset=utf-8";
            }

            // ===== 6. 发送响应 =====
            logOperation("【网页后台】准备发送响应，内容长度：" + responseBody.getBytes("UTF-8").length + " 字节");
            sendResponse(socket, "200 OK", contentType, responseBody);
            logOperation("【网页后台】✅ 响应发送完成");

        } catch (Exception e) {
            e.printStackTrace();
            logOperation("【网页后台】❌ 处理请求异常：" + e.getClass().getSimpleName() + " - " + e.getMessage());
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 发送 HTTP 响应
     *
     * 【HTTP 响应格式】
     * 第一行：状态行（HTTP/1.1 200 OK）
     * 然后是响应头（Content-Type、Content-Length 等）
     * 空行
     * 响应体（HTML 内容）
     */
    private void sendResponse(Socket socket, String status, String contentType, String body) throws Exception {
        byte[] bodyBytes = body.getBytes("UTF-8");

        // 构建响应头
        String header = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +  // 告诉客户端发完就关，避免 keep-alive
                "\r\n";  // 空行分隔头和体

        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes("UTF-8"));
        out.write(bodyBytes);
        out.flush();

        logOperation("【网页后台】响应头+体已写入输出流");
        socket.close();
    }

    /**
     * 解析表单数据（application/x-www-form-urlencoded）
     * 格式：key1=value1&key2=value2
     */
    private Map<String, String> parseFormData(String body) {
        Map<String, String> params = new java.util.HashMap<>();
        if (body == null || body.isEmpty()) return params;
        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    // URL 解码，处理中文和特殊字符
                    String key = URLDecoder.decode(kv[0], "UTF-8");
                    String value = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
                    params.put(key, value);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            logOperation("【网页后台】解析表单数据失败：" + e.getMessage());
        }
        return params;
    }

    // ====================== HTML 页面构建 ======================

    /**
     * 构建配置页面 HTML（APP 风格，4个分组）
     *
     * 分组：
     * 1. 直播源 - 自定义直播源链接 + 推送按钮
     * 2. 节目单 - 自定义节目单链接 + 推送按钮
     * 3. 播放器 - 自定义UA + 推送按钮
     * 4. 调试 - 上传apk
     */
    private String buildConfigPage() {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        // 读取当前配置，回显到输入框
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
                "        .item input[type=text] { flex: 1; text-align: right; border: none; outline: none; font-size: 14px; color: #333; background: transparent; }\n" +
                "        .item input[type=text]::placeholder { color: #ccc; }\n" +
                "        .header-item { flex-direction: column; align-items: flex-start; padding: 16px; }\n" +
                "        .header-title { font-size: 17px; color: #333; font-weight: 500; margin-bottom: 4px; }\n" +
                "        .header-desc { font-size: 13px; color: #999; }\n" +
                "        .btn-blue { display: block; margin: 12px 12px 0; padding: 8px 20px; background: #40A9FF; color: white; border: none; border-radius: 6px; font-size: 14px; font-weight: 500; cursor: pointer; float: right; }\n" +
                "        .btn-blue:active { background: #1890FF; }\n" +
                "        .btn-wrap { overflow: hidden; padding: 0 0 12px; }\n" +
                "        .upload-box { width: 80px; height: 80px; border: 1px solid #eee; border-radius: 8px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; color: #ccc; cursor: pointer; margin-left: auto; }\n" +
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
                "    <!-- 1. 直播源分组 -->\n" +
                "    <div class=\"section-title\">直播源</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item header-item\">\n" +
                "            <div class=\"header-title\">自定义直播源</div>\n" +
                "            <div class=\"header-desc\">支持m3u、txt格式</div>\n" +
                "        </div>\n" +
                "        <form method=\"post\" action=\"/submit\">\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">链接</div>\n" +
                "                <input type=\"text\" name=\"live_url\" placeholder=\"直播源链接\" value=\"" + currentLive + "\">\n" +
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
                "                <div class=\"item-label\">链接</div>\n" +
                "                <input type=\"text\" name=\"epg_url\" placeholder=\"节目单链接\" value=\"" + currentEpg + "\">\n" +
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
                "            <div class=\"upload-box\" onclick=\"alert('功能开发中，敬请期待~')\">\n" +
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
                "</body>\n" +
                "</html>";
    }

    /**
     * 构建日志页面 HTML（APP 风格，支持 tab 切换）
     *
     * 两个 tab：
     * - 操作日志：OPERATION_LOG（用户操作 + 网页后台日志）
     * - 解析日志：PLAY_LOG（EPG解析 + 播放器日志）
     */
    private String buildLogPage() {
        // ===== 1. 构建操作日志 HTML =====
        String operationLogContent = SettingsActivity.OPERATION_LOG != null
                ? SettingsActivity.OPERATION_LOG.toString() : "";
        String[] opLines = operationLogContent.split("\n");
        StringBuilder opLogHtml = new StringBuilder();
        for (int i = opLines.length - 1; i >= 0; i--) {
            String line = opLines[i];
            if (line.trim().isEmpty()) continue;

            // 提取时间和内容
            String time = "";
            String content = line;
            if (line.startsWith("[") && line.contains("]")) {
                time = line.substring(1, line.indexOf("]"));
                content = line.substring(line.indexOf("]") + 1).trim();
            }

            // 判断级别（操作日志一般都是 INFO 级别，除了错误）
            boolean isError = content.contains("失败") || content.contains("异常") || content.contains("❌");
            String level = isError ? "ERROR" : "INFO";
            String levelColor = isError ? "#F5222D" : "#1890FF";
            String icon = isError ? "✕" : "⚙";  // 操作日志用齿轮图标

            opLogHtml.append("        <div class=\"log-item\">\n");
            opLogHtml.append("            <div class=\"log-icon\" style=\"background: ").append(levelColor).append(";\">").append(icon).append("</div>\n");
            opLogHtml.append("            <div class=\"log-content\">\n");
            opLogHtml.append("                <div class=\"log-level\" style=\"color: ").append(levelColor).append(";\">").append(level).append("</div>\n");
            opLogHtml.append("                <div class=\"log-text\">").append(content).append("</div>\n");
            opLogHtml.append("            </div>\n");
            opLogHtml.append("            <div class=\"log-time\">").append(time).append("</div>\n");
            opLogHtml.append("        </div>\n");
        }
        if (opLogHtml.length() == 0) {
            opLogHtml.append("        <div style=\"padding: 40px 20px; text-align: center; color: #999; font-size: 14px;\">暂无操作日志</div>\n");
        }

        // ===== 2. 构建解析日志 HTML =====
        String playLogContent = SettingsActivity.PLAY_LOG != null
                ? SettingsActivity.PLAY_LOG.toString() : "";
        String[] playLines = playLogContent.split("\n");
        StringBuilder playLogHtml = new StringBuilder();
        for (int i = playLines.length - 1; i >= 0; i--) {
            String line = playLines[i];
            if (line.trim().isEmpty()) continue;

            // 提取时间和内容
            String time = "";
            String content = line;
            if (line.startsWith("[") && line.contains("]")) {
                time = line.substring(1, line.indexOf("]"));
                content = line.substring(line.indexOf("]") + 1).trim();
            }

            // 判断级别
            boolean isError = content.contains("错误") || content.contains("失败")
                    || content.contains("异常") || content.contains("ERROR") || content.contains("❌");
            String level = isError ? "ERROR" : "INFO";
            String levelColor = isError ? "#F5222D" : "#1890FF";
            String icon = isError ? "✕" : "i";  // 解析日志用 i 图标

            playLogHtml.append("        <div class=\"log-item\">\n");
            playLogHtml.append("            <div class=\"log-icon\" style=\"background: ").append(levelColor).append(";\">").append(icon).append("</div>\n");
            playLogHtml.append("            <div class=\"log-content\">\n");
            playLogHtml.append("                <div class=\"log-level\" style=\"color: ").append(levelColor).append(";\">").append(level).append("</div>\n");
            playLogHtml.append("                <div class=\"log-text\">").append(content).append("</div>\n");
            playLogHtml.append("            </div>\n");
            playLogHtml.append("            <div class=\"log-time\">").append(time).append("</div>\n");
            playLogHtml.append("        </div>\n");
        }
        if (playLogHtml.length() == 0) {
            playLogHtml.append("        <div style=\"padding: 40px 20px; text-align: center; color: #999; font-size: 14px;\">暂无解析日志</div>\n");
        }

        // ===== 3. 完整页面（带 tab 切换） =====
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
                "        <div class=\"tab-item active\" onclick=\"switchTab('operation')\">操作日志</div>\n" +
                "        <div class=\"tab-item\" onclick=\"switchTab('play')\">解析日志</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 操作日志面板 -->\n" +
                "    <div id=\"panel-operation\" class=\"log-panel active\">\n" +
                "        <div class=\"log-list\">\n" +
                opLogHtml.toString() +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 解析日志面板 -->\n" +
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
                "        setTimeout(function() { location.reload(); }, 60000);\n" +
                "    </script>\n" +
                "\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * 构建提交成功页面
     */
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

    // ====================== 工具方法 ======================

    /**
     * 获取设备的 IP 地址
     */
    private String getDeviceIPAddress() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            int ip = info.getIpAddress();
            // 小端序转成正常的 IP 格式
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Exception e) {
            return "192.168.1.100";
        }
    }

    /**
     * 添加历史记录
     * 新添加的在最前面，去重，限制总长度
     */
    private void addHistory(String key, String url) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String history = sp.getString(key, "");
        StringBuilder sb = new StringBuilder();
        // 新的放最前面
        sb.append(url);
        if (!history.isEmpty()) {
            String[] arr = history.split("\\|");
            for (String s : arr) {
                // 去重 + 限制总长度
                if (!s.equals(url) && sb.length() < 1000) {
                    sb.append("|").append(s);
                }
            }
        }
        sp.edit().putString(key, sb.toString()).apply();
    }

    /**
     * 输出操作日志
     * 直接调用 SettingsActivity 的静态方法
     */
    private void logOperation(String msg) {
        SettingsActivity.logOperation(msg);
    }
}
