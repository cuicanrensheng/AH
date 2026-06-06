package com.tv.live;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;

public class PlayerGestureHelper {
    private final GestureDetector gestureDetector;
    private final GestureCallback callback;

    public PlayerGestureHelper(Context context, GestureCallback callback) {
        this.callback = callback;
        // 禁用长按抢占滑动，避免长按导致滑动失效
        gestureDetector = new GestureDetector(context, new MyGestureListener());
        gestureDetector.setIsLongpressEnabled(true);
    }

    public boolean handleTouch(MotionEvent event) {
        // 返回gestureDetector结果，保证事件持续下发
        return gestureDetector.onTouchEvent(event);
    }

    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {
        // 关键：onDown必须return true，才能接收后续MOVE滑动事件
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        // 单击 = OK键 → 弹出频道列表
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            callback.onOk();
            return true;
        }

        // 双击 = Menu/Help → 打开设置
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            callback.onMenu();
            return true;
        }

        // 长按OK → 打开设置
        @Override
        public void onLongPress(MotionEvent e) {
            callback.onLongOk();
        }

        /**
         * dy<0 = 手指向上滑 → 上一个频道Prev
         * dy>0 = 手指向下滑 → 下一个频道Next
         */
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            // 只处理竖直滑动，过滤横向
            if (Math.abs(dy) > Math.abs(dx)) {
                // 向上滑动（dy负数）→上一频道
                if (dy < -12) {
                    callback.onPrevChannel();
                }
                // 向下滑动（dy正数）→下一频道
                if (dy > 12) {
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
