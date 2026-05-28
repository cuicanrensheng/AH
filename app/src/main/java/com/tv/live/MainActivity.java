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
import java.util.Calendar;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import android.view.ViewGroup;
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
            public String dayName;
            public String time;
            public String title;
            public String playUrl;
            public boolean isNow;
            public boolean isPast;

            public EpgItem(String dayName, String time, String title, String playUrl, boolean isNow) {
                this.dayName = dayName;
                this.time = time;
                this.title = title;
                this.playUrl = playUrl;
                this.isNow = isNow;
                this.isPast = false;
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
    private static final String UPDATE_URL = "https://cdn.jsdelivr.net/gh/cuicanrensheng/AH/main/update.json";
    private static final int REQUEST_INSTALL_PACKAGES = 1001;
    private String latestApkUrl = "";
    private HttpServer httpServer;
    private String customSource;
    private String customEpg;

    private List<String> weekNames;

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
        currentRatioIndex = sp.getInt("play_ratio", 2);
        customSource = sp.getString("custom_source", "");
        customEpg = sp.getString("custom_epg", "");

        loadSourceHistory();
        initWeekList();

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
                                        markProgramStatus(ch.epgList);
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

    private void initWeekList() {
        weekNames = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int today = cal.get(Calendar.DAY_OF_WEEK);
        String[] weeks = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        for (int i = 0; i < 7; i++) {
            int index = (today + i - 1) % 7;
            weekNames.add(weeks[index]);
        }
    }

    private void markProgramStatus(List<Channel.EpgItem> list) {
        if (list == null || list.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Channel.EpgItem item : list) {
            item.isPast = false;
            item.isNow = false;
            if (item.time == null || item.time.length() < 5) continue;

            try {
                String[] split = item.time.split("-");
                if (split.length < 2) continue;
                String start = split[0].trim();
                String[] hm = start.split(":");
                if (hm.length < 2) continue;

                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                c.set(Calendar.SECOND, 0);
                long startMs = c.getTimeInMillis();

                if (startMs < now) {
                    item.isPast = true;
                }
                if (Math.abs(now - startMs) < 3600000) {
                    item.isNow = true;
                    item.isPast = false;
                }
            } catch (Exception ignored) {}
        }
    }

    private void setRatio(int index) {
        currentRatioIndex = index;
        switch (index) {
            case 0:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case 1:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case 2:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
            case 3:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
        }
        sp.edit().putInt("play_ratio", currentRatioIndex).apply();
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
        if (sourceHistoryList.size() > 10) {
            sourceHistoryList = sourceHistoryList.subList(0, 10);
        }
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
        int qrSize = 250;
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
            Toast.makeText(this, "二维码生成失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean rev = sp.getBoolean("reverse_channel", false);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            changeChannel(rev ? 1 : -1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            changeChannel(rev ? -1 : 1);
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
                if (Math.abs(dy) > 60) {
                    boolean rev = sp.getBoolean("reverse_channel", false);
                    changeChannel(dy > 0 ? (rev ? -1 : 1) : (rev ? 1 : -1));
                }
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
        Toast.makeText(this, channelSourceList.get(idx).name, Toast.LENGTH_SHORT).show();
    }

    private void initExoPlayer() {
    // 支持所有直播源：虎牙/斗鱼/B站/IPTV/影视/轮播
    exoPlayer = new ExoPlayer.Builder(this)
        .setUseLazyPreparation(false)
        .build();

    playerView.setPlayer(exoPlayer);
    setRatio(currentRatioIndex);

    exoPlayer.addListener(new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                // 播放失败自动重试
                if (!isPlayingPlayback) {
                    tryAutoRetry();
                }
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            // 任何错误都自动切源 + 重试
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "播放失败，自动切换线路", Toast.LENGTH_SHORT).show();
            });
            tryNextSource();
        }
    });
}

// 自动重试播放
private void tryAutoRetry() {
    try {
        exoPlayer.prepare();
        exoPlayer.play();
    } catch (Exception e) {
        tryNextSource();
    }
}

