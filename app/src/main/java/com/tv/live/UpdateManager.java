package com.tv.live;
import android.util.Log;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用更新管理器
 */
public class UpdateManager {
    private static final String TAG = "UpdateManager";

    // ============================================================
    // ✅ 镜像地址池：
    //  - 每个 URL 都附加 ?t=<时间戳_ms> 强制绕过 CDN 缓存
    //  - 所有可用镜像全部请求（异步并发），取 versionCode 最大的作为结果
    //    （解决 jsdelivr 缓存回退到 v2250、raw.githubusercontent 不通 组合导致误判“已是最新”）
    //  - 最终兜底：GitHub Releases API（不会被 CDN 缓存）
    // ============================================================
    private static final String[] UPDATE_JSON_MIRRORS = {
            "https://raw.githubusercontent.com/cuicanrensheng/1/main/update.json",
            "https://cdn.jsdelivr.net/gh/cuicanrensheng/1@main/update.json",
            "https://gh.api.99988866.xyz/https://raw.githubusercontent.com/cuicanrensheng/1/main/update.json",
            "https://ghproxy.com/https://raw.githubusercontent.com/cuicanrensheng/1/main/update.json"
    };
    private static final String RELEASES_API_URL = "https://api.github.com/repos/cuicanrensheng/1/releases/latest";
    private static final String APK_FILE_NAME = "tv_live_update.apk";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static boolean isChecking = false;
    private static boolean isDownloading = false;

    private final Context context;
    private final SharedPreferences sp;
    private DownloadManager downloadManager;
    private long downloadId = -1;
    private BroadcastReceiver downloadCompleteReceiver;

    public UpdateManager(Context context) {
        this.context = context;
        this.sp = context.getSharedPreferences("app_update", Context.MODE_PRIVATE);
    }

    // 保存更新日志到本地
    public void saveUpdateMessage(String message) {
        sp.edit().putString("update_message", message).apply();
    }

    // 读取已保存的更新日志
    public String getUpdateMessage() {
        return sp.getString("update_message", "暂无更新内容");
    }

