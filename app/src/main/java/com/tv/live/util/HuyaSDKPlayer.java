package com.tv.live.util;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 虎牙 SDK 原生播放器封装
 *
 * 使用虎牙 SDK 内部播放器直接处理直播流，无需提取 URL 再交给 ExoPlayer。
 * SDK 原生播放器优势：
 * - 内置 CDN 鉴权，不会出现 403 错误
 * - 自动选择最优线路和码率
 * - 内置弹幕、画质切换等功能
 *
 * 使用反射调用，避免 ProGuard 混淆问题。
 */
public class HuyaSDKPlayer {

    private static final String TAG = "HuyaSDKPlayer";

    private Object mPlayerView;  // com.huya.berry.sdkplayer.floats.view.PlayerView
    private FrameLayout mContainer;
    private Context mContext;
    private boolean mIsPlaying = false;
    private boolean mSdkPlayerAvailable = false;

    // 反射缓存
    private static Class<?> sPlayerViewClass;
    private static Class<?> sStreamInfoHelperClass;
    private static Class<?> sLivePlayerClass;
    private static Class<?> sHYMediaSoftDecodePlayerClass;
    private static Class<?> sHyberryVideoViewClass;
    private static boolean sClassesLoaded = false;

    // 方法缓存
    private static Method sStartPlayMethod;
    private static Method sOnResumeMethod;
    private static Method sOnPauseMethod;
    private static Method sOnDestroyMethod;
    private static Method sStopPlayMethod;
    private static Method sSetMuteMethod;
    private static Method sCreatePlayerMethod;
    private static Method sHybridStartPlayMethod;
    private static Method sGetInterfaceMethod;
    private static Method sSetPlayConfigMethod;
    private static Constructor<?> sPlayerViewConstructor;
    private static Constructor<?> sHYMediaPlayerConstructor;
    private static Constructor<?> sHyberryVideoViewConstructor;

    public interface OnSDKPlayerListener {
        void onPlayStarted();
        void onPlayStopped();
        void onPlayError(String error);
        default void onPlayEnded() {}
    }

    private OnSDKPlayerListener mListener;

    public HuyaSDKPlayer(Context context) {
        mContext = context;
        checkSDKAvailability();
    }

    public void setOnSDKPlayerListener(OnSDKPlayerListener listener) {
        mListener = listener;
    }

