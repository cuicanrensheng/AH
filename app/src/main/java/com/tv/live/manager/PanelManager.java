package com.tv.live.manager;

import android.view.View;
import com.google.android.exoplayer2.ui.PlayerView;
import com.tv.live.AppConfig;
import com.tv.live.GestureManager;
import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;
import com.tv.live.ScreenRatioManager;
import com.tv.live.TVPlayerManager;
import com.tv.live.listener.PlayerStateListenerImpl;

public class PlayerInitManager {
    private final MainActivity activity;
    private final PlayerView playerView;
    private final AppConfig appConfig;

    public PlayerInitManager(MainActivity activity, PlayerView playerView, AppConfig appConfig) {
        this.activity = activity;
        this.playerView = playerView;
        this.appConfig = appConfig;
    }

    public void init(PlayerStateListenerImpl listener) {
        TVPlayerManager mPlayerManager = TVPlayerManager.getInstance(activity);
        mPlayerManager.attachPlayerView(playerView);
        mPlayerManager.setOnPlayStateListener(listener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {});

        ScreenRatioManager screenRatio = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatio.apply();

        GestureManager gestureManager = new GestureManager(activity);
        PlayerGestureHelper helper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            helper.handleTouch(event);
            return true;
        });

        activity.setPlayerManager(mPlayerManager);
        activity.setScreenRatioManager(screenRatio);
    }
}
