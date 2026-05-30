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

    // 触发系统投屏选择器的核心代码
    public void openCastPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            int routeType = MediaRouter.ROUTE_TYPE_LIVE_AUDIO | MediaRouter.ROUTE_TYPE_LIVE_VIDEO;
            // 强制切换到当前路由，系统会弹出选择界面
            mediaRouter.selectRoute(routeType, mediaRouter.getSelectedRoute(routeType));
        }
    }

    public void selectRoute(MediaRouter.RouteInfo route) {
        currentRoute = route;
    }

    // 断开投屏（仅标记状态，不调用会报错的系统API）
    public void disconnect() {
        currentRoute = null;
    }

    public boolean isCasting() {
        return currentRoute != null;
    }

    public String getCastDeviceName() {
        if (isCasting()) return currentRoute.getName().toString();
        return "未连接";
    }

    public void release() {
        currentRoute = null;
        instance = null;
    }
}
