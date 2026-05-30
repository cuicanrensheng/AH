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

    public void start() {
        try {
            if (nanoHTTPD == null) {
                nanoHTTPD = new NanoHTTPD(PORT);
            }
            nanoHTTPD.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        if (nanoHTTPD != null) {
            nanoHTTPD.stop();
            nanoHTTPD = null;
        }
    }
}
