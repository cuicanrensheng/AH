package com.tv.live;
import android.widget.TextView;
import android.widget.AdapterView;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public int currentChannelIndex = 0;

    public static class Channel {
        public String name;
        public List<String> urls;
        public List<EpgItem> epgList;

        public static class EpgItem {
            public String time;
            public String title;
            public String playUrl;
            public boolean isNow;
        }

        public Channel(String name, List<String> urls) {
            this.name = name;
            this.urls = urls;
            this.epgList = new ArrayList<>();
        }
    }

    public List<Channel> channels = new ArrayList<>();
    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private SettingsManager setting;
    private List<Channel> channelSourceList = new ArrayList<>();
    private int currentPlayIndex = 0;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private final String[] scaleArr = {"原始比例","16:9比例","全屏拉伸"};
    private final String[] decodeArr = {"自动解码","硬件解码","软件解码"};
    private GestureDetector gestureDetector;
    private boolean channelReverse = false;
    private boolean bootAutoStart = false;
    private boolean epgEnabled = true;
    private boolean uiVisible = false;
    private SharedPreferences sp;

    // ====================== 保存上次播放频道 ======================
    private int lastPlayChannelIndex = 0;

    private final String URL1 = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
    private final String URL2 = "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u";
    private final String URL3 = "https://gitee.com/qf_1111/iptv/raw/master/iptvedqw.m3u";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        setContentView(R.layout.activity_main);
        mInstance = this;

        sp = getSharedPreferences("tv_config", MODE_PRIVATE);
        channelReverse = sp.getBoolean("channelReverse", false);
        bootAutoStart = sp.getBoolean("bootAutoStart", false);
        epgEnabled = sp.getBoolean("epgEnabled", true);

        // ========== 读取上次播放的频道 ==========
        lastPlayChannelIndex = sp.getInt("last_play_channel", 0);

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setFocusable(false);
        playerView.setFocusableInTouchMode(false);
        setting = SettingsManager.getInstance(this);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);

        gestureDetector = new GestureDetector(this, new MyGestureListener());
        playerView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });

        initExoPlayer();
        applyAllSetting();
        loadSource(URL1);
        setUI(false);
    }

    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            float dy = e2.getY() - e1.getY();
            if (Math.abs(dy) > 80) {
                if (dy > 0) changeChannel(channelReverse ? -1 : 1);
                else changeChannel(channelReverse ? 1 : -1);
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            showChannelListDialog();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            showSettingDialog();
        }
    }

    private void setUI(boolean show) {
        uiVisible = show;
        int vis = show ? View.VISIBLE : View.GONE;
        try {
            findViewById(R.id.btn_line).setVisibility(vis);
            findViewById(R.id.btn_scale).setVisibility(vis);
            findViewById(R.id.btn_decode).setVisibility(vis);
            findViewById(R.id.btn_timeout).setVisibility(vis);
            findViewById(R.id.btn_sub).setVisibility(vis);
        } catch (Exception ignored) {}
    }

    private void showSourceDialog() {
        new AlertDialog.Builder(this)
                .setTitle("选择直播源")
                .setItems(new String[]{"源1","源2","源3","自定义"}, (d, w) -> {
                    if (w == 0) loadSource(URL1);
                    if (w == 1) loadSource(URL2);
                    if (w == 2) loadSource(URL3);
                    if (w == 3) showCustomSourceInput();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSettingDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_setting, null);
        Switch sw_reverse = view.findViewById(R.id.sw_reverse);
        Switch sw_boot = view.findViewById(R.id.sw_boot);
        Switch sw_epg = view.findViewById(R.id.sw_epg);
        Switch sw_timeout = view.findViewById(R.id.sw_timeout);
        Spinner sp_line = view.findViewById(R.id.sp_line);
        Spinner sp_scale = view.findViewById(R.id.sp_scale);
        Spinner sp_decode = view.findViewById(R.id.sp_decode);
        EditText et_timeout_sec = view.findViewById(R.id.et_timeout_sec);
        EditText et_sub_url = view.findViewById(R.id.et_sub_url);

        sw_reverse.setChecked(channelReverse);
        sw_boot.setChecked(bootAutoStart);
        sw_epg.setChecked(epgEnabled);
        sw_timeout.setChecked(setting.isTimeoutEnable());

        String[] lineArr = {"线路1", "线路2", "线路3"};
        ArrayAdapter<String> lineAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, lineArr);
        sp_line.setAdapter(lineAdapter);
        sp_line.setSelection(setting.getLine());
        sp_line.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                setting.setLine(position);
                playChannel(currentPlayIndex);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        String[] scaleArr = {"原始", "16:9", "全屏拉伸"};
        ArrayAdapter<String> scaleAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, scaleArr);
        sp_scale.setAdapter(scaleAdapter);
        sp_scale.setSelection(setting.getScale());
        sp_scale.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                setting.setScale(position);
                applyAllSetting();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        String[] decodeArr = {"自动", "硬解", "软解"};
        ArrayAdapter<String> decodeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, decodeArr);
        sp_decode.setAdapter(decodeAdapter);
        sp_decode.setSelection(setting.getDecode());
        sp_decode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                setting.setDecode(position);
                initExoPlayer();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        et_timeout_sec.setText(String.valueOf(setting.getTimeoutSec()));
        et_sub_url.setText(setting.getSubUrl());

        new AlertDialog.Builder(this)
                .setTitle("播放设置")
                .setView(view)
                .setPositiveButton("保存", (dialog, which) -> {
                    channelReverse = sw_reverse.isChecked();
                    bootAutoStart = sw_boot.isChecked();
                    epgEnabled = sw_epg.isChecked();
                    setting.setTimeoutEnable(sw_timeout.isChecked());

                    try {
                        int sec = Integer.parseInt(et_timeout_sec.getText().toString());
                        setting.setTimeoutSec(sec);
                    } catch (Exception e) {
                        setting.setTimeoutSec(6);
                    }
                    setting.setSubUrl(et_sub_url.getText().toString().trim());

                    sp.edit().putBoolean("channelReverse", channelReverse)
                            .putBoolean("bootAutoStart", bootAutoStart)
                            .putBoolean("epgEnabled", epgEnabled).apply();
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("切换直播源", (d, w) -> showSourceDialog())
                .show();
    }

    private void showCustomSourceInput() {
        EditText et = new EditText(this);
        et.setHint("输入m3u地址");
        new AlertDialog.Builder(this)
                .setTitle("自定义直播源")
                .setView(et)
                .setPositiveButton("播放", (d, w) -> {
                    String input = et.getText().toString().trim();
                    if (!input.isEmpty()) loadSource(input);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void loadSource(String url) {
    new Thread(() -> {
        try {
            List<Channel> res = PlaylistParser.parseWithRealName(url);
            runOnUiThread(() -> {
                channelSourceList = res;
                channels = res;

                // 下载真实EPG，绑定到每个频道
                EpgManager.getInstance().downloadAndParseEpg(this, () -> {
                    for (Channel ch : channelSourceList) {
                        ch.epgList = EpgManager.getInstance().getEpgByChannelName(ch.name);
                    }
                    // 恢复上次播放频道
                    if (!res.isEmpty()) {
                        if (lastPlayChannelIndex >= 0 && lastPlayChannelIndex < res.size()) {
                            playChannel(lastPlayChannelIndex);
                        } else {
                            playChannel(0);
                        }
                    }
                    Toast.makeText(this, "已加载：" + res.size() + "个频道", Toast.LENGTH_SHORT).show();
                });
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show());
        }
    }).start();
}

   
    private void changeChannel(int delta) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIndex = currentPlayIndex + delta;
        if (newIndex < 0) newIndex = channelSourceList.size() - 1;
        if (newIndex >= channelSourceList.size()) newIndex = 0;
        currentChannelIndex = newIndex;
        playChannel(newIndex);
    }

    private void showChannelListDialog() {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            Toast.makeText(this, "暂无频道", Toast.LENGTH_SHORT).show();
            return;
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_channel_epg, null);
        ListView lvChannel = view.findViewById(R.id.lv_channel);
        ListView lvEpg = view.findViewById(R.id.lv_epg);

        ArrayAdapter<Channel> channelAdapter = new ArrayAdapter<Channel>(this,
                android.R.layout.simple_list_item_1, channelSourceList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                Channel ch = getItem(position);
                String nowText = "";
                if (epgEnabled && ch.epgList != null && !ch.epgList.isEmpty()) {
                    for (Channel.EpgItem item : ch.epgList) {
                        if (item.isNow) {
                            nowText = "\n▶ " + item.title;
                            break;
                        }
                    }
                }
                tv.setText(ch.name + nowText);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                return v;
            }
        };
        lvChannel.setAdapter(channelAdapter);
        lvChannel.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lvChannel.setItemChecked(currentPlayIndex, true);

        ArrayAdapter<Channel.EpgItem> epgAdapter = new ArrayAdapter<Channel.EpgItem>(this,
                android.R.layout.simple_list_item_1, channelSourceList.get(currentPlayIndex).epgList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                Channel.EpgItem item = getItem(position);
                String text = item.time + "    " + item.title;
                if (!TextUtils.isEmpty(item.playUrl)) text += "    回放";
                if (item.isNow) text = "▶ " + text;
                tv.setText(text);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(15);
                return v;
            }
        };
        lvEpg.setAdapter(epgAdapter);

        lvChannel.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                currentPlayIndex = position;
                playChannel(position);
                Channel ch = channelSourceList.get(position);
                epgAdapter.clear();
                epgAdapter.addAll(ch.epgList);
                epgAdapter.notifyDataSetChanged();
            }
        });
         lvEpg.setOnItemClickListener(new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
            Channel.EpgItem item = channelSourceList.get(currentPlayIndex).epgList.get(position);
            if (!TextUtils.isEmpty(item.playUrl)) {
                exoPlayer.stop();
                exoPlayer.setMediaItem(MediaItem.fromUri(item.playUrl));
                exoPlayer.prepare();
                exoPlayer.play();
                Toast.makeText(MainActivity.this, "正在播放回放：" + item.title, Toast.LENGTH_SHORT).show();
            }
        }
    });

    new AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("关闭", null)
            .show();
}

    private void initExoPlayer() {
        DefaultRenderersFactory factory = new DefaultRenderersFactory(this);
        int mode = setting.getDecode();
        if (mode == SettingsManager.DECODE_HARD) factory.setEnableDecoderFallback(false);
        else if (mode == SettingsManager.DECODE_SOFT) factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        if (exoPlayer != null) exoPlayer.release();
        exoPlayer = new ExoPlayer.Builder(this).setRenderersFactory(factory).build();
        playerView.setPlayer(exoPlayer);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) cancelTimeoutTask();
            }
            @Override
            public void onPlayerError(PlaybackException error) { autoSwitchLine(); }
        });
    }

    private void applyAllSetting() {
        int s = setting.getScale();
        if (s == SettingsManager.SCALE_16_9) playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH);
        else if (s == SettingsManager.SCALE_FILL) playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        else playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
    }

    private void playChannel(int index) {
        if (channelSourceList.isEmpty() || index < 0 || index >= channelSourceList.size()) return;
        currentPlayIndex = index;
        currentChannelIndex = index;
        Channel ch = channelSourceList.get(index);
        int line = setting.getLine();
        if (line >= ch.urls.size()) line = 0;
        String url = ch.urls.get(line);

        exoPlayer.stop();
        exoPlayer.clearMediaItems();
        exoPlayer.setMediaItem(MediaItem.fromUri(url));
        exoPlayer.prepare();
        exoPlayer.play();
        startTimeoutCheck();
        Toast.makeText(this, "正在播放：" + ch.name, Toast.LENGTH_SHORT).show();

        // ========== 保存当前频道 ==========
        lastPlayChannelIndex = index;
        sp.edit().putInt("last_play_channel", lastPlayChannelIndex).apply();
    }

    private void startTimeoutCheck() {
        cancelTimeoutTask();
        if (!setting.getTimeoutEnable()) return;
        int ms = setting.getTimeoutSec() * 1000;
        timeoutRunnable = this::autoSwitchLine;
        mainHandler.postDelayed(timeoutRunnable, ms);
    }

    private void cancelTimeoutTask() {
        if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
    }

    private void autoSwitchLine() {
        Channel ch = channelSourceList.get(currentPlayIndex);
        int now = setting.getLine();
        if (now + 1 < ch.urls.size()) {
            setting.setLine(now + 1);
            playChannel(currentPlayIndex);
        } else {
            Toast.makeText(this, "本频道所有线路失效", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: changeChannel(channelReverse ? 1 : -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: changeChannel(channelReverse ? -1 : 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: showChannelListDialog(); return true;
            case KeyEvent.KEYCODE_HELP: showSettingDialog(); return true;
            case KeyEvent.KEYCODE_BACK: finish(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) exoPlayer.pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 退出时保存频道
        sp.edit().putInt("last_play_channel", lastPlayChannelIndex).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelTimeoutTask();
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
    }
}
