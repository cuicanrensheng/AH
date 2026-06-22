package com.tv.live.manager;


import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.util.Rational;

public class PictureInPictureManager {
    private static final String TAG = "PIPManager";
    private static PictureInPictureManager instance;

    private final Context appContext;
    private boolean pipEnabled = false;
    private OnPipListener listener;

    // 单例
    public static PictureInPictureManager getInstance(Context context) {
        if (instance == null) {
            instance = new PictureInPictureManager(context.getApplicationContext());
        }
        return instance;
    }

    private PictureInPictureManager(Context context) {
        this.appContext = context;
    }

    // 回调接口
    public interface OnPipListener {
        void onPipModeChanged(boolean inPip);
    }

    // 检查支持
    public boolean isPipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    // 开关
    public void setPipEnabled(boolean enabled) {
        this.pipEnabled = enabled;
    }

    public boolean isPipEnabled() {
        return pipEnabled;
    }

    // 设置监听
    public void setListener(OnPipListener listener) {
        this.listener = listener;
    }

    // 更新播放状态（仅记录，不操作）
    public void updatePlayState(boolean isPlaying) {}

    // 更新频道信息
    public void updateChannelInfo(int num, String name, String bitrate) {}

    // 进入画中画
    public boolean enterPictureInPicture(Activity activity, PictureInPictureParams params) {
        if (!isPipSupported() || !pipEnabled || activity == null || activity.isFinishing()) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.enterPictureInPictureMode(params);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "进入画中画失败", e);
        }
        return false;
    }

    // 模式变化
    public void onPipModeChanged(Activity activity, boolean isInPip) {
        if (listener != null) {
            listener.onPipModeChanged(isInPip);
        }
    }

    // 释放
    public void release() {
        listener = null;
    }
}
