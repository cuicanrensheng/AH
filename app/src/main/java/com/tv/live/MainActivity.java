package com.tv.live;

import android.app.AlertDialog;
import android.content.DialogInterface;
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
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public int currentChannelIndex = 0;
    public int currentPlayIndex = 0;
    private Setting setting = new Setting();

    public static class Setting {
        private int line = 0;
        public int getLine() { return line; }
        public void setLine(int line) { this.line = line; }
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

            public EpgItem(String dayName, String time, String title, String playUrl, boolean isNow) {
                this.dayName = dayName;
                this.time = time;
                this.title = title;
                this.playUrl = playUrl;
                this.isNow = isNow;
            }
        }

        public Channel(String name, String group, List<String> urls) {
            this.name = name;
            this.group = group;
            this.urls = urls;
            this.epgList = new ArrayList<>();
        }

        @Override
        public String toString() {
            return name;
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
    public int currentRatioIndex = 2;
    private final String[] ratioNames = {"4:3", "16:9", "全屏", "填充"};
    private static final String UPDATE_URL = "https://cdn.jsdelivr.net/gh/cuicanrensheng/AH@main/update.json";
    private static final int REQUEST_INSTALL_PACKAGES = 1001;
    private String latestApkUrl = "";
    private HttpServer httpServer;
    private String customSource;
    private String customEpg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        mInstance = this;

        sp = getSharedPreferences("tv_config", MODE_PRIVATE);
        epgEnabled = sp.getBoolean("epgEnabled", true);
        lastPlayIndex = sp.getInt("lastPlayIndex", 0);
        currentRatioIndex = sp.getInt("play_ratio", 2);
        customSource = sp.getString("custom_source", "");
        customEpg = sp.getString("custom_epg", "");

        loadSourceHistory();

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setFocusable(false);
        playerView.setClickable(false);
        playerView.setFocusableInTouchMode(true);
        playerView.requestFocus();

        initExoPlayer();
        initGesture();

        try {
            httpServer = new HttpServer(10481, this);
            httpServer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ---------------- 核心修复：EPG完整加载 ----------------
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String playUrl = TextUtils.isEmpty(customSource) ? LIVE_SOURCE_URL : customSource;
                    channelSourceList = PlaylistParser.parse(playUrl);
                    String epgUrl = TextUtils.isEmpty(customEpg) ? "https://e.erw.cc/all.xml.gz" : customEpg;
                    EpgManager.getInstance().setEpgUrl(epgUrl);
                    EpgManager.getInstance().loadEpg(new Runnable() {
                        @Override
                        public void run() {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    for (Channel ch : channelSourceList) {
                                        ch.epgList = EpgManager.getInstance().getEpg(ch.name);
                                    }
                                    int playIdx = Math.min(lastPlayIndex, channelSourceList.size() - 1);
                                    playChannel(playIdx);
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();

        if (sp.getBoolean("auto_update", true)) {
            checkUpdate();
        }
    }

    private void setRatio(int index) {
        currentRatioIndex = index;
        switch (index) {
            case 0:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case 1:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH);
                break;
            case 2:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
            case 3:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
        }
        sp.edit().putInt("play_ratio", index).apply();
    }

    private void loadSourceHistory() {
        String json = sp.getString("source_history", "");
        Type type = new TypeToken<List<String>>(){}.getType();
        List<String> list = gson.fromJson(json, type);
        sourceHistoryList = list == null ? new ArrayList<>() : list;
    }

    private void saveSourceHistory(String url) {
        if (TextUtils.isEmpty(url)) return;
        sourceHistoryList.remove(url);
        sourceHistoryList.add(0, url);
        if (sourceHistoryList.size() > 10) {
            sourceHistoryList = sourceHistoryList.subList(0, 10);
        }
        sp.edit().putString("source_history", gson.toJson(sourceHistoryList)).apply();
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress() && inetAddress.isSiteLocalAddress()) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    private void showQrDialog() {
        String ip = getLocalIpAddress();
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, "获取IP失败", Toast.LENGTH_SHORT).show();
            return;
        }
        String qrContent = "http://"+ip+":10481";
        int qrSize = 250;
        try {
            BitMatrix bitMatrix = new MultiFormatWriter().encode(qrContent, BarcodeFormat.QR_CODE, qrSize, qrSize);
            Bitmap bmp = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.ARGB_8888);
            for (int x=0; x<qrSize; x++) {
                for (int y=0; y<qrSize; y++) {
                    bmp.setPixel(x, y, bitMatrix.get(x,y) ? Color.BLACK : Color.WHITE);
                }
            }
            View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qrcode, null);
            ImageView iv = dialogView.findViewById(R.id.iv_qr);
            iv.setImageBitmap(bmp);
            new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("关闭", null)
                .show();
        } catch (WriterException e) {
            e.printStackTrace();
            Toast.makeText(this, "生成二维码失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean reverse = sp.getBoolean("reverse_channel", false);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            changeChannel(reverse ? 1 : -1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            changeChannel(reverse ? -1 : 1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showChannelListDialog();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSettingDialog();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            showQrDialog();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void initGesture() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > 60) {
                    boolean reverse = sp.getBoolean("reverse_channel", false);
                    changeChannel(dy > 0 ? (reverse ? -1 : 1) : (reverse ? 1 : -1));
                }
                return super.onFling(e1, e2, vx, vy);
            }
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
        });
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestureDetector.onTouchEvent(event);
            }
        });
    }

    private void changeChannel(int delta) {
        if (channelSourceList.isEmpty()) return;
        int idx = currentPlayIndex + delta;
        if (idx < 0) idx = channelSourceList.size() - 1;
        if (idx >= channelSourceList.size()) idx = 0;
        playChannel(idx);
    }

    private void initExoPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        setRatio(currentRatioIndex);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(MainActivity.this, "播放失败，尝试下一个源", Toast.LENGTH_SHORT).show();
                tryNextSource();
            }
        });
    }

    private void tryNextSource() {
        Channel ch = channelSourceList.get(currentPlayIndex);
        int nextLine = setting.getLine() + 1;
        if (nextLine >= ch.urls.size()) {
            nextLine = 0;
        }
        setting.setLine(nextLine);
        playChannel(currentPlayIndex);
    }

    private void playChannel(int index) {
        if (channelSourceList.isEmpty()) return;
        currentPlayIndex = index;
        lastPlayIndex = index;
        sp.edit().putInt("lastPlayIndex", index).apply();
        Channel ch = channelSourceList.get(index);
        int line = Math.min(setting.getLine(), ch.urls.size()-1);
        String url = ch.urls.get(line);
        MediaItem item = MediaItem.fromUri(url);
        exoPlayer.setMediaItem(item);
        exoPlayer.prepare();
        exoPlayer.play();
    }

    // ---------------- 修复问题1：设置界面（文字纯白+高对比度） ----------------
    private void showSettingDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_setting, null);
        final SharedPreferences.Editor editor = sp.edit();

        Switch sw_reverse = view.findViewById(R.id.switch_reverse);
        Switch sw_boot = view.findViewById(R.id.switch_boot);
        Switch sw_update = view.findViewById(R.id.switch_update);
        TextView tv_ratio = view.findViewById(R.id.tv_ratio);
        TextView btn_source = view.findViewById(R.id.btn_source);
        TextView btn_epg = view.findViewById(R.id.btn_epg);
        TextView btn_qr = view.findViewById(R.id.btn_qr);

        sw_reverse.setChecked(sp.getBoolean("reverse_channel", false));
        sw_boot.setChecked(sp.getBoolean("boot_start", false));
        sw_update.setChecked(sp.getBoolean("auto_update", true));
        tv_ratio.setText(ratioNames[currentRatioIndex]);

        sw_reverse.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                editor.putBoolean("reverse_channel", isChecked).apply();
            }
        });

        sw_boot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                editor.putBoolean("boot_start", isChecked).apply();
            }
        });

        sw_update.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                editor.putBoolean("auto_update", isChecked).apply();
                if (isChecked) checkUpdate();
            }
        });

        tv_ratio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("画面比例")
                    .setItems(ratioNames, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            currentRatioIndex = which;
                            tv_ratio.setText(ratioNames[which]);
                            setRatio(which);
                        }
                    })
                    .show();
            }
        });

        btn_source.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                View inputView = LayoutInflater.from(MainActivity.this).inflate(R.layout.dialog_input, null);
                final EditText et = inputView.findViewById(R.id.et_input);
                et.setText(customSource);
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("自定义直播源")
                    .setView(inputView)
                    .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String url = et.getText().toString().trim();
                            editor.putString("custom_source", url).apply();
                            customSource = url;
                            saveSourceHistory(url);
                            Toast.makeText(MainActivity.this, "保存成功，重启生效", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            }
        });

        btn_epg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                View inputView = LayoutInflater.from(MainActivity.this).inflate(R.layout.dialog_input, null);
                final EditText et = inputView.findViewById(R.id.et_input);
                et.setText(customEpg);
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("自定义EPG")
                    .setView(inputView)
                    .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String url = et.getText().toString().trim();
                            editor.putString("custom_epg", url).apply();
                            customEpg = url;
                            Toast.makeText(MainActivity.this, "保存成功，重启生效", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            }
        });

        btn_qr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQrDialog();
            }
        });

        new AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("关闭", null)
            .show();
    }

    private void showChannelListDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_channel_list, null);
        ListView lvGroup = view.findViewById(R.id.lv_group);
        ListView lvChannel = view.findViewById(R.id.lv_channel);
        ListView lvEpg = view.findViewById(R.id.lv_epg);

        Set<String> groups = new LinkedHashSet<>();
        for (Channel ch : channelSourceList) {
            groups.add(ch.group);
        }
        final List<String> groupList = new ArrayList<>(groups);

        final ArrayAdapter<String> groupAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, groupList);
        lvGroup.setAdapter(groupAdapter);

        final List<Channel> filteredList = new ArrayList<>();
        final ArrayAdapter<Channel> channelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, filteredList);
        lvChannel.setAdapter(channelAdapter);

        lvGroup.setOnItemClickListener((parent, view1, position, id) -> {
            String group = groupList.get(position);
            filteredList.clear();
            for (Channel ch : channelSourceList) {
                if (ch.group.equals(group)) {
                    filteredList.add(ch);
                }
            }
            channelAdapter.notifyDataSetChanged();
        });

        lvChannel.setOnItemClickListener((parent, view12, position, id) -> {
            Channel ch = filteredList.get(position);
            currentPlayIndex = channelSourceList.indexOf(ch);
            playChannel(currentPlayIndex);
            // 显示EPG列表
            List<Channel.EpgItem> epgList = ch.epgList;
            List<String> epgStrList = new ArrayList<>();
            for (Channel.EpgItem item : epgList) {
                epgStrList.add(item.dayName + " " + item.time + " " + item.title);
            }
            final ArrayAdapter<String> epgAdapter = new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_list_item_1, epgStrList);
            lvEpg.setAdapter(epgAdapter);
        });

        new AlertDialog.Builder(this)
            .setView(view)
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    playerView.requestFocus();
                }
            })
            .show();
    }

    // ---------------- 更新逻辑（不闪退版） ----------------
    private void checkUpdate() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                BufferedReader reader = null;
                try {
                    URL url = new URL(UPDATE_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.connect();
                    int code = conn.getResponseCode();
                    if (code != 200) return;

                    reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }

                    JSONObject json = new JSONObject(sb.toString());
                    int serverCode = json.getInt("versionCode");
                    String serverName = json.getString("versionName");
                    String msg = json.getString("message");
                    latestApkUrl = json.getString("downloadUrl");

                    int currentCode = BuildConfig.VERSION_CODE;
                    if (serverCode > currentCode) {
                        final String name = serverName;
                        final String message = msg;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("发现新版本 v" + name)
                                    .setMessage(message)
                                    .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                if (!getPackageManager().canRequestPackageInstalls()) {
                                                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                                                    startActivityForResult(intent, REQUEST_INSTALL_PACKAGES);
                                                    return;
                                                }
                                            }
                                            startDownload();
                                        }
                                    })
                                    .setNegativeButton("稍后再说", null)
                                    .show();
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try { if (reader != null) reader.close(); if (conn != null) conn.disconnect(); } catch (IOException e) { e.printStackTrace(); }
                }
            }
        }).start();
    }

    private void startDownload() {
        Toast.makeText(this, "开始下载更新…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                InputStream is = null;
                FileOutputStream fos = null;
                try {
                    URL url = new URL(latestApkUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.connect();
                    is = conn.getInputStream();

                    File outFile = new File(getExternalFilesDir("update"), "update.apk");
                    if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();

                    fos = new FileOutputStream(outFile);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        fos.write(buf, 0, len);
                    }

                    final File apkFile = outFile;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                Uri uri;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    uri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", apkFile);
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                } else {
                                    uri = Uri.fromFile(apkFile);
                                }
                                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "安装失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "下载失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    try { if (fos != null) fos.close(); if (is != null) is.close(); if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_PACKAGES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (getPackageManager().canRequestPackageInstalls()) {
                    startDownload();
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
        if (httpServer != null) httpServer.stop();
        if (exoPlayer != null) exoPlayer.release();
        if (playbackPlayer != null) playbackPlayer.release();
    }

    public void onReceiveNewConfig(String liveUrl, String epgUrl) {
        SharedPreferences.Editor editor = sp.edit();
        editor.putString("custom_source", liveUrl);
        editor.putString("custom_epg", epgUrl);
        editor.apply();
        customSource = liveUrl;
        customEpg = epgUrl;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "配置已保存，重启生效", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
