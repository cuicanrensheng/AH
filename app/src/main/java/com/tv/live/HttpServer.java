package com.tv.live;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class HttpServer extends NanoHTTPD {
    private final MainActivity mainActivity;

    public HttpServer(int port, MainActivity activity) {
        super(port);
        this.mainActivity = activity;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

        // 访问首页
        if ("/".equals(uri)) {
            try {
                InputStream is = getClass().getClassLoader().getResourceAsStream("public/settings.html");
                if (is == null) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
                }
                byte[] htmlBuf = new byte[is.available()];
                is.read(htmlBuf);
                is.close();
                return newFixedLengthResponse(Response.Status.OK, "text/html", new String(htmlBuf));
            } catch (Exception e) {
                e.printStackTrace();
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error");
            }
        }

        // 接收配置 POST 请求
        if ("/config".equals(uri) && Method.POST.equals(session.getMethod())) {
            try {
                // 读取请求体（替代 session.getBody()）
                InputStream inputStream = session.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String body = sb.toString();

                JSONObject json = new JSONObject(body);
                String liveUrl = json.optString("liveUrl", "");
                String epgUrl = json.optString("epgUrl", "");

                mainActivity.onReceiveNewConfig(liveUrl, epgUrl);

                JSONObject resp = new JSONObject();
                resp.put("success", true);
                return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
            } catch (Exception e) {
                e.printStackTrace();
                JSONObject resp = new JSONObject();
                resp.put("success", false);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", resp.toString());
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404");
    }
}
