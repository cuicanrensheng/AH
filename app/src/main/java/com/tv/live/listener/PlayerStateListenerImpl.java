package com.tv.live.listener;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.tv.live.MainActivity;
import com.tv.live.TVPlayerManager;

public class PlayerStateListenerImpl implements TVPlayerManager.OnPlayStateListener {
    private final Context context;
    private String currentChannelName = "";

    public PlayerStateListenerImpl(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCurrentChannelName(String name) {
        this.currentChannelName = name;
    }

    @Override
    public void onIdle() {}

    @Override
    public void onBuffering() {
        // ✅ 彻底删除所有Toast
    }

    @Override
    public void onPlayReady() {}

    @Override
    public void onPlayEnd() {
        // ✅ 静默自动重试，不弹Toast
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (MainActivity.mInstance != null) {
                MainActivity.mInstance.playChannel(MainActivity.mInstance.currentPlayIndex);
            }
        }, 500);
    }

    @Override
    public void onPlayError(String msg) {
        // ✅ 播放错误也静默重试
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (MainActivity.mInstance != null) {
                MainActivity.mInstance.playChannel(MainActivity.mInstance.currentPlayIndex);
            }
        }, 1000);
    }
}
