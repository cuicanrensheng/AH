package com.tv.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Player;
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
            this.epgList = new ArrayList<>();
        }
    }

    public List<Channel> channelSourceList = new ArrayList<>();
    private ExoPlayer exoPlayer;
    private ExoPlayer playbackPlayer;
    private boolean isPlayingPlayback = false;
    private PlayerView playerView;
    private SettingsManager setting;
    private int currentPlayIndex = 0;
    private GestureDetector gestureDetector;
    private SharedPreferences sp;
    private boolean channelReverse = false;
    private boolean bootAutoStart = false;
    private boolean epgEnabled = true;
    private Runnable timeoutRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mInstance = this;
        sp = getSharedPreferences("config", Context.MODE_PRIVATE);
        setting = SettingsManager.getInstance(this);

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);

        initExoPlayer();
        initGesture();

        // 后台加载直播源 + EPG
        new Thread(() -> {
            try {
                String liveUrl = "你的直播源地址.m3u";
                channelSourceList = PlaylistParser.parseWithRealName(liveUrl);

                EpgManager.getInstance().load(this, () -> runOnUiThread(() -> {
                    if (!channelSourceList.isEmpty()) {
                        currentPlayIndex = 0;
                        playChannel(0);
                    }
                }));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void initExoPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(com.google.android.exoplayer2.PlaybackException error) {
                autoSwitchLine();
            }
        });
    }

    // 手势：单击左屏=EPG菜单，单击右屏=设置，上下滑动换台
    private void initGesture() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float x = e.getX();
                int screenWidth = playerView.getWidth();
                if (x < screenWidth / 2f) {
                    showChannelListDialog();
                } else {
                    showSettingDialog();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > 80) {
                    if (dy < -80) nextChannel();
                    else if (dy > 80) prevChannel();
                }
                return true;
            }
        });
        playerView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private void loadLiveSource() {
    }

    private void playChannel(int index) {
        if (channelSourceList.isEmpty()) return;
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        exoPlayer.setMediaItem(com.google.android.exoplayer2.MediaItem.fromUri(ch.urls.get(0)));
        exoPlayer.prepare();
        exoPlayer.play();
        startTimeoutCheck();
    }

    private void nextChannel() {
        int next = currentPlayIndex + 1;
        if (next >= channelSourceList.size()) next = 0;
        playChannel(next);
    }

    private void prevChannel() {
        int prev = currentPlayIndex - 1;
        if (prev < 0) prev = channelSourceList.size() - 1;
        playChannel(prev);
    }

    private void startTimeoutCheck() {
        cancelTimeoutTask();
        timeoutRunnable = this::autoSwitchLine;
        playerView.postDelayed(timeoutRunnable, setting.getTimeoutSec() * 1000L);
    }

    private void cancelTimeoutTask() {
        if (timeoutRunnable != null) playerView.removeCallbacks(timeoutRunnable);
    }

    private void autoSwitchLine() {
        if (isPlayingPlayback) return;
        Channel ch = channelSourceList.get(currentPlayIndex);
        int now = setting.getLine();
        if (now + 1 < ch.urls.size()) {
            setting.setLine(now + 1);
            playChannel(currentPlayIndex);
        } else {
            Toast.makeText(this, "本频道所有线路失效", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyAllSetting() {
        int scaleMode;
        switch (setting.getScale()) {
            case 0: scaleMode = com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT; break;
            case 1: scaleMode = com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH; break;
            case 2: scaleMode = com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL; break;
            default: scaleMode = com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
        }
        playerView.setResizeMode(scaleMode);
    }

    private void showChannelListDialog() {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
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

        ArrayAdapter<String> groupAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setText(groupList.get(position));
                tv.setTextColor(android.graphics.Color.WHITE);
                tv.setTextSize(18);
                return v;
            }
        };
        lvGroup.setAdapter(groupAdapter);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lvGroup.setItemChecked(0, true);

        List<Channel> groupChannels = new ArrayList<>();
        String selectedGroup = groupList.get(0);
        for (Channel ch : channelSourceList) {
            if (ch.group.equals(selectedGroup)) groupChannels.add(ch);
        }

        ArrayAdapter<Channel> channelAdapter = new ArrayAdapter<Channel>(this,
                android.R.layout.simple_list_item_1, groupChannels) {
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
                tv.setTextColor(android.graphics.Color.WHITE);
                tv.setTextSize(16);
                return v;
            }
        };
        lvChannel.setAdapter(channelAdapter);
        lvChannel.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        final ArrayAdapter<Channel.EpgItem> epgAdapter = new ArrayAdapter<Channel.EpgItem>(this,
                R.layout.item_epg, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_epg, parent, false);
                }
                TextView tvDay = convertView.findViewById(R.id.tv_day);
                TextView tvInfo = convertView.findViewById(R.id.tv_info);

                Channel.EpgItem item = getItem(position);
                String infoText = item.time + "    " + item.title;
                if (!android.text.TextUtils.isEmpty(item.playUrl)) {
                    infoText += "        回看";
                }
                if (item.isNow) {
                    infoText = "▶ " + infoText;
                }

                tvDay.setText(item.day);
                tvInfo.setText(infoText);
                return convertView;
            }
        };
        lvEpg.setAdapter(epgAdapter);

        int finalSelGroupIdx = groupList.indexOf(channelSourceList.get(currentPlayIndex).group);
        lvGroup.setItemChecked(finalSelGroupIdx, true);
        lvGroup.setSelection(finalSelGroupIdx);

        lvChannel.post(() -> {
            int pos = groupChannels.indexOf(channelSourceList.get(currentPlayIndex));
            lvChannel.setItemChecked(pos, true);
            lvChannel.setSelection(pos);
            epgAdapter.clear();
            epgAdapter.addAll(channelSourceList.get(currentPlayIndex).epgList);
            epgAdapter.notifyDataSetChanged();
        });

        lvGroup.setOnItemClickListener((parent, v, position, id) -> {
            String g = groupList.get(position);
            List<Channel> newList = new ArrayList<>();
            for (Channel ch : channelSourceList) if (ch.group.equals(g)) newList.add(ch);
            channelAdapter.clear();
            channelAdapter.addAll(newList);
            channelAdapter.notifyDataSetChanged();
            epgAdapter.clear();
            epgAdapter.notifyDataSetChanged();
        });

        lvChannel.setOnItemClickListener((parent, v, position, id) -> {
            Channel ch = groupChannels.get(position);
            int realIndex = channelSourceList.indexOf(ch);
            currentPlayIndex = realIndex;
            playChannel(realIndex);
            epgAdapter.clear();
            epgAdapter.addAll(ch.epgList);
            epgAdapter.notifyDataSetChanged();
        });

        lvEpg.setOnItemClickListener((parent, v, position, id) -> {
            Channel.EpgItem item = channelSourceList.get(currentPlayIndex).epgList.get(position);
            if (!android.text.TextUtils.isEmpty(item.playUrl)) {
                isPlayingPlayback = true;
                exoPlayer.pause();
                if (playbackPlayer == null) playbackPlayer = new ExoPlayer.Builder(MainActivity.this).build();
                playerView.setPlayer(playbackPlayer);
                playbackPlayer.setMediaItem(com.google.android.exoplayer2.MediaItem.fromUri(item.playUrl));
                playbackPlayer.prepare();
                playbackPlayer.play();
                Toast.makeText(MainActivity.this, "正在播放回放：" + item.title, Toast.LENGTH_SHORT).show();
            }
        });

        new AlertDialog.Builder(this)
                .setView(view)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showSettingDialog() {
        cancelTimeoutTask();
        View view = getLayoutInflater().inflate(R.layout.dialog_setting, null);

        Switch sw_reverse = view.findViewById(R.id.sw_reverse);
        Switch sw_boot = view.findViewById(R.id.sw_boot);
        Switch sw_epg = view.findViewById(R.id.sw_epg);

        Spinner sp_line = view.findViewById(R.id.sp_line);
        Spinner sp_scale = view.findViewById(R.id.sp_scale);
        Spinner sp_decode = view.findViewById(R.id.sp_decode);
        EditText et_timeout_sec = view.findViewById(R.id.et_timeout_sec);
        EditText et_sub_url = view.findViewById(R.id.et_sub_url);

        sw_reverse.setChecked(channelReverse);
        sw_boot.setChecked(bootAutoStart);
        sw_epg.setChecked(epgEnabled);

        String[] lineArr = {"线路1", "线路2", "线路3"};
        ArrayAdapter<String> lineAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, lineArr);
        sp_line.setAdapter(lineAdapter);
        sp_line.setSelection(setting.getLine());
        sp_line.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                setting.setLine(position);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        String[] scaleArr = {"原始", "16:9", "全屏拉伸"};
        ArrayAdapter<String> scaleAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, scaleArr);
        sp_scale.setAdapter(scaleAdapter);
        sp_scale.setSelection(setting.getScale());
        sp_scale.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                setting.setScale(position);
                applyAllSetting();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        String[] decodeArr = {"自动", "硬解", "软解"};
        ArrayAdapter<String> decodeAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, decodeArr);
        sp_decode.setAdapter(decodeAdapter);
        sp_decode.setSelection(setting.getDecode());
        sp_decode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                setting.setDecode(position);
                initExoPlayer();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        et_timeout_sec.setText(String.valueOf(setting.getTimeoutSec()));
        et_sub_url.setText(setting.getSubUrl());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("播放设置")
                .setView(view)
                .setPositiveButton("保存", (d, which) -> {
                    channelReverse = sw_reverse.isChecked();
                    bootAutoStart = sw_boot.isChecked();
                    epgEnabled = sw_epg.isChecked();

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
                    startTimeoutCheck();
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("切换直播源", (d, w) -> {})
                .create();

        dialog.setOnDismissListener(dialog1 -> {
            if (exoPlayer != null) {
                exoPlayer.addListener(new Player.Listener() {
                    @Override
                    public void onPlayerError(com.google.android.exoplayer2.PlaybackException error) {
                        autoSwitchLine();
                    }
                });
            }
            startTimeoutCheck();
        });
        dialog.show();
    }

    @Override
    public void onBackPressed() {
        if (isPlayingPlayback) {
            isPlayingPlayback = false;
            if (playbackPlayer != null) {
                playbackPlayer.stop();
                playbackPlayer.release();
                playbackPlayer = null;
            }
            playerView.setPlayer(exoPlayer);
            exoPlayer.play();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelTimeoutTask();
        if (exoPlayer != null) exoPlayer.release();
        if (playbackPlayer != null) playbackPlayer.release();
    }
}
