package com.tv.live.util;

import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogCollector {
    private static volatile LogCollector sInstance;
    private final List<String> logs;
    private final SimpleDateFormat sdf;

    // 🟢【新增】分割线特殊标记符
    public static final String DIVIDER_TOKEN = "###DIVIDER###";

    private LogCollector() {
        // 🔴【崩溃修复】LinkedList 非线程安全，多线程 add/遍历会破坏内部 Node.prev 指针导致 NPE
        // 改用 ArrayList + 所有写操作加 synchronized 块保护
        logs = new ArrayList<>();
        sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    }

    public static LogCollector getInstance() {
        if (sInstance == null) {
            synchronized (LogCollector.class) {
                if (sInstance == null) {
                    sInstance = new LogCollector();
                }
            }
        }
        return sInstance;
    }

    public void addLog(String tag, String msg) {
        if (!TextUtils.isEmpty(tag) && !"播放".equals(tag)) {
            return;
        }
        String time = sdf.format(new Date());
        String line;
        if (!TextUtils.isEmpty(tag)) {
            line = time + " 【" + tag + "】 " + msg;
        } else {
            line = time + " " + msg;
        }
        synchronized (logs) {
            logs.add(0, line);
            if (logs.size() > 200) {
                logs.remove(logs.size() - 1);
            }
        }
    }

    // 🟢【新增】添加自动延长分割线
    public void addDivider() {
        String time = sdf.format(new Date());
        // 先存入时间 + 特殊标记，等到显示时再替换成等号
        synchronized (logs) {
            logs.add(0, time + " " + DIVIDER_TOKEN);
            if (logs.size() > 200) {
                logs.remove(logs.size() - 1);
            }
        }
    }

    public String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        synchronized (logs) {
            // 🔴【修复】遍历时也加锁，防止与 addLog 并发修改导致 ConcurrentModificationException
            for (String log : logs) {
                sb.append(log).append("\n");
            }
        }
        return sb.toString();
    }

    public void clear() {
        synchronized (logs) {
            logs.clear();
        }
    }
}
