package com.tv.live;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * 画中画(PIP)核心管理器
 * 作用：统一管理画中画的初始化、开关、状态回调、兼容性判断、生命周期处理
 * 设计：单例模式，全局唯一实例，避免重复创建
 * 
 * 集成说明：
 * - 参考 TVBox 项目 PlayActivity 的画中画实现
 * - 将画中画相关的状态判断、生命周期处理统一封装到此类
 * - Activity 只需调用此类的方法，无需自己写复杂的判断逻辑
 */
public class PictureInPictureManager {

    // ====================== 常量 ======================
    private static final String TAG = "PIPManager";

    // ====================== 单例相关 ======================
    private static PictureInPictureManager instance;
    private final Context appContext;

    // ====================== 开关与状态 ======================
    private boolean pipEnabled = false;      // 画中画功能总开关
    private boolean isInPipMode = false;     // 当前是否在画中画模式
    private boolean isPipEntering = false;   // 是否正在进入画中画（防重复触发）
    private boolean onStopCalled = false;    // onStop 是否已被调用（参考 TVBox 实现）

    // ====================== 监听回调 ======================
    private OnPipListener listener;

    // ====================================================================
    // 单例获取
    // ====================================================================
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

    // ====================================================================
    // 监听接口
    // ====================================================================
    /**
     * 画中画状态回调接口
     * 作用：监听画中画 进入/退出 状态，通知Activity更新UI/播放
     */
    public interface OnPipListener {
        // 画中画模式变化回调
        void onPipModeChanged(boolean inPip);
    }

    // ====================================================================
    // 基础能力判断
    // ====================================================================
    /**
     * 判断设备是否支持画中画
     * 要求：Android 8.0 (API 26) 及以上
     */
    public boolean isPipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    // ====================================================================
    // 开关控制
    // ====================================================================
    /**
     * 设置画中画开关状态
     * @param enabled true=开启 false=关闭
     */
    public void setPipEnabled(boolean enabled) {
        this.pipEnabled = enabled;
        Log.d(TAG, "画中画开关设置：" + (enabled ? "开启" : "关闭"));
    }

    /**
     * 获取当前画中画开关状态
     */
    public boolean isPipEnabled() {
        return pipEnabled;
    }

    // ====================================================================
    // 状态查询
    // ====================================================================
    /**
     * 当前是否在画中画模式
     */
    public boolean isInPipMode() {
        return isInPipMode;
    }

    /**
     * 是否正在进入画中画（防重复触发用）
     */
    public boolean isPipEntering() {
        return isPipEntering;
    }

    /**
     * 设置正在进入画中画的标记
     * @param entering 是否正在进入
     */
    public void setPipEntering(boolean entering) {
        this.isPipEntering = entering;
    }

    /**
     * 设置 onStop 标记（参考 TVBox 实现）
     * 作用：判断用户是返回应用还是关闭应用
     * @param stopCalled onStop 是否已被调用
     */
    public void setStopCalled(boolean stopCalled) {
        this.onStopCalled = stopCalled;
    }

    /**
     * 获取 onStop 标记
     */
    public boolean isStopCalled() {
        return onStopCalled;
    }

    // ====================================================================
    // 监听设置
    // ====================================================================
    /**
     * 设置画中画状态监听
     * @param listener 回调接口
     */
    public void setListener(OnPipListener listener) {
        this.listener = listener;
    }

    // ====================================================================
    // ✅ 新增：是否应该进入画中画的统一判断
    // 参考 TVBox PlayActivity 的 onUserLeaveHint 逻辑
    // ====================================================================
    /**
     * 判断是否应该进入画中画模式
     * 
     * 判断条件：
     * 1. 设备支持画中画
     * 2. 画中画开关已开启
     * 3. 当前不在画中画模式
     * 4. 没有正在进入画中画（防重复触发）
     * 5. 不是外部播放器（可选，默认 false）
     * 
     * @param isExternalPlayer 是否是外部播放器（外部播放器不进入画中画）
     * @return true=应该进入 false=不应该进入
     */
    public boolean shouldEnterPip(boolean isExternalPlayer) {
        // 条件1：设备支持
        if (!isPipSupported()) {
            Log.d(TAG, "不进入画中画：设备不支持");
            return false;
        }

        // 条件2：开关开启
        if (!pipEnabled) {
            Log.d(TAG, "不进入画中画：开关未开启");
            return false;
        }

        // 条件3：当前不在画中画模式
        if (isInPipMode) {
            Log.d(TAG, "不进入画中画：已在画中画模式");
            return false;
        }

        // 条件4：没有正在进入（防重复）
        if (isPipEntering) {
            Log.d(TAG, "不进入画中画：正在进入中");
            return false;
        }

        // 条件5：不是外部播放器
        if (isExternalPlayer) {
            Log.d(TAG, "不进入画中画：外部播放器");
            return false;
        }

        Log.d(TAG, "所有条件满足，可以进入画中画");
        return true;
    }

    /**
     * 重载方法：默认不是外部播放器
     */
    public boolean shouldEnterPip() {
        return shouldEnterPip(false);
    }

    // ====================================================================
    // 进入画中画
    // ====================================================================
    /**
     * 主动进入画中画模式
     * @param activity 当前页面
     * @param params 画中画参数（宽高比等）
     * @return 进入成功/失败
     */
    public boolean enterPictureInPicture(Activity activity, PictureInPictureParams params) {
        // 校验：设备支持 + 功能开启 + Activity有效
        if (!isPipSupported() || !pipEnabled || activity == null || activity.isFinishing()) {
            Log.d(TAG, "进入画中画失败：条件不满足");
            return false;
        }

        try {
            // 标记正在进入
            isPipEntering = true;

            // 系统API：进入画中画
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.enterPictureInPictureMode(params);
                Log.d(TAG, "进入画中画成功");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "进入画中画失败：" + e.getMessage(), e);
            isPipEntering = false;
        }

