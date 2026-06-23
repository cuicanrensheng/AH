package com.tv.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * 自动更新闹钟管理器
 *
 * 【职责】
 * 负责自动更新源的闹钟管理，包括：
 * 1. 设置自动更新闹钟（每天凌晨4点）
 * 2. 取消自动更新闹钟
 *
 * 【为什么拆分？】
 * 功能独立，和 SettingsActivity 其他功能没关系。
 * 拆出来后职责清晰，以后要加更多闹钟功能（比如多个闹钟、不同时间）也方便。
 *
 * 【使用方式】
 * AutoUpdateManager autoUpdateManager = new AutoUpdateManager(context);
 * autoUpdateManager.setAutoUpdateAlarm();
 * autoUpdateManager.cancelAutoUpdateAlarm();
 */
public class AutoUpdateManager {

    // ====================== 常量 ======================
    /** 闹钟请求码 */
    private static final int ALARM_REQUEST_CODE = 0;
    /** 自动更新时间：凌晨4点 */
    private static final int UPDATE_HOUR = 4;
    private static final int UPDATE_MINUTE = 0;

    // ====================== 成员变量 ======================
    /** 上下文 */
    private final Context context;

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     * @param context 上下文
     */
    public AutoUpdateManager(Context context) {
        this.context = context;
    }

    // ====================================================================
    // 1. 设置自动更新闹钟
    // ====================================================================
    /**
     * 设置自动更新闹钟
     * 每天凌晨4点执行，自动更新直播源和 EPG
     *
     * 【原理】
     * 使用 AlarmManager 设置一个重复闹钟，
     * 每天凌晨4点发送一个广播，
     * MainActivity 收到广播后刷新直播源和 EPG。
     */
    public void setAutoUpdateAlarm() {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");

            // 根据 API 版本选择合适的 Flag
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE,
                    intent,
                    flags
            );

            // 设置每天凌晨4点
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.set(Calendar.HOUR_OF_DAY, UPDATE_HOUR);
            calendar.set(Calendar.MINUTE, UPDATE_MINUTE);
            calendar.set(Calendar.SECOND, 0);

            // 如果今天的4点已经过了，就设为明天
            if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_MONTH, 1);
            }

            // 设置重复闹钟（每天一次）
            // 使用 setInexactRepeating 而不是 setRepeating，
            // 因为 Android 4.4+ 之后 setRepeating 不准确，
            // setInexactRepeating 更省电，系统会批量处理闹钟。
            alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
            );

            LogManager.logOperation("【设置】已设置自动更新闹钟，每天凌晨" + UPDATE_HOUR + "点执行");
        } catch (Exception e) {
            e.printStackTrace();
            LogManager.logOperation("【设置】设置自动更新闹钟失败：" + e.getMessage());
        }
    }

    // ====================================================================
    // 2. 取消自动更新闹钟
    // ====================================================================
    /**
     * 取消自动更新闹钟
     */
    public void cancelAutoUpdateAlarm() {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");

            // 根据 API 版本选择合适的 Flag
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE,
                    intent,
                    flags
            );
            alarmManager.cancel(pendingIntent);

            LogManager.logOperation("【设置】已取消自动更新闹钟");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
