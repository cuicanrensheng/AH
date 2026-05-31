package com.tv.live;
public class TimeUtil {
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

    private static long parse(String time) throws Exception {
        int y = Integer.parseInt(time.substring(0, 4));
        int M = Integer.parseInt(time.substring(4, 6)) - 1;
        int d = Integer.parseInt(time.substring(6, 8));
        int h = Integer.parseInt(time.substring(8, 10));
        int m = Integer.parseInt(time.substring(10, 12));
        return new java.util.Calendar.Builder()
                .setDate(y, M, d)
                .setTimeOfDay(h, m, 0)
                .build()
                .getTimeInMillis();
    }
}
