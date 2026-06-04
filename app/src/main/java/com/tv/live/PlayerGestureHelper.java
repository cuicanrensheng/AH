package com.tv.live;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;

public class PlayerGestureHelper {
    private final GestureDetector gestureDetector;
    private final GestureCallback callback;

    // 提升滑动灵敏度阈值，防止误触
    private static final float SCROLL_THRESHOLD = 20;

    public PlayerGestureHelper(Context context, GestureCallback callback) {
        this.callback = callback;
        gestureDetector = new GestureDetector(context, new MyGestureListener());
    }

    public void handleTouch(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
    }

    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (callback != null) {
                callback.onOk();
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (callback != null) {
                callback.onMenu();
            }
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (callback != null) {
                callback.onLongOk();
            }
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (callback == null) return true;

            if (Math.abs(dy) > Math.abs(dx)) {
                // 上下滑动 → 切台
                if (dy < -SCROLL_THRESHOLD) {
                    callback.onPrevChannel();
                } else if (dy > SCROLL_THRESHOLD) {
                    callback.onNextChannel();
                }
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