    private void checkSDKAvailability() {
        if (sClassesLoaded) {
            // 类已加载，但需要确保构造器和方法也已缓存
            // isSDKPlayerAvailable() 可能只加载了类而未缓存方法
            if (sPlayerViewConstructor == null && sPlayerViewClass != null) {
                cachePlayerViewMethods();
            }
            if (sHYMediaPlayerConstructor == null && sHYMediaSoftDecodePlayerClass != null) {
                cacheHybridPlayerMethods();
            }
            mSdkPlayerAvailable = (sPlayerViewClass != null) || (sHYMediaSoftDecodePlayerClass != null);
            return;
        }
        sClassesLoaded = true;
        try {
            sPlayerViewClass = Class.forName("com.huya.berry.sdkplayer.floats.view.PlayerView");
            sHyberryVideoViewClass = Class.forName("com.huya.berry.sdkplayer.floats.view.HyberryVideoView");
            sStreamInfoHelperClass = Class.forName("com.huya.berry.sdkplayer.player.StreamInfoHelper");
            sLivePlayerClass = Class.forName("com.huya.berry.sdkplayer.player.LivePlayer");
            sHYMediaSoftDecodePlayerClass = Class.forName("com.huya.berry.sdkplayer.player.HYMediaSoftDecodePlayer");

            cachePlayerViewMethods();
            cacheHybridPlayerMethods();
            cacheStreamInfoHelperMethods();

            if (sHyberryVideoViewClass != null) {
                sHyberryVideoViewConstructor = sHyberryVideoViewClass.getConstructor(Context.class);
            }

            mSdkPlayerAvailable = (sPlayerViewClass != null) || (sHYMediaSoftDecodePlayerClass != null);
            Log.d(TAG, "SDK 播放器类加载成功: PlayerView=" + (sPlayerViewClass != null)
                    + " HYMediaSoftDecodePlayer=" + (sHYMediaSoftDecodePlayerClass != null)
                    + " StreamInfoHelper=" + (sStreamInfoHelperClass != null)
                    + " playerAvail=" + mSdkPlayerAvailable);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "SDK 播放器类未找到: " + e.getMessage());
            mSdkPlayerAvailable = false;
        } catch (Exception e) {
            Log.e(TAG, "SDK 播放器初始化异常", e);
            mSdkPlayerAvailable = false;
        }
    }

    private void cachePlayerViewMethods() {
        if (sPlayerViewClass == null) return;

        // 打印所有构造器
        Log.d(TAG, "=== PlayerView 构造器列表 ===");
        for (Constructor<?> c : sPlayerViewClass.getConstructors()) {
            Log.d(TAG, "  Constructor: " + c);
        }

        // 打印所有方法
        Log.d(TAG, "=== PlayerView 方法列表 ===");
        for (Method m : sPlayerViewClass.getMethods()) {
            StringBuilder sb = new StringBuilder("  Method: " + m.getName() + "(");
            Class<?>[] params = m.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getSimpleName());
            }
            sb.append(") -> ").append(m.getReturnType().getSimpleName());
            Log.d(TAG, sb.toString());
        }

        // 🔴【新增】搜索 PlayerView 实现的接口（回调相关）
        Log.d(TAG, "=== PlayerView 实现的接口 ===");
        Class<?>[] interfaces = sPlayerViewClass.getInterfaces();
        for (Class<?> iface : interfaces) {
            Log.d(TAG, "  Interface: " + iface.getName());
            // 打印接口的所有方法
            for (Method im : iface.getMethods()) {
                Log.d(TAG, "    Method: " + im);
            }
        }
        // 也打印父类的接口
        Class<?> superClass = sPlayerViewClass.getSuperclass();
        while (superClass != null) {
            Class<?>[] superInterfaces = superClass.getInterfaces();
            for (Class<?> iface : superInterfaces) {
                Log.d(TAG, "  Super-Interface(" + superClass.getSimpleName() + "): " + iface.getName());
                for (Method im : iface.getMethods()) {
                    Log.d(TAG, "    Method: " + im);
                }
            }
            superClass = superClass.getSuperclass();
        }

        // 🔴【新增】搜索 setListener / setCallback / addListener 等方法
        Log.d(TAG, "=== PlayerView 监听器/回调方法 ===");
        for (Method m : sPlayerViewClass.getMethods()) {
            String name = m.getName();
            if (name.contains("Listener") || name.contains("Callback") || name.contains("listener") || name.contains("callback")
                    || name.startsWith("setOn") || name.startsWith("addOn") || name.startsWith("setPlayer") || name.startsWith("setVideo")) {
                Log.d(TAG, "  Listener method: " + m);
            }
        }

        // 尝试初始化
        try {
            sPlayerViewConstructor = sPlayerViewClass.getConstructor(Context.class);
            Log.d(TAG, "PlayerView Constructor(Context) found");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "PlayerView Constructor(Context) not found, searching...");
            for (Constructor<?> c : sPlayerViewClass.getConstructors()) {
                sPlayerViewConstructor = c;
                Log.d(TAG, "Using PlayerView constructor: " + c);
                break;
            }
        }
        try {
            sStartPlayMethod = sPlayerViewClass.getMethod("startPlay", long.class);
            Log.d(TAG, "PlayerView.startPlay(long) found");
        } catch (NoSuchMethodException e) {
            // 尝试其他签名
            for (Method m : sPlayerViewClass.getMethods()) {
                if (m.getName().equals("startPlay")) {
                    sStartPlayMethod = m;
                    Log.d(TAG, "Found startPlay method: " + m);
                    break;
                }
            }
        }
        if (sStartPlayMethod == null) {
            Log.w(TAG, "PlayerView.startPlay not found");
        }
        try { sOnResumeMethod = sPlayerViewClass.getMethod("onResume"); } catch (NoSuchMethodException ignored) {}
        try { sOnPauseMethod = sPlayerViewClass.getMethod("onPause"); } catch (NoSuchMethodException ignored) {}
        try { sOnDestroyMethod = sPlayerViewClass.getMethod("onDestroy"); } catch (NoSuchMethodException ignored) {}
        try { sStopPlayMethod = sPlayerViewClass.getMethod("stopPlay"); } catch (NoSuchMethodException ignored) {}
        try { sSetMuteMethod = sPlayerViewClass.getMethod("setMuteAudio", boolean.class); } catch (NoSuchMethodException ignored) {}
    }

    private void cacheHybridPlayerMethods() {
        if (sHYMediaSoftDecodePlayerClass == null) return;
        try {
            sHYMediaPlayerConstructor = sHYMediaSoftDecodePlayerClass.getConstructor();
        } catch (NoSuchMethodException e) {
            for (Constructor<?> c : sHYMediaSoftDecodePlayerClass.getConstructors()) {
                sHYMediaPlayerConstructor = c;
                break;
            }
        }
        try {
            sCreatePlayerMethod = sHYMediaSoftDecodePlayerClass.getMethod("createPlayer",
                    sHyberryVideoViewClass);
        } catch (NoSuchMethodException e) {
            for (Method m : sHYMediaSoftDecodePlayerClass.getMethods()) {
                if (m.getName().equals("createPlayer")) {
                    sCreatePlayerMethod = m;
                    break;
                }
            }
        }
        try {
            sHybridStartPlayMethod = sHYMediaSoftDecodePlayerClass.getMethod("startPlay");
        } catch (NoSuchMethodException ignored) {}
    }

    private void cacheStreamInfoHelperMethods() {
        if (sStreamInfoHelperClass == null) return;
        try {
            sGetInterfaceMethod = sStreamInfoHelperClass.getMethod("getInterfaceById", int.class);
        } catch (NoSuchMethodException e) {
            for (Method m : sStreamInfoHelperClass.getMethods()) {
                if (m.getName().equals("getInterfaceById")) {
                    sGetInterfaceMethod = m;
                    break;
                }
            }
        }
        try {
            sSetPlayConfigMethod = sStreamInfoHelperClass.getMethod("setPlayConfig",
                    String.class, String.class, String.class, String.class,
                    int.class, int.class, int.class, int.class, int.class, int.class);
        } catch (NoSuchMethodException e) {
            for (Method m : sStreamInfoHelperClass.getMethods()) {
                if (m.getName().equals("setPlayConfig")) {
                    sSetPlayConfigMethod = m;
                    break;
                }
            }
        }
    }

    public boolean isAvailable() {
        return mSdkPlayerAvailable;
    }

    /**
     * 静态方法：检查 SDK 播放器是否可用（同时触发类加载）
     */
    public static boolean isSDKPlayerAvailable() {
        if (!sClassesLoaded) {
            // 触发完整的类加载
            try {
                Class.forName("com.huya.berry.sdkplayer.floats.view.PlayerView");
                sClassesLoaded = true;
            } catch (ClassNotFoundException e) {
                // 尝试其他类
                try {
                    Class.forName("com.huya.berry.sdkplayer.player.HYMediaSoftDecodePlayer");
                    sClassesLoaded = true;
                } catch (ClassNotFoundException e2) {
                    return false;
                }
            }
        }
        // 确保类已加载
        if (sPlayerViewClass == null && sHYMediaSoftDecodePlayerClass == null) {
            // 重新检查 - 可能在其他线程中已被加载
            try {
                if (sPlayerViewClass == null) {
                    sPlayerViewClass = Class.forName("com.huya.berry.sdkplayer.floats.view.PlayerView");
                }
            } catch (ClassNotFoundException ignored) {}
            try {
                if (sHYMediaSoftDecodePlayerClass == null) {
                    sHYMediaSoftDecodePlayerClass = Class.forName("com.huya.berry.sdkplayer.player.HYMediaSoftDecodePlayer");
                }
            } catch (ClassNotFoundException ignored) {}
        }
        return sPlayerViewClass != null || sHYMediaSoftDecodePlayerClass != null;
    }

    /**
     * 使用 SDK 原生播放器播放虎牙直播
     */
    public boolean play(int roomId, FrameLayout container, Activity activity) {
        if (!mSdkPlayerAvailable) {
            Log.w(TAG, "SDK 播放器不可用");
            return false;
        }

        mContainer = container;

        try {
            releasePlayer();

            // 确保构造器和方法已缓存
            if (sPlayerViewClass != null && (sPlayerViewConstructor == null || sStartPlayMethod == null)) {
                cachePlayerViewMethods();
            }
            if (sHYMediaSoftDecodePlayerClass != null && (sHYMediaPlayerConstructor == null || sCreatePlayerMethod == null)) {
                cacheHybridPlayerMethods();
            }
            if (sHyberryVideoViewClass != null && sHyberryVideoViewConstructor == null) {
                sHyberryVideoViewConstructor = sHyberryVideoViewClass.getConstructor(Context.class);
            }

            // 方式1: PlayerView (推荐 - 更完整的功能)
            if (sPlayerViewClass != null) {
                Log.d(TAG, "尝试 PlayerView 方式: constructor=" + (sPlayerViewConstructor != null) + " startPlay=" + (sStartPlayMethod != null));
                if (sPlayerViewConstructor != null && sStartPlayMethod != null) {
                    return playWithPlayerView(roomId, container, activity);
                }
            }

            // 方式2: HyberryVideoView + StreamInfoHelper + HYMediaSoftDecodePlayer
            if (sHYMediaSoftDecodePlayerClass != null && sHyberryVideoViewClass != null) {
                Log.d(TAG, "尝试 Hybrid 方式: videoViewConstructor=" + (sHyberryVideoViewConstructor != null) + " playerConstructor=" + (sHYMediaPlayerConstructor != null) + " createPlayer=" + (sCreatePlayerMethod != null));
                if (sHyberryVideoViewConstructor != null && sHYMediaPlayerConstructor != null
                        && sCreatePlayerMethod != null && sHybridStartPlayMethod != null) {
                    return playWithHybridPlayer(roomId, container);
                }
            }

            Log.w(TAG, "没有可用的 SDK 播放器组件 (类存在但方法/构造器缺失)");
            return false;

        } catch (Exception e) {
            Log.e(TAG, "SDK 播放器启动异常", e);
            if (mListener != null) {
                mListener.onPlayError("SDK 播放器异常: " + e.getMessage());
            }
            return false;
        }
    }

    private boolean playWithPlayerView(int roomId, FrameLayout container, Activity activity) {
        try {
            mPlayerView = sPlayerViewConstructor.newInstance(activity);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            ((View) mPlayerView).setLayoutParams(params);
            container.addView((View) mPlayerView);

            // 🔴【关键修复1】捕获 startPlay 返回值，判断是否成功
            Object result = sStartPlayMethod.invoke(mPlayerView, roomId);
            Log.d(TAG, "SDK startPlay(" + roomId + ") 返回值: " + result
                    + " (type=" + (result != null ? result.getClass().getSimpleName() : "null") + ")");

            int playStatus = -1;
            boolean playOk = false;
            if (result instanceof Number) {
                playStatus = ((Number) result).intValue();
                playOk = (playStatus == 0); // 0 = 成功
            } else if (result instanceof Boolean) {
                playOk = (Boolean) result;
                playStatus = playOk ? 0 : -1;
            } else if (result == null) {
                // void 返回值，无法判断，假设成功
                playOk = true;
                playStatus = 0;
            }

            if (!playOk) {
                Log.w(TAG, "SDK startPlay 返回非成功状态: " + playStatus + "，可能房间未开播或流获取失败");
                if (mListener != null) {
                    mListener.onPlayError("SDK startPlay 返回: " + playStatus + " (可能房间未开播)");
                }
                return false;
            }

            // 🔴【关键修复2】启动后立即调用 onResume，触发 SDK 内部渲染线程
            if (sOnResumeMethod != null) {
                try {
                    sOnResumeMethod.invoke(mPlayerView);
                    Log.d(TAG, "SDK PlayerView onResume 已调用，渲染线程启动");
                } catch (Exception e) {
                    Log.w(TAG, "onResume 调用失败: " + e.getMessage());
                }
            }

            mIsPlaying = true;
            Log.d(TAG, "SDK PlayerView 启动成功, roomId=" + roomId + " (status=" + playStatus + ")");

            // 🔴【关键修复3】startPlay 返回成功，立即回调 onPlayStarted
            // TVPlayerManager 会在收到回调后取消 8秒超时
            // 若 SDK 内部渲染失败（黑屏），8秒超时会兜底回退到 PureParser
            if (mListener != null) {
                mListener.onPlayStarted();
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "PlayerView 方式失败，尝试降级方案", e);
            if (mPlayerView != null) {
                try {
                    container.removeView((View) mPlayerView);
                } catch (Exception ignored) {}
                mPlayerView = null;
            }
            // 降级到 Hybrid 方案
            if (sHyberryVideoViewClass != null && sHYMediaSoftDecodePlayerClass != null) {
                return playWithHybridPlayer(roomId, container);
            }
            return false;
        }
    }

    private boolean playWithHybridPlayer(int roomId, FrameLayout container) {
        try {
            // 1. 创建 HyberryVideoView
            Object videoView = sHyberryVideoViewConstructor.newInstance(mContext);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            ((View) videoView).setLayoutParams(params);
            container.addView((View) videoView);

            // 2. 配置 StreamInfoHelper
            if (sStreamInfoHelperClass != null && sGetInterfaceMethod != null && sSetPlayConfigMethod != null) {
                Object streamInfoHelper = sGetInterfaceMethod.invoke(null, 0);
                if (streamInfoHelper != null) {
                    sSetPlayConfigMethod.invoke(streamInfoHelper,
                            String.valueOf(roomId),
                            "", "", "",
                            0, 0, 0, 0, 0, 0);
                    Log.d(TAG, "StreamInfoHelper 配置完成, roomId=" + roomId);
                }
            }

            // 3. 创建 HYMediaSoftDecodePlayer
            Object player = sHYMediaPlayerConstructor.newInstance();
            sCreatePlayerMethod.invoke(player, videoView);

            // 4. 启动播放
            sHybridStartPlayMethod.invoke(player);

            mPlayerView = videoView; // 用于后续生命周期管理
            mIsPlaying = true;
            Log.d(TAG, "SDK Hybrid 播放器启动成功, roomId=" + roomId);

            if (mListener != null) {
                mListener.onPlayStarted();
            }
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Hybrid 播放器方式失败", e);
            if (mListener != null) {
                mListener.onPlayError("SDK 播放器失败: " + e.getMessage());
            }
            return false;
        }
    }

    public void pause() {
        if (mPlayerView != null && sOnPauseMethod != null) {
            try {
                sOnPauseMethod.invoke(mPlayerView);
                mIsPlaying = false;
                Log.d(TAG, "SDK 播放器已暂停");
            } catch (Exception e) {
                Log.e(TAG, "暂停失败", e);
            }
        }
    }

    public void resume() {
        if (mPlayerView != null && sOnResumeMethod != null) {
            try {
                sOnResumeMethod.invoke(mPlayerView);
                mIsPlaying = true;
                Log.d(TAG, "SDK 播放器已恢复");
            } catch (Exception e) {
                Log.e(TAG, "恢复失败", e);
            }
        }
    }

    public void stop() {
        releasePlayer();
    }

    public void setMute(boolean mute) {
        if (mPlayerView != null && sSetMuteMethod != null) {
            try {
                sSetMuteMethod.invoke(mPlayerView, mute);
            } catch (Exception e) {
                Log.e(TAG, "设置静音失败", e);
            }
        }
    }

    private void releasePlayer() {
        if (mPlayerView != null) {
            try {
                if (sOnDestroyMethod != null) {
                    sOnDestroyMethod.invoke(mPlayerView);
                } else if (sStopPlayMethod != null) {
                    sStopPlayMethod.invoke(mPlayerView);
                }

                if (mContainer != null) {
                    mContainer.removeView((View) mPlayerView);
                }
                Log.d(TAG, "SDK 播放器已释放");
            } catch (Exception e) {
                Log.e(TAG, "释放播放器异常", e);
            }
            mPlayerView = null;
        }
        mIsPlaying = false;
    }

    public void onDestroy() {
        releasePlayer();
        mContainer = null;
        mContext = null;
        mListener = null;
    }

    /**
     * 释放所有资源
     */
    public void release() {
        releasePlayer();
        mListener = null;
        mContext = null;
    }

    /**
     * 🔴【新增】获取 PlayerView 第一个子视图的类名
     * 用于判断是否是真正的视频渲染视图（SurfaceView/TextureView）
     */
    public String getFirstChildClassName() {
        if (mPlayerView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) mPlayerView;
            if (vg.getChildCount() > 0) {
                return vg.getChildAt(0).getClass().getName();
            }
        }
        return "none";
    }

    /**
     * 检查 PlayerView 是否包含视频渲染视图（SurfaceView/TextureView）
     */
    public boolean hasVideoRenderView() {
        if (mPlayerView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) mPlayerView;
            for (int i = 0; i < vg.getChildCount(); i++) {
                String className = vg.getChildAt(i).getClass().getName();
                if (className.contains("SurfaceView") || className.contains("TextureView")
                        || className.contains("Surface")) {
                    return true;
                }
            }
        }
        return false;
    }

    public void onPause() {
        pause();
    }

    public void onResume() {
        resume();
    }

    public void onForeground() {
        resume();
    }

    public void onBackground() {
        pause();
    }

    public boolean isPlaying() {
        return mIsPlaying;
    }

    public boolean hasPlayerView() {
        return mPlayerView != null;
    }
}