package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateHelper {

    // 你的 GitHub 地址
    private static final String UPDATE_JSON_URL
            = "https://raw.githubusercontent.com/cuicanrensheng/AH/main/update.json";

    // 🟢 统一的主线程 Handler，避免频繁 new Handler 造成微小内存抖动
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // 🟢 并发锁：防止用户多次连续点击导致后台线程堆积
    private static boolean isChecking = false;
    private static boolean isDownloading = false;

    public interface UpdateCallback {
        void onNewVersionFound(String versionName, String downloadUrl);
        void onNoUpdate();
        void onError(String msg);
    }

    public static void checkUpdate(final Context context, final UpdateCallback callback) {
        // 🟢 加锁：如果正在检测中，直接忽略本次请求，避免并发
        synchronized (UpdateHelper.class) {
            if (isChecking) {
                MAIN_HANDLER.post(() -> callback.onError("正在检查中，请稍后..."));
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

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                int versionCode = json.getInt("versionCode");
                String versionName = json.getString("versionName");
                String downloadUrl = json.getString("downloadUrl");

                int currentVersion
                        = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;

                MAIN_HANDLER.post(() -> {
                    // 🟢 解锁
                    synchronized (UpdateHelper.class) {
                        isChecking = false;
                    }
                    if (versionCode > currentVersion) {
                        callback.onNewVersionFound(versionName, downloadUrl);
                    } else {
                        callback.onNoUpdate();
                    }
                });

            } catch (Exception e) {
                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateHelper.class) {
                        isChecking = false;
                    }
                    callback.onError("检查更新失败：" + e.getMessage());
                });
            }
        }).start();
    }

    public static void downloadAndInstallApk(Context context, String url) {
        // 🟢 加锁：防止同时下载多个 APK 导致 I/O 竞争
        synchronized (UpdateHelper.class) {
            if (isDownloading) {
                MAIN_HANDLER.post(() -> Toast.makeText(context, "正在下载中，请稍后...", Toast.LENGTH_SHORT).show());
                return;
            }
            isDownloading = true;
        }

        new Thread(() -> {
            try {
                URL apkUrl = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) apkUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.connect();

                File apkFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(apkFile);
                // 🟢 优化：增大缓冲区到 8KB，减少高频率磁盘 I/O 写入
                byte[] buffer = new byte[8192];
                int len;
                while ((len = conn.getInputStream().read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                conn.disconnect();

                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateHelper.class) {
                        isDownloading = false;
                    }
                    Toast.makeText(context, "下载完成，开始安装", Toast.LENGTH_SHORT).show();
                    installApk(context, apkFile);
                });

            } catch (Exception e) {
                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateHelper.class) {
                        isDownloading = false;
                    }
                    Toast.makeText(context, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private static void installApk(Context context, File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri uri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        apkFile
                );
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // 🟢 捕获异常：防止因 FileProvider 配置遗漏导致直接闪退
            Toast.makeText(context, "安装失败，请检查应用是否有安装未知应用权限：\n" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