// 自动切换下一条线路
private void tryNextSource() {
    if (channelSourceList == null || channelSourceList.isEmpty()) return;

    Channel ch = channelSourceList.get(currentPlayIndex);
    int nextLine = setting.getLine() + 1;
    if (nextLine >= ch.urls.size()) {
        nextLine = 0;
    }
    setting.setLine(nextLine);

    runOnUiThread(() -> {
        playChannel(currentPlayIndex);
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

    private void playEpgItem(Channel.EpgItem item) {
        if (TextUtils.isEmpty(item.playUrl)) {
            Toast.makeText(this, "暂无回看", Toast.LENGTH_SHORT).show();
            return;
        }
        if (playbackPlayer == null) {
            playbackPlayer = new ExoPlayer.Builder(this).build();
        }
        playerView.setPlayer(playbackPlayer);
        playbackPlayer.setMediaItem(MediaItem.fromUri(item.playUrl));
        playbackPlayer.prepare();
        playbackPlayer.play();
        isPlayingPlayback = true;
        Toast.makeText(this, "回看：" + item.title, Toast.LENGTH_SHORT).show();
    }

    private void showChannelListDialog() {
        if (channelSourceList.isEmpty()) {
            Toast.makeText(this, "暂无频道", Toast.LENGTH_SHORT).show();
            return;
        }
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_channel_list, null);
        ListView lvGroup = v.findViewById(R.id.lv_group);
        ListView lvWeek = v.findViewById(R.id.lv_channel);
        ListView lvEpg = v.findViewById(R.id.lv_epg);
        Channel curr = channelSourceList.get(currentPlayIndex);

        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel ch : channelSourceList) {
            groupSet.add(ch.group);
        }
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
            if (ch.group.equals(curr.group)) {
                gChannels.add(ch);
            }
        }

        ArrayAdapter<String> weekAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, weekNames) {
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
        lvWeek.setAdapter(weekAdapter);
        lvWeek.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        ArrayAdapter<Channel.EpgItem> eAdapter = new ArrayAdapter<Channel.EpgItem>(this, R.layout.item_epg, new ArrayList<Channel.EpgItem>()) {
            @Override
            public View getView(int pos, View cv, ViewGroup p) {
                if (cv == null) {
                    cv = LayoutInflater.from(getContext()).inflate(R.layout.item_epg, p, false);
                }
                Channel.EpgItem item = getItem(pos);
                TextView day = cv.findViewById(R.id.tv_dayName);
                TextView time = cv.findViewById(R.id.tv_time);
                TextView title = cv.findViewById(R.id.tv_title);
                TextView btn = cv.findViewById(R.id.tv_action);

                day.setText(item.dayName);
                time.setText(item.time);
                title.setText(item.title);

                if (item.isNow) {
                    btn.setText("直播中");
                    btn.setBackgroundColor(0xFFFF6600);
                } else if (item.isPast) {
                    btn.setText("回看");
                    btn.setBackgroundColor(0xFF4CAF50);
                } else {
                    btn.setText("未播");
                    btn.setBackgroundColor(0xFF666666);
                }

                if (item.isNow) {
                    title.setTextColor(0xFFFF9900);
                } else {
                    title.setTextColor(Color.WHITE);
                }
                return cv;
            }
        };
        lvEpg.setAdapter(eAdapter);

        lvGroup.post(new Runnable() {
            @Override
            public void run() {
                lvGroup.setItemChecked(gPos, true);
                lvGroup.setSelection(gPos);
                lvWeek.setItemChecked(0, true);

                eAdapter.clear();
                for (Channel.EpgItem item : curr.epgList) {
                    if (item.dayName.equals(weekNames.get(0))) {
                        eAdapter.add(item);
                    }
                }
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
            }
        });

        lvWeek.setOnItemClickListener((parent, view, pos, id) -> {
            String selectDay = weekNames.get(pos);
            eAdapter.clear();
            for (Channel.EpgItem item : curr.epgList) {
                if (item.dayName.equals(selectDay)) {
                    eAdapter.add(item);
                }
            }
            eAdapter.notifyDataSetChanged();
        });

        lvEpg.setOnItemClickListener((parent, view, pos, id) -> {
            Channel.EpgItem item = eAdapter.getItem(pos);
            if (item.isPast || item.isNow) {
                playEpgItem(item);
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
        TextView tv_ratio = v.findViewById(R.id.tv_ratio);
        TextView btn_source = v.findViewById(R.id.btn_source);
        TextView btn_epg = v.findViewById(R.id.btn_epg);
        TextView btn_qr = v.findViewById(R.id.btn_qr);
        switch_reverse.setChecked(sp.getBoolean("reverse_channel", false));
        switch_boot.setChecked(sp.getBoolean("boot_start", false));
        switch_update.setChecked(sp.getBoolean("auto_update", true));
        tv_ratio.setText(ratioNames[currentRatioIndex]);

        switch_reverse.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp.edit().putBoolean("reverse_channel", isChecked).apply());

        switch_boot.setOnCheckedChangeListener((buttonView, isChecked) ->
                ed.putBoolean("boot_start", isChecked).apply());

        switch_update.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ed.putBoolean("auto_update", isChecked).apply();
            if (isChecked) checkUpdate();
        });

        tv_ratio.setOnClickListener(view -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("画面比例")
                    .setItems(ratioNames, (dialog, which) -> {
                        currentRatioIndex = which;
                        tv_ratio.setText(ratioNames[currentRatioIndex]);
                        setRatio(which);
                        Toast.makeText(MainActivity.this, "已切换："+ratioNames[which], Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btn_qr.setOnClickListener(view -> showDynamicQrCodeDialog());

        btn_source.setOnClickListener(view -> {
            View ev = LayoutInflater.from(MainActivity.this).inflate(R.layout.dialog_edit, null);
            EditText et = ev.findViewById(R.id.et_input);
            et.setText(sp.getString("custom_source", ""));
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("自定义直播源")
                .setView(ev)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = et.getText().toString().trim();
                    ed.putString("custom_source", url).apply();
                    saveSourceHistory(url);
                    Toast.makeText(MainActivity.this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
        });

        btn_epg.setOnClickListener(view -> {
            View ev = LayoutInflater.from(MainActivity.this).inflate(R.layout.dialog_edit, null);
            EditText et = ev.findViewById(R.id.et_input);
            et.setText(sp.getString("custom_epg", ""));
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("自定义EPG")
                .setView(ev)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = et.getText().toString().trim();
                    ed.putString("custom_epg", url).apply();
                    Toast.makeText(MainActivity.this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
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
        SharedPreferences.Editor editor = sp.edit();
        editor.putString("custom_source", liveUrl);
        editor.putString("custom_epg", epgUrl);
        editor.apply();
        customSource = liveUrl;
        customEpg = epgUrl;
        runOnUiThread(() -> Toast.makeText(MainActivity.this, "配置已保存，重启生效", Toast.LENGTH_SHORT).show());
    }

    private void checkUpdate() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            BufferedReader reader = null;
            try {
                URL url = new URL(UPDATE_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.connect();
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) return;

                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);

                JSONObject json = new JSONObject(sb.toString());
                int serverVersionCode = json.getInt("versionCode");
                String serverVersionName = json.getString("versionName");
                String serverMessage = json.getString("message");
                latestApkUrl = json.getString("downloadUrl");

                int currentVersionCode = BuildConfig.VERSION_CODE;
                if (serverVersionCode > currentVersionCode) {
                    runOnUiThread(() -> {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("发现新版本 v" + serverVersionName)
                                .setMessage(serverMessage)
                                .setPositiveButton("立即更新", (dialog, which) -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        if (!getPackageManager().canRequestPackageInstalls()) {
                                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse("package:" + getPackageName()));
                                            startActivityForResult(intent, REQUEST_INSTALL_PACKAGES);
                                            return;
                                        }
                                    }
                                    startDownload();
                                })
                                .setNegativeButton("稍后再说", null)
                                .show();
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    if (reader != null) reader.close();
                    if (conn != null) conn.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void startDownload() {
        Toast.makeText(this, "开始下载更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
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
                while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);

                runOnUiThread(() -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        Uri uri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            uri = androidx.core.content.FileProvider.getUriForFile(
                                    MainActivity.this, getPackageName() + ".fileprovider", outFile);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } else {
                            uri = Uri.fromFile(outFile);
                        }
                        intent.setDataAndType(uri, "application/vnd.android.package-archive");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "安装失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "下载失败", Toast.LENGTH_SHORT).show());
            } finally {
                try {
                    if (fos != null) fos.close();
                    if (is != null) is.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQUEST_INSTALL_PACKAGES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (getPackageManager().canRequestPackageInstalls()) startDownload();
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
