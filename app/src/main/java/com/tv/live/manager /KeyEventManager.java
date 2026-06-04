package com.tv.live.manager;

import android.view.KeyEvent;
import com.tv.live.MainActivity;

public class KeyEventManager {

    private final MainActivity activity;

    public KeyEventManager(MainActivity activity) {
        this.activity = activity;
    }

    // 你原来的方法（保留）
    public boolean dispatchKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                activity.playPrev();
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                activity.playNext();
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                activity.togglePanel();
                return true;

            case KeyEvent.KEYCODE_MENU:
                activity.openSettings();
                return true;
        }
        return false;
    }

    // ===================== 新增这个方法！解决编译错误 =====================
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 只处理按键按下事件，避免重复触发
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return dispatchKey(event.getKeyCode());
        }
        return false;
    }
}
