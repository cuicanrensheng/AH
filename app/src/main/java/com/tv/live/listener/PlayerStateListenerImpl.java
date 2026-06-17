package com.tv.live.listener;

import android.content.Context;
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
    public void onBuffering() {}

    @Override
    public void onPlayReady() {}

    @Override
    public void onPlayEnd() {
        // ✅ 什么都不做！不要自动重试！
        // 源有问题用户会手动切台
    }

    @Override
    public void onPlayError(String msg) {
        // ✅ 错误也不重试，用户手动切台
    }
}
