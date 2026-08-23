package com.tv.live;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 崩溃显示页面
 * 应用崩溃时自动弹出，显示详细错误信息
 * 纯代码动态构建，绝不依赖 activity_main.xml，防止因 PlayerView 崩溃导致连崩溃页都进不去
 */
public class CrashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // 🟢【修复崩溃页面"被竖向压缩成细条"】
        // 原因：AndroidManifest 给 CrashActivity 使用的是
        //       Theme.DeviceDefault.Light.Dialog —— 手机上 Dialog 默认宽度会 WRAP_CONTENT，
        //       而我们的 rootLayout 又 setPadding(48) 且中间 ScrollView 用的是 weight=1 0dp，
        //       在竖屏 + Dialog 窄宽组合下就会出现"整个卡片只剩一列文字的竖线"。
        //
        // 修复方式：
        //   1) 强制 Dialog 窗口的宽高按 MATCH_PARENT(带上下边距)，不跟随系统 Dialog 默认 WRAP；
        //   2) 即使手机发生了横竖屏旋转/传感器引起方向变化，也让根容器宽度主动铺满可用区域；
        //   3) 摘要/详情 TextView 均改成 MATCH_PARENT，不再用 WRAP（WRAP 在超长堆栈下会把宽度压缩成每行 1~2 个字符）。
        try {
            Window w = getWindow();
            if (w != null) {
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                // 取屏幕可用宽度的 92% 作为对话框宽度（比全屏更柔和）
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int screenH = getResources().getDisplayMetrics().heightPixels;
                lp.width  = Math.max(screenW * 92 / 100, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                lp.height = Math.max(screenH * 82 / 100, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER;
                w.setAttributes(lp);
            }
        } catch (Throwable ignored) {}

        // ============================================================
        // 纯代码动态创建布局（防御性编程，杜绝 XML 解析崩溃）
        // ============================================================
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFFFFFFFF);
        rootLayout.setPadding(dp(24), dp(24), dp(24), dp(24));
        rootLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        // 根容器也用 MATCH_PARENT，确保宽度被我们上面的 Window.LayoutParams 正确撑满
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // --- 1. 标题 ---
        TextView tvTitle = new TextView(this);
        tvTitle.setText("😢 应用崩溃了");
        tvTitle.setTextSize(22);
        tvTitle.setTextColor(Color.BLACK);
        tvTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dp(16));
        tvTitle.setLayoutParams(titleParams);
        rootLayout.addView(tvTitle);

        // --- 2. 错误摘要 ---
        TextView tvError = new TextView(this);
        tvError.setTextSize(15);
        tvError.setTextColor(0xFFFF4D4F);
        tvError.setGravity(Gravity.CENTER);
        tvError.setMaxLines(4);
        tvError.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.setMargins(0, 0, 0, dp(16));
        tvError.setLayoutParams(errorParams);
        rootLayout.addView(tvError);

        // --- 3. 滚动容器（详细日志） ---
        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1); // 权重为1，自动填满剩余空间
        scrollParams.setMargins(0, 0, 0, dp(16));
        scrollView.setLayoutParams(scrollParams);

        TextView tvDetail = new TextView(this);
        tvDetail.setTextSize(11);
        tvDetail.setTextColor(Color.BLACK);
        tvDetail.setPadding(dp(16), dp(16), dp(16), dp(16));
        tvDetail.setBackgroundColor(0xFFF5F5F5);
        // 🟢【关键】避免出现"一个字符一行"的竖直显示
        tvDetail.setMinEms(40);              // 横向至少能放 40 个字符（约等于 40 列）
        tvDetail.setHorizontallyScrolling(false);
        tvDetail.setSingleLine(false);
        // ScrollView 的 child 需要用 FRAME 的 MATCH_PARENT（因为 ScrollView 自己是 FrameLayout 子项规则）
        scrollView.addView(tvDetail, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        rootLayout.addView(scrollView);

        // --- 4. 按钮容器 ---
        LinearLayout btnLayout = new LinearLayout(this);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnLayoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLayout.setLayoutParams(btnLayoutParams);

        // 重启按钮
        Button btnRestart = new Button(this);
        btnRestart.setText("重启应用");
        btnRestart.setTextColor(Color.WHITE);
        btnRestart.setBackgroundColor(0xFF40A9FF);
        btnRestart.setMinHeight(dp(44));
        LinearLayout.LayoutParams btnRestartParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1);
        btnRestartParams.setMargins(0, 0, dp(8), 0);
        btnRestart.setLayoutParams(btnRestartParams);
        btnLayout.addView(btnRestart);

        // 退出按钮
        Button btnExit = new Button(this);
        btnExit.setText("退出应用");
        btnExit.setTextColor(Color.WHITE);
        btnExit.setBackgroundColor(0xFFFF6B6B);
        btnExit.setMinHeight(dp(44));
        LinearLayout.LayoutParams btnExitParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1);
        btnExitParams.setMargins(dp(8), 0, 0, 0);
        btnExit.setLayoutParams(btnExitParams);
        btnLayout.addView(btnExit);

        rootLayout.addView(btnLayout);
        setContentView(rootLayout);

        // ============================================================
        // 读取并显示崩溃信息
        // ============================================================
        String crashLog = CrashHandler.CRASH_LOG;
        if (TextUtils.isEmpty(crashLog)) {
            crashLog = CrashHandler.getInstance().getLatestCrashLog();
        }

        String errorMsg = "发生了未处理的异常";
        String detailMsg = "无详细信息 (日志文件可能未保存)";

        if (!TextUtils.isEmpty(crashLog)) {
            String[] lines = crashLog.split("\n");
            for (String line : lines) {
                if (line.startsWith("异常信息：")) {
                    errorMsg = line.replace("异常信息：", "");
                    break;
                }
            }
            detailMsg = crashLog; // 完整显示，不再截断
        }

        tvError.setText(errorMsg);
        tvDetail.setText(detailMsg);

        // ============================================================
        // 【核心修复】点击事件绑定
        // ============================================================

        // 1. 重启按钮点击
        btnRestart.setOnClickListener(v -> {
            try {
                AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                Intent intent = new Intent(CrashActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                PendingIntent pendingIntent = PendingIntent.getActivity(
                        CrashActivity.this,
                        0,
                        intent,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
                );
                if (alarmManager != null) {
                    alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent);
                }
            } catch (Exception ignored) {}
            // 无论是正常重启还是失败，当前页面都要关闭
            finish();
        });

        // 2. 退出按钮点击 (彻底杀死进程)
        btnExit.setOnClickListener(v -> {
            // 销毁当前的 Activity
            finish();
            // 🔥 强制杀死当前应用进程
            Process.killProcess(Process.myPid());
            System.exit(0);
        });
    }

    // ============================================================
    // 按返回键也彻底退出
    // ============================================================
    @Override
    public void onBackPressed() {
        finish();
        Process.killProcess(Process.myPid());
        System.exit(0);
    }

    // ============================================================
    // 工具：dp 转 px（动态布局时使用，保证在不同密度设备上尺寸一致）
    // ============================================================
    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
