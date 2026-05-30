package com.tv.live.manager;

import com.tv.live.TVPlayerManager;
import com.tv.live.config.AppConfig;

public class ScreenRatioManager {

    private final TVPlayerManager mPlayerManager;
    private final AppConfig appConfig;

    public ScreenRatioManager(TVPlayerManager playerManager, AppConfig config) {
        this.mPlayerManager = playerManager;
        this.appConfig = config;
    }

    public void apply() {
        String ratio = appConfig.getScreenRatio();
        switch (ratio) {
            case "原始":
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT);
                break;
            case "填充":
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
            default:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.ZOOM);
                break;
        }
    }
}
