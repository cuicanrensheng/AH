package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;

public class PlayerGestureHelper {
    private final GestureDetector gestureDetector;
    private final GestureCallback callback;

    // 🟢【核心修复1】引入防连击锁，防止 onScroll 连续触发导致的卡顿
    private boolean isScrollLocked = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long SCROLL_LOCK_DELAY = 500; // 锁定 500ms

    public PlayerGestureHelper(Context context, GestureCallback callback) {
        this.callback = callback;
        gestureDetector = new GestureDetector(context, new MyGestureListener());
    }

    public void handleTouch(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
    }

    // 🟢【优化2】加上防连击锁和方向判定
    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {
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
            // 如果处于锁定状态，直接忽略后续滑动事件
            if (isScrollLocked) {
                return true;
            }

            // 优先判定是垂直滑动（切台）还是水平滑动
            if (Math.abs(dy) > Math.abs(dx)) {
                // 向下滑（数值变大）
                if (dy > 10) {
                    callback.onNextChannel();
                    lockScroll();
                } 
                // 向上滑（数值变小）
                else if (dy < -10) {
                    callback.onPrevChannel();
                    lockScroll();
                }
            }
            return true;
        }

        // 🟢 锁定逻辑：锁定期间阻止再次触发，500ms 后自动解锁
        private void lockScroll() {
            isScrollLocked = true;
            mainHandler.removeCallbacksAndMessages(null);
            mainHandler.postDelayed(() -> {
                isScrollLocked = false;
            }, SCROLL_LOCK_DELAY);
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
