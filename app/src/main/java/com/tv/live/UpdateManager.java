package com.tv.live;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用更新管理器
 *
 * 【功能】
 * 1. 检查更新（请求服务器 JSON 配置）
 * 2. 版本号对比
 * 3. 显示更新对话框（含更新日志）
 * 4. 下载 APK（使用系统 DownloadManager）
 * 5. 下载完成后自动安装
 *
 * 【使用方式】
 * UpdateManager updateManager = new UpdateManager(context);
 * updateManager.checkUpdate();
 *
 * 【JSON 配置格式】
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1.0",
 *   "downloadUrl": "https://xxx.com/app.apk",
 *   "updateLog": "1. 修复xxx\n2. 新增xxx",
 *   "forceUpdate": false
 * }
 */
public class UpdateManager {
    // ====================== 常量 ======================
    /**
     * 版本配置文件地址
     * 【修改为你自己的地址】
     */
    private static final String UPDATE_JSON_URL = "https://raw.githubusercontent.com/cuicanrensheng/AH/main/update.json";

    /** 下载文件名称 */
    private static final String APK_FILE_NAME = "tv_live_update.apk";

    // ====================== 成员变量 ======================
    /** 上下文 */
    private final Context context;

    /** 下载管理器 */
    private DownloadManager downloadManager;

    /** 下载任务 ID */
    private long downloadId = -1;

    /** 下载完成广播接收器 */
    private BroadcastReceiver downloadCompleteReceiver;

    // ====================== 构造函数 ======================
    public UpdateManager(Context context) {
        this.context = context;
    }

    // ====================================================================
    // 1. 检查更新
    // ====================================================================
    /**
     * 检查更新
     *
     * 【流程】
     * 1. 请求服务器 JSON 配置
     * 2. 解析版本号
     * 3. 对比当前版本
     * 4. 有新版本 → 显示更新对话框
     * 5. 没有新版本 → 提示已是最新
     */
    public void checkUpdate() {
        // 子线程请求网络
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 请求 JSON 配置
                    URL url = new URL(UPDATE_JSON_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000); // 10秒超时
                    conn.setReadTimeout(10000);

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        throw new Exception("HTTP 错误：" + responseCode);
                    }

                    // 读取响应
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

                    // 解析 JSON
                    JSONObject json = new JSONObject(sb.toString());
                    int latestVersionCode = json.getInt("versionCode");
                    String latestVersionName = json.getString("versionName");
                    String downloadUrl = json.getString("downloadUrl");
                    String updateLog = json.optString("updateLog", "");
                    boolean forceUpdate = json.optBoolean("forceUpdate", false);

                    // 获取当前版本
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

                    // 对比版本
                    final int finalCurrentVersionCode = currentVersionCode;
                    final String finalCurrentVersionName = currentVersionName;
                    final String finalLatestVersionName = latestVersionName;
                    final String finalDownloadUrl = downloadUrl;
                    final String finalUpdateLog = updateLog;
                    final boolean finalForceUpdate = forceUpdate;

                    // 切回主线程显示结果
                    ((android.app.Activity) context).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (latestVersionCode > finalCurrentVersionCode) {
                                // 有新版本
                                showUpdateDialog(
                                        finalCurrentVersionName,
                                        finalLatestVersionName,
                                        finalUpdateLog,
                                        finalDownloadUrl,
                                        finalForceUpdate
                                );
                            } else {
                                // 已是最新版本
                                Toast.makeText(context,
                                        "已是最新版本\n当前版本：" + finalCurrentVersionName,
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });

