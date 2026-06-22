package com.tv.live;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;

public class PlayerGestureHelper {
    private final GestureDetector gestureDetector;
    private final GestureCallback callback;

    public PlayerGestureHelper(Context context, GestureCallback callback) {
        this.callback = callback;
        gestureDetector = new GestureDetector(context, new MyGestureListener());
    }

    public void handleTouch(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
    }

    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            callback.onOk(); return true;
        }
        @Override public boolean onDoubleTap(MotionEvent e) {
            callback.onMenu(); return true;
        }
        @Override public void onLongPress(MotionEvent e) {
            callback.onLongOk();
        }
        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (Math.abs(dy) > Math.abs(dx)) {
                if (dy < -10) callback.onPrevChannel();
                if (dy > 10) callback.onNextChannel();
            }
            return true;
        }
    }

    public interface GestureCallback {
        void onOk();
        void onLongOk();
        void onMenu();
        void onPrevChannel();
        void onNextChannel();
    }
}
