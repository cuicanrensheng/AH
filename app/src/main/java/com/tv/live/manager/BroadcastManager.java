package com.tv.live.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import com.tv.live.MainActivity;
import com.tv.live.receiver.AppBroadcast;

public class BroadcastManager {
    private final MainActivity activity;
    private BroadcastReceiver toggleReceiver;
    private BroadcastReceiver refreshReceiver;

    public BroadcastManager(MainActivity activity) {
        this.activity = activity;
    }

    public void register() {
        toggleReceiver = AppBroadcast.getToggleControllerReceiver(activity);
        refreshReceiver = AppBroadcast.getRefreshReceiver(activity);

        activity.registerReceiver(toggleReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
        activity.registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));
    }

    public void unregister() {
        try {
            if (toggleReceiver != null) activity.unregisterReceiver(toggleReceiver);
            if (refreshReceiver != null) activity.unregisterReceiver(refreshReceiver);
        } catch (Exception ignored) {}
    }
}
