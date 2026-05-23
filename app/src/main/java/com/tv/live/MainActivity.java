package com.tv.live;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.tv.live.SettingsManager;
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
    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private SettingsManager setting;
    private List<List<String>> channelSourceList = new ArrayList<>();
    private int currentPlayIndex = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    private final String[] scaleArr = {"原始比例", "16:9比例", "全屏拉伸"};
    private final String[] decodeArr = {"自动解码", "硬件解码", "软件解码"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 绑定控件（和你当前布局ID一一对应）
        playerView = findViewById(R.id.player_view);
        setting = SettingsManager.getInstance(this);

        // 初始化播放器
        initExoPlayer();
        // 应用画面比例设置
        applyAllSetting();
        // 初始化默认频道
        initDefaultChannel();

        // 绑定所有设置按钮（和你当前布局ID一一对应）
        findViewById(R.id.btn_line).setOnClickListener(v -> showLineDialog());
        findViewById(R.id.btn_scale).setOnClickListener(v -> showScaleDialog());
        findViewById(R.id.btn_decode).setOnClickListener(v -> showDecodeDialog());
        findViewById(R.id.btn_timeout).setOnClickListener(v -> showTimeoutDialog());
        findViewById(R.id.btn_sub).setOnClickListener(v -> loadSubscribeUrl());
    }

    // 初始化播放器
    private void initExoPlayer() {
        DefaultRenderersFactory renderFactory = new DefaultRenderersFactory(this);
        int decodeMode = setting.getDecode();
        switch (decodeMode) {
            case SettingsManager.DECODE_HARD:
                renderFactory.setEnableDecoderFallback(false);
                break;
            case SettingsManager.DECODE_SOFT:
                renderFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
                break;
            default:
                renderFactory.setEnableDecoderFallback(true);
        }

        if (exoPlayer != null) exoPlayer.release();
        exoPlayer = new ExoPlayer.Builder(this)
                .setRenderersFactory(renderFactory)
                .build();
        playerView.setPlayer(exoPlayer);

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    cancelTimeoutTask();
                }
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                autoSwitchLine();
            }
        });
    }

    // 应用画面比例设置
    private void applyAllSetting() {
        int scale = setting.getScale();
        switch (scale) {
            case SettingsManager.SCALE_16_9:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_16_9);
                break;
            case SettingsManager.SCALE_FILL:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
            default:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
    }

    // 默认初始频道
    private void initDefaultChannel() {
        channelSourceList.clear();
        List<String> cctv1 = new ArrayList<>();
        cctv1.add("http://hwrr.jx.chinamobile.com:8080/PLTV/88888888/224/3221225618/index.m3u8");
        channelSourceList.add(cctv1);
        playChannel(0);
    }

    // 核心播放方法
    private void playChannel(int index) {
        if (channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;

        currentPlayIndex = index;
        List<String> lines = channelSourceList.get(index);
        int linePos = setting.getLine();
        if (linePos >= lines.size()) linePos = 0;

        String playUrl = lines.get(linePos);
        exoPlayer.stop();
        exoPlayer.clearMediaItems();
        MediaItem item = MediaItem.fromUri(playUrl);
        exoPlayer.setMediaItem(item);
        exoPlayer.prepare();
        exoPlayer.play();
        startTimeoutCheck();
    }

    // 开启超时换源检测
    private void startTimeoutCheck() {
        cancelTimeoutTask();
        if (!setting.isTimeoutEnable()) return;
        int timeMs = setting.getTimeoutSec() * 1000;
        timeoutRunnable = this::autoSwitchLine;
        mainHandler.postDelayed(timeoutRunnable, timeMs);
    }

    // 取消超时任务
    private void cancelTimeoutTask() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
        }
    }

    // 自动切换线路
    private void autoSwitchLine() {
        List<String> lines = channelSourceList.get(currentPlayIndex);
        int nowLine = setting.getLine();
        if (nowLine + 1 < lines.size()) {
            setting.setLine(nowLine + 1);
            playChannel(currentPlayIndex);
        } else {
            Toast.makeText(this, "当前频道所有线路播放失败", Toast.LENGTH_SHORT).show();
        }
    }

    // 线路选择弹窗
    private void showLineDialog() {
        int now = setting.getLine();
        new AlertDialog.Builder(this)
                .setTitle("选择播放线路")
                .setSingleChoiceItems(new String[]{"线路1","线路2","线路3"}, now, (d, pos) -> {
                    setting.setLine(pos);
                    playChannel(currentPlayIndex);
                    d.dismiss();
                }).show();
    }

    // 画面比例弹窗
    private void showScaleDialog() {
        int now = setting.getScale();
        new AlertDialog.Builder(this)
                .setTitle("画面显示比例")
                .setSingleChoiceItems(scaleArr, now, (d, pos) -> {
                    setting.setScale(pos);
                    applyAllSetting();
                    d.dismiss();
                }).show();
    }

    // 解码模式弹窗
    private void showDecodeDialog() {
        int now = setting.getDecode();
        new AlertDialog.Builder(this)
                .setTitle("解码模式（黑屏切换修复）")
                .setSingleChoiceItems(decodeArr, now, (d, pos) -> {
                    setting.setDecode(pos);
                    initExoPlayer();
                    applyAllSetting();
                    playChannel(currentPlayIndex);
                    d.dismiss();
                }).show();
    }

    // 超时换源设置弹窗
    private void showTimeoutDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_timeout, null);
        EditText etTime = view.findViewById(R.id.et_timeout);
        etTime.setText(String.valueOf(setting.getTimeoutSec()));

        new AlertDialog.Builder(this)
                .setTitle("超时自动换源(秒)")
                .setView(view)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int t = Integer.parseInt(etTime.getText().toString());
                        setting.setTimeoutSec(t);
                    }catch (Exception e){}
                })
                .setNegativeButton("关闭自动换源",(d,w)-> setting.setTimeoutEnable(false))
                .setNeutralButton("开启自动换源",(d,w)-> setting.setTimeoutEnable(true)).show();
    }

    // 加载订阅M3U地址
    private void loadSubscribeUrl() {
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        edit.setText(setting.getSubUrl());

        new AlertDialog.Builder(this)
                .setTitle("M3U订阅地址")
                .setView(edit)
                .setPositiveButton("确认加载", (d, w) -> {
                    String url = edit.getText().toString().trim();
                    setting.setSubUrl(url);
                    Toast.makeText(this,"正在解析频道列表...",Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        try {
                            List<List<String>> newChannels = PlaylistParser.parseFromUrl(url);
                            runOnUiThread(() -> {
                                channelSourceList = newChannels;
                                if(!channelSourceList.isEmpty()){
                                    playChannel(0);
                                    Toast.makeText(MainActivity.this,"加载成功，共"+channelSourceList.size()+"个频道",Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,"解析失败："+e.getMessage(),Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                })
                .setNegativeButton("取消",null).show();
    }

    // 页面生命周期回收
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
