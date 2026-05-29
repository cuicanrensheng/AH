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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

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

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public int currentChannelIndex = 0;
    public int currentPlayIndex = 0;
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

        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        setRatio(currentRatioIndex);

        initGesture();

        try {
            httpServer = new HttpServer(10481, this);
            httpServer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }

        loadChannels();
        if (sp.getBoolean("auto_update", true)) {
            checkUpdate();
        }
    }

    private void initWeekList() {
        weekNames = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int todayWeek = cal.get(Calendar.DAY_OF_WEEK);
        String[] weeks = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        for (int i = 0; i < 7; i++) {
            int index = (todayWeek + i - 1) % 7;
            weekNames.add(weeks[index]);
        }
    }

    private void markProgramStatus(List<Channel.EpgItem> list) {
        if (list == null || list.isEmpty()) return;
        long nowTime = System.currentTimeMillis();
        for (Channel.EpgItem item : list) {
            item.isPast = false;
            item.isNow = false;
            if (item.time == null || item.time.length() < 5) continue;
            try {
                String[] timeSplit = item.time.split("-");
                if (timeSplit.length < 2) continue;
                String startTime = timeSplit[0].trim();
                String[] hm = startTime.split(":");
                if (hm.length < 2) continue;

                Calendar itemCal = Calendar.getInstance();
                itemCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                itemCal.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                itemCal.set(Calendar.SECOND, 0);
                long itemTime = itemCal.getTimeInMillis();

                if (itemTime < nowTime) {
                    item.isPast = true;
                }
                if (Math.abs(nowTime - itemTime) < 3600 * 1000) {
                    item.isNow = true;
                    item.isPast = false;
                }
            } catch (Exception ignored) {
            }
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
        String json = sp.getString("source_history", "");
        Type type = new TypeToken<List<String>>() {
        }.getType();
        List<String> temp = gson.fromJson(json, type);
        sourceHistoryList = temp == null ? new ArrayList<>() : temp;
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

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                Enumeration<InetAddress> addr = ni.getInetAddresses();
                while (addr.hasMoreElements()) {
                    InetAddress inet = addr.nextElement();
                    if (!inet.isLoopbackAddress() && inet.isSiteLocalAddress()) {
                        return inet.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    private void showQrDialog() {
        String ip = getLocalIp();
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, "获取IP失败", Toast.LENGTH_SHORT).show();
            return;
        }
        String qrContent = "http://" + ip + ":10481";
        int qrSize = 250;
        Bitmap qrBitmap = null;
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(qrContent, BarcodeFormat.QR_CODE, qrSize, qrSize);
            qrBitmap = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < qrSize; x++) {
                for (int y = 0; y < qrSize; y++) {
                    qrBitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
        } catch (WriterException e) {
            e.printStackTrace();
        }
        if (qrBitmap == null) {
            Toast.makeText(this, "生成二维码失败", Toast.LENGTH_SHORT).show();
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_qrcode, null);
        ImageView ivQr = view.findViewById(R.id.iv_qrcode);
        ivQr.setImageBitmap(qrBitmap);
        new AlertDialog.Builder(this)
                .setTitle("扫码访问")
                .setView(view)
                .setPositiveButton("关闭", null)
                .show();
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
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > 60) {
                    boolean reverse = sp.getBoolean("reverse_channel", false);
                    changeChannel(dy > 0 ? (reverse ? -1 : 1) : (reverse ? 1 : -1));
                }
                return true;
            }
        });
        playerView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private void changeChannel(int delta) {
        if (channelSourceList.isEmpty()) return;
        int index = currentPlayIndex + delta;
        if (index < 0) {
            index = channelSourceList.size() - 1;
        }
        if (index >= channelSourceList.size()) {
            index = 0;
        }
        playChannel(index);
        Toast.makeText(this, channelSourceList.get(index).name, Toast.LENGTH_SHORT).show();
    }

    private void playChannel(int index) {
        if (channelSourceList.isEmpty()) return;
        currentPlayIndex = index;
        currentChannelIndex = index;
        Channel ch = channelSourceList.get(index);
        int line = Math.min(setting.getLine(), ch.urls.size() - 1);
        String url = ch.urls.get(line);

        exoPlayer.stop();
        exoPlayer.clearMediaItems();
        exoPlayer.setMediaItem(MediaItem.fromUri(url));
        exoPlayer.prepare();
        exoPlayer.play();

        lastPlayIndex = index;
        sp.edit().putInt("last_play", lastPlayIndex).apply();
        isPlayingPlayback = false;
    }

    private void playEpgItem(Channel.EpgItem item) {
        if (TextUtils.isEmpty(item.playUrl)) {
            Toast.makeText(this, "暂无回看地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (playbackPlayer == null) {
            playbackPlayer = new ExoPlayer.Builder(this).build();
        }
        playerView.setPlayer(playbackPlayer);
        playbackPlayer.stop();
        playbackPlayer.clearMediaItems();
        playbackPlayer.setMediaItem(MediaItem.fromUri(item.playUrl));
        playbackPlayer.prepare();
        playbackPlayer.play();
        isPlayingPlayback = true;
        Toast.makeText(this, "正在回看：" + item.title, Toast.LENGTH_SHORT).show();
    }

    private void loadChannels() {
        new Thread(() -> {
            try {
                String sourceUrl = TextUtils.isEmpty(customSource) ? LIVE_SOURCE_URL : customSource;
                channelSourceList = PlaylistParser.parse(sourceUrl);
                String epgUrl = TextUtils.isEmpty(customEpg) ? "https://e.erw.cc/all.xml.gz" : customEpg;
                EpgManager.getInstance().setEpgUrl(epgUrl);
                EpgManager.getInstance().loadEpg(() -> runOnUiThread(() -> {
                    for (Channel ch : channelSourceList) {
                        ch.epgList = EpgManager.getInstance().getEpg(ch.name);
                        markProgramStatus(ch.epgList);
                    }
                    if (!channelSourceList.isEmpty()) {
                        int idx = Math.min(lastPlayIndex, channelSourceList.size() - 1);
                        playChannel(idx);
                    }
                }));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "频道加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showChannelListDialog() {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            Toast.makeText(this, "暂无频道数据", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
            currentPlayIndex = 0;
        }

        View view;
        try {
            view = LayoutInflater.from(this).inflate(R.layout.dialog_channel_list, null);
        } catch (Exception e) {
            Toast.makeText(this, "加载布局失败", Toast.LENGTH_SHORT).show();
            return;
        }

        ListView lvGroup = view.findViewById(R.id.lv_group);
        ListView lvChannelList = view.findViewById(R.id.lv_channel_list);
        ListView lvDate = view.findViewById(R.id.lv_date);
        ListView lvEpg = view.findViewById(R.id.lv_epg);

        if (lvGroup == null || lvChannelList == null || lvDate == null || lvEpg == null) {
            Toast.makeText(this, "控件加载异常", Toast.LENGTH_SHORT).show();
            return;
        }

        final Channel initChannel = channelSourceList.get(currentPlayIndex);

        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel ch : channelSourceList) {
            groupSet.add(ch.group);
        }
        final List<String> groupList = new ArrayList<>(groupSet);
        int groupPos = groupList.indexOf(initChannel.group);

        List<Channel> tempGroupChannels = new ArrayList<>();
        for (Channel ch : channelSourceList) {
            if (ch.group.equals(initChannel.group)) {
                tempGroupChannels.add(ch);
            }
        }
        final List<Channel> groupChannels = tempGroupChannels;
        int channelPos = groupChannels.indexOf(initChannel);

        ArrayAdapter<String> groupAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(17);
                tv.setPadding(15, 18, 15, 18);
                return v;
            }
        };
        lvGroup.setAdapter(groupAdapter);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        ArrayAdapter<Channel> channelAdapter = new ArrayAdapter<Channel>(this, android.R.layout.simple_list_item_1, groupChannels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                tv.setPadding(15, 18, 15, 18);
                tv.setText(getItem(position).name);
                return v;
            }
        };
        lvChannelList.setAdapter(channelAdapter);
        lvChannelList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        ArrayAdapter<String> dateAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, weekNames) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                tv.setPadding(15, 18, 15, 18);
                return v;
            }
        };
        lvDate.setAdapter(dateAdapter);
        lvDate.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        final ArrayAdapter<Channel.EpgItem> epgAdapter = new ArrayAdapter<Channel.EpgItem>(this, R.layout.item_epg, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_epg, parent, false);
                }
                Channel.EpgItem item = getItem(position);
                TextView tvDay = convertView.findViewById(R.id.tv_dayName);
                TextView tvTime = convertView.findViewById(R.id.tv_time);
                TextView tvTitle = convertView.findViewById(R.id.tv_title);
                TextView tvTag = convertView.findViewById(R.id.tv_action);

                tvDay.setText(item.dayName);
                tvTime.setText(item.time);
                tvTitle.setText(item.title);

                if (item.isNow) {
                    tvTag.setText("直播中");
                    tvTag.setBackgroundColor(0xFFFF6600);
                } else if (item.isPast) {
                    tvTag.setText("回看");
                    tvTag.setBackgroundColor(0xFF4CAF50);
                } else {
                    tvTag.setText("未播");
                    tvTag.setBackgroundColor(0xFF666666);
                }
                tvTitle.setTextColor(item.isNow ? 0xFFFF9900 : Color.WHITE);
                return convertView;
            }
        };
        lvEpg.setAdapter(epgAdapter);

        lvGroup.post(() -> {
            lvGroup.setItemChecked(groupPos, true);
            lvChannelList.setItemChecked(channelPos, true);
            lvDate.setItemChecked(0, true);

            epgAdapter.clear();
            for (Channel.EpgItem item : initChannel.epgList) {
                if (item.dayName.equals(weekNames.get(0))) {
                    epgAdapter.add(item);
                }
            }
            epgAdapter.notifyDataSetChanged();
        });

        lvGroup.setOnItemClickListener((parent, v, pos, id) -> {
            String selectGroup = groupList.get(pos);
            List<Channel> newChList = new ArrayList<>();
            for (Channel ch : channelSourceList) {
                if (ch.group.equals(selectGroup)) {
                    newChList.add(ch);
                }
            }
            channelAdapter.clear();
            channelAdapter.addAll(newChList);
            channelAdapter.notifyDataSetChanged();
            lvChannelList.setItemChecked(0, true);

            if (!newChList.isEmpty()) {
                Channel firstCh = newChList.get(0);
                int datePos = lvDate.getCheckedItemPosition();
                String day = weekNames.get(datePos);
                epgAdapter.clear();
                for (Channel.EpgItem item : firstCh.epgList) {
                    if (item.dayName.equals(day)) {
                        epgAdapter.add(item);
                    }
                }
                epgAdapter.notifyDataSetChanged();
            }
        });

        lvChannelList.setOnItemClickListener((parent, v, pos, id) -> {
            Channel selectCh = channelAdapter.getItem(pos);
            int datePos = lvDate.getCheckedItemPosition();
            String day = weekNames.get(datePos);
            epgAdapter.clear();
            for (Channel.EpgItem item : selectCh.epgList) {
                if (item.dayName.equals(day)) {
                    epgAdapter.add(item);
                }
            }
            epgAdapter.notifyDataSetChanged();
        });

        lvDate.setOnItemClickListener((parent, v, pos, id) -> {
            String selectDay = weekNames.get(pos);
            int chPos = lvChannelList.getCheckedItemPosition();
            if (chPos >= 0 && chPos < channelAdapter.getCount()) {
                Channel currCh = channelAdapter.getItem(chPos);
                epgAdapter.clear();
                for (Channel.EpgItem item : currCh.epgList) {
                    if (item.dayName.equals(selectDay)) {
                        epgAdapter.add(item);
                    }
                }
                epgAdapter.notifyDataSetChanged();
            }
        });

        lvEpg.setOnItemClickListener((parent, v, pos, id) -> {
            Channel.EpgItem item = epgAdapter.getItem(pos);
            if (item.isPast || item.isNow) {
                playEpgItem(item);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .show();
        dialog.setOnDismissListener(dialog1 -> playerView.requestFocus());
    }

    private void showSettingDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_setting, null);

        Switch swReverse = view.findViewById(R.id.switch_reverse);
        Switch swBoot = view.findViewById(R.id.switch_boot);
        Switch swUpdate = view.findViewById(R.id.switch_update);
        TextView tvRatio = view.findViewById(R.id.tv_ratio);
        TextView btnSource = view.findViewById(R.id.btn_source);
        TextView btnEpg = view.findViewById(R.id.btn_epg);
        TextView btnQr = view.findViewById(R.id.btn_qr);

        swReverse.setChecked(sp.getBoolean("reverse_channel", false));
        swBoot.setChecked(sp.getBoolean("boot_play", false));
        swUpdate.setChecked(sp.getBoolean("auto_update", true));
        tvRatio.setText(ratioNames[currentRatioIndex]);

        swReverse.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp.edit().putBoolean("reverse_channel", isChecked).apply());

        swBoot.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp.edit().putBoolean("boot_play", isChecked).apply());

        swUpdate.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp.edit().putBoolean("auto_update", isChecked).apply());

        tvRatio.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this)
                .setTitle("画面比例")
                .setItems(ratioNames, (dialog, which) -> {
                    setRatio(which);
                    tvRatio.setText(ratioNames[which]);
                })
                .setNegativeButton("取消", null)
                .show());

        btnSource.setOnClickListener(v -> {
            View editView = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
            EditText et = editView.findViewById(R.id.et_input);
            et.setText(sp.getString("custom_source", ""));
            new AlertDialog.Builder(this)
                    .setTitle("自定义直播源")
                    .setView(editView)
                    .setPositiveButton("保存", (dialog, which) -> {
                        String url = et.getText().toString().trim();
                        sp.edit().putString("custom_source", url).apply();
                        saveSourceHistory(url);
                        Toast.makeText(this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btnEpg.setOnClickListener(v -> {
            View editView = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
            EditText et = editView.findViewById(R.id.et_input);
            et.setText(sp.getString("custom_epg", ""));
            new AlertDialog.Builder(this)
                    .setTitle("自定义EPG地址")
                    .setView(editView)
                    .setPositiveButton("保存", (dialog, which) -> {
                        String url = et.getText().toString().trim();
                        sp.edit().putString("custom_epg", url).apply();
                        Toast.makeText(this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btnQr.setOnClickListener(v -> showQrDialog());

        new AlertDialog.Builder(this)
                .setView(view)
                .setNegativeButton("关闭", null)
                .show();
    }

    public void onReceiveConfig(String liveUrl, String epgUrl) {
        sp.edit()
                .putString("custom_source", liveUrl)
                .putString("custom_epg", epgUrl)
                .apply();
        customSource = liveUrl;
        customEpg = epgUrl;
        runOnUiThread(() -> Toast.makeText(this, "配置已接收，重启生效", Toast.LENGTH_SHORT).show());
    }

    private void checkUpdate() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            BufferedReader br = null;
            try {
                URL url = new URL(UPDATE_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                if (conn.getResponseCode() != 200) return;

                br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                JSONObject json = new JSONObject(sb.toString());
                int serverVer = json.getInt("versionCode");
                String serverVerName = json.getString("versionName");
                String desc = json.getString("desc");
                latestApkUrl = json.getString("apkUrl");
                int localVer = BuildConfig.VERSION_CODE;

                if (serverVer > localVer) {
                    runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                            .setTitle("发现新版本 " + serverVerName)
                            .setMessage(desc)
                            .setPositiveButton("立即更新", (dialog, which) -> startDownloadApk())
                            .setNegativeButton("稍后", null)
                            .show());
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    if (br != null) br.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void startDownloadApk() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_INSTALL_PACKAGES);
                return;
            }
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream is = null;
            FileOutputStream fos = null;
            try {
                URL url = new URL(latestApkUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.connect();
                is = conn.getInputStream();
                File dir = new File(getExternalFilesDir(null), "update");
                if (!dir.exists()) dir.mkdirs();
                File apkFile = new File(dir, "update.apk");
                fos = new FileOutputStream(apkFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    Uri uri;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        uri = FileProvider.getUriForFile(MainActivity.this,
                                BuildConfig.APPLICATION_ID + ".fileprovider", apkFile);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        uri = Uri.fromFile(apkFile);
                    }
                    intent.setDataAndType(uri, "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "下载失败", Toast.LENGTH_SHORT).show());
            } finally {
                try {
                    if (fos != null) fos.close();
                    if (is != null) is.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {
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
                    startDownloadApk();
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
        if (exoPlayer != null) {
            exoPlayer.release();
        }
        if (playbackPlayer != null) {
            playbackPlayer.release();
        }
    }
}
