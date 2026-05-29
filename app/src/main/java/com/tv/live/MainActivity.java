package com.tv.live;
import com.tv.live.Channel;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.util.HuyaParser;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // 单例
    public static MainActivity mInstance;

    // 👇 开放权限给外部调用（修复私有访问错误）
    public List<Channel> channelSourceList = new ArrayList<>();
    public int currentPlayIndex = 0;
    public int currentChannelIndex = 0;

    public boolean isPlayingPlayback = false;
    public int currentRatioIndex = 2;

    public ListView lvGroup;
    public ListView lvChannelList;
    public ListView lvDate;
    public ListView lvEpg;

    public TVPlayerManager mPlayerManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;
        setContentView(R.layout.activity_main);

        PlayerView playerView = findViewById(R.id.player_view);
        lvGroup = findViewById(R.id.lv_group);
        lvChannelList = findViewById(R.id.lv_channel_list);
        lvDate = findViewById(R.id.lv_date);
        lvEpg = findViewById(R.id.lv_epg);

        SharedPreferences sp = getSharedPreferences("play_config", Context.MODE_PRIVATE);
        currentRatioIndex = sp.getInt("play_ratio", 2);
        currentPlayIndex = sp.getInt("last_play_index", 0);

        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        switch (currentRatioIndex) {
            case 0: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT); break;
            case 1: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT); break;
            case 2: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM); break;
            case 3: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL); break;
        }

        mPlayerManager.setOnPlayStateListener(new TVPlayerManager.OnPlayStateListener() {
            @Override public void onIdle() {}
            @Override public void onBuffering() { Toast.makeText(MainActivity.this, "缓冲中...", Toast.LENGTH_SHORT).show(); }
            @Override public void onPlayReady() {}
            @Override public void onPlayEnd() { Toast.makeText(MainActivity.this, "播放结束，自动重试", Toast.LENGTH_SHORT).show(); }
            @Override public void onPlayError(String errorMsg) { Toast.makeText(MainActivity.this, "播放异常："+errorMsg, Toast.LENGTH_SHORT).show(); }
        });

        initGesture();
        loadChannels();
        initChannelList();
        initListViewClick();
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        currentPlayIndex = index;
        currentChannelIndex = index;
        Channel channel = channelSourceList.get(index);
        String playUrl = channel.getPlayUrl();
        if (TextUtils.isEmpty(playUrl)) { Toast.makeText(this, "播放地址为空", Toast.LENGTH_SHORT).show(); return; }
        mPlayerManager.playUrl(playUrl);
        isPlayingPlayback = false;
        getSharedPreferences("play_config",0).edit().putInt("last_play_index", index).apply();
    }

    private void playEpgItem(Channel.EpgItem epgItem) {
        if (epgItem == null || TextUtils.isEmpty(epgItem.getReplayUrl())) {
            Toast.makeText(this, "暂无回看地址", Toast.LENGTH_SHORT).show();
            return;
        }
        mPlayerManager.playUrl(epgItem.getReplayUrl());
        isPlayingPlayback = true;
        Toast.makeText(this, "正在播放回看", Toast.LENGTH_SHORT).show();
    }

    private void setRatio(int index) {
        currentRatioIndex = index;
        switch (index) {
            case 0: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT); break;
            case 1: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT); break;
            case 2: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM); break;
            case 3: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL); break;
        }
        getSharedPreferences("play_config",0).edit().putInt("play_ratio", index).apply();
    }

    private void initListViewClick() {
    // 关键：先判空，避免空指针
    if (lvChannelList != null) {
        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            Channel selectChannel = (Channel) parent.getItemAtPosition(position);
            int pos = channelSourceList.indexOf(selectChannel);
            playChannel(pos);
            lvChannelList.setSelection(position);
        });
    }

    if (lvGroup != null) {
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            // 分组点击逻辑
            lvChannelList.setSelection(0);
        });
    }

    if (lvEpg != null) {
        lvEpg.setOnItemClickListener((parent, view, position, id) -> {
            Object obj = parent.getItemAtPosition(position);
            if (obj instanceof Channel.EpgItem) {
                playEpgItem((Channel.EpgItem) obj);
            }
        });
    }
}


    @Override
    public void onBackPressed() {
        if (isPlayingPlayback) { isPlayingPlayback = false; playChannel(currentPlayIndex); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPlayerManager != null) mPlayerManager.release();
        HuyaParser.release();
        mInstance = null;
    }

    private void initGesture() {}
    private void loadChannels() {}
    private void initChannelList() {}

    // 👇 修复 HttpServer 找不到 onReceiveConfig 错误
    public void onReceiveConfig(String liveUrl, String epgUrl) {}
}
