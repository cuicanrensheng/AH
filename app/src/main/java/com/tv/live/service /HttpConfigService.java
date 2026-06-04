package com.tv.live.service;

import com.tv.live.NanoHTTPD;

public class HttpConfigService {

    private static HttpConfigService instance;
    private NanoHTTPD nanoHTTPD;
    private final int PORT = 10481;

    private HttpConfigService() {}

    public static HttpConfigService getInstance() {
        if (instance == null) {
            instance = new HttpConfigService();
        }
        return instance;
    }

    // 修复：去掉 isAlive()，改用 null 判断，兼容你的 NanoHTTPD
    public void start() {
        try {
            // 已经启动就不再启动
            if (nanoHTTPD != null) {
                return;
            }
            nanoHTTPD = new NanoHTTPD(PORT);
            nanoHTTPD.start();
        } catch (Exception e) {
            e.printStackTrace();
            stop();
        }
    }

    public void stop() {
        try {
            if (nanoHTTPD != null) {
                nanoHTTPD.stop();
            }
        } catch (Exception e) {
            // 安全兜底，不崩溃
        } finally {
            nanoHTTPD = null;
        }
    }

    // 修复：无 isAlive()，所以只用 null 判断
    public boolean isRunning() {
        return nanoHTTPD != null;
    }
}
