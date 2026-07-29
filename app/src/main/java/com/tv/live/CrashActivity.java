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

        // ============================================================
        // 纯代码动态创建布局（防御性编程，杜绝 XML 解析崩溃）
        // ============================================================
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFFFFFFFF);
        rootLayout.setPadding(48, 48, 48, 48);
        rootLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        // --- 1. 标题 ---
        TextView tvTitle = new TextView(this);
        tvTitle.setText("😢 应用崩溃了");
        tvTitle.setTextSize(22);
        tvTitle.setTextColor(Color.BLACK);
        tvTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, 24);
        tvTitle.setLayoutParams(titleParams);
        rootLayout.addView(tvTitle);

        // --- 2. 错误摘要 ---
        TextView tvError = new TextView(this);
        tvError.setTextSize(15);
        tvError.setTextColor(0xFFFF4D4F);
        tvError.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.setMargins(0, 0, 0, 24);
        tvError.setLayoutParams(errorParams);
        rootLayout.addView(tvError);

        // --- 3. 滚动容器（详细日志） ---
        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1); // 权重为1，自动填满剩余空间
        scrollParams.setMargins(0, 0, 0, 24);
        scrollView.setLayoutParams(scrollParams);

        TextView tvDetail = new TextView(this);
        tvDetail.setTextSize(11);
        tvDetail.setTextColor(Color.BLACK);
        tvDetail.setPadding(24, 24, 24, 24);
        tvDetail.setBackgroundColor(0xFFF5F5F5);
        scrollView.addView(tvDetail);
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
        LinearLayout.LayoutParams btnRestartParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1);
        btnRestartParams.setMargins(0, 0, 12, 0);
        btnRestart.setLayoutParams(btnRestartParams);
        btnLayout.addView(btnRestart);

        // 退出按钮
        Button btnExit = new Button(this);
        btnExit.setText("退出应用");
        btnExit.setTextColor(Color.WHITE);
        btnExit.setBackgroundColor(0xFFFF6B6B);
        LinearLayout.LayoutParams btnExitParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1);
        btnExitParams.setMargins(12, 0, 0, 0);
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
}
