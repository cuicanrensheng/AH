package com.tv.live.service;

import com.tv.live.NanoHTTPD;

public class HttpConfigService {

    private static HttpConfigService instance;
    private NanoHTTPD nanoHTTPD;
    private final int PORT = 10481;

    // 私有单例
    private HttpConfigService() {}

    // 单例获取
    public static HttpConfigService getInstance() {
        if (instance == null) {
            instance = new HttpConfigService();
        }
        return instance;
    }

    // ====================== 【修复核心】启动服务，防重复、防占用 ======================
    public void start() {
        try {
            // 如果服务已经在运行，直接不启动，避免端口冲突
            if (nanoHTTPD != null && nanoHTTPD.isAlive()) {
                return;
            }
            // 重新创建实例
            nanoHTTPD = new NanoHTTPD(PORT);
            nanoHTTPD.start();
        } catch (Exception e) {
            // 失败：强制释放，避免卡死
            stop();
        }
    }

    // ====================== 【修复核心】安全关闭，彻底释放端口 ======================
    public void stop() {
        try {
            if (nanoHTTPD != null) {
                nanoHTTPD.stop();
            }
        } catch (Exception e) {
            // 安全兜底
        } finally {
            // 强制置空，确保端口释放
            nanoHTTPD = null;
        }
    }

    // 判断服务是否运行
    public boolean isRunning() {
        return nanoHTTPD != null && nanoHTTPD.isAlive();
    }
}
