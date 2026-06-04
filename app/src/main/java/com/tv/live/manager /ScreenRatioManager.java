package com.tv.live.manager;

import com.tv.live.TVPlayerManager;
import com.tv.live.config.AppConfig;

/**
 * 画面比例管理器（已修复：切换立即生效）
 */
public class ScreenRatioManager {

    private final TVPlayerManager mPlayerManager;
    private final AppConfig appConfig;

    public ScreenRatioManager(TVPlayerManager playerManager, AppConfig config) {
        this.mPlayerManager = playerManager;
        this.appConfig = config;
    }

    /**
     * 应用画面比例 → 修复：切换后立即刷新生效
     */
    public void apply() {
        String ratio = appConfig.getScreenRatio();
        if (ratio == null) ratio = "填充";

        // 1. 设置比例模式
        switch (ratio) {
            case "原始":
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT);
                break;
            case "填充":
            default:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
        }

        // 2. ✅ 修复关键：强制刷新画面
        if (mPlayerManager.getPlayerView() != null) {
            mPlayerManager.getPlayerView().requestLayout();
            mPlayerManager.getPlayerView().invalidate();
        }
    }
}
