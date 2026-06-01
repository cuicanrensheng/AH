package com.tv.live;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;

public class LivePlayerActivity extends AppCompatActivity {

    private PlayerView playerView;
    private TVPlayerManager mgr;

    private View infoBar;
    private TextView tvChannel, tvFhd, tvAudio, tvBitrate;
    private TextView tvNow, tvNowTime, tvRemain, tvNext, tvNextTime;
    private ProgressBar progress;

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
        tvRemain   = findViewById(R.id.remaining_time);
        tvNext     = findViewById(R.id.tv_next_program_name);
        tvNextTime = findViewById(R.id.tv_next_time_range);

        // 使用你自带的 TVPlayerManager（自带请求头 + Cookie）
        mgr = TVPlayerManager.getInstance(this);
        mgr.attachPlayerView(playerView);

        // 获取地址 → 直接播放（不解析、不跳转、不拆接口）
        String url = getIntent().getStringExtra("url");
        if (url != null) {
            mgr.playUrl(url);
        }

        // 触摸显示/隐藏
        playerView.setOnTouchListener((v, ev) -> {
            if (infoBar != null) {
                infoBar.setVisibility(View.VISIBLE);
                infoBar.removeCallbacks(hide);
                infoBar.postDelayed(hide, 1000);
            }
            return false;
        });

        // 节目单刷新
        mgr.startAutoRefresh(url, info -> {
            tvChannel.setText(info.channel);
            tvNow.setText(info.nowTitle);
            tvNowTime.setText(info.nowTime);
            progress.setProgress(info.progress);
            tvRemain.setText(info.remain + "分钟");
            tvNext.setText(info.nextTitle);
            tvNextTime.setText(info.nextTime);

            TVPlayerManager.LiveInfo live = mgr.getLiveInfo();
            tvFhd.setText(live.quality);
            tvAudio.setText(live.audio);
            tvBitrate.setText(live.bitrate);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mgr != null) mgr.stopAutoRefresh();
        if (infoBar != null) infoBar.removeCallbacks(hide);
    }
}