        return false;
    }

    // ====================================================================
    // ✅ 新增：处理 onPause（参考 TVBox PlayActivity 的 onPause 逻辑）
    // ====================================================================
    /**
     * 处理 onPause 生命周期
     * 
     * 逻辑：
     * - 画中画模式下：调用 resumeAction（继续播放）
     * - 普通模式下：调用 pauseAction（暂停播放）
     * 
     * 使用示例：
     * pipManager.handleOnPause(
     *     () -> mPlayerManager.resume(),   // 画中画模式：继续播放
     *     () -> mPlayerManager.pause()     // 普通模式：暂停播放
     * );
     * 
     * @param resumeAction 画中画模式下执行的操作（继续播放）
     * @param pauseAction 普通模式下执行的操作（暂停播放）
     */
    public void handleOnPause(Runnable resumeAction, Runnable pauseAction) {
        if (!isPipSupported()) {
            // 不支持画中画，直接暂停
            if (pauseAction != null) {
                pauseAction.run();
            }
            return;
        }

        if (isInPipMode || isPipEntering) {
            // ✅ 画中画模式下：继续播放（关键！防止黑屏）
            Log.d(TAG, "onPause：画中画模式，继续播放");
            if (resumeAction != null) {
                try {
                    resumeAction.run();
                } catch (Exception e) {
                    Log.e(TAG, "onPause 恢复播放失败：" + e.getMessage(), e);
                }
            }
        } else {
            // 普通模式下：暂停播放
            Log.d(TAG, "onPause：普通模式，暂停播放");
            if (pauseAction != null) {
                try {
                    pauseAction.run();
                } catch (Exception e) {
                    Log.e(TAG, "onPause 暂停播放失败：" + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 重载方法：只传画中画模式下的操作
     */
    public void handleOnPause(Runnable resumeAction) {
        handleOnPause(resumeAction, null);
    }

    // ====================================================================
    // 画中画模式变化回调
    // ====================================================================
    /**
     * 画中画模式变化时，回调给注册的监听
     * 
     * @param activity 当前页面
     * @param isInPip 是否进入画中画模式
     */
    public void onPipModeChanged(Activity activity, boolean isInPip) {
        // 更新状态
        this.isInPipMode = isInPip;
        this.isPipEntering = false;  // 进入完成，清除标记

        Log.d(TAG, "画中画模式变化：" + (isInPip ? "进入" : "退出"));

        // 回调给监听者
        if (listener != null) {
            try {
                listener.onPipModeChanged(isInPip);
            } catch (Exception e) {
                Log.e(TAG, "画中画模式变化回调失败：" + e.getMessage(), e);
            }
        }
    }

    // ====================================================================
    // ✅ 新增：处理退出画中画（参考 TVBox PlayActivity 的实现）
    // ====================================================================
    /**
     * 处理退出画中画的逻辑
     * 
     * 逻辑：
     * - 如果 onStopCalled 为 true（用户关闭了应用）：执行 releaseAction（释放播放器）
     * - 如果 onStopCalled 为 false（用户返回应用）：不释放，继续播放
     * 
     * 使用示例：
     * pipManager.handleExitPip(
     *     () -> mPlayerManager.release()  // 关闭应用时释放播放器
     * );
     * 
     * @param releaseAction 关闭应用时执行的释放操作
     */
    public void handleExitPip(Runnable releaseAction) {
        if (!isPipSupported()) {
            return;
        }

        if (onStopCalled) {
            // 用户关闭了应用：释放播放器
            Log.d(TAG, "退出画中画：应用已关闭，释放播放器");
            if (releaseAction != null) {
                try {
                    releaseAction.run();
                } catch (Exception e) {
                    Log.e(TAG, "释放播放器失败：" + e.getMessage(), e);
                }
            }
        } else {
            // 用户返回应用：不释放，继续播放
            Log.d(TAG, "退出画中画：返回应用，继续播放");
        }

        // 重置 onStop 标记
        onStopCalled = false;
    }

    // ====================================================================
    // 预留接口
    // ====================================================================
    /**
     * 更新播放状态（预留接口，用于同步播放状态）
     * @param isPlaying 是否正在播放
     */
    public void updatePlayState(boolean isPlaying) {
        // 预留：后续可扩展画中画自定义操作（暂停/播放按钮）
        Log.d(TAG, "更新播放状态：" + (isPlaying ? "播放中" : "已暂停"));
    }

    /**
     * 更新频道信息（预留接口，用于小窗显示频道信息）
     * @param num 频道号
     * @param name 频道名称
     * @param bitrate 码率
     */
    public void updateChannelInfo(int num, String name, String bitrate) {
        // 预留：后续可扩展画中画显示频道信息
        Log.d(TAG, "更新频道信息：" + num + " - " + name + " - " + bitrate);
    }

    // ====================================================================
    // 资源释放
    // ====================================================================
    /**
     * 释放资源（防止内存泄漏）
     */
    public void release() {
        listener = null;
        isInPipMode = false;
        isPipEntering = false;
        onStopCalled = false;
        Log.d(TAG, "PictureInPictureManager 资源已释放");
    }
}
