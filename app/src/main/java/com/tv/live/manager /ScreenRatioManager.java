package com.tv.live.manager;

import com.tv.live.TVPlayerManager;
import com.tv.live.config.AppConfig;

/**
 * 画面比例管理器
 * 作用：根据配置切换 原始/填充 两种画面模式
 */
public class ScreenRatioManager {

    // 播放器管理器
    private final TVPlayerManager mPlayerManager;

    // 配置文件
    private final AppConfig appConfig;

    /**
     * 构造方法
     * @param playerManager 播放器实例
     * @param config 应用配置
     */
    public ScreenRatioManager(TVPlayerManager playerManager, AppConfig config) {
        this.mPlayerManager = playerManager;
        this.appConfig = config;
    }

    /**
     * 应用画面比例设置
     * 原始 = FIT（适应）
     * 填充 = FILL（铺满）
     * 默认 = 填充
     */
    public void apply() {
        String ratio = appConfig.getScreenRatio();

        // 空值安全处理
        if (ratio == null) ratio = "填充";

        switch (ratio) {
            case "原始":
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT);
                break;
            case "填充":
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
            default:
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
        }
    }
}
