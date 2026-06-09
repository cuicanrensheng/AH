package com.tv.live;

import android.content.Context;
import android.os.Process;
import java.io.PrintWriter;
import java.io.StringWriter;

public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static CrashHandler instance;
    private Context context;

    public static CrashHandler getInstance() {
        if (instance == null) {
            instance = new CrashHandler();
        }
        return instance;
    }

    public void init(Context context) {
        this.context = context.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        // 收集崩溃堆栈信息
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        String crashInfo = sw.toString();

        // 写入MainActivity静态日志（最新在前）
        MainActivity.log("========================================");
        MainActivity.log("【崩溃】应用发生崩溃");
        MainActivity.log("【崩溃】线程：" + thread.getName());
        MainActivity.log("【崩溃】时间：" + System.currentTimeMillis());
        MainActivity.log("【崩溃】堆栈：" + crashInfo);
        MainActivity.log("========================================");

        // 延迟退出，确保日志写入完成
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 退出应用
        Process.killProcess(Process.myPid());
        System.exit(1);
    }
}
