package com.tv.live.manager;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 显示管理器
 *
 * 【功能说明】
 * 统一管理所有和显示相关的功能，包括：
 * 1. 全面屏适配（刘海屏、沉浸式、系统栏隐藏）
 * 2. 加载动画（动态创建、显示、隐藏）
 */
public class DisplayManager {

    // ====================== 成员变量 ======================
    private final Activity activity;
    private View loadingView;
    private TextView tvLoadingText;
    private boolean loadingViewInitialized = false;

    // 🟢【新增缓存】防止重复触发系统窗口重绘
    private boolean fullScreenApplied = false;

    // ====================== 构造函数 ======================
    public DisplayManager(Activity activity) {
        this.activity = activity;
    }

    // ====================================================================
    // ✅ 功能一：全面屏适配
    // ====================================================================

    /**
     * 应用全面屏适配
     */
    public void applyFullScreen() {
        // 🟢【优化】如果已经应用过，且没有重置，直接返回，避免系统窗口重复重绘
        if (fullScreenApplied) {
            return;
        }

        try {
            // ================================================
            // 第一部分：刘海屏适配 + 全屏标志 + 旧版沉浸式
            // ================================================
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                } else {
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                activity.getWindow().setAttributes(lp);
            }

            activity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            );

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                activity.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }

            // ================================================
            // 第二部分：Android 11+ 的 WindowInsetsController
            // ================================================
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller =
                        activity.getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
                activity.getWindow().setDecorFitsSystemWindows(false);
            }

            // 🟢【缓存】标记已成功应用全屏，防止后续重复调用
            fullScreenApplied = true;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 重新应用全面屏（页面获得焦点时调用）
     * 🟢【优化】重置标记位，确保再次应用生效
     */
    public void reapplyFullScreen() {
        // 重置缓存，允许下二次调用时重新应用系统属性
        fullScreenApplied = false;
        applyFullScreen();
    }

    // ====================================================================
    // ✅ 功能二：加载动画
    // ====================================================================

    /**
     * 初始化加载视图（动态创建）
     */
    private void initLoadingView() {
        if (loadingViewInitialized) return;

        try {
            FrameLayout rootLayout = activity.findViewById(android.R.id.content);

            FrameLayout loadingLayout = new FrameLayout(activity);
            loadingLayout.setBackgroundColor(0xEE000000);
            loadingLayout.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            loadingLayout.setVisibility(View.GONE);

            LinearLayout linearLayout = new LinearLayout(activity);
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            linearLayout.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams llParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            llParams.gravity = Gravity.CENTER;
            linearLayout.setLayoutParams(llParams);

            ProgressBar progressBar = new ProgressBar(activity);
            linearLayout.addView(progressBar);

            tvLoadingText = new TextView(activity);
            tvLoadingText.setText("加载中...");
            tvLoadingText.setTextColor(Color.WHITE);
            tvLoadingText.setTextSize(16);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.setMargins(0, 20, 0, 0);
            tvLoadingText.setLayoutParams(textParams);
            linearLayout.addView(tvLoadingText);

            loadingLayout.addView(linearLayout);
            rootLayout.addView(loadingLayout);

            loadingView = loadingLayout;
            loadingViewInitialized = true;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 显示加载动画
     */
    public void showLoading(String text) {
        if (!loadingViewInitialized) {
            initLoadingView();
        }

        if (loadingView != null) {
            loadingView.setVisibility(View.VISIBLE);
        }
        if (tvLoadingText != null && text != null) {
            tvLoadingText.setText(text);
        }
    }

    public void showLoading() {
        showLoading("加载中...");
    }

    public void updateLoadingText(String text) {
        if (tvLoadingText != null && text != null) {
            tvLoadingText.setText(text);
        }
    }

    public void hideLoading() {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
    }

    public boolean isLoadingShowing() {
        return loadingView != null && loadingView.getVisibility() == View.VISIBLE;
    }

    // ====================================================================
    // 资源释放
    // ====================================================================

    /**
     * 释放资源
     */
    public void release() {
        // 🟢【优化】先判空，避免 getParent 为 null 时导致的崩溃
        if (loadingView != null && loadingView.getParent() != null) {
            try {
                ((ViewGroup) loadingView.getParent()).removeView(loadingView);
            } catch (Exception e) {
                // 忽略移除失败
            }
        }
        loadingView = null;
        tvLoadingText = null;
        loadingViewInitialized = false;
        fullScreenApplied = false;
    }
}
