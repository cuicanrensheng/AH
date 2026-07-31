package com.tv.live.manager;

import com.tv.live.TVPlayerManager;
import com.tv.live.config.AppConfig;

public class ScreenRatioManager {

    private final TVPlayerManager mPlayerManager;
    private final AppConfig appConfig;
    
    // 🟢 缓存当前生效的比例，避免重复调用播放器
    private String currentAppliedRatio = "";

    public ScreenRatioManager(TVPlayerManager playerManager, AppConfig config) {
        this.mPlayerManager = playerManager;
        this.appConfig = config;
    }

    public void apply() {
        String ratio = appConfig.getScreenRatio();
        
        // 🟢【核心优化】如果设置的比例和当前应用的比例一致，直接跳过
        if (ratio.equals(currentAppliedRatio)) {
            return; 
        }
        
        switch (ratio) {
            case "原始":
                // 🟢 原始：保持视频原始比例，如果不符合屏幕比例会出现黑边
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT);
                break;
            case "填充":
                // 🟢 填充：等比放大填满屏幕，可能会有部分画面被裁剪（类似 ZOOM 效果）
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM);
                break;
            case "全屏":
                // 🟢 全屏：等比缩放到满屏（画面完整显示，填满屏幕底部），无黑边
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
            default:
                // 🟢 默认兜底：全屏模式
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
        }
        
        // 🟢 记录下本次生效的比例
        currentAppliedRatio = ratio;
    }
}
