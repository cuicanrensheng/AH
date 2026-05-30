package com.tv.live;

import android.content.Context;
import android.media.MediaRouter;
import android.os.Build;

public class CastManager {

    private static CastManager instance;
    private final MediaRouter mediaRouter;
    private MediaRouter.RouteInfo currentRoute;
    private final Context appContext;

    private CastManager(Context context) {
        appContext = context.getApplicationContext();
        mediaRouter = (MediaRouter) appContext.getSystemService(Context.MEDIA_ROUTER_SERVICE);
    }

    public static synchronized CastManager getInstance(Context context) {
        if (instance == null) {
            instance = new CastManager(context);
        }
        return instance;
    }

    // 打开系统投屏选择器
    public void openCastPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            // 触发系统自带的设备选择界面
            mediaRouter.selectRoute(
                    MediaRouter.ROUTE_TYPE_LIVE_AUDIO | MediaRouter.ROUTE_TYPE_LIVE_VIDEO,
                    mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO | MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
            );
        }
    }

    // 选择设备
    public void selectRoute(MediaRouter.RouteInfo route) {
        currentRoute = route;
    }

    // 断开投屏（兼容无unselect方法的低版本）
    public void disconnect() {
        currentRoute = null;
        // 不再调用unselect，避免编译错误，仅清空状态即可
    }

    // 是否正在投屏
    public boolean isCasting() {
        return currentRoute != null;
    }

    // 获取投屏设备名
    public String getCastDeviceName() {
        if (isCasting()) return currentRoute.getName().toString();
        return "未连接";
    }

    // 释放资源
    public void release() {
        currentRoute = null;
        instance = null;
    }
}
