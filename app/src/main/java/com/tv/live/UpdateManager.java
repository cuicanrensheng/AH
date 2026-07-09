package com.tv.live;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
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
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用更新管理器
 */
public class UpdateManager {
    // 🔥 保持您指定的 URL 不变
    private static final String UPDATE_JSON_URL = "https://raw.githubusercontent.com/cuicanrensheng/1/main/update.json";
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
            try {
                URL url = new URL(UPDATE_JSON_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new Exception("HTTP 错误：" + responseCode);
                }

                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                is.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                int latestVersionCode = json.getInt("versionCode");
                String latestVersionName = json.getString("versionName");
                String downloadUrl = json.getString("downloadUrl");
                // 🟢【核心修复】将 "updateLog" 改为 "message"，并设置默认值防止为 null
                String updateMessage = json.optString("message", "暂无更新内容");
                boolean forceUpdate = json.optBoolean("forceUpdate", false);

                // 保存更新日志
                saveUpdateMessage(updateMessage);

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
                // 修改变量传递
                final String finalUpdateMessage = updateMessage;
                final boolean finalForceUpdate = forceUpdate;

                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateManager.class) {
                        isChecking = false;
                    }
                    if (context instanceof android.app.Activity) {
                        android.app.Activity activity = (android.app.Activity) context;
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                    }
                    if (latestVersionCode > finalCurrentVersionCode) {
                        showUpdateDialog(
                                finalCurrentVersionName,
                                finalLatestVersionName,
                                finalUpdateMessage, // 🟢 传递正确的消息
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
                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateManager.class) {
                        isChecking = false;
                    }
                    Toast.makeText(context, "检查更新失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showUpdateDialog(String currentVersion, String latestVersion,
                                   String updateMessage, String downloadUrl,
                                   boolean forceUpdate) {
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
        }

        // 🟢 优化弹窗文字排版，更美观
        String message = "📱 发现新版本！\n\n"
                + "当前版本：" + currentVersion + "\n"
                + "最新版本：" + latestVersion + "\n\n"
                + "━━━━━━ 更新内容 ━━━━━━\n"
                + updateMessage;

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("📥 发现新版本")
                .setMessage(message)
                .setPositiveButton("立即更新", (dialog, which) -> {
                    startDownload(downloadUrl);
                });

        if (!forceUpdate) {
            builder.setNegativeButton("稍后再说", null);
        }
        builder.setCancelable(!forceUpdate);
        builder.show();
    }

    private void startDownload(String downloadUrl) {
        synchronized (UpdateManager.class) {
            if (isDownloading) {
                MAIN_HANDLER.post(() -> Toast.makeText(context, "正在下载中，请稍后...", Toast.LENGTH_SHORT).show());
                return;
            }
            isDownloading = true;
        }

        try {
            downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle("电视直播 更新");
            request.setDescription("正在下载新版本...");
            request.setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE
            );
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

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
            registerDownloadCompleteReceiver();
            Toast.makeText(context, "开始下载，通知栏可查看进度", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            synchronized (UpdateManager.class) {
                isDownloading = false;
            }
            Toast.makeText(context, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

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
        try {
            context.registerReceiver(downloadCompleteReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                        Uri apkUri = Uri.parse(uriString);
                        Intent installIntent = new Intent(Intent.ACTION_VIEW);
                        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
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
            synchronized (UpdateManager.class) {
                isDownloading = false;
            }
        }
    }

    public void release() {
        unregisterDownloadCompleteReceiver();
    }
}
