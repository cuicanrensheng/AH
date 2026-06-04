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

    // 国内加速地址
    private static final String UPDATE_JSON_URL
            = "https://ghproxy.com/https://raw.githubusercontent.com/cuicanrensheng/AH/main/update.json";

    public interface UpdateCallback {
        void onNewVersionFound(String versionName, String downloadUrl);
        void onNoUpdate();
        void onError(String msg);
    }

    public static void checkUpdate(final Context context, final UpdateCallback callback) {
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

                // ✅ 已修复：适配你的下划线 JSON
                JSONObject json = new JSONObject(sb.toString());
                int versionCode     = json.getInt("version_code");
                String versionName  = json.getString("version_name");
                String downloadUrl  = json.getString("download_url");

                // ✅ 自动加速下载链接
                if (downloadUrl.contains("github.com")) {
                    downloadUrl = "https://ghproxy.com/" + downloadUrl;
                }

                int currentVersion = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionCode;

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (versionCode > currentVersion) {
                        callback.onNewVersionFound(versionName, downloadUrl);
                    } else {
                        callback.onNoUpdate();
                    }
                });

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onError("检查更新失败：" + e.getMessage())
                );
            }
        }).start();
    }

    public static void downloadAndInstallApk(Context context, String url) {
        new Thread(() -> {
            try {
                URL apkUrl = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) apkUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.connect();

                File apkFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(apkFile);
                byte[] buffer = new byte[1024];
                int len;
                while ((len = conn.getInputStream().read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }

                fos.close();
                conn.disconnect();

                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(context, "下载完成，开始安装", Toast.LENGTH_SHORT).show();
                    installApk(context, apkFile);
                });

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
                );
            }
        }).start();
    }

    private static void installApk(Context context, File apkFile) {
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
    }
}
