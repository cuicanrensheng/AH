package com.tv.live;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
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
 *
 * 【说明】
 * 用代码动态创建布局，不需要 XML 布局文件
 * 提供「重启应用」和「退出应用」两个按钮
 */
public class CrashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // ===== 用代码动态创建布局 =====
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFFFFFFFF);
        rootLayout.setPadding(48, 48, 48, 48);
        rootLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        // 标题
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

        // 错误摘要
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

        // 滚动容器（放详细堆栈）
        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1);
        scrollParams.setMargins(0, 0, 0, 24);
        scrollView.setLayoutParams(scrollParams);

        // 详细堆栈信息
        TextView tvDetail = new TextView(this);
        tvDetail.setTextSize(11);
        tvDetail.setTextColor(Color.BLACK);
        tvDetail.setPadding(24, 24, 24, 24);
        tvDetail.setBackgroundColor(0xFFF5F5F5);
        scrollView.addView(tvDetail);
        rootLayout.addView(scrollView);

        // 按钮容器
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

        // ===== 显示崩溃信息 =====
        String crashLog = CrashHandler.CRASH_LOG;
        if (TextUtils.isEmpty(crashLog)) {
            tvError.setText("未知错误");
            tvDetail.setText("无详细信息");
        } else {
            // 提取异常信息作为摘要
            String[] lines = crashLog.split("\n");
            String errorMsg = "";
            for (String line : lines) {
                if (line.startsWith("异常信息：")) {
                    errorMsg = line.replace("异常信息：", "");
                    break;
                }
            }
            if (TextUtils.isEmpty(errorMsg)) {
                errorMsg = "发生了未处理的异常";
            }
            tvError.setText(errorMsg);
            tvDetail.setText(crashLog);
        }

        // 重启按钮点击
        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(CrashActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }
        });

        // 退出按钮点击
        btnExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }
        });
    }

    @Override
    public void onBackPressed() {
        // 按返回键也退出应用
        finish();
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }
}
