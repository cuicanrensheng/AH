package com.tv.live.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 🖥️ 设备能力检测（电视/手机/平板通用）
 *
 * <p>设计目标：让上游组件（虎牙 SDK 解析、播放器初始化）能够根据设备能力
 * 自动选择合适的策略——例如：
 * <ul>
 *   <li>老电视芯片（MT9255 等）硬解 1080p 不稳定 → 强制软解 + 优先 720p 码流</li>
 *   <li>现代手机/电视硬解能力足够 → 使用硬解 + 优先 1080p 最高码率</li>
 * </ul>
 *
 * <p>关键 API：
 * <ul>
 *   <li>{@link #isTv()} - 是否是电视设备（Android TV、HDMI 输出、宽屏遥控器）</li>
 *   <li>{@link #isOldTvChipset()} - 是否是已知硬解能力不足的老电视芯片</li>
 *   <li>{@link #shouldForceSoftwareCodec()} - 是否应该强制使用软解</li>
 *   <li>{@link #preferredTargetHeight()} - 推荐的最大视频高度（720/1080/2160）</li>
 * </ul>
 *
 * <p>检测时机：在 {@link HuyaSDKParser#parseFull} 的 onSuccess 回调中调用，
 *              自动选择最适合当前设备的码率/线路。
 *
 * @since 2026-08-11
 */
public final class DeviceCapabilities {

    private static final String TAG = "DeviceCapabilities";

    /** 是否已检测（懒加载，单次计算） */
    private static volatile boolean sDetected = false;

    /** 是否是电视 */
    private static volatile boolean sIsTv = false;

    /** 是否是老电视芯片（硬解 1080p 不可靠） */
    private static volatile boolean sIsOldTvChipset = false;

    /** 推荐的最大视频高度 */
    private static volatile int sPreferredTargetHeight = 1080;

    private DeviceCapabilities() {}

    /**
     * 确保检测已执行（懒加载）。
     * 通常在 Application onCreate 或第一次使用前调用。
     */
    public static synchronized void ensureDetected(Context appContext) {
        if (sDetected) return;
        detect(appContext);
        sDetected = true;
    }

    private static void detect(Context appContext) {
        sIsTv = detectTv(appContext);
        sIsOldTvChipset = detectOldTvChipset();
        sPreferredTargetHeight = computePreferredTargetHeight();

        Log.i(TAG, "设备能力检测结果：");
        Log.i(TAG, "  Hardware = " + Build.HARDWARE);
        Log.i(TAG, "  Model = " + Build.MODEL);
        Log.i(TAG, "  Manufacturer = " + Build.MANUFACTURER);
        Log.i(TAG, "  isTv = " + sIsTv);
        Log.i(TAG, "  isOldTvChipset = " + sIsOldTvChipset);
        Log.i(TAG, "  preferredTargetHeight = " + sPreferredTargetHeight);
    }

    /**
     * 检测是否是电视设备。综合以下特征：
     *  - Android TV UI mode（Configuration.uiMode == UI_MODE_TYPE_TELEVISION）
     *  - Coocaa/创维/海信/TCL/长虹/索尼/三星等品牌识别
     *  - 屏幕宽度 ≥ 720 且无触控
     *  - 型号包含 TV/P31/P30/A1/A2 等常见电视命名
     */
    private static boolean detectTv(Context appContext) {
        // 1. Android 官方 TV 标识
        try {
            android.content.res.Configuration cfg = appContext.getResources().getConfiguration();
            if (cfg.uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
        } catch (Throwable ignored) {}

        // 2. 品牌识别（覆盖国内 OEM ROM 改 Android 标识的情况）
        String manufacturer = safeLower(Build.MANUFACTURER);
        String brand = safeLower(Build.BRAND);
        String model = safeLower(Build.MODEL);

        if (containsAny(manufacturer, "coocaa", "skyworth", "hisense", "tcl", "changhong", "haier", "konka", "tcl")
                || containsAny(brand, "coocaa", "skyworth", "hisense", "tcl", "changhong", "haier", "konka")) {
            return true;
        }

        // 3. 型号包含 TV
        if (containsAny(model, "tv", "p31", "p30", "a1", "a2", "a3")) {
            // 排除手机型号混淆（极少见）
            if (!containsAny(model, "iphone")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检测是否是老电视芯片（硬解 1080p 不可靠）。
     *
     * <p>已知问题芯片：
     * <ul>
     *   <li>MT9255 (MStar/MediaTek 创维 Coocaa 3T223_P31)：硬解 1080p NoSupport，会卡顿循环</li>
     *   <li>MStar 全系列老芯片：硬解能力可疑</li>
     *   <li>aml/amlogic 早期 SoC：硬解 H.265 较稳定但 H.264 1080p 偶有卡顿</li>
     * </ul>
     */
    private static boolean detectOldTvChipset() {
        String hardware = safeLower(Build.HARDWARE);
        String model = safeLower(Build.MODEL);
        String board = safeLower(Build.BOARD);

        // MT9255 (MStar/MediaTek 创维 Coocaa 电视)
        if (hardware.contains("mt9255") || board.contains("mt9255")) {
            return true;
        }
        // MStar 全系列
        if (hardware.contains("mstar") || board.contains("mstar")) {
            return true;
        }
        // 创维 Coocaa 特定型号（已知硬解不可靠）
        if (containsAny(model, "p31", "3t223", "5t220", "8m92")) {
            return true;
        }
        return false;
    }

    /**
     * 计算推荐的最大视频高度。
     *  - 老电视芯片 → 720p（避免硬解 1080p 卡顿）
     *  - 普通电视 → 1080p
     *  - 手机 → 1080p（大多数手机硬解 1080p 没问题）
     */
    private static int computePreferredTargetHeight() {
        if (sIsOldTvChipset) {
            return 720;
        }
        // 未来可扩展：4K 电视、高刷手机等
        return 1080;
    }

    public static boolean isTv() {
        return sIsTv;
    }

    public static boolean isOldTvChipset() {
        return sIsOldTvChipset;
    }

    /**
     * 是否应该强制使用软解（Android 系统 MediaCodec 软解器）。
     * 当前条件：老电视芯片 → 强制软解。
     */
    public static boolean shouldForceSoftwareCodec() {
        return sIsOldTvChipset;
    }

    /**
     * 运行时检测是否有不稳定的硬解器。
     * 通过 MediaCodecUtil 查询 H.264 硬解器列表，过滤已知缺陷项。
     */
    private static boolean detectFlakyHardwareCodec() {
        try {
            android.util.Log.d(TAG, "检测硬解器稳定性...");
            List<String> flakyPrefixes = new ArrayList<>();
            flakyPrefixes.add("omx.ms.");
            flakyPrefixes.add("c2.mstar.");
            flakyPrefixes.add("c2.amlogic.avc.decoder.awesome");
            flakyPrefixes.add("omx.hisi.video.decoder");

            List<?> codecs = androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                    .getDecoderInfos("video/avc", false, false);
            for (Object codec : codecs) {
                String name = (String) codec.getClass().getMethod("getName").invoke(codec);
                if (name == null) continue;
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                for (String prefix : flakyPrefixes) {
                    if (lower.startsWith(prefix)) {
                        android.util.Log.w(TAG, "发现不稳定硬解器: " + name);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "硬解器检测异常（忽略）: " + t.getMessage());
        }
        return false;
    }

    /**
     * 推荐的最大视频高度（720/1080/2160）。
     * 用于 HuyaSDKParser 在多个码率中选择最合适的。
     */
    public static int preferredTargetHeight() {
        return sPreferredTargetHeight;
    }

    /**
     * 兼容老接口：等价于 {@link #shouldForceSoftwareCodec()}
     */
    public static boolean isOldTvChipsetOrSoftwareCodecRequired() {
        return sIsOldTvChipset;
    }

    // ===================== 字符串辅助 =====================

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isEmpty()) return false;
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}