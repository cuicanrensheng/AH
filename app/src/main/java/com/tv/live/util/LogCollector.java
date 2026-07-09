package com.tv.live.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 用于收集和查看日志的简易收集器
 */
public class LogCollector {
    private static final int MAX_LOG_COUNT = 300; // 最多保存300条最新日志
    private final List<String> logList = new ArrayList<>();
    private static volatile LogCollector instance;

    private LogCollector() {}

    public static LogCollector getInstance() {
        if (instance == null) {
            synchronized (LogCollector.class) {
                if (instance == null) {
                    instance = new LogCollector();
                }
            }
        }
        return instance;
    }

    // 添加一条日志
    public void addLog(String tag, String msg) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String logEntry = "[" + time + "] " + tag + " -> " + msg;
        synchronized (logList) {
            logList.add(logEntry);
            // 控制内存溢出，超过300条时移除最早的一条
            if (logList.size() > MAX_LOG_COUNT) {
                logList.remove(0);
            }
        }
    }

    // 获取所有日志（用于弹窗显示）
    public String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        synchronized (logList) {
            for (String log : logList) {
                sb.append(log).append("\n");
            }
        }
        return sb.toString();
    }

    // 清空日志
    public void clearLogs() {
        synchronized (logList) {
            logList.clear();
        }
    }
}
