package com.tv.live;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hide = new Runnable() {
        @Override
        public void run() {
            if (infoBar != null) {
                infoBar.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_player);

        // 绑定控件
        playerView = findViewById(R.id.player_view);
        infoBar = findViewById(R.id.info_bar);
        tvChannel = findViewById(R.id.tv_channel_name);
        tvFhd = findViewById(R.id.tv_tag_fhd);
        tvAudio = findViewById(R.id.tv_tag_audio);
        tvBitrate = findViewById(R.id.tv_bitrate);
        tvNow = findViewById(R.id.tv_current_program_name);
        tvNowTime = findViewById(R.id.tv_current_time_range);
        progress = findViewById(R.id.progress_program);
        tvRemain = findViewById(R.id.tv_remaining_time);
        tvNext = findViewById(R.id.tv_next_program_name);
        tvNextTime = findViewById(R.id.tv_next_time_range);

        // 初始化时强制显示，确保能看到
        infoBar.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(hide, 5000);

        // 初始化播放器
        String url = getIntent().getStringExtra("url");
        mgr = TVPlayerManager.getInstance(this);
        mgr.attachPlayerView(playerView);
        mgr.playUrl(url);

        // 监听遥控器按键（电视设备必用，这个一定能触发）
        playerView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                showInfoBar();
            }
            return false;
        });

        // 启动自动刷新（用匿名内部类）
        mgr.startAutoRefresh(url, new TVPlayerManager.OnPlayInfoListener() {
            @Override
            public void onSuccess(TVPlayerManager.PlayInfo info) {
                if (info == null) return;

                tvChannel.setText(info.channel != null ? info.channel : "未知频道");
                tvNow.setText(info.nowTitle != null ? info.nowTitle : "暂无节目");
                tvNowTime.setText(info.nowTime != null ? info.nowTime : "");
                progress.setProgress(info.progress);
                tvRemain.setText(info.remain + "分钟");
                tvNext.setText(info.nextTitle != null ? info.nextTitle : "");
                tvNextTime.setText(info.nextTime != null ? info.nextTime : "");

                TVPlayerManager.LiveInfo live = mgr.getLiveInfo();
                tvFhd.setText(live.quality);
                tvAudio.setText(live.audio);
                tvBitrate.setText(live.bitrate);
            }
        });
    }

    private void showInfoBar() {
        if (infoBar != null) {
            infoBar.setVisibility(View.VISIBLE);
            mainHandler.removeCallbacks(hide);
            mainHandler.postDelayed(hide, 5000);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mgr != null) {
            mgr.stopAutoRefresh();
            mgr.release();
        }
        if (infoBar != null) {
            mainHandler.removeCallbacks(hide);
        }
    }
}
