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
    private boolean uiVisible = false; // 默认隐藏按钮

    private SharedPreferences sp;

    // 3个内置直播源
    private final String URL1 = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
    private final String URL2 = "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u";
    private final String URL3 = "https://gitee.com/qf_1111/iptv/raw/master/iptvedqw.m3u";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 强制全屏，消除系统黑边黑块
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        setContentView(R.layout.activity_main);
        mInstance = this;

        sp = getSharedPreferences("tv_config", MODE_PRIVATE);
        channelReverse = sp.getBoolean("channelReverse", false);
        bootAutoStart = sp.getBoolean("bootAutoStart", false);
        epgEnabled = sp.getBoolean("epgEnabled", true);

        playerView = findViewById(R.id.player_view);
        setting = SettingsManager.getInstance(this);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);

        // 代码强制播放器溢出5%，彻底覆盖黑边黑块（无XML百分比报错）
        playerView.post(() -> {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) playerView.getLayoutParams();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 1.05f);
            params.height = (int) (getResources().getDisplayMetrics().heightPixels * 1.05f);
            params.gravity = android.view.Gravity.CENTER;
            playerView.setLayoutParams(params);
        });

        gestureDetector = new GestureDetector(this, new MyGestureListener());
        playerView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            // 单击隐藏按钮
            if (event.getAction() == MotionEvent.ACTION_UP && uiVisible) {
                setUI(false);
            }
            return true;
        });

        initExoPlayer();
        applyAllSetting();
        loadSource(URL1);
        setUI(false); // 默认隐藏按钮

        findViewById(R.id.btn_line).setOnClickListener(v -> showLineDialog());
        findViewById(R.id.btn_scale).setOnClickListener(v -> showScaleDialog());
        findViewById(R.id.btn_decode).setOnClickListener(v -> showDecodeDialog());
        findViewById(R.id.btn_timeout).setOnClickListener(v -> showTimeoutDialog());
        findViewById(R.id.btn_sub).setOnClickListener(v -> showSourceDialog());
    }

    // 手势监听：双击显示按钮，单击弹出频道列表，长按设置，上下滑切台
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
        // 双击：隐藏→显示，显示→隐藏
        if (uiVisible) {
            setUI(false);
        } else {
            setUI(true);
        }
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

    // 控制按钮显隐
    private void setUI(boolean show) {
        uiVisible = show;
        int vis = show ? View.VISIBLE : View.GONE;
        findViewById(R.id.btn_line).setVisibility(vis);
        findViewById(R.id.btn_scale).setVisibility(vis);
        findViewById(R.id.btn_decode).setVisibility(vis);
        findViewById(R.id.btn_timeout).setVisibility(vis);
        findViewById(R.id.btn_sub).setVisibility(vis);
    }

    // 直播源选择弹窗（新增：自定义源/虎牙源输入）
    private void showSourceDialog() {
        new AlertDialog.Builder(this)
                .setTitle("选择直播源")
                .setItems(new String[]{"源1","源2","源3","自定义/虎牙源"}, (d, w) -> {
                    if (w == 0) loadSource(URL1);
                    if (w == 1) loadSource(URL2);
                    if (w == 2) loadSource(URL3);
                    if (w == 3) showCustomSourceInput(); // 输入虎牙链接/自定义m3u8
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 输入框：支持 虎牙房间号/虎牙链接/普通m3u8
    private void showCustomSourceInput() {
        EditText et = new EditText(this);
        et.setHint("输入：虎牙房间号/虎牙链接/m3u8地址");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("自定义直播源")
                .setView(et)
                .setPositiveButton("播放", (d, w) -> {
                    String input = et.getText().toString().trim();
                    if (input.isEmpty()) return;
                    loadCustomOrHuyaSource(input);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 自动判断：虎牙源/普通m3u8
    private void loadCustomOrHuyaSource(String input) {
        new Thread(() -> {
            try {
                String realUrl;
                // 判断是否为虎牙
                if (input.contains("huya") || input.matches("\\d+")) {
                    realUrl = HuyaParser.getHuyaRealUrl(input);
                    List<Channel> singleChannel = new ArrayList<>();
                    List<String> urls = new ArrayList<>();
                    urls.add(realUrl);
                    singleChannel.add(new Channel("虎牙直播", urls));
                    runOnUiThread(() -> {
                        channelSourceList = singleChannel;
                        channels = singleChannel;
                        playChannel(0);
                        Toast.makeText(this, "虎牙直播加载成功", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    // 普通m3u8直播源
                    List<Channel> res = PlaylistParser.parseWithRealName(input);
                    runOnUiThread(() -> {
                        channelSourceList = res;
                        channels = res;
                        if (!res.isEmpty()) playChannel(0);
                        Toast.makeText(this, "加载成功：" + res.size() + "个频道", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // 加载内置3个直播源
    private void loadSource(String url) {
        new Thread(() -> {
            try {
                List<Channel> res = PlaylistParser.parseWithRealName(url);
                runOnUiThread(() -> {
                    channelSourceList = res;
                    channels = res;
                    if (!res.isEmpty()) playChannel(0);
                    Toast.makeText(this, "已切换源：" + res.size() + "个频道", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "源加载失败", Toast.LENGTH_SHORT).show());
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
                    sp.edit().putBoolean("channelReverse", channelReverse)
                            .putBoolean("bootAutoStart", bootAutoStart)
                            .putBoolean("epgEnabled", epgEnabled).apply();
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("切换源", (d, w) -> showSourceDialog())
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
