package com.tv.live.util;

import com.tv.live.SettingsActivity;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局日志工具类
 * 功能：统一日志打印、日志列表缓存、限制最大存储条数、同步写入设置页日志面板
 * 拆分自MainActivity原有静态log()方法与logList集合
 */
public class LogUtils {
    // 全局日志缓存集合，最新日志放在列表头部
    public static final List<String> logList = new ArrayList<>();
    // 日志最大保存条数，超出自动删除末尾旧日志
    private static final int MAX_LOG_COUNT = 100;

    /**
     * 统一日志输出方法
     * @param msg 需要打印的日志文本
     */
    public static void log(String msg) {
        // 新日志插入表头
        logList.add(0, msg);
        // 超出上限自动移除最后一条日志
        while (logList.size() > MAX_LOG_COUNT) {
            logList.remove(logList.size() - 1);
        }
        // 同步回调到设置页面日志展示
        SettingsActivity.log(msg);
    }
}