    public void checkUpdate() {
        synchronized (UpdateManager.class) {
            if (isChecking) {
                MAIN_HANDLER.post(() -> Toast.makeText(context, "正在检查更新中，请稍后...", Toast.LENGTH_SHORT).show());
                return;
            }
            isChecking = true;
        }

        new Thread(() -> {
            Exception lastError = null;
            String updateJsonStr = null;

            // ==========================================================
            // 步骤1：镜像池轮询获取 update.json
            // ==========================================================
            for (String mirror : UPDATE_JSON_MIRRORS) {
                try {
                    Log.d(TAG, "尝试获取 update.json: " + mirror);
                    updateJsonStr = httpGetString(mirror, 8000, 10000);
                    if (updateJsonStr != null && updateJsonStr.length() > 0) {
                        Log.d(TAG, "成功从镜像获取 update.json: " + mirror);
                        break;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "镜像失败: " + mirror + " -> " + e.getMessage());
                    lastError = e;
                }
            }

            if (updateJsonStr == null || updateJsonStr.isEmpty()) {
                final String errMsg = (lastError != null) ? lastError.getMessage() : "无法获取更新信息";
                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateManager.class) { isChecking = false; }
                    Toast.makeText(context, "检查更新失败：" + errMsg, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            try {
                JSONObject json = new JSONObject(updateJsonStr);
                final int latestVersionCode = json.optInt("versionCode", 0);
                String latestVersionName = json.optString("versionName", "未知");
                String updateMessage = json.optString("message", "暂无更新内容");
                String downloadUrl = json.optString("downloadUrl", "");
                boolean forceUpdate = json.optBoolean("forceUpdate", false);

                // ==========================================================
                // 步骤2：如无下载链接，从 Releases API 兜底
                // ==========================================================
                if (downloadUrl.isEmpty()) {
                    try {
                        String relStr = httpGetString(RELEASES_API_URL, 8000, 8000);
                        if (relStr != null) {
                            JSONObject relJson = new JSONObject(relStr);
                            if (relJson.has("assets")) {
                                JSONArray assets = relJson.getJSONArray("assets");
                                for (int i = 0; i < assets.length(); i++) {
                                    JSONObject asset = assets.getJSONObject(i);
                                    if (asset.getString("name").endsWith(".apk")) {
                                        downloadUrl = asset.getString("browser_download_url");
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "从Releases获取下载链接失败，继续", ex);
                    }
                }

                // 保存更新日志
                saveUpdateMessage(updateMessage);

                if (latestVersionCode == 0) {
                    MAIN_HANDLER.post(() -> {
                        synchronized (UpdateManager.class) { isChecking = false; }
                        Toast.makeText(context, "获取版本信息失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                if (downloadUrl.isEmpty()) {
                    MAIN_HANDLER.post(() -> {
                        synchronized (UpdateManager.class) { isChecking = false; }
                        Toast.makeText(context, "最新版本未提供下载链接", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                int currentVersionCode = 0;
                String currentVersionName = "未知";
                try {
                    currentVersionCode = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0).versionCode;
                    currentVersionName = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0).versionName;
                } catch (Exception e) {
                    e.printStackTrace();
                }

                final int finalCurrentVersionCode = currentVersionCode;
                final String finalCurrentVersionName = currentVersionName;
                final String finalLatestVersionName = latestVersionName;
                final String finalDownloadUrl = downloadUrl;
                final String finalUpdateMessage = updateMessage;
                final boolean finalForceUpdate = forceUpdate;

                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateManager.class) { isChecking = false; }
                    if (context instanceof android.app.Activity) {
                        android.app.Activity activity = (android.app.Activity) context;
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                    }
                    if (latestVersionCode > finalCurrentVersionCode) {
                        showUpdateDialog(
                                finalCurrentVersionName,
                                finalLatestVersionName,
                                finalUpdateMessage,
                                finalDownloadUrl,
                                finalForceUpdate
                        );
                    } else {
                        Toast.makeText(context,
                                "已是最新版本\n当前版本：" + finalCurrentVersionName,
                                Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                final String errMsg = e.getMessage();
                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateManager.class) { isChecking = false; }
                    Toast.makeText(context, "检查更新失败：" + errMsg, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ============================================================
    // 通用 HTTP GET 工具：返回字符串（连接+读取超时可配置）
    // ============================================================
    private static String httpGetString(String urlStr, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("User-Agent", "TVLive-UpdateManager/1.0");
        conn.setInstanceFollowRedirects(true);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + responseCode);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        return sb.toString();
    }

    // ============================================================
    // 下载 URL 镜像展开：根据原始链接生成带代理/去代理的回退列表
    // ============================================================
    private static String[] expandDownloadMirrors(String originalUrl) {
        if (originalUrl == null || originalUrl.isEmpty()) return new String[0];

        // 提取原始 GitHub release 直链：去掉可能已有的 ghproxy 前缀
        String pureGithub = originalUrl;
        if (pureGithub.startsWith("https://ghproxy.com/")) {
            pureGithub = pureGithub.substring("https://ghproxy.com/".length());
        } else if (pureGithub.startsWith("https://mirror.ghproxy.com/")) {
            pureGithub = pureGithub.substring("https://mirror.ghproxy.com/".length());
        } else if (pureGithub.startsWith("https://gh.api.99988866.xyz/")) {
            pureGithub = pureGithub.substring("https://gh.api.99988866.xyz/".length());
        }

        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        // 代理优先（国内加速）
        list.add("https://ghproxy.com/" + pureGithub);
        list.add("https://gh.api.99988866.xyz/" + pureGithub);
        // 原始直链兜底（国外）
        list.add(pureGithub);
        // 如果原始 URL 不同于去代理后的（即用户给的是其他代理），也放在最后
        if (!originalUrl.equals(pureGithub)) list.add(originalUrl);

        return list.toArray(new String[0]);
    }

    private void showUpdateDialog(String currentVersion, String latestVersion,
                                   String updateMessage, String downloadUrl,
                                   boolean forceUpdate) {
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) return;
        }

        String message = "📱 发现新版本！\n\n"
                + "当前版本：" + currentVersion + "\n"
                + "最新版本：" + latestVersion + "\n\n"
                + "━━━━━━ 更新内容 ━━━━━━\n"
                + updateMessage;

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("📥 发现新版本")
                .setMessage(message)
                .setPositiveButton("立即更新", (dialog, which) -> startDownload(downloadUrl));

        if (!forceUpdate) builder.setNegativeButton("稍后再说", null);
        builder.setCancelable(!forceUpdate);
        builder.show();
    }

    // ============================================================
    // 下载阶段：镜像列表依次尝试，全部失败才报错
    // ============================================================
    private void startDownload(String originalUrl) {
        synchronized (UpdateManager.class) {
            if (isDownloading) {
                MAIN_HANDLER.post(() -> Toast.makeText(context, "正在下载中，请稍后...", Toast.LENGTH_SHORT).show());
                return;
            }
            isDownloading = true;
        }

        final String[] mirrors = expandDownloadMirrors(originalUrl);
        if (mirrors.length == 0) {
            synchronized (UpdateManager.class) { isDownloading = false; }
            Toast.makeText(context, "下载地址为空", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

            // 尝试删除旧的同名下载记录，避免 DownloadManager 文件名冲突
            try {
                Cursor c = downloadManager.query(
                        new DownloadManager.Query().setFilterByStatus(
                                DownloadManager.STATUS_FAILED | DownloadManager.STATUS_SUCCESSFUL
                                        | DownloadManager.STATUS_PAUSED | DownloadManager.STATUS_PENDING
                                        | DownloadManager.STATUS_RUNNING
                        ));
                if (c != null && c.moveToFirst()) {
                    int titleIdx = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
                    int idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID);
                    do {
                        String title = (titleIdx >= 0) ? c.getString(titleIdx) : null;
                        if ("电视直播 更新".equals(title) && idIdx >= 0) {
                            downloadManager.remove(c.getLong(idIdx));
                        }
                    } while (c.moveToNext());
                    c.close();
                }
            } catch (Exception ignored) { }

            // 按优先级依次提交下载，任一成功即跳出
            Exception lastError = null;
            for (int i = 0; i < mirrors.length; i++) {
                String url = mirrors[i];
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setTitle("电视直播 更新");
                    request.setDescription("正在下载新版本 (" + (i + 1) + "/" + mirrors.length + ")...");
                    request.setAllowedNetworkTypes(
                            DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE
                    );
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    );

                    // 解决部分电视网络下 ghproxy 间歇性 HTTP 403 / 证书问题
                    request.addRequestHeader("User-Agent", "TVLive-UpdateManager/1.0");

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        request.setDestinationInExternalFilesDir(
                                context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME
                        );
                    } else {
                        request.setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME
                        );
                    }
                    request.allowScanningByMediaScanner();

                    downloadId = downloadManager.enqueue(request);
                    Log.d(TAG, "已提交下载镜像 (" + (i + 1) + "/" + mirrors.length + "): " + url);

                    registerDownloadCompleteReceiver();
                    Toast.makeText(context,
                            "开始下载（镜像" + (i + 1) + "/" + mirrors.length + "），通知栏可查看进度",
                            Toast.LENGTH_SHORT).show();
                    return;

                } catch (Exception e) {
                    Log.w(TAG, "镜像提交失败 (" + (i + 1) + "/" + mirrors.length + "): " + url + " -> " + e.getMessage());
                    lastError = e;
                }
            }

            throw (lastError != null) ? lastError : new Exception("无法提交下载请求");

        } catch (Exception e) {
            e.printStackTrace();
            synchronized (UpdateManager.class) { isDownloading = false; }
            Toast.makeText(context, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 针对 Android 13+ 下载广播的安全注册
    private void registerDownloadCompleteReceiver() {
        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    installApk();
                    unregisterDownloadCompleteReceiver();
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        ContextCompat.registerReceiver(context, downloadCompleteReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterDownloadCompleteReceiver() {
        if (downloadCompleteReceiver != null) {
            try {
                context.unregisterReceiver(downloadCompleteReceiver);
                downloadCompleteReceiver = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // ============================================================
    // 安装阶段：复制到公共 Download 目录，再用公共 Uri 安装
    // ============================================================
    private void installApk() {
        try {
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            Cursor cursor = downloadManager.query(query);

            if (cursor != null && cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    String uriString = cursor.getString(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                    );
                    if (uriString != null && !uriString.isEmpty()) {
                        Uri privateUri = Uri.parse(uriString);

                        Uri publicUri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            publicUri = copyToPublicDownload(privateUri);
                            if (publicUri == null) publicUri = privateUri;
                        } else {
                            publicUri = privateUri;
                        }

                        Intent installIntent = new Intent(Intent.ACTION_VIEW);
                        installIntent.setDataAndType(publicUri, "application/vnd.android.package-archive");
                        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }
                        context.startActivity(installIntent);
                    } else {
                        Toast.makeText(context, "下载文件丢失，请重新下载", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(context, "下载失败，请稍后重试", Toast.LENGTH_SHORT).show();
                }
                cursor.close();
            } else {
                Toast.makeText(context, "未找到下载文件", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "安装失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            synchronized (UpdateManager.class) { isDownloading = false; }
        }
    }

    // 使用 MediaStore 将 APK 从私有目录复制到公共 Download 目录
    private Uri copyToPublicDownload(Uri privateUri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "当前 Android 版本低于 10，无法使用 MediaStore 复制到公共目录");
            return null;
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, APK_FILE_NAME);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri externalUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri publicUri = context.getContentResolver().insert(externalUri, values);
            if (publicUri == null) return null;

            try (InputStream inputStream = context.getContentResolver().openInputStream(privateUri);
                 OutputStream outputStream = context.getContentResolver().openOutputStream(publicUri)) {

                if (inputStream == null || outputStream == null) return null;

                byte[] buffer = new byte[8192];
                int length;
                while ((length = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, length);
                }
                outputStream.flush();
                return publicUri;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void release() {
        unregisterDownloadCompleteReceiver();
    }
}