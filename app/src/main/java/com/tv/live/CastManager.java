package com.tv.live;

import android.content.Context;
import android.media.MediaRouter;
import android.os.Build;

public class CastManager {

    private static CastManager instance;
    private final MediaRouter mediaRouter;
    private MediaRouter.RouteInfo currentRoute;
    private final Context appContext;
    private MediaRouter.Callback mediaRouterCallback;

    private CastManager(Context context) {
        appContext = context.getApplicationContext();
        mediaRouter = (MediaRouter) appContext.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        initMediaRouterCallback();
    }

    public static synchronized CastManager getInstance(Context context) {
        if (instance == null) {
            instance = new CastManager(context);
        }
        return instance;
    }

    // 初始化投屏回调，监听设备连接/断开
    private void initMediaRouterCallback() {
        mediaRouterCallback = new MediaRouter.Callback() {
            @Override
            public void onRouteSelected(MediaRouter router, int type, MediaRouter.RouteInfo info) {
                super.onRouteSelected(router, type, info);
                currentRoute = info;
            }

            @Override
            public void onRouteUnselected(MediaRouter router, int type, MediaRouter.RouteInfo info) {
                super.onRouteUnselected(router, type, info);
                if (info.equals(currentRoute)) {
                    currentRoute = null;
                }
            }
        };
        // 注册回调，监听所有类型的路由设备
        mediaRouter.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO | MediaRouter.ROUTE_TYPE_LIVE_VIDEO,
                mediaRouterCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
    }

    // 打开系统投屏选择器
    public void openCastPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            mediaRouter.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO | MediaRouter.ROUTE_TYPE_LIVE_VIDEO,
                    mediaRouter.getRouteAt(0));
        }
    }

    // 手动选择设备
    public void selectRoute(MediaRouter.RouteInfo route) {
        currentRoute = route;
        mediaRouter.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO | MediaRouter.ROUTE_TYPE_LIVE_VIDEO, route);
    }

    // 断开投屏
    public void disconnect() {
        if (currentRoute != null) {
            mediaRouter.unselect(MediaRouter.ROUTE_TYPE_LIVE_AUDIO | MediaRouter.ROUTE_TYPE_LIVE_VIDEO);
            currentRoute = null;
        }
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

    // 释放资源（在Activity的onDestroy中调用）
    public void release() {
        if (mediaRouter != null && mediaRouterCallback != null) {
            mediaRouter.removeCallback(mediaRouterCallback);
        }
        instance = null;
    }
}
