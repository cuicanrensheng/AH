package com.tv.live.utils;

import android.content.Context;
import android.util.Log;
import fi.iki.elonen.NanoHTTPD;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebServer extends NanoHTTPD {
    private static final String TAG = "WebServer";
    private static final int PORT = 10481;
    private final Context context;

    public WebServer(Context context) {
        super(PORT);
        this.context = context.getApplicationContext();
    }

    // 去掉throws Exception，和父类保持一致
    @Override
    public void start() {
        super.start();
        Log.i(TAG, "Web Server started on port " + PORT);
    }

    @Override
    public void stop() {
        super.stop();
        Log.i(TAG, "Web Server stopped");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = session.getUri();
        Method method = session.getMethod();
        // 强制类型转换，解决类型不匹配
        Map<String, String> params = new HashMap<>((Map<String, String>) session.getParameters());

        Log.d(TAG, "Request: " + method + " " + path + ", params: " + params);

        try {
            switch (path) {
                case "/":
                    return serveIndexPage();
                case "/list":
                    return servePlaylistList();
                case "/add":
                    return handleAddPlaylist(params);
                case "/delete":
                    return handleDeletePlaylist(params);
                case "/export":
                    return serveExportData();
                default:
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
            }
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }

    private Response serveIndexPage() {
        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>IPTV后台管理</title>" +
                "<style>body{font-family:Arial;padding:20px;}.container{max-width:800px;margin:0 auto;}.btn{padding:8px 16px;margin:5px;cursor:pointer;}textarea{width:100%;height:200px;margin:10px 0;padding:10px;}.playlist-item{padding:10px;border:1px solid #ccc;margin:5px 0;display:flex;justify-content:space-between;align-items:center;}</style></head>" +
                "<body><div class='container'><h1>📺 IPTV 后台管理</h1><div><h3>操作菜单</h3>" +
                "<button class='btn' onclick=\"location.href='/list'\">查看所有订阅源</button>" +
                "<button class='btn' onclick=\"document.getElementById('addForm').style.display='block'\">新增订阅源</button>" +
                "<button class='btn' onclick=\"location.href='/export'\">导出数据</button></div>" +
                "<div id='addForm' style='display:none;margin-top:20px;border:1px solid #ccc;padding:15px;'>" +
                "<h4>新增订阅源</h4><input type='text' id='name' placeholder='订阅源名称' style='width:100%;padding:8px;margin:5px 0;'><br>" +
                "<textarea id='url' placeholder='订阅源地址/内容' style='width:100%;height:100px;padding:8px;margin:5px 0;'></textarea><br>" +
                "<button class='btn' onclick='addPlaylist()'>保存</button>" +
                "<button class='btn' onclick=\"document.getElementById('addForm').style.display='none'\">取消</button></div></div>" +
                "<script>function addPlaylist(){const name=document.getElementById('name').value;const url=document.getElementById('url').value;fetch('/add?name='+encodeURIComponent(name)+'&url='+encodeURIComponent(url)).then(res=>res.text()).then(text=>alert(text)).catch(err=>alert('添加失败: '+err));}</script></body></html>";
        return newFixedLengthResponse(Response.Status.OK, "text/html;charset=utf-8", html);
    }

    private Response servePlaylistList() {
        List<Playlist> playlists = PlaylistManager.getAllPlaylists();
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>订阅源列表</title></head><body><h1>所有订阅源</h1><a href='/'>返回首页</a><br><br>");
        for (Playlist playlist : playlists) {
            html.append("<div class='playlist-item'><div><strong>").append(playlist.getName()).append("</strong><br><small>").append(playlist.getUrl()).append("</small></div><div><button onclick=\"location.href='/delete?id=").append(playlist.getId()).append("'\">删除</button></div></div>");
        }
        html.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html;charset=utf-8", html.toString());
    }

    private Response handleAddPlaylist(Map<String, String> params) {
        String name = params.get("name");
        String url = params.get("url");
        if (name == null || url == null) {
            return newFixedLengthResponse("参数错误：缺少name或url");
        }
        Playlist playlist = new Playlist(System.currentTimeMillis(), name, url);
        PlaylistManager.addPlaylist(playlist);
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "添加成功！");
    }

    private Response handleDeletePlaylist(Map<String, String> params) {
        String idStr = params.get("id");
        if (idStr == null) {
            return newFixedLengthResponse("参数错误：缺少id");
        }
        long id = Long.parseLong(idStr);
        PlaylistManager.deletePlaylist(id);
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "删除成功！");
    }

    private Response serveExportData() {
        String data = PlaylistManager.exportAllData();
        Response response = newFixedLengthResponse(Response.Status.OK, "application/json", data);
        response.addHeader("Content-Disposition", "attachment; filename=\"iptv_backup.json\"");
        return response;
    }
}
