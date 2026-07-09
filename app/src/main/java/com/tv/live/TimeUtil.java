package com.tv.live;

import java.util.Calendar;

public class TimeUtil {
    // 🟢 复用同一个 Calendar 实例，杜绝每次调用都创建新对象的开销
    private static final Calendar CAL = Calendar.getInstance();

    public static String fmt(String time) {
        if (time == null || time.length() < 14) return "";
        return time.substring(8, 10) + ":" + time.substring(10, 12);
    }

    public static int progress(String start, String stop) {
        try {
            long s = parse(start);
            long e = parse(stop);
            long n = System.currentTimeMillis();
            if (s >= e) return 0;
            return (int) ((n - s) * 100 / (e - s));
        } catch (Exception e) {
            return 0;
        }
    }

    public static int remain(String stop) {
        try {
            long e = parse(stop);
            long n = System.currentTimeMillis();
            return (int) ((e - n) / 60000);
        } catch (Exception e) {
            return 0;
        }
    }

    // 🟢 核心优化：使用 synchronized 锁 + 复用 CAL 对象，完全消灭对象分配
    private static synchronized long parse(String time) throws Exception {
        int y = Integer.parseInt(time.substring(0, 4));
        int M = Integer.parseInt(time.substring(4, 6)) - 1;
        int d = Integer.parseInt(time.substring(6, 8));
        int h = Integer.parseInt(time.substring(8, 10));
        int m = Integer.parseInt(time.substring(10, 12));
        
        CAL.set(y, M, d, h, m, 0);
        CAL.set(Calendar.MILLISECOND, 0);
        return CAL.getTimeInMillis();
    }
}
