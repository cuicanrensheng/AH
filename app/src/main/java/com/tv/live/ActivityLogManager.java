package com.tv.live;

import android.os.Handler;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tv.live.util.LogCollector;

public class ActivityLogManager {
    private final Handler mainHandler;
    private View logWindowContainer;
    private ScrollView logScrollView;
    private TextView tvLogContent;
    private boolean logWindowVisible = false;
    private Runnable logUpdateRunnable;

    public ActivityLogManager(Handler mainHandler) {
        this.mainHandler = mainHandler;
    }

    public void initViews(View logWindowContainer, ScrollView logScrollView, TextView tvLogContent) {
        this.logWindowContainer = logWindowContainer;
        this.logScrollView = logScrollView;
        this.tvLogContent = tvLogContent;
    }

    public void showLogWindow() {
        if (logWindowVisible) return;
        logWindowVisible = true;
        if (logWindowContainer != null) {
            logWindowContainer.setVisibility(View.VISIBLE);
        }
        startLogUpdate();
    }

    public void hideLogWindow() {
        if (!logWindowVisible) return;
        logWindowVisible = false;
        if (logWindowContainer != null) {
            logWindowContainer.setVisibility(View.GONE);
        }
        stopLogUpdate();
    }

    private void startLogUpdate() {
        if (logUpdateRunnable != null) return;
        logUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!logWindowVisible) {
                    stopLogUpdate();
                    return;
                }

                String logs = LogCollector.getInstance().getAllLogs();

                if (logs.contains(LogCollector.DIVIDER_TOKEN)) {
                    int viewWidth = tvLogContent.getMeasuredWidth();
                    int eqCount = 30;

                    if (viewWidth > 0) {
                        float eqWidth = tvLogContent.getPaint().measureText("=");
                        eqCount = (int) (viewWidth / eqWidth);
                        if (eqCount > 200) eqCount = 200;
                    }

                    String eqString = new String(new char[eqCount]).replace('\0', '=');
                    logs = logs.replace(LogCollector.DIVIDER_TOKEN, eqString);
                }

                tvLogContent.setText(logs);
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                mainHandler.postDelayed(this, 300);
            }
        };
        mainHandler.post(logUpdateRunnable);
    }

    private void stopLogUpdate() {
        if (logUpdateRunnable != null) {
            mainHandler.removeCallbacks(logUpdateRunnable);
            logUpdateRunnable = null;
        }
    }

    public static void toggleLogWindow(MainActivity activity, boolean enable) {
        if (activity == null) return;
        ActivityLogManager manager = activity.getLogManager();
        if (manager != null) {
            if (enable) {
                manager.showLogWindow();
            } else {
                manager.hideLogWindow();
            }
        }
    }

    public void cleanup() {
        stopLogUpdate();
        logWindowContainer = null;
        logScrollView = null;
        tvLogContent = null;
    }
}
