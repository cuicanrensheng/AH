package com.tv.live.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.tv.live.Channel;
import com.tv.live.PlaylistParser;
import com.tv.live.R;
import com.tv.live.util.CacheManager;

import java.util.List;

public class SetupActivity extends Activity {

    private static final String TAG = "TIF_Setup";
    private static final String SP_NAME = "app_settings";
    private static final String KEY_CUSTOM_LIVE_URL = "custom_live_url";
    private static final String KEY_CUSTOM_EPG_URL = "custom_epg_url";
    private static final String KEY_TIF_ENABLED = "tif_enabled";

    private TextView tvStatus;
    private Button btnSync;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("TVLive 电视直播源设置");
        tvTitle.setTextSize(24);
        tvTitle.setPadding(0, 0, 0, 30);
        layout.addView(tvTitle);

        // 状态显示
        tvStatus = new TextView(this);
        tvStatus.setTextSize(16);
        tvStatus.setPadding(0, 0, 0, 20);
        layout.addView(tvStatus);

        // 直播源输入
        final EditText etLive = new EditText(this);
        etLive.setHint("请输入直播源 M3U 地址");
        etLive.setSingleLine(true);

        // EPG输入
        final EditText etEpg = new EditText(this);
        etEpg.setHint("请输入 EPG 地址");
        etEpg.setSingleLine(true);

        // 加载已有配置
        SharedPreferences sp = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String savedLive = sp.getString(KEY_CUSTOM_LIVE_URL, "");
        String savedEpg = sp.getString(KEY_CUSTOM_EPG_URL, "");
        if (!savedLive.isEmpty()) etLive.setText(savedLive);
        if (!savedEpg.isEmpty()) etEpg.setText(savedEpg);

        // 保存按钮
        Button btnSave = new Button(this);
        btnSave.setText("保存配置");

        // 同步频道按钮
        btnSync = new Button(this);
        btnSync.setText("同步频道到系统直播电视");

        // 返回按钮
        Button btnBack = new Button(this);
        btnBack.setText("完成并返回");

        layout.addView(etLive);
        layout.addView(etEpg);
        layout.addView(btnSave);
        layout.addView(btnSync);
        layout.addView(btnBack);
        setContentView(layout);

        updateStatus();

        btnSave.setOnClickListener(v -> {
            String live = etLive.getText().toString().trim();
            String epg = etEpg.getText().toString().trim();

            SharedPreferences.Editor editor = sp.edit();
            if (!live.isEmpty()) {
                editor.putString(KEY_CUSTOM_LIVE_URL, live);
            }
            if (!epg.isEmpty()) {
                editor.putString(KEY_CUSTOM_EPG_URL, epg);
            }
            editor.putBoolean(KEY_TIF_ENABLED, true);
            editor.apply();

            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
            updateStatus();
        });

        btnSync.setOnClickListener(v -> syncChannelsToSystem());

        btnBack.setOnClickListener(v -> {
            Toast.makeText(this, "配置完成，请在电视主页\"直播电视\"中查看频道", Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private void updateStatus() {
        String inputId = getInputId();
        if (inputId == null) {
            tvStatus.setText("状态：TIF 服务尚未激活\n请先保存配置，再点击\"同步频道到系统\"");
            btnSync.setEnabled(false);
            return;
        }

        int count = TvChannelSyncManager.getSyncedChannelCount(this, inputId);
        if (count > 0) {
            tvStatus.setText("状态：已同步 " + count + " 个频道到系统\n电视主页\"直播电视\"可直接浏览和切换频道");
            btnSync.setEnabled(true);
            btnSync.setText("重新同步频道到系统");
        } else {
            tvStatus.setText("状态：TIF 服务已激活，但尚未同步频道\n请点击\"同步频道到系统\"");
            btnSync.setEnabled(true);
        }
    }

    private void syncChannelsToSystem() {
        btnSync.setEnabled(false);
        btnSync.setText("正在同步...");

        new Thread(() -> {
            try {
                // 1. 从缓存加载频道
                CacheManager cacheManager = CacheManager.getInstance(this);
                String cacheContent = cacheManager.getFileCache("live_source");

                if (cacheContent == null || cacheContent.isEmpty()) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "暂无频道数据，请先在主应用加载直播源", Toast.LENGTH_LONG).show();
                        btnSync.setEnabled(true);
                        btnSync.setText("同步频道到系统");
                    });
                    return;
                }

                List<Channel> channels = PlaylistParser.parseContent(cacheContent);
                if (channels == null || channels.isEmpty()) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "频道解析失败", Toast.LENGTH_SHORT).show();
                        btnSync.setEnabled(true);
                        btnSync.setText("同步频道到系统");
                    });
                    return;
                }

                // 2. 同步到系统
                String inputId = getInputId();
                if (inputId == null) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "TIF 服务未激活，请先保存配置", Toast.LENGTH_LONG).show();
                        btnSync.setEnabled(true);
                        btnSync.setText("同步频道到系统");
                    });
                    return;
                }

                int synced = TvChannelSyncManager.syncChannels(this, inputId, channels);

                mainHandler.post(() -> {
                    Toast.makeText(this, "成功同步 " + synced + "/" + channels.size() + " 个频道", Toast.LENGTH_LONG).show();
                    updateStatus();
                });

            } catch (Exception e) {
                Log.e(TAG, "同步频道异常", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "同步异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    btnSync.setEnabled(true);
                    btnSync.setText("同步频道到系统");
                });
            }
        }).start();
    }

    /**
     * 获取当前 TvInputService 的 inputId
     */
    private String getInputId() {
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return null;

            String packageName = getPackageName();
            String serviceName = LiveTvInputService.class.getName();
            for (TvInputInfo info : tim.getTvInputList()) {
                String id = info.getId();
                if (id != null && id.startsWith(packageName) && id.contains(serviceName)) {
                    return id;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取 inputId 失败", e);
        }
        return null;
    }
}
