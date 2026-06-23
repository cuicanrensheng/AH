package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.util.Log;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 全局崩溃捕获器
 * 捕获应用未处理的异常，保存日志并弹出错误页面
 *
 * 【使用方法】
 * 在 Application 的 onCreate 中调用：
 * CrashHandler.getInstance().init(this);
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashHandler";
    private static CrashHandler instance;
    private Context context;
    private Thread.UncaughtExceptionHandler defaultHandler;

    // 崩溃日志（静态变量，供 CrashActivity 和 SettingsActivity 读取）
    public static volatile String CRASH_LOG = "";

    private CrashHandler() {}

    public static CrashHandler getInstance() {
        if (instance == null) {
            instance = new CrashHandler();
        }
        return instance;
    }

    /**
     * 初始化崩溃捕获器
     * @param ctx 上下文
     */
    public void init(Context ctx) {
        context = ctx.getApplicationContext();
        // 保存系统默认的异常处理器
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        // 设置为默认异常处理器
        Thread.setDefaultUncaughtExceptionHandler(this);
        Log.d(TAG, "全局崩溃捕获器已初始化");
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            // 1. 保存崩溃日志
            saveCrashLog(thread, ex);

            // 2. 启动崩溃页面（用新的任务栈启动）
            Intent intent = new Intent(context, CrashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);

            // 3. 等待页面启动后，杀死崩溃的进程
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}

            Process.killProcess(Process.myPid());
            System.exit(1);

        } catch (Exception e) {
            Log.e(TAG, "崩溃处理失败", e);
            // 如果自定义处理失败，交给系统默认处理
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
        }
    }

    /**
     * 保存崩溃日志
     */
    private void saveCrashLog(Thread thread, Throwable ex) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("================ 崩溃日志 ================\n");
            sb.append("时间：").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", new java.util.Date())).append("\n");
            sb.append("线程：").append(thread.getName()).append("\n");
            sb.append("异常类型：").append(ex.getClass().getName()).append("\n");
            sb.append("异常信息：").append(ex.getMessage()).append("\n");
            sb.append("\n========== 堆栈信息 ==========\n");

            // 获取完整堆栈信息
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            pw.close();
            sb.append(sw.toString());

            sb.append("\n========================================\n");

            CRASH_LOG = sb.toString();
            Log.e(TAG, CRASH_LOG);

            // 同步到 SettingsActivity 的日志系统
            try {
                SettingsActivity.log("【崩溃】" + ex.getClass().getName() + ": " + ex.getMessage());
            } catch (Exception ignored) {}

        } catch (Exception e) {
            Log.e(TAG, "保存崩溃日志失败", e);
        }
    }
}
