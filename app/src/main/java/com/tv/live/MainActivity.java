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

        // 最安全播放器初始化（绝不闪退）
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
                if (startMs < now) item.isPast = true;
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
            case 0: playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case 1: playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case 2: playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
            case 3: playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
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
        if (sourceHistoryList.size() > 10) sourceHistoryList = sourceHistoryList.subList(0, 10);
        sp.edit().putString("source_history_list", gson.toJson(sourceHistoryList)).apply();
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.isSiteLocalAddress())
                        return inetAddress.getHostAddress();
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
                .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
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
        if (keyCode == KeyEvent.KEYCODE_MENU) {
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

    private void playChannel(int index) {
        if (channelSourceList.isEmpty()) return;
        currentPlayIndex = index;
        currentChannelIndex = index;
        Channel ch = channelSourceList.get(index);
        int line = Math.min(setting.getLine(), ch.urls.size()-1);
        String url = ch.urls.get(line);

        // 最安全播放（不闪退）
        exoPlayer.stop();
        exoPlayer.clearMediaItems();
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
        if (playbackPlayer == null) playbackPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(playbackPlayer);
        playbackPlayer.setMediaItem(MediaItem.fromUri(item.playUrl));
        playbackPlayer.prepare();
        playbackPlayer.play();
        isPlayingPlayback = true;
        Toast.makeText(this, "回看：" + item.title, Toast.LENGTH_SHORT).show();
    }

    private void loadChannels() {
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
                                    if (!channelSourceList.isEmpty()) {
                                        int playIdx = Math.min(lastPlayIndex, channelSourceList.size() - 1);
                                        playChannel(playIdx);
                                    }
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
    }
    private void showChannelListDialog() {
    // 基础数据保护
    if (channelSourceList == null || channelSourceList.isEmpty()) {
        Toast.makeText(this, "暂无频道数据，请稍后再试", Toast.LENGTH_SHORT).show();
        return;
    }
    if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
        currentPlayIndex = 0;
    }

    // 加载布局
    View v;
    try {
        v = LayoutInflater.from(this).inflate(R.layout.dialog_channel_list, null);
    } catch (Exception e) {
        Toast.makeText(this, "加载频道列表失败", Toast.LENGTH_SHORT).show();
        return;
    }

    // 绑定四栏控件
    ListView lvGroup = v.findViewById(R.id.lv_group);
    ListView lvChannelList = v.findViewById(R.id.lv_channel_list);
    ListView lvDate = v.findViewById(R.id.lv_date);
    ListView lvEpg = v.findViewById(R.id.lv_epg);

    if (lvGroup == null || lvChannelList == null || lvDate == null || lvEpg == null) {
        Toast.makeText(this, "频道列表控件加载失败", Toast.LENGTH_SHORT).show();
        return;
    }

    // 当前频道信息
    Channel curr = channelSourceList.get(currentPlayIndex);

    // 1. 频道分组数据
    Set<String> groupSet = new LinkedHashSet<>();
    for (Channel ch : channelSourceList) groupSet.add(ch.group);
    List<String> groupList = new ArrayList<>(groupSet);
    int gPos = groupList.indexOf(curr.group);

    // 2. 当前分组下的频道列表
    List<Channel> currentGroupChannels = new ArrayList<>();
    for (Channel ch : channelSourceList) {
        if (ch.group.equals(curr.group)) {
            currentGroupChannels.add(ch);
        }
    }
    int channelPos = currentGroupChannels.indexOf(curr);

    // 3. 频道分组适配器
    ArrayAdapter<String> groupAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, groupList) {
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
    lvGroup.setAdapter(groupAdapter);
    lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

    // 4. 频道列表适配器
    ArrayAdapter<Channel> channelAdapter = new ArrayAdapter<Channel>(this, android.R.layout.simple_list_item_1, currentGroupChannels) {
        @Override
        public View getView(int pos, View cv, ViewGroup p) {
            View view = super.getView(pos, cv, p);
            TextView tv = view.findViewById(android.R.id.text1);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16);
            tv.setPadding(15, 18, 15, 18);
            tv.setText(getItem(pos).name);
            return view;
        }
    };
    lvChannelList.setAdapter(channelAdapter);
    lvChannelList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

    // 5. 日期列表适配器
    ArrayAdapter<String> dateAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, weekNames) {
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
    lvDate.setAdapter(dateAdapter);
    lvDate.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

    // 6. 节目单适配器
    ArrayAdapter<Channel.EpgItem> epgAdapter = new ArrayAdapter<Channel.EpgItem>(this, R.layout.item_epg, new ArrayList<>()) {
        @Override
        public View getView(int pos, View cv, ViewGroup p) {
            if (cv == null) cv = LayoutInflater.from(getContext()).inflate(R.layout.item_epg, p, false);
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
            title.setTextColor(item.isNow ? 0xFFFF9900 : Color.WHITE);
            return cv;
        }
    };
    lvEpg.setAdapter(epgAdapter);

    // 初始化选中状态
    lvGroup.post(() -> {
        lvGroup.setItemChecked(gPos, true);
        lvChannelList.setItemChecked(channelPos, true);
        lvDate.setItemChecked(0, true);

        // 加载当天节目单
        epgAdapter.clear();
        for (Channel.EpgItem item : curr.epgList) {
            if (item.dayName.equals(weekNames.get(0))) {
                epgAdapter.add(item);
            }
        }
        epgAdapter.notifyDataSetChanged();
    });

    // 1. 分组点击事件：联动频道列表
    lvGroup.setOnItemClickListener((parent, view, pos, id) -> {
        String selectedGroup = groupList.get(pos);
        List<Channel> newChannels = new ArrayList<>();
        for (Channel ch : channelSourceList) {
            if (ch.group.equals(selectedGroup)) {
                newChannels.add(ch);
            }
        }
        channelAdapter.clear();
        channelAdapter.addAll(newChannels);
        channelAdapter.notifyDataSetChanged();
        lvChannelList.setItemChecked(0, true);

        // 默认选中第一个频道
        if (!newChannels.isEmpty()) {
            curr = newChannels.get(0);
            epgAdapter.clear();
            for (Channel.EpgItem item : curr.epgList) {
                if (item.dayName.equals(weekNames.get(0))) {
                    epgAdapter.add(item);
                }
            }
            epgAdapter.notifyDataSetChanged();
        }
    });

    // 2. 频道列表点击事件：联动节目单
    lvChannelList.setOnItemClickListener((parent, view, pos, id) -> {
        curr = channelAdapter.getItem(pos);
        epgAdapter.clear();
        for (Channel.EpgItem item : curr.epgList) {
            if (item.dayName.equals(weekNames.get(lvDate.getCheckedItemPosition()))) {
                epgAdapter.add(item);
            }
        }
        epgAdapter.notifyDataSetChanged();
    });

    // 3. 日期列表点击事件：联动节目单
    lvDate.setOnItemClickListener((parent, view, pos, id) -> {
        String selectedDay = weekNames.get(pos);
        epgAdapter.clear();
        for (Channel.EpgItem item : curr.epgList) {
            if (item.dayName.equals(selectedDay)) {
                epgAdapter.add(item);
            }
        }
        epgAdapter.notifyDataSetChanged();
    });

    // 4. 节目单点击事件：回看功能
    lvEpg.setOnItemClickListener((parent, view, pos, id) -> {
        Channel.EpgItem item = epgAdapter.getItem(pos);
        if (item.isPast || item.isNow) {
            playEpgItem(item);
        }
    });

    // 创建弹窗
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
        switch_reverse.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean("reverse_channel", isChecked).apply();
            }
        });
        switch_boot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ed.putBoolean("boot_start", isChecked).apply();
            }
        });
        switch_update.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ed.putBoolean("auto_update", isChecked).apply();
                if (isChecked) checkUpdate();
            }
        });
        tv_ratio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("画面比例")
                        .setItems(ratioNames, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                currentRatioIndex = which;
                                tv_ratio.setText(ratioNames[currentRatioIndex]);
                                setRatio(which);
                                Toast.makeText(MainActivity.this, "已切换："+ratioNames[which], Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                            }
                        })
                        .show();
            }
        });
        btn_qr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showDynamicQrCodeDialog();
            }
        });
        btn_source.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                View ev = LayoutInflater.from(MainActivity.this).inflate(R.layout.dialog_edit, null);
                EditText et = ev.findViewById(R.id.et_input);
                et.setText(sp.getString("custom_source", ""));
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("自定义直播源")
                    .setView(ev)
                    .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String url = et.getText().toString().trim();
                            ed.putString("custom_source", url).apply();
                            saveSourceHistory(url);
                            Toast.makeText(MainActivity.this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .show();
            }
        });
        btn_epg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                View ev = LayoutInflater.from(MainActivity.this).inflate(R.layout.dialog_edit, null);
                EditText et = ev.findViewById(R.id.et_input);
                et.setText(sp.getString("custom_epg", ""));
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("自定义EPG")
                    .setView(ev)
                    .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String url = et.getText().toString().trim();
                            ed.putString("custom_epg", url).apply();
                            Toast.makeText(MainActivity.this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .show();
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setView(v).setNegativeButton("关闭", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        }).show();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                playerView.requestFocus();
            }
        });
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
                    while ((line = reader.readLine()) != null) sb.append(line);
                    JSONObject json = new JSONObject(sb.toString());
                    int svc = json.getInt("versionCode");
                    String svn = json.getString("versionName");
                    String msg = json.getString("message");
                    latestApkUrl = json.getString("downloadUrl");
                    int cvc = BuildConfig.VERSION_CODE;
                    if (svc > cvc) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("发现新版本 v" + svn)
                                        .setMessage(msg)
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
                                        .setNegativeButton("稍后再说", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                dialog.dismiss();
                                            }
                                        })
                                        .show();
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try { if (reader != null) reader.close(); if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
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
                    while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                Uri uri;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    uri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", outFile);
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
