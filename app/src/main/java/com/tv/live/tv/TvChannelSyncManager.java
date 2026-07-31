package com.tv.live.tv;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.tv.live.Channel;

import java.util.List;

/**
 * Android TV Input Framework (TIF) 频道同步管理器
 *
 * 【职责】
 * 1. 将应用内的频道列表注册到 Android TV 系统的 TvContract.Channels
 * 2. 同步 EPG 节目单到 TvContract.Programs
 * 3. 提供频道同步状态查询和清除功能
 *
 * 【使用方式】
 * TvChannelSyncManager.sync(context, inputId, channelList);
 */
public class TvChannelSyncManager {

    private static final String TAG = "TvChannelSyncManager";

    /**
     * 将频道列表同步到 Android TV 系统
     *
     * @param context      上下文
     * @param inputId      TvInputService 的 inputId（如 "com.tv.live/.tv.LiveTvInputService/0"）
     * @param channels     频道列表
     * @return 成功同步的频道数量
     */
    public static int syncChannels(Context context, String inputId, List<Channel> channels) {
        if (channels == null || channels.isEmpty()) {
            Log.w(TAG, "频道列表为空，跳过同步");
            return 0;
        }

        int syncedCount = 0;
        try {
            for (int i = 0; i < channels.size(); i++) {
                Channel ch = channels.get(i);
                if (ch == null || ch.getName() == null) continue;

                long channelId = insertOrUpdateChannel(context, inputId, ch, i);
                if (channelId > 0) {
                    syncedCount++;
                }
            }
            Log.i(TAG, "频道同步完成: " + syncedCount + "/" + channels.size());
        } catch (Exception e) {
            Log.e(TAG, "频道同步异常", e);
        }
        return syncedCount;
    }

    /**
     * 同步单个频道的 EPG 节目单到系统
     *
     * @param context     上下文
     * @param channelUri  频道 URI（从 TvContract.Channels.CONTENT_URI 获取）
     * @param programs    EPG 节目列表
     */
    public static void syncEpg(Context context, Uri channelUri, List<Channel.EpgItem> programs) {
        if (channelUri == null || programs == null || programs.isEmpty()) return;

        try {
            // 先清除旧节目单
            context.getContentResolver().delete(
                    TvContract.Programs.CONTENT_URI,
                    TvContract.Programs.COLUMN_CHANNEL_ID + "=?",
                    new String[]{String.valueOf(ContentUris.parseId(channelUri))}
            );

            // 插入新节目单
            for (Channel.EpgItem item : programs) {
                if (item == null) continue;

                ContentValues values = new ContentValues();
                values.put(TvContract.Programs.COLUMN_CHANNEL_ID, ContentUris.parseId(channelUri));
                values.put(TvContract.Programs.COLUMN_TITLE, item.title);
                values.put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, parseTime(item.time));
                values.put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, parseTime(item.time) + 30 * 60 * 1000L);

                context.getContentResolver().insert(TvContract.Programs.CONTENT_URI, values);
            }
        } catch (Exception e) {
            Log.e(TAG, "EPG同步异常", e);
        }
    }

    /**
     * 清除所有已同步的频道
     *
     * @param context 上下文
     * @param inputId TvInputService 的 inputId
     * @return 清除的频道数量
     */
    public static int clearChannels(Context context, String inputId) {
        try {
            int deleted = context.getContentResolver().delete(
                    TvContract.Channels.CONTENT_URI,
                    TvContract.Channels.COLUMN_INPUT_ID + "=?",
                    new String[]{inputId}
            );
            Log.i(TAG, "已清除同步频道: " + deleted);
            return deleted;
        } catch (Exception e) {
            Log.e(TAG, "清除频道异常", e);
            return 0;
        }
    }

    /**
     * 检查该 inputId 是否已有同步的频道
     */
    public static boolean hasSyncedChannels(Context context, String inputId) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    TvContract.Channels.CONTENT_URI,
                    new String[]{TvContract.Channels._ID},
                    TvContract.Channels.COLUMN_INPUT_ID + "=?",
                    new String[]{inputId},
                    null
            );
            return cursor != null && cursor.getCount() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 获取已同步的频道数量
     */
    public static int getSyncedChannelCount(Context context, String inputId) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    TvContract.Channels.CONTENT_URI,
                    new String[]{TvContract.Channels._ID},
                    TvContract.Channels.COLUMN_INPUT_ID + "=?",
                    new String[]{inputId},
                    null
            );
            return cursor != null ? cursor.getCount() : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    // ============================================================
    // 内部方法
    // ============================================================

    private static long insertOrUpdateChannel(Context context, String inputId, Channel ch, int index) {
        try {
            // 检查是否已存在
            long existingId = findChannelId(context, inputId, ch.getName());

            ContentValues values = new ContentValues();
            values.put(TvContract.Channels.COLUMN_INPUT_ID, inputId);
            values.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, String.valueOf(index + 1));
            values.put(TvContract.Channels.COLUMN_DISPLAY_NAME, ch.getName());
            values.put(TvContract.Channels.COLUMN_DESCRIPTION, ch.getGroup());

            String channelId = ch.getChannelId();
            if (channelId != null && !channelId.isEmpty()) {
                try {
                    values.put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, Integer.parseInt(channelId));
                } catch (NumberFormatException ignored) {}
            }

            // 频道类型：其他
            values.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER);

            // 可浏览
            values.put(TvContract.Channels.COLUMN_BROWSABLE, 1);

            // 可搜索
            values.put(TvContract.Channels.COLUMN_SEARCHABLE, 1);

            if (existingId > 0) {
                // 更新
                Uri updateUri = TvContract.buildChannelUri(existingId);
                context.getContentResolver().update(updateUri, values, null, null);
                Log.d(TAG, "更新频道: " + ch.getName() + " id=" + existingId);
                return existingId;
            } else {
                // 插入
                Uri uri = context.getContentResolver().insert(TvContract.Channels.CONTENT_URI, values);
                long newId = uri != null ? ContentUris.parseId(uri) : -1;
                Log.d(TAG, "新增频道: " + ch.getName() + " id=" + newId);
                return newId;
            }
        } catch (Exception e) {
            Log.e(TAG, "插入/更新频道失败: " + ch.getName(), e);
            return -1;
        }
    }

    private static long findChannelId(Context context, String inputId, String displayName) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    TvContract.Channels.CONTENT_URI,
                    new String[]{TvContract.Channels._ID},
                    TvContract.Channels.COLUMN_INPUT_ID + "=? AND " + TvContract.Channels.COLUMN_DISPLAY_NAME + "=?",
                    new String[]{inputId, displayName},
                    null
            );
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "查找频道失败", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }

    /**
     * 简单时间解析：假设 time 格式为 "HH:mm"，返回当天的时间戳
     */
    private static long parseTime(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            if (parts.length >= 2) {
                int hour = Integer.parseInt(parts[0].trim());
                int minute = Integer.parseInt(parts[1].trim());
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
                cal.set(java.util.Calendar.MINUTE, minute);
                cal.set(java.util.Calendar.SECOND, 0);
                cal.set(java.util.Calendar.MILLISECOND, 0);
                return cal.getTimeInMillis();
            }
        } catch (Exception ignored) {}
        return System.currentTimeMillis();
    }
}
