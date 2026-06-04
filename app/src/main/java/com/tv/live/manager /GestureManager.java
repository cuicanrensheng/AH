package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

public class GestureManager {

    private final MainActivity activity;
    // 修复：正确的主线程 Handler
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_DELAY_MS = 300;
    private boolean isGestureLocked = false;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {

            // 单击 / 短按OK → 频道面板
            @Override
            public void onOk() {
                activity.togglePanel();
            }

            // 长按OK → 设置
            @Override
            public void onLongOk() {
                activity.openSettings();
            }

            // 菜单键 → 设置
            @Override
            public void onMenu() {
                activity.openSettings();
            }

            // 上滑 / 上键 → 上一频道
            @Override
            public void onPrevChannel() {
                if (!isGestureLocked) {
                    isGestureLocked = true;
                    activity.playPrev();
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                }
            }

            // 下滑 / 下键 → 下一频道
            @Override
            public void onNextChannel() {
                if (!isGestureLocked) {
                    isGestureLocked = true;
                    activity.playNext();
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                }
            }
        });
    }
}
