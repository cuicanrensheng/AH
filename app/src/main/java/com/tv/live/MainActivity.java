package com.tv.live;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static MainActivity mInstance;
    private TVPlayerManager playerManager;
    private List<Channel> channelList;
    private int currentPlayIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mInstance = this;
        initPlayer();
        loadLiveSource();
    }

    private void initPlayer() {
        playerManager = TVPlayerManager.getInstance(this);
        playerManager.initPlayer(findViewById(R.id.player_view));
    }

    private void loadLiveSource() {
        LiveSourceLoader.load(new LiveSourceCallback() {
            @Override
            public void onSuccess(List<Channel> list) {
                channelList = list;
                if (!list.isEmpty()) {
                    playChannel(0);
                }
            }

            @Override
            public void onFail() {
                Toast.makeText(MainActivity.this, "直播源加载失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ====================== 核心：我已经帮你加好虎牙解析调用 ======================
    private void playChannel(int index) {
        if (channelList == null || index < 0 || index >= channelList.size()) return;
        currentPlayIndex = index;
        Channel channel = channelList.get(index);
        String url = channel.getUrl();

        // 自动识别虎牙链接 → 调用 HuyaParser 解析
        if (url != null && (url.contains("huya") || url.contains("jdshipin") || url.contains("zxyndc"))) {
            new HuyaParser().parse(extractRoomId(url), new HuyaParser.OnParseResultListener() {
                @Override
                public void onSuccess(String playUrl, int type) {
                    playerManager.play(playUrl);
                }

                @Override
                public void onError(String msg) {
                    playerManager.play(url);
                }
            });
        } else {
            playerManager.play(url);
        }
    }

    // 从链接提取房间号（给虎牙解析用）
    private int extractRoomId(String url) {
        try {
            if (url.contains("id=")) {
                return Integer.parseInt(url.split("id=")[1].replaceAll("[^0-9]", ""));
            }
            if (url.contains("huya.com/")) {
                return Integer.parseInt(url.split("huya.com/")[1].replaceAll("[^0-9]", ""));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    // ====================== 下面是你原有代码结构保留 ======================
    public void playNext() {
        if (currentPlayIndex + 1 < channelList.size()) {
            playChannel(currentPlayIndex + 1);
        }
    }

    public void playPrev() {
        if (currentPlayIndex - 1 >= 0) {
            playChannel(currentPlayIndex - 1);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        playerManager.release();
    }

    public static MainActivity getInstance() {
        return mInstance;
    }
}
