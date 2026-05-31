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

    // 用于隐藏信息栏的Handler和Runnable
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

        // 绑定控件（ID和你原来的完全一致）
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

        // 强制显示一次，方便你调试
        infoBar.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(hide, 5000);

        // 初始化播放器
        String url = getIntent().getStringExtra("url");
        mgr = TVPlayerManager.getInstance(this);
        mgr.attachPlayerView(playerView);
        mgr.playUrl(url);

        // 方案1：触摸事件监听（修复版）
        playerView.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN || ev.getAction() == MotionEvent.ACTION_MOVE) {
                showInfoBar();
            }
            return false;
        });

        // 方案2：按键事件监听（电视遥控器必用，这个一定能触发）
        playerView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                showInfoBar();
            }
            return false;
        });

        // 启动自动刷新（用匿名内部类，避免Lambda兼容性问题）
        mgr.startAutoRefresh(url, new TVPlayerManager.OnPlayInfoListener() {
            @Override
            public void onSuccess(TVPlayerManager.PlayInfo info) {
                if (info == null) return;

                // 更新节目信息
                tvChannel.setText(info.channel != null ? info.channel : "未知频道");
                tvNow.setText(info.nowTitle != null ? info.nowTitle : "暂无节目");
                tvNowTime.setText(info.nowTime != null ? info.nowTime : "");
                progress.setProgress(info.progress);
                tvRemain.setText(info.remain + "分钟");
                tvNext.setText(info.nextTitle != null ? info.nextTitle : "");
                tvNextTime.setText(info.nextTime != null ? info.nextTime : "");

                // 更新播放信息
                TVPlayerManager.LiveInfo live = mgr.getLiveInfo();
                tvFhd.setText(live.quality);
                tvAudio.setText(live.audio);
                tvBitrate.setText(live.bitrate);
            }
        });
    }

    // 显示信息栏的统一方法
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
        // 停止刷新，移除回调，防止内存泄漏
        if (mgr != null) {
            mgr.stopAutoRefresh();
            mgr.release();
        }
        if (infoBar != null) {
            mainHandler.removeCallbacks(hide);
        }
    }
}
