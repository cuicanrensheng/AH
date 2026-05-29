package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class SettingsActivity extends AppCompatActivity {

    private Switch swBoot, swEpg, swUpdate, swReverse, swNumKey;
    private Spinner spRatio;
    private EditText etLive, etEpg;
    private Button btnSave, btnLiveHistory, btnEpgHistory, btnQr, btnCheckUpdate;
    private TextView tvVersion;

    private SharedPreferences sp;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // 已替换为你的仓库地址
    private static final String UPDATE_JSON_URL = "https://raw.githubusercontent.com/cuicanrensheng/AH/main/update.json";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        initViews();
        loadSettings();
        initListeners();
    }

    private void initViews() {
        swBoot = findViewById(R.id.sw_boot);
        swEpg = findViewById(R.id.sw_epg);
        swUpdate = findViewById(R.id.sw_update);
        swReverse = findViewById(R.id.sw_reverse);
        swNumKey = findViewById(R.id.sw_num_key);

        spRatio = findViewById(R.id.sp_ratio);
        String[] ratios = {"全屏", "填充", "原始"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ratios);
        spRatio.setAdapter(adapter);

        etLive = findViewById(R.id.et_live_url);
        etEpg = findViewById(R.id.et_epg_url);

        btnSave = findViewById(R.id.btn_save);
        btnLiveHistory = findViewById(R.id.btn_live_history);
        btnEpgHistory = findViewById(R.id.btn_epg_history);
        btnQr = findViewById(R.id.btn_qr);
        btnCheckUpdate = findViewById(R.id.btn_check_update);

        tvVersion = findViewById(R.id.tv_version);
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            tvVersion.setText("版本：" + pi.versionName + " (" + pi.versionCode + ")");
        } catch (Exception e) {
            tvVersion.setText("版本未知");
        }
    }

    private void loadSettings() {
        swBoot.setChecked(sp.getBoolean("boot_start", false));
        swEpg.setChecked(sp.getBoolean("epg_enable", true));
        swUpdate.setChecked(sp.getBoolean("auto_update", false));
        swReverse.setChecked(sp.getBoolean("channel_reverse", false));
        swNumKey.setChecked(sp.getBoolean("num_key_enable", true));

        String ratio = sp.getString("screen_ratio", "全屏");
        if ("填充".equals(ratio)) spRatio.setSelection(1);
        else if ("原始".equals(ratio)) spRatio.setSelection(2);
        else spRatio.setSelection(0);

        etLive.setText(sp.getString("live_url", ""));
        etEpg.setText(sp.getString("epg_url", ""));
    }

    private void initListeners() {
        btnSave.setOnClickListener(v -> saveSettings());
        btnLiveHistory.setOnClickListener(v -> showHistoryDialog("live_history", "选择直播源地址"));
        btnEpgHistory.setOnClickListener(v -> showHistoryDialog("epg_history", "选择EPG地址"));
        btnQr.setOnClickListener(v -> showQrDialog());
        btnCheckUpdate.setOnClickListener(v -> checkUpdate());
    }

    private void saveSettings() {
        String liveUrl = etLive.getText().toString().trim();
        String epgUrl = etEpg.getText().toString().trim();
        String ratio = (String) spRatio.getSelectedItem();

        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean("boot_start", swBoot.isChecked());
        editor.putBoolean("epg_enable", swEpg.isChecked());
        editor.putBoolean("auto_update", swUpdate.isChecked());
        editor.putBoolean("channel_reverse", swReverse.isChecked());
        editor.putBoolean("num_key_enable", swNumKey.isChecked());
        editor.putString("screen_ratio", ratio);
        editor.putString("live_url", liveUrl);
        editor.putString("epg_url", epgUrl);
        editor.apply();

        saveHistory("live_history", liveUrl);
        saveHistory("epg_history", epgUrl);

        Toast.makeText(this, "设置已保存，重启后生效", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void saveHistory(String key, String url) {
        if (url.isEmpty()) return;
        String history = sp.getString(key, "");
        if (!history.contains(url)) {
            String newHistory = history.isEmpty() ? url : url + "|" + history;
            String[] list = newHistory.split("\\|");
            if (list.length > 10) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 10; i++) {
                    sb.append(list[i]).append("|");
                }
                newHistory = sb.toString();
            }
            sp.edit().putString(key, newHistory).apply();
        }
    }

    private void showHistoryDialog(String key, String title) {
        String history = sp.getString(key, "");
        if (history.isEmpty()) {
            Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] list = history.split("\\|");
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setItems(list, (dialog, which) -> {
            String url = list[which];
            if ("live_history".equals(key)) etLive.setText(url);
            else etEpg.setText(url);
        });
        builder.setNeutralButton("清空", (dialog, which) -> {
            sp.edit().putString(key, "").apply();
            Toast.makeText(this, "已清空历史", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    private void showQrDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_qr, null);
        ImageView ivQr = view.findViewById(R.id.iv_qr);
        TextView tvInfo = view.findViewById(R.id.tv_qr_info);

        String qrText = "TVLive:推送地址";
        try {
            BitMatrix bitMatrix = new MultiFormatWriter().encode(qrText, BarcodeFormat.QR_CODE, 400, 400, getEncodeHint());
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, width, 0, 0, width, height);
            ivQr.setImageBitmap(bmp);
        } catch (Exception e) {
            Toast.makeText(this, "生成二维码失败", Toast.LENGTH_SHORT).show();
            return;
        }
        tvInfo.setText("扫码推送直播源地址");
        builder.setView(view);
        builder.setPositiveButton("关闭", null);
        builder.show();
    }

    private Map<EncodeHintType, Object> getEncodeHint() {
        Map<EncodeHintType, Object> map = new HashMap<>();
        map.put(EncodeHintType.CHARACTER_SET, "utf-8");
        map.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        return map;
    }

    // -------------------------- 更新功能核心 --------------------------
    private void checkUpdate() {
        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show();
        new CheckUpdateTask().execute();
    }

    private class CheckUpdateTask extends AsyncTask<Void, Void, JSONObject> {
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
                Log.e("UpdateHelper", "checkUpdate error", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(JSONObject json) {
            if (json == null) {
                Toast.makeText(SettingsActivity.this, "获取更新信息失败，请稍后重试", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                int versionCode = json.getInt("versionCode");
                String versionName = json.getString("versionName");
                String downloadUrl = json.getString("downloadUrl");

                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                int currentVersionCode = pi.versionCode;

                if (versionCode > currentVersionCode) {
                    showUpdateDialog(versionName, downloadUrl);
                } else {
                    Toast.makeText(SettingsActivity.this, "当前已是最新版本", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(SettingsActivity.this, "解析更新信息失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showUpdateDialog(String versionName, String downloadUrl) {
        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage("当前版本：" + getVersionName() + "\n最新版本：" + versionName)
                .setPositiveButton("立即更新", (dialog, which) -> {
                    downloadAndInstallApk(downloadUrl);
                })
                .setNegativeButton("稍后再说", null)
                .show();
    }

    private String getVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    private void downloadAndInstallApk(String url) {
        Toast.makeText(this, "正在下载更新...", Toast.LENGTH_SHORT).show();
        new DownloadApkTask().execute(url);
    }

    private class DownloadApkTask extends AsyncTask<String, Integer, File> {
        @Override
        protected File doInBackground(String... strings) {
            try {
                URL apkUrl = new URL(strings[0]);
                HttpURLConnection conn = (HttpURLConnection) apkUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.connect();

                File apkFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "app-release.apk");
                FileOutputStream fos = new FileOutputStream(apkFile);
                byte[] buffer = new byte[1024];
                int len;
                while ((len = conn.getInputStream().read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                return apkFile;
            } catch (Exception e) {
                Log.e("UpdateHelper", "download apk error", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(File apkFile) {
            if (apkFile == null || !apkFile.exists()) {
                Toast.makeText(SettingsActivity.this, "下载更新失败", Toast.LENGTH_SHORT).show();
                return;
            }
            installApk(apkFile);
        }
    }

    private void installApk(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
}
