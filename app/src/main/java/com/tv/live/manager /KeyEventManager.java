package com.tv.live.manager;

import android.view.KeyEvent;
import com.tv.live.MainActivity;

public class KeyEventManager {

    private final MainActivity activity;

    public KeyEventManager(MainActivity activity) {
        this.activity = activity;
    }

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
                //activity.togglePanel();
                return true;

            case KeyEvent.KEYCODE_MENU:
                //activity.openSettings();
                return true;
        }
        return false;
    }
}
