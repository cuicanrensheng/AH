package com.tv.live;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.KeyEvent; // 关键：导入KeyEvent
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import android.view.ViewGroup;
import android.widget.Switch;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public int currentChannelIndex = 0;
    public int currentPlayIndex = 0;
    // 线路切换配置类
private Setting setting = new Setting();

public static class Setting {
    private int line = 0;

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }
}

    public static class Channel {
        public String name;
        public String group;
        public List<String> urls;
        public List<EpgItem> epgList;

        public static class EpgItem {
            public String dayName;
            public String time;
            public String title;
            public String playUrl;
            public boolean isNow;
        }

        public Channel(String name, String group, List<String> urls) {
            this.name = name;
            this.group = group;
            this.urls = urls;
            this.epgList = new ArrayList<>();
        }
    }

    public List<Channel> channelSourceList = new ArrayList<>();
    private ExoPlayer exoPlayer;
    private ExoPlayer playbackPlayer;
    private boolean isPlayingPlayback = false;
    private PlayerView playerView;
    private GestureDetector gestureDetector;
    private SharedPreferences sp;
    private boolean epgEnabled = true;
    private int lastPlayIndex = 0;
    private final String LIVE_SOURCE_URL = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";

    private List<String> sourceHistoryList = new ArrayList<>();
    private Gson gson = new Gson();

    private int currentRatioIndex = 0;
    private final String[] ratioNames = {"16:9", "4:3", "全屏"};
    private final int[] ratioModes = {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT,
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    };

    // 自动更新配置
    private final String UPDATE_URL = "https://raw.githubusercontent.com/cuicanrensheng/AH/main/update.json";
    private static final int REQUEST_INSTALL_PACKAGES = 1001;

    // 本地HTTP服务（扫码设置）
    private HttpServer httpServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        mInstance = this;

        sp = getSharedPreferences("tv_config", MODE_PRIVATE);
        epgEnabled = sp.getBoolean("epgEnabled", true);
        lastPlayIndex = sp.getInt("last_play", 0);
        currentRatioIndex = sp.getInt("play_ratio", 0);

        String customSource = sp.getString("custom_source", "");
        String customEpg = sp.getString("custom_epg", "");
        loadSourceHistory();

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setFocusable(false);
        playerView.setClickable(false);
        playerView.setFocusableInTouchMode(true);
        playerView.requestFocus();

        initGesture();
        initExoPlayer();

        // 启动本地HTTP服务（扫码设置）
        httpServer = new HttpServer(10481, this);
        try {
            httpServer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 加载频道列表
        new Thread(() -> {
            try {
                String playUrl = TextUtils.isEmpty(customSource) ? LIVE_SOURCE_URL : customSource;
                channelSourceList = PlaylistParser.parse(playUrl);

                String epgUrl = TextUtils.isEmpty(customEpg) ? "http://epg.51zmt.top:8000/e.xml.gz" : customEpg;
                EpgManager.getInstance().setEpgUrl(epgUrl);

                EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
                    for (Channel ch : channelSourceList) {
                        ch.epgList = EpgManager.getInstance().getEpg(ch.name);
                    }
                    int playIdx = Math.min(lastPlayIndex, channelSourceList.size() - 1);
                    playChannel(playIdx);
                }));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();

        // 自动更新检查
        if (sp.getBoolean("auto_update", true)) {
            checkUpdate();
        }
    }

    private void loadSourceHistory() {
        String json = sp.getString("source_history_list", "");
        Type type = new TypeToken<List<String>>() {}.getType();
        List<String> list = gson.fromJson(json, type);
        if (list == null) list = new ArrayList<>();
        sourceHistoryList = list;
    }

    private void saveSourceHistory(String url) {
        if (TextUtils.isEmpty(url)) return;
        sourceHistoryList.remove(url);
        sourceHistoryList.add(0, url);
        if (sourceHistoryList.size() > 10) sourceHistoryList = sourceHistoryList.subList(0, 10);
        sp.edit().putString("source_history_list", gson.toJson(sourceHistoryList)).apply();
    }

    private void updatePlayerRatio() {
        if (exoPlayer != null) {
            exoPlayer.setVideoScalingMode(ratioModes[currentRatioIndex]);
        }
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface intf = interfaces.nextElement();
                Enumeration<InetAddress> addrs = intf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // 完整二维码生成方法（修复显示不全）
    private void showDynamicQrCodeDialog() {
        String ip = getLocalIpAddress();
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, "获取IP失败，请检查网络", Toast.LENGTH_SHORT).show();
            return;
        }
        String qrContent = "http://" + ip + ":10481";
        int qrSize = 400;

        try {
            BitMatrix bitMatrix = new MultiFormatWriter().encode(
                    qrContent,
                    BarcodeFormat.QR_CODE,
                    qrSize,
                    qrSize
            );

            Bitmap bmp = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < qrSize; x++) {
                for (int y = 0; y < qrSize; y++) {
                    bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qrcode, null);
            ImageView ivQr = dialogView.findViewById(R.id.iv_qrcode);
            ivQr.setImageBitmap(bmp);

            new AlertDialog.Builder(this)
                    .setTitle("扫码设置")
                    .setView(dialogView)
                    .setPositiveButton("关闭", null)
                    .show();

        } catch (WriterException e) {
            e.printStackTrace();
            Toast.makeText(this, "二维码生成失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean reverse = sp.getBoolean("reverse_channel", false);
        int up = reverse ? 1 : -1;
        int down = reverse ? -1 : 1;

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            changeChannel(up);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            changeChannel(down);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showChannelListDialog();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_HELP) {
            showSettingDialog();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            showDynamicQrCodeDialog();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void initGesture() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showChannelListDialog();
                return true;
            }
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                showSettingDialog();
                return true;
            }
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > 60) {
                    changeChannel(dy > 0 ? 1 : -1);
                }
                return true;
            }
        });
        playerView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private void changeChannel(int delta) {
        if (channelSourceList.isEmpty()) return;
        int newIdx = currentPlayIndex + delta;
        if (newIdx < 0) newIdx = channelSourceList.size() - 1;
        if (newIdx >= channelSourceList.size()) newIdx = 0;
        playChannel(newIdx);
        Toast.makeText(this, channelSourceList.get(newIdx).name, Toast.LENGTH_SHORT).show();
    }

    private void initExoPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        updatePlayerRatio();
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                if (sp.getBoolean("auto_line", true)) {
                    autoSwitchLine();
                }
            }
        });
    }

    private void playChannel(int index) {
        if (channelSourceList.isEmpty()) return;
        currentPlayIndex = index;
        currentChannelIndex = index;
        Channel ch = channelSourceList.get(index);
        int line = Math.min(setting.getLine(), ch.urls.size() - 1);
        String url = ch.urls.get(line);
        exoPlayer.setMediaItem(MediaItem.fromUri(url));
        exoPlayer.prepare();
        exoPlayer.play();
        lastPlayIndex = index;
        sp.edit().putInt("last_play", index).apply();
    }

    private void autoSwitchLine() {
        if (isPlayingPlayback) return;
        Channel ch = channelSourceList.get(currentPlayIndex);
        int nowLine = setting.getLine();
        if (nowLine + 1 < ch.urls.size()) {
            setting.setLine(nowLine + 1);
            playChannel(currentPlayIndex);
        }
    }

    private void showChannelListDialog() {
        if (channelSourceList.isEmpty()) {
            Toast.makeText(this, "暂无频道", Toast.LENGTH_SHORT).show();
            return;
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_channel_list, null);
        ListView lvGroup = view.findViewById(R.id.lv_group);
        ListView lvChannel = view.findViewById(R.id.lv_channel);
        ListView lvEpg = view.findViewById(R.id.lv_epg);

        Channel currentCh = channelSourceList.get(currentPlayIndex);
        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel ch : channelSourceList) groupSet.add(ch.group);
        List<String> groupList = new ArrayList<>(groupSet);
        int groupPos = groupList.indexOf(currentCh.group);

        ArrayAdapter<String> groupAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setText(groupList.get(position));
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(17);
                tv.setPadding(15, 18, 15, 18);
                return v;
            }
        };
        lvGroup.setAdapter(groupAdapter);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        List<Channel> groupChannels = new ArrayList<>();
        for (Channel ch : channelSourceList) {
            if (ch.group.equals(currentCh.group)) groupChannels.add(ch);
        }

        ArrayAdapter<Channel> channelAdapter = new ArrayAdapter<Channel>(this, android.R.layout.simple_list_item_1, groupChannels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setText(groupChannels.get(position).name);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                tv.setPadding(15, 18, 15, 18);
                return v;
            }
        };
        lvChannel.setAdapter(channelAdapter);
        lvChannel.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        ArrayAdapter<Channel.EpgItem> epgAdapter = new ArrayAdapter<Channel.EpgItem>(this, R.layout.item_epg, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_epg, parent, false);
                }
                TextView tvDayName = convertView.findViewById(R.id.tv_dayName);
                TextView tvTime = convertView.findViewById(R.id.tv_time);
                TextView tvTitle = convertView.findViewById(R.id.tv_title);
                Channel.EpgItem item = getItem(position);
                tvDayName.setText(item.dayName);
                tvTime.setText(item.time);
                tvTitle.setText(item.title);
                return convertView;
            }
        };
        lvEpg.setAdapter(epgAdapter);

        lvGroup.post(() -> {
            lvGroup.setItemChecked(groupPos, true);
            lvGroup.setSelection(groupPos);
            int channelPos = groupChannels.indexOf(currentCh);
            lvChannel.setItemChecked(channelPos, true);
            lvChannel.setSelection(channelPos);
            epgAdapter.clear();
            epgAdapter.addAll(currentCh.epgList);
            epgAdapter.notifyDataSetChanged();
        });

        lvGroup.setOnItemClickListener((parent, v, pos, id) -> {
            String g = groupList.get(pos);
            groupChannels.clear();
            for (Channel ch : channelSourceList) {
                if (ch.group.equals(g)) groupChannels.add(ch);
            }
            channelAdapter.notifyDataSetChanged();
            epgAdapter.clear();
        });

        lvChannel.setOnItemClickListener((parent, v, pos, id) -> {
            Channel ch = groupChannels.get(pos);
            int realIdx = channelSourceList.indexOf(ch);
            playChannel(realIdx);
            epgAdapter.clear();
            epgAdapter.addAll(ch.epgList);
            epgAdapter.notifyDataSetChanged();
        });

        new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .show()
                .setOnDismissListener(dialog -> playerView.requestFocus());
    }

    private void showSettingDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_setting, null);
        SharedPreferences.Editor ed = sp.edit();

        Switch switch_reverse = v.findViewById(R.id.switch_reverse);
        Switch switch_boot = v.findViewById(R.id.switch_boot);
        Switch switch_update = v.findViewById(R.id.switch_update);
        Switch switch_line = v.findViewById(R.id.switch_line);
        TextView tv_ratio = v.findViewById(R.id.tv_ratio);
        TextView btn_source = v.findViewById(R.id.btn_source);
        TextView btn_epg = v.findViewById(R.id.btn_epg);
        TextView btn_qr = v.findViewById(R.id.btn_qr);

        switch_reverse.setChecked(sp.getBoolean("reverse_channel", false));
        switch_boot.setChecked(sp.getBoolean("boot_start", false));
        switch_update.setChecked(sp.getBoolean("auto_update", true));
        switch_line.setChecked(sp.getBoolean("auto_line", true));
        tv_ratio.setText(ratioNames[currentRatioIndex]);

        switch_reverse.setOnCheckedChangeListener((b, c) -> ed.putBoolean("reverse_channel", c).apply());
        switch_boot.setOnCheckedChangeListener((b, c) -> ed.putBoolean("boot_start", c).apply());
        switch_update.setOnCheckedChangeListener((b, c) -> ed.putBoolean("auto_update", c).apply());
        switch_line.setOnCheckedChangeListener((b, c) -> ed.putBoolean("auto_line", c).apply());

        tv_ratio.setOnClickListener(view -> {
            currentRatioIndex = (currentRatioIndex + 1) % ratioNames.length;
            tv_ratio.setText(ratioNames[currentRatioIndex]);
            updatePlayerRatio();
            ed.putInt("play_ratio", currentRatioIndex).apply();
        });

        btn_qr.setOnClickListener(view -> {
            showDynamicQrCodeDialog();
        });

        btn_source.setOnClickListener(view -> {
            View editView = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
            EditText et = editView.findViewById(R.id.et_input);
            et.setText(sp.getString("custom_source", ""));
            new AlertDialog.Builder(this)
                    .setTitle("自定义直播源")
                    .setView(editView)
                    .setPositiveButton("保存", (d, w) -> {
                        String url = et.getText().toString().trim();
                        ed.putString("custom_source", url).apply();
                        saveSourceHistory(url);
                        Toast.makeText(this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btn_epg.setOnClickListener(view -> {
            View editView = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
            EditText et = editView.findViewById(R.id.et_input);
            et.setText(sp.getString("custom_epg", ""));
            new AlertDialog.Builder(this)
                    .setTitle("自定义EPG")
                    .setView(editView)
                    .setPositiveButton("保存", (d, w) -> {
                        String url = et.getText().toString().trim();
                        ed.putString("custom_epg", url).apply();
                        Toast.makeText(this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        new AlertDialog.Builder(this)
                .setView(v)
                .setNegativeButton("关闭", null)
                .show()
                .setOnDismissListener(dialog -> playerView.requestFocus());
    }

    // 接收网页下发的配置并热更新
    public void onReceiveNewConfig(String liveUrl, String epgUrl) {
        SharedPreferences.Editor ed = sp.edit();
        ed.putString("custom_source", liveUrl);
        ed.putString("custom_epg", epgUrl);
        ed.apply();

        runOnUiThread(() -> Toast.makeText(this, "已收到新配置，正在刷新频道", Toast.LENGTH_SHORT).show());

        new Thread(() -> {
            try {
                if (exoPlayer != null) {
                    exoPlayer.stop();
                }

                String useSource = TextUtils.isEmpty(liveUrl) ? LIVE_SOURCE_URL : liveUrl;
                channelSourceList = PlaylistParser.parse(useSource);

                String useEpg = TextUtils.isEmpty(epgUrl) ? "http://epg.51zmt.top:8000/e.xml.gz" : epgUrl;
                EpgManager.getInstance().setEpgUrl(useEpg);
                EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
                    for (Channel ch : channelSourceList) {
                        ch.epgList = EpgManager.getInstance().getEpg(ch.name);
                    }
                    if (!channelSourceList.isEmpty()) {
                        playChannel(0);
                    }
                    Toast.makeText(MainActivity.this, "频道刷新完成", Toast.LENGTH_SHORT).show();
                }));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "配置刷新失败", Toast.LENGTH_SHORT).show());
                e.printStackTrace();
            }
        }).start();
    }

    // 自动更新逻辑
    private void checkUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(UPDATE_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.connect();

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                int latestVersion = json.getInt("versionCode");
                String downloadUrl = json.getString("downloadUrl");
                String updateMsg = json.getString("message");

                if (latestVersion > 1) {
                    runOnUiThread(() -> showUpdateDialog(updateMsg, downloadUrl));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void showUpdateDialog(String msg, String url) {
        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("立即更新", (d, w) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (!getPackageManager().canRequestPackageInstalls()) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_INSTALL_PACKAGES);
                            return;
                        }
                    }
                    downloadAndInstallApk(url);
                })
                .setNegativeButton("稍后再说", null)
                .show();
    }

    private void downloadAndInstallApk(String url) {
        Toast.makeText(this, "正在下载更新...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                URL apkUrl = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) apkUrl.openConnection();
                conn.connect();
                InputStream is = conn.getInputStream();
                File apkFile = new File(getExternalCacheDir(), "update.apk");
                FileOutputStream fos = new FileOutputStream(apkFile);
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                is.close();
                conn.disconnect();

                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "更新失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_PACKAGES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (getPackageManager().canRequestPackageInstalls()) {
                    downloadAndInstallApk(sp.getString("latest_apk_url", ""));
                } else {
                    Toast.makeText(this, "需要开启安装未知应用权限才能更新", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (isPlayingPlayback) {
            isPlayingPlayback = false;
            playerView.setPlayer(exoPlayer);
            exoPlayer.play();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (httpServer != null) {
            httpServer.stop();
        }
        if (exoPlayer != null) exoPlayer.release();
        if (playbackPlayer != null) playbackPlayer.release();
    }
}
