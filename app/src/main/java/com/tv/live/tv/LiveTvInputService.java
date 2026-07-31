package com.tv.live.tv;

import android.content.ContentUris;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;

import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.PlaylistParser;
import com.tv.live.TVPlayerManager;
import com.tv.live.util.CacheManager;

import java.util.ArrayList;
import java.util.List;

public class LiveTvInputService extends TvInputService {

    private static final String TAG = "LiveTvInputService";
    private static final String SP_NAME = "app_settings";
    private static final String KEY_CUSTOM_LIVE_URL = "custom_live_url";

    @Override
    public Session onCreateSession(String inputId) {
        return new TvInputSession(inputId);
    }

    public class TvInputSession extends TvInputService.Session {

        private final String inputId;
        private TVPlayerManager mPlayerManager;
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private List<Channel> mChannelList = new ArrayList<>();

        public TvInputSession(String inputId) {
            super(LiveTvInputService.this);
            this.inputId = inputId;
            mPlayerManager = TVPlayerManager.getInstance(LiveTvInputService.this);
            loadChannelsAsync();
        }

        /**
         * 独立加载频道列表（不依赖 MainActivity）
         */
        private void loadChannelsAsync() {
            new Thread(() -> {
                try {
                    // 1. 尝试从缓存加载
                    CacheManager cacheManager = CacheManager.getInstance(LiveTvInputService.this);
                    String cacheContent = cacheManager.getFileCache("live_source");
                    if (cacheContent != null && !cacheContent.isEmpty()) {
                        List<Channel> parsed = PlaylistParser.parseContent(cacheContent);
                        if (parsed != null && !parsed.isEmpty()) {
                            mChannelList = parsed;
                            Log.i(TAG, "TIF 从缓存加载频道: " + mChannelList.size());
                        }
                    }

                    // 2. 同步频道到系统（如果尚未同步）
                    if (!TvChannelSyncManager.hasSyncedChannels(LiveTvInputService.this, inputId)) {
                        if (!mChannelList.isEmpty()) {
                            int synced = TvChannelSyncManager.syncChannels(
                                    LiveTvInputService.this, inputId, mChannelList);
                            Log.i(TAG, "TIF 自动同步频道到系统: " + synced);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "TIF 加载频道异常", e);
                }
            }).start();
        }

        @Override
        public boolean onSetSurface(Surface surface) {
            if (mPlayerManager != null) {
                mPlayerManager.setSurface(surface);
                Log.d(TAG, "Surface 已绑定到播放器");
            }
            return true;
        }

        @Override
        public boolean onTune(Uri channelUri) {
            long channelId = ContentUris.parseId(channelUri);
            Log.d(TAG, "正在切换到频道 ID: " + channelId);

            // 优先从系统 TvContract 查询频道信息
            String playUrl = null;
            String channelName = null;

            // 1. 尝试从系统频道数据库获取信息
            playUrl = getChannelUrlFromSystem(channelUri);
            channelName = getChannelNameFromSystem(channelUri);

            // 2. 如果系统没有URL，从本地频道列表匹配
            if (playUrl == null || playUrl.isEmpty()) {
                playUrl = findUrlByChannelId(channelId);
            }
            if (channelName == null || channelName.isEmpty()) {
                channelName = findNameByChannelId(channelId);
            }

            if (playUrl != null && !playUrl.isEmpty()) {
                final String finalPlayUrl = playUrl;
                final String finalChannelName = channelName != null ? channelName : "频道" + channelId;
                mMainHandler.post(() -> {
                    if (mPlayerManager != null) {
                        mPlayerManager.playUrl(finalPlayUrl, finalChannelName);
                    }
                });
                notifyVideoAvailable();
                return true;
            } else {
                Log.w(TAG, "未找到该频道，播放失败");
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
                return false;
            }
        }

        /**
         * 从系统 TvContract 获取频道的播放 URL（存储在 internal_provider_data）
         */
        private String getChannelUrlFromSystem(Uri channelUri) {
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(channelUri,
                        new String[]{TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA},
                        null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    String data = cursor.getString(0);
                    if (data != null && data.startsWith("http")) {
                        return data;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "读取系统频道URL失败", e);
            } finally {
                if (cursor != null) cursor.close();
            }
            return null;
        }

        private String getChannelNameFromSystem(Uri channelUri) {
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(channelUri,
                        new String[]{TvContract.Channels.COLUMN_DISPLAY_NAME},
                        null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getString(0);
                }
            } catch (Exception e) {
                Log.e(TAG, "读取系统频道名称失败", e);
            } finally {
                if (cursor != null) cursor.close();
            }
            return null;
        }

        /**
         * 根据 channelId 从本地频道列表查找 URL
         */
        private String findUrlByChannelId(long channelId) {
            if (mChannelList == null || mChannelList.isEmpty()) return null;
            int index = (int) (channelId - 1);
            if (index >= 0 && index < mChannelList.size()) {
                return mChannelList.get(index).getPlayUrl();
            }
            // 尝试按 tvg-id 匹配
            for (Channel ch : mChannelList) {
                String chId = ch.getChannelId();
                if (chId != null) {
                    try {
                        if (Long.parseLong(chId) == channelId) {
                            return ch.getPlayUrl();
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            return null;
        }

        private String findNameByChannelId(long channelId) {
            if (mChannelList == null || mChannelList.isEmpty()) return null;
            int index = (int) (channelId - 1);
            if (index >= 0 && index < mChannelList.size()) {
                return mChannelList.get(index).getName();
            }
            for (Channel ch : mChannelList) {
                String chId = ch.getChannelId();
                if (chId != null) {
                    try {
                        if (Long.parseLong(chId) == channelId) {
                            return ch.getName();
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            return null;
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            // 忽略字幕设置
        }

        @Override
        public void onSetStreamVolume(float volume) {
            // 忽略音量设置
        }

        @Override
        public void onRelease() {
            Log.d(TAG, "TIF Session 释放");
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            if (mPlayerManager != null) {
                mPlayerManager.pause();
            }
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            MainActivity activity = MainActivity.getRunningInstance();
            if (activity != null) {
                return activity.dispatchKeyEvent(event);
            }
            return super.onKeyUp(keyCode, event);
        }
    }
}
