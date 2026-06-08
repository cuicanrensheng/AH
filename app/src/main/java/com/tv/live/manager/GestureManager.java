package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

public class GestureManager {

    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_DELAY_MS = 300; // 300ms防抖
    private boolean isGestureLocked = false;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                // 【已修复】注释掉单击关闭面板，解决日期列表/频道列表点击被拦截的问题
                // activity.togglePanel();
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
                if (!isGestureLocked) {
                    isGestureLocked = true;
                    activity.playPrev();
                    // 解锁
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                }
            }

            @Override
            public void onNextChannel() {
                if (!isGestureLocked) {
                    isGestureLocked = true;
                    activity.playNext();
                    // 解锁
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                }
            }
        });
    }
}
