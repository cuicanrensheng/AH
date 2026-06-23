package com.tv.live;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 崩溃显示页面
 * 应用崩溃时自动弹出，显示详细错误信息
 *
 * 【说明】
 * 用代码动态创建布局，不需要 XML 布局文件。
 * 提供「重启应用」和「退出应用」两个按钮。
 *
 * 【为什么用代码动态创建布局？】
 * 因为这是崩溃页面，越简单越可靠越好。
 * 如果用 XML 布局，万一 XML 解析也出问题，崩溃页面就显示不出来了。
 * 用纯代码创建布局，不依赖任何资源文件，更可靠。
 *
 * 【2026-06-23 修改清单】
 * 1. ✅ 增加 60 秒倒计时显示
 * 2. ✅ 倒计时结束后自动退出应用（不自动重启）
 * 3. ✅ 点击按钮后取消倒计时
 * 4. ✅ 优化重启逻辑：用 AlarmManager 实现可靠重启
 * 5. ✅ 增加复制崩溃日志到剪贴板功能
 * 6. ✅ 优化 UI 样式，更美观
 * 7. ✅ 增加 dp2px 工具方法，适配不同分辨率
 */
public class CrashActivity extends Activity {

    // ====================================================================
    // 倒计时相关常量
    // ====================================================================
    /**
     * 倒计时总秒数（60秒）
     *
     * 【为什么是 60 秒？】
     * - 给用户足够的时间看清楚崩溃信息
     * - 也给用户时间复制日志、截图等
     * - 60 秒后自动退出，避免用户忘了操作一直卡在这
     * - 不会太长，用户不用等太久
     */
    private static final int COUNTDOWN_SECONDS = 60;

    /**
     * 倒计时 Handler
     *
     * 【为什么用 Handler？】
     * 因为倒计时需要在主线程更新 UI，
     * Handler + postDelayed 是 Android 中最常用的定时方式。
     *
     * 【为什么用主线程的 Looper？】
     * 确保 Handler 运行在主线程，
     * 这样 post 的 Runnable 也会在主线程执行，可以直接更新 UI。
     */
    private Handler mCountdownHandler;

    /**
     * 倒计时 Runnable
     *
     * 【作用】
     * 每秒执行一次，更新倒计时显示，
     * 然后再 postDelayed 自己，实现循环倒计时。
     */
    private Runnable mCountdownRunnable;

    /**
     * 剩余秒数
     *
     * 【初始值】
     * COUNTDOWN_SECONDS = 60
     *
     * 【变化】
     * 每秒减 1，直到 0 时触发倒计时结束逻辑。
     */
    private int mRemainingSeconds = COUNTDOWN_SECONDS;

    /**
     * 倒计时显示的 TextView
     *
     * 【显示位置】
     * 页面顶部，标题下面。
     *
     * 【显示内容】
     * "60 秒后自动退出应用"
     * "59 秒后自动退出应用"
     * ...
     * "正在退出应用..."
     */
    private TextView mTvCountdown;

    /**
     * 退出按钮（用于倒计时结束后更新文字）
     *
     * 【为什么要保存这个引用？】
     * 因为倒计时过程中需要更新按钮文字，
     * 比如"退出应用 (59)"、"退出应用 (58)"...
     * 所以需要保存按钮的引用，方便更新文字。
     */
    private Button mBtnExit;

    // ====================================================================
    // onCreate 生命周期
    // ====================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 去掉标题栏
        // 【为什么？】
        // 崩溃页面是全屏显示的，不需要标题栏，
        // 而且系统默认的标题栏不好看。
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // 禁止按返回键退出（用户必须看清楚崩溃信息）
        // 【为什么禁止返回键？】
        // 因为崩溃后应用状态已经不正常了，
        // 返回键可能导致更多问题，甚至再次崩溃。
        // 让用户明确选择"重启"或"退出"更安全。
        // 而且有倒计时，60 秒后会自动退出，用户不会卡在这。
        //
        // 【如果想允许返回键退出怎么办？】
        // 把 onBackPressed() 方法里的注释去掉就行。

