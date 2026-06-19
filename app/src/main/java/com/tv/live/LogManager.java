package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 日志管理器
 *
 * 【职责】
 * 负责所有日志相关的逻辑，包括：
 * 1. 解析&播放日志存储和记录
 * 2. 操作日志存储和记录
 * 3. 显示日志对话框
 * 4. 清空日志
 *
 * 【为什么拆分？】
 * 日志是全局通用的功能，MainActivity、TVPlayerManager、
 * WebServerManager 等很多地方都需要记录日志。
 * 拆出来后统一管理，不用通过 SettingsActivity 访问。
 *
 * 【使用方式】
 * 记录日志：
 *   LogManager.log("播放开始");
 *   LogManager.logOperation("用户切台");
 *
 * 显示日志对话框：
 *   LogManager.showLogDialog(context);
 *   LogManager.showOperationLogDialog(context);
 */
public class LogManager {

    // ====================== 全局日志系统 ======================
    /**
     * 解析&播放日志
     * 用 volatile 保证多线程可见性
     */
    private static volatile StringBuilder PLAY_LOG = new StringBuilder();

    /**
     * 操作日志
     * 记录用户的所有操作 + 网页后台日志
     */
    private static volatile StringBuilder OPERATION_LOG = new StringBuilder();

    // ====================== 日志大小限制 ======================
    /** 日志最大长度 */
    private static final int MAX_LOG_LENGTH = 20000;
    /** 裁剪后保留长度 */
    private static final int KEEP_LOG_LENGTH = 15000;

    // ====================================================================
    // 1. 记录解析&播放日志
    // ====================================================================
    /**
     * 记录解析&播放日志
     * @param msg 日志内容
     */
    public static void log(String msg) {
        if (PLAY_LOG == null) {
            PLAY_LOG = new StringBuilder();
        }
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        PLAY_LOG.append("[").append(time).append("] ").append(msg).append("\n");
        // 限制日志大小，防止内存溢出
        if (PLAY_LOG.length() > MAX_LOG_LENGTH) {
            PLAY_LOG.delete(0, PLAY_LOG.length() - KEEP_LOG_LENGTH);
        }
    }

    // ====================================================================
    // 2. 记录操作日志
    // ====================================================================
    /**
     * 记录操作日志
     * @param msg 操作内容
     */
    public static void logOperation(String msg) {
        if (OPERATION_LOG == null) {
            OPERATION_LOG = new StringBuilder();
        }
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        OPERATION_LOG.append("[").append(time).append("] ").append(msg).append("\n");
        if (OPERATION_LOG.length() > MAX_LOG_LENGTH) {
            OPERATION_LOG.delete(0, OPERATION_LOG.length() - KEEP_LOG_LENGTH);
        }
    }

    // ====================================================================
    // 3. 显示解析&播放日志对话框
    // ====================================================================
    /**
     * 显示解析&播放日志对话框
     * 最新的日志显示在最上面（倒序）
     *
     * @param context 上下文
     */
    public static void showLogDialog(Context context) {
        ScrollView scrollView = new ScrollView(context);
        TextView tv = new TextView(context);

        if (PLAY_LOG == null || PLAY_LOG.length() == 0) {
            tv.setText("暂无日志内容，请先播放一个频道再查看。");
        } else {
            // 倒序显示：最新的在最上面
            String originalLog = PLAY_LOG.toString();
            String[] lines = originalLog.split("\n");
            StringBuilder reversedLog = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) {
                    reversedLog.append(lines[i]).append("\n");
                }
            }
            tv.setText(reversedLog.toString());
        }

        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(android.graphics.Color.BLACK);
        scrollView.addView(tv);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("📄 解析 & 播放日志");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            clearPlayLog();
            Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    // ====================================================================
    // 4. 显示操作日志对话框
    // ====================================================================
    /**
     * 显示操作日志对话框
     * 最新的日志显示在最上面（倒序）
     *
     * @param context 上下文
     */
    public static void showOperationLogDialog(Context context) {
        ScrollView scrollView = new ScrollView(context);
        TextView tv = new TextView(context);

        if (OPERATION_LOG == null || OPERATION_LOG.length() == 0) {
            tv.setText("暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作，\n以及网页后台的启动、请求、响应等详细信息。");
        } else {
            // 倒序显示：最新的在最上面
            String originalLog = OPERATION_LOG.toString();
            String[] lines = originalLog.split("\n");
            StringBuilder reversedLog = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) {
                    reversedLog.append(lines[i]).append("\n");
                }
            }
            tv.setText(reversedLog.toString());
        }

        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(android.graphics.Color.BLACK);
        scrollView.addView(tv);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("📌 操作日志");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            clearOperationLog();
            Toast.makeText(context, "操作日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    // ====================================================================
    // 5. 清空日志
    // ====================================================================
    /**
     * 清空解析&播放日志
     */
    public static void clearPlayLog() {
        if (PLAY_LOG != null) {
            PLAY_LOG.setLength(0);
        }
    }

    /**
     * 清空操作日志
     */
    public static void clearOperationLog() {
        if (OPERATION_LOG != null) {
            OPERATION_LOG.setLength(0);
        }
    }

    // ====================================================================
    // 6. 获取日志内容（供外部使用）
    // ====================================================================
    /**
     * 获取解析&播放日志内容
     * @return 日志字符串
     */
    public static String getPlayLog() {
        return PLAY_LOG == null ? "" : PLAY_LOG.toString();
    }

    /**
     * 获取操作日志内容
     * @return 日志字符串
     */
    public static String getOperationLog() {
        return OPERATION_LOG == null ? "" : OPERATION_LOG.toString();
    }

}
