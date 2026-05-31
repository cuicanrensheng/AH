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

    // 虎牙直播间ID 标识（备用）
    public static final String EXTRA_HUYA_ROOM_ID = "huya_room_id";

    // 播放器视图
    private PlayerView playerView;
    // 你项目自带的播放器管理类
    private TVPlayerManager mgr;

    // 顶部信息栏相关控件
    private View infoBar;
    private TextView tvChannel, tvFhd, tvAudio, tvBitrate;
    private TextView tvNow, tvNowTime, tvRemain, tvNext, tvNextTime;
    private ProgressBar progress;

    // 虎牙专用 OkHttp（带Cookie + 防盗链）
    private OkHttpClient mHuyaOkClient;

    // 延迟隐藏信息栏的任务
    private final Runnable hide = () -> infoBar.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_player);

        // ====================== 绑定界面控件 ======================
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

        // ====================== 初始化虎牙网络请求工具 ======================
        initHuyaOkHttp();

        // ====================== 绑定你原有播放器 ======================
        mgr = TVPlayerManager.getInstance(this);
        mgr.attachPlayerView(playerView);

        // ====================== 获取传入的播放地址 ======================
        String normalUrl = getIntent().getStringExtra("url");
        String huyaRoomId = getIntent().getStringExtra(EXTRA_HUYA_ROOM_ID);

        // 判断：有虎牙ID → 解析虎牙；没有 → 播放普通地址
        if (huyaRoomId != null && !huyaRoomId.isEmpty()) {
            // 解析虎牙直播流并播放
            getHuyaStreamAndPlay(huyaRoomId);
        } else if (normalUrl != null && !normalUrl.isEmpty()) {
            // 直接播放普通地址（原逻辑）
            mgr.playUrl(normalUrl);
        }

        // ====================== 触摸显示信息栏，1秒后自动隐藏 ======================
        playerView.setOnTouchListener((v, ev) -> {
            if (infoBar != null) {
                infoBar.setVisibility(View.VISIBLE);
                infoBar.removeCallbacks(hide);
                infoBar.postDelayed(hide, 1000);
            }
            return false;
        });

        // ====================== 原有节目信息自动刷新逻辑 ======================
        String playUrl = (huyaRoomId != null) ? huyaRoomId : normalUrl;
        mgr.startAutoRefresh(playUrl, info -> {
            tvChannel.setText(info.channel);
            tvNow.setText(info.nowTitle);
            tvNowTime.setText(info.nowTime);
            progress.setProgress(info.progress);
            tvRemain.setText(info.remain + "分钟");
            tvNext.setText(info.nextTitle);
            tvNextTime.setText(info.nextTime);

            // 显示清晰度、音轨、码率
            TVPlayerManager.LiveInfo live = mgr.getLiveInfo();
            tvFhd.setText(live.quality);
            tvAudio.setText(live.audio);
            tvBitrate.setText(live.bitrate);
        });
    }

    /**
     * 初始化 OkHttp
     * 自带：Cookie会话管理 + Referer + User-Agent 防盗链
     */
    private void initHuyaOkHttp() {
        // 内存 Cookie 管理器
        CookieJar cookieJar = new CookieJar() {
            private final Map<String, List<Cookie>> cookieStore = new HashMap<>();

            // 保存服务器返回的 Cookie
            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                cookieStore.put(url.host(), new ArrayList<>(cookies));
            }

            // 请求时自动带上对应域名的 Cookie
            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> cookies = cookieStore.get(url.host());
                return cookies != null ? cookies : new ArrayList<>();
            }
        };

        // 构建 OkHttp 客户端
        mHuyaOkClient = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor((Interceptor) chain -> {
                    // 统一添加防盗链请求头
                    Request req = chain.request().newBuilder()
                            .header("Referer", "https://www.huya.com/")
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android TV 10) AppleWebKit/537.36 Chrome/114.0.0 Safari/537.36")
                            .build();
                    return chain.proceed(req);
                })
                .build();
    }

    /**
     * 虎牙核心：根据房间ID获取真实播放地址
     */
    private void getHuyaStreamAndPlay(final String roomId) {
        // 网络请求必须在子线程执行
        new Thread(() -> {
            try {
                // 虎牙官方接口地址
                String api = "https://www.huya.com/cache.php?m=Live&do=room&roomid=" + roomId;
                Request request = new Request.Builder().url(api).build();
                Response response = mHuyaOkClient.newCall(request).execute();

                if (!response.isSuccessful() || response.body() == null) {
                    return;
                }

                // 解析 JSON
                String json = response.body().string();
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonObject data = root.getAsJsonObject("data");
                JsonObject stream = data.getAsJsonArray("gameStreamInfoList").get(0).getAsJsonObject();

                // 提取三个关键参数
                String sFlvUrl = stream.get("sFlvUrl").getAsString();
                String sStreamName = stream.get("sStreamName").getAsString();
                String sAntiCode = stream.get("sFlvAntiCode").getAsString();

                // 拼接成可直接播放的 FLV 地址
                final String playUrl = sFlvUrl + "/" + sStreamName + "_" + sAntiCode + ".flv";

                // 切回主线程调用播放器播放
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
        // 停止节目刷新
        if (mgr != null) mgr.stopAutoRefresh();
        // 移除延迟任务，防止内存泄漏
        if (infoBar != null) infoBar.removeCallbacks(hide);
    }
}
