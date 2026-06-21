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

import com.tv.live.SettingsActivity;

/**
 * 显示管理器
 *
 * 【功能说明】
 * 统一管理所有和显示相关的功能，包括：
 * 1. 全面屏适配（刘海屏、沉浸式、系统栏隐藏）
 * 2. 加载动画（动态创建、显示、隐藏）
 *
 * 【为什么合并成一个文件？】
 * 这两个功能都是和"界面显示"相关的基础功能，
 * 合并在一起方便统一管理，也减少 MainActivity 的代码量。
 *
 * 【电视兼容说明】
 * 所有全面屏适配代码都加了 try-catch，
 * 即使电视不支持这些 API，也不会崩溃，只是不显示全屏效果而已。
 *
 * 【使用方式】
 * 1. 在 onCreate 中创建实例：displayManager = new DisplayManager(this)
 * 2. 调用 applyFullScreen() 应用全面屏适配
 * 3. 调用 showLoading() / hideLoading() 控制加载动画
 * 4. 在 onDestroy 中调用 release() 释放资源
 */
public class DisplayManager {

    // ====================== 成员变量 ======================
    /** 宿主 Activity */
    private final Activity activity;
    /** 加载动画根视图 */
    private View loadingView;
    /** 加载文字提示 */
    private TextView tvLoadingText;
    /** 是否已初始化加载视图 */
    private boolean loadingViewInitialized = false;

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     *
     * @param activity 宿主 Activity
     */
    public DisplayManager(Activity activity) {
        this.activity = activity;
    }

    // ====================================================================
    // ✅ 功能一：全面屏适配
    // ====================================================================

    /**
     * 应用全面屏适配
     *
     * 【包含内容】
     * 1. 刘海屏适配（Android P 及以上）
     * 2. 全屏标志
     * 3. 沉浸式模式（隐藏状态栏和导航栏）
     * 4. Android 11+ 的 WindowInsetsController 新方式
     *
     * 【调用时机】
     * 在 onCreate 中调用，setContentView 之前或之后都可以。
     *
     * 【为什么分这么多方式？】
     * 不同 Android 版本的全面屏 API 不一样：
     * - Android 9 以下：用 setSystemUiVisibility（旧方式）
     * - Android 9-10：用 layoutInDisplayCutoutMode + setSystemUiVisibility
     * - Android 11+：用 WindowInsetsController（新方式）
     * 我们全部都支持，保证在各个版本上都有最好的效果。
     */
    public void applyFullScreen() {
        try {
            // ================================================
            // 第一部分：刘海屏适配 + 全屏标志 + 旧版沉浸式
            // ================================================

            // 1. 刘海屏适配（Android P 及以上）
            // 【为什么需要这个？】
            // 默认情况下，内容不会布局到刘海区域，会有一条黑边。
            // 设置 LAYOUT_IN_DISPLAY_CUTOUT_MODE 后，内容可以延伸到刘海区域，
            // 真正实现全屏效果。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+：always 模式，所有边都允许布局到刘海区域
                    // 【为什么用 always？】
                    // shortEdges 只在短边（上下）允许，左右两边的挖孔屏不生效。
                    // always 模式所有边都允许，适配各种异形屏。
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                } else {
                    // Android 9-11：shortEdges 模式，只在短边（上下）允许布局到刘海区域
                    // 【为什么不用 always？】
                    // Android 9-11 的 always 模式有 bug，某些机型上会导致内容被刘海挡住。
                    // shortEdges 更稳定，上下有刘海的机型也够用了。
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                activity.getWindow().setAttributes(lp);
            }

            // 2. 全屏标志
            // 【作用】告诉 WindowManager 我们要全屏显示，
            // 这样系统会自动隐藏状态栏的一些元素。
            activity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            );

            // 3. Android 10 及以下的沉浸式（旧方式）
            // 【为什么 Android 10 及以下才用？】
            // Android 11+ 推荐用 WindowInsetsController，
            // 但旧版本没有这个 API，只能用 setSystemUiVisibility。
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
            // 第二部分：Android 11+ 的 WindowInsetsController（新方式）
            // ================================================

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller =
                        activity.getWindow().getInsetsController();
                if (controller != null) {
                    // 隐藏系统栏（状态栏 + 导航栏）
                    controller.hide(android.view.WindowInsets.Type.systemBars());

                    // 临时显示行为：滑动显示，过一会自动隐藏
                    // 【为什么用这个？】
                    // 用户从顶部往下滑或者从底部往上滑，系统栏会临时显示出来，
                    // 过几秒自动隐藏，不影响观看体验。
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }

