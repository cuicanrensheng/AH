package com.tv.live.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局线程池工具类：
 * - io():      固定 4 线程，用于网络请求 / 缓存 IO / 解析等耗时任务（daemon，不阻止进程退出）
 * - serial():  单线程串行队列，用于需要顺序执行的缓存写入 / 状态变更
 * - main():    切换到主线程执行
 * - ioThread():获取可复用的 IO 线程池实例
 *
 * 统一线程池后避免每次 new Thread 的开销，同时限制并发上限防止线程爆炸。
 */
public class AppExecutors {

    private static final AtomicInteger IO_THREAD_ID = new AtomicInteger(1);
    private static final AtomicInteger SERIAL_THREAD_ID = new AtomicInteger(1);

    private static final ExecutorService IO = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "AppIO-" + IO_THREAD_ID.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService SERIAL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AppSerial-" + SERIAL_THREAD_ID.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private AppExecutors() {}

    /** 固定 4 线程的 IO 池：网络 / 缓存 / 解析 */
    public static ExecutorService io() {
        return IO;
    }

    /** 串行队列：需要按提交顺序执行的任务 */
    public static ExecutorService serial() {
        return SERIAL;
    }

    /** 提交一个后台任务 */
    public static void io(Runnable runnable) {
        IO.execute(runnable);
    }

    /** 提交到主线程执行 */
    public static void main(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            MAIN_HANDLER.post(runnable);
        }
    }
}
