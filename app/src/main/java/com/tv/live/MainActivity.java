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
import android.view.KeyEvent;
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
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.AdapterView;

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
            public String dayName; public String time; public String title;
            public String playUrl; public boolean isNow;
        }
        public Channel(String name, String group, List<String> urls) {
            this.name = name; this.group = group; this.urls = urls;
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
    public int currentRatioIndex = 2; // 默认全屏
    private void setFullScreenRatio() {
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
    }
    private final String UPDATE_URL = "https://raw.githubusercontent.com/cuicanrensheng/AH/main/update.json";
    private static final int REQUEST_INSTALL_PACKAGES = 1001;
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
        try {
            httpServer = new HttpServer(10481, this);
            httpServer.start();
        } catch (Exception e) { e.printStackTrace(); }
        new Thread(() -> {
            try {
                String playUrl = TextUtils.isEmpty(customSource) ? LIVE_SOURCE_URL : customSource;
                channelSourceList = PlaylistParser.parse(playUrl);
                String epgUrl = TextUtils.isEmpty(customEpg) ? "http://epg.51zmt.top:8000/e.xml.gz" : customEpg;
                EpgManager.getInstance().setEpgUrl(epgUrl);
                EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
                    for (Channel ch : channelSourceList)
                        ch.epgList = EpgManager.getInstance().getEpg(ch.name);
                    int playIdx = Math.min(lastPlayIndex, channelSourceList.size()-1);
                    playChannel(playIdx);
                }));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
        if (sp.getBoolean("auto_update", true))
            checkUpdate();
    }

    private void loadSourceHistory() {
        String json = sp.getString("source_history_list", "");
        Type type = new TypeToken<List<String>>(){}.getType();
        List<String> list = gson.fromJson(json, type);
        sourceHistoryList = list == null ? new ArrayList<>() : list;
    }

    private void saveSourceHistory(String url) {
        if (TextUtils.isEmpty(url)) return;
        sourceHistoryList.remove(url);
        sourceHistoryList.add(0, url);
        if (sourceHistoryList.size() > 10)
            sourceHistoryList = sourceHistoryList.subList(0, 10);
        sp.edit().putString("source_history_list", gson.toJson(sourceHistoryList)).apply();
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.isSiteLocalAddress()) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    private void showDynamicQrCodeDialog() {
        String ip = getLocalIpAddress();
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, "获取IP失败", Toast.LENGTH_SHORT).show();
            return;
        }
        String qrContent = "http://"+ip+":10481";
        int qrSize = 400;
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(qrContent, BarcodeFormat.QR_CODE, qrSize, qrSize);
            Bitmap bmp = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.ARGB_8888);
            for (int x=0; x<qrSize; x++) {
                for (int y=0; y<qrSize; y++) {
                    bmp.setPixel(x, y, matrix.get(x,y) ? Color.BLACK : Color.WHITE);
                }
            }
            View dv = LayoutInflater.from(this).inflate(R.layout.dialog_qrcode, null);
            ImageView iv = dv.findViewById(R.id.iv_qrcode);
            iv.setImageBitmap(bmp);
            new AlertDialog.Builder(this)
                .setTitle("扫码设置")
                .setView(dv)
                .setPositiveButton("关闭", null)
                .show();
        } catch (WriterException e) {
            e.printStackTrace();
            Toast.makeText(this, "二维码失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean reverseChannel = sp.getBoolean("reverse_channel", false);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (reverseChannel) {
                changeChannel(-1);
            } else {
                changeChannel(1);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (reverseChannel) {
                changeChannel(1);
            } else {
                changeChannel(-1);
            }
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
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                showChannelListDialog();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                showSettingDialog();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > 60)
                    changeChannel(dy > 0 ? 1 : -1);
                return true;
            }
        });
        playerView.setOnTouchListener((v, e) -> gestureDetector.onTouchEvent(e));
    }

    private void changeChannel(int delta) {
        if (channelSourceList.isEmpty()) return;
        int idx = currentPlayIndex + delta;
        if (idx < 0) idx = channelSourceList.size() - 1;
        if (idx >= channelSourceList.size()) idx = 0;
        playChannel(idx);
        Toast.makeText(this, channelSourceList.get(idx).name, Toast.LENGTH_SHORT).show();
    }

    private void initExoPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        setFullScreenRatio();
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
        int line = Math.min(setting.getLine(), ch.urls.size()-1);
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
        int now = setting.getLine();
        if (now + 1 < ch.urls.size()) {
            setting.setLine(now + 1);
            playChannel(currentPlayIndex);
        }
    }

    private void showChannelListDialog() {
        if (channelSourceList.isEmpty()) {
            Toast.makeText(this, "暂无频道", Toast.LENGTH_SHORT).show();
            return;
        }
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_channel_list, null);
        ListView lvGroup = v.findViewById(R.id.lv_group);
        ListView lvChannel = v.findViewById(R.id.lv_channel);
        ListView lvEpg = v.findViewById(R.id.lv_epg);
        Channel curr = channelSourceList.get(currentPlayIndex);
        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel ch : channelSourceList)
            groupSet.add(ch.group);
        List<String> groupList = new ArrayList<>(groupSet);
        int gPos = groupList.indexOf(curr.group);
        ArrayAdapter<String> gAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int pos, View cv, ViewGroup p) {
                View view = super.getView(pos, cv, p);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(17);
                tv.setPadding(15, 18, 15, 18);
                return view;
            }
        };
        lvGroup.setAdapter(gAdapter);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        List<Channel> gChannels = new ArrayList<>();
        for (Channel ch : channelSourceList) {
            if (ch.group.equals(curr.group))
                gChannels.add(ch);
        }
        ArrayAdapter<Channel> cAdapter = new ArrayAdapter<Channel>(this, android.R.layout.simple_list_item_1, gChannels) {
            @Override
            public View getView(int pos, View cv, ViewGroup p) {
                View view = super.getView(pos, cv, p);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                tv.setPadding(15, 18, 15, 18);
                return view;
            }
        };
        lvChannel.setAdapter(cAdapter);
        lvChannel.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        ArrayAdapter<Channel.EpgItem> eAdapter = new ArrayAdapter<Channel.EpgItem>(this, R.layout.item_epg, new ArrayList<>()) {
            @Override
            public View getView(int pos, View cv, ViewGroup p) {
                if (cv == null)
                    cv = LayoutInflater.from(getContext()).inflate(R.layout.item_epg, p, false);
                Channel.EpgItem item = getItem(pos);
                ((TextView) cv.findViewById(R.id.tv_dayName)).setText(item.dayName);
                ((TextView) cv.findViewById(R.id.tv_time)).setText(item.time);
                ((TextView) cv.findViewById(R.id.tv_title)).setText(item.title);
                return cv;
            }
        };
        lvEpg.setAdapter(eAdapter);
        lvGroup.post(new Runnable() {
            @Override
            public void run() {
                lvGroup.setItemChecked(gPos, true);
                lvGroup.setSelection(gPos);
                int cPos = gChannels.indexOf(curr);
                lvChannel.setItemChecked(cPos, true);
                lvChannel.setSelection(cPos);
                eAdapter.clear();
                eAdapter.addAll(curr.epgList);
                eAdapter.notifyDataSetChanged();
            }
        });
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                String g = groupList.get(pos);
                gChannels.clear();
                for (Channel ch : channelSourceList) {
                    if (ch.group.equals(g)) {
                        gChannels.add(ch);
                    }
                }
                cAdapter.notifyDataSetChanged();
                eAdapter.clear();
            }
        });
        lvChannel.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                Channel ch = gChannels.get(pos);
                int real = channelSourceList.indexOf(ch);
                playChannel(real);
                eAdapter.clear();
                eAdapter.addAll(ch.epgList);
                eAdapter.notifyDataSetChanged();
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(v)
                .setCancelable(true)
                .show();
        dialog.setOnDismissListener(dialog1 -> playerView.requestFocus());
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

    // 比例选项（你要的4个）
    final String[] ratioNames = {"4:3", "16:9", "全屏", "填充"};

    switch_reverse.setChecked(sp.getBoolean("reverse_channel", false));
    switch_boot.setChecked(sp.getBoolean("boot_start", false));
    switch_update.setChecked(sp.getBoolean("auto_update", true));
    switch_line.setChecked(sp.getBoolean("auto_line", true));
    tv_ratio.setText(ratioNames[currentRatioIndex]);

    // 反向切换
    switch_reverse.setOnCheckedChangeListener((buttonView, isChecked) -> {
        sp.edit().putBoolean("reverse_channel", isChecked).apply();
    });
    switch_boot.setOnCheckedChangeListener((buttonView, isChecked) -> ed.putBoolean("boot_start", isChecked).apply());
    switch_update.setOnCheckedChangeListener((buttonView, isChecked) -> ed.putBoolean("auto_update", isChecked).apply());
    switch_line.setOnCheckedChangeListener((buttonView, isChecked) -> ed.putBoolean("auto_line", isChecked).apply());

    // ====================== 【修复：画面比例点击弹窗】 ======================
    tv_ratio.setOnClickListener(view -> {
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("画面比例")
                .setItems(ratioNames, (dialog, which) -> {
                    currentRatioIndex = which;
                    tv_ratio.setText(ratioNames[currentRatioIndex]);

                    // 真正切换4种比例
                    switch (which) {
                        case 0: // 4:3
                            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                            playerView.setAspectRatio(4f / 3);
                            break;
                        case 1: // 16:9
                            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                            playerView.setAspectRatio(16f / 9);
                            break;
                        case 2: // 全屏（裁切，不变形）
                            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                            break;
                        case 3: // 填充（拉伸满屏）
                            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                            break;
                    }

                    sp.edit().putInt("play_ratio", currentRatioIndex).apply();
                    Toast.makeText(MainActivity.this, "已切换："+ratioNames[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    });
    // ====================================================================

    btn_qr.setOnClickListener(view -> showDynamicQrCodeDialog());

    btn_source.setOnClickListener(view -> {
        View ev = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
        EditText et = ev.findViewById(R.id.et_input);
        et.setText(sp.getString("custom_source", ""));
        new AlertDialog.Builder(this)
            .setTitle("自定义直播源")
            .setView(ev)
            .setPositiveButton("保存", (dialog, which) -> {
                String url = et.getText().toString().trim();
                ed.putString("custom_source", url).apply();
                saveSourceHistory(url);
                Toast.makeText(this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    });

    btn_epg.setOnClickListener(view -> {
        View ev = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
        EditText et = ev.findViewById(R.id.et_input);
        et.setText(sp.getString("custom_epg", ""));
        new AlertDialog.Builder(this)
            .setTitle("自定义EPG")
            .setView(ev)
            .setPositiveButton("保存", (dialog, which) -> {
                String url = et.getText().toString().trim();
                ed.putString("custom_epg", url).apply();
                Toast.makeText(this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    });

    AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(v)
            .setNegativeButton("关闭", null)
            .show();
    dialog.setOnDismissListener(dialog1 -> playerView.requestFocus());
}

        btn_epg.setOnClickListener(view -> {
            View ev = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
            EditText et = ev.findViewById(R.id.et_input);
            et.setText(sp.getString("custom_epg", ""));
            new AlertDialog.Builder(this)
                .setTitle("自定义EPG")
                .setView(ev)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = et.getText().toString().trim();
                    ed.putString("custom_epg", url).apply();
                    Toast.makeText(this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(v)
                .setNegativeButton("关闭", null)
                .show();
        dialog.setOnDismissListener(dialog1 -> playerView.requestFocus());
    }

    public void onReceiveNewConfig(String liveUrl, String epgUrl) {
        SharedPreferences.Editor ed = sp.edit();
        ed.putString("custom_source", liveUrl);
        ed.putString("custom_epg", epgUrl);
        ed.apply();
        runOnUiThread(() -> Toast.makeText(this, "收到新配置，刷新频道", Toast.LENGTH_SHORT).show());
        new Thread(() -> {
            try {
                if (exoPlayer != null) exoPlayer.stop();
                String use = TextUtils.isEmpty(liveUrl) ? LIVE_SOURCE_URL : liveUrl;
                channelSourceList = PlaylistParser.parse(use);
                String e = TextUtils.isEmpty(epgUrl) ? "http://epg.51zmt.top:8000/e.xml.gz" : epgUrl;
                EpgManager.getInstance().setEpgUrl(e);
                EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
                    for (Channel ch : channelSourceList)
                        ch.epgList = EpgManager.getInstance().getEpg(ch.name);
                    if (!channelSourceList.isEmpty())
                        playChannel(0);
                    Toast.makeText(this, "刷新完成", Toast.LENGTH_SHORT).show();
                }));
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "刷新失败", Toast.LENGTH_SHORT).show());
                ex.printStackTrace();
            }
        }).start();
    }

    private void checkUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(UPDATE_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.connect();
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null)
                    sb.append(line);
                r.close();
                conn.disconnect();
                JSONObject json = new JSONObject(sb.toString());
                int ver = json.getInt("versionCode");
                String down = json.getString("downloadUrl");
                String msg = json.getString("message");
                if (ver > 1)
                    runOnUiThread(() -> showUpdateDialog(msg, down));
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private void showUpdateDialog(String msg, String url) {
        new AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(msg)
            .setPositiveButton("立即更新", (dialog, which) -> {
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
            HttpURLConnection conn = null;
            InputStream is = null;
            FileOutputStream fos = null;
            try {
                URL apkUrl = new URL(url);
                conn = (HttpURLConnection) apkUrl.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(20000);
                conn.connect();
                is = conn.getInputStream();
                File apkFile = new File(getExternalCacheDir(), "update.apk");
                fos = new FileOutputStream(apkFile);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "更新失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                try {
                    if (fos != null) fos.close();
                    if (is != null) is.close();
                    if (conn != null) conn.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQUEST_INSTALL_PACKAGES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (getPackageManager().canRequestPackageInstalls()) {
                    downloadAndInstallApk(sp.getString("latest_apk_url", ""));
                } else {
                    Toast.makeText(this, "需要开启安装未知应用权限", Toast.LENGTH_SHORT).show();
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
}
