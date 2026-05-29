package com.tv.live;
import android.util.Log;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
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
    private PlayerGestureHelper gestureHelper;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        PlayerView playerView = findViewById(R.id.player_view);
        lvGroup = findViewById(R.id.lv_group);
        lvChannelList = findViewById(R.id.lv_channel_list);
        lvDate = findViewById(R.id.lv_date);
        lvEpg = findViewById(R.id.lv_epg);

        gestureHelper = new PlayerGestureHelper(this, new PlayerGestureHelper.GestureCallback() {
            @Override public void onOk() { openChannelList(); }
            @Override public void onLongOk() { openSettings(); }
            @Override public void onMenu() { openSettings(); }
            @Override public void onPrevChannel() { playPrev(); }
            @Override public void onNextChannel() { playNext(); }
        });
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });

        SharedPreferences spPlay = getSharedPreferences("play_config", Context.MODE_PRIVATE);
        currentRatioIndex = spPlay.getInt("play_ratio", 2);
        currentPlayIndex = spPlay.getInt("last_play_index", 0);

        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        applyScreenRatioFromSettings();

        mPlayerManager.setOnPlayStateListener(new TVPlayerManager.OnPlayStateListener() {
            @Override public void onIdle() {}
            @Override public void onBuffering() { Toast.makeText(MainActivity.this,"缓冲中...",Toast.LENGTH_SHORT).show(); }
            @Override public void onPlayReady() {}
            @Override public void onPlayEnd() { Toast.makeText(MainActivity.this,"播放结束，自动重试",Toast.LENGTH_SHORT).show(); }
            @Override public void onPlayError(String msg) { Toast.makeText(MainActivity.this,"播放异常："+msg,Toast.LENGTH_SHORT).show(); }
        });

        loadLiveAndEpg();
        initGesture();
        initChannelList();
        initListViewClick();
    }

    // ========== 从设置页同步比例 ==========
    private void applyScreenRatioFromSettings() {
        String ratio = sp.getString("screen_ratio", "全屏");
        switch (ratio) {
            case "原始": mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT); break;
            case "填充": mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL); break;
            default: mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM); break;
        }
    }

    private void loadLiveAndEpg() {
        new Thread(() -> {
            try {
                Log.i("MainActivity", "加载直播源...");
                List<Channel> channels = PlaylistParser.parse(UrlConfig.LIVE_URL);
                runOnUiThread(() -> {
                    Log.i("MainActivity", "解析到频道数：" + (channels == null ? "null" : channels.size()));
                    if (channels != null && !channels.isEmpty()) {
                        channelSourceList.clear();
                        channelSourceList.addAll(channels);
                        Toast.makeText(MainActivity.this, "直播源加载完成：" + channelSourceList.size() + "个频道", Toast.LENGTH_SHORT).show();
                        initChannelList();
                        playChannel(0);
                    }
                });

                EpgManager.getInstance().setEpgUrl(UrlConfig.EPG_URL);
                EpgManager.getInstance().loadEpg(() -> {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "EPG节目单加载完成", Toast.LENGTH_SHORT).show();
                    });
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean reverse = sp.getBoolean("channel_reverse", false);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (reverse) playNext(); else playPrev(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (reverse) playPrev(); else playNext(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: openChannelList(); return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_HELP: openSettings(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void playPrev() {
        if (channelSourceList.isEmpty()) return;
        int i = (currentPlayIndex - 1 + channelSourceList.size()) % channelSourceList.size();
        playChannel(i);
    }

    private void playNext() {
        if (channelSourceList.isEmpty()) return;
        int i = (currentPlayIndex + 1) % channelSourceList.size();
        playChannel(i);
    }

    private void openChannelList() {
        Intent intent = new Intent(this, ChannelListActivity.class);
        startActivity(intent);
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    // ========== 切台时自动应用比例 ==========
    public void playChannel(int index) {
        if (channelSourceList.isEmpty()) return;
        currentPlayIndex = index;
        currentChannelIndex = index;
        Channel ch = channelSourceList.get(index);

        if (TextUtils.isEmpty(ch.getPlayUrl())) {
            Toast.makeText(this,"播放地址为空",Toast.LENGTH_SHORT).show();
            return;
        }

        if (mPlayerManager != null) {
            mPlayerManager.playUrl(ch.getPlayUrl());
            applyScreenRatioFromSettings();
        }

        isPlayingPlayback = false;
        getSharedPreferences("play_config",0).edit().putInt("last_play_index",index).apply();
    }

    private void playEpgItem(Channel.EpgItem epg) {
        if (epg == null || TextUtils.isEmpty(epg.getReplayUrl())) {
            Toast.makeText(this,"暂无回看地址",Toast.LENGTH_SHORT).show();
            return;
        }
        if (mPlayerManager != null) {
            mPlayerManager.playUrl(epg.getReplayUrl());
            applyScreenRatioFromSettings();
        }
        isPlayingPlayback = true;
        Toast.makeText(this,"正在播放回看",Toast.LENGTH_SHORT).show();
    }

    private void initListViewClick() {
        if (lvChannelList != null) {
            lvChannelList.setOnItemClickListener((p, v, pos, id) -> {
                Channel c = (Channel) p.getItemAtPosition(pos);
                int i = channelSourceList.indexOf(c);
                playChannel(i);
                lvChannelList.setSelection(pos);
            });
        }
        if (lvEpg != null) {
            lvEpg.setOnItemClickListener((p, v, pos, id) -> {
                Object o = p.getItemAtPosition(pos);
                if (o instanceof Channel.EpgItem) {
                    playEpgItem((Channel.EpgItem) o);
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ========== 从设置页返回时自动刷新比例 ==========
        applyScreenRatioFromSettings();
    }

    @Override
    public void onBackPressed() {
        if (isPlayingPlayback) {
            isPlayingPlayback = false;
            playChannel(currentPlayIndex);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }
        mInstance = null;
    }

    private void initGesture() {}
    private void initChannelList() {}
    public void onReceiveConfig(String u1,String u2){}
}
