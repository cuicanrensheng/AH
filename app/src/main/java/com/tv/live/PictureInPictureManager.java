package com.tv.live;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * 画中画(PIP)核心管理器
 * 作用：统一管理画中画的初始化、开关、状态回调、兼容性判断
 * 设计：单例模式，全局唯一实例，避免重复创建
 */
public class PictureInPictureManager {
    // 日志TAG
    private static final String TAG = "PIPManager";
    // 单例实例
    private static PictureInPictureManager instance;

    // 应用上下文（全局，防止内存泄漏）
    private final Context appContext;
    // 画中画功能总开关
    private boolean pipEnabled = false;
    // 画中画状态监听回调
    private OnPipListener listener;

    /**
     * 获取单例实例（对外暴露）
     * @param context 上下文
     * @return PictureInPictureManager 唯一实例
     */
    public static PictureInPictureManager getInstance(Context context) {
        if (instance == null) {
            instance = new PictureInPictureManager(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * 私有构造方法（单例模式，禁止外部new）
     * @param context 应用上下文
     */
    private PictureInPictureManager(Context context) {
        this.appContext = context;
    }

    /**
     * 画中画状态回调接口
     * 作用：监听画中画 进入/退出 状态，通知Activity更新UI/播放
     */
    public interface OnPipListener {
        // 画中画模式变化回调
        void onPipModeChanged(boolean inPip);
    }

    /**
     * 判断设备是否支持画中画
     * 要求：Android 8.0 (API 26) 及以上
     */
    public boolean isPipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * 设置画中画开关状态
     * @param enabled true=开启 false=关闭
     */
    public void setPipEnabled(boolean enabled) {
        this.pipEnabled = enabled;
    }

    /**
     * 获取当前画中画开关状态
     */
    public boolean isPipEnabled() {
        return pipEnabled;
    }

    /**
     * 设置画中画状态监听
     * @param listener 回调接口
     */
    public void setListener(OnPipListener listener) {
        this.listener = listener;
    }

    /**
     * 更新播放状态（预留接口，用于同步播放状态）
     */
    public void updatePlayState(boolean isPlaying) {}

    /**
     * 更新频道信息（预留接口，用于小窗显示频道信息）
     */
    public void updateChannelInfo(int num, String name, String bitrate) {}

    /**
     * 主动进入画中画模式
     * @param activity 当前页面
     * @param params 画中画参数（宽高比等）
     * @return 进入成功/失败
     */
    public boolean enterPictureInPicture(Activity activity, PictureInPictureParams params) {
        // 校验：设备支持 + 功能开启 + Activity有效
        if (!isPipSupported() || !pipEnabled || activity == null || activity.isFinishing()) {
            return false;
        }
        try {
            // 系统API：进入画中画
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.enterPictureInPictureMode(params);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "进入画中画失败", e);
        }
        return false;
    }

    /**
     * 画中画模式变化时，回调给注册的监听
     */
    public void onPipModeChanged(Activity activity, boolean isInPip) {
        if (listener != null) {
            listener.onPipModeChanged(isInPip);
        }
    }

    /**
     * 释放资源（防止内存泄漏）
     */
    public void release() {
        listener = null;
    }
}