                // 让内容布局到系统栏下面（沉浸式）
                // 【为什么设为 false？】
                // 默认是 true，内容不会布局到系统栏区域，
                // 设为 false 后，内容可以延伸到状态栏和导航栏下面，
                // 真正实现全屏沉浸式效果。
                activity.getWindow().setDecorFitsSystemWindows(false);
            }

            SettingsActivity.logOperation("【适配】全面屏适配成功");

        } catch (Exception e) {
            // ✅ 全面屏适配失败不影响正常使用
            // 【为什么要 try-catch？】
            // 有些电视盒子的 Android 系统是定制的，不支持这些 API，
            // 直接调用会崩溃。加个 try-catch，失败了就不用全屏效果，
            // 至少保证应用能正常运行。
            e.printStackTrace();
            SettingsActivity.logOperation("【适配】全面屏适配失败：" + e.getMessage());
        }
    }

    /**
     * 重新应用全面屏（页面获得焦点时调用）
     *
     * 【为什么需要这个？】
     * 有些情况下系统栏会重新显示出来（比如弹出对话框后），
     * 在 onWindowFocusChanged 里重新调用一下，保证一直是全屏状态。
     */
    public void reapplyFullScreen() {
        // 简单起见，直接重新调用 applyFullScreen
        applyFullScreen();
    }

    // ====================================================================
    // ✅ 功能二：加载动画
    // ====================================================================

    /**
     * 初始化加载视图（动态创建）
     *
     * 【为什么动态创建而不是 XML 布局？】
     * 1. 不需要修改 XML 布局文件，侵入性小
     * 2. 可以在任何 Activity 里复用
     * 3. 加载视图比较简单，动态创建代码量也不大
     *
     * 【视图结构】
     * - 根布局：FrameLayout（黑色半透明背景，全屏）
     *   - 垂直布局：LinearLayout（居中）
     *     - ProgressBar（圆形进度条）
     *     - TextView（加载文字提示）
     */
    private void initLoadingView() {
        if (loadingViewInitialized) return;

        try {
            // 获取 Activity 的根布局（android.R.id.content 是 FrameLayout）
            FrameLayout rootLayout = activity.findViewById(android.R.id.content);

            // ===== 加载容器（黑色半透明背景，全屏） =====
            FrameLayout loadingLayout = new FrameLayout(activity);
            loadingLayout.setBackgroundColor(0xEE000000);  // 93% 不透明度的黑色
            loadingLayout.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            // 默认隐藏
            loadingLayout.setVisibility(View.GONE);

            // ===== 垂直布局（进度条 + 文字，居中） =====
            LinearLayout linearLayout = new LinearLayout(activity);
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            linearLayout.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams llParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            llParams.gravity = Gravity.CENTER;
            linearLayout.setLayoutParams(llParams);

            // ===== 圆形进度条 =====
            ProgressBar progressBar = new ProgressBar(activity);
            linearLayout.addView(progressBar);

            // ===== 加载文字 =====
            tvLoadingText = new TextView(activity);
            tvLoadingText.setText("加载中...");
            tvLoadingText.setTextColor(Color.WHITE);
            tvLoadingText.setTextSize(16);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.setMargins(0, 20, 0, 0);  // 上边距 20px
            tvLoadingText.setLayoutParams(textParams);
            linearLayout.addView(tvLoadingText);

            // 把垂直布局加到加载容器里
            loadingLayout.addView(linearLayout);

            // 把加载容器加到根布局
            rootLayout.addView(loadingLayout);

            loadingView = loadingLayout;
            loadingViewInitialized = true;

            SettingsActivity.logOperation("【加载】加载视图初始化完成");

        } catch (Exception e) {
            e.printStackTrace();
            SettingsActivity.logOperation("【加载】加载视图初始化失败：" + e.getMessage());
        }
    }

    /**
     * 显示加载动画
     *
     * @param text 加载提示文字，为 null 则不修改文字
     */
    public void showLoading(String text) {
        // 第一次调用时初始化
        if (!loadingViewInitialized) {
            initLoadingView();
        }

        if (loadingView != null) {
            loadingView.setVisibility(View.VISIBLE);
        }
        if (tvLoadingText != null && text != null) {
            tvLoadingText.setText(text);
        }

        SettingsActivity.logOperation("【加载】显示加载动画：" + text);
    }

    /**
     * 显示加载动画（使用默认文字）
     */
    public void showLoading() {
        showLoading("加载中...");
    }

    /**
     * 更新加载文字
     *
     * @param text 新的加载提示文字
     */
    public void updateLoadingText(String text) {
        if (tvLoadingText != null && text != null) {
            tvLoadingText.setText(text);
        }
    }

    /**
     * 隐藏加载动画
     */
    public void hideLoading() {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
        SettingsActivity.logOperation("【加载】隐藏加载动画");
    }

    /**
     * 加载动画是否正在显示
     *
     * @return true=显示中，false=已隐藏
     */
    public boolean isLoadingShowing() {
        return loadingView != null && loadingView.getVisibility() == View.VISIBLE;
    }

    // ====================================================================
    // 资源释放
    // ====================================================================

    /**
     * 释放资源
     *
     * 【调用时机】
     * Activity 销毁时调用，防止内存泄漏。
     */
    public void release() {
        // 移除加载视图
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
    }
}
