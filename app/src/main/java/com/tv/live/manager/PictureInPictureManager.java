package com.tv.live.manager;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.util.Rational;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * 画中画（PIP）管理类
 * 适配 Android 8.0 (API 26) 及以上系统
 */
public class PictureInPictureManager {
    private static final String TAG = "PipManager";
    private static PictureInPictureManager instance;

    private Context mContext;
    private boolean isPipEnabled = false; // 是否开启画中画功能
    private OnPipListener mListener;

    // 频道信息缓存
    private int mChannelNum;
    private String mChannelName;
    private String mBitrate;

    /**
     * 画中画回调接口
     */
    public interface OnPipListener {
        /**
         * 画中画模式下播放/暂停切换
         */
        void onPlayPause();

        /**
         * 画中画模式状态变化
         * @param inPip 是否进入画中画
         */
        void onPipModeChanged(boolean inPip);
    }

    private PictureInPictureManager(Context context) {
        this.mContext = context.getApplicationContext();
    }

    /**
     * 获取单例实例
     * @param context 上下文
     * @return PictureInPictureManager实例
     */
    public static PictureInPictureManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PictureInPictureManager.class) {
                if (instance == null) {
                    instance = new PictureInPictureManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * 检查设备是否支持画中画功能
     * @return true=支持，false=不支持
     */
    public boolean isPipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * 设置画中画功能是否启用
     * @param enabled 是否启用
     */
    public void setPipEnabled(boolean enabled) {
        this.isPipEnabled = enabled;
        Log.d(TAG, "画中画功能" + (enabled ? "已启用" : "已禁用"));
    }

    /**
     * 获取画中画功能启用状态
     * @return true=启用，false=禁用
     */
    public boolean isPipEnabled() {
        return isPipEnabled;
    }

    /**
     * 设置画中画回调监听
     * @param listener 回调监听
     */
    public void setListener(OnPipListener listener) {
        this.mListener = listener;
    }

    /**
     * 进入画中画模式
     * @param activity 当前Activity
     * @param pipParams 画中画参数（宽高比等）
     * @return true=进入成功，false=进入失败
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    public boolean enterPictureInPicture(Activity activity, PictureInPictureParams pipParams) {
        if (!isPipEnabled || !isPipSupported()) {
            Log.w(TAG, "画中画功能未启用或设备不支持");
            return false;
        }

        try {
            // 进入画中画前保持屏幕常亮
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            
            boolean result = activity.enterPictureInPictureMode(pipParams);
            Log.d(TAG, "进入画中画模式：" + (result ? "成功" : "失败"));
            
            if (mListener != null) {
                mListener.onPipModeChanged(true);
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "进入画中画模式异常", e);
            return false;
        }
    }

    /**
     * 画中画模式状态变化回调
     * @param activity 当前Activity
     * @param isInPictureInPictureMode 是否处于画中画模式
     */
    public void onPipModeChanged(Activity activity, boolean isInPictureInPictureMode) {
        Log.d(TAG, "画中画模式变化：" + (isInPictureInPictureMode ? "进入" : "退出"));

        // 退出画中画时恢复屏幕常亮
        if (!isInPictureInPictureMode) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        if (mListener != null) {
            mListener.onPipModeChanged(isInPictureInPictureMode);
        }
    }

    /**
     * 更新播放状态（适配MainActivity中移除isPlaying()的调用）
     * @param isPlaying 是否正在播放
     */
    public void updatePlayState(boolean isPlaying) {
        if (!isPipSupported() || !isPipEnabled) return;
        Log.d(TAG, "更新画中画播放状态：" + (isPlaying ? "播放中" : "已暂停"));
        // 可在此处更新画中画控制栏的播放/暂停按钮状态
    }

    /**
     * 更新频道信息到画中画
     * @param channelNum 频道号
     * @param channelName 频道名称
     * @param bitrate 码率
     */
    public void updateChannelInfo(int channelNum, @NonNull String channelName, @NonNull String bitrate) {
        if (!isPipSupported() || !isPipEnabled) return;
        
        this.mChannelNum = channelNum;
        this.mChannelName = channelName;
        this.mBitrate = bitrate;
        
        Log.d(TAG, "更新画中画频道信息：频道" + channelNum + " " + channelName + " 码率：" + bitrate);
        
        // Android 12+ 可通过PictureInPictureParams更新画中画窗口的元数据
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Activity activity = (Activity) mContext;
                if (activity.isInPictureInPictureMode()) {
                    PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                    // 设置宽高比（16:9）
                    builder.setAspectRatio(new Rational(16, 9));
                    // 可在此处添加自定义操作或元数据
                    activity.setPictureInPictureParams(builder.build());
                }
            } catch (Exception e) {
                Log.e(TAG, "更新画中画频道信息异常", e);
            }
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        mContext = null;
        mListener = null;
        instance = null;
        Log.d(TAG, "画中画管理器已释放");
    }
}
