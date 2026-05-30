package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateHelper {
    private static final String TAG = "UpdateHelper";
    private static final String UPDATE_JSON_URL = "https://raw.githubusercontent.com/cuicanrensheng/AH/main/update.json"; // 改成你的 raw 地址

    public interface UpdateCallback {
        void onNewVersionFound(String versionName, String downloadUrl);
        void onNoUpdate();
        void onError(String msg);
    }

    public static void checkUpdate(final Context context, final UpdateCallback callback) {
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... voids) {
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
                    return new JSONObject(sb.toString());
                } catch (Exception e) {
                    Log.e(TAG, "checkUpdate error", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject json) {
                if (json == null) {
                    callback.onError("获取更新信息失败");
                    return;
                }
                try {
                    int versionCode = json.getInt("versionCode");
                    String versionName = json.getString("versionName");
                    String downloadUrl = json.getString("downloadUrl");

                    int currentVersionCode = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;

                    if (versionCode > currentVersionCode) {
                        callback.onNewVersionFound(versionName, downloadUrl);
                    } else {
                        callback.onNoUpdate();
                    }
                } catch (Exception e) {
                    callback.onError("解析更新信息失败");
                }
            }
        }.execute();
    }

    public static void downloadAndInstallApk(final Context context, String url) {
        new AsyncTask<String, Integer, File>() {
            @Override
            protected File doInBackground(String... strings) {
                try {
                    URL apkUrl = new URL(strings[0]);
                    HttpURLConnection conn = (HttpURLConnection) apkUrl.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setDoOutput(true);
                    conn.connect();

                    File apkFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
                    FileOutputStream fos = new FileOutputStream(apkFile);
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = conn.getInputStream().read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                    return apkFile;
                } catch (Exception e) {
                    Log.e(TAG, "download apk error", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(File apkFile) {
                if (apkFile == null || !apkFile.exists()) {
                    Toast.makeText(context, "下载更新失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                installApk(context, apkFile);
            }
        }.execute(url);
    }

    private static void installApk(Context context, File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
