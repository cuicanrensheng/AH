package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;

public class MainActivity extends AppCompatActivity {

    // ========== 只保留 ExoPlayer ==========
    private ExoPlayer exoPlayer;

    // 固定使用 Exo 模式
    public static final int PLAYER_EXO = 0;

    private int currentChannelIndex = 0;
    private boolean epgEnable = true;
    private boolean reverseChannelOrder = false;
    private String currentSourceUrl;

    private final ActivityResultLauncher<Intent> settingLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    releaseAllPlayer();
                    playCurrentChannel();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 只显示 ExoPlayer
        findViewById(R.id.exo_player_view).setVisibility(View.VISIBLE);

        loadConfig();
        loadLiveSource();
    }

    // ========== 强制只使用 ExoPlayer ==========
    private void playCurrentChannel() {
        String url = getCurrentChannelUrl();
        initExoPlayer(url);
    }

    // ExoPlayer 初始化
    private void initExoPlayer(String url) {
        exoPlayer = new ExoPlayer.Builder(this).build();
        PlayerView playerView = findViewById(R.id.exo_player_view);
        playerView.setPlayer(exoPlayer);

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();
    }

    // 只释放 Exo
    private void releaseAllPlayer() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
    }

    // ========== 原有逻辑保留 ==========
    private void loadConfig() {
    }

    private void loadLiveSource() {
        playCurrentChannel();
    }

    private String getCurrentChannelUrl() {
        // 这里替换成你的真实直播源地址
        return "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("设置").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getTitle().equals("设置")) {
            settingLauncher.launch(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStop() {
        super.onStop();
        releaseAllPlayer();
    }
}
