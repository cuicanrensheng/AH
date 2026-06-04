package com.tv.live.manager;

import android.view.KeyEvent;
import com.tv.live.MainActivity;

public class KeyEventManager {

    private final MainActivity activity;

    public KeyEventManager(MainActivity activity) {
        this.activity = activity;
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();

        // 长按 OK → 设置
        if (event.isLongPress()) {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER) {
                activity.openSettings();
                return true;
            }
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            switch (key) {
                // 短按 OK → 面板
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    activity.togglePanel();
                    return true;

                // 菜单 / 帮助 → 设置
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_HELP:
                    activity.openSettings();
                    return true;

                // 上键 → 上一频道
                case KeyEvent.KEYCODE_DPAD_UP:
                    activity.playPrev();
                    return true;

                // 下键 → 下一频道
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    activity.playNext();
                    return true;

                // 数字键选台
                case KeyEvent.KEYCODE_0: case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_2:
                case KeyEvent.KEYCODE_3: case KeyEvent.KEYCODE_4: case KeyEvent.KEYCODE_5:
                case KeyEvent.KEYCODE_6: case KeyEvent.KEYCODE_7: case KeyEvent.KEYCODE_8:
                case KeyEvent.KEYCODE_9:
                    int num = key - KeyEvent.KEYCODE_0;
                    activity.selectChannelByNumber(num);
                    return true;
            }
        }
        return false;
    }
}
