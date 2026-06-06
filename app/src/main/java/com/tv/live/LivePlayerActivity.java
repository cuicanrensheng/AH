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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_player);

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

        String url = getIntent().getStringExtra("url");
        mgr = TVPlayerManager.getInstance(this);
        mgr.attachPlayerView(playerView);
        mgr.playUrl(url);

        getWindow().getDecorView().setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN || ev.getAction() == MotionEvent.ACTION_MOVE) {
                infoBar.setVisibility(View.VISIBLE);
                infoBar.removeCallbacks(hide);
                infoBar.postDelayed(hide, 5000);
            }
            return false;
        });
        
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

    private final Runnable hide = () -> infoBar.setVisibility(View.GONE);

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mgr != null) mgr.stopAutoRefresh();
    }
}
