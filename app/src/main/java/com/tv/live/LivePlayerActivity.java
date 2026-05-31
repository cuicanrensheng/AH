package com.tv.live;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LivePlayerActivity extends AppCompatActivity {
    public static final String EXTRA_HUYA_ROOM_ID = "huya_room_id";

    private PlayerView playerView;
    private TVPlayerManager mgr;
    private View infoBar;
    private TextView tvChannel, tvFhd, tvAudio, tvBitrate;
    private TextView tvNow, tvNowTime, tvRemain, tvNext, tvNextTime;
    private ProgressBar progress;

    // OkHttp 全局实例（带Cookie、防盗链）
    private OkHttpClient mHuyaOkClient;

    // 2秒隐藏任务
    private final Runnable hide = () -> infoBar.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_player);

        // 绑定原有UI控件
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

        // 初始化 OkHttp（Cookie + Referer + UA 防盗链）
        initHuyaOkHttp();

        mgr = TVPlayerManager.getInstance(this);
        mgr.attachPlayerView(playerView);

        // 区分：普通流地址 / 虎牙直播间ID
        String normalUrl = getIntent().getStringExtra("url");
        String huyaRoomId = getIntent().getStringExtra(EXTRA_HUYA_ROOM_ID);

        if (huyaRoomId != null && !huyaRoomId.isEmpty()) {
            // 走虎牙拉流逻辑
            getHuyaStreamAndPlay(huyaRoomId);
        } else if (normalUrl != null && !normalUrl.isEmpty()) {
            // 原有普通播放逻辑不变
            mgr.playUrl(normalUrl);
        }

        // 原有触摸显示/自动隐藏逻辑 完全保留
        playerView.setOnTouchListener((v, ev) -> {
            if (infoBar != null) {
                infoBar.setVisibility(View.VISIBLE);
                infoBar.removeCallbacks(hide);
                infoBar.postDelayed(hide, 1000);
            }
            return false;
        });

        // 原有节目单自动刷新 完全保留
        String playUrl = (huyaRoomId != null) ? huyaRoomId : normalUrl;
        mgr.startAutoRefresh(playUrl, info -> {
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

    /**
     * 初始化虎牙专用 OkHttp：Cookie会话 + 防盗链请求头
     */
    private void initHuyaOkHttp() {
        CookieJar cookieJar = new CookieJar() {
            private final Map<String, List<Cookie>> cookieStore = new HashMap<>();

            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                cookieStore.put(url.host(), new ArrayList<>(cookies));
            }

            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> cookies = cookieStore.get(url.host());
                return cookies != null ? cookies : new ArrayList<>();
            }
        };

        mHuyaOkClient = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor((Interceptor) chain -> {
                    Request req = chain.request().newBuilder()
                            .header("Referer", "https://www.huya.com/")
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android TV 10) AppleWebKit/537.36 Chrome/114.0.0 Safari/537.36")
                            .build();
                    return chain.proceed(req);
                })
                .build();
    }

    /**
     * 根据虎牙直播间ID，请求接口 → 解析 → 拿到流地址 → 调用原有播放器播放
     */
    private void getHuyaStreamAndPlay(final String roomId) {
        // 网络请求放子线程
        new Thread(() -> {
            try {
                String api = "https://www.huya.com/cache.php?m=Live&do=room&roomid=" + roomId;
                Request request = new Request.Builder().url(api).build();
                Response response = mHuyaOkClient.newCall(request).execute();

                if (!response.isSuccessful() || response.body() == null) {
                    return;
                }

                String json = response.body().string();
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonObject data = root.getAsJsonObject("data");
                JsonObject stream = data.getAsJsonArray("gameStreamInfoList").get(0).getAsJsonObject();

                String sFlvUrl = stream.get("sFlvUrl").getAsString();
                String sStreamName = stream.get("sStreamName").getAsString();
                String sAntiCode = stream.get("sFlvAntiCode").getAsString();

                // 拼接最终播放地址
                final String playUrl = sFlvUrl + "/" + sStreamName + "_" + sAntiCode + ".flv";

                // 切回主线程调用原有播放方法
                runOnUiThread(() -> mgr.playUrl(playUrl));

            } catch (IOException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mgr != null) mgr.stopAutoRefresh();
        if (infoBar != null) infoBar.removeCallbacks(hide);
    }
}
