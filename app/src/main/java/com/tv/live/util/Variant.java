package com.tv.live.util;

import android.text.TextUtils;

/**
 * 清晰度变体数据类，表示一个可播放的视频流变体
 * 支持普通流和虎牙SDK流两种来源
 */
public class Variant {
    public String url;
    public int bandwidth;
    public int width;
    public int height;
    public String resolutionLabel;
    // 虎牙专用：码率显示名（如"蓝光4M"、"超清2M"），为空则回退 resolutionLabel
    public String huyaBitRateDisplayName;
    // 虎牙专用：线路索引和码率，用于切线路时找对应流
    public int huyaLineIndex = -1;
    public int huyaBitRate = -1;

    public Variant(String url, int bandwidth, int width, int height) {
        this.url = url;
        this.bandwidth = bandwidth;
        this.width = width;
        this.height = height;
        if (height >= 2160) resolutionLabel = "4K (2160p)";
        else if (height >= 1080) resolutionLabel = "1080p";
        else if (height >= 720) resolutionLabel = "720p";
        else if (height > 0) resolutionLabel = height + "p";
        else resolutionLabel = "自适应";
    }

    /** 虎牙 SDK 创建清晰度变体 */
    public static Variant fromHuyaStreamInfo(HuyaSDKParser.HuyaStreamInfo s) {
        Variant v = new Variant(
                s.getPlayUrl(),
                s.bitRate * 1000,     // Kbps → bps
                0, 0
        );
        // URL模式匹配推导分辨率标签（与反编译版一致）
        String url = s.getPlayUrl();
        if (!TextUtils.isEmpty(url)) {
            v.height = inferHeightFromUrl(url, s.bitRate);
            v.resolutionLabel = inferResolutionLabelFromUrl(url, s.bitRate);
        } else {
            v.height = s.bitRate >= 4000 ? 1080 : s.bitRate >= 2000 ? 720 : 360;
            v.resolutionLabel = v.height + "p";
        }
        // 优先用SDK显示名，但用分辨率标签做UI展示
        if (!TextUtils.isEmpty(s.bitRateDisplayName)) {
            v.huyaBitRateDisplayName = s.bitRateDisplayName;
        }
        v.huyaLineIndex = s.lineIndex;
        v.huyaBitRate = s.bitRate;
        return v;
    }

    /** 从URL模式推导分辨率标签（与反编译版setupHuyaVariants一致） */
    private static String inferResolutionLabelFromUrl(String url, int bitRate) {
        if (url.contains("_6000.") || bitRate >= 6000) return "1080p高清";
        if (url.contains("_4000.") || bitRate >= 4000) return "1080p";
        if (url.contains("_2000.") || bitRate >= 2000) return "720p";
        if (url.contains("_1000.") || bitRate >= 1000) return "480p";
        if (url.contains("_500.") || bitRate >= 400) return "360p";
        return "360p";
    }

    /** 从URL模式推导高度 */
    private static int inferHeightFromUrl(String url, int bitRate) {
        if (url.contains("_6000.") || bitRate >= 6000) return 1080;
        if (url.contains("_4000.") || bitRate >= 4000) return 1080;
        if (url.contains("_2000.") || bitRate >= 2000) return 720;
        if (url.contains("_1000.") || bitRate >= 1000) return 480;
        if (url.contains("_500.") || bitRate >= 400) return 360;
        return 360;
    }

    /** 获取用于 UI 列表和存储的标签 */
    public String getDisplayLabel() {
        return resolutionLabel != null ? resolutionLabel :
                (!TextUtils.isEmpty(huyaBitRateDisplayName) ? huyaBitRateDisplayName : "未知");
    }
}
