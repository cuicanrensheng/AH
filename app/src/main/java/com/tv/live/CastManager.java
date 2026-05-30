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

    public static CastManager getInstance(Context context) {
        if (instance == null) {
            instance = new CastManager(context);
        }
        return instance;
    }

    // 打开系统投屏选择器（兼容写法）
    public void openCastPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            // 正确方式：通过 MediaRouter 打开选择器
            mediaRouter.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
        }
    }

    // 选择设备
    public void selectRoute(MediaRouter.RouteInfo route) {
        if (currentRoute != null) {
            // 断开旧连接
            mediaRouter.unselect(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
        }
        currentRoute = route;
        if (route != null) {
            // 连接新设备
            mediaRouter.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, route);
        }
    }

    // 断开投屏
    public void disconnect() {
        if (currentRoute != null) {
            mediaRouter.unselect(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
            currentRoute = null;
        }
    }

    // 是否正在投屏
    public boolean isCasting() {
        return currentRoute != null && currentRoute.isSelected();
    }

    // 获取投屏设备名
    public String getCastDeviceName() {
        if (isCasting()) return currentRoute.getName().toString();
        return "未连接";
    }
}
