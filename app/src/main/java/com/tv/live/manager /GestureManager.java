package com.tv.live.manager;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

public class GestureManager {

    private final MainActivity activity;
    private PlayerGestureHelper gestureHelper;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                activity.togglePanel();
            }

            @Override
            public void onLongOk() {
                activity.openSettings();
            }

            @Override
            public void onMenu() {
                activity.openSettings();
            }

            @Override
            public void onPrevChannel() {
                activity.playNext();
            }

            @Override
            public void onNextChannel() {
                activity.playPrev();
            }
        });
    }
}
