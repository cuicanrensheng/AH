package com.tv.live;

import android.app.AlertDialog;
import android.content.DialogInterface;
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
        public Channel(String name, List<String> urls) {
            this.name = name;
            this.urls = urls;
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
    private boolean uiVisible = true;

    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mInstance = this;
        sp = getSharedPreferences("tv_config", MODE_PRIVATE);
        channelReverse = sp.getBoolean("channelReverse", false);
        bootAutoStart = sp.getBoolean("bootAutoStart", false);
        epgEnabled = sp.getBoolean("epgEnabled", true);

        playerView = findViewById(R.id.player_view);
        setting = SettingsManager.getInstance(this);

        gestureDetector = new GestureDetector(this, new MyGestureListener());
        playerView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });

        initExoPlayer();
        applyAllSetting();
        loadDefaultSource();

        findViewById(R.id.btn_line).setOnClickListener(v -> showLineDialog());
        findViewById(R.id.btn_scale).setOnClickListener(v -> showScaleDialog());
        findViewById(R.id.btn_decode).setOnClickListener(v -> showDecodeDialog());
        findViewById(R.id.btn_timeout).setOnClickListener(v -> showTimeoutDialog());
        findViewById(R.id.btn_sub).setOnClickListener(v -> loadSubscribeUrl());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                changeChannel(channelReverse ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                changeChannel(channelReverse ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                showChannelListDialog();
                return true;
            case KeyEvent.KEYCODE_HELP:
                showSettingDialog();
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
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
        public boolean onSingleTapConfirmed(MotionEvent e) {
            showChannelListDialog();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            toggleUI();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            showSettingDialog();
        }
    }

    private void toggleUI() {
        uiVisible = !uiVisible;
        int vis = uiVisible ? View.VISIBLE : View.GONE;
        findViewById(R.id.btn_line).setVisibility(vis);
        findViewById(R.id.btn_scale).setVisibility(vis);
        findViewById(R.id.btn_decode).setVisibility(vis);
        findViewById(R.id.btn_timeout).setVisibility(vis);
        findViewById(R.id.btn_sub).setVisibility(vis);
        Toast.makeText(this, uiVisible ? "显示控制" : "隐藏控制", Toast.LENGTH_SHORT).show();
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
        if (channelSourceList.isEmpty()) {
            Toast.makeText(this, "暂无频道", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[channelSourceList.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = channelSourceList.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("频道列表｜当前：" + channelSourceList.get(currentPlayIndex).name)
                .setSingleChoiceItems(names, currentPlayIndex, (dialog, which) -> {
                    currentChannelIndex = which;
                    playChannel(which);
                    dialog.dismiss();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showSettingDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_setting, null);
        Switch sw_reverse = view.findViewById(R.id.sw_reverse);
        Switch sw_boot = view.findViewById(R.id.sw_boot);
        Switch sw_epg = view.findViewById(R.id.sw_epg);

        sw_reverse.setChecked(channelReverse);
        sw_boot.setChecked(bootAutoStart);
        sw_epg.setChecked(epgEnabled);

        new AlertDialog.Builder(this)
                .setTitle("直播设置")
                .setView(view)
                .setPositiveButton("保存", (dialog, which) -> {
                    channelReverse = sw_reverse.isChecked();
                    bootAutoStart = sw_boot.isChecked();
                    epgEnabled = sw_epg.isChecked();

                    sp.edit()
                            .putBoolean("channelReverse", channelReverse)
                            .putBoolean("bootAutoStart", bootAutoStart)
                            .putBoolean("epgEnabled", epgEnabled)
                            .apply();

                    Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("切换直播源", (d, w) -> loadSubscribeUrl())
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
        else playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }
    
    private void loadDefaultSource() {
    new Thread(() -> {
        try {
            String url = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
            // 直接获取【名称+线路】
            List<Channel> result = PlaylistParser.parseFromUrlRealName(url);
            runOnUiThread(() -> {
                channelSourceList = result;
                channels = result;
                if (!channelSourceList.isEmpty()) playChannel(0);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }).start();
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
    }

    private void startTimeoutCheck() {
        cancelTimeoutTask();
        if (!setting.isTimeoutEnable()) return;
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

    private void showLineDialog() {
        int now = setting.getLine();
        new AlertDialog.Builder(this)
                .setTitle("切换线路")
                .setSingleChoiceItems(new String[]{"线路1","线路2","线路3"}, now, (d, p) -> {
                    setting.setLine(p);
                    playChannel(currentPlayIndex);
                    d.dismiss();
                }).show();
    }

    private void showScaleDialog() {
        int now = setting.getScale();
        new AlertDialog.Builder(this)
                .setTitle("画面比例")
                .setSingleChoiceItems(scaleArr, now, (d, p) -> {
                    setting.setScale(p);
                    applyAllSetting();
                    d.dismiss();
                }).show();
    }

    private void showDecodeDialog() {
        int now = setting.getDecode();
        new AlertDialog.Builder(this)
                .setTitle("解码模式")
                .setSingleChoiceItems(decodeArr, now, (d, p) -> {
                    setting.setDecode(p);
                    initExoPlayer();
                    applyAllSetting();
                    playChannel(currentPlayIndex);
                    d.dismiss();
                }).show();
    }

    private void showTimeoutDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_timeout, null);
        EditText et = v.findViewById(R.id.et_timeout);
        et.setText(String.valueOf(setting.getTimeoutSec()));
        new AlertDialog.Builder(this)
                .setTitle("超时自动换源(秒)")
                .setView(v)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int t = Integer.parseInt(et.getText().toString());
                        setting.setTimeoutSec(t);
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("关闭", (d, w) -> setting.setTimeoutEnable(false))
                .setNeutralButton("开启", (d, w) -> setting.setTimeoutEnable(true))
                .show();
    }

   private void loadSubscribeUrl() {
    EditText ed = new EditText(this);
    ed.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
    ed.setText("https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u");
    new AlertDialog.Builder(this)
            .setTitle("M3U订阅地址")
            .setView(ed)
            .setPositiveButton("加载", (d, w) -> {
                String url = ed.getText().toString().trim();
                setting.setSubUrl(url);
                Toast.makeText(this, "解析中...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try {
                        List<Channel> result = PlaylistParser.parseFromUrlRealName(url);
                        runOnUiThread(() -> {
                            channelSourceList = result;
                            channels = result;
                            if (!channelSourceList.isEmpty()) playChannel(0);
                            Toast.makeText(this, "共 " + result.size() + " 个频道", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "解析失败", Toast.LENGTH_SHORT).show());
                    }
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
}

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) exoPlayer.pause();
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
