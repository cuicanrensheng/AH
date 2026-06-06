package com.tv.live;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;

/**
 * 触屏手势规则：
 * 单击 = OK → 开关频道面板
 * 双击 = 菜单 → 设置
 * 长按 = 长按OK → 设置
 * 上滑=上一频道，下滑=下一频道
 */
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
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            callback.onOk();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            callback.onMenu();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            callback.onLongOk();
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (Math.abs(dy) > Math.abs(dx)) {
                if (dy < -15) callback.onPrevChannel();
                if (dy > 15) callback.onNextChannel();
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
