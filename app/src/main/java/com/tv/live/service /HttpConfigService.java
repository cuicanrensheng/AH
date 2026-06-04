package com.tv.live.service;

import com.tv.live.NanoHTTPD;
import android.content.SharedPreferences;
import com.tv.live.SettingsActivity;
import org.json.JSONObject;
import android.content.Context;

public class HttpConfigService extends NanoHTTPD {
    private static HttpConfigService instance;
    private final int PORT = 10481;
    private SharedPreferences sp;

    private HttpConfigService() {
        super(PORT);
    }

    public static HttpConfigService getInstance() {
        if (instance == null) {
            instance = new HttpConfigService();
        }
        return instance;
    }

    // 外部传入上下文获取SP
    public void setSp(SharedPreferences sp) {
        this.sp = sp;
    }

    public void startServer() {
        try {
            if (!isAlive()) {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            }
        } catch (Exception e) {
            stopServer();
        }
    }

    public void stopServer() {
        try {
            if (isAlive()) stop();
        } catch (Exception ignored) {}
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        try {
            // POST提交配置
            if (Method.POST.equals(method)) {
                Map<String, String> params = session.getParms();
                String jsonStr = params.get("data");
                JSONObject json = new JSONObject(jsonStr);
                String liveUrl = json.optString("live_url", "");
                String epgUrl = json.optString("epg_url", "");
                boolean needRefresh = false;

                // 保存直播源
                if (!liveUrl.isEmpty() && sp != null) {
                    sp.edit().putString("custom_live_url", liveUrl).apply();
                    SettingsActivity.addHistory("live_history", liveUrl);
                    needRefresh = true;
                }
                // 保存节目单
                if (!epgUrl.isEmpty() && sp != null) {
                    sp.edit().putString("custom_epg_url", epgUrl).apply();
                    SettingsActivity.addHistory("epg_history", epgUrl);
                    needRefresh = true;
                }
                // 发送全局刷新广播
                if (needRefresh) {
                    // 发送REFRESH广播
                    Context ctx = sp.getContext();
                    android.content.Intent intent = new android.content.Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                    ctx.sendBroadcast(intent);
                }
                return newFixedLengthResponse("{\"code\":200,\"msg\":\"配置保存成功\"}");
            }
            // 默认返回空白网页
            return newFixedLengthResponse("<html><body>直播源配置：POST提交data={live_url:'',epg_url:''}</body></html>");
        } catch (Exception e) {
            return newFixedLengthResponse("{\"code\":500,\"msg\":\"异常："+e.getMessage()+"\"}");
        }
    }
}
