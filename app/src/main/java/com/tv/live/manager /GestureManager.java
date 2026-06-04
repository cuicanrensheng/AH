package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

public class GestureManager {

    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLoop());
    private static final long DEBOUNCE_MS = 300;
    private boolean locked = false;

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
                if (!locked) {
                    locked = true;
                    activity.playPrev();
                    mainHandler.postDelayed(() -> locked = false, DEBOUNCE_MS);
                }
            }

            // 下滑 / 下键 → 下一频道
            @Override
            public void onNextChannel() {
                if (!locked) {
                    locked = true;
                    activity.playNext();
                    mainHandler.postDelayed(() -> locked = false, DEBOUNCE_MS);
                }
            }

            // 双击 → 设置
            @Override
            public void onDoubleTap() {
                activity.openSettings();
            }

            // 长按屏幕 → 设置
            @Override
            public void onLongPress() {
                activity.openSettings();
            }
        });
    }
}
