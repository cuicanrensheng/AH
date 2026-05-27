package com.tv.live;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;

public class HttpServer extends NanoHTTPD {
    private final MainActivity mainActivity;

    public HttpServer(int port, MainActivity activity) {
        super(port);
        this.mainActivity = activity;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

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

        if ("/config".equals(uri) && Method.POST.equals(session.getMethod())) {
            try {
                session.parseBody(null);
                String body = session.getBody();
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
