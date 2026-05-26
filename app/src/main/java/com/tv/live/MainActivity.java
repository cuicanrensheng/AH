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
            this.epgList = new ArrayList();
        }
    }

    public List channelSourceList = new ArrayList();
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

        playerView = (PlayerView) findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setFocusable(false);
        playerView.setClickable(false);

        setting = SettingsManager.getInstance(this);
        initGesture();
        initExoPlayer();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    channelSourceList = PlaylistParser.parseWithRealName(LIVE_SOURCE_URL);

                    EpgManager.getInstance().loadEpg("http://epg.51zmt.top:8000/e.xml.gz", new Runnable() {
                        @Override
                        public void run() {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    for (int i = 0; i < channelSourceList.size(); i++) {
                                        Channel ch = (Channel) channelSourceList.get(i);
                                        ch.epgList = EpgManager.getInstance().getEpgByChannelName(ch.name);
                                    }

                                    int playIdx = Math.min(lastPlayIndex, channelSourceList.size() - 1);
                                    playChannel(playIdx);
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

    private void initGesture() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

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

        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureDetector.onTouchEvent(event);
                return true;
            }
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
        currentChannelIndex = index;
        Channel ch = (Channel) channelSourceList.get(index);
        int line = Math.min(setting.getLine(), ch.urls.size() - 1);
        String url = (String) ch.urls.get(line);

        exoPlayer.setMediaItem(MediaItem.fromUri(url));
        exoPlayer.prepare();
        exoPlayer.play();

        lastPlayIndex = index;
        sp.edit().putInt("last_play", index).apply();
    }

    private void autoSwitchLine() {
        if (isPlayingPlayback) return;
        Channel ch = (Channel) channelSourceList.get(currentPlayIndex);
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
        ListView lvGroup = (ListView) view.findViewById(R.id.lv_group);
        ListView lvChannel = (ListView) view.findViewById(R.id.lv_channel);
        ListView lvEpg = (ListView) view.findViewById(R.id.lv_epg);

        Set groupSet = new LinkedHashSet();
        for (int i = 0; i < channelSourceList.size(); i++) {
            Channel ch = (Channel) channelSourceList.get(i);
            groupSet.add(ch.group);
        }
        final List groupList = new ArrayList(groupSet);

        ArrayAdapter groupAdapter = new ArrayAdapter(this, android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = (TextView) v.findViewById(android.R.id.text1);
                tv.setText((String) groupList.get(position));
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(17);
                tv.setPadding(15, 18, 15, 18);
                return v;
            }
        };

        lvGroup.setAdapter(groupAdapter);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lvGroup.setItemChecked(0, true);

        final List groupChannels = new ArrayList();
        String curGroup = (String) groupList.get(0);
        for (int i = 0; i < channelSourceList.size(); i++) {
            Channel ch = (Channel) channelSourceList.get(i);
            if (ch.group.equals(curGroup)) {
                groupChannels.add(ch);
            }
        }

        ArrayAdapter channelAdapter = new ArrayAdapter(this, android.R.layout.simple_list_item_1, groupChannels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = (TextView) v.findViewById(android.R.id.text1);
                Channel ch = (Channel) getItem(position);
                String nowTitle = "";
                tv.setText(ch.name + nowTitle);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                tv.setPadding(15, 18, 15, 18);
                return v;
            }
        };

        lvChannel.setAdapter(channelAdapter);
        lvChannel.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        ArrayAdapter epgAdapter = new ArrayAdapter(this, R.layout.item_epg, new ArrayList()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_epg, parent, false);
                }
                TextView tvTime = (TextView) convertView.findViewById(R.id.tv_time);
                TextView tvTitle = (TextView) convertView.findViewById(R.id.tv_title);
                TextView tvReplay = (TextView) convertView.findViewById(R.id.tv_replay);

                Channel.EpgItem item = (Channel.EpgItem) getItem(position);
                tvTime.setText(item.day + "\n" + item.time);
                tvTitle.setText(item.title);

                if (TextUtils.isEmpty(item.playUrl)) {
                    tvReplay.setVisibility(View.INVISIBLE);
                } else {
                    tvReplay.setVisibility(View.VISIBLE);
                    tvReplay.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            playReplay(item.playUrl);
                        }
                    });
                }
                return convertView;
            }
        };

        lvEpg.setAdapter(epgAdapter);

        lvChannel.post(new Runnable() {
            @Override
            public void run() {
                int selIdx = groupChannels.indexOf(channelSourceList.get(currentPlayIndex));
                lvChannel.setItemChecked(selIdx, true);
                epgAdapter.clear();
                Channel ch = (Channel) channelSourceList.get(currentPlayIndex);
                epgAdapter.addAll(ch.epgList);
                epgAdapter.notifyDataSetChanged();
            }
        });

        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                String g = (String) groupList.get(pos);
                List newList = new ArrayList();
                for (int i = 0; i < channelSourceList.size(); i++) {
                    Channel ch = (Channel) channelSourceList.get(i);
                    if (ch.group.equals(g)) {
                        newList.add(ch);
                    }
                }
                channelAdapter.clear();
                channelAdapter.addAll(newList);
                channelAdapter.notifyDataSetChanged();
                epgAdapter.clear();
                epgAdapter.notifyDataSetChanged();
            }
        });

        lvChannel.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                Channel ch = (Channel) groupChannels.get(pos);
                int realIdx = channelSourceList.indexOf(ch);
                playChannel(realIdx);
                epgAdapter.clear();
                epgAdapter.addAll(ch.epgList);
                epgAdapter.notifyDataSetChanged();
            }
        });

        new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .show();
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
