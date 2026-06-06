package com.tv.live;

import android.content.Context;
import android.view.KeyEvent;

public class PlayerGestureHelper {

    private final Context context;
    private final GestureCallback callback;

    public PlayerGestureHelper(Context context, GestureCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    public void handleTouch(android.view.MotionEvent event) {
        // 你原有逻辑不需要改动
    }

    public boolean handleKeyEvent(KeyEvent event) {
        if (callback == null) return false;

        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    callback.onOk();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    callback.onMenu();
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_DPAD_UP:
                    callback.onPrevChannel();
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    callback.onNextChannel();
                    return true;
            }
        }
        return false;
    }

    public interface GestureCallback {
        void onOk();
        void onLongOk();
        void onMenu();
        void onPrevChannel();
        void onNextChannel();
    }
}
