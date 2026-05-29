package com.tv.live;

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

    // ==================== 新增：单例实例 ====================
    public static MainActivity mInstance;

    // ==================== 原有全局变量（全部保留） ====================
    private List<Channel> channelSourceList = new ArrayList<>();
    private int currentPlayIndex = 0;
    private int currentChannelIndex = 0;
    private boolean isPlayingPlayback = false;
    private int currentRatioIndex = 2;

    private ListView lvGroup;
    private ListView lvChannelList;
    private ListView lvDate;
    private ListView lvEpg;

    // 新播放器
    private TVPlayerManager mPlayerManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ==================== 新增：绑定单例 ====================
        mInstance = this;

        setContentView(R.layout.activity_main);

        // 绑定控件（原有代码）
        PlayerView playerView = findViewById(R.id.player_view);
        lvGroup = findViewById(R.id.lv_group);
        lvChannelList = findViewById(R.id.lv_channel_list);
        lvDate = findViewById(R.id.lv_date);
        lvEpg = findViewById(R.id.lv_epg);

        // 读取本地配置（原有代码）
        SharedPreferences sp = getSharedPreferences("play_config", Context.MODE_PRIVATE);
        currentRatioIndex = sp.getInt("play_ratio", 2);
        currentPlayIndex = sp.getInt("last_play_index", 0);

        // ========== 初始化独立播放器（原有代码） ==========
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        // 初始化画面比例（原有代码）
        switch (currentRatioIndex) {
            case 0:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT);
                break;
            case 1:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT);
                break;
            case 2:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM);
                break;
            case 3:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
        }

        // 播放器状态监听（原有代码）
        mPlayerManager.setOnPlayStateListener(new TVPlayerManager.OnPlayStateListener() {
            @Override
            public void onIdle() {

            }

            @Override
            public void onBuffering() {
                Toast.makeText(MainActivity.this, "缓冲中...", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onPlayReady() {

            }

            @Override
            public void onPlayEnd() {
                Toast.makeText(MainActivity.this, "播放结束，自动重试", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onPlayError(String errorMsg) {
                Toast.makeText(MainActivity.this, "播放异常：" + errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        // ========== 原有初始化逻辑（手势、频道、列表）全部保留 ==========
        initGesture();
        loadChannels();
        initChannelList();
        initListViewClick();
    }

    /**
     * 播放直播频道（核心方法）
     */
    private void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            return;
        }
        currentPlayIndex = index;
        currentChannelIndex = index;
        Channel channel = channelSourceList.get(index);

        String playUrl = channel.getPlayUrl();
        if (TextUtils.isEmpty(playUrl)) {
            Toast.makeText(this, "播放地址为空", Toast.LENGTH_SHORT).show();
            return;
        }

        // 调用新播放器播放（完美支持虎牙一起看长参链接）
        mPlayerManager.playUrl(playUrl);
        isPlayingPlayback = false;

        // 保存播放位置
        SharedPreferences sp = getSharedPreferences("play_config", Context.MODE_PRIVATE);
        sp.edit().putInt("last_play_index", index).apply();
    }

    /**
     * 播放回看节目
     */
    private void playEpgItem(Channel.EpgItem epgItem) {
        if (epgItem == null || TextUtils.isEmpty(epgItem.getReplayUrl())) {
            Toast.makeText(this, "暂无回看地址", Toast.LENGTH_SHORT).show();
            return;
        }
        mPlayerManager.playUrl(epgItem.getReplayUrl());
        isPlayingPlayback = true;
        Toast.makeText(this, "正在播放回看", Toast.LENGTH_SHORT).show();
    }

    /**
     * 切换画面显示比例
     */
    private void setRatio(int index) {
        currentRatioIndex = index;
        switch (index) {
            case 0:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT);
                break;
            case 1:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT);
                break;
            case 2:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM);
                break;
            case 3:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
        }
        SharedPreferences sp = getSharedPreferences("play_config", Context.MODE_PRIVATE);
        sp.edit().putInt("play_ratio", index).apply();
    }

    /**
     * 列表点击事件
     */
    private void initListViewClick() {
        // 频道点击
        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            Channel selectChannel = (Channel) parent.getItemAtPosition(position);
            int pos = channelSourceList.indexOf(selectChannel);
            playChannel(pos);
            lvChannelList.setSelection(position);
        });

        // 分组点击（原有逻辑保留）
        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            // 此处保留你原有分组筛选代码
            lvChannelList.setSelection(0);
        });

        // 节目单点击播放回看
        lvEpg.setOnItemClickListener((parent, view, position, id) -> {
            Object obj = parent.getItemAtPosition(position);
            if (obj instanceof Channel.EpgItem) {
                playEpgItem((Channel.EpgItem) obj);
            }
        });
    }

    /**
     * 返回键：回看状态切回直播
     */
    @Override
    public void onBackPressed() {
        if (isPlayingPlayback) {
            isPlayingPlayback = false;
            playChannel(currentPlayIndex);
            return;
        }
        super.onBackPressed();
    }

    /**
     * 页面销毁，统一释放资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放播放器
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }
        // 释放解析工具线程池
        HuyaParser.release();
        // 清空单例
        mInstance = null;
    }

    // ========== 以下为你原有方法，原样保留 ==========
    private void initGesture() {
        // 你的手势逻辑
    }

    private void loadChannels() {
        // 加载频道列表逻辑
    }

    private void initChannelList() {
        // 初始化频道列表适配器
    }
}
