package com.tv.live;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;

    // 频道类：自动从直播源读取 分组/名称/多线路
    public static class Channel {
        public String name;
        public String group;
        public List<String> urls;
        public List<EpgItem> epgList;

        // EPG节目单：自动从EPG接口读取 日期/时间/节目名/回看地址
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

    // 直播源地址（自动读取频道+分组）
    private final String LIVE_SOURCE_URL = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
    // EPG接口（自动读取节目单+回看）
    private final String EPG_SOURCE_URL = "http://epg.51zmt.top:8000/e.xml.gz";

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

        setting = SettingsManager.getInstance(this);
        initGesture();
        initExoPlayer();

        // 后台：1.拉取直播源 2.拉取EPG 3.自动播放
        new Thread(() -> {
            try {
                // 第一步：自动拉取直播源（分组+频道+线路）
                channelSourceList = PlaylistParser.parseWithRealName(LIVE_SOURCE_URL);

                // 第二步：自动拉取EPG节目单，绑定到对应频道
                EpgManager.getInstance().loadEpg(EPG_SOURCE_URL, () -> runOnUiThread(() -> {
                    // 自动匹配EPG到每个频道
                    for (Channel ch : channelSourceList) {
                        ch.epgList = EpgManager.getInstance().getEpgByChannelName(ch.name);
                    }

                    // 自动播放上次频道
                    int playIdx = Math.min(lastPlayIndex, channelSourceList.size() - 1);
                    playChannel(playIdx);
                }));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "直播源/EPG加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // 手势：单击=三栏菜单，双击=设置，上下滑动=换台
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
                    if (dy > 0) changeChannel(1);
                    else changeChannel(-1);
                }
                return true;
            }
        });
        playerView.setOnTouchListener((v, e) -> {
            gestureDetector.onTouchEvent(e);
            return true;
        });
    }

    private void changeChannel(int delta) {
        if (channelSourceList.isEmpty()) return;
        int newIdx = currentPlayIndex + delta;
        if (newIdx < 0) newIdx = channelSourceList.size() - 1;
        if (newIdx >= channelSourceList.size()) newIdx = 0;
        playChannel(newIdx);
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
        Channel ch = channelSourceList.get(index);
        int line = Math.min(setting.getLine(), ch.urls.size() - 1);
        String url = ch.urls.get(line);

        exoPlayer.setMediaItem(MediaItem.fromUri(url));
        exoPlayer.prepare();
        exoPlayer.play();

        // 记忆播放
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

    // 三栏菜单：分组｜频道｜EPG+回看（完全匹配你截图样式）
    private void showChannelEpgDialog() {
        if (channelSourceList.isEmpty()) return;
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_channel_epg, null);
        ListView lvGroup = view.findViewById(R.id.lv_group);
        ListView lvChannel = view.findViewById(R.id.lv_channel);
        ListView lvEpg = view.findViewById(R.id.lv_epg);

        // 1. 自动提取所有分组（从直播源读取）
        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel ch : channelSourceList) groupSet.add(ch.group);
        List<String> groupList = new ArrayList<>(groupSet);

        // 分组适配器
        ArrayAdapter<String> groupAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int pos, View cv, ViewGroup p) {
                View v = super.getView(pos, cv, p);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setText(groupList.get(pos));
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(17);
                tv.setPadding(15,18,15,18);
                return v;
            }
        };
        lvGroup.setAdapter(groupAdapter);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lvGroup.setItemChecked(0, true);

        // 2. 当前分组的频道
        List<Channel> groupChannels = new ArrayList<>();
        String curGroup = groupList.get(0);
        for (Channel ch : channelSourceList) {
            if (ch.group.equals(curGroup)) groupChannels.add(ch);
        }

        // 频道适配器（显示频道名+当前正在播放节目）
        ArrayAdapter<Channel> channelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, groupChannels) {
            @Override
            public View getView(int pos, View cv, ViewGroup p) {
                View v = super.getView(pos, cv, p);
                TextView tv = v.findViewById(android.R.id.text1);
                Channel ch = getItem(pos);
                String nowTitle = "";
                if (epgEnabled && ch.epgList != null) {
                    for (Channel.EpgItem item : ch.epgList) {
                        if (item.isNow) {
                            nowTitle = "\n  " + item.title;
                            break;
                        }
                    }
                }
                tv.setText(ch.name + nowTitle);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                tv.setPadding(15,18,15,18);
                return v;
            }
        };
        lvChannel.setAdapter(channelAdapter);
        lvChannel.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // 3.EPG适配器（时间+节目名+回看按钮，自动从EPG获取）
        ArrayAdapter<Channel.EpgItem> epgAdapter = new ArrayAdapter<>(this, R.layout.item_epg, new ArrayList<>()) {
            @Override
            public View getView(int pos, View cv, ViewGroup p) {
                if(cv == null) cv = LayoutInflater.from(getContext()).inflate(R.layout.item_epg, p, false);
                TextView tvTime = cv.findViewById(R.id.tv_time);
                TextView tvTitle = cv.findViewById(R.id.tv_title);
                TextView tvReplay = cv.findViewById(R.id.tv_replay);

                Channel.EpgItem item = getItem(pos);
                tvTime.setText(item.day + "\n" + item.time);
                tvTitle.setText(item.title);

                // 有回看地址才显示回看
                if(TextUtils.isEmpty(item.playUrl)){
                    tvReplay.setVisibility(View.INVISIBLE);
                }else{
                    tvReplay.setVisibility(View.VISIBLE);
                    tvReplay.setOnClickListener(v -> playReplay(item.playUrl));
                }
                return cv;
            }
        };
        lvEpg.setAdapter(epgAdapter);

        // 默认选中当前播放频道
        lvChannel.post(() -> {
            int selIdx = groupChannels.indexOf(channelSourceList.get(currentPlayIndex));
            lvChannel.setItemChecked(selIdx, true);
            epgAdapter.clear();
            epgAdapter.addAll(channelSourceList.get(currentPlayIndex).epgList);
            epgAdapter.notifyDataSetChanged();
        });

        // 切换分组 → 刷新频道
        lvGroup.setOnItemClickListener((parent, v, pos, id) -> {
            String g = groupList.get(pos);
            List<Channel> newList = new ArrayList<>();
            for(Channel ch : channelSourceList) if(ch.group.equals(g)) newList.add(ch);
            channelAdapter.clear();
            channelAdapter.addAll(newList);
            channelAdapter.notifyDataSetChanged();
            epgAdapter.clear();
            epgAdapter.notifyDataSetChanged();
        });

        // 切换频道 → 播放+刷新EPG
        lvChannel.setOnItemClickListener((parent, v, pos, id) -> {
            Channel ch = groupChannels.get(pos);
            int realIdx = channelSourceList.indexOf(ch);
            playChannel(realIdx);
            epgAdapter.clear();
            epgAdapter.addAll(ch.epgList);
            epgAdapter.notifyDataSetChanged();
        });

        new AlertDialog.Builder(this).setView(view).setCancelable(true).show();
    }

    // 播放回看
    private void playReplay(String url){
        isPlayingPlayback = true;
        exoPlayer.pause();
        if(playbackPlayer == null) playbackPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(playbackPlayer);
        playbackPlayer.setMediaItem(MediaItem.fromUri(url));
        playbackPlayer.prepare();
        playbackPlayer.play();
    }

    // 设置弹窗
    private void showSettingDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_setting, null);
        new AlertDialog.Builder(this).setTitle("播放设置").setView(view).show();
    }

    @Override
    public void onBackPressed() {
        if(isPlayingPlayback){
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
        if(exoPlayer != null) exoPlayer.release();
        if(playbackPlayer != null) playbackPlayer.release();
    }
}