                    // 记录日志
                    SettingsActivity.logOperation("【更新】检查更新完成：当前="
                            + currentVersionName + "，最新=" + latestVersionName);

                } catch (final Exception e) {
                    e.printStackTrace();
                    // 切回主线程显示错误
                    ((android.app.Activity) context).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context,
                                    "检查更新失败：" + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                    SettingsActivity.logOperation("【更新】检查更新失败：" + e.getMessage());
                }
            }
        }).start();
    }

    // ====================================================================
    // 2. 显示更新对话框
    // ====================================================================
    /**
     * 显示更新对话框
     *
     * @param currentVersion 当前版本名
     * @param latestVersion  最新版本名
     * @param updateLog      更新日志
     * @param downloadUrl    下载地址
     * @param forceUpdate    是否强制更新
     */
    private void showUpdateDialog(String currentVersion, String latestVersion,
                                   String updateLog, String downloadUrl,
                                   boolean forceUpdate) {
        // 构建消息内容
        String message = "发现新版本！\n\n"
                + "当前版本：" + currentVersion + "\n"
                + "最新版本：" + latestVersion + "\n\n"
                + "【更新内容】\n"
                + updateLog;

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("📥 发现新版本")
                .setMessage(message)
                .setPositiveButton("立即更新", (dialog, which) -> {
                    // 开始下载
                    startDownload(downloadUrl);
                });

        // 非强制更新才显示"稍后再说"
        if (!forceUpdate) {
            builder.setNegativeButton("稍后再说", null);
        }

        // 强制更新不能取消
        builder.setCancelable(!forceUpdate);

        builder.show();

        SettingsActivity.logOperation("【更新】发现新版本：" + latestVersion);
    }

    // ====================================================================
    // 3. 开始下载 APK
    // ====================================================================
    /**
     * 开始下载 APK
     * 使用系统 DownloadManager，支持断点续传、通知栏显示进度
     *
     * @param downloadUrl APK 下载地址
     */
    private void startDownload(String downloadUrl) {
        try {
            downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

            // 创建下载请求
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle("电视直播 更新");
            request.setDescription("正在下载新版本...");
            request.setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE
            );
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            // 设置下载保存位置
            // Android 10+ 不能直接写外部存储，用 DownloadManager 的默认位置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME
                );
            } else {
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME
                );
            }

            // 允许媒体扫描（下载完成后能被文件管理器看到）
            request.allowScanningByMediaScanner();

            // 开始下载
            downloadId = downloadManager.enqueue(request);

            // 注册下载完成广播
            registerDownloadCompleteReceiver();

            Toast.makeText(context, "开始下载，通知栏可查看进度", Toast.LENGTH_SHORT).show();
            SettingsActivity.logOperation("【更新】开始下载新版本");

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            SettingsActivity.logOperation("【更新】下载失败：" + e.getMessage());
        }
    }

    // ====================================================================
    // 4. 注册下载完成广播
    // ====================================================================
    /**
     * 注册下载完成广播接收器
     * 下载完成后自动弹出安装界面
     */
    private void registerDownloadCompleteReceiver() {
        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    // 下载完成，安装 APK
                    installApk();
                    // 注销广播
                    unregisterDownloadCompleteReceiver();
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        context.registerReceiver(downloadCompleteReceiver, filter);
    }

    /**
     * 注销下载完成广播
     */
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

    // ====================================================================
    // 5. 安装 APK
    // ====================================================================
    /**
     * 安装下载好的 APK
     */
    private void installApk() {
        try {
            // 获取下载文件的 URI
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            Cursor cursor = downloadManager.query(query);

            if (cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    // 下载成功，获取文件 URI
                    String uriString = cursor.getString(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                    );
                    Uri apkUri = Uri.parse(uriString);

                    // 打开安装界面
                    Intent installIntent = new Intent(Intent.ACTION_VIEW);
                    installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    // Android 7.0+ 需要 FileProvider
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }

                    context.startActivity(installIntent);

                    SettingsActivity.logOperation("【更新】下载完成，开始安装");
                } else {
                    Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show();
                    SettingsActivity.logOperation("【更新】下载失败，状态：" + status);
                }
            }

            cursor.close();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "安装失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            SettingsActivity.logOperation("【更新】安装失败：" + e.getMessage());
        }
    }

    // ====================================================================
    // 6. 释放资源
    // ====================================================================
    /**
     * 释放资源（Activity 销毁时调用）
     */
    public void release() {
        unregisterDownloadCompleteReceiver();
    }
}
