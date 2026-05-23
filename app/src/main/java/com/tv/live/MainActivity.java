package com.tv.live;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
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
    private List<List<String>> channelSourceList = new ArrayList<>();
    private int currentPlayIndex = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    private final String[] scaleArr = {"原始比例","16:9比例","全屏拉伸"};
    private final String[] decodeArr = {"自动解码","硬件解码","软件解码"};

    private GestureDetector gestureDetector;
    private boolean channelReverse = false;
    private boolean bootAutoStart = false;
    private boolean epgEnabled = true;
    private boolean uiVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mInstance = this;
        playerView = findViewById(R.id.player_view);
        setting = SettingsManager.getInstance(this);

        initExoPlayer();
        applyAllSetting();
        initDefaultChannel();

        gestureDetector = new GestureDetector(this, new MyGestureListener());

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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event);
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
        findViewById(R.id.btn_line).setVisibility(uiVisible ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_scale).setVisibility(uiVisible ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_decode).setVisibility(uiVisible ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_timeout).setVisibility(uiVisible ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_sub).setVisibility(uiVisible ? View.VISIBLE : View.GONE);
        Toast.makeText(this, uiVisible ? "显示控制栏" : "隐藏控制栏", Toast.LENGTH_SHORT).show();
    }

    private void changeChannel(int delta) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIndex = currentPlayIndex + delta;
        if (newIndex < 0) newIndex = channelSourceList.size() - 1;
        if (newIndex >= channelSourceList.size()) newIndex = 0;
        playChannel(newIndex);
    }

    private void showChannelListDialog() {
        if (channelSourceList.isEmpty()) {
            Toast.makeText(this, "暂无频道", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[channelSourceList.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = "频道 " + (i + 1);
        }
        new AlertDialog.Builder(this)
                .setTitle("频道列表")
                .setItems(names, (dialog, which) -> playChannel(which))
                .show();
    }

    private void showSettingDialog() {
        String[] items = {"频道反转","开机自启","EPG节目单"};
        boolean[] checked = {channelReverse, bootAutoStart, epgEnabled};

        new AlertDialog.Builder(this)
                .setTitle("直播设置")
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> {
                    switch (which) {
                        case 0: channelReverse = isChecked; break;
                        case 1: bootAutoStart = isChecked; break;
                        case 2: epgEnabled = isChecked; break;
                    }
                })
                .setPositiveButton("确定", (d, w) -> {
                    getSharedPreferences("tv_config", MODE_PRIVATE)
                            .edit()
                            .putBoolean("channelReverse", channelReverse)
                            .putBoolean("bootAutoStart", bootAutoStart)
                            .putBoolean("epgEnabled", epgEnabled)
                            .apply();
                    Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
                })
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

    private void initDefaultChannel() {
        new Thread(() -> {
            try {
                String defaultUrl = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
                List<List<String>> list = PlaylistParser.parseFromUrl(defaultUrl);
                runOnUiThread(() -> {
                    channelSourceList = list;
                    if (!channelSourceList.isEmpty()) {
                        playChannel(0);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void playChannel(int index) {
        if (channelSourceList.isEmpty() || index < 0 || index >= channelSourceList.size()) return;
        currentPlayIndex = index;
        List<String> lines = channelSourceList.get(index);
        int line = setting.getLine();
        if (line >= lines.size()) line = 0;
        String url = lines.get(line);

        exoPlayer.stop();
        exoPlayer.clearMediaItems();
        exoPlayer.setMediaItem(MediaItem.fromUri(url));
        exoPlayer.prepare();
        exoPlayer.play();
        startTimeoutCheck();

        Toast.makeText(this, "正在播放：频道 " + (index + 1), Toast.LENGTH_SHORT).show();
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
        List<String> lines = channelSourceList.get(currentPlayIndex);
        int now = setting.getLine();
        if (now + 1 < lines.size()) {
            setting.setLine(now + 1);
            playChannel(currentPlayIndex);
        } else {
            Toast.makeText(this, "当前频道所有线路失效", Toast.LENGTH_SHORT).show();
        }
    }

    private void showLineDialog() {
        int now = setting.getLine();
        new AlertDialog.Builder(this)
                .setTitle("选择播放线路")
                .setSingleChoiceItems(new String[]{"线路1","线路2","线路3"}, now, (d, p) -> {
                    setting.setLine(p);
                    playChannel(currentPlayIndex);
                    d.dismiss();
                }).show();
    }

    private void showScaleDialog() {
        int now = setting.getScale();
        new AlertDialog.Builder(this)
                .setTitle("画面显示比例")
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
                .setNegativeButton("关闭自动换源", (d, w) -> setting.setTimeoutEnable(false))
                .setNeutralButton("开启自动换源", (d, w) -> setting.setTimeoutEnable(true))
                .show();
    }

    private void loadSubscribeUrl() {
        EditText ed = new EditText(this);
        ed.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        ed.setText("https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u");
        new AlertDialog.Builder(this)
                .setTitle("M3U订阅地址")
                .setView(ed)
                .setPositiveButton("确认加载", (d, w) -> {
                    String url = ed.getText().toString().trim();
                    setting.setSubUrl(url);
                    Toast.makeText(this, "正在解析频道列表...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        try {
                            List<List<String>> list = PlaylistParser.parseFromUrl(url);
                            runOnUiThread(() -> {
                                channelSourceList = list;
                                if (!channelSourceList.isEmpty()) playChannel(0);
                                Toast.makeText(this, "加载成功：" + list.size() + "个频道", Toast.LENGTH_SHORT).show();
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
