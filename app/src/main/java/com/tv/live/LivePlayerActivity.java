package com.tv.live;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import java.util.HashMap;
import java.util.Map;

public class LivePlayerActivity extends AppCompatActivity {

    // 播放器相关
    private PlayerView playerView;
    private ExoPlayer exoPlayer;

    // UI控件
    private View infoBar;
    private TextView tvChannel, tvFhd, tvAudio, tvBitrate;
    private TextView tvNow, tvNowTime, tvRemain, tvNext, tvNextTime;
    private ProgressBar progress;

    // 控制栏自动隐藏
    private final Runnable hide = () -> infoBar.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_player);

        // 绑定控件
        playerView = findViewById(R.id.player_view);
        infoBar    = findViewById(R.id.info_bar);
        tvChannel  = findViewById(R.id.tv_channel_name);
        tvFhd      = findViewById(R.id.tv_tag_fhd);
        tvAudio    = findViewById(R.id.tv_tag_audio);
        tvBitrate  = findViewById(R.id.tv_bitrate);
        tvNow      = findViewById(R.id.tv_current_program_name);
        tvNowTime  = findViewById(R.id.tv_current_time_range);
        progress   = findViewById(R.id.progress_program);
        tvRemain   = findViewById(R.id.tv_remaining_time);
        tvNext     = findViewById(R.id.tv_next_program_name);
        tvNextTime = findViewById(R.id.tv_next_time_range);

        // 获取播放地址
        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) {
            finish();
            return;
        }

        // 初始化 ExoPlayer 并设置请求头
        initExoPlayerWithHeaders(url);

        // 触摸显示/隐藏控制栏
        playerView.setOnTouchListener((v, ev) -> {
            if (infoBar != null) {
                infoBar.setVisibility(View.VISIBLE);
                infoBar.removeCallbacks(hide);
                infoBar.postDelayed(hide, 1000);
            }
            return false;
        });

        // 这里可以根据需要，继续保留你原有的节目单刷新逻辑（如果还需要）
        // mgr.startAutoRefresh(...) 相关代码，因为现在用ExoPlayer，不需要TVPlayerManager了
    }

    /**
     * 初始化 ExoPlayer，并设置请求头
     */
    private void initExoPlayerWithHeaders(String url) {
        // 1. 构造请求头
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android TV 10) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
        headers.put("Referer", "http://cdn.jdshipin.com:8880/");
        headers.put("Accept", "*/*");

        // 2. 创建带请求头的 DataSource.Factory
        HttpDataSource.Factory httpDataSourceFactory =
                new DefaultHttpDataSource.Factory()
                        .setUserAgent("Mozilla/5.0 (Linux; Android TV 10) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                        .setDefaultRequestProperties(headers);

        // 3. 创建 MediaSource.Factory，支持 FLV/HLS 等
        MediaSource.Factory mediaSourceFactory =
                new DefaultMediaSourceFactory(httpDataSourceFactory);

        // 4. 创建 MediaItem
        MediaItem mediaItem = MediaItem.fromUri(url);

        // 5. 创建 MediaSource
        MediaSource mediaSource = mediaSourceFactory.createMediaSource(mediaItem);

        // 6. 初始化 ExoPlayer
        exoPlayer = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();

        // 7. 绑定到 PlayerView
        playerView.setPlayer(exoPlayer);

        // 8. 准备并播放
        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();
        exoPlayer.play();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放 ExoPlayer
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
        if (infoBar != null) {
            infoBar.removeCallbacks(hide);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) {
            exoPlayer.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exoPlayer != null) {
            exoPlayer.play();
        }
    }
}
