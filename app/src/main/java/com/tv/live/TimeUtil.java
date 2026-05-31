public class TimeUtil {
    public static String formatEpgTime(String time) {
        if (time == null || time.length() < 14) return "00:00";
        return time.substring(8, 10) + ":" + time.substring(10, 12);
    }

    public static int getProgramProgress(String start, String stop) {
        try {
            long s = parseTime(start);
            long e = parseTime(stop);
            long now = System.currentTimeMillis();
            if (s >= e) return 0;
            return (int) ((now - s) * 100 / (e - s));
        } catch (Exception e) {
            return 0;
        }
    }

    public static int getRemainMinutes(String stop) {
        try {
            long e = parseTime(stop);
            long now = System.currentTimeMillis();
            return (int) ((e - now) / 60000);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseTime(String time) throws Exception {
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
