package com.tv.live.util;

import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class LogCollector {
    private static volatile LogCollector sInstance;
    private final List<String> logs;
    private final SimpleDateFormat sdf;

    // 🟢【新增】分割线特殊标记符
    public static final String DIVIDER_TOKEN = "###DIVIDER###";

    private LogCollector() {
        logs = new LinkedList<>();
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
        logs.add(0, line);
        if (logs.size() > 200) {
            logs.remove(logs.size() - 1);
        }
    }

    // 🟢【新增】添加自动延长分割线
    public void addDivider() {
        String time = sdf.format(new Date());
        // 先存入时间 + 特殊标记，等到显示时再替换成等号
        logs.add(0, time + " " + DIVIDER_TOKEN);
        if (logs.size() > 200) {
            logs.remove(logs.size() - 1);
        }
    }

    public String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        for (String log : logs) {
            sb.append(log).append("\n");
        }
        return sb.toString();
    }

    public void clear() {
        logs.clear();
    }
}
