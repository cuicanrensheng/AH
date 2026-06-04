package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

public class GestureManager {

    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_DELAY_MS = 300;
    private boolean isGestureLocked = false;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {

            // 单击屏幕 / 短按OK → 切换频道列表
            @Override
            public void onOk() {
                activity.togglePanel();
            }

            // 长按OK / 菜单键 → 打开设置
            @Override
            public void onLongOk() {
                activity.openSettings();
            }

            @Override
            public void onMenu() {
                activity.openSettings();
            }

            // ====================
            // 手机上滑 / 遥控器上键 → 上一频道
            // ====================
            @Override
            public void onPrevChannel() {
                if (!isGestureLocked) {
                    isGestureLocked = true;
                    activity.playPrev();
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                }
            }

            // ====================
            // 手机下滑 / 遥控器下键 → 下一频道
            // ====================
            @Override
            public void onNextChannel() {
                if (!isGestureLocked) {
                    isGestureLocked = true;
                    activity.playNext();
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                }
            }

            // ====================
            // 双击屏幕 → 打开设置
            // ====================
            @Override
            public void onDoubleTap() {
                activity.openSettings();
            }

            // ====================
            // 长按屏幕 → 打开设置
            // ====================
            @Override
            public void onLongPress() {
                activity.openSettings();
            }
        });
    }
}
