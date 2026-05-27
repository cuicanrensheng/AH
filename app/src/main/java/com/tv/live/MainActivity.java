package com.tv.live;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public int currentChannelIndex = 0;
    public int currentPlayIndex = 0;

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
    private SettingsManager setting;
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
    com.google.android.exoplayer2.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
    com.google.android.exoplayer2.C.VIDEO_SCALING_MODE_SCALE_TO_FIT,
    com.google.android.exoplayer2.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
};

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

        setting = SettingsManager.getInstance(this);
        initGesture();
        initExoPlayer();

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

    private Bitmap generateQrCode(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, size, 0, 0, size, size);
        return bmp;
    }

    private void showDynamicQrCodeDialog() {
        String ip = getLocalIpAddress();
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, "获取IP失败", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = "http://" + ip + ":10481";
        try {
            Bitmap bmp = generateQrCode(url, 320);
            View v = LayoutInflater.from(this).inflate(R.layout.dialog_qrcode, null);
            ImageView iv = v.findViewById(R.id.iv_qrcode);
            TextView tip = v.findViewById(R.id.tv_tip);
            iv.setImageBitmap(bmp);
            tip.setText("扫码管理订阅源/EPG\n" + url);
            new AlertDialog.Builder(this)
                    .setTitle("扫码设置")
                    .setView(v)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Exception e) {
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
            showChannelEpgDialog();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_HELP) {
            showSettingDialog();
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
                showChannelEpgDialog();
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
        playerView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
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

    private void showChannelEpgDialog() {
        if (channelSourceList.isEmpty()) {
            Toast.makeText(this, "暂无频道", Toast.LENGTH_SHORT).show();
            return;
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_channel_epg, null);
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
                tv.setText(((Channel) getItem(position)).name);
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

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .show();
        dialog.setOnDismissListener(dialogInterface -> playerView.requestFocus());
    }

    private void showSettingDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_setting, null);
        SharedPreferences.Editor ed = sp.edit();

        android.widget.Switch switch_reverse = v.findViewById(R.id.switch_reverse);
        android.widget.Switch switch_boot = v.findViewById(R.id.switch_boot);
        android.widget.Switch switch_update = v.findViewById(R.id.switch_update);
        android.widget.Switch switch_line = v.findViewById(R.id.switch_line);
        TextView tv_ratio = v.findViewById(R.id.tv_ratio);
        TextView btn_source = v.findViewById(R.id.btn_source);
        TextView btn_epg = v.findViewById(R.id.btn_epg);
        TextView btn_qrcode = v.findViewById(R.id.btn_qrcode);

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

        btn_qrcode.setOnClickListener(view -> showDynamicQrCodeDialog());

        btn_source.setOnClickListener(view1 -> {
            View dv = LayoutInflater.from(this).inflate(R.layout.dialog_source_history, null);
            EditText et = dv.findViewById(R.id.et_source);
            ListView lv = dv.findViewById(R.id.lv_history);
            TextView clear = dv.findViewById(R.id.tv_clear);
            et.setText(sp.getString("custom_source", ""));

            ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, sourceHistoryList);
            lv.setAdapter(ad);

            lv.setOnItemClickListener((parent, view12, position, id1) -> {
                et.setText(sourceHistoryList.get(position));
            });

            lv.setOnItemLongClickListener((parent, view13, position, id12) -> {
                sourceHistoryList.remove(position);
                ad.notifyDataSetChanged();
                sp.edit().putString("source_history_list", gson.toJson(sourceHistoryList)).apply();
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                return true;
            });

            clear.setOnClickListener(view14 -> {
                sourceHistoryList.clear();
                ad.notifyDataSetChanged();
                sp.edit().remove("source_history_list").apply();
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
            });

            new AlertDialog.Builder(this)
                    .setTitle("自定义订阅源")
                    .setView(dv)
                    .setPositiveButton("保存", (dialog, which) -> {
                        String url = et.getText().toString().trim();
                        ed.putString("custom_source", url).apply();
                        saveSourceHistory(url);
                        Toast.makeText(this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btn_epg.setOnClickListener(view1 -> {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("自定义节目单");
            EditText edit = new EditText(this);
            edit.setText(sp.getString("custom_epg", ""));
            edit.setHint("xml/xml.gz");
            b.setView(edit);
            b.setPositiveButton("保存", (d, w) -> {
                ed.putString("custom_epg", edit.getText().toString().trim()).apply();
                Toast.makeText(this, "已保存，重启生效", Toast.LENGTH_SHORT).show();
            });
            b.setNegativeButton("取消", null);
            b.show();
        });

        new AlertDialog.Builder(this)
                .setView(v)
                .setNegativeButton("关闭", null)
                .show();
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
        if (exoPlayer != null) exoPlayer.release();
        if (playbackPlayer != null) playbackPlayer.release();
    }
}