        // ===== 用代码动态创建布局 =====
        //
        // 【为什么用代码动态创建？】
        // 1. 不依赖 XML 布局文件，更可靠（万一 XML 解析也崩溃了）
        // 2. 不需要额外的资源文件，减少依赖
        // 3. 代码量不大，用代码写也不麻烦

        // 根布局：垂直方向的 LinearLayout
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFFFFFFFF);  // 白色背景
        rootLayout.setPadding(dp2px(24), dp2px(24), dp2px(24), dp2px(24));
        rootLayout.setGravity(Gravity.CENTER_HORIZONTAL);  // 内容水平居中

        // ===== 标题 =====
        TextView tvTitle = new TextView(this);
        tvTitle.setText("😢 应用崩溃了");
        tvTitle.setTextSize(22);
        tvTitle.setTextColor(Color.BLACK);
        tvTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dp2px(12));  // 底部间距 12dp
        tvTitle.setLayoutParams(titleParams);
        rootLayout.addView(tvTitle);

        // ====================================================================
        // ✅ 修改 1：新增倒计时提示
        // ====================================================================
        //
        // 【显示位置】
        // 标题下面，错误摘要上面。
        //
        // 【显示内容】
        // "60 秒后自动退出应用"
        // 每秒更新一次，直到倒计时结束。
        //
        // 【为什么要加这个？】
        // 让用户知道页面会自动退出，不用一直等着。
        // 也给用户一个时间预期，知道还有多久会自动退出。
        mTvCountdown = new TextView(this);
        mTvCountdown.setText("60 秒后自动退出应用");
        mTvCountdown.setTextSize(14);
        mTvCountdown.setTextColor(0xFF888888);  // 灰色文字，不抢视线
        mTvCountdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countdownParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        countdownParams.setMargins(0, 0, 0, dp2px(16));  // 底部间距 16dp
        mTvCountdown.setLayoutParams(countdownParams);
        rootLayout.addView(mTvCountdown);

        // ===== 错误摘要 =====
        //
        // 【显示内容】
        // 异常信息的简要描述（从崩溃日志中提取）。
        // 比如："Attempt to invoke virtual method..."
        //
        // 【为什么单独显示？】
        // 让用户一眼就能看到大概是什么错误，
        // 不用去详细堆栈里找。
        TextView tvError = new TextView(this);
        tvError.setTextSize(15);
        tvError.setTextColor(0xFFFF4D4F);  // 红色文字，醒目
        tvError.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.setMargins(0, 0, 0, dp2px(16));  // 底部间距 16dp
        tvError.setLayoutParams(errorParams);
        rootLayout.addView(tvError);

        // ===== 滚动容器（放详细堆栈） =====
        //
        // 【为什么用 ScrollView？】
        // 因为详细堆栈信息可能很长，一屏显示不下，
        // 用 ScrollView 可以上下滚动查看全部内容。
        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,  // 高度为 0，配合 weight=1 占满剩余空间
                1); // weight = 1，占满剩余空间
        scrollParams.setMargins(0, 0, 0, dp2px(16));  // 底部间距 16dp
        scrollView.setLayoutParams(scrollParams);

        // 详细堆栈信息
        TextView tvDetail = new TextView(this);
        tvDetail.setTextSize(11);  // 小一点，能显示更多内容
        tvDetail.setTextColor(Color.BLACK);
        tvDetail.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));
        tvDetail.setBackgroundColor(0xFFF5F5F5);  // 浅灰色背景，和白色区分
        scrollView.addView(tvDetail);
        rootLayout.addView(scrollView);

        // ====================================================================
        // ✅ 修改 5：新增复制日志按钮
        // ====================================================================
        //
        // 【功能】
        // 点击后把崩溃日志复制到剪贴板。
        //
        // 【为什么要加这个？】
        // 方便用户把崩溃日志复制下来，发给开发者排查问题。
        // 不用用户手动选中文本复制，一键搞定。
        //
        // 【样式】
        // 浅蓝色背景，蓝色文字，和主按钮区分开。
        Button btnCopy = new Button(this);
        btnCopy.setText("📋 复制崩溃日志");
        btnCopy.setTextColor(0xFF40A9FF);  // 蓝色文字
        btnCopy.setBackgroundColor(0xFFE6F7FF);  // 浅蓝色背景
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        copyParams.setMargins(0, 0, 0, dp2px(12));  // 底部间距 12dp
        btnCopy.setLayoutParams(copyParams);
        rootLayout.addView(btnCopy);

        // ===== 按钮容器 =====
        //
        // 【为什么单独用一个 LinearLayout？】
        // 因为两个按钮要横向排列，
        // 外面的根布局是垂直的，所以里面再套一个水平的。
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
        btnRestart.setBackgroundColor(0xFF40A9FF);  // 蓝色背景
        LinearLayout.LayoutParams btnRestartParams = new LinearLayout.LayoutParams(
                0,  // 宽度为 0，配合 weight=1 平分宽度
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1); // weight = 1，和退出按钮平分宽度
        btnRestartParams.setMargins(0, 0, dp2px(6), 0);  // 右边间距 6dp
        btnRestart.setLayoutParams(btnRestartParams);
        btnLayout.addView(btnRestart);

        // 退出按钮
        mBtnExit = new Button(this);
        mBtnExit.setText("退出应用");
        mBtnExit.setTextColor(Color.WHITE);
        mBtnExit.setBackgroundColor(0xFFFF6B6B);  // 红色背景
        LinearLayout.LayoutParams btnExitParams = new LinearLayout.LayoutParams(
                0,  // 宽度为 0，配合 weight=1 平分宽度
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1); // weight = 1，和重启按钮平分宽度
        btnExitParams.setMargins(dp2px(6), 0, 0, 0);  // 左边间距 6dp
        mBtnExit.setLayoutParams(btnExitParams);
        btnLayout.addView(mBtnExit);

        rootLayout.addView(btnLayout);

        // 设置根布局为 Activity 的内容视图
        setContentView(rootLayout);

        // ===== 显示崩溃信息 =====
        //
        // 【从哪里读取？】
        // 从 CrashHandler.CRASH_LOG 静态变量读取。
        //
        // 【为什么用静态变量？】
        // 因为崩溃发生在其他线程，而且进程马上就要被杀了，
        // 用静态变量传递最快最直接。
        // 如果用 Intent 传递，一来有大小限制，二来可能传不过去。
        String crashLog = CrashHandler.CRASH_LOG;

        if (TextUtils.isEmpty(crashLog)) {
            // 没有崩溃日志（理论上不会出现，兜底用）
            tvError.setText("未知错误");
            tvDetail.setText("无详细信息");
        } else {
            // 提取异常信息作为摘要
            //
            // 【怎么提取？】
            // 按行分割，找到"异常信息："开头的那一行，
            // 把后面的内容提取出来作为摘要。
            String[] lines = crashLog.split("\n");
            String errorMsg = "";
            for (String line : lines) {
                if (line.startsWith("异常信息：")) {
                    errorMsg = line.replace("异常信息：", "");
                    break;  // 找到就退出循环
                }
            }

            // 如果没找到异常信息，用默认提示
            if (TextUtils.isEmpty(errorMsg)) {
                errorMsg = "发生了未处理的异常";
            }

            // 显示错误摘要和详细堆栈
            tvError.setText(errorMsg);
            tvDetail.setText(crashLog);
        }

        // ====================================================================
        // ✅ 修改 5：复制日志按钮点击事件
        // ====================================================================
        //
        // 【做了什么？】
        // 1. 把崩溃日志复制到剪贴板
        // 2. Toast 提示"已复制到剪贴板"
        //
        // 【为什么用 final？】
        // 因为 crashLog 是局部变量，
        // 在匿名内部类中访问需要是 final 的（或 effectively final）。
        final String finalCrashLog = crashLog;
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(finalCrashLog);
                Toast.makeText(CrashActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        });

        // 重启按钮点击
        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 取消倒计时（用户已经手动操作了，不用自动退出了）
                cancelCountdown();
                // 重启应用
                restartApp();
            }
        });

        // 退出按钮点击
        mBtnExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 取消倒计时
                cancelCountdown();
                // 退出应用
                exitApp();
            }
        });

        // ====================================================================
        // ✅ 修改 1：启动 60 秒倒计时
        // ====================================================================
        //
        // 【什么时候启动？】
        // onCreate 的最后，所有布局都创建好之后。
        //
        // 【为什么放在最后？】
        // 因为倒计时需要更新 UI（mTvCountdown、mBtnExit），
        // 必须等这些控件都创建好了才能启动。
        startCountdown();
    }

    // ====================================================================
    // ✅ 修改 1：倒计时相关方法
    // ====================================================================

    /**
     * 启动倒计时
     *
     * 【倒计时逻辑】
     * 1. 创建 Handler（主线程）
     * 2. 创建 Runnable，每秒执行一次
     * 3. Runnable 中：剩余秒数减 1 → 更新显示 → 如果还有时间就再 postDelayed 自己
     * 4. 如果倒计时结束，调用 onCountdownFinish()
     *
     * 【为什么用 postDelayed 而不是 Thread.sleep？】
     * 1. postDelayed 不会阻塞主线程，UI 不会卡顿
     * 2. postDelayed 运行在主线程，可以直接更新 UI
     * 3. Thread.sleep 会阻塞线程，如果在主线程 sleep 会导致 ANR
     *
     * 【为什么延迟 1 秒后开始？】
     * 让用户先看清楚页面内容，然后再开始倒计时。
     * 也给页面一个完全显示出来的时间。
     */
    private void startCountdown() {
        // 创建 Handler（绑定主线程 Looper）
        mCountdownHandler = new Handler(Looper.getMainLooper());

        // 创建倒计时 Runnable
        mCountdownRunnable = new Runnable() {
            @Override
            public void run() {
                // 剩余秒数减 1
                mRemainingSeconds--;

                if (mRemainingSeconds > 0) {
                    // 还有时间：更新显示，继续下一秒
                    updateCountdownText();
                    // 延迟 1 秒后再次执行自己（实现循环）
                    mCountdownHandler.postDelayed(this, 1000);
                } else {
                    // 倒计时结束
                    onCountdownFinish();
                }
            }
        };

        // 启动倒计时（延迟 1 秒后开始第一次执行）
        mCountdownHandler.postDelayed(mCountdownRunnable, 1000);
    }

    /**
     * 取消倒计时
     *
     * 【调用时机】
     * 1. 用户点击"重启应用"按钮时
     * 2. 用户点击"退出应用"按钮时
     * 3. Activity 销毁时（onDestroy）
     *
     * 【为什么要取消？】
     * 1. 用户已经手动操作了，不需要自动退出了
     * 2. Activity 销毁后如果还在倒计时，可能会导致内存泄漏
     * 3. 防止倒计时结束后执行退出逻辑，但用户已经点了重启
     *
     * 【做了什么？】
     * 1. 移除 Runnable 回调
     * 2. 把 Handler 和 Runnable 置为 null（帮助 GC）
     */
    private void cancelCountdown() {
        if (mCountdownHandler != null && mCountdownRunnable != null) {
            // 移除所有未执行的 Runnable
            mCountdownHandler.removeCallbacks(mCountdownRunnable);
            // 置为 null，帮助垃圾回收
            mCountdownHandler = null;
            mCountdownRunnable = null;
        }
    }

    /**
     * 更新倒计时显示文字
     *
     * 【更新了什么？】
     * 1. 顶部的提示文字："59 秒后自动退出应用"
     * 2. 退出按钮文字："退出应用 (59)"
     *
     * 【为什么两个地方都要更新？】
     * 1. 顶部提示：让用户一眼看到倒计时
     * 2. 按钮上也显示：用户看按钮的时候也能看到倒计时
     * 双保险，用户不会错过倒计时信息。
     */
    private void updateCountdownText() {
        // 更新顶部提示文字
        if (mTvCountdown != null) {
            mTvCountdown.setText(mRemainingSeconds + " 秒后自动退出应用");
        }
        // 更新退出按钮文字
        if (mBtnExit != null) {
            mBtnExit.setText("退出应用 (" + mRemainingSeconds + ")");
        }
    }

    /**
     * 倒计时结束回调
     *
     * 【做了什么？】
     * 1. 更新显示为"正在退出应用..."
     * 2. 延迟 500ms 后调用 exitApp() 退出
     *
     * 【为什么要延迟 500ms？】
     * 让用户看到"正在退出"的提示，
     * 有一个过渡效果，不会太突兀。
     *
     * 【注意】
     * 倒计时结束后自动退出应用，不自动重启。
     * （用户明确要求：不要自动重启应用）
     */
    private void onCountdownFinish() {
        // 更新显示，告诉用户正在退出
        if (mTvCountdown != null) {
            mTvCountdown.setText("正在退出应用...");
        }
        if (mBtnExit != null) {
            mBtnExit.setText("退出中...");
        }

        // 延迟一小会儿再退出，让用户看到"正在退出"的提示
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                exitApp();
            }
        }, 500);
    }

    // ====================================================================
    // 退出和重启方法
    // ====================================================================

    /**
     * 退出应用
     *
     * 【退出逻辑】
     * 1. finish 当前 Activity
     * 2. 杀死当前进程
     * 3. 调用 System.exit(0)
     *
     * 【为什么要三步都做？】
     * 为了确保应用完全退出，不残留进程。
     * 有些系统只 killProcess 可能不够，加上 System.exit 更保险。
     *
     * 【为什么不用 ActivityManager.killBackgroundProcesses？】
     * 那个方法只能杀后台进程，
     * 当前 Activity 在前台，杀不死。
     */
    private void exitApp() {
        finish();
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    /**
     * 重启应用
     *
     * 【为什么不用 startActivity + killProcess？】
     * 因为 startActivity 是异步的，还没等新 Activity 启动起来，
     * 进程就被杀了，可能导致重启失败。
     *
     * 【正确做法：用 AlarmManager】
     * 1. 创建一个 PendingIntent，指向 MainActivity
     * 2. 用 AlarmManager 设置一个 1 秒后的闹钟
     * 3. 闹钟触发时，系统会自动启动 MainActivity
     * 4. 然后杀死当前进程
     *
     * 【为什么这样更可靠？】
     * 因为 AlarmManager 是系统服务，不受当前进程影响。
     * 即使当前进程被杀了，闹钟还是会触发，
     * 系统会在新的进程中启动 MainActivity。
     *
     * 【为什么延迟 1 秒？】
     * 给当前进程一点时间清理资源，
     * 也避免闹钟立刻触发，和当前进程冲突。
     *
     * 【兜底方案】
     * 如果 AlarmManager 方式失败（比如某些定制系统限制），
     * 就降级为原来的 startActivity + killProcess 方式。
     */
    private void restartApp() {
        try {
            // 创建重启 Intent
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // 创建 PendingIntent
            //
            // 【为什么用 PendingIntent？】
            // 因为 AlarmManager 需要一个 PendingIntent，
            // 闹钟触发时系统会用这个 PendingIntent 启动 Activity。
            //
            // 【FLAG_UPDATE_CURRENT 是什么意思？】
            // 如果已存在相同的 PendingIntent，就更新它的 extra 数据。
            // 这里我们没有 extra，所以影响不大，但加上是好习惯。
            //
            // 【为什么 Android 12+ 要加 FLAG_IMMUTABLE？】
            // Android 12 (API 31) 要求指定 PendingIntent 的可变性，
            // 否则会报错。FLAG_IMMUTABLE 表示不可变，更安全。
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, flags);

            // 用 AlarmManager 设置 1 秒后重启
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.set(
                        AlarmManager.RTC,  // RTC 时间（墙上时间）
                        System.currentTimeMillis() + 1000,  // 1 秒后
                        pendingIntent
                );
            }

            // 退出当前应用
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);

        } catch (Exception e) {
            // 如果 AlarmManager 方式失败，降级为原来的方式
            //
            // 【为什么要有降级方案？】
            // 因为某些定制系统可能限制了 AlarmManager，
            // 或者某些特殊情况下 AlarmManager 不可用。
            // 有降级方案至少能保证能重启，虽然可靠性差一点。
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }
    }

    // ====================================================================
    // ✅ 修改 5：复制到剪贴板
    // ====================================================================

    /**
     * 复制文本到剪贴板
     *
     * 【用途】
     * 用户可以把崩溃日志复制下来，发给开发者排查问题。
     *
     * 【为什么用 try-catch 包起来？】
     * 因为剪贴板服务在某些极端情况下可能不可用，
     * 用 try-catch 兜底，不会因为复制失败导致崩溃页面也崩溃。
     *
     * 【ClipData.newPlainText 是什么？】
     * 创建一个纯文本类型的 ClipData 对象。
     * 剪贴板可以放多种类型的数据（文本、图片、URI 等），
     * 我们这里只需要放纯文本。
     *
     * @param text 要复制的文本
     */
    private void copyToClipboard(String text) {
        try {
            // 获取剪贴板服务
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                // 创建纯文本 ClipData
                // 第一个参数是标签（label），用于标识剪贴板内容
                // 第二个参数是实际的文本内容
                ClipData clip = ClipData.newPlainText("崩溃日志", text);
                // 设置到剪贴板
                clipboard.setPrimaryClip(clip);
            }
        } catch (Exception e) {
            // 复制失败就静默失败，不影响用户
            // 反正崩溃页面已经显示了，用户也可以手动复制
        }
    }

    // ====================================================================
    // 工具方法
    // ====================================================================

    /**
     * dp 转 px
     *
     * 【为什么需要这个方法？】
     * 因为代码中设置的 padding、margin、文字大小等都是像素值（px），
     * 但我们设计时用的是 dp（设备无关像素），
     * 所以需要转换一下，保证在不同分辨率的设备上显示效果一致。
     *
     * 【公式】
     * px = dp * density + 0.5f
     *
     * 【为什么加 0.5f？】
     * 为了四舍五入。
     * 因为强转 int 是直接截断小数部分，
     * 加 0.5f 后再截断，就相当于四舍五入了。
     * 例如：1.3 + 0.5 = 1.8 → 截断为 1（正确，1.3 四舍五入是 1）
     *       1.6 + 0.5 = 2.1 → 截断为 2（正确，1.6 四舍五入是 2）
     *
     * @param dp dp 值
     * @return px 值
     */
    private int dp2px(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    // ====================================================================
    // 返回键处理
    // ====================================================================

    /**
     * 返回键按下回调
     *
     * 【当前行为】
     * 不做任何事，禁止按返回键退出。
     *
     * 【为什么禁止返回键？】
     * 因为崩溃后应用状态已经不正常了，
     * 返回键可能导致更多问题，甚至再次崩溃。
     * 让用户明确选择"重启"或"退出"更安全。
     * 而且有倒计时，60 秒后会自动退出，用户不会卡在这。
     *
     * 【如果想允许返回键退出怎么办？】
     * 把下面的注释去掉就行：
     * super.onBackPressed();
     * cancelCountdown();
     * exitApp();
     */
    @Override
    public void onBackPressed() {
        // 按返回键不退出，必须等倒计时结束或手动点击按钮
        //
        // 【为什么不调用 super.onBackPressed()？】
        // 因为 super.onBackPressed() 会 finish Activity，
        // 我们不希望用户按返回键就退出，所以不调用。
        //
        // 如果你想允许返回键退出，把下面几行的注释去掉：
        //
        // super.onBackPressed();
        // cancelCountdown();
        // exitApp();
    }

    // ====================================================================
    // onDestroy 生命周期
    // ====================================================================

    /**
     * Activity 销毁回调
     *
     * 【做了什么？】
     * 取消倒计时，防止内存泄漏。
     *
     * 【为什么要取消？】
     * 如果 Activity 销毁了但 Handler 还在 postDelayed，
     * Runnable 中持有 Activity 的引用，会导致内存泄漏。
     * 虽然这个页面是崩溃页面，进程马上就要被杀了，
     * 但加上是个好习惯，规范的代码应该这样写。
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 页面销毁时取消倒计时，防止内存泄漏
        cancelCountdown();
    }
}
