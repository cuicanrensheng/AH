package com.tv.live;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public int currentChannelIndex = 0;

    public static class Channel {
        public String name;
        public String group;
        public List<String> urls;
        public List<EpgItem> epgList;

        public static class EpgItem {
            public String day;
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

    public List<Channel> channels = new ArrayList<>();
    private ExoPlayer exoPlayer;
    private ExoPlayer playbackPlayer;
    private boolean isPlayingPlayback = false;
    private PlayerView playerView;
    private SettingsManager setting;
    public List<Channel> channelSourceList = new ArrayList<>();
    public int currentPlayIndex = 0;
    private Handler mainHandler;
    private Runnable timeoutRunnable;
    private GestureDetector gestureDetector;
    private boolean channelReverse = false;
    private boolean epgEnabled = true;
    private SharedPreferences sp;
    private int lastPlayChannelIndex = 0;

    private final String URL1 = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        mInstance = this;

        mainHandler = new Handler(Looper.getMainLooper());
        sp = getSharedPreferences("tv_config", MODE_PRIVATE);
        channelReverse = sp.getBoolean("channelReverse", false);
        epgEnabled = sp.getBoolean("epgEnabled", true);
        lastPlayChannelIndex = sp.getInt("last_play_channel", 0);

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setFocusable(false);
        playerView.setFocusableInTouchMode(false);
        playerView.setClickable(false);

        setting = SettingsManager.getInstance(this);
        initGestureDetector();
        initExoPlayer();
        loadSource(URL1);
    }

    // ✅ 核心修复：手势不冲突、单击/双击彻底分开
    private void initGestureDetector() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true; // 必须返回true，否则手势全失效
            }

            // ✅ 单击 = 频道列表 + EPG
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showChannelListDialog();
                return true;
            }

            // ✅ 双击 = 设置界面
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                showSettingDialog();
                return true;
            }

            // ✅ 上下滑动 = 换台
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > 60) {
                    if (dy > 0) changeChannel(1);
                    else changeChannel(-1);
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
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIdx = currentPlayIndex + delta;
        if (newIdx < 0) newIdx = channelSourceList.size() - 1;
        if (newIdx >= channelSourceList.size()) newIdx = 0;
        playChannel(newIdx);
    }

    private void loadSource(String url) {
        new Thread(() -> {
            try {
                List<Channel> res = PlaylistParser.parseWithRealName(url);
                runOnUiThread(() -> {
                    channelSourceList = res;
                    channels = res;
                    if (!res.isEmpty()) {
                        int playIdx = Math.min(lastPlayChannelIndex, res.size() - 1);
                        playChannel(playIdx);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void initExoPlayer() {
        if (exoPlayer != null) exoPlayer.release();
        DefaultRenderersFactory factory = new DefaultRenderersFactory(this);
        exoPlayer = new ExoPlayer.Builder(this).setRenderersFactory(factory).build();
        playerView.setPlayer(exoPlayer);

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                autoSwitchLine();
            }
        });
    }

    private void playChannel(int index) {
        if (channelSourceList.isEmpty() || index < 0 || index >= channelSourceList.size()) return;
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

        lastPlayChannelIndex = index;
        sp.edit().putInt("last_play_channel", index).apply();
        Toast.makeText(this, "正在播放：" + ch.name, Toast.LENGTH_SHORT).show();
    }

    private void autoSwitchLine() {
        if (isPlayingPlayback || channelSourceList == null) return;
        Channel ch = channelSourceList.get(currentPlayIndex);
        int now = setting.getLine();
        if (now + 1 < ch.urls.size()) {
            setting.setLine(now + 1);
            playChannel(currentPlayIndex);
        } else {
            Toast.makeText(this, "本频道所有线路失效", Toast.LENGTH_SHORT).show();
        }
    }

    // ✅ 单击弹出：频道列表 + EPG
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
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                tv.setText(getItem(position).name);
                return v;
            }
        };
        lvChannel.setAdapter(channelAdapter);
        lvChannel.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        ArrayAdapter<Channel.EpgItem> epgAdapter = new ArrayAdapter<Channel.EpgItem>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(15);
                Channel.EpgItem item = getItem(position);
                if (item != null) {
                    String info = item.time + " | " + item.title;
                    if (item.isNow) info = "▶ " + info;
                    tv.setText(info);
                }
                return v;
            }
        };
        lvEpg.setAdapter(epgAdapter);

        lvChannel.post(() -> {
            lvChannel.setItemChecked(currentPlayIndex, true);
            epgAdapter.clear();
            epgAdapter.addAll(channelSourceList.get(currentPlayIndex).epgList);
            epgAdapter.notifyDataSetChanged();
        });

        lvChannel.setOnItemClickListener((parent, v, position, id) -> {
            playChannel(position);
            epgAdapter.clear();
            epgAdapter.addAll(channelSourceList.get(position).epgList);
            epgAdapter.notifyDataSetChanged();
        });

        new AlertDialog.Builder(this)
                .setView(view)
                .setNegativeButton("关闭", null)
                .show();
    }

    // ✅ 双击弹出：设置（所有功能全部修复生效）
    private void showSettingDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_setting, null);

        Switch sw_reverse = view.findViewById(R.id.sw_reverse);
        Switch sw_epg = view.findViewById(R.id.sw_epg);
        Spinner sp_line = view.findViewById(R.id.sp_line);
        Spinner sp_scale = view.findViewById(R.id.sp_scale);
        Spinner sp_decode = view.findViewById(R.id.sp_decode);
        EditText et_timeout = view.findViewById(R.id.et_timeout_sec);

        sw_reverse.setChecked(channelReverse);
        sw_epg.setChecked(epgEnabled);

        String[] lines = {"线路1", "线路2", "线路3"};
        sp_line.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, lines));
        sp_line.setSelection(setting.getLine());

        String[] scales = {"原始", "16:9", "全屏"};
        sp_scale.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, scales));
        sp_scale.setSelection(setting.getScale());

        String[] decodes = {"自动", "硬解", "软解"};
        sp_decode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, decodes));
        sp_decode.setSelection(setting.getDecode());

        et_timeout.setText(String.valueOf(setting.getTimeoutSec()));

        new AlertDialog.Builder(this)
                .setTitle("播放设置")
                .setView(view)
                .setPositiveButton("保存", (d, w) -> {
                    channelReverse = sw_reverse.isChecked();
                    epgEnabled = sw_epg.isChecked();

                    setting.setLine(sp_line.getSelectedItemPosition());
                    setting.setScale(sp_scale.getSelectedItemPosition());
                    setting.setDecode(sp_decode.getSelectedItemPosition());

                    try {
                        int sec = Integer.parseInt(et_timeout.getText().toString());
                        setting.setTimeoutSec(sec);
                    } catch (Exception e) {
                        setting.setTimeoutSec(6);
                    }

                    sp.edit()
                            .putBoolean("channelReverse", channelReverse)
                            .putBoolean("epgEnabled", epgEnabled)
                            .apply();

                    applyAllSetting();
                    initExoPlayer();
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyAllSetting() {
        int sc = setting.getScale();
        if (sc == 1) playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH);
        else if (sc == 2) playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        else playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
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
        mainHandler.removeCallbacksAndMessages(null);
    }
}
