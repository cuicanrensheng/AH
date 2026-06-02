package com.tv.live.listener;

import android.content.Context;
import android.widget.Toast;
import com.tv.live.manager.TVPlayerManager;

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
        // 这里清空：不弹“正在播放：xxx”
        // Toast.makeText(context, "正在播放：" + currentChannelName, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPlayReady() {}

    @Override
    public void onPlayEnd() {
        Toast.makeText(context, "播放结束，自动重试", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPlayError(String msg) {
        Toast.makeText(context, "播放异常：" + msg, Toast.LENGTH_SHORT).show();
    }
}
