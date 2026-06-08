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

        if (event.getAction() == KeyEvent.ACTION_UP) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    activity.togglePanel();
                    return true;

                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_HELP:
                    activity.openSettings();
                    return true;

                case KeyEvent.KEYCODE_DPAD_UP:
                    activity.playPrev();
                    return true;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    activity.playNext();
                    return true;
            }
        }
        return false;
    }
}
