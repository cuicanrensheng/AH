package com.tv.live;

import android.os.Handler;
import android.os.Looper;
//新增虎牙解析导入（修复找不到符号核心）
import com.tv.live.HuyaParser;
//============下方保留你项目原有全部import代码============
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

//=====原有所有类变量、常量、广播、方法全部保留，只修改playChannel方法=====
public class MainActivity extends Activity {
    //【你原有全部成员变量不动】
    private TVPlayerManager mPlayerManager;
    private final long CHANNEL_COOLDOWN = 300;
    private long lastSwitchTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //全屏、横屏等原有初始化代码保留
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        //原有初始化播放器、加载频道、EPG代码不变
        initPlayer();
        loadLiveAndEpg();
        registerAllReceiver();
    }

    //============修改后的playChannel播放核心方法============
    private void playChannel(int index) {
        long now = System.currentTimeMillis();
        if (now - lastSwitchTime < CHANNEL_COOLDOWN) return;
        lastSwitchTime = now;

        String playUrl = getChannelUrl(index); //你原有获取频道链接方法

        //判断虎牙系列链接：huya/jdshipin/zxyndc
        if (playUrl != null && (playUrl.contains("huya") || playUrl.contains("jdshipin") || playUrl.contains("zxyndc"))) {
            new HuyaParser().parse(playUrl, new HuyaParser.OnParseResultListener() {
                @Override
                public void onSuccess(String realPlayUrl, int type) {
                    //主线程播放解析后的真实地址
                    new Handler(Looper.getMainLooper()).post(() -> mPlayerManager.playUrl(realPlayUrl));
                }

                @Override
                public void onError(String msg) {
                    //解析失败，使用原地址兜底播放
                    new Handler(Looper.getMainLooper()).post(() -> mPlayerManager.playUrl(playUrl));
                }
            });
        } else {
            //普通链接直接播放
            mPlayerManager.playUrl(playUrl);
        }
        //原有更新频道名称、EPG、UI代码全部保留
        updateChannelInfo(index);
    }

    //=====下方所有原有方法：playPrev、playNext、loadLiveAndEpg、广播、onPause、onDestroy全部保留不修改=====
    private void initPlayer(){/*你的原有代码*/}
    private String getChannelUrl(int pos){/*你的原有代码*/return "";}
    private void updateChannelInfo(int pos){/*你的原有代码*/}
    private void loadLiveAndEpg(){/*你的原有加载源代码*/}
    private void registerAllReceiver(){/*你的广播注册代码*/}

    @Override
    protected void onPause() {
        super.onPause();
        mPlayerManager.pausePlay();
    }
    @Override
    protected void onDestroy() {
        unregisterAllReceiver();
        mPlayerManager.release();
        super.onDestroy();
    }
    private void unregisterAllReceiver(){/*注销广播*/}
}
