package com.tv.live;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    public List<Channel> channelSourceList = new ArrayList<>();
    private ExoPlayer exoPlayer;
    private ExoPlayer playbackPlayer;
    private boolean isPlayingPlayback = false;
    private PlayerView playerView;
    private SettingsManager setting;
    public int currentPlayIndex = 0;
    private GestureDetector gestureDetector;
    private SharedPreferences sp;
    private boolean epgEnabled = true;
    private int lastPlayIndex = 0;

    private final String LIVE_SOURCE_URL = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";

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
                channelSourceList = PlaylistParser.parseWithRealName(LIVE_SOURCE_URL);
                EpgManager.getInstance().loadEpg("http://epg.51zmt.top:8000/e.xml.gz", () -> runOnUiThread(() -> {
                    for (Channel ch : channelSourceList) {
                        ch.epgList = EpgManager.getInstance().getEpgByChannelName(ch.name);
                    }
                    int playIdx = Math.min(lastPlayIndex, channelSourceList.size() - 1);
                    playChannel(playIdx);
                }));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ====================== 遥控器核心逻辑 ======================
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            changeChannel(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            changeChannel(1);
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
        if (keyCode == KeyEvent.KEYCODE_0) playChannelByNum(0);
        if (keyCode == KeyEvent.KEYCODE_1) playChannelByNum(1);
        if (keyCode == KeyEvent.KEYCODE_2) playChannelByNum(2);
        if (keyCode == KeyEvent.KEYCODE_3) playChannelByNum(3);
        if (keyCode == KeyEvent.KEYCODE_4) playChannelByNum(4);
        if (keyCode == KeyEvent.KEYCODE_5) playChannelByNum(5);
        if (keyCode == KeyEvent.KEYCODE_6) playChannelByNum(6);
        if (keyCode == KeyEvent.KEYCODE_7) playChannelByNum(7);
        if (keyCode == KeyEvent.KEYCODE_8) playChannelByNum(8);
        if (keyCode == KeyEvent.KEYCODE_9) playChannelByNum(9);
        return super.onKeyUp(keyCode, event);
    }

    private void playChannelByNum(int num) {
        if (channelSourceList.size() > num) {
            playChannel(num);
            Toast.makeText(this, "切换到频道 " + (num + 1), Toast.LENGTH_SHORT).show();
        }
    }

    // ===========================================================

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
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                autoSwitchLine();
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

        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel ch : channelSourceList) groupSet.add(ch.group);
        List<String> groupList = new ArrayList<>(groupSet);

        ArrayAdapter<String> groupAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, groupList) {
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
        lvGroup.setItemChecked(0, true);

        List<Channel> groupChannels = new ArrayList<>();
        String curGroup = groupList.get(0);
        for (Channel ch : channelSourceList) {
            if (ch.group.equals(curGroup)) groupChannels.add(ch);
        }

        ArrayAdapter<Channel> channelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, groupChannels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                Channel ch = getItem(position);
                tv.setText(ch.name);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                tv.setPadding(15, 18, 15, 18);
                return v;
            }
        };
        lvChannel.setAdapter(channelAdapter);
        lvChannel.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        ArrayAdapter<Channel.EpgItem> epgAdapter = new ArrayAdapter<>(this, R.layout.item_epg, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_epg, parent, false);
                }
                TextView tvDay = convertView.findViewById(R.id.tv_day);
                TextView tvTime = convertView.findViewById(R.id.tv_time);
                TextView tvTitle = convertView.findViewById(R.id.tv_title);
                TextView tvReplay = convertView.findViewById(R.id.tv_replay);
                Channel.EpgItem item = getItem(position);

                tvDay.setText(item.day);
                tvTime.setText(item.time);
                tvTitle.setText(item.title);

                if (TextUtils.isEmpty(item.playUrl)) {
                    tvReplay.setVisibility(View.INVISIBLE);
                } else {
                    tvReplay.setVisibility(View.VISIBLE);
                    tvReplay.setOnClickListener(v -> playReplay(item.playUrl));
                }
                return convertView;
            }
        };
        lvEpg.setAdapter(epgAdapter);

        lvChannel.post(() -> {
            int selIdx = groupChannels.indexOf(channelSourceList.get(currentPlayIndex));
            lvChannel.setItemChecked(selIdx, true);
            epgAdapter.clear();
            epgAdapter.addAll(channelSourceList.get(currentPlayIndex).epgList);
            epgAdapter.notifyDataSetChanged();
        });

        lvGroup.setOnItemClickListener((parent, v, pos, id) -> {
            String g = groupList.get(pos);
            List<Channel> newList = new ArrayList<>();
            for (Channel ch : channelSourceList) {
                if (ch.group.equals(g)) newList.add(ch);
            }
            groupChannels.clear();
            groupChannels.addAll(newList);
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

    private void playReplay(String url) {
        isPlayingPlayback = true;
        exoPlayer.pause();
        if (playbackPlayer == null) {
            playbackPlayer = new ExoPlayer.Builder(this).build();
        }
        playerView.setPlayer(playbackPlayer);
        playbackPlayer.setMediaItem(MediaItem.fromUri(url));
        playbackPlayer.prepare();
        playbackPlayer.play();
    }

    private void showSettingDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_setting, null);
        new AlertDialog.Builder(this)
                .setTitle("播放设置")
                .setView(view)
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
