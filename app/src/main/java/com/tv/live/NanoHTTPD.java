package com.tv.live;

import java.io.*;
import java.net.*;
import java.util.*;

public class NanoHTTPD {
    private ServerSocket serverSocket;
    private int port;
    private boolean running;

    public NanoHTTPD(int port) {
        this.port = port;
        serverSocket = null;
        running = false;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        new Thread(this::doListen).start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }

    private void doListen() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client)).start();
            } catch (Exception ignored) {
                break;
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();

            String line = in.readLine();
            if (line == null || !line.startsWith("GET ")) {
                send404(out);
                in.close();
                out.close();
                socket.close();
                return;
            }

            String path = line.split(" ")[1];
            if (path.equals("/")) {
                sendIndex(out);
            } else if (path.startsWith("/apply")) {
                sendSuccess(out);
            } else {
                send404(out);
            }

            in.close();
            out.close();
            socket.close();
        } catch (Exception ignored) {}
    }

    private void sendIndex(OutputStream out) throws Exception {
        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<body style='background:#111;color:#fff;padding:30px;font-size:16px;'>\n" +
                "<h2>TV 后台配置</h2>\n" +
                "<form action='/apply'>\n" +
                "<p>直播源地址：</p>\n" +
                "<input type='text' name='live' style='width:100%;padding:8px;font-size:16px;' />\n" +
                "<p>EPG节目单：</p>\n" +
                "<input type='text' name='epg' style='width:100%;padding:8px;font-size:16px;' />\n" +
                "<br/><br/>\n" +
                "<button type='submit' style='width:100%;padding:12px;font-size:18px;'>保存并刷新</button>\n" +
                "</form>\n" +
                "</body>\n" +
                "</html>";

        out.write(("HTTP/1.1 200 OK\r\nContent-Type:text/html;charset=UTF-8\r\n\r\n" + html).getBytes());
    }

    private void sendSuccess(OutputStream out) throws Exception {
        String html = "<h2 style='color:#0c0;'>保存成功！</h2>";
        out.write(("HTTP/1.1 200 OK\r\nContent-Type:text/html;charset=UTF-8\r\n\r\n" + html).getBytes());
    }

    private void send404(OutputStream out) throws Exception {
        out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
    }
}
